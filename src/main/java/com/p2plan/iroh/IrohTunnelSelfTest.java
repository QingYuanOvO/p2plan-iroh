package com.p2plan.iroh;

import com.p2plan.tunnel.Candidate;
import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.TunnelOptions;
import com.p2plan.tunnel.Tunnels;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * 验证「上层 API」路径：{@code Tunnels.host()} → 房间码候选 → {@code Tunnels.guest()} → 本地入口。
 *
 * <p>与 {@link IrohSelfTest} 的区别：那个测的是 {@link IrohBridge} 直连；
 * 这个测的是**游戏真正走的那条路**（GUI → P2PHost/P2PJoin → Tunnels → IrohTunnel），
 * 所以它才是「能不能进游戏验收」的关键证据。
 */
public final class IrohTunnelSelfTest {

    private static final int PAYLOAD = 512 * 1024;

    public static void main(String[] args) throws Exception {
        System.out.println("=== IrohTunnel 上层路径自检（等价于游戏流程）===");

        // 假 MC：TCP 回声
        ServerSocket mc = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        startEcho(mc);
        System.out.println("[1] 假 MC 监听 127.0.0.1:" + mc.getLocalPort());

        // 房主：Tunnels.host(...)
        System.out.println("[2] 启动房主隧道（Tunnels.host）…");
        P2PTunnel host = Tunnels.host(mc.getLocalPort(), TunnelOptions.defaults(),
                st -> System.out.println("    [host] " + st.describe()));

        List<Candidate> cands;
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            cands = host.candidates();
            if (!cands.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        cands = host.candidates();
        System.out.println("[3] 房主候选 " + cands.size() + " 条：");
        for (Candidate c : cands) {
            System.out.println("      " + c.describe());
        }
        if (cands.isEmpty()) {
            System.out.println("❌ 房主没产出候选，后续无法进行");
            System.exit(1);
        }

        // 访客：Tunnels.guest(...)
        System.out.println("[4] 启动访客隧道（Tunnels.guest）…");
        P2PTunnel guest = Tunnels.guest(cands, "SELFTEST", TunnelOptions.defaults(),
                st -> System.out.println("    [guest] " + st.describe()));

        // 等访客就绪，拿本地入口
        String local = null;
        deadline = System.currentTimeMillis() + 25_000;
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
            System.out.println("❌ 访客未就绪：" + guest.status().describe());
            System.exit(1);
        }
        System.out.println("[5] 访客本地入口 = " + local);

        // 通过访客入口传数据并校验
        System.out.println("[6] 传 " + (PAYLOAD / 1024) + " KB 并校验…");
        String[] hp = local.split(":");
        boolean ok;
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(60_000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] payload = new byte[PAYLOAD];
            new java.util.Random(7).nextBytes(payload);
            out.write(payload);
            out.flush();
            byte[] back = new byte[PAYLOAD];
            in.readFully(back);
            ok = payload.length == back.length && java.util.Arrays.equals(payload, back);
        } catch (Throwable t) {
            System.out.println("    ❌ 传输异常: " + t);
            ok = false;
        }
        long ms = System.currentTimeMillis() - t0;

        if (ok) {
            System.out.println("[7] ✅ 双向一致，用时 " + ms + "ms");
            System.out.println();
            System.out.println("IROH TUNNEL SELF-TEST PASS");
        } else {
            System.out.println("[7] ❌ 失败");
            System.out.println();
            System.out.println("IROH TUNNEL SELF-TEST FAIL");
        }

        try {
            guest.close();
        } catch (Throwable ignored) {
        }
        try {
            host.close();
        } catch (Throwable ignored) {
        }
        mc.close();
        System.exit(ok ? 0 : 1);
    }

    private static void startEcho(ServerSocket mc) {
        Thread t = new Thread(() -> {
            while (!mc.isClosed()) {
                Socket s = null;
                try {
                    s = mc.accept();
                    Socket f = s;
                    Thread c = new Thread(() -> {
                        try {
                            f.setTcpNoDelay(true);
                            byte[] buf = new byte[32 * 1024];
                            while (true) {
                                int n = f.getInputStream().read(buf);
                                if (n < 0) break;
                                f.getOutputStream().write(buf, 0, n);
                                f.getOutputStream().flush();
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                f.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }, "echo-conn");
                    c.setDaemon(true);
                    c.start();
                } catch (Throwable t2) {
                    break;
                }
            }
        }, "echo-accept");
        t.setDaemon(true);
        t.start();
    }
}