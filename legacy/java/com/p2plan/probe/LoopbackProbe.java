/**
 * 跨进程真机联机验证：两个独立 JVM 通过真实 UDP socket 走完整隧道，再用普通 TCP 客户端连进去。
 *
 * <p>用法：
 * <pre>
 *   java LoopbackProbe host   &lt;mcPort&gt; &lt;秒&gt; &lt;口令&gt; [relayPort]   # 打印房间码候选
 *   java LoopbackProbe guest  &lt;mcPort&gt; &lt;秒&gt; &lt;口令&gt; &lt;relayPort&gt;    # 只有中继候选
 * </pre>
 * host 侧自己起一个 TCP 回声服务端当"假 MC"，guest 侧通过 guest.localJoinAddress() 连进去做双向校验。
 *
 * <p>这不是单测替身：用的是生产代码 {@code Tunnels.host/guest}、{@code RelayServer}、{@code RoomCode}，
 * 唯一区别是"MC"换成了回声服务端。零第三方依赖。
 */
package com.p2plan.probe;

import com.p2plan.RoomCode;
import com.p2plan.tunnel.Candidate;
import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.RelayServer;
import com.p2plan.tunnel.TunnelOptions;
import com.p2plan.tunnel.Tunnels;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class LoopbackProbe {

    private static final int PAYLOAD = 512 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("usage: host <mcPort> <seconds> <secret> [relayPort] | guest <mcPort> <seconds> <secret> <relayPort>");
            System.exit(2);
        }
        String role = args[0];
        int seconds = Integer.parseInt(args[2]);
        String secret = args[3];
        int relayPort = args.length > 4 && !args[4].isEmpty() ? Integer.parseInt(args[4]) : 0;

        if ("host".equals(role)) {
            runHost(seconds, secret, relayPort);
        } else if ("guest".equals(role)) {
            runGuest(seconds, secret, relayPort);
        } else {
            System.out.println("unknown role " + role);
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------ 房主

    private static void runHost(int seconds, String secret, int relayPort) throws Exception {
        // 假 MC：回声服务端（收到什么回什么）
        try (ServerSocket mcEcho = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread echo = new Thread(() -> {
                while (!mcEcho.isClosed()) {
                    try (Socket s = mcEcho.accept()) {
                        s.setTcpNoDelay(true);
                        byte[] buf = new byte[16384];
                        int n;
                        var out = s.getOutputStream();
                        var in = s.getInputStream();
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "probe-echo");
            echo.setDaemon(true);
            echo.start();

            P2PTunnel host = Tunnels.host(mcEcho.getLocalPort(), TunnelOptions.defaults(),
                    st -> System.out.println("[host] " + st.describe()));
            try {
                host.setSecret(secret);
                if (relayPort > 0) {
                    host.addCandidate(new Candidate(Candidate.Kind.RELAY, "127.0.0.1", relayPort));
                }

                // 等候选就绪
                long deadline = System.currentTimeMillis() + 15_000;
                while (host.candidates().isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                List<Candidate> cs = host.candidates();
                String code = RoomCode.create(cs)[0];
                System.out.println("HOST_READY mcPort=" + mcEcho.getLocalPort() + " candidates=" + cs.size());
                for (Candidate c : cs) {
                    System.out.println("  cand " + c.kind() + " " + c.address());
                }
                System.out.println("ROOM_CODE " + code);
                System.out.flush();

                // 等一下访客
                Thread.sleep(seconds * 1000L);
                System.out.println("[host] 结束，状态=" + host.status().describe());
            } finally {
                host.close();
            }
        }
    }

    // ------------------------------------------------------------------ 访客

    private static void runGuest(int seconds, String secret, int relayPort) throws Exception {
        if (relayPort <= 0) {
            System.out.println("guest 需要 relayPort（本探针只用中继候选，确保不依赖直连）");
            System.exit(2);
        }
        List<Candidate> cands = List.of(new Candidate(Candidate.Kind.RELAY, "127.0.0.1", relayPort));
        P2PTunnel guest = Tunnels.guest(cands, secret, TunnelOptions.defaults(),
                st -> System.out.println("[guest] " + st.describe()));
        try {
            long deadline = System.currentTimeMillis() + 20_000;
            while (!guest.isReady() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (!guest.isReady()) {
                System.out.println("GUEST_FAIL 隧道未打通：" + guest.status().describe());
                System.exit(1);
            }
            String local = guest.localJoinAddress();
            System.out.println("GUEST_READY localJoin=" + local + " state=" + guest.status().state());

            // 通过隧道连"假 MC"，双向校验
            String[] hp = local.split(":");
            try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
                s.setTcpNoDelay(true);
                s.setSoTimeout(30_000);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] payload = new byte[PAYLOAD];
                new java.util.Random(42).nextBytes(payload);
                long t0 = System.nanoTime();
                out.writeInt(PAYLOAD);
                out.write(payload);
                out.flush();
                int n = in.readInt();
                byte[] back = new byte[n];
                in.readFully(back);
                double secs = (System.nanoTime() - t0) / 1e9;
                boolean same = Arrays.equals(payload, back);
                System.out.printf("TRANSFER %d bytes back, equal=%s, %.0f KB/s%n",
                        n, same, (PAYLOAD / 1024.0) / Math.max(secs, 1e-6));
                if (!same || n != PAYLOAD) {
                    System.out.println("GUEST_FAIL 数据不一致");
                    System.exit(1);
                }
                System.out.println("GUEST_PASS");
            }
            Thread.sleep(Math.max(0, seconds * 1000L - 3000));
        } finally {
            guest.close();
        }
    }
}