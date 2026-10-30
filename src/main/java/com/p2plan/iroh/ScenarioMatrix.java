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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 工况矩阵：一台机器两个端点（不同身份），把**代码能负责的网络路径**逐条实测。
 *
 * <p>诚实边界：单机双 JVM 能验的是「我们的代码在各条件下走对分支、传对数据」，
 * 验不了真实网络拓扑（双对称 NAT、跨运营商 IPv6、DERP 延迟）—— 那些要两台不同网络的机器。
 * 每个用例用独立身份命名空间（System 属性逐用例切换），互不污染。
 *
 * <p>跑法：{@code build.bat scenarioMatrix}
 */
public final class ScenarioMatrix {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 工况矩阵测试（单机可验的代码路径）===");

        s("S1 基线：中继开，访客凭完整候选连通",
                () -> scenario(true, false, false));
        s("S2 中继关：纯 P2P，同机仍直连",
                () -> scenario(false, false, false));
        s("S3 并发：两个访客同时连一个房主",
                () -> scenario(true, true, false));
        s("S4 候选乱序：地址顺序打乱仍连通",
                () -> scenario(true, false, true));
        s("S5 端口冲突：固定端口被占，回退后仍可服务",
                ScenarioMatrix::portCollisionFallback);

        System.out.println();
        System.out.printf("工况通过 %d / %d%n", pass, pass + fail);
        System.out.println(fail == 0 ? "SCENARIOS PASS" : "SCENARIOS FAIL(" + fail + ")");
        System.exit(fail == 0 ? 0 : 1);
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void s(String name, Case c) {
        System.out.print(name + " ... ");
        System.out.flush();
        try {
            c.run();
            pass++;
            System.out.println("PASS");
        } catch (Throwable t) {
            fail++;
            System.out.println("FAIL: " + t);
        }
    }

    // ------------------------------------------------------------------ 公共件

    /** 一个假 MC 服务器（回显）。 */
    private static final class FakeMc implements AutoCloseable {
        final ServerSocket ss;
        final Thread acceptor;

        FakeMc() throws Exception {
            ss = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket c = ss.accept();
                        new Thread(() -> {
                            try (Socket s = c) {
                                byte[] buf = new byte[64 * 1024];
                                int n;
                                while ((n = s.getInputStream().read(buf)) > 0) {
                                    s.getOutputStream().write(buf, 0, n);
                                    s.getOutputStream().flush();
                                }
                            } catch (Exception ignored) {
                            }
                        }, "echo").start();
                    } catch (Exception e) {
                        return;
                    }
                }
            }, "fake-mc");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            ss.close();
        }
    }

    /** 访客经隧道传 256KB 校验。 */
    private static void verifyGuest(P2PTunnel guest, long waitMs) throws Exception {
        long end = System.currentTimeMillis() + waitMs;
        String local = null;
        while (System.currentTimeMillis() < end) {
            if (guest.isReady()) {
                local = guest.localJoinAddress();
                if (local != null) {
                    break;
                }
            }
            Thread.sleep(150);
        }
        if (local == null) {
            throw new IllegalStateException("访客未就绪: " + guest.status().describe());
        }
        String[] hp = local.split(":");
        try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(20_000);
            byte[] payload = new byte[256 * 1024];
            new Random(3).nextBytes(payload);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(payload);
            out.flush();
            byte[] back = new byte[payload.length];
            in.readFully(back);
            if (!Arrays.equals(payload, back)) {
                throw new IllegalStateException("数据不一致");
            }
        }
    }

    /**
     * 基线/中继开关/并发/乱序 四个用例的公共流程。
     *
     * @param relay      设 p2plan.relay 系统属性
     * @param twoGuests  并发两个访客
     * @param shuffle    访客拿到的候选顺序打乱
     */
    private static void scenario(boolean relay, boolean twoGuests, boolean shuffle) throws Exception {
        System.setProperty("p2plan.relay", String.valueOf(relay));
        String tag = relay ? "r" : "n";
        System.setProperty("p2plan.identity.host", "sc-" + tag + "-host");
        System.setProperty("p2plan.identity.guest", "sc-" + tag + "-guest");

        try (FakeMc mc = new FakeMc()) {
            P2PTunnel host = Tunnels.host(mc.port(), TunnelOptions.defaults(), st -> { });
            try {
                List<Candidate> cs = awaitCandidates(host, 30_000);
                if (cs.isEmpty()) {
                    throw new IllegalStateException("房主无候选: " + host.status().describe());
                }
                if (twoGuests) {
                    // 身份命名空间是 JVM 全局系统属性、在 bind 时读取：
                    // 不能两个线程并发「设属性→建隧道」，会读到同一个命名空间 → 同 NodeID 互斥。
                    // 因此**串行建两条隧道**（各自命名空间），再并行校验 —— 隧道建成后就不再依赖属性了。
                    System.setProperty("p2plan.identity.guest", "sc-" + tag + "-guest1");
                    P2PTunnel g1 = Tunnels.guest(cs, "S", TunnelOptions.defaults(), st -> { });
                    System.setProperty("p2plan.identity.guest", "sc-" + tag + "-guest2");
                    P2PTunnel g2 = Tunnels.guest(cs, "S", TunnelOptions.defaults(), st -> { });
                    try {
                        List<Throwable> errs = new ArrayList<>();
                        Thread v1 = new Thread(() -> {
                            try {
                                verifyGuest(g1, 45_000);
                            } catch (Throwable e) {
                                synchronized (errs) {
                                    errs.add(e);
                                }
                            }
                        }, "verify-1");
                        Thread v2 = new Thread(() -> {
                            try {
                                verifyGuest(g2, 45_000);
                            } catch (Throwable e) {
                                synchronized (errs) {
                                    errs.add(e);
                                }
                            }
                        }, "verify-2");
                        v1.start();
                        v2.start();
                        v1.join(70_000);
                        v2.join(70_000);
                        if (!errs.isEmpty()) {
                            throw new IllegalStateException("并发访客失败: " + errs.get(0));
                        }
                    } finally {
                        g1.close();
                        g2.close();
                    }
                } else {
                    List<Candidate> given = new ArrayList<>(cs);
                    if (shuffle) {
                        java.util.Collections.reverse(given);
                    }
                    System.setProperty("p2plan.identity.guest",
                            "sc-" + tag + "-g" + (shuffle ? "x" : "s"));
                    P2PTunnel guest = Tunnels.guest(given, "S", TunnelOptions.defaults(), st -> { });
                    try {
                        verifyGuest(guest, 30_000);
                    } finally {
                        guest.close();
                    }
                }
            } finally {
                host.close();
            }
        } finally {
            System.clearProperty("p2plan.relay");
        }
    }

    // ------------------------------------------------------------------ S5 端口冲突

    /**
     * 固定 iroh 端口已被本机另一实例占用时，第二个房主必须**回退随机端口并照常服务**，
     * 不能开房失败（这是 bindWithFallback 的回退链实测）。
     */
    private static void portCollisionFallback() throws Exception {
        System.setProperty("p2plan.relay", "false"); // 回退链最短，聚焦端口冲突本身
        System.setProperty("p2plan.identity.host", "sc-a-host");
        System.setProperty("p2plan.identity.guest", "sc-a-guest");
        try (FakeMc mc = new FakeMc()) {
            P2PTunnel hostA = Tunnels.host(mc.port(), TunnelOptions.defaults(), st -> { });
            try {
                List<Candidate> csA = awaitCandidates(hostA, 30_000);
                if (csA.isEmpty()) {
                    throw new IllegalStateException("房主 A 无候选");
                }
                int aPort = portOf(csA);

                // 第二个房主：同一身份文件 → 同一固定端口 → 必须走回退链
                System.setProperty("p2plan.identity.host", "sc-b-host");
                System.setProperty("p2plan.identity.guest", "sc-b-guest");
                P2PTunnel hostB = Tunnels.host(mc.port(), TunnelOptions.defaults(), st -> { });
                try {
                    List<Candidate> csB = awaitCandidates(hostB, 30_000);
                    if (csB.isEmpty()) {
                        throw new IllegalStateException("房主 B 撞端口后没回退成功: "
                                + hostB.status().describe());
                    }
                    int bPort = portOf(csB);
                    if (bPort == aPort) {
                        throw new IllegalStateException("B 与 A 端口相同（不可能同时 bind）");
                    }
                    // B 必须真的能服务：访客连 B 传数据
                    P2PTunnel guest = Tunnels.guest(csB, "S", TunnelOptions.defaults(), st -> { });
                    try {
                        verifyGuest(guest, 30_000);
                    } finally {
                        guest.close();
                    }
                } finally {
                    hostB.close();
                }
            } finally {
                hostA.close();
                System.clearProperty("p2plan.relay");
            }
        }
    }

    private static int portOf(List<Candidate> cs) {
        for (Candidate c : cs) {
            if (c.kind() != Candidate.Kind.IROH_NODE) {
                return c.port();
            }
        }
        throw new IllegalStateException("候选里没有地址");
    }

    private static List<Candidate> awaitCandidates(P2PTunnel t, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            List<Candidate> cs = t.candidates();
            if (cs != null && !cs.isEmpty()) {
                return cs;
            }
            if (t.status() != null && "FAILED".equals(t.status().state().name())) {
                throw new IllegalStateException("房主失败: " + t.status().describe());
            }
            Thread.sleep(150);
        }
        return List.of();
    }
}
