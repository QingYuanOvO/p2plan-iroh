package com.p2plan.iroh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

/**
 * 把 iroh 的 QUIC 流桥接到本地 TCP：MC 只认 TCP，iroh 只给 QUIC。
 *
 * <ul>
 *   <li><b>房主侧</b>：等 iroh 进来 → 每条连接接受一条流 → 连本机 {@code 127.0.0.1:mcPort} → 双向搬运；</li>
 *   <li><b>访客侧</b>：监听 {@code 127.0.0.1:随机端口} → MC 连进来 → 向房主开一条流 → 双向搬运。</li>
 * </ul>
 * MC 客户端只连 {@code 127.0.0.1:本地端口}，底下是 QUIC 它并不知情。
 */
class IrohBridge(
    private val endpoint: IrohEndpoint,
    /** 房主侧要连的本机 MC 端口；访客侧传 0。 */
    private val mcPort: Int,
    /** 房主 NodeID（访客侧连接用）；房主侧传 null。 */
    private val hostNodeId: String? = null,
    /** 房主直连地址（来自房间码）；为空时只能靠 relay 找对方。 */
    private val hostDirectAddrs: List<String> = emptyList(),
    /** QUIC ALPN，双方必须一致。 */
    private val alpn: String = "p2plan/mc/1",
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val rx = LongAdder()
    private val tx = LongAdder()

    /** 本桥起的所有线程（close 时统一打断，避免阻塞等待拖住关房）。 */
    private val threads: MutableSet<Thread> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 正在搬运的 TCP socket：close() 时逐个关，用来打断阻塞中的 read（见 pumpBoth 注释）。 */
    private val liveSockets: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 正在搬运的 QUIC 流：close() 时一起关，避免 Rust 侧资源等 GC。 */
    private val liveStreams: MutableSet<P2PStream> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    var localPort: Int = -1
        private set

    /** 最近一次连接的质量快照（供 MC 侧进服后提示一次）。 */
    @Volatile
    var lastQuality: IrohQuality? = null
        private set

    fun rxBytes(): Long = rx.sum()
    fun txBytes(): Long = tx.sum()

    fun localJoinAddress(): String? = if (localPort > 0) "127.0.0.1:$localPort" else null

    // ------------------------------------------------------------------ 房主侧

    /** 后台循环：接受 iroh 连接，每条桥到本机 MC。 */
    fun startHostAcceptLoop() {
        daemon("p2plan-iroh-accept") {
            while (!closed.get() && endpoint.isAlive()) {
                val conn = endpoint.acceptNextConnection()
                if (conn == null) {
                    // 返回 null 有两种情况：超时（正常，继续等）或 endpoint 内部出错。
                    // 不能无退避地 continue —— iroh 的 socket 级错误未必立刻反映到 isClosed()，
                    // 那样会变成每轮新建一个 runBlocking 事件循环的热自旋，直接把一个核打满。
                    if (!endpoint.isAlive()) {
                        break
                    }
                    if (closed.get()) {
                        break
                    }
                    Thread.sleep(200)
                    continue
                }
                daemon("p2plan-iroh-conn") {
                    // 注意：这里不能 conn.use{} —— 那样 wait 完 stream 就会把 Connection 关掉，
                    // 正在传输的流会立刻被重置（实测报「软件导致连接中止」）。
                    val stream = conn.acceptStream()
                    if (stream != null) {
                        bridgeToLocalMc(stream)
                        runCatching { stream.close() }
                    }
                    runCatching { conn.close() }
                }
            }
        }
    }

    private fun bridgeToLocalMc(stream: P2PStream) {
        try {
            Socket(InetAddress.getByName("127.0.0.1"), mcPort).use { tcp ->
                tcp.tcpNoDelay = true
                pumpBoth(stream, tcp, tcp.getInputStream(), tcp.getOutputStream())
            }
        } catch (_: Throwable) {
            // 连不上本机 MC（还没开房 / 已退出）：这条流作废即可
        }
    }

    // ------------------------------------------------------------------ 访客侧

    /** 起本地 TCP 监听，MC 连进来就转发到房主。返回本地端口。 */
    fun startGuestListener(): Int {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        listener = ss
        localPort = ss.localPort
        daemon("p2plan-iroh-listen") {
            while (!closed.get()) {
                val tcp = runCatching { ss.accept() }.getOrNull() ?: break
                daemon("p2plan-iroh-guest") {
                    try {
                        tcp.tcpNoDelay = true
                        val conn = endpoint.connectTo(hostNodeId!!, alpn, hostDirectAddrs)
                        val stream = conn.openStream()
                        if (stream != null) {
                            // 质量取样放在流建立之后、同步取一次（不再 sleep 后取）：
                            // 旧做法在 MC 连接很快结束时会对已关闭的 conn 取样，把直连误报成中继。
                            // 流已打开说明路径基本定型，此处取样足够准。
                            runCatching {
                                val q = conn.quality()
                                lastQuality = q
                                recordWorkingAddr(q)
                            }
                            pumpBoth(stream, tcp, tcp.getInputStream(), tcp.getOutputStream())
                            runCatching { stream.close() }
                        }
                        runCatching { conn.close() }
                    } catch (t: Throwable) {
                        System.err.println("[p2plan-iroh] 访客连接失败: $t")
                    } finally {
                        runCatching { tcp.close() }
                    }
                }
            }
        }
        return ss.localPort
    }

    /**
     * 把这次真正连通的直连地址按房主 NodeID 记下来，下次开连时排到最前（P2-15）。
     *
     * <p>只记**直连**地址：中继地址不是房主的直连地址，记下来对下次没帮助。
     */
    private fun recordWorkingAddr(q: IrohQuality) {
        if (q.relayed || q.remoteAddr.isBlank()) {
            return
        }
        kotlin.runCatching {
            val dir = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("p2plan")
            java.nio.file.Files.createDirectories(dir)
            val f = dir.resolve("working-addrs.properties")
            val p = java.util.Properties()
            if (java.nio.file.Files.isRegularFile(f)) {
                java.nio.file.Files.newBufferedReader(f).use { p.load(it) }
            }
            p.setProperty(hostNodeId ?: return, q.remoteAddr)
            java.nio.file.Files.newBufferedWriter(f).use {
                p.store(it, "p2plan: last working direct address per host node")
            }
        }
    }

    // ------------------------------------------------------------------ 双向搬运
    /**
     * 把 QUIC 流与 TCP 流对接：两个方向各一个线程，调用线程等待整个会话结束。
     *
     * <p>MC 的连接是全双工长连接，只用单线程单向读会让另一半数据堵住。
     *
     * <p><b>join 为什么绝不能带超时</b>（这里曾经踩过一个「每次开玩 60 秒必掉线」的事故）：
     * 上行线程阻塞在 TCP read、下行线程阻塞在 QUIC read —— 会话健康时**两个都会一直阻塞**。
     * 若写成 join(30s)+join(30s)，正常游戏 60 秒后 pumpBoth 会“等到超时”提前返回，
     * 调用方接着 close(stream)/close(conn)/close(tcp)，把一条完全健康的连接杀掉，
     * 表现为“玩着玩着准时在 1 分钟断开”（同机双开也断，和网络无关；短促的自检传输则恰好躲过）。
     *
     * <p>正确性靠三条唤醒链保证，不需要超时兜底：
     * <ul>
     *   <li>对端正常结束：下行先醒 → finally 关 TCP → 上行的 read 抛异常 → 双双返回；</li>
     *   <li>本端关隧道：[close] 关掉所有活动 socket/stream + interrupt 线程 → read 抛 → 返回；</li>
     *   <li>TCP 先断：上行醒 → stream.finish() 发 FIN → 下行 read 返回 null → 返回。</li>
     * </ul>
     */
    private fun pumpBoth(stream: P2PStream, tcp: Socket, tcpIn: InputStream, tcpOut: OutputStream) {
        liveSockets.add(tcp)
        liveStreams.add(stream)
        val up = daemon("p2plan-iroh-up") {
            tcpToStream(tcpIn, stream)
            stream.finish()
        }
        val down = daemon("p2plan-iroh-down") {
            streamToTcp(stream, tcpOut)
            // 下行结束（对端 FIN 或隧道出错）→ 主动关 TCP，唤醒阻塞在 read 的上行线程
            runCatching { tcp.close() }
        }
        try {
            up.join()
            down.join()
        } catch (_: InterruptedException) {
            // bridge.close() 在收尾时会 interrupt 本线程：正常退出路径
            Thread.currentThread().interrupt()
        } finally {
            liveSockets.remove(tcp)
            liveStreams.remove(stream)
        }
    }

    /** TCP → QUIC。 */
    private fun tcpToStream(from: InputStream, stream: P2PStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (!closed.get()) {
                val n = from.read(buf)
                if (n < 0) break
                if (!stream.write(buf.copyOf(n))) break
                tx.add(n.toLong())
            }
        } catch (_: Throwable) {
            // 对端关闭 / 网络中断
        }
    }

    /** QUIC → TCP。 */
    private fun streamToTcp(stream: P2PStream, to: OutputStream) {
        try {
            while (!closed.get()) {
                val data = try {
                    stream.read()
                } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                    // 单纯「这段时间没数据」——继续等。
                    // 旧版 read() 把超时也吞成 null → 这里 break → 连接被「正常收尾」，
                    // 正是「玩着玩着断线」的头号根因（MC 的 keep-alive 间隔 15s，300s 都空
                    // 已经是极端情况，但绝不该当成对端结束）。
                    continue
                } ?: break // null = 对端 FIN，正常收尾
                if (data.isEmpty()) break
                to.write(data)
                to.flush()
                rx.add(data.size.toLong())
            }
        } catch (_: Throwable) {
            // 流被重置/连接断开：收尾
        } finally {
            runCatching { to.flush() }
        }
    }

    private fun daemon(name: String, body: () -> Unit): Thread {
        val t = Thread({
            try {
                body()
            } catch (_: Throwable) {
                // 后台线程绝不外抛
            } finally {
                threads.remove(Thread.currentThread())
            }
        }, name)
        t.isDaemon = true
        threads.add(t)
        t.start()
        return t
    }

    /**
     * 关闭桥。
     *
     * <p>子线程都是守护线程（不会阻止 JVM 退出），但**必须能自己醒过来**：
     * 房主侧的 accept 循环最长会阻塞 60 秒，如果只关 listener，它要等满超时才退。
     * 所以这里同时：
     * <ol>
     *   <li>把 {@code closed} 置位（循环条件立刻为假，下一轮就跑完）；</li>
     *   <li>关本机 TCP 监听（打断 {@code accept()}）；</li>
     *   <li>打断仍阻塞在 iroh 调用里的线程 —— iroh 的 acceptNext() 是阻塞的，
     *       靠中断标志 + 上层 close endpoint 让 bind 的退出路径生效。</li>
     * </ol>
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener?.close() }
        listener = null
        // 1) 关所有活动 TCP socket：打断阻塞中的 read，让 pump 线程能退出
        for (s in liveSockets) {
            runCatching { s.close() }
        }
        liveSockets.clear()
        // 2) 关所有活动 QUIC 流（显式关，不等 Cleaner）
        for (st in liveStreams) {
            runCatching { st.close() }
        }
        liveStreams.clear()
        // 3) 打断剩余线程（例如阻塞在 iroh acceptNext 里的）
        for (t in threads) {
            runCatching { t.interrupt() }
        }
        threads.clear()
    }
}
