package com.p2plan.tunnel;

import com.p2plan.iroh.IrohTunnel;

import java.util.List;
import java.util.function.Consumer;

/**
 * 隧道的唯一工厂：外部（GUI/服务端）只从这里拿 {@link P2PTunnel}，不直接 new 实现。
 *
 * <p><b>本版本用 iroh</b>（{@code p2plan-iroh} 分支）：隧道层由 Rust 的 QUIC P2P 库负责，
 * 自带打洞、端到端加密、稳定 NodeID。上层（{@code P2PHost}/{@code P2PJoin}/GUI/房间码）
 * 一行都不用改，因为它们只依赖 {@link P2PTunnel} 接口。
 *
 * <p>旧的自研 UDP 打洞实现（{@code UdpTunnel}）仍留在源码里作为参考与回退，
 * 但不再被这里使用。
 */
public final class Tunnels {

    private Tunnels() {
    }

    /**
     * 房主侧隧道。
     *
     * @param mcPort 本机 MC 服务端口；访客流量最终被搬到这里（{@code 127.0.0.1:mcPort}）
     */
    public static P2PTunnel host(int mcPort, TunnelOptions opts, Consumer<TunnelStatus> sink) {
        return IrohTunnel.startHost(mcPort, opts, sink);
    }

    /**
     * 访客侧隧道。
     *
     * @param candidates 房间码里的候选（第一条是房主的 iroh NodeID，其余是直连地址）
     * @param secret     房间口令（iroh 已自带加密；口令仍由 MC 协议层裁决）
     */
    public static P2PTunnel guest(List<Candidate> candidates, String secret, TunnelOptions opts,
                                  Consumer<TunnelStatus> sink) {
        return IrohTunnel.startGuest(candidates, secret, opts, sink);
    }
}