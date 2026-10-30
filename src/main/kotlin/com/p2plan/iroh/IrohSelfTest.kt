package com.p2plan.iroh

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * iroh 端到端自检：不依赖 Minecraft，验证「QUIC 流 + TCP 桥」整条链路。
 *
 * <p>流程：
 * 1. 起一个假的 MC 服务端（TCP 回声），随机端口；
 * 2. 房主 endpoint 绑定 + [IrohBridge] 连到假 MC；
 * 3. 访客 endpoint 绑定 + [IrohBridge] 起本地监听；
 * 4. 用普通 Socket 连访客的本地端口，双向传 1MB 校验一致性。
 *
 * <p>这不是 mock：用的是生产代码 [IrohEndpoint] / [IrohBridge]，只有「MC」换成了回声服务端。
 *
 * <p>关键：房主和访客在**同一进程**里跑，属于回环场景（不走 NAT），
 * 验证的是协议与桥接正确性，不代表跨网打洞成功率。
 */
object IrohSelfTest {

    private const val PAYLOAD = 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== iroh self-test (no Minecraft) ===")
        println("java.version = ${System.getProperty("java.version")}")

        // 0) 原生库状态（在 MC 里由 IrohNatives 准备；这里直接跑在普通 JVM，JNA 会自己找）
        println("[0] iroh version check: ${runCatching { SecretKeyCheck() }.getOrElse { "FAIL: $it" }}")

        // 1) 假 MC：TCP 回声服务端
        val mc = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread({
            while (!mc.isClosed) {
                val s = runCatching { mc.accept() }.getOrNull() ?: break
                Thread({
                    s.use {
                        try {
                            val buf = ByteArray(32 * 1024)
                            val out = it.getOutputStream()
                            val inp = it.getInputStream()
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                out.flush()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }, "fake-mc-conn").apply { isDaemon = true }.start()
            }
        }, "fake-mc").apply { isDaemon = true }
        echoThread.start()
        println("[1] 假 MC 服务端监听 127.0.0.1:${mc.localPort}")

        // 2) 房主
        val hostEp = runCatching { IrohEndpoint.bind(relay = false) }
            .getOrElse {
                println("[2] ❌ 房主 endpoint 绑定失败: $it")
                exitProcess(1)
            }
        println("[2] 房主 NodeID = ${hostEp.nodeId}")
        println("    boundSockets = ${hostEp.boundSockets()}")
        val hostAddrs = hostEp.directAddresses()
        println("    directAddresses = $hostAddrs")

        val hostBridge = IrohBridge(hostEp, mc.localPort, hostNodeId = null)
        hostBridge.startHostAcceptLoop()
        println("    房主桥已启动（等 iroh 连接 → 转 127.0.0.1:${mc.localPort}）")

        // 3) 访客
        val guestEp = runCatching { IrohEndpoint.bind(relay = false) }
            .getOrElse {
                println("[3] ❌ 访客 endpoint 绑定失败: $it")
                exitProcess(1)
            }
        println("[3] 访客 NodeID = ${guestEp.nodeId}")

        // 关键：把房主的直连地址传给访客 —— 纯 P2P（relay 关闭）时必须靠它找到对方
        val guestBridge = IrohBridge(
            guestEp, 0,
            hostNodeId = hostEp.nodeId,
            hostDirectAddrs = hostAddrs,
        )
        val localPort = guestBridge.startGuestListener()
        println("    访客本地入口 = 127.0.0.1:$localPort")

        // 4) 通过访客本地入口连进去，双向校验
        println("[4] 通过桥传 ${PAYLOAD / 1024} KB 并校验…")
        val t0 = System.currentTimeMillis()
        val ok = runCatching {
            Socket("127.0.0.1", localPort).use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 60_000
                val out = DataOutputStream(s.getOutputStream())
                val inp = DataInputStream(s.getInputStream())

                val payload = ByteArray(PAYLOAD)
                java.util.Random(42).nextBytes(payload)
                out.write(payload)
                out.flush()

                val back = ByteArray(PAYLOAD)
                inp.readFully(back)
                payload.contentEquals(back)
            }
        }.getOrElse {
            println("    ❌ 传输异常: $it")
            false
        }
        val ms = System.currentTimeMillis() - t0

        if (ok) {
            println("[5] ✅ 双向 ${PAYLOAD / 1024} KB 逐字节一致，用时 ${ms}ms " +
                    "（${PAYLOAD / 1024 * 1000L / maxOf(ms, 1)} KB/s）")
            println("    rx=${guestBridge.rxBytes()} tx=${guestBridge.txBytes()}")
            println()
            println("IROH SELF-TEST PASS")
        } else {
            println("[5] ❌ 数据不一致或传输失败")
            println()
            println("IROH SELF-TEST FAIL")
        }

        runCatching { guestBridge.close() }
        runCatching { hostBridge.close() }
        runCatching { guestEp.close() }
        runCatching { hostEp.close() }
        runCatching { mc.close() }
        exitProcess(if (ok) 0 else 1)
    }

    private fun SecretKeyCheck(): String {
        val sk = IrohEndpoint.generateSecretKey()
        val id = IrohEndpoint.nodeIdOf(sk)
        return "ok (${sk.size}B key -> ${id.take(16)}…)"
    }
}
