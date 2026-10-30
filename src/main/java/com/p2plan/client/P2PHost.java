package com.p2plan.client;

import com.p2plan.HostService;
import com.p2plan.P2PLanMod;
import com.p2plan.tunnel.Candidate;
import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.TunnelOptions;
import com.p2plan.tunnel.TunnelState;
import com.p2plan.tunnel.TunnelStatus;
import com.p2plan.tunnel.Tunnels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端侧的开房/关房执行者：只有这里才碰 IntegratedServer（专用服务端不会加载本类）。
 *
 * <p>线程约定：{@link #open(int)} 在渲染线程上只做「发布集成服务器 + 起后台线程」；
 * 建隧道、等候选地址全在后台线程里做，GUI 通过 {@link #status()} 轮询，绝不阻塞渲染线程。
 */
public final class P2PHost {

    /**
     * 等隧道拿出候选地址的上限。
     *
     * <p>iroh 的绑定链（固定端口→随机端口→纯 P2P）在 {@code Tunnels.host()} 内部同步跑完，
     * 候选也在那一步就填好，所以这里通常立刻返回；保留窗口只是兜底。
     * 绑定失败由下面的 {@code TunnelState.FAILED} 检查即时上报，不会让玩家干等。
     */
    private static final long CANDIDATE_WAIT_MS = 15_000L;

    private static final Object LOCK = new Object();
    /** 开/关房代号：后台线程只在代号未变时提交结果，避免「关了房又被后台标记成开房」。 */
    private static volatile int generation;
    private static volatile Component status = Component.translatable("p2plan.state.idle");
    /** 当前房主侧隧道（用于判断「隧道是否已坏、要不要重建」）。 */
    private static volatile P2PTunnel tunnelRef;

    private P2PHost() {
    }

    /** 最近一次状态文案（任意线程可读）。 */
    public static Component status() {
        return status;
    }

    /**
     * 开房：把集成服务器发布到 {@code port}，然后在后台线程里建隧道、等候选地址。
     * 立即返回，不等隧道就绪。
     *
     * <p><b>幂等（多次开房只取第一次）</b>：已经开房且隧道健康时，直接复用当前房间码并返回，
     * <b>不重建隧道、不换口令</b>。因为每重建一次都会重新生成邀请口令，把已经拿到旧房间码的朋友
     * 全部踢成「房间口令不正确」（实测踩到过：3 秒内连点两次「开房」就会这样）。
     */
    public static Component open(int port) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return set(Component.translatable("p2plan.state.no_world"));
        }
        // 幂等：已经在开房就直接沿用现有房间码，绝不重建（否则口令变了，旧码全部失效）
        if (HostService.isHosting() && !tunnelBroken()) {
            String code = HostService.roomCode();
            P2PLanMod.LOGGER.info("[p2plan] 已在开房中，沿用现有房间码（不重建隧道、不换口令）");
            return set(Component.translatable("p2plan.state.already_hosting",
                    code == null ? "-" : code));
        }
        try {
            // 只有隧道确实坏了才重建：先把旧隧道收掉（markStopped 会把关闭回调丢后台跑）
            if (HostService.isHosting()) {
                HostService.markStopped();
            }
            if (server.isPublished()) {
                server.unpublishServer();
            }
            // 验证模式不再由这里决定：正版验证 / 离线 UUID 是面板上的独立切换按钮（OfflineMode.toggle）。
            // 这里只保证「身份仓库已接管」，这样即便玩家已切到离线 UUID，UUID 也是稳定的。
            OfflineMode.attachIdentities(server);
            if (!server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port)) {
                return set(Component.translatable("p2plan.state.publish_failed", port));
            }
            int actual = server.getPort();
            int gen;
            synchronized (LOCK) {
                gen = ++generation;
            }
            Component opening = Component.translatable("p2plan.state.opening");
            status = opening;

            Thread worker = new Thread(() -> openAsync(gen, actual), "p2plan-host-open");
            worker.setDaemon(true);
            worker.start();
            return opening;
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 开房异常", t);
            return set(Component.translatable("p2plan.state.open_failed", String.valueOf(t.getMessage())));
        }
    }

    /**
     * 隧道是否已经失效。
     *
     * <p>除了「状态是 FAILED/CLOSED」，还要看 {@link P2PTunnel#isReady()}：
     * iroh 的 endpoint 可能已经死了（底层 socket 出错、被系统回收）而状态没来得及刷新，
     * 只看 state 会误判成「还活着」并复用一个死隧道，表现为「显示已开房但没人能连进来」。
     */
    private static boolean tunnelBroken() {
        P2PTunnel t = tunnelRef;
        if (t == null) {
            return true;
        }
        TunnelStatus st = t.status();
        if (st == null) {
            return true;
        }
        if (st.state() == TunnelState.FAILED || st.state() == TunnelState.CLOSED) {
            return true;
        }
        // 状态说没事，但隧道自己报告没就绪 → 视为坏了，重建更安全
        return !t.isReady();
    }

    /** 关房：关隧道 + 取消发布 + 清房间状态；可重复调用不报错。 */
    public static Component close() {
        final P2PTunnel toClose;
        synchronized (LOCK) {
            generation++;
            toClose = tunnelRef;
            tunnelRef = null;
        }
        // 立即清房间状态（渲染线程上只是几个字段赋值，很快），这样界面立刻显示「已关房」。
        try {
            HostService.markStoppedNow();
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 清理房间状态异常", t);
        }
        // iroh 的 endpoint.shutdown() 是 suspend 函数，内部 runBlocking + 最长 5 秒超时；
        // 绝不能放在渲染线程上。关隧道丢后台，玩家感知不到等待。
        //
        // 注意这里关的是**同步块里抓到的那个引用**，而不是「当前 tunnelRef」：
        // 否则玩家关房后立刻重开，后台线程会把刚建好的新隧道给关了。
        if (toClose != null) {
            Thread closer = new Thread(() -> {
                try {
                    toClose.close();
                } catch (Throwable t) {
                    P2PLanMod.LOGGER.warn("[p2plan] 后台关闭隧道异常", t);
                }
            }, "p2plan-host-close");
            closer.setDaemon(true);
            closer.start();
        }
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && server.isPublished()) {
                server.unpublishServer();
            }
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 取消发布异常", t);
        }
        return set(Component.translatable("p2plan.state.stopped"));
    }

    // ------------------------------------------------------------ 后台流程

    private static void openAsync(int gen, int port) {
        P2PTunnel tunnel = null;
        try {
            tunnel = Tunnels.host(port, TunnelOptions.defaults(), st -> onTunnelStatus(gen, st));
            // tunnelRef 与关闭回调必须在同一把锁里、确认代号未变之后一起登记：
            // 若分开写，玩家恰好在这两步之间关房（generation++、tunnelRef 置空），
            // 这个旧隧道会被重新塞回 tunnelRef —— 表现为「已关房但状态复用死隧道」。
            synchronized (LOCK) {
                if (gen != generation) {
                    abort(gen, tunnel);
                    return;
                }
                tunnelRef = tunnel;
                HostService.setTunnelCloser(tunnel::close);
            }

            List<Candidate> ready = awaitCandidates(gen, tunnel);
            if (ready == null) {
                return; // 超时或已取消：收尾与状态文案都在 awaitCandidates 里做掉了
            }
            synchronized (LOCK) {
                if (gen != generation) {
                    // 等候选期间玩家关了房/重开了房：这个旧隧道不能提交，必须关掉，
                    // 否则 endpoint + 监听端口 + 一堆线程全泄漏（旧代码直接 return 就是漏在这）。
                    abort(gen, tunnel);
                    return;
                }
                HostService.markHosting(port, ready);
                // 口令在 markHosting 里才生成，拿到后立刻交给隧道：
                // 不符令牌的对端在隧道层就被丢弃，避免有人占住这条 UDP 通道把真正的访客挡住。
                String secret = HostService.inviteSecret();
                if (secret != null && !secret.isEmpty()) {
                    tunnel.setSecret(secret);
                }
            }
            status = Component.translatable("p2plan.state.opened", String.valueOf(HostService.roomCode()));
            // 首次开房引导：绝大多数人第一次联机失败就栽在「正版验证没关、离线朋友进不来」。
            // 只在「首次 + 当前仍是正版模式」时提示一次，之后不再打扰。
            maybeFirstTimeHint();
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 后台开房失败", t);
            safeClose(tunnel);
            if (gen == generation) {
                HostService.setTunnelCloser(null);
                status = Component.translatable("p2plan.state.open_failed", String.valueOf(t.getMessage()));
            }
        }
    }

    /**
     * 首次开房引导：提醒切「离线」验证模式。
     *
     * <p>触发条件三条同时满足才提示，避免老玩家被打扰：
     * <ol>
     *   <li>配置文件里没有「已提示过」标记；</li>
     *   <li>当前世界仍处于正版验证模式（离线朋友会被「无效会话」挡住）；</li>
     *   <li>能拿到玩家对象（不在世界里就跳过）。</li>
     * </ol>
     */
    private static void maybeFirstTimeHint() {
        try {
            java.nio.file.Path flag = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("p2plan").resolve("first-open-hinted.txt");
            if (flag != null && java.nio.file.Files.isRegularFile(flag)) {
                return;
            }
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            // 已经是离线模式 → 没什么可提醒的，直接标记为已提示免得下次还弹
            boolean offline = OfflineMode.isOffline(mc);
            if (!offline && mc.player != null) {
                // 全仓唯一一处从后台线程碰 MC 状态的调用，必须切回渲染线程：
                // 和断线/tick 并发时 LocalPlayer 可能正在拆，直接写会踩半初始化状态。
                final net.minecraft.network.chat.Component msg =
                        net.minecraft.network.chat.Component.translatable("p2plan.hint.verify_mode");
                mc.execute(() -> {
                    try {
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(msg);
                        }
                    } catch (Throwable ignored) {
                        // 切线程后玩家可能已退出世界，忽略
                    }
                });
            }
            java.nio.file.Files.createDirectories(flag.getParent());
            java.nio.file.Files.writeString(flag, "1");
        } catch (Throwable t) {
            P2PLanMod.LOGGER.debug("[p2plan] 首次引导提示失败（不影响联机）", t);
        }
    }

    /** 轮询等候选地址；返回 null 表示超时/失败/被取消（此时隧道已收掉、状态已写好）。 */
    private static List<Candidate> awaitCandidates(int gen, P2PTunnel tunnel) {
        long deadline = System.currentTimeMillis() + CANDIDATE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (gen != generation) {
                abort(gen, tunnel);
                return null;
            }
            if (hostWorldGone()) {
                abort(gen, tunnel);
                if (gen == generation) {
                    status = Component.translatable("p2plan.state.stopped");
                }
                return null;
            }
            // 隧道自己报了失败（比如 iroh 绑定失败）→ 立刻失败并把原因透传给玩家。
            // 没有这道检查的话要白等满 25 秒才超时 —— 绑定 5 秒就失败了，玩家盯着
            // 「正在启动…」干等，还以为在正常打洞。
            TunnelStatus st = tunnel.status();
            if (st != null && st.state() == TunnelState.FAILED) {
                abort(gen, tunnel);
                if (gen == generation) {
                    status = Component.translatable("p2plan.state.open_failed", st.describe());
                }
                return null;
            }
            List<Candidate> cs = tunnel.candidates();
            if (cs != null && !cs.isEmpty()) {
                return preferIpv6First(cs);
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        abort(gen, tunnel);
        if (gen == generation) {
            status = Component.translatable("p2plan.state.no_candidates", CANDIDATE_WAIT_MS / 1000);
        }
        return null;
    }

    /** 收隧道；代号未变时顺手把 HostService 上的关闭回调也撤掉（避免回调指向已关的隧道）。 */
    private static void abort(int gen, P2PTunnel tunnel) {
        safeClose(tunnel);
        synchronized (LOCK) {
            if (gen == generation) {
                HostService.setTunnelCloser(null);
            }
        }
    }

    private static void onTunnelStatus(int gen, TunnelStatus st) {
        if (st == null || gen != generation) {
            return;
        }
        status = Component.literal(st.describe());
    }

    // ------------------------------------------------------------ 工具

    /** 把 IPv6 直连候选提到最前：房间码与 GUI 都按这个顺序优先展示 IPv6。 */
    public static List<Candidate> preferIpv6First(List<Candidate> cs) {
        List<Candidate> out = new ArrayList<>(cs.size());
        for (Candidate c : cs) {
            if (c.kind() == Candidate.Kind.IPV6) {
                out.add(c);
            }
        }
        for (Candidate c : cs) {
            if (c.kind() != Candidate.Kind.IPV6) {
                out.add(c);
            }
        }
        return out;
    }

    /** 世界已卸载/已取消发布时，开房流程应立刻收手。 */
    private static boolean hostWorldGone() {
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            return server == null || !server.isPublished();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void safeClose(P2PTunnel tunnel) {
        if (tunnel == null) {
            return;
        }
        try {
            tunnel.close();
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 关闭隧道异常", t);
        }
    }

    private static Component set(Component c) {
        status = c;
        return c;
    }
}