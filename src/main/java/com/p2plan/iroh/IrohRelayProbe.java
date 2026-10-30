package com.p2plan.iroh;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 探测 iroh 公共中继（DERP）在当前网络下是否可用。
 *
 * <p>为什么单独做这个探针：中继是「双方都是对称型 NAT」时唯一的兜底路径。
 * 如果中继不可达，那种网络下就彻底连不上 —— 社区用户报「连不上」时，第一个要确认的就是它。
 *
 * <p>判据：用默认 relay 配置（{@code RelayMode.defaultMode()}）绑定 endpoint，
 * 看它能否在超时内完成绑定、以及 endpoint 报告的中继 URL。
 */
public final class IrohRelayProbe {

    public static void main(String[] args) {
        System.out.println("=== iroh 公共中继可用性探测 ===");
        System.out.println("JVM: " + System.getProperty("java.version")
                + "  平台: " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));

        // 1) 不带中继（纯 P2P）—— 应该总是成功，作为对照组
        long t0 = System.currentTimeMillis();
        try (IrohEndpoint ep = IrohEndpoint.bind(null, "0.0.0.0:0", false,
                java.util.List.of(IrohEndpoint.DEFAULT_ALPN), 15_000)) {
            System.out.println("[1] 纯 P2P（relay=disabled）绑定成功，"
                    + (System.currentTimeMillis() - t0) + "ms");
            System.out.println("    NodeID = " + ep.getNodeId().substring(0, 16) + "…");
            System.out.println("    直连地址数 = " + ep.directAddresses().size());
        } catch (Throwable e) {
            System.out.println("[1] 纯 P2P 绑定失败：" + e);
        }

        // 2) 带中继（默认配置）—— 决定「对称 NAT 兜底」是否可用
        t0 = System.currentTimeMillis();
        AtomicLong bindMs = new AtomicLong(-1);
        try (IrohEndpoint ep = IrohEndpoint.bind(null, "0.0.0.0:0", true,
                java.util.List.of(IrohEndpoint.DEFAULT_ALPN), 25_000)) {
            bindMs.set(System.currentTimeMillis() - t0);
            System.out.println("[2] 带中继（relay=default）绑定成功，" + bindMs.get() + "ms");
            System.out.println("    地址串 = " + ep.addrString());
            boolean hasRelay = ep.addrString().contains("relay")
                    || ep.addrString().contains("http");
            System.out.println("    地址里含中继信息 = " + hasRelay);
        } catch (Throwable e) {
            System.out.println("[2] 带中继绑定失败（" + (System.currentTimeMillis() - t0) + "ms）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("结论说明：");
        System.out.println("  [2] 成功 → 公共中继可用，双方对称 NAT 时能兜底（延迟较高）");
        System.out.println("  [2] 失败 → 中继不可用；此时只有 IPv6 直连与打洞两条路，");
        System.out.println("           双方都无 IPv6 且都是对称 NAT 时会连不上（需自建中继）。");
        System.exit(0);
    }
}