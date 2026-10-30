package com.p2plan.tunnel;

import java.util.List;

/**
 * 隧道参数。默认值是按“玩家能忍多久”定的：STUN 3 秒、打洞合计 12 秒、连接后每 5 秒保活。
 *
 * @param stunServers    STUN 服务器列表（{@code host:port}），按顺序尝试，全部失败才判为无法发现公网地址
 * @param stunTimeoutMs  单个 STUN 请求超时
 * @param punchTimeoutMs 打洞总时限（到点仍未打通就转 FAILED）
 * @param keepaliveMs    隧道建立后的保活间隔（维持 NAT 映射不失效）
 */
public record TunnelOptions(List<String> stunServers, int stunTimeoutMs, int punchTimeoutMs, int keepaliveMs) {

    public TunnelOptions {
        stunServers = List.copyOf(stunServers);
        if (stunServers.isEmpty()) {
            throw new IllegalArgumentException("stunServers must not be empty");
        }
    }

    /**
     * 默认配置。四个 STUN 里前两个在国外、后两个在国内，本机实测四个都通；
     * 国内网络建议保留国内的，避免出境丢包。
     */
    public static TunnelOptions defaults() {
        return new TunnelOptions(
                List.of("stun.miwifi.com:3478",
                        "stun.chat.bilibili.com:3478",
                        "stun.l.google.com:19302",
                        "stun.cloudflare.com:3478"),
                3000,
                // 打洞窗口从 12 秒放宽到 30 秒：对方 NAT 映射建立慢、或首包丢在慢速链路上时，
                // 12 秒经常「差一点就通」。玩家能接受等半分钟，不能接受「看起来立刻失败」。
                30000,
                5000);
    }
}