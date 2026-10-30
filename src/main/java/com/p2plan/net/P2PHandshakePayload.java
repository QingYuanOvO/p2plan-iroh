package com.p2plan.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 进房握手载荷：客户端把自己的邀请口令与身份令牌发给房主。
 *
 * <p>房主据此做两件事：① 口令不对就踢人（防止 IPv6 端口被扫到就进来）；
 * ② 令牌对应的旧身份会被记录，下次该玩家进服即认领（UUID 在登录阶段就已确定，本局改不了）。
 */
public record P2PHandshakePayload(String secret, String token) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<P2PHandshakePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("p2plan", "handshake"));

    public static final StreamCodec<ByteBuf, P2PHandshakePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, P2PHandshakePayload::secret,
            ByteBufCodecs.STRING_UTF8, P2PHandshakePayload::token,
            P2PHandshakePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}