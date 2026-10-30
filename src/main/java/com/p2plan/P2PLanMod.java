package com.p2plan;

import com.p2plan.compat.McWiFiPnP;
import com.p2plan.net.P2PHandshakePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * P2P LAN 主入口（双端都会跑）。
 *
 * <p>职责：① 接管离线 UUID 稳定化；② 注册进房握手载荷与裁决；③ 提供 /p2plan 命令便于联调。
 * 真正的"发布端口/生成房间码"在客户端面板（P 键）里做，因为只有集成服务器能发布。
 */
public class P2PLanMod implements ModInitializer {

    public static final String MOD_ID = "p2plan";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // ---------- 0) iroh 原生库 ----------
        // 必须在任何 iroh 类被碰到之前准备好 dll：JNA 一旦加载失败会缓存错误，
        // 之后再设 jna.library.path 也没用。
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            String err = com.p2plan.iroh.IrohNatives.prepare(gameDir);
            if (err == null) {
                LOGGER.info("[p2plan] iroh 原生库已就绪：{}", com.p2plan.iroh.IrohNatives.nativeDir());
            } else {
                LOGGER.error("[p2plan] iroh 原生库准备失败，联机功能将不可用：{}", err);
            }
        } catch (Throwable t) {
            LOGGER.error("[p2plan] iroh 原生库初始化异常", t);
        }

        // ---------- 1) 离线 UUID 稳定化 ----------
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
                Path worldDir = playerData.getParent();
                Path gameDir = FabricLoader.getInstance().getGameDir();
                UuidStore.get().attachWorld(worldDir, gameDir);
                LOGGER.info("[p2plan] 已接管世界身份仓库：{}", worldDir);
                // 验证模式偏好是纯客户端行为，且 IntegratedServer 只在客户端存在；
                // 整个判断放进 try 内：专用服务端上该类不在运行时 classpath，
                // instanceof 触发 NoClassDefFoundError 会打断 SERVER_STARTED（本模组声明支持专用服务端加载）。
                if (server instanceof net.minecraft.client.server.IntegratedServer is) {
                    com.p2plan.client.OfflineMode.applyPreference(is);
                }
            } catch (NoClassDefFoundError dedicatedSafe) {
                // 专用服务端：IntegratedServer 缺失属预期，身份仓库已接管即可，验证模式本就不适用
                LOGGER.debug("[p2plan] 非客户端环境，跳过验证模式偏好恢复");
            } catch (Throwable t) {
                LOGGER.warn("[p2plan] 绑定世界目录或恢复偏好失败，本次退回默认", t);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            UuidStore.get().detach();
            HostService.markStopped();
        });

        // ---------- 2) 进房握手 ----------
        // 26.3 的 Fabric API 把 playC2S() 改名为 serverboundPlay()
        PayloadTypeRegistry.serverboundPlay().register(P2PHandshakePayload.TYPE, P2PHandshakePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(P2PHandshakePayload.TYPE, (payload, context) ->
                HostService.onHandshake(context.player(), payload.secret(), payload.token()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                HostService.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                HostService.onPlayerLeave(handler.player));

        // 宽限期到了还没握手的踢掉（每秒扫一次）
        ServerTickEvents.END_SERVER_TICK.register(HostService::tick);

        // ---------- 3) 命令（联调/无 GUI 场景） ----------
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("p2plan")
                        .then(Commands.literal("code").executes(ctx -> {
                            String code = HostService.roomCode();
                            ctx.getSource().sendSuccess(() -> Component.literal(code == null
                                    ? "[p2plan] 当前没有开房（房主请在客户端按 P 打开面板开房）"
                                    : "[p2plan] 房间码：" + code), false);
                            return 1;
                        }))
                        .then(Commands.literal("stop").executes(ctx -> {
                            HostService.markStopped();
                            ctx.getSource().sendSuccess(() -> Component.literal("[p2plan] 已关房"), false);
                            return 1;
                        }))
                        .then(Commands.literal("who").executes(ctx -> {
                            if (ctx.getSource().getServer() == null) {
                                return 0;
                            }
                            var list = ctx.getSource().getServer().getPlayerList().getPlayers();
                            StringBuilder sb = new StringBuilder("[p2plan] 在线 " + list.size() + " 人：");
                            for (var p : list) {
                                sb.append(' ').append(p.getName().getString())
                                  .append("(ping=").append(p.connection.latency()).append("ms)");
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            String s = HostService.isHosting()
                                    ? "[p2plan] 开房中：监听端口 " + HostService.port()
                                      + "，候选 " + HostService.candidates().size()
                                      + " 条（" + HostService.hostSummary() + "）"
                                    : "[p2plan] 未开房";
                            ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                            return 1;
                        }))));

        // ---------- 4) 兼容性：与「更高级联机设置」在 UUID 上分工 ----------
        if (McWiFiPnP.loaded()) {
            if (McWiFiPnP.shouldDefer()) {
                LOGGER.warn("[p2plan] mcwifipnp 的「修复UUID」正在生效，本模组让位（避免两边抢同一个 Mixin 注入点、"
                        + "导致同一玩家在不同启动拿到不一致的 UUID）。要由本模组接管：-Dp2plan.uuidfix=force");
            } else {
                LOGGER.info("[p2plan] 检测到 mcwifipnp，但它的「修复UUID」未开启：本模组继续负责离线 UUID 稳定化");
            }
        }

        LOGGER.info("[p2plan] 初始化完成：离线 UUID 稳定化 + 进房握手 + /p2plan 命令");
    }
}