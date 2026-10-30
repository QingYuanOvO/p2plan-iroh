package com.p2plan.tunnel;

/**
 * NAT 类型判定结果 —— 直接决定「打洞能不能成功」，也是给玩家看的人话诊断。
 *
 * <p>判定依据（本模组的做法）：用**同一条 UDP socket** 向多个不同目标的 STUN 服务器请求，
 * 比较各自返回的公网映射端口：
 * <ul>
 *   <li>端口全都一样 → <b>锥型（Cone）</b>：映射与目标无关，打洞可以成功；</li>
 *   <li>端口随目标变化 → <b>对称型（Symmetric）</b>：映射与目标绑定，对方拿不到正确端口，
 *       打洞在原理上不可能成功（这也是运营商 CGNAT 的典型表现）。</li>
 * </ul>
 *
 * <p>另外：如果拿到的公网 IP 落在私有段（10/8、172.16-31、192.168/16、100.64/10），
 * 说明外面还有一层不归自己管的 NAT（CGNAT / 双层 NAT），同样无法打洞。
 */
public enum NatType {

    /** 端口一致：可以打洞。 */
    CONE("锥型 NAT", "打洞可行"),
    /** 端口随目标变化：对称型，打洞不可行。 */
    SYMMETRIC("对称型 NAT", "打洞不可行（映射随目标变化，对方拿不到正确端口）"),
    /** 拿到的公网地址是私有地址：处于运营商/上级 NAT 之后。 */
    CGNAT("运营商 NAT（CGNAT）", "打洞不可行（你在运营商的内网里，外面还有一层不归你管的 NAT）"),
    /** 一个 STUN 服务器都没回应：无法判断，仍可尝试（可能是 STUN 被墙，不代表不能打洞）。 */
    UNKNOWN("未知", "STUN 无应答，无法判断；仍会尝试打洞"),
    /** 还没测。 */
    UNTESTED("未检测", "");

    private final String label;
    private final String hint;

    NatType(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** 中文短名，给 GUI 用。 */
    public String label() {
        return label;
    }

    /** 一句话解释，给 GUI/日志用。 */
    public String hint() {
        return hint;
    }

    /** 是否值得尝试打洞（CGNAT 与对称型不必白等 30 秒）。 */
    public boolean punchingViable() {
        return this == CONE || this == UNKNOWN || this == UNTESTED;
    }

    /**
     * 根据「多个 STUN 返回的映射」推断类型。
     *
     * @param mapped 同一条 socket 对多个 STUN 服务器得到的公网映射（可能为空）
     */
    public static NatType infer(java.util.List<java.net.InetSocketAddress> mapped) {
        if (mapped == null || mapped.isEmpty()) {
            return UNKNOWN;
        }
        boolean anyPrivate = false;
        Integer port = null;
        boolean portVaries = false;
        for (java.net.InetSocketAddress a : mapped) {
            java.net.InetAddress ip = a.getAddress();
            if (ip != null && (ip.isSiteLocalAddress() || ip.isLoopbackAddress()
                    || isCgnat(ip))) {
                anyPrivate = true;
            }
            if (port == null) {
                port = a.getPort();
            } else if (port != a.getPort()) {
                portVaries = true;
            }
        }
        if (anyPrivate) {
            return CGNAT;
        }
        return portVaries ? SYMMETRIC : CONE;
    }

    /** 100.64.0.0/10（RFC 6598，运营商级 NAT 常用段）。 */
    private static boolean isCgnat(java.net.InetAddress ip) {
        byte[] b = ip.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }
}