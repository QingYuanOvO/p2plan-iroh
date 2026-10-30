package com.p2plan.tunnel;

import java.util.List;

/**
 * 一条 P2P 隧道（房主侧或访客侧各一个实例）。
 *
 * <p>生命周期：{@code Tunnels.host(...)} / {@code Tunnels.guest(...)} 创建即开始工作，
 * 后台线程推进状态机并通过构造时传入的 listener 上报 {@link TunnelStatus}；{@link #close()} 释放全部资源。
 *
 * <p>线程约定：实现必须把 listener 的回调放在自己的后台线程上调，GUI 侧负责切回主线程。
 */
public interface P2PTunnel extends AutoCloseable {

    /** 最近一次状态快照。 */
    TunnelStatus status();

    /**
     * 房主：本方可被连接的候选地址（含 STUN 反射地址），用来生成房间码。
     * 访客：正在尝试的候选列表。
     */
    List<Candidate> candidates();

    /**
     * 访客：本机监听地址，MC 客户端应连接这里（形如 {@code 127.0.0.1:25599}）。
     * 房主：返回 null（房主不需要本机转发入口）。
     */
    String localJoinAddress();

    /** 是否已打通（CONNECTED），GUI 用它决定能不能开始连接游戏。 */
    boolean isReady();

    /**
     * 房主侧：设置房间口令。设置之后隧道层只接受握手令牌匹配的对端，不匹配的探测包直接丢弃；
     * 否则任何知道本机地址的人都能占住这条 UDP 通道（把真正的访客挡在外面）。
     *
     * <p>口令要在房间码生成之后才有值，所以房主是「先建隧道、后设口令」：调用晚了有窗口期，
     * 但窗口期内的对端仍会被 MC 协议层的口令裁决拦住。
     *
     * <p>访客侧在构造时就已带上口令，不需要再调。
     */
    void setSecret(String secret);

    /**
     * 房主：追加一条对外候选（典型用途是玩家手填的**中继地址**），会并入 {@link #candidates()}
     * 因而进入房间码，同时房主自己也会向它报名。
     *
     * <p>必须在 {@link #candidates()} 被读取之前调用（开房流程里就是「建隧道 → addCandidate → 等候选」）。
     * 访客侧不需要调用：它要用的候选都来自房间码。
     */
    void addCandidate(Candidate candidate);

    /**
     * 本机 NAT 类型（房主侧在候选发现阶段判定）。
     *
     * <p>GUI 用它给玩家一句人话诊断：「你的网络可以被直接连到 / 你需要 IPv6 / 你的网络无法打洞」。
     * 访客侧通常是 {@link NatType#UNTESTED}（访客不做 STUN）。
     */
    NatType natType();

    @Override
    void close();
}