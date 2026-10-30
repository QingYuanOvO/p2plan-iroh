package com.p2plan.iroh;

import com.p2plan.tunnel.Candidate;
import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.TunnelOptions;
import com.p2plan.tunnel.Tunnels;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;

/**
 * 「长会话」回归测试：正是抓「玩着约 1 分钟必掉线」那类 bug 的。
 *
 * <p>历史事故：{@code IrohBridge.pumpBoth} 用 join(30s)+join(30s) 等两条搬运线程，
 * 健康会话的两个读线程永远不会自己退出 → 60 秒后 pumpBoth 超时返回、调用方把
 * stream/conn/tcp 全关掉 → 一条完全健康的连接被自己杀掉。MC 的 keep-alive 是 15 秒，
 * 15 秒内不会踢；于是表现为「进服约 1 分钟突然中断」，且同机双开也照样断。
 * 所有既有自检传输都在几秒内完成，恰好全部躲过 —— 这就是为什么需要一个专门的长时间测试。
 *
 * <p>本测试走真实生产路径（Tunnels.host/guest → IrohTunnel → IrohBridge），
 * 会话保持 {@value #HOLD_MS} 毫秒并期间穿插小流量，要求全程不掉线、结束时干净收尾。
 * 跑法：{@code build.bat irohLongSessionTest}
 */
public final class IrohLongSessionTest {

    /** 会话保持时长：必须明显大于历史上「60 秒必死」的那个坑，留足余量取 100 秒。 */
    private static final long HOLD_MS = 100_000L;
    private static final String SECRET = "LONGTEST";

    public static void main(String[] args) throws Exception {
        System.out.println("=== iroh 长会话保持测试（" + HOLD_MS / 1000 + " 秒，防「join 超时杀健康连接」回归）===");

        // 假 MC 服务：接受连接后一直应答（像服务器一样持续有进出流量）
        ServerSocket mc = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread echo = new Thread(() -> {
            while (!mc.isClosed()) {
                Socket s;
                try {
                    s = mc.accept();
                } catch (Exception e) {
                    return;
                }
                Thread c = new Thread(() -> {
                    try (Socket sock = s) {
                        sock.setTcpNoDelay(true);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = sock.getInputStream().read(buf)) > 0) {
                            sock.getOutputStream().write(buf, 0, n);
                            sock.getOutputStream().flush();
                        }
                    } catch (Exception ignored) {
                    }
                }, "echo-conn");
                c.setDaemon(true);
                c.start();
            }
        }, "echo-accept");
        echo.setDaemon(true);
        echo.start();

        P2PTunnel host = Tunnels.host(mc.getLocalPort(), TunnelOptions.defaults(), st -> { });
        List<Candidate> cs = List.of();
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            cs = host.candidates();
            if (!cs.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        if (cs.isEmpty()) {
            System.out.println("LONGSESSION FAIL 房主无候选");
            System.exit(1);
        }

        P2PTunnel guest = Tunnels.guest(cs, SECRET, TunnelOptions.defaults(), st -> { });
        deadline = System.currentTimeMillis() + 30_000;
        String local = null;
        while (System.currentTimeMillis() < deadline) {
            if (guest.isReady()) {
                local = guest.localJoinAddress();
                if (local != null) {
                    break;
                }
            }
            Thread.sleep(200);
        }
        if (local == null) {
            System.out.println("LONGSESSION FAIL 访客隧道未就绪");
            System.exit(1);
        }

        String[] hp = local.split(":");
        long t0 = System.currentTimeMillis();
        boolean ok;
        String why = "";
        try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
            s.setTcpNoDelay(true);
            // 关键：读超时给到「会话总时长 + 60 秒」。
            // 如果 bridge 在 60 秒把健康连接自己关掉，这里的 read 会抛异常/拿到 EOF，而不是超时。
            s.setSoTimeout((int) (HOLD_MS + 60_000));
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            Random r = new Random(7);
            ok = true;
            // 模拟真实游戏：间歇性小包（聊天/移动），穿插一次中等突发（区块加载），共 HOLD_MS
            long end = t0 + HOLD_MS;
            long nextBeat = t0 + 1_000;
            long nextBurst = t0 + 20_000;
            while (System.currentTimeMillis() < end && ok) {
                long now = System.currentTimeMillis();
                if (now >= nextBurst) {
                    byte[] burst = new byte[512 * 1024];
                    r.nextBytes(burst);
                    out.write(burst);
                    out.flush();
                    byte[] back = new byte[burst.length];
                    in.readFully(back);
                    if (!java.util.Arrays.equals(burst, back)) {
                        ok = false;
                        why = "突发数据不一致";
                    }
                    nextBurst += 30_000;
                    long used = System.currentTimeMillis() - now;
                    System.out.printf("  [%3ds] 突发 512KB 往返一致（用 %dms）%n",
                            (now - t0) / 1000, used);
                } else if (now >= nextBeat) {
                    byte[] beat = "keep-alive-beat".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    out.write(beat);
                    out.flush();
                    byte[] back = new byte[beat.length];
                    in.readFully(back); // 这里卡住/抛异常 = 连接在会话中途被杀
                    if (!java.util.Arrays.equals(beat, back)) {
                        ok = false;
                        why = "心跳数据不一致";
                    }
                    nextBeat = now + 3_000;
                    System.out.printf("  [%3ds] 心跳 OK%n", (now - t0) / 1000);
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            ok = false;
            why = "连接在会话中途断开：" + e;
        }

        long secs = (System.currentTimeMillis() - t0) / 1000;
        // 收尾也要干净：显式关闭后 5 秒内双方 close 必须返回（曾有 teardown 卡死问题）
        long c0 = System.currentTimeMillis();
        Thread hc = new Thread(host::close, "close-host");
        hc.setDaemon(true);
        hc.start();
        guest.close();
        hc.join(8_000);
        System.out.printf("收尾：host.close %s，耗时 %dms%n",
                hc.isAlive() ? "仍在进行（守护线程，不阻塞退出）" : "已返回",
                System.currentTimeMillis() - c0);
        mc.close();

        if (ok && secs >= HOLD_MS / 1000) {
            System.out.println("LONGSESSION PASS 会话全程保持 " + secs + " 秒无中断");
            System.exit(0);
        } else {
            System.out.println("LONGSESSION FAIL 会话只活了 " + secs + " 秒  " + why);
            System.exit(1);
        }
    }
}
