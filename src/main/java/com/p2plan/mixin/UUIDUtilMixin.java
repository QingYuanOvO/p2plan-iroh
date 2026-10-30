package com.p2plan.mixin;

import com.p2plan.P2PLanMod;
import com.p2plan.UuidStore;
import com.p2plan.compat.McWiFiPnP;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 离线 UUID 稳定化：劫持原版「由玩家名派生 UUID」的那一步。
 *
 * <p>证据：{@code ServerLoginPacketListenerImpl} 的字节码里
 * {@code invokestatic UUIDUtil.createOfflineProfile:(Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;}
 * —— 也就是说离线登录最终会走到这里。我们只改写 UUID 的来源，
 * 下游的玩家数据/白名单/成就/OP 全部以 UUID 为键，因此一处生效、全链路一致。
 *
 * <p>未接管世界时（{@link UuidStore#lookupOrCreate} 返回 null）直接放行，保持原版行为。
 *
 * <p>与「更高级联机设置」（mcwifipnp）撞车时主动让位，理由见 {@link McWiFiPnP}：
 * 两边都在同一注入点取消原版逻辑，硬碰硬会让同一玩家拿到不一致的 UUID，进而丢背包/成就。
 */
@Mixin(UUIDUtil.class)
public abstract class UUIDUtilMixin {

    private static volatile boolean deferWarned;

    @Inject(method = "createOfflinePlayerUUID", at = @At("HEAD"), cancellable = true)
    private static void p2plan$stableOfflineUuid(String name, CallbackInfoReturnable<UUID> cir) {
        if (McWiFiPnP.shouldDefer()) {
            if (!deferWarned) {
                deferWarned = true;
                P2PLanMod.LOGGER.warn("[p2plan] 检测到「更高级联机设置」的 UUID 修复正在生效，"
                        + "本模组的离线 UUID 稳定化已自动让位（避免两边抢同一个注入点导致 UUID 不一致）。"
                        + "若要反过来由本模组接管：加启动参数 -Dp2plan.uuidfix=force，"
                        + "并在对方界面关掉「禁用 + 修复UUID」。");
            }
            return;
        }
        UUID stable = UuidStore.get().lookupOrCreate(name);
        if (stable != null) {
            P2PLanMod.LOGGER.debug("[p2plan] 离线 UUID 稳定化：{} -> {}", name, stable);
            cir.setReturnValue(stable);
        }
    }
}