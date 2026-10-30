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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 跨进程 iroh 隧道验证：两个独立 JVM（房主进程 + 访客进程）用**真实 QUIC socket** 互通，
 * 走的是与游戏完全相同的代码路径 {@code Tunnels.host/guest → IrohTunnel → IrohBridge}。
 *
 * <p>这是 GUI 自动化输入进不去游戏时的最强证据：跨进程、真实 socket、生产代码。
 *
 * <p>用法（classpath = mod 的 runtimeClasspath）：
 * <pre>
 *   java com.p2plan.iroh.IrohCrossProbe host  &lt;交接文件&gt;
 *   java com.p2plan.iroh.IrohCrossProbe guest &lt;交接文件&gt;
 * </pre>
 * host 打印 {@code CROSS_HOST_READY} 并写交接文件（候选 + 假 MC 端口）；
 * guest 轮询交接文件、建隧道、连自己的本地入口做 512KB 校验，
 * 成功打印 {@code CROSS_GUEST_PASS}。
 */
public final class IrohCrossProbe {

    private static final int PAYLOAD = 512 * 1024;
    private static final String SECRET = "CROSS1";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: IrohCrossProbe host|guest <handoffFile>");
            System.exit(2);
        }
        if ("host".equals(args[0])) {
            host(Path.of(args[1]));
        } else {
            guest(Path.of(args[1]));
        }
    }

    // ------------------------------------------------------------------ 房主进程

    private static void host(Path handoff) throws Exception {
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
                        byte[] buf = new byte[32 * 1024];
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
        System.out.println("[host] 假 MC 端口 " + mc.getLocalPort());

        P2PTunnel t = Tunnels.host(mc.getLocalPort(), TunnelOptions.defaults(),
                st -> System.out.println("[host] " + st.describe()));
        long deadline = System.currentTimeMillis() + 25_000;
        List<Candidate> cs = List.of();
        while (System.currentTimeMillis() < deadline) {
            cs = t.candidates();
            if (!cs.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        if (cs.isEmpty()) {
            System.out.println("CROSS_HOST_FAIL 无候选");
            System.exit(1);
        }
        // 交接文件：假 MC 端口 + 候选（每行 kind|host|port）
        StringBuilder sb = new StringBuilder();
        sb.append(mc.getLocalPort()).append('\n');
        for (Candidate c : cs) {
            sb.append(c.kind().name()).append('|').append(c.host()).append('|').append(c.port()).append('\n');
        }
        Files.writeString(handoff, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("CROSS_HOST_READY candidates=" + cs.size());
        System.out.flush();

        // 活 180 秒等访客：gradle 冷启动 + 排队可能吃掉一两分钟，90 秒实测不够用
        Thread.sleep(180_000);
        t.close();
        mc.close();
        System.exit(0);
    }

    // ------------------------------------------------------------------ 访客进程

    private static void guest(Path handoff) throws Exception {
        List<String> lines = List.of();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(handoff)) {
                String txt = Files.readString(handoff, StandardCharsets.UTF_8);
                if (!txt.isBlank()) {
                    lines = Arrays.asList(txt.split("\n"));
                    break;
                }
            }
            Thread.sleep(300);
        }
        if (lines.isEmpty()) {
            System.out.println("CROSS_GUEST_FAIL 等不到交接文件");
            System.exit(1);
        }
        int mcPort = Integer.parseInt(lines.get(0).trim());
        System.out.println("[guest] 房主假 MC 端口 " + mcPort + "（仅用于校验目标）");

        List<Candidate> cs = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\\|");
            cs.add(new Candidate(Candidate.Kind.valueOf(p[0]), p[1], Integer.parseInt(p[2])));
        }
        System.out.println("[guest] 读到候选 " + cs.size() + " 条，建 iroh 隧道…");

        P2PTunnel t = Tunnels.guest(cs, SECRET, TunnelOptions.defaults(),
                st -> System.out.println("[guest] " + st.describe()));
        String local = null;
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (t.isReady()) {
                local = t.localJoinAddress();
                if (local != null) {
                    break;
                }
            }
            Thread.sleep(200);
        }
        if (local == null) {
            System.out.println("CROSS_GUEST_FAIL 隧道未就绪");
            System.exit(1);
        }
        System.out.println("[guest] 本地入口 " + local + "，传 " + (PAYLOAD / 1024) + " KB 校验…");
        String[] hp = local.split(":");
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(60_000);
            byte[] payload = new byte[PAYLOAD];
            new java.util.Random(11).nextBytes(payload);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(payload);
            out.flush();
            byte[] back = new byte[PAYLOAD];
            in.readFully(back);
            long ms = System.currentTimeMillis() - t0;
            if (Arrays.equals(payload, back)) {
                System.out.printf("CROSS_GUEST_PASS %d bytes %dms %dKB/s%n",
                        PAYLOAD, ms, PAYLOAD / 1024L * 1000L / Math.max(ms, 1));
            } else {
                System.out.println("CROSS_GUEST_FAIL 数据不一致");
            }
        } catch (Exception e) {
            System.out.println("CROSS_GUEST_FAIL 传输异常 " + e);
        }
        t.close();
        System.exit(0);
    }
}
