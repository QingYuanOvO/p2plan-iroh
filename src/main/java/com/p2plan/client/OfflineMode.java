package com.p2plan.client;

import com.p2plan.P2PLanMod;
import com.p2plan.UuidStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

/**
 * 「正版验证 ↔ 离线 UUID」开关（由房间面板上的按钮手动切换，**不再于开房时自动强制**）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>正版验证开</b>（原版默认）：只有正版账户能进，UUID 由 Mojang 会话决定。</li>
 *   <li><b>离线 UUID</b>：关掉正版验证，离线账户也能进；同时由 {@link UuidStore} +
 *       {@code UUIDUtilMixin} 给玩家名分配**稳定 UUID**，改名/换设备也能靠令牌认领回原身份。</li>
 * </ul>
 *
 * <p>为什么做成按钮：离线模式是「谁能进房」的策略，该由房主自己决定 ——
 * 想跟正版朋友玩就保持开，想拉离线账户进来才关。自动强制关掉会让房主失去选择权。
 *
 * <p>注意：切换只对**当前存档的集成服务器**生效；换存档或重进世界会回到原版默认，需要再点一次。
 *
 * <p>ponytail: 关掉正版验证后「知道房间码的人都能进」，这正是房间口令要负责的事；
 * 口令在 MC 协议层裁决（HostService.onHandshake），它只是准入校验、不是加密通道。
 */
public final class OfflineMode {

    private OfflineMode() {
    }

    /**
     * 切换当前存档的验证模式：正版验证开 → 离线 UUID，或反过来。
     * 切换结果会被记住，下次进任何存档自动恢复（玩家偏好，不区分存档）。
     *
     * @return 给玩家看的结果文案（成功/失败都直接显示）
     */
    public static String toggle(Minecraft mc) {
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        if (server == null) {
            return "请先进入一个单人存档再切换（该开关作用于当前世界的集成服务器）";
        }
        try {
            boolean currentlyOnline = server.usesAuthentication();
            if (currentlyOnline) {
                // → 离线 UUID
                server.setUsesAuthentication(false);
                attachIdentities(server);
                savePreference(true);
                P2PLanMod.LOGGER.info("[p2plan] 已切换到「离线 UUID」并记住：正版验证关闭，离线账户可以进房");
                return "已切换为「离线 UUID」：正版验证已关闭，离线账户可以进房；UUID 由本模组固定";
            } else {
                // → 正版验证
                server.setUsesAuthentication(true);
                savePreference(false);
                P2PLanMod.LOGGER.info("[p2plan] 已切换到「正版验证」并记住：只有正版账户能进房");
                return "已切换为「正版验证」：只有正版账户能进房";
            }
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 切换验证模式失败", t);
            return "切换失败：" + t.getMessage();
        }
    }

    // ------------------------------------------------------------ 偏好持久化

    /**
     * 记住玩家的验证模式偏好（config/p2plan/verify-offline.txt）。
     * 玩家来回切换是常态，记住后不用每次进存档都点一遍按钮。
     */
    public static void savePreference(boolean offline) {
        try {
            java.nio.file.Path f = prefFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, String.valueOf(offline));
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 保存验证模式偏好失败", t);
        }
    }

    /** 上次的偏好；从没切换过（文件不存在）时返回 false = 保持原版正版验证。 */
    public static boolean preferredOffline() {
        try {
            java.nio.file.Path f = prefFile();
            if (java.nio.file.Files.isRegularFile(f)) {
                return "true".equalsIgnoreCase(java.nio.file.Files.readString(f).trim());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 进存档后按偏好恢复离线模式（SERVER_STARTED 里调用）。 */
    public static void applyPreference(IntegratedServer server) {
        if (server == null || !preferredOffline()) {
            return;
        }
        try {
            server.setUsesAuthentication(false);
            attachIdentities(server);
            P2PLanMod.LOGGER.info("[p2plan] 按上次偏好恢复「离线 UUID」模式");
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 恢复验证模式偏好失败", t);
        }
    }

    private static java.nio.file.Path prefFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("p2plan").resolve("verify-offline.txt");
    }

    /**
     * 让身份仓库接管本存档（离线 UUID 稳定的前提）。
     * 只有当身份仓库确实接管时，{@code UUIDUtilMixin} 才会改写离线 UUID；否则退回原版行为。
     */
    public static void attachIdentities(IntegratedServer server) {
        if (server == null || UuidStore.get().isAttached()) {
            return;
        }
        try {
            var playerData = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
            var worldDir = playerData.getParent();
            var gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            UuidStore.get().attachWorld(worldDir, gameDir);
            P2PLanMod.LOGGER.info("[p2plan] 身份仓库已接管：{}", worldDir);
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 绑定身份仓库失败，本次退回原版离线 UUID", t);
        }
    }

    /** 当前是否处于离线（非正版验证）模式；不在世界里返回 false。 */
    public static boolean isOffline(Minecraft mc) {
        IntegratedServer s = mc == null ? null : mc.getSingleplayerServer();
        return s != null && !s.usesAuthentication();
    }

    /** 是否在世界里（按钮可用性判断）。 */
    public static boolean inWorld(Minecraft mc) {
        return mc != null && mc.getSingleplayerServer() != null;
    }

    /** 按钮标签：只写当前状态，不带括号说明（说明放悬停提示里）。 */
    public static String currentLabel(Minecraft mc) {
        if (!inWorld(mc)) {
            return "验证模式：未入档";
        }
        return isOffline(mc) ? "验证模式：离线" : "验证模式：正版";
    }

    /** 悬停提示：解释这个按钮是干什么的。 */
    public static String tooltip(Minecraft mc) {
        if (!inWorld(mc)) {
            return "先进入一个单人存档（开关作用于当前世界的服务器）";
        }
        return isOffline(mc)
                ? "点一下切回「正版」：只有正版账户能进房"
                : "点一下切到「离线」：离线账户也能进房，玩家名用固定 UUID，改名不丢背包";
    }
}