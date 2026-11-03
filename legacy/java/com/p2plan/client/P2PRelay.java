package com.p2plan.client;

import com.p2plan.P2PLanMod;
import com.p2plan.tunnel.RelayServer;
import net.minecraft.network.chat.Component;

/**
 * 面板上的「启动中继 / 停止中继」执行者：{@link RelayServer} 的生命周期与状态都收在这里，
 * 面板关掉再打开看到的还是同一个中继。
 *
 * <p>中继绑定的是**本机端口**；要让别人用上，得把本机的**公网 IPv4** 填进面板的「中继地址」框，
 * 它才会作为 {@code Candidate(Kind.RELAY, ...)} 并进房间码。
 *
 * <p>线程约定：绑定/关闭都在后台线程里做，渲染线程只读 volatile 状态（{@link #line()} 由 tick() 轮询）。
 */
public final class P2PRelay {

    /** 默认中继端口；被占用时退到随机端口，实际端口显示在状态区那一行。 */
    public static final int DEFAULT_PORT = 25600;

    private static final Object LOCK = new Object();
    /** 启动/停止代号：后台线程只在代号未变时提交结果，避免「刚停又启」把新中继收掉。 */
    private static volatile int generation;
    private static volatile RelayServer server;
    private static volatile int port = -1;
    /** 过程文案（启动中/失败/已停止…）；运行中与空闲时都为 null，状态区那一行按需显示。 */
    private static volatile Component status;

    private P2PRelay() {
    }

    public static boolean running() {
        return server != null;
    }

    public static int port() {
        return port;
    }

    /** 状态区那一行：运行中显示端口 + {@code stats()}，否则显示过程文案（空闲为 null）。 */
    public static Component line() {
        RelayServer s = server;
        if (s == null) {
            return status;
        }
        return Component.translatable("p2plan.relay.line", s.port(), stats(s));
    }

    /** 启动本机中继；已在运行或正在启动时不做任何事。立即返回。 */
    public static void start() {
        int gen;
        synchronized (LOCK) {
            if (server != null) {
                return;
            }
            gen = ++generation;
        }
        status = Component.translatable("p2plan.relay.starting", DEFAULT_PORT);
        run("p2plan-relay-start", () -> startAsync(gen));
    }

    /** 停止本机中继；没在运行时不报错。立即返回。 */
    public static void stop() {
        RelayServer old;
        synchronized (LOCK) {
            generation++;
            old = server;
            server = null;
            port = -1;
        }
        if (old == null) {
            status = null;
            return;
        }
        status = Component.translatable("p2plan.relay.stopping");
        run("p2plan-relay-stop", () -> {
            safeClose(old);
            status = Component.translatable("p2plan.relay.stopped");
        });
    }

    // ------------------------------------------------------------ 后台流程

    private static void startAsync(int gen) {
        try {
            RelayServer s;
            try {
                s = RelayServer.start(DEFAULT_PORT);
            } catch (Throwable bindFailed) {
                P2PLanMod.LOGGER.warn("[p2plan] 中继端口 {} 不可用，改用随机端口", DEFAULT_PORT, bindFailed);
                s = RelayServer.start(0);
            }
            synchronized (LOCK) {
                if (gen != generation) {
                    safeClose(s);
                    return;
                }
                server = s;
                port = s.port();
            }
            status = null; // 运行中的文案由 line() 直接取 stats()
            P2PLanMod.LOGGER.info("[p2plan] 中继已启动：端口 {}（公网地址请自行填进面板）", s.port());
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 启动中继失败", t);
            status = Component.translatable("p2plan.relay.failed", String.valueOf(t.getMessage()));
        }
    }

    private static String stats(RelayServer s) {
        try {
            String st = s.stats();
            return st == null || st.isBlank() ? "-" : st;
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 读中继状态失败", t);
            return "-";
        }
    }

    private static void safeClose(RelayServer s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 关闭中继异常", t);
        }
    }

    private static void run(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }
}