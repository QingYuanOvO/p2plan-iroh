package com.p2plan.tunnel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * UDP 打洞隧道：IDLE → DISCOVERING → PUNCHING → CONNECTED（→ CLOSED / FAILED）。
 *
 * <p>打洞原理（按 openp2p/EasyTier 那套精简下来的）：
 * <ul>
 *   <li><b>一条 IPv4 UDP socket 干全部事</b>：STUN 发现、探测、搬运都在这条 socket 上，
 *       所以 STUN 拿到的公网映射就是打洞用的映射。</li>
 *   <li>房主：DISCOVERING 里发布「全球 IPv6 直连地址（端口 = MC 端口，由访客直接 TCP 连，不走隧道）
 *       + STUN 反射地址 + 局域网 IPv4」；然后待机。收到合法 HELLO 就地学习对方端点、立即回 HELLO_ACK，进 CONNECTED。</li>
 *   <li>访客：不做 STUN（省 3 秒），直接按房间码里的候选依次发 HELLO 探测；收到 HELLO_ACK 时
 *       从<b>回包的源地址</b>学习对端真实端点（对称 NAT 下对端映射端口 ≠ 房间码里那个），进 CONNECTED。</li>
 * </ul>
 *
 * <p>握手带房间口令的令牌校验，令牌不符的报文直接丢弃。
 * ponytail: 令牌只是握手校验（SHA-256 前 8 字节），不是加密通道；隧道载荷是明文 UDP，
 * 需要防窃听/防篡改时要在 ReliableStream 之上加 AEAD（例如每段一个 XChaCha20-Poly1305）。
 *
 * <p>ponytail: 只做 IPv4 打洞、不做端口预测/多端口扫描（本机房主是对称 NAT，这路子本来就不通）；
 * 双边对称 NAT 走中继：见 {@link RelayProtocol}（协议）与 {@link RelayServer}（服务端）。
 * 中继对隧道是透明的，所以上面两层（ReliableStream / TcpBridge）不需要知道它的存在。
 */
public final class UdpTunnel implements P2PTunnel {

    static final int MAGIC = 0x50324C31; // "P2L1"
    static final byte T_HELLO = 1;
    static final byte T_HELLO_ACK = 2;
    static final byte T_DATA = 3;
    static final byte T_ACK = 4;
    static final byte T_FIN = 5;
    static final byte T_FIN_ACK = 6;
    static final byte T_KA = 7;
    /** 协议头：magic(4) type(1) 保留(1) 载荷长度(2) ack(4) 窗口(4) seq(4)。 */
    static final int HDR = 20;
    private static final int TOKEN_LEN = 8;
    private static final int MAX_CANDIDATES = 8; // RoomCode 的上限
    private static final int PROBE_INTERVAL_MS = 250;
    private static final int JOIN_PORT = 25599;

    private final boolean host;
    private final int mcPort;
    private final List<Candidate> given;
    private final TunnelOptions opts;
    private final Consumer<TunnelStatus> sink;

    private final Object lock = new Object();
    private final LongAdder rxBytes = new LongAdder();
    private final LongAdder txBytes = new LongAdder();
    private final BlockingQueue<DatagramPacket> stunInbox = new LinkedBlockingQueue<>();

    private final DatagramSocket udp4;
    private final ServerSocket listener;

    private volatile TunnelState state = TunnelState.IDLE;
    private volatile String detail = "";
    private volatile Candidate path;
    private volatile String peerText;
    private volatile List<Candidate> myCandidates = List.of();
    /** 房主侧追加的对外候选（典型是中继地址），见 {@link #addCandidate}。 */
    private final List<Candidate> extra = new CopyOnWriteArrayList<>();
    /** 中继端点：房主报名用它，访客被配对后 peer 就是它。没有中继时为 null。 */
    private volatile InetSocketAddress relayAddr;
    private volatile Candidate relayCandidate;
    private volatile InetSocketAddress peer;
    private volatile Candidate lastProbed;
    private volatile Map<InetSocketAddress, Candidate> probeTargets = Map.of();
    private volatile byte[] expectedToken;
    private volatile ReliableStream stream;
    private volatile TcpBridge bridge;
    private volatile boolean connected;
    private volatile boolean closing;
    private volatile String stunNote = "";
    /** 本机 NAT 类型（房主侧在候选发现阶段判定，访客侧保持 UNTESTED）。 */
    private volatile NatType natType = NatType.UNTESTED;
    /** UPnP 映射结果（成功才有值），关隧道时用于撤销。 */
    private volatile Upnp.Mapping upnpMapping;
    /** UPnP 开通的公网地址，作为一条额外候选发给对方。 */
    private volatile Candidate upnpCandidate;

    UdpTunnel(boolean host, int mcPort, List<Candidate> given, String secret,
              TunnelOptions opts, Consumer<TunnelStatus> sink) {
        this.host = host;
        this.mcPort = mcPort;
        this.given = given == null ? List.of() : List.copyOf(given);
        this.opts = opts;
        this.sink = sink;
        if (!host) {
            this.expectedToken = tokenOf(secret);
        }
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0));
        } catch (Exception e) {
            s = null;
        }
        this.udp4 = s;
        this.listener = host ? null : bindJoinListener();
        if (udp4 == null) {
            failNow("UDP socket 绑定失败");
        } else if (!host && listener == null) {
            failNow("本机转发端口绑定失败");
        }
    }

    UdpTunnel startTunnel() {
        if (state == TunnelState.FAILED) {
            return this;
        }
        BlockingQueue<DatagramPacket> inbox = host ? stunInbox : null;
        Thread rx = new Thread(() -> receiveLoop(inbox), "p2p-udp-rx");
        rx.setDaemon(true);
        rx.start();
        Thread main = new Thread(host ? this::runHost : this::runGuest, "p2p-tunnel-main");
        main.setDaemon(true);
        main.start();
        return this;
    }

    // ------------------------------------------------------------ 对外契约

    @Override
    public TunnelStatus status() {
        return new TunnelStatus(state, path, peerText, rxBytes.sum(), txBytes.sum(), detail);
    }

    @Override
    public List<Candidate> candidates() {
        return host ? myCandidates : given;
    }

    @Override
    public String localJoinAddress() {
        return host || listener == null ? null : "127.0.0.1:" + listener.getLocalPort();
    }

    @Override
    public boolean isReady() {
        return connected && (state == TunnelState.CONNECTED || state == TunnelState.RELAY);
    }

    /**
     * 房主侧可选：设置后只接受握手令牌匹配的探测包（HostService 生成房间口令后调用即可）。
     * 不设置时房主接受任何令牌 —— 此时口令仍由 MC 协议层（{@code HostService.onHandshake}）裁决。
     */
    public void setSecret(String secret) {
        this.expectedToken = tokenOf(secret);
    }

    @Override
    public void addCandidate(Candidate candidate) {
        if (candidate == null || extra.contains(candidate)) {
            return;
        }
        extra.add(candidate);
        if (candidate.kind() == Candidate.Kind.RELAY) {
            relayCandidate = candidate;
            relayAddr = resolveV4(candidate);
            if (relayAddr == null) {
                noteBridge("中继地址无法解析或不是 IPv4：" + candidate.address());
            }
        }
    }

    @Override
    public NatType natType() {
        return natType;
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        shutdown();
        if (state != TunnelState.FAILED) {
            setState(TunnelState.CLOSED, "已关闭");
        }
    }

    // ------------------------------------------------------------ 状态机

    private void runHost() {
        try {
            setState(TunnelState.DISCOVERING, "正在发现本机地址与公网映射…");
            // UPnP 先试：一旦路由器肯帮忙把端口映射出去，本机在公网上就是可达的，
            // 对方直接连即可，连打洞都不需要 —— 这是提升成功率最有效的一步。
            String upnpNote = tryUpnp();
            List<Candidate> mine = publishCandidates();
            myCandidates = mine;
            if (mine.isEmpty()) {
                fail("没有可用候选地址");
                return;
            }
            setState(TunnelState.PUNCHING,
                    "已发布 " + mine.size() + " 条候选（" + stunNote + upnpNote + "）"
                            + (relayAddr != null ? "，已向中继报名" : "") + "，等待访客的探测包…");
            startRelayJoinLoop();
            awaitConnected(opts.punchTimeoutMs(),
                    "打洞超时：没有收到对方的探测包。可能是双方都在对称型 NAT 之后（这类网络无法打洞），"
                            + "可尝试：① 双方都开 IPv6；② 在路由器里给本机开放 UDP " + udp4.getLocalPort() + " 端口");
            if (!connected) {
                return;
            }
            startKeepalive();
            dialLocalMc();
        } catch (Throwable t) {
            fail("房主侧异常：" + t);
        }
    }

    /**
     * 尝试用 UPnP 把隧道 UDP 端口映射到公网。
     *
     * @return 给状态栏看的一句话（成功/跳过）
     */
    private String tryUpnp() {
        try {
            Upnp.Mapping m = Upnp.mapUdp(udp4.getLocalPort(), "p2plan tunnel");
            if (m == null) {
                return "";
            }
            upnpMapping = m;
            // 映射成功：对方可以直接连「公网 IP:端口」，把它作为一条反射地址候选加进去
            if (m.externalIp() != null && m.externalIp().matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                upnpCandidate = new Candidate(Candidate.Kind.IPV4_REFLEXIVE,
                        m.externalIp(), m.externalPort());
            }
            return "，UPnP 已开端口 " + m.externalPort();
        } catch (Throwable t) {
            return "";
        }
    }

    private void runGuest() {
        try {
            setState(TunnelState.DISCOVERING, "访客侧跳过 STUN，直接用房间码里的候选打洞");
            Map<InetSocketAddress, Candidate> targets = new LinkedHashMap<>();
            for (Candidate c : given) {
                if (c.kind() == Candidate.Kind.IPV6) {
                    continue; // 全球 IPv6 是访客直接 TCP 连的直连路径，不走隧道
                }
                InetSocketAddress a = resolveV4(c);
                if (a != null && !targets.containsKey(a)) {
                    targets.put(a, c);
                    if (c.kind() == Candidate.Kind.RELAY && relayCandidate == null) {
                        relayCandidate = c;
                        relayAddr = a;
                    }
                }
            }
            if (targets.isEmpty()) {
                fail("房间码里没有可打洞的 IPv4 候选（只有 IPv6 直连地址时请直接连接）");
                return;
            }
            probeTargets = targets;
            setState(TunnelState.PUNCHING, "正在依次探测 " + targets.size() + " 条候选…");
            List<InetSocketAddress> order = new ArrayList<>(targets.keySet());
            long deadline = System.currentTimeMillis() + opts.punchTimeoutMs();
            int i = 0;
            while (!connected && !closing && System.currentTimeMillis() < deadline) {
                InetSocketAddress target = order.get(i++ % order.size());
                Candidate tc = targets.get(target);
                lastProbed = tc;
                // 中继用 JOIN 报名；其余候选才是直连打洞的 HELLO
                sendHandshake(target, tc != null && tc.kind() == Candidate.Kind.RELAY
                        ? RelayProtocol.T_RELAY_JOIN : T_HELLO);
                synchronized (lock) {
                    lock.wait(PROBE_INTERVAL_MS);
                }
            }
            if (!connected) {
                fail("打洞超时：候选地址都没有应答（对端是对称型 NAT 时无法直连）");
                return;
            }
            startKeepalive();
            acceptLoop();
        } catch (Throwable t) {
            fail("访客侧异常：" + t);
        }
    }

    /**
     * 向中继按节奏报名（幂等、抗丢包）：房主先到中继处等着，访客从中继过来时才能被配对。
     * 打通后（{@link #markConnected} 会 notify）循环自行退出。
     */
    private void startRelayJoinLoop() {
        InetSocketAddress relay = relayAddr;
        if (relay == null) {
            return;
        }
        Thread t = new Thread(() -> {
            while (!connected && !closing) {
                sendHandshake(relay, RelayProtocol.T_RELAY_JOIN);
                synchronized (lock) {
                    try {
                        lock.wait(RelayProtocol.JOIN_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "p2p-relay-join");
        t.setDaemon(true);
        t.start();
    }

    private void awaitConnected(int timeoutMs, String timeoutReason) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!connected && !closing && System.currentTimeMillis() < deadline) {
                lock.wait(200);
            }
        }
        if (!connected && !closing) {
            fail(timeoutReason);
        }
    }

    private void markConnected(InetSocketAddress peerAddr, Candidate pathCandidate) {
        synchronized (lock) {
            if (connected || closing) {
                return;
            }
            peer = peerAddr;
            path = host ? null : pathCandidate;
            peerText = peerAddr.getAddress().getHostAddress() + ":" + peerAddr.getPort();
            stream = new ReliableStream(this::sendToPeer);
            connected = true;
            boolean viaRelay = relayAddr != null && peerAddr.equals(relayAddr);
            state = viaRelay ? TunnelState.RELAY : TunnelState.CONNECTED;
            detail = viaRelay ? ("已建立中继通道（经 " + peerText + " 转发）") : "已建立直连隧道";
            lock.notifyAll();
        }
        emit();
    }

    // ------------------------------------------------------------ 收发

    private void receiveLoop(BlockingQueue<DatagramPacket> inbox) {
        byte[] buf = new byte[2048];
        while (!closing) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                udp4.receive(p);
            } catch (IOException e) {
                if (!closing) {
                    fail("UDP 收包失败：" + e);
                }
                return;
            }
            int off = p.getOffset();
            int len = p.getLength();
            byte[] d = p.getData();
            if (Stun.isStun(d, off, len)) {
                if (inbox != null) {
                    inbox.add(new DatagramPacket(Arrays.copyOfRange(d, off, off + len), len, p.getSocketAddress()));
                }
                continue;
            }
            if (len - off < HDR || Stun.readInt(d, off) != MAGIC) {
                continue;
            }
            try {
                handlePacket(d, off, len, (InetSocketAddress) p.getSocketAddress());
            } catch (Throwable t) {
                // 后台线程绝不外抛；坏包/内部错误都只记录
                noteBridge("收包处理异常：" + t);
            }
        }
    }

    private void handlePacket(byte[] d, int off, int len, InetSocketAddress src) {
        int type = d[off + 4] & 0xff;
        int plen = Stun.readShort(d, off + 6);
        if (type == T_HELLO || type == T_HELLO_ACK || type == RelayProtocol.T_RELAY_ACK) {
            if (plen < TOKEN_LEN || len - off < HDR + TOKEN_LEN || !tokenOk(d, off + HDR)) {
                return; // 口令不符：直接丢
            }
            if (type == T_HELLO) {
                learnAndConnect(src, probeTargets.get(src));
                sendHandshake(src, T_HELLO_ACK); // 立即回包，借这个包把房主侧 NAT 也打开
            } else if (type == RelayProtocol.T_RELAY_ACK) {
                // 中继说「已配对」：从此把中继当作 peer，载荷由它透明转发给另一侧
                relayAddr = src;
                learnAndConnect(src, relayCandidate != null ? relayCandidate : probeTargets.get(src));
            } else {
                if (host) {
                    return; // 房主不按候选探测，ACK 无意义
                }
                learnAndConnect(src, probeTargets.getOrDefault(src, lastProbed));
            }
            return;
        }
        if (!connected) {
            return; // 未打通前只认握手包
        }
        InetSocketAddress p = peer;
        if (p == null || !src.equals(p)) {
            return; // 只认已学习的对端端点
        }
        ReliableStream s = stream;
        if (s != null && type >= T_DATA) {
            s.onPacket(d, off, len, type);
        }
    }

    private void learnAndConnect(InetSocketAddress src, Candidate pathCandidate) {
        if (connected) {
            if (host) {
                peer = src; // NAT 重映射后跟着走（learn-on-receive）
            }
            return;
        }
        markConnected(src, pathCandidate);
    }

    private void sendHandshake(InetSocketAddress target, byte type) {
        byte[] token = expectedToken;
        if (token == null) {
            token = new byte[TOKEN_LEN]; // 房主还没设口令：发全零令牌，对端只当作占位
        }
        byte[] p = buildPacket(type, 0, 0, 0, token, TOKEN_LEN);
        try {
            udp4.send(new DatagramPacket(p, p.length, target));
        } catch (IOException ignored) {
        }
    }

    private void sendToPeer(byte[] packet, int len) {
        InetSocketAddress p = peer;
        if (p == null || closing) {
            return;
        }
        try {
            udp4.send(new DatagramPacket(packet, len, p));
        } catch (IOException ignored) {
        }
    }

    private boolean tokenOk(byte[] d, int off) {
        byte[] exp = expectedToken;
        if (exp == null) {
            return true; // 房主未设口令：交给 MC 协议层裁决
        }
        int diff = 0;
        for (int i = 0; i < TOKEN_LEN; i++) {
            diff |= exp[i] ^ d[off + i];
        }
        return diff == 0;
    }

    static byte[] buildPacket(byte type, int ack, int wnd, int seq, byte[] payload, int plen) {
        byte[] p = new byte[HDR + plen];
        Stun.writeInt(p, 0, MAGIC);
        p[4] = type;
        p[5] = 0;
        Stun.writeShort(p, 6, plen);
        Stun.writeInt(p, 8, ack);
        Stun.writeInt(p, 12, wnd);
        Stun.writeInt(p, 16, seq);
        if (plen > 0 && payload != null) {
            System.arraycopy(payload, 0, p, HDR, plen);
        }
        return p;
    }

    // ------------------------------------------------------------ 候选发现（房主）

    private List<Candidate> publishCandidates() {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 房间码长度不再是限制因素：v3 编码（原始地址字节 + Base32hex）下 8 条候选也只要约 169 字符。
        // 所以这里按「尽量多给对端可试的地址」来收，直接提高打洞成功率。
        //   3 条全局 IPv6（多地址/多网卡，IPv6 直连成功率最高）
        //   3 条 STUN 反射（不同 STUN 可能拿到不同映射，多给几条让对端多试）
        //   2 条局域网（同 Wi-Fi 场景）
        for (Candidate c : extra) {
            if (addCandidate(out, seen, c.kind(), c.host(), c.port())
                    && c.kind() == Candidate.Kind.RELAY && relayCandidate == null) {
                relayCandidate = c;
                relayAddr = resolveV4(c);
            }
        }
        int v6 = 0;
        for (InetAddress a : localGlobalIPv6()) {
            if (v6 >= 3) {
                break;
            }
            if (addCandidate(out, seen, Candidate.Kind.IPV6, a.getHostAddress(), mcPort)) {
                v6++;
            }
        }
        List<InetSocketAddress> mapped = Stun.discover(udp4, opts.stunServers(), opts.stunTimeoutMs(), stunInbox);
        // NAT 类型判定：多个 STUN 返回的端口是否一致。这决定打洞值不值得等。
        natType = NatType.infer(mapped);
        int reflexive = 0;
        // UPnP 开通的地址优先放（它是「真·公网可达」，比 STUN 反射更可靠）
        Candidate up = upnpCandidate;
        if (up != null && addCandidate(out, seen, up.kind(), up.host(), up.port())) {
            reflexive++;
        }
        for (InetSocketAddress a : mapped) {
            if (reflexive >= 3) {
                break;
            }
            if (a.getAddress() instanceof Inet4Address
                    && addCandidate(out, seen, Candidate.Kind.IPV4_REFLEXIVE,
                    a.getAddress().getHostAddress(), a.getPort())) {
                reflexive++;
            }
        }
        stunNote = mapped.isEmpty() ? "STUN 全部无应答" : ("STUN " + mapped.size() + " 条，" + natType.label());
        stunInbox.clear(); // 迟到的 STUN 报文没人再看了，别攒着
        int lan = 0;
        for (InetAddress a : localLanIPv4()) {
            if (lan >= 2) {
                break;
            }
            if (addCandidate(out, seen, Candidate.Kind.IPV4_LAN, a.getHostAddress(), udp4.getLocalPort())) {
                lan++;
            }
        }
        // 本机回环兜底：同一台机器上双开（自检/单机联调）唯一能走的 IPv4 路径
        addCandidate(out, seen, Candidate.Kind.IPV4_LAN, "127.0.0.1", udp4.getLocalPort());
        return out;
    }

    private static boolean addCandidate(List<Candidate> out, Set<String> seen, Candidate.Kind kind, String host, int port) {
        if (port <= 0 || port > 65535 || out.size() >= MAX_CANDIDATES) {
            return false;
        }
        if (!seen.add(host + ":" + port)) {
            return false;
        }
        out.add(new Candidate(kind, host, port));
        return true;
    }

    private static List<InetAddress> localGlobalIPv6() {
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : localAddresses()) {
            if (a instanceof Inet6Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                    && !a.isMulticastAddress()) {
                out.add(a);
            }
        }
        return out;
    }

    private static List<InetAddress> localLanIPv4() {
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : localAddresses()) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                    && !a.isMulticastAddress()) {
                out.add(a);
            }
        }
        return out;
    }

    private static List<InetAddress> localAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() != null) {
                        out.add(ia.getAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static ServerSocket bindJoinListener() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), JOIN_PORT), 4);
            return ss;
        } catch (Exception e) {
            try {
                return new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static InetSocketAddress resolveV4(Candidate c) {
        try {
            InetAddress a = InetAddress.getByName(c.host());
            return a instanceof Inet4Address ? new InetSocketAddress(a, c.port()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] tokenOf(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(h, TOKEN_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ TCP 侧

    private void dialLocalMc() {
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), mcPort), 5000);
                attach(new TcpBridge(s, stream, this));
            } catch (IOException e) {
                noteBridge("连接本机 MC 端口 " + mcPort + " 失败：" + e.getMessage());
            }
        }, "p2p-tcp-dial");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        Thread t = new Thread(() -> {
            while (!closing && listener != null) {
                try {
                    Socket s = listener.accept();
                    s.setTcpNoDelay(true);
                    if (bridge != null) {
                        s.close(); // ponytail: 一条隧道只搬一条 TCP 连接
                        continue;
                    }
                    attach(new TcpBridge(s, stream, this));
                } catch (IOException e) {
                    if (!closing) {
                        fail("本机转发端口已停止：" + e);
                    }
                    return;
                }
            }
        }, "p2p-tcp-accept");
        t.setDaemon(true);
        t.start();
    }

    private void attach(TcpBridge b) {
        bridge = b;
        b.start();
    }

    private void startKeepalive() {
        Thread t = new Thread(() -> {
            while (!closing) {
                ReliableStream s = stream;
                if (s != null) {
                    s.sendKeepalive();
                }
                emit();
                try {
                    Thread.sleep(Math.max(200, opts.keepaliveMs()));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "p2p-keepalive");
        t.setDaemon(true);
        t.start();
    }

    void addRx(long n) {
        rxBytes.add(n);
    }

    void addTx(long n) {
        txBytes.add(n);
    }

    void noteBridge(String message) {
        if (closing) {
            return;
        }
        detail = message;
        emit();
    }

    // ------------------------------------------------------------ 状态与资源

    private void setState(TunnelState st, String d) {
        state = st;
        detail = d;
        emit();
    }

    private void emit() {
        try {
            sink.accept(status());
        } catch (Throwable ignored) {
            // 监听器抛异常不能带崩后台线程
        }
    }

    private void failNow(String reason) {
        state = TunnelState.FAILED;
        detail = reason;
        emit();
    }

    private void fail(String reason) {
        if (closing) {
            return;
        }
        setState(TunnelState.FAILED, reason);
        shutdown();
    }

    private void shutdown() {
        ReliableStream s = stream;
        if (s != null) {
            s.close();
        }
        TcpBridge b = bridge;
        if (b != null) {
            b.close();
        }
        udp4.close();
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
        // 撤销 UPnP 映射，别让路由器上留一条指向已经不存在的端口的规则
        Upnp.Mapping m = upnpMapping;
        if (m != null) {
            upnpMapping = null;
            Thread t = new Thread(() -> Upnp.unmapUdp(m.internalPort(), m.controlUrl()), "p2p-upnp-unmap");
            t.setDaemon(true);
            t.start();
        }
    }
}