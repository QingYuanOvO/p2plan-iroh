package com.p2plan.tunnel;

/**
 * 某个时刻的隧道状态快照（不可变，可安全地从后台线程抛给 GUI 线程）。
 *
 * <p><b>本类刻意不依赖 Minecraft</b>：{@code tunnel} 包要能在纯 JVM 自检里加载
 * （{@code irohTunnelTest} 等不启游戏）。所以这里只给「语言键」，翻译推迟到 GUI 层：
 * 见 {@link #stateKey()}，由 {@code P2PHost}/{@code P2PJoin} 用 {@code Component.translatable} 渲染。
 *
 * @param state 阶段
 * @param path  当前选中的候选路径，未选定时为 null
 * @param peer  对端地址文本（{@code host:port}），未确定时为 null
 * @param rxBytes 从对端收到的隧道载荷字节数（不含协议头）
 * @param txBytes 发给对端的隧道载荷字节数（不含协议头）
 * @param detail 人类可读的补充说明（失败原因、NAT 判断等；多为内部诊断，保持原样）
 */
public record TunnelStatus(TunnelState state, Candidate path, String peer,
                           long rxBytes, long txBytes, String detail) {

    public static TunnelStatus of(TunnelState state, String detail) {
        return new TunnelStatus(state, null, null, 0, 0, detail);
    }

    /** 该阶段对应的语言文件键（{@code p2plan.tunnel.*}），供 GUI 层翻译。 */
    public String stateKey() {
        return switch (state) {
            case IDLE -> "p2plan.tunnel.idle";
            case DISCOVERING -> "p2plan.tunnel.discovering";
            case PUNCHING -> "p2plan.tunnel.punching";
            case CONNECTED -> "p2plan.tunnel.connected";
            case RELAY -> "p2plan.tunnel.relay";
            case FAILED -> "p2plan.tunnel.failed";
            case CLOSED -> "p2plan.tunnel.closed";
        };
    }

    /**
     * 给日志/纯 JVM 自检用的一行纯文本（不翻译）。
     * 玩家界面上看到的文案走 {@link #stateKey()}。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(state.name());
        if (path != null) {
            sb.append(" | ").append(path.describe());
        }
        if (peer != null) {
            sb.append(" | peer=").append(peer);
        }
        if (rxBytes > 0 || txBytes > 0) {
            sb.append(" | rx=").append(rxBytes / 1024).append("KB tx=").append(txBytes / 1024).append("KB");
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(" | ").append(detail);
        }
        return sb.toString();
    }
}