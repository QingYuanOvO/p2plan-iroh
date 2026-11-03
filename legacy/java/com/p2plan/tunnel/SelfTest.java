package com.p2plan.tunnel;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 隧道自检（{@code build.bat tunnelSelfTest}）：不加载 Minecraft，只依赖 java.* 和 tunnel 包。
 *
 * <p>跑的是「STUN 发现 → 打洞 → 可靠字节流 → TCP 桥」整条链路：
 * 本机起一个假 STUN 服务端（回 XOR-MAPPED-ADDRESS＝它看到的源地址，顺便反证「同一条 socket 发现」）、
 * 一个假 MC 回声服务端，然后 {@link Tunnels#host} + {@link Tunnels#guest} 走回环打通，
 * 最后用普通 Socket 连 {@link P2PTunnel#localJoinAddress()} 双向各 4MB 逐字节校验。
 */
public final class SelfTest {

    private static final int BYTES = 4 * 1024 * 1024;
    private static final int RELAY_BYTES = 2 * 1024 * 1024;
    private static final String SECRET = "P2PTEST";

    private SelfTest() {
    }

    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(180_000);
            } catch (InterruptedException ignored) {
            }
            System.out.println("SELF-TEST FAIL: 超时（180 秒）");
            Runtime.getRuntime().halt(1);
        }, "selftest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            checkStunParsing();
            System.out.println("[1/5] STUN 解析（IPv4/IPv6、XOR/兜底、RFC 5769 样例）通过");

            try (StubStun stun = new StubStun()) {
                TunnelOptions opts = new TunnelOptions(List.of("127.0.0.1:" + stun.port()), 1000, 8000, 1000);

                try (EchoServer mc = new EchoServer()) {
                    P2PTunnel host = Tunnels.host(mc.port(), opts, s -> {
                    });
                    ((UdpTunnel) host).setSecret(SECRET);
                    List<Candidate> candidates = waitForCandidates(host, 5000);
                    System.out.println("[2/5] 房主候选 " + candidates.size() + " 条:");
                    for (Candidate c : candidates) {
                        System.out.println("        - " + c.describe());
                    }

                    P2PTunnel guest = Tunnels.guest(candidates, SECRET, opts, s -> {
                    });
                    System.out.println("[3/5] 访客本机入口 " + guest.localJoinAddress());

                    long deadline = System.currentTimeMillis() + 20_000;
                    while (!guest.isReady() && System.currentTimeMillis() < deadline) {
                        if (guest.status().state() == TunnelState.FAILED
                                || host.status().state() == TunnelState.FAILED) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    if (!guest.isReady()) {
                        fail("20 秒内未打通\n  房主: " + host.status().describe()
                                + "\n  访客: " + guest.status().describe());
                    }
                    System.out.println("[4/5] 已打通：" + guest.status().describe());

                    long start = System.nanoTime();
                    long[] counters = roundTrip(guest.localJoinAddress(), BYTES);
                    double seconds = (System.nanoTime() - start) / 1e9;
                    long expected = BYTES;
                    long serverGot = mc.received();
                    if (counters[0] != expected || counters[1] != expected || serverGot != expected) {
                        fail("字节数不符：客户端发送=" + counters[0] + " 客户端收到=" + counters[1]
                                + " 服务端收到=" + serverGot + "，期望各 " + expected);
                    }
                    if (mc.errors()) {
                        fail("假 MC 服务端出错：" + mc.error());
                    }
                    double kbPerSec = (2.0 * BYTES / 1024.0) / Math.max(seconds, 1e-6);
                    System.out.println("[5/5] 双向各 " + (BYTES / 1024 / 1024) + "MB 逐字节一致（"
                            + counters[1] + " B 回程校验通过）");

                    guest.close();
                    host.close();
                    System.out.printf("SELF-TEST PASS  %d 字节双向一致  用时 %.2f s  吞吐 %.0f KB/s%n",
                            BYTES, seconds, kbPerSec);
                }
            }
            checkRelay();
        } catch (Throwable t) {
            fail(String.valueOf(t), t);
        }
        // 后台线程全是 daemon，这里直接收工
        Runtime.getRuntime().halt(0);
    }

    // ------------------------------------------------------------------ 数据通路

    /**
     * 连访客本机入口，双向跑 {@code bytes} 字节。
     *
     * @return {客户端发送的字节, 客户端回程校验通过的字节}
     */
    private static long[] roundTrip(String joinAddress, int bytes) throws Exception {
        int colon = joinAddress.lastIndexOf(':');
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(InetAddress.getByName(joinAddress.substring(0, colon)),
                Integer.parseInt(joinAddress.substring(colon + 1))), 5000);

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicLong received = new AtomicLong();

        Thread writer = new Thread(() -> {
            try {
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[32 * 1024];
                long sent = 0;
                while (sent < bytes) {
                    for (int i = 0; i < buf.length; i++) {
                        buf[i] = pattern(sent + i);
                    }
                    out.write(buf);
                    sent += buf.length;
                }
                out.flush();
                socket.shutdownOutput(); // 半关闭：让对端知道上行发完了
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "selftest-writer");

        Thread reader = new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[32 * 1024];
                long got = 0;
                while (got < bytes) {
                    int n = in.read(buf);
                    if (n < 0) {
                        error.compareAndSet(null, new IOException("回程提前 EOF，只收到 " + got + " 字节"));
                        return;
                    }
                    if (n == 0) {
                        continue;
                    }
                    for (int i = 0; i < n; i++) {
                        if (buf[i] != pattern(got + i)) {
                            error.compareAndSet(null, new IOException(
                                    "回程第 " + (got + i) + " 字节不一致：" + buf[i] + " != " + pattern(got + i)));
                            return;
                        }
                    }
                    got += n;
                }
                received.set(got);
                if (in.read() != -1) {
                    error.compareAndSet(null, new IOException("回程数据之后没有收到 FIN"));
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "selftest-reader");

        writer.start();
        reader.start();
        writer.join(60_000);
        reader.join(60_000);
        if (writer.isAlive() || reader.isAlive()) {
            fail("数据搬运卡住（写线程活着=" + writer.isAlive() + "，读线程活着=" + reader.isAlive() + "）");
        }
        socket.close();
        if (error.get() != null) {
            throw new IOException(error.get());
        }
        return new long[]{bytes, received.get()};
    }

    private static byte pattern(long i) {
        return (byte) (i * 31 + 7);
    }

    // ------------------------------------------------------------------ 中继场景（RELAY）

    /**
     * 中继自检：访客候选里**只有中继地址**（不可能走直连），验证
     * 「JOIN 配对 → 中继透明转发 → ReliableStream/TcpBridge → 假 MC 回声」整条链路。
     *
     * <p>走 {@link Tunnels#host}/{@link Tunnels#guest} 的公开契约，不碰 {@link UdpTunnel} 内部。
     *
     * <p>ponytail: 只做正向打通（JOIN 配对 / 幂等 ACK / 透明转发 / 双向 2MB）；没做中继侧丢包注入、
     * 也没测 {@link RelayProtocol#IDLE_TIMEOUT_MS} 失活回收（要等 30 秒才见效，跑得太慢）。
     * 升级路径：自检里对 RelayServer 直接发伪造 JOIN/DATA 帧，用两个假端点覆盖失活与多房间隔离。
     */
    private static void checkRelay() {
        try (StubStun stun = new StubStun()) {
            TunnelOptions opts = new TunnelOptions(List.of("127.0.0.1:" + stun.port()), 1000, 15_000, 1000);
            String secret = "RELAYTEST";
            try (RelayServer relay = RelayServer.start(0); EchoServer mc = new EchoServer()) {
                Candidate relayCandidate = new Candidate(Candidate.Kind.RELAY, "127.0.0.1", relay.port());
                System.out.println("[relay] 中继端口 " + relay.port() + "，访客候选只有 " + relayCandidate.describe());

                P2PTunnel host = Tunnels.host(mc.port(), opts, s -> {
                });
                host.setSecret(secret); // 口令必须与访客一致，否则令牌不匹配、中继不会配对
                host.addCandidate(relayCandidate);

                P2PTunnel guest = Tunnels.guest(List.of(relayCandidate), secret, opts, s -> {
                });
                System.out.println("[relay] 访客本机入口 " + guest.localJoinAddress());

                long deadline = System.currentTimeMillis() + 20_000;
                while (!guest.isReady() && System.currentTimeMillis() < deadline) {
                    if (guest.status().state() == TunnelState.FAILED
                            || host.status().state() == TunnelState.FAILED) {
                        break;
                    }
                    Thread.sleep(100);
                }
                if (!guest.isReady()) {
                    relayFail("20 秒内未经中继打通\n  房主: " + host.status().describe()
                            + "\n  访客: " + guest.status().describe() + "\n  中继: " + relay.stats());
                }
                System.out.println("[relay] 已打通：" + guest.status().describe() + "；中继 " + relay.stats());

                long start = System.nanoTime();
                long[] counters = roundTrip(guest.localJoinAddress(), RELAY_BYTES);
                double seconds = (System.nanoTime() - start) / 1e9;
                long serverGot = mc.received();
                if (counters[0] != RELAY_BYTES || counters[1] != RELAY_BYTES || serverGot != RELAY_BYTES) {
                    relayFail("字节数不符：客户端发送=" + counters[0] + " 客户端收到=" + counters[1]
                            + " 服务端收到=" + serverGot + "，期望各 " + RELAY_BYTES);
                }
                if (mc.errors()) {
                    relayFail("假 MC 服务端出错：" + mc.error());
                }
                double kbPerSec = (2.0 * RELAY_BYTES / 1024.0) / Math.max(seconds, 1e-6);

                guest.close();
                host.close();
                relay.close();
                relay.close(); // 幂等：第二次调用必须还是无异常
                System.out.println("[relay] 关闭后统计（应为空）：" + relay.stats());
                System.out.printf("RELAY SELF-TEST PASS  %d 字节双向一致  用时 %.2f s  吞吐 %.0f KB/s%n",
                        RELAY_BYTES, seconds, kbPerSec);
            }
        } catch (Throwable t) {
            relayFail(String.valueOf(t), t);
        }
    }

    // ------------------------------------------------------------------ 假 STUN 服务端

    /**
     * 本机假 STUN：把「它看到的数据包源地址」XOR 后回给请求方。
     * 这同时验证了 STUN 解析、以及「请求确实从打洞用的那条 socket 发出」（回显的端口就是那条 socket 的端口）。
     */
    private static final class StubStun implements Closeable {
        private final DatagramSocket socket;

        StubStun() throws IOException {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            Thread t = new Thread(() -> {
                byte[] buf = new byte[1024];
                while (!socket.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (IOException e) {
                        return;
                    }
                    byte[] d = p.getData();
                    int off = p.getOffset();
                    int len = p.getLength();
                    if (len - off < 20 || Stun.readShort(d, off) != Stun.BINDING_REQUEST) {
                        continue;
                    }
                    InetSocketAddress src = (InetSocketAddress) p.getSocketAddress();
                    byte[] resp = response(d, off, src);
                    try {
                        socket.send(new DatagramPacket(resp, resp.length, src));
                    } catch (IOException ignored) {
                    }
                }
            }, "stub-stun");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private static byte[] response(byte[] req, int off, InetSocketAddress src) {
            byte[] resp = new byte[20 + 12];
            Stun.writeShort(resp, 0, Stun.BINDING_RESPONSE);
            Stun.writeShort(resp, 2, 12);
            Stun.writeInt(resp, 4, Stun.MAGIC_COOKIE);
            System.arraycopy(req, off + 8, resp, 8, 12);
            Stun.writeShort(resp, 20, 0x0020);
            Stun.writeShort(resp, 22, 8);
            resp[24] = 0;
            resp[25] = 0x01;
            Stun.writeShort(resp, 26, src.getPort() ^ (Stun.MAGIC_COOKIE >>> 16));
            byte[] raw = src.getAddress().getAddress();
            for (int i = 0; i < 4; i++) {
                resp[28 + i] = (byte) (raw[i] ^ (byte) (Stun.MAGIC_COOKIE >>> (24 - 8 * i)));
            }
            return resp;
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    // ------------------------------------------------------------------ 假 MC 服务端

    /** 本机 TCP 回声服务端（端口 0 由系统分配），记录收到的字节数。 */
    private static final class EchoServer implements Closeable {
        private final ServerSocket server;
        private final AtomicLong received = new AtomicLong();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Thread thread;

        EchoServer() throws IOException {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    Socket s;
                    try {
                        s = server.accept();
                    } catch (IOException e) {
                        return;
                    }
                    Thread h = new Thread(() -> handle(s), "stub-mc-conn");
                    h.setDaemon(true);
                    h.start();
                }
            }, "stub-mc");
            thread.setDaemon(true);
            thread.start();
        }

        private void handle(Socket s) {
            try {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[32 * 1024];
                int n;
                long got = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) {
                        continue;
                    }
                    out.write(buf, 0, n);
                    out.flush();
                    got += n;
                    received.set(got);
                }
                out.flush();
                s.shutdownOutput();
                s.close();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }

        int port() {
            return server.getLocalPort();
        }

        long received() {
            return received.get();
        }

        boolean errors() {
            return error.get() != null;
        }

        Throwable error() {
            return error.get();
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ STUN 解析用例

    private static void checkStunParsing() throws Exception {
        byte[] tx = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c};

        // RFC 5769 §2.2 的 XOR-MAPPED-ADDRESS 字节，解出来必须是 192.0.2.1:32853
        byte[] rfc = {0x00, 0x20, 0x00, 0x08, 0x00, 0x01, (byte) 0xa1, 0x47,
                (byte) 0xe1, 0x12, (byte) 0xa6, 0x43};
        check(stunMessage(rfc, tx), "192.0.2.1", 32853, "RFC 5769 XOR-MAPPED-ADDRESS(IPv4)");

        // MAPPED-ADDRESS(0x0001) 兜底（IPv4 明文）
        byte[] mappedV4 = {0x00, 0x01, 0x00, 0x08, 0x00, 0x01, 0x12, 0x34,
                (byte) 203, 0x00, 113, 9};
        check(stunMessage(mappedV4, tx), "203.0.113.9", 0x1234, "MAPPED-ADDRESS(IPv4)");

        // 同时存在时 0x0020 优先
        byte[] both = new byte[rfc.length + mappedV4.length];
        System.arraycopy(rfc, 0, both, 0, rfc.length);
        System.arraycopy(mappedV4, 0, both, rfc.length, mappedV4.length);
        check(stunMessage(both, tx), "192.0.2.1", 32853, "XOR 优先于 MAPPED");

        // XOR-MAPPED-ADDRESS(IPv6)：掩码 = magic cookie ‖ transaction id
        InetAddress v6 = InetAddress.getByName("2001:db8::1");
        byte[] mask = new byte[16];
        System.arraycopy(cookie(), 0, mask, 0, 4);
        System.arraycopy(tx, 0, mask, 4, 12);
        byte[] x6 = xor(v6.getAddress(), mask);
        int xport = 5000 ^ (Stun.MAGIC_COOKIE >>> 16);
        byte[] xorV6 = new byte[4 + 4 + 16];
        Stun.writeShort(xorV6, 0, 0x0020);
        Stun.writeShort(xorV6, 2, 20);
        xorV6[4] = 0;
        xorV6[5] = 0x02;
        Stun.writeShort(xorV6, 6, xport);
        System.arraycopy(x6, 0, xorV6, 8, 16);
        check(stunMessage(xorV6, tx), "2001:db8:0:0:0:0:0:1", 5000, "XOR-MAPPED-ADDRESS(IPv6)");

        // MAPPED-ADDRESS(IPv6) 明文
        byte[] mV6 = new byte[4 + 4 + 16];
        Stun.writeShort(mV6, 0, 0x0001);
        Stun.writeShort(mV6, 2, 20);
        mV6[4] = 0;
        mV6[5] = 0x02;
        Stun.writeShort(mV6, 6, 6000);
        System.arraycopy(InetAddress.getByName("2001:db8::2").getAddress(), 0, mV6, 8, 16);
        check(stunMessage(mV6, tx), "2001:db8:0:0:0:0:0:2", 6000, "MAPPED-ADDRESS(IPv6)");

        // 截断/无关报文必须返回 null，不能抛
        if (Stun.parse(new byte[10], 0, 10) != null) {
            fail("截断报文应当解析失败");
        }
        byte[] notStun = new byte[32];
        Stun.writeShort(notStun, 0, 0x0001);
        if (Stun.parse(notStun, 0, notStun.length) != null) {
            fail("Binding Request 不应被当成应答解析");
        }
    }

    private static void check(byte[] message, String host, int port, String what) throws Exception {
        InetSocketAddress parsed = Stun.parse(message, 0, message.length);
        if (parsed == null) {
            fail(what + "：解析返回 null");
        }
        String gotHost = parsed.getAddress() instanceof java.net.Inet6Address
                ? normalizeV6(parsed.getAddress().getHostAddress())
                : parsed.getAddress().getHostAddress();
        String wantHost = host.contains("::") ? normalizeV6(host) : host;
        if (!gotHost.equalsIgnoreCase(wantHost) || parsed.getPort() != port) {
            fail(what + "：期望 " + wantHost + ":" + port + "，实际 " + gotHost + ":" + parsed.getPort());
        }
    }

    private static String normalizeV6(String s) {
        try {
            return InetAddress.getByName(s).getHostAddress();
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] stunMessage(byte[] attrs, byte[] tx) {
        byte[] m = new byte[20 + attrs.length];
        Stun.writeShort(m, 0, Stun.BINDING_RESPONSE);
        Stun.writeShort(m, 2, attrs.length);
        Stun.writeInt(m, 4, Stun.MAGIC_COOKIE);
        System.arraycopy(tx, 0, m, 8, 12);
        System.arraycopy(attrs, 0, m, 20, attrs.length);
        return m;
    }

    private static byte[] cookie() {
        return new byte[]{(byte) (Stun.MAGIC_COOKIE >>> 24), (byte) (Stun.MAGIC_COOKIE >>> 16),
                (byte) (Stun.MAGIC_COOKIE >>> 8), (byte) Stun.MAGIC_COOKIE};
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------ 杂项

    private static List<Candidate> waitForCandidates(P2PTunnel host, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (host.candidates().isEmpty() && System.currentTimeMillis() < deadline) {
            if (host.status().state() == TunnelState.FAILED) {
                fail("房主候选发现失败：" + host.status().describe());
            }
            Thread.sleep(50);
        }
        List<Candidate> cs = host.candidates();
        if (cs.isEmpty()) {
            fail("5 秒内没有发现任何候选地址");
        }
        return cs;
    }

    private static void fail(String reason) {
        fail(reason, null);
    }

    private static void fail(String reason, Throwable t) {
        System.out.println("SELF-TEST FAIL: " + reason);
        if (t != null) {
            t.printStackTrace(System.out);
        }
        Runtime.getRuntime().halt(1);
    }

    private static void relayFail(String reason) {
        relayFail(reason, null);
    }

    private static void relayFail(String reason, Throwable t) {
        System.out.println("RELAY SELF-TEST FAIL: " + reason);
        if (t != null) {
            t.printStackTrace(System.out);
        }
        Runtime.getRuntime().halt(1);
    }
}