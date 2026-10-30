package com.p2plan.tunnel;

/** 隧道状态机的对外阶段，GUI 直接展示。 */
public enum TunnelState {
    /** 还没开始。 */
    IDLE,
    /** 正在通过 STUN 发现自己的公网映射/挑选候选。 */
    DISCOVERING,
    /** 正在打洞（双端互探）。 */
    PUNCHING,
    /** 已建立直连隧道。 */
    CONNECTED,
    /** 走了中继（第二阶段功能；当前实现不会进入该状态）。 */
    RELAY,
    /** 失败（原因见 status.detail）。 */
    FAILED,
    /** 已关闭。 */
    CLOSED
}