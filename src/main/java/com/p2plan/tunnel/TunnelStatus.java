package com.p2plan.tunnel;

/**
 * 某个时刻的隧道状态快照（不可变，可安全地从后台线程抛给 GUI 线程）。
 *
 * @param state 阶段
 * @param path  当前选中的候选路径，未选定时为 null
 * @param peer  对端地址文本（{@code host:port}），未确定时为 null
 * @param rxBytes 从对端收到的隧道载荷字节数（不含协议头）
 * @param txBytes 发给对端的隧道载荷字节数（不含协议头）
 * @param detail 人类可读的补充说明（失败原因、NAT 判断等）
 */
public record TunnelStatus(TunnelState state, Candidate path, String peer,
                           long rxBytes, long txBytes, String detail) {

    public static TunnelStatus of(TunnelState state, String detail) {
        return new TunnelStatus(state, null, null, 0, 0, detail);
    }

    /** GUI 用的一行文本。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (state) {
            case IDLE -> "未启动";
            case DISCOVERING -> "正在发现公网地址…";
            case PUNCHING -> "正在打洞…";
            case CONNECTED -> "已打通";
            case RELAY -> "已连接（中继）";
            case FAILED -> "失败";
            case CLOSED -> "已关闭";
        });
        if (path != null) {
            sb.append(" | ").append(path.describe());
        }
        if (peer != null) {
            sb.append(" | 对端 ").append(peer);
        }
        if (rxBytes > 0 || txBytes > 0) {
            sb.append(String.format(" | 收 %d KB 发 %d KB", rxBytes / 1024, txBytes / 1024));
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(" | ").append(detail);
        }
        return sb.toString();
    }
}