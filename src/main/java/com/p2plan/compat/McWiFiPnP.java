package com.p2plan.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/**
 * 与「更高级联机设置」（mcwifipnp）的兼容处理。
 *
 * <p>证据（反编译它的 2.1.4）：它也带 {@code MixinUUIDUtil}，同样在
 * {@code UUIDUtil.createOfflinePlayerUUID} 的 HEAD 上做 {@code setReturnValue + cancel}，
 * 但策略与本模组不同 —— 它把离线玩家名去匹配 Mojang 服务器，取**官方 UUID**；
 * 本模组则是给一个**稳定 UUID**（并在已有原版离线玩家数据时沿用原值）。
 *
 * <p>两个注入在同一位置都会取消原版逻辑，谁先执行取决于 mod 加载顺序，于是同一个玩家名
 * 可能在两次启动里拿到不同 UUID —— 表现出来就是背包、成就「丢了」。
 * 因此在对方的「修复 UUID」确实开启时，本模组主动让位；要反过来由本模组接管，
 * 用 {@code -Dp2plan.uuidfix=force} 并去对方界面关掉「禁用 + 修复UUID」。
 */
public final class McWiFiPnP {

    private static final String MOD_ID = "mcwifipnp";
    private static final String FIXER_CLASS = "io.github.satxm.mcwifipnp.UUIDFixer";

    /** 强制由本模组接管 UUID（覆盖自动让位）。 */
    private static final boolean FORCE = "force".equalsIgnoreCase(System.getProperty("p2plan.uuidfix", ""));

    private static volatile Field enabledField;
    private static volatile boolean probed;

    private McWiFiPnP() {
    }

    /** 对方模组是否在场（不在场时下面全部走零开销分支）。 */
    public static boolean loaded() {
        try {
            return FabricLoader.getInstance().isModLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 对方的「修复UUID」是否正在生效：读它自己的 {@code public static boolean enabled}。
     * 用反射而不是编译期依赖，这样没装对方模组时本模组完全不受影响；取不到就当没开。
     */
    public static boolean fixerEnabled() {
        if (!loaded()) {
            return false;
        }
        if (!probed) {
            try {
                enabledField = Class.forName(FIXER_CLASS).getField("enabled");
            } catch (Throwable t) {
                enabledField = null;
            }
            probed = true;
        }
        Field f = enabledField;
        if (f == null) {
            return false;
        }
        try {
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本模组是否应当在离线 UUID 这件事上让位给对方。 */
    public static boolean shouldDefer() {
        return !FORCE && fixerEnabled();
    }

    /** 是否被显式强制接管。 */
    public static boolean forced() {
        return FORCE;
    }
}