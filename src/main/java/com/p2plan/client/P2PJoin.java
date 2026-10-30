package com.p2plan.client;

import com.p2plan.P2PLanMod;
import com.p2plan.RoomCode;
import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.TunnelOptions;
import com.p2plan.tunnel.TunnelState;
import com.p2plan.tunnel.TunnelStatus;
import com.p2plan.tunnel.Tunnels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;


/**
 * 访客侧加入流程（分层，按房间码里的候选顺序）：
 * <ol>
 *   <li>有 IPv6 候选：先用普通 TCP 连接探测（3 秒）该候选，通了就直接连它 —— IPv6 直连不需要穿透，
 *       访客侧起隧道纯属多余；</li>
 *   <li>IPv6 不通（或没有 IPv6 候选）：才起访客隧道打洞，等 {@code isReady()} 后用
 *       {@code localJoinAddress()} 连本机入口；</li>
 *   <li>IPv6 与打洞都不通：给出人话文案（对称型 NAT 下打洞不可用，让房主填中继地址后重开房，
 *       中继候选会随房间码一起下来）。</li>
 * </ol>
 *
 * <p>线程约定：{@link #start} 立即返回；探测、打洞、等待全在后台线程，只有
 * {@code ConnectScreen.startConnecting} 通过 {@code Minecraft.execute} 切回渲染线程调用。
 * 状态放在 volatile 字段里，GUI 在 tick() 里轮询。
 */
public final class P2PJoin {

    /** 等隧道打通的上限（隧道自身打洞时限 12 秒，再留出 STUN 与排队余量）。 */
    private static final long TUNNEL_READY_WAIT_MS = 15_000L;

    private static final Object LOCK = new Object();
    private static volatile int generation;
    private static P2PTunnel tunnel;

    private static volatile Component status = Component.translatable("p2plan.state.join_idle");
    /** MC 客户端要连的地址：IPv6 直连时是对端地址，隧道时是本机转发入口。 */
    private static volatile String address;
    /** 当前选中的路径文案（IPv6 直连 / 打洞隧道），未选定时为 null。 */
    private static volatile Component path;

    private P2PJoin() {
    }

    public static Component status() {
        return status;
    }

    /** 当前选中的路径文案；未打通前为 null。 */
    public static Component path() {
        return path;
    }

    /** MC 要连接的地址；未打通前为 null。 */
    public static String address() {
        return address;
    }

    /** 开始一次加入；立即返回，重入时先收掉上一次的隧道。 */
    public static void start(RoomCode code, Screen parent) {
        int gen;
        P2PTunnel previous;
        synchronized (LOCK) {
            gen = ++generation;
            previous = tunnel;
            tunnel = null;
        }
        // 关闭旧隧道丢后台：iroh 的 shutdown 最坏 5 秒，放渲染线程会卡住界面
        closeAsync(previous);
        address = null;
        path = null;
        status = Component.translatable("p2plan.state.join_punching");

        Thread worker = new Thread(() -> joinAsync(gen, code, parent), "p2plan-guest-join");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------ 后台流程

    private static void joinAsync(int gen, RoomCode code, Screen parent) {
        P2PTunnel t = null;
        try {
            // iroh 版不做「IPv6 直连 TCP 探测」：iroh 走 QUIC，那条 TCP 路不存在，
            // 探测只会白等 3 秒。房间码里的地址直接交给 iroh 建连用。
            status = Component.translatable("p2plan.state.join_punching");
            t = Tunnels.guest(code.candidates(), code.secret(), TunnelOptions.defaults(),
                    st -> onTunnelStatus(gen, st));
            synchronized (LOCK) {
                if (gen != generation) {
                    safeClose(t);
                    return;
                }
                tunnel = t;
            }

            String local = awaitReady(gen, t);
            if (local == null) {
                return; // 超时/已取消：状态与清理都在 awaitReady 里做掉了
            }
            commit(gen, code, parent, local, pathLabel(t));
        } catch (Throwable ex) {
            P2PLanMod.LOGGER.warn("[p2plan] 加入失败", ex);
            safeClose(t);
            if (gen == generation) {
                status = Component.translatable("p2plan.state.join_failed", String.valueOf(ex.getMessage()));
            }
        }
    }

    /**
     * 打通后的路径文案：状态是 {@link TunnelState#RELAY} 就写中继地址，否则按打洞隧道写。
     * 中继候选本来就藏在房间码里，访客不需要任何额外操作。
     */
    private static Component pathLabel(P2PTunnel t) {
        TunnelStatus st = t.status();
        if (st != null && st.state() == TunnelState.RELAY) {
            String relay = st.path() != null ? st.path().address() : st.peer();
            return Component.translatable("p2plan.path.relay",
                    relay == null || relay.isBlank() ? "-" : relay);
        }
        return Component.translatable("p2plan.path.tunnel", t.localJoinAddress());
    }

    /** 轮询等隧道打通；返回本机转发入口，null 表示超时/失败/被取消（状态已写好、隧道已收）。 */
    private static String awaitReady(int gen, P2PTunnel t) {
        long deadline = System.currentTimeMillis() + TUNNEL_READY_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (gen != generation) {
                detach(gen, t);
                return null;
            }
            // 隧道绑定阶段就失败了（iroh 起不来）→ 立刻报因，不陪玩家等满 30 秒超时
            TunnelStatus st = t.status();
            if (st != null && st.state() == TunnelState.FAILED) {
                status = Component.translatable("p2plan.state.join_failed", st.describe());
                detach(gen, t);
                return null;
            }
            if (t.isReady()) {
                String local = t.localJoinAddress();
                if (local != null && !local.isBlank()) {
                    return local.trim();
                }
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        String detail = t.status() == null ? null : t.status().detail();
        // 超时时给一句能自救的建议：历史房间码最常见的失效原因就是「房主换了网络（IP 变了）」
        // 或「房主重开过房（口令换了）」，这两种都只能要新码。
        status = Component.translatable("p2plan.state.join_timeout_hint",
                detail == null || detail.isBlank() ? "-" : detail);
        detach(gen, t);
        return null;
    }

    /**
     * 访客退出服务器时调用（由 DISCONNECT 事件触发）：把隧道收掉。
     *
     * <p>没有这一步，退回主菜单后 endpoint、本机 127.0.0.1 监听、pump 线程都会一直存活，
     * 每进出一个房间就留一份。已在后台线程上调用，可以直接关。
     */
    public static void shutdownForDisconnect() {
        P2PTunnel toClose;
        synchronized (LOCK) {
            generation++;
            toClose = tunnel;
            tunnel = null;
        }
        address = null;
        path = null;
        safeClose(toClose);
    }

    /** 在后台线程里关隧道（渲染线程绝不等待 iroh 的 shutdown）。 */
    private static void closeAsync(P2PTunnel t) {
        if (t == null) {
            return;
        }
        Thread closer = new Thread(() -> safeClose(t), "p2plan-guest-close");
        closer.setDaemon(true);
        closer.start();
    }

    /**
     * 当前加入用隧道的连接质量（直连/中继 + RTT）。
     *
     * <p>只在「本模组负责建立的隧道」存在时有值；走原生局域网/直连服务器时为 null，
     * 调用方应当静默跳过提示。
     */
    public static com.p2plan.iroh.IrohQuality tunnelQuality() {
        P2PTunnel t;
        synchronized (LOCK) {
            t = tunnel;
        }
        if (t instanceof com.p2plan.iroh.IrohTunnel it) {
            return it.quality();
        }
        return null;
    }

    /** 把隧道从本类摘掉并关闭（状态已在调用方写好）。 */
    private static void detach(int gen, P2PTunnel t) {
        synchronized (LOCK) {
            if (gen == generation && tunnel == t) {
                tunnel = null;
            }
        }
        safeClose(t);
    }

    /** 打通了：记下地址与路径，把口令递给进服握手，然后切回渲染线程开始连接。 */
    private static void commit(int gen, RoomCode code, Screen parent, String target, Component pathLabel) {
        synchronized (LOCK) {
            if (gen != generation) {
                return;
            }
            address = target;
            path = pathLabel;
        }
        status = Component.translatable("p2plan.state.join_ready");
        // 口令必须在本机连上之前记好：连上瞬间 P2PLanClient 会把它发给房主做校验
        P2PLanClient.PendingJoin.set(code.secret());
        // 隧道已打通 = 这条房间码有效：记进历史，下次可双击直连。
        // 放在 commit 而不是点击「加入」时记 —— 打不通的码不该污染历史列表。
        try {
            RoomHistory.remember(code.encode(), pathLabel.getString());
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 记录历史房间码失败", t);
        }
        P2PLanMod.LOGGER.info("[p2plan] 已就绪，连接 {}（{}）", target, pathLabel.getString());

        Minecraft.getInstance().execute(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                ServerAddress serverAddress = parseAddress(target);
                ServerData data = new ServerData("P2P LAN", target, ServerData.Type.OTHER);
                ConnectScreen.startConnecting(parent, mc, serverAddress, data, false, null);
            } catch (Throwable t) {
                P2PLanMod.LOGGER.warn("[p2plan] 进入游戏失败", t);
                status = Component.translatable("p2plan.state.join_failed", String.valueOf(t.getMessage()));
            }
        });
    }

    private static void onTunnelStatus(int gen, TunnelStatus st) {
        if (st == null || gen != generation) {
            return;
        }
        status = Component.literal(st.describe());
    }

    // ------------------------------------------------------------ 工具

    /**
     * 把 {@code host:port} 拆成 {@link ServerAddress}；IPv6 字面量可能带方括号
     * （{@code [2409:8a00::1]:25599}），拆出来的 host 不带括号交给 ServerAddress。
     */
    static ServerAddress parseAddress(String addr) {
        String s = addr.trim();
        String host;
        String portText;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("地址不合法：" + addr);
            }
            host = s.substring(1, end);
            portText = s.substring(end + 1);
        } else {
            int i = s.lastIndexOf(':');
            if (i < 0) {
                throw new IllegalArgumentException("地址不合法：" + addr);
            }
            host = s.substring(0, i);
            portText = s.substring(i + 1);
        }
        if (portText.startsWith(":")) {
            portText = portText.substring(1);
        }
        return new ServerAddress(host, Integer.parseInt(portText.trim()));
    }

    private static void safeClose(P2PTunnel t) {
        if (t == null) {
            return;
        }
        try {
            t.close();
        } catch (Throwable ex) {
            P2PLanMod.LOGGER.warn("[p2plan] 关闭访客隧道异常", ex);
        }
    }
}