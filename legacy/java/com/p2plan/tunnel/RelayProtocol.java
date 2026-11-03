package com.p2plan.tunnel;

/**
 * 中继（RELAY）协议 —— 直连打洞打不通时的兜底通道（双方都是对称型 NAT 时唯一可行的路）。
 *
 * <p><b>设计要点：中继是透明 UDP 转发器。</b>它把两个同口令客户端之间的载荷帧**原样转发**，
 * 因此 {@link ReliableStream} / {@link TcpBridge} 一行都不用改：客户端把「中继地址」当作对端，
 * 中继负责把包搬到另一侧。中继只多认两种握手帧。
 *
 * <p><b>帧格式与 {@link UdpTunnel} 完全一致</b>（magic {@code 0x50324C31} = "P2L1"）：
 * <pre>
 * [0..3]  magic | [4] type | [5] flags | [6..7] payload 长度(大端)
 * [8..11] ack   | [12..15] wnd | [16..19] seq        | body 紧跟头后（偏移 20）
 * </pre>
 * {@code T_RELAY_JOIN} 的 body = 8 字节口令令牌（与 T_HELLO 同一套令牌，由 SHA-256(口令) 派生）。
 *
 * <p><b>中继行为</b>：
 * <ol>
 *   <li>收到 {@code T_RELAY_JOIN}：按令牌登记来源端点；同一令牌出现<b>第二个不同端点</b>即配对，
 *       向两端各发一次 {@code T_RELAY_ACK}；对已配对端点的重复 JOIN 要<b>重发 ACK</b>（幂等，抗丢包）。</li>
 *   <li>配对之后：把除 {@code T_RELAY_JOIN} 以外的所有帧（DATA/ACK/FIN/FIN_ACK/KA）在两端点间原样转发。</li>
 *   <li>超过 {@link #IDLE_TIMEOUT_MS} 没见到某端点任何包的登记作废并清理；配对也随之解除。</li>
 *   <li>不同令牌互不干扰（可同时承载多个房间）；一个令牌只服务一对端点。</li>
 * </ol>
 *
 * <p><b>客户端行为</b>（{@link UdpTunnel} 已实现）：房主/访客在直连候选之外，若拿到 RELAY 候选，
 * 就按 {@link #JOIN_INTERVAL_MS} 节奏持续发 {@code T_RELAY_JOIN}；收到 {@code T_RELAY_ACK} 即把
 * 中继地址当作 peer 进入 CONNECTED（状态记 {@link TunnelState#RELAY}）。
 *
 * <p>ponytail: 中继只做转发、不加密也不鉴权（令牌只防止串房）；中继端点是明文写在房间码里的，
 * 谁都能看到。升级路径：配对后做一次 ECDH 交换并加密载荷，令牌换成 HMAC 挑战响应。
 *
 * <p>ponytail: 隧道 socket 是 IPv4 单栈（{@code 0.0.0.0:0}），所以中继必须能被 IPv4 访问到；
 * 升级路径是把 {@code udp4} 换成双栈 socket，即可支持纯 IPv6 中继。
 */
public final class RelayProtocol {

    /** 客户端 → 中继：报名（body 前 8 字节 = 口令令牌）。 */
    public static final byte T_RELAY_JOIN = 8;
    /** 中继 → 客户端：已配对（可重复发送，客户端幂等处理）。 */
    public static final byte T_RELAY_ACK = 9;

    /** 中继多久没看到某端点的任何包就清掉它（毫秒）。 */
    public static final int IDLE_TIMEOUT_MS = 30_000;
    /** 客户端报名间隔，与直连探测同节奏（毫秒）。 */
    public static final int JOIN_INTERVAL_MS = 250;

    private RelayProtocol() {
    }
}