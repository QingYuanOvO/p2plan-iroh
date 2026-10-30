package com.p2plan.iroh;

import com.p2plan.tunnel.P2PTunnel;
import com.p2plan.tunnel.TunnelOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 验证「身份持久化 → 房间码稳定」：连续两次启动房主隧道，比较 NodeID 与直连地址。
 *
 * <p>这就是「持久化 NodeID 是否约等于固定房间码」的实测答案：
 * <ul>
 *   <li>NodeID 一致 → 身份稳定（本次改动生效）；</li>
 *   <li>地址里的 IP 一致、端口一致 → 房间码逐字节相同；</li>
 *   <li>若端口因上次未释放而变，会看到端口不同（属于预期，重开一次即可）。</li>
 * </ul>
 */
public final class IrohStabilityProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 房间码稳定性验证（连续两次开房）===");

        String first = runOnce("第 1 次");
        System.out.println(" 等待 3 秒让端口释放…");
        Thread.sleep(3_000);
        String second = runOnce("第 2 次");

        System.out.println();
        System.out.println("=== 结果 ===");
        boolean same = first != null && first.equals(second);
        String[] a = (first == null ? "|" : first).split("\\|", 2);
        String[] b = (second == null ? "|" : second).split("\\|", 2);
        System.out.println("NodeID 一致  : " + (a[0].equals(b[0])));
        System.out.println("地址列表一致: " + (a.length > 1 && b.length > 1 && a[1].equals(b[1])));
        if (a.length > 1) {
            System.out.println("第 1 次地址: " + a[1]);
        }
        if (b.length > 1) {
            System.out.println("第 2 次地址: " + b[1]);
        }
        System.out.println(same
                ? "STABILITY_RESULT=SAME 房间码完全一致（IP 不变即稳定）"
                : "STABILITY_RESULT=DIFF");
        System.out.println("说明：NodeID 一致 = 身份已持久化（房间码的「人」那半边稳定）；");
        System.out.println("     地址若不同，通常是同进程内两次绑定选了不同 UDP 端口 —— 跨进程重启才会复用固定端口。");
        System.exit(0);
    }

    /** 开一次房，打印 NodeID + 候选摘要，返回「NodeID|地址列表」指纹。 */
    private static String runOnce(String tag) throws Exception {
        Path tmp = Files.createTempDirectory("p2plan-stab");
        P2PTunnel t = com.p2plan.tunnel.Tunnels.host(25565, TunnelOptions.defaults(),
                st -> {
                });
        try {
            long deadline = System.currentTimeMillis() + 25_000;
            List<com.p2plan.tunnel.Candidate> cs = List.of();
            while (System.currentTimeMillis() < deadline) {
                cs = t.candidates();
                if (!cs.isEmpty()) {
                    break;
                }
                Thread.sleep(200);
            }
            if (cs.isEmpty()) {
                System.out.println(tag + " 没拿到候选：" + t.status().describe());
                return null;
            }
            String node = "?";
            StringBuilder addrs = new StringBuilder();
            for (var c : cs) {
                if (c.kind() == com.p2plan.tunnel.Candidate.Kind.IROH_NODE) {
                    node = c.host();
                } else {
                    if (addrs.length() > 0) {
                        addrs.append(',');
                    }
                    addrs.append(c.address());
                }
            }
            String shortNode = node.length() > 12 ? node.substring(0, 12) + "…" : node;
            System.out.println(tag + " NodeID=" + shortNode + " 地址数=" + (cs.size() - 1));
            return node + "|" + addrs;
        } finally {
            t.close();
        }
    }
}
