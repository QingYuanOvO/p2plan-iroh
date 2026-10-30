package com.p2plan.tunnel;

/**
 * 一条可尝试的直连候选地址。房间码里按优先级从高到低携带若干条，访客侧依次尝试。
 *
 * @param kind 候选类型，决定是否需要 NAT 穿透
 * @param host IPv6 文本（不含方括号）或 IPv4 点分十进制
 * @param port 端口
 */
public record Candidate(Kind kind, String host, int port) {

    /** 候选类型；{@code wireId} 是房间码里的稳定编码，不可随意改动。 */
    public enum Kind {
        /** 全球 IPv6：通常双方可直连，不需要打洞（本机网络实测 IPv6 可用，是首选路径）。 */
        IPV6((byte) 1),
        /** STUN 反射到的公网 IPv4 地址：需要 UDP 打洞。 */
        IPV4_REFLEXIVE((byte) 2),
        /** 同局域网 IPv4：仅作兜底（同一 Wi-Fi 下直接可用）。 */
        IPV4_LAN((byte) 3),
        /** 中继地址：直连打洞失效时（双方对称型 NAT）经它转发，见 {@link RelayProtocol}。 */
        RELAY((byte) 4),
        /**
         * iroh 节点标识（32 字节 NodeID 的 hex 串，64 字符）。
         *
         * <p>iroh 版隧道用它当「房主的稳定身份」：NodeID 由公钥派生，不随 IP/端口变化，
         * 比地址可靠得多。端口位固定填 1（占位，无实际含义），
         * 真正的直连地址作为其它候选一起放在房间码里。
         */
        IROH_NODE((byte) 5);

        public final byte wireId;

        Kind(byte wireId) {
            this.wireId = wireId;
        }

        public static Kind fromWire(byte id) {
            for (Kind k : values()) {
                if (k.wireId == id) {
                    return k;
                }
            }
            return null;
        }
    }

    public Candidate {
        if (kind == null || host == null || host.isEmpty() || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("bad candidate: " + kind + " " + host + ":" + port);
        }
    }

    /** 可直接交给 socket/连接界面的地址文本（IPv6 带方括号）。 */
    public String address() {
        // IROH_NODE 的 host 本身就是标识符，不加端口
        if (kind == Kind.IROH_NODE) {
            return host;
        }
        return kind == Kind.IPV6 ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** 给 GUI/日志看的一行描述。 */
    public String describe() {
        String tag = switch (kind) {
            case IPV6 -> "IPv6 直连";
            case IPV4_REFLEXIVE -> "IPv4 打洞";
            case IPV4_LAN -> "局域网";
            case RELAY -> "中继";
            case IROH_NODE -> "iroh 节点";
        };
        return tag + " " + address();
    }
}