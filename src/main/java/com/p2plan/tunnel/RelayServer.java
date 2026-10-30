package com.p2plan.tunnel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 中继服务端：直连打洞打不通时（双方都是对称型 NAT）的兜底 UDP 转发器，行为见 {@link RelayProtocol}。
 *
 * <p>它只认两种握手帧（{@link RelayProtocol#T_RELAY_JOIN} / {@link RelayProtocol#T_RELAY_ACK}），
 * 配对之后把两端点之间的载荷帧**原样搬运**，所以 {@link ReliableStream}/{@link TcpBridge} 完全不用感知中继。
 *
 * <p>线程模型：一条 socket + 一个收包线程（daemon）跑全部状态；{@link #stats()} 从别的线程读，故都走同一把锁。
 * 后台线程内部吞掉所有异常，坏包只丢不抛。
 *
 * <p>ponytail: 单线程收包+转发，单 socket 吞吐上限约几百 Mbps（自检里 ~3.5MB/s 的花销在 ReliableStream 侧，
 * 中继本身只是 memcpy）；升级路径是按房间分片或用 NIO/多 socket。
 *
 * <p>ponytail: 中继不鉴权也不加密，令牌只防串房（明文写在房间码里，见 {@link RelayProtocol}）；
 * 升级路径是配对后 ECDH + AEAD，令牌换 HMAC 挑战响应。
 */
public final class RelayServer implements AutoCloseable {

    /** 帧头与 {@link UdpTunnel}/{@link RelayProtocol} 冻结的一致（此处独立声明，避免依赖正在改动的 UdpTunnel）。 */
    private static final int MAGIC = 0x50324C31; // "P2L1"
    private static final int HDR = 20;
    private static final int TOKEN_LEN = 8;
    private static final int BUF = 2048; // ≥ ReliableStream 最大报文（HDR + 1100）
    private static final int SWEEP_MS = 1000;

    /** 一个口令令牌下的登记：先到的是 a，同令牌第二个不同端点成为 b 即配对；任一端失活只清它自己。 */
    private static final class Room {
        final byte[] token;
        InetSocketAddress a;
        InetSocketAddress b;
        long seenA;
        long seenB;

        Room(byte[] token) {
            this.token = token;
        }

        boolean paired() {
            return a != null && b != null;
        }
    }

    private final DatagramSocket socket;
    private final Thread loop;
    /** 令牌 → 房间；与 endpoint → 令牌 都只由收包线程改（配 stats() 时持同一把锁）。 */
    private final Map<ByteBuffer, Room> rooms = new HashMap<>();
    private final Map<InetSocketAddress, ByteBuffer> endpointToken = new HashMap<>();

    private volatile boolean closed;
    private long forwardedBytes;
    private long lastSweep;

    private RelayServer(DatagramSocket socket) {
        this.socket = socket;
        this.loop = new Thread(this::run, "relay-server");
        this.loop.setDaemon(true);
        this.loop.start();
    }

    /**
     * 绑定 {@code 0.0.0.0:port} 并开始服务。
     *
     * @param port 监听端口；{@code 0} 表示由系统分配一个随机端口（{@link #port()} 可查）
     */
    public static RelayServer start(int port) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("bad port: " + port);
        }
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
            s.setSoTimeout(SWEEP_MS); // 定期醒来做失活清理，顺便让 close() 能立刻退出
        } catch (IOException e) {
            s.close();
            throw e;
        }
        return new RelayServer(s);
    }

    /** 实际监听端口（{@code start(0)} 时就是系统分配的那个）。 */
    public int port() {
        return socket.getLocalPort();
    }

    /** 一行可读统计，例如 {@code "房间=1 客户端=2 转发=1.2 MB"}。 */
    public String stats() {
        synchronized (this) {
            return "房间=" + rooms.size() + " 客户端=" + endpointToken.size()
                    + " 转发=" + human(forwardedBytes);
        }
    }

    /** 停收包线程、关 socket；幂等。 */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            rooms.clear();
            endpointToken.clear();
        }
        socket.close();
        try {
            loop.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 收包线程

    private void run() {
        byte[] buf = new byte[BUF];
        while (!closed) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException idle) {
                sweep();
                continue;
            } catch (IOException e) {
                return; // socket 关了（close() 或错误）：静默收工，绝不外抛
            }
            try {
                synchronized (this) {
                    handle(buf, p.getOffset(), p.getLength(), (InetSocketAddress) p.getSocketAddress());
                    sweep();
                }
            } catch (Throwable t) {
                // 后台线程绝不外抛：坏包、内部错误都只丢这一个包
            }
        }
    }

    private void handle(byte[] buf, int off, int len, InetSocketAddress src) {
        if (len < HDR || Stun.readInt(buf, off) != MAGIC) {
            return; // 不是本协议的报文
        }
        long now = System.currentTimeMillis();
        int type = buf[off + 4] & 0xff;
        if (type == RelayProtocol.T_RELAY_JOIN) {
            int plen = Stun.readShort(buf, off + 6);
            if (plen < TOKEN_LEN || len < HDR + TOKEN_LEN) {
                return; // 口令令牌不完整
            }
            join(ByteBuffer.wrap(Arrays.copyOfRange(buf, off + HDR, off + HDR + TOKEN_LEN)), src, now);
            return;
        }

        ByteBuffer token = endpointToken.get(src);
        if (token == null) {
            return; // 没登记过的来源：非 JOIN 帧直接丢
        }
        Room room = rooms.get(token);
        if (room == null) {
            endpointToken.remove(src);
            return;
        }
        touch(room, src, now);
        InetSocketAddress other = src.equals(room.a) ? room.b : (src.equals(room.b) ? room.a : null);
        if (other == null) {
            return; // 登记了但还没配对（或对端已失活）：攒着，等配对后再转发
        }
        try {
            socket.send(new DatagramPacket(buf, off, len, other)); // 原样搬运，一个字节都不改
            forwardedBytes += len;
        } catch (IOException ignored) {
            // 发不出去（对端刚走等）继续服务其它房间
        }
    }

    private void join(ByteBuffer token, InetSocketAddress src, long now) {
        if (endpointToken.containsKey(src)) {
            ByteBuffer prev = endpointToken.get(src);
            if (!prev.equals(token)) {
                detach(src); // 同一端点换令牌：先退出旧房间
            }
        }
        Room room = rooms.get(token);
        if (room == null) {
            room = new Room(tokenBytes(token));
            rooms.put(ByteBuffer.wrap(room.token), room);
        }
        if (src.equals(room.a)) {
            room.seenA = now;
            if (room.paired()) {
                ack(src, room.token); // 幂等、抗丢包：配对后重复 JOIN 就重发 ACK
            }
            return;
        }
        if (src.equals(room.b)) {
            room.seenB = now;
            if (room.paired()) {
                ack(src, room.token);
            }
            return;
        }
        if (room.paired()) {
            return; // 一个令牌只服务一对端点，第三个端点忽略
        }
        if (room.a == null) {
            room.a = src;
            room.seenA = now;
            endpointToken.put(src, token);
        } else {
            room.b = src;
            room.seenB = now;
            endpointToken.put(src, token);
            endpointToken.put(room.a, token);
            ack(room.a, room.token);
            ack(room.b, room.token);
        }
    }

    /** 把端点从任何房间摘掉（配对随之解除；另一端保留登记，等它重新 JOIN 再配对）。 */
    private void detach(InetSocketAddress src) {
        ByteBuffer token = endpointToken.remove(src);
        if (token == null) {
            return;
        }
        Room room = rooms.get(token);
        if (room == null) {
            return;
        }
        if (src.equals(room.a)) {
            room.a = null;
            room.seenA = 0;
        } else if (src.equals(room.b)) {
            room.b = null;
            room.seenB = 0;
        }
        if (room.a == null && room.b == null) {
            rooms.remove(token);
        }
    }

    /** 失活清理：超过 {@link RelayProtocol#IDLE_TIMEOUT_MS} 没见到任何包的端点作废，配对解除。最多每秒扫一次。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < SWEEP_MS) {
            return;
        }
        lastSweep = now;
        var it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            Room room = it.next().getValue();
            if (room.a != null && now - room.seenA > RelayProtocol.IDLE_TIMEOUT_MS) {
                endpointToken.remove(room.a);
                room.a = null;
            }
            if (room.b != null && now - room.seenB > RelayProtocol.IDLE_TIMEOUT_MS) {
                endpointToken.remove(room.b);
                room.b = null;
            }
            if (room.a == null && room.b == null) {
                it.remove();
            }
        }
    }

    private void touch(Room room, InetSocketAddress src, long now) {
        if (src.equals(room.a)) {
            room.seenA = now;
        } else if (src.equals(room.b)) {
            room.seenB = now;
        }
    }

    private void ack(InetSocketAddress dst, byte[] token) {
        byte[] p = new byte[HDR + TOKEN_LEN];
        Stun.writeInt(p, 0, MAGIC);
        p[4] = RelayProtocol.T_RELAY_ACK;
        p[5] = 0;
        Stun.writeShort(p, 6, TOKEN_LEN);
        System.arraycopy(token, 0, p, HDR, TOKEN_LEN);
        try {
            socket.send(new DatagramPacket(p, p.length, dst));
        } catch (IOException ignored) {
        }
    }

    private static byte[] tokenBytes(ByteBuffer token) {
        byte[] out = new byte[TOKEN_LEN];
        token.duplicate().get(out);
        return out;
    }

    private static String human(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}