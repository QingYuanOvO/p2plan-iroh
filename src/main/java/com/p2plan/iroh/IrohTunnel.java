package com.p2plan.iroh;

import com.p2plan.tunnel.Candidate;
import com.p2plan.tunnel.NatType;
import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.TunnelOptions;
import com.p2plan.tunnel.TunnelState;
import com.p2plan.tunnel.TunnelStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 把 iroh 隧道适配成既有的 {@link P2PTunnel} 接口。
 *
 * <p><b>为什么用适配器</b>：上层（{@code P2PHost} / {@code P2PJoin} / GUI / 房间码）已经按
 * {@link P2PTunnel} 写好了，做一个 iroh 实现就能整层替换，界面与流程一行都不用改。
 *
 * <p>与旧 UDP 实现的对应关系：
 * <ul>
 *   <li>{@link #candidates()}：把 iroh 的 NodeID + 直连地址编码成候选。
 *       <b>NodeID 放第一条</b>（它是稳定标识），直连地址跟在后面。</li>
 *   <li>{@link #localJoinAddress()}：访客侧的 {@code 127.0.0.1:port}，MC 客户端连它。</li>
 *   <li>状态：iroh 绑定/连接是同步阻塞的，所以状态会很快从 DISCOVERING 走到 CONNECTED。</li>
 * </ul>
 *
 * <p>为什么不再有 NAT 类型判定：iroh 自己处理打洞与（可选的）DERP 中继，
 * 不需要我们判断；{@link NatType} 返回 UNTESTED 即可，GUI 会显示「未知」而不误导玩家。
 */
public final class IrohTunnel implements P2PTunnel {

    private final int mcPort;
    private final Consumer<TunnelStatus> sink;
    private final List<Candidate> given;

    private volatile IrohEndpoint endpoint;
    private volatile IrohBridge bridge;
    private volatile TunnelStatus status = TunnelStatus.of(TunnelState.IDLE, "未启动");
    private final AtomicReference<List<Candidate>> candidates = new AtomicReference<>(List.of());
    /** close() 幂等标志（关房回调与后台清理可能都指向同一个隧道）。 */
    private final java.util.concurrent.atomic.AtomicBoolean closeOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * {@code host}/{@code secret}/{@code opts} 是 {@link com.p2plan.tunnel.Tunnels} 抽象工厂的契约参数，
     * iroh 版用不到（连通性由 NodeID+直连地址+DERP 决定、加密由 QUIC 自带、STUN 不需要），
     * 所以收下即丢，不存字段。
     */
    private IrohTunnel(int mcPort, List<Candidate> given, Consumer<TunnelStatus> sink) {
        this.mcPort = mcPort;
        this.given = given == null ? List.of() : List.copyOf(given);
        this.sink = sink;
    }

    /** 房主侧启动。 */
    public static IrohTunnel startHost(int mcPort, TunnelOptions opts, Consumer<TunnelStatus> sink) {
        IrohTunnel t = new IrohTunnel(mcPort, List.of(), sink);
        t.runHost();
        return t;
    }

    /** 访客侧启动。 */
    public static IrohTunnel startGuest(List<Candidate> candidates, String secret,
                                        TunnelOptions opts, Consumer<TunnelStatus> sink) {
        IrohTunnel t = new IrohTunnel(0, candidates, sink);
        t.runGuest();
        return t;
    }

    // ------------------------------------------------------------------ 状态机

    private void runHost() {
        emit(TunnelState.DISCOVERING, "正在启动 iroh 端点…");
        try {
            // 用持久身份 + 固定端口：IP 不变时 NodeID 与地址都不变 → 房间码稳定，历史码可用。
            // 命名空间来自系统属性：进程内自检会在同一 JVM 里同时跑 host 和 guest，
            // 若共用一份密钥就会撞成同一个 NodeID 而无法互连；真实游戏里两个客户端各有 config 目录，不需要它。
            IrohIdentity id = IrohIdentity.loadOrCreate(configDir(), hostNamespace());
            endpoint = bindWithFallback(id);
            bridge = new IrohBridge(endpoint, mcPort, null, List.of(), IrohEndpoint.DEFAULT_ALPN);
            bridge.startHostAcceptLoop();

            // 把 NodeID + 直连地址编码成候选：NodeID 用 IROH_NODE 类型（房主的稳定身份），
            // 直连地址按 IPv6/IPv4 分类，这样房间码沿用既有格式。
            List<Candidate> cs = new ArrayList<>();
            for (String addr : endpoint.directAddresses()) {
                Candidate c = toCandidate(addr);
                if (c != null) {
                    cs.add(c);
                }
            }
            String nodeId = endpoint.getNodeId();
            cs.add(0, new Candidate(Candidate.Kind.IROH_NODE, nodeId, 1));
            candidates.set(List.copyOf(cs));

            emit(TunnelState.CONNECTED,
                    "iroh 端点已就绪：" + nodeId.substring(0, Math.min(12, nodeId.length())) + "…（"
                            + endpoint.directAddresses().size() + " 个直连地址）");
        } catch (Throwable t) {
            emit(TunnelState.FAILED, "iroh 启动失败：" + t);
        }
    }

    /**
     * 是否启用 iroh 公共中继（DERP）。
     *
     * <p><b>默认开启，这是手机热点能联机的关键</b>：热点、校园网、多数 CGNAT 都属于
     * 对称型 NAT —— 打洞在原理上不可能成功。没有中继时这类网络下「连接超时」是必然结果；
     * 有中继则能兜底（延迟高、带宽受限，但能用）。
     *
     * <p>可以用 JVM 参数 {@code -Dp2plan.relay=false} 关掉（局域网/都有 IPv6 时想省掉中继开销）。
     * 注意：关掉之后对称 NAT 环境就彻底连不上了，只在明确知道双方网络都好时才关。
     */
    private static boolean relayEnabled() {
        String v = System.getProperty("p2plan.relay", "true");
        return !"false".equalsIgnoreCase(v.trim());
    }

    /**
     * 按持久身份绑定；端口被占用时回退到系统分配，并把实际端口记下来。
     *
     * <p>为什么需要回退：固定端口一旦被别的程序占用，开房就彻底失败；
     * 回退随机端口只是「这次房间码变一下」，比开不了房好得多。
     *
     * <p>绑定顺序：**先带中继，失败再退纯 P2P**。反过来不行 —— 纯 P2P 在对称 NAT 下注定连不上，
     * 先试它等于白白让玩家多看一次失败。
     */
    private IrohEndpoint bindWithFallback(IrohIdentity id) {
        boolean relay = relayEnabled();
        try {
            IrohEndpoint ep = IrohEndpoint.bind(id.secretKey(), id.bindAddr(), relay,
                    List.of(IrohEndpoint.DEFAULT_ALPN), relay ? 25_000 : 20_000);
            id.rememberPort(actualPort(ep, id.port()));
            return ep;
        } catch (Throwable first) {
            emit(TunnelState.DISCOVERING, "端口 " + id.port() + " 绑定失败，改用随机端口…");
            try {
                IrohEndpoint ep = IrohEndpoint.bind(id.secretKey(), "0.0.0.0:0", relay,
                        List.of(IrohEndpoint.DEFAULT_ALPN), relay ? 25_000 : 20_000);
                int actual = actualPort(ep, -1);
                if (actual > 0) {
                    id.rememberPort(actual);
                }
                return ep;
            } catch (Throwable second) {
                // 中继也连不上时最后再试一次纯 P2P：至少保留 IPv6/局域网直连这条活路
                if (relay) {
                    emit(TunnelState.DISCOVERING, "中继不可用，改为纯 P2P（仅 IPv6/局域网可连）…");
                    IrohEndpoint ep = IrohEndpoint.bind(id.secretKey(), "0.0.0.0:0", false,
                            List.of(IrohEndpoint.DEFAULT_ALPN), 20_000);
                    int actual = actualPort(ep, -1);
                    if (actual > 0) {
                        id.rememberPort(actual);
                    }
                    return ep;
                }
                throw second;
            }
        }
    }

    /** 从 boundSockets 里取出实际监听的端口（形如 {@code 0.0.0.0:51600}）。 */
    private static int actualPort(IrohEndpoint ep, int fallback) {
        try {
            for (String s : ep.boundSockets()) {
                int i = s.lastIndexOf(':');
                if (i > 0) {
                    int p = Integer.parseInt(s.substring(i + 1));
                    if (p > 0) {
                        return p;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * 身份命名空间：默认空串（真实游戏）。
     *
     * <p>进程内自检会在同一 JVM 同时起 host 与 guest，通过系统属性
     * {@code p2plan.identity.host} / {@code p2plan.identity.guest} 给它们各一个名字，
     * 否则两者读到同一份密钥 → 同一个 NodeID → iroh 拒绝自连。
     */
    private static String hostNamespace() {
        return System.getProperty("p2plan.identity.host", "");
    }

    private static String guestNamespace() {
        return System.getProperty("p2plan.identity.guest", "");
    }

    /**
     * P2-15：把上次连通过的地址提到最前（按房主 NodeID 查）。
     *
     * <p>为什么不去调用 client 层的 {@code RoomHistory}：那会让 tunnel 包反向依赖 client 包，
     * 而 tunnel 需要能在纯 JVM（自检）里独立运行。这里直接用同一个文件路径读 Properties。
     */
    private static List<String> preferKnownFirst(List<String> addrs, String hostNodeId) {
        try {
            java.nio.file.Path f = configDir().resolve("working-addrs.properties");
            if (!java.nio.file.Files.isRegularFile(f)) {
                return addrs;
            }
            java.util.Properties p = new java.util.Properties();
            try (java.io.Reader r = java.nio.file.Files.newBufferedReader(f)) {
                p.load(r);
            }
            String known = p.getProperty(hostNodeId);
            if (known == null || known.isBlank() || !addrs.contains(known)) {
                return addrs;
            }
            List<String> out = new ArrayList<>(addrs.size());
            out.add(known);
            for (String a : addrs) {
                if (!a.equals(known)) {
                    out.add(a);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return addrs;
        }
    }

    /**
     * 配置目录：正常情况下是 {@code .minecraft/config/p2plan}。
     *
     * <p>回退链是必要的：{@code FabricLoader.getInstance().getConfigDir()} 在**非 Minecraft 环境**
     * （比如进程内自检的普通 JVM）里会返回 null，直接拿来 resolve 会 NPE。
     * 这时退到 {@code java.io.tmpdir/p2plan}，让自检也能跑。
     */
    private static java.nio.file.Path configDir() {
        try {
            java.nio.file.Path cfg = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            if (cfg != null) {
                return cfg.resolve("p2plan");
            }
        } catch (Throwable ignored) {
            // FabricLoader 不在时（单元自检）走下面的回退
        }
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir", "."), "p2plan");
    }

    private void runGuest() {
        emit(TunnelState.DISCOVERING, "正在启动 iroh 端点…");
        try {
            String nodeId = null;
            List<String> addrs = new ArrayList<>();
            for (Candidate c : given) {
                if (c.kind() == Candidate.Kind.IROH_NODE) {
                    nodeId = c.host(); // 房主 NodeID
                } else {
                    addrs.add(c.address());
                }
            }
            if (nodeId == null) {
                emit(TunnelState.FAILED, "房间码里没有 iroh NodeID（房间码版本不符？）");
                return;
            }
            // P2-15：把该房主上次真正连通的地址提到最前。iroh 会并发尝试所有地址，
            // 但顺序仍影响最终选中的路径，提前通常能少绕几百毫秒。
            addrs = preferKnownFirst(addrs, nodeId);
            // 访客也用自己的持久身份：换房间时 NodeID 不变，便于房主侧做「同一玩家」识别。
            IrohIdentity id = IrohIdentity.loadOrCreate(configDir(), guestNamespace());
            // 访客侧同样要开中继：热点/校园网访客连房主时，打洞大概率失败，只有中继能兜底。
            boolean relay = relayEnabled();
            try {
                endpoint = IrohEndpoint.bind(id.secretKey(), "0.0.0.0:0", relay,
                        List.of(IrohEndpoint.DEFAULT_ALPN), relay ? 25_000 : 20_000);
            } catch (Throwable bindFail) {
                if (!relay) {
                    throw bindFail;
                }
                emit(TunnelState.DISCOVERING, "中继不可用，改为纯 P2P（仅 IPv6/局域网可连）…");
                endpoint = IrohEndpoint.bind(id.secretKey(), "0.0.0.0:0", false,
                        List.of(IrohEndpoint.DEFAULT_ALPN), 20_000);
            }
            bridge = new IrohBridge(endpoint, 0, nodeId, addrs, IrohEndpoint.DEFAULT_ALPN);
            int localPort = bridge.startGuestListener();
            emit(TunnelState.CONNECTED, "iroh 已就绪，本机入口 127.0.0.1:" + localPort);
        } catch (Throwable t) {
            emit(TunnelState.FAILED, "iroh 启动失败：" + t);
        }
    }

    private static Candidate toCandidate(String addr) {
        try {
            String h = addr;
            int port;
            if (h.startsWith("[")) {
                int close = h.indexOf(']');
                if (close < 0) return null;
                String ip = h.substring(1, close);
                port = Integer.parseInt(h.substring(close + 2));
                return new Candidate(Candidate.Kind.IPV6, ip, port);
            }
            int idx = h.lastIndexOf(':');
            if (idx < 0) return null;
            String ip = h.substring(0, idx);
            port = Integer.parseInt(h.substring(idx + 1));
            // 不带方括号但 ip 段里还有冒号 = 裸 IPv6：必须标 IPV6，否则 preferIpv6First
            // 按 kind==IPV6 排序会失效、房间码里也显示成「局域网」误导玩家。
            Candidate.Kind kind = ip.indexOf(':') >= 0 ? Candidate.Kind.IPV6 : Candidate.Kind.IPV4_LAN;
            return new Candidate(kind, ip, port);
        } catch (Throwable t) {
            return null;
        }
    }

    private void emit(TunnelState state, String detail) {
        long rx = 0;
        long tx = 0;
        IrohBridge b = bridge;
        if (b != null) {
            rx = b.rxBytes();
            tx = b.txBytes();
        }
        status = new TunnelStatus(state, null, null, rx, tx, detail);
        Consumer<TunnelStatus> s = sink;
        if (s != null) {
            try {
                s.accept(status);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ P2PTunnel

    @Override
    public TunnelStatus status() {
        return status;
    }

    @Override
    public List<Candidate> candidates() {
        return candidates.get();
    }

    @Override
    public String localJoinAddress() {
        IrohBridge b = bridge;
        return b == null ? null : b.localJoinAddress();
    }

    @Override
    public boolean isReady() {
        return endpoint != null && endpoint.isAlive();
    }

    @Override
    public void setSecret(String secret) {
        // iroh 自带 TLS 1.3 加密，房间口令在 MC 协议层（HostService.onHandshake）裁决即可，
        // 隧道层不需要再校验一次。
    }

    @Override
    public void addCandidate(Candidate candidate) {
        // iroh 不走「候选列表」那套：连通性由 NodeID + 直连地址 + （可选）DERP 负责。
        // 保留方法以满足接口，外部传进来的中继地址在这里被忽略。
    }

    @Override
    public NatType natType() {
        // iroh 自己处理 NAT，不需要我们判定；返回 UNTESTED 让 GUI 显示「未知」而不是误导。
        return NatType.UNTESTED;
    }

    @Override
    public void close() {
        // 幂等：玩家关房（HostService 的 closer）与后台开房失败清理（abort）可能都指向同一个隧道，
        // 二次 close 会把状态文案刷成两遍「已关闭」，也平白多走一遍 shutdown 超时等待。
        if (!closeOnce.compareAndSet(false, true)) {
            return;
        }
        IrohBridge b = bridge;
        if (b != null) {
            b.close();
            bridge = null;
        }
        IrohEndpoint e = endpoint;
        if (e != null) {
            e.close();
            endpoint = null;
        }
        emit(TunnelState.CLOSED, "已关闭");
    }

    /** 供 GUI 显示：本机 NodeID（房间码里最关键的那串）。 */
    public String nodeId() {
        IrohEndpoint e = endpoint;
        return e == null ? "-" : e.getNodeId();
    }

    /**
     * 最近一次连接的质量（直连/中继 + RTT）。
     *
     * @return 还没测出来时返回 null（MC 侧会稍后再试一次或不提示）
     */
    public IrohQuality quality() {
        IrohBridge b = bridge;
        return b == null ? null : b.getLastQuality();
    }
}