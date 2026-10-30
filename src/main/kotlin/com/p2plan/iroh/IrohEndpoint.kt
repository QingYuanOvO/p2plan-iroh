package com.p2plan.iroh

import computer.iroh.BiStream
import computer.iroh.RecvStream
import computer.iroh.SendStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * iroh Endpoint 的薄封装：把 Kotlin 的 suspend API 暴露成 Java 能直接调用的阻塞方法。
 *
 * <p>为什么需要它：iroh 的接口全是 suspend 函数，Java 侧手写 Continuation 不工作
 * （uniffi 需要协程调度器）。这里用 `runBlocking` 把它们变成普通方法。
 *
 * <p>API 链（实测确认，2026-10）：
 * <pre>
 * 房主：endpoint.acceptNext() → Incoming.accept() → Accepting.connect() → Connection.acceptBi() → BiStream
 * 访客：endpoint.connect(EndpointAddr, alpn) → Connection → Connection.openBi() → BiStream
 * 收发：BiStream.send() → SendStream.write / BiStream.recv() → RecvStream.read
 * </pre>
 */
class IrohEndpoint private constructor(
    private val endpoint: Endpoint,
    val nodeId: String,
    val relayEnabled: Boolean,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    fun boundSockets(): List<String> = runCatching { endpoint.boundSockets() }.getOrDefault(emptyList())

    fun addrString(): String = runCatching { endpoint.addr().toString() }.getOrDefault("")

    fun isAlive(): Boolean = !closed.get() && runCatching { !endpoint.isClosed() }.getOrDefault(false)

    // ------------------------------------------------------------------ 房主侧

    /** 等一个进来的连接（阻塞）。返回 null 表示 endpoint 已关闭或超时。 */
    fun acceptNextConnection(timeoutMs: Long = 60_000): P2PConnection? {
        if (closed.get()) return null
        return runCatching {
            runBlocking {
                withTimeout(timeoutMs) {
                    val incoming = endpoint.acceptNext()
                        ?: return@withTimeout null
                    val accepting = incoming.accept()
                    val conn = accepting.connect()
                    P2PConnection(conn)
                }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 访客侧

    /**
     * 主动连接房主。
     *
     * <p><b>关于并行探测</b>：iroh 的 {@code connect(EndpointAddr, alpn)} 内部会**同时尝试传入的所有
     * directAddresses**（QUIC 的多路径建连），所以不需要我们在外面并发发起。我们要做的只是
     * 把「最可能成功的地址排在前面」，并对失败做一次整体重试。
     *
     * @param hostNodeId     房主的 NodeID（来自房间码）
     * @param directAddrs    房主的直连地址；传空列表时只能靠 relay
     * @param retries        失败后额外重试次数（弱网下第一次建连常因丢包失败）
     */
    fun connectTo(
        hostNodeId: String,
        alpn: String,
        directAddrs: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
        retries: Int = 1,
    ): P2PConnection {
        val addr = EndpointAddr(
            EndpointId.fromString(hostNodeId),
            null,
            directAddrs,
        )
        var last: Throwable? = null
        for (attempt in 0..retries) {
            try {
                val conn = runBlocking {
                    withTimeout(timeoutMs) {
                        endpoint.connect(addr, alpn.toByteArray())
                    }
                }
                return P2PConnection(conn)
            } catch (t: Throwable) {
                last = t
                // 第一次失败后稍等再试：对端可能正在切换路径或 NAT 映射刚失效
                if (attempt < retries) {
                    runCatching { Thread.sleep(700) }
                }
            }
        }
        throw last ?: IllegalStateException("连接失败")
    }

    /** 本机当前的直连地址列表（给房间码用）。 */
    fun directAddresses(): List<String> = runCatching {
        endpoint.addr().directAddresses()
    }.getOrDefault(emptyList())

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { runBlocking { withTimeout(5_000) { endpoint.shutdown() } } }
    }

    companion object {
        /**
         * 绑定 endpoint。
         *
         * @param secretKey 32 字节私钥；null=随机生成（NodeID 随之变化）
         * @param bindAddr  监听地址，`0.0.0.0:0` = 所有网卡 + 随机端口
         * @param relay     true=启用 iroh 官方 DERP 中继兜底；false=纯 P2P
         * @param alpns     本 endpoint 接受的协议名列表。
         *                  **必须在 bind 时声明**，否则对端连接会报
         *                  "peer doesn't support any known protocol"（实测踩到过）。
         * @param timeoutMs 绑定超时（连不上中继时会卡，必须设限）
         */
        @JvmStatic
        @JvmOverloads
        fun bind(
            secretKey: ByteArray? = null,
            bindAddr: String = "0.0.0.0:0",
            relay: Boolean = false,
            alpns: List<String> = listOf(DEFAULT_ALPN),
            timeoutMs: Long = 20_000,
        ): IrohEndpoint {
            val sk = if (secretKey != null) SecretKey.fromBytes(secretKey) else SecretKey.generate()
            val opts = EndpointOptions(
                secretKey = sk.toBytes(),
                bindAddr = bindAddr,
                alpns = alpns.map { it.toByteArray() },
                relayMode = if (relay) RelayMode.defaultMode() else RelayMode.disabled(),
            )
            val ep = runBlocking { withTimeout(timeoutMs) { Endpoint.bind(opts) } }
            return IrohEndpoint(ep, ep.id().toString(), relay)
        }

        /** 默认 ALPN：本模组 MC 隧道的协议标识。 */
        const val DEFAULT_ALPN = "p2plan/mc/1"

        @JvmStatic
        fun generateSecretKey(): ByteArray = SecretKey.generate().toBytes()

        @JvmStatic
        fun nodeIdOf(secretKey: ByteArray): String =
            SecretKey.fromBytes(secretKey).public().toString()
    }
}

/** 一条 iroh 连接上的双向流。 */
class P2PConnection internal constructor(private val conn: Connection) : AutoCloseable {

    /** 房主侧：接受对端开来的流（阻塞）。 */
    fun acceptStream(timeoutMs: Long = 60_000): P2PStream? = runCatching {
        runBlocking { withTimeout(timeoutMs) { P2PStream(conn.acceptBi()) } }
    }.getOrNull()

    /** 访客侧：主动开一条流（阻塞）。 */
    fun openStream(timeoutMs: Long = 30_000): P2PStream? = runCatching {
        runBlocking { withTimeout(timeoutMs) { P2PStream(conn.openBi()) } }
    }.getOrNull()

    fun closeReason(): String = runCatching { conn.closeReason() ?: "" }.getOrDefault("")

    /**
     * 连接质量快照：走直连还是中继、RTT 多少、对端地址。
     *
     * <p>取样时机（在 [openStream] 成功之后同步取一次）：流能打开说明路径已定型；
     * 不要改成「sleep 之后再取」—— 那会对已关闭的 conn 取样，把直连误报成中继（实测踩过）。
     */
    fun quality(): IrohQuality = runCatching {
        val paths = conn.paths()
        val selected = paths.firstOrNull { it.isSelected } ?: paths.firstOrNull()
        IrohQuality(
            relayed = selected?.isRelay ?: true,
            rttMs = selected?.let { rttOf(it) } ?: -1L,
            remoteAddr = selected?.remoteAddr ?: "",
            pathCount = paths.size,
        )
    }.getOrElse { IrohQuality(true, -1L, "", 0) }

    /**
     * 读 PathSnapshot 的 RTT。
     *
     * <p>Kotlin 侧属性名是 {@code rttMs}（ULong），编译后方法名被改写成 {@code getRttMs-s-VKNKU}
     * 这类带哈希后缀的形式，Kotlin 源码里直接调用会「unresolved」。用反射按前缀找最稳。
     */
    private fun rttOf(snap: computer.iroh.PathSnapshot): Long {
        // 先试常规属性访问（不同 iroh 版本方法名不同）
        runCatching {
            val m = snap.javaClass.methods.firstOrNull {
                it.name.startsWith("getRttMs") && it.parameterCount == 0
            }
            if (m != null) {
                val v = m.invoke(snap)
                // ULong 在 JVM 上是 long；也可能是装箱的 ULong 对象
                return when (v) {
                    is Long -> v
                    is kotlin.ULong -> v.toLong()
                    null -> -1L
                    else -> (v as? Number)?.toLong() ?: -1L
                }
            }
        }
        return -1L
    }

    override fun close() {
        runCatching { conn.close() }
    }
}

/** 一次连接的质量快照（给 GUI/聊天栏用）。 */
data class IrohQuality(
    /** true = 经 iroh 公共中继（DERP）转发；false = 点对点直连。 */
    val relayed: Boolean,
    /** 往返延迟（毫秒）；-1 表示还没测出来。 */
    val rttMs: Long,
    /** 对端地址（直连时是 IP:端口，中继时是中继地址）。 */
    val remoteAddr: String,
    /** iroh 当前记录的路径条数（>1 说明它同时试了多条再择优）。 */
    val pathCount: Int,
) {
    /** 一句话描述，直接显示给玩家。 */
    fun describe(): String {
        val mode = if (relayed) "中继" else "直连"
        val rtt = if (rttMs >= 0) "$rttMs ms" else "延迟测量中"
        val addr = if (remoteAddr.isBlank()) "" else " · $remoteAddr"
        return "$mode · $rtt$addr"
    }
}

/** iroh 的双向字节流，对外提供简单的 read/write。 */
class P2PStream internal constructor(private val bi: BiStream) : AutoCloseable {

    // 不用 lazy：Loom 的 include 会改变依赖解析路径，lazy 的委托类型推断在这里会失效。
    private val sendStream: SendStream = bi.send()
    private val recvStream: RecvStream = bi.recv()

    /**
     * 读最多 len 字节。
     *
     * <p>语义（修复「玩着玩着断线」的关键）：
     * <ul>
     *   <li>返回 {@code null} = 对端正常结束流（FIN），调用方应收尾；</li>
     *   <li>抛 {@link kotlinx.coroutines.TimeoutCancellationException} = 单纯超时没数据，
     *       调用方应继续等 —— 旧版把超时也吞成 null，玩家站着不动 120 秒没包就「正常收尾」了；</li>
     *   <li>其它异常 = 流被重置/连接断了，向外抛由调用方处理。</li>
     * </ul>
     */
    fun read(len: Int = 16 * 1024, timeoutMs: Long = 300_000): ByteArray? {
        return try {
            runBlocking { withTimeout(timeoutMs) { recvStream.read(len.toUInt()) } }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            throw t // 让调用方区分「没数据」和「对端结束」
        }
    }

    /**
     * 写完全部字节（QUIC 背压时阻塞等待）。
     *
     * <p>旧版用 {@code write()} 并**丢弃返回的已写字节数**，把「部分写」当「全写」——
     * 弱网/带宽不足时数据被静默截断，MC 协议流错位，几秒后校验失败掉线。
     * {@code writeAll} 是 iroh 提供的「写完整或抛异常」版本，直接用它。
     */
    fun write(data: ByteArray, timeoutMs: Long = 300_000): Boolean {
        return try {
            runBlocking { withTimeout(timeoutMs) { sendStream.writeAll(data) } }
            true
        } catch (t: Throwable) {
            System.err.println("[p2plan-iroh] writeAll 失败: $t")
            false
        }
    }

    /** 关闭发送方向（半关闭）。 */
    fun finish() {
        runCatching { runBlocking { withTimeout(5_000) { sendStream.finish() } } }
    }

    override fun close() {
        runCatching { bi.close() }
    }
}

