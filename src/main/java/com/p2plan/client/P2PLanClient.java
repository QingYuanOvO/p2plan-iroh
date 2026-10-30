package com.p2plan.client;

import com.p2plan.P2PLanMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 客户端入口：把「开房 / 加入」挂到原版界面上（**没有快捷键**）。
 *
 * <ul>
 *   <li>暂停菜单（世界内）：「P2P 开房」按钮 —— 点了就发布到局域网并生成房间码。</li>
 *   <li>多人游戏界面：「粘贴 P2P 房间码加入」按钮 —— 点了打开面板直接粘贴加入。</li>
 * </ul>
 *
 * <p><b>为什么用反射调 addRenderableWidget</b>：{@code Screens.getWidgets(screen)} 拿到的是 Fabric 的
 * {@code ButtonList} 包装器，往它 add 理论可行，但实测按钮不显示（原版界面在 AFTER_INIT 之后还会重建控件，
 * 且该包装器与本界面的渲染列表并非同一时刻的那份）。直接调用原版 {@code protected addRenderableWidget}
 * 才能保证进入真正的 renderables/children/narratables 三份列表，既被渲染又能接收点击。
 */
public class P2PLanClient implements ClientModInitializer {

    /** 缓存反射到的方法，避免每次开界面都查找。 */
    private static volatile Method addWidgetMethod;

    @Override
    public void onInitializeClient() {
        // 启动即清空房间状态：上一次会话（进程崩溃/强杀）留下的「已开房」会让玩家以为还在开房，
        // 实际隧道早就没了。这里显式清一次，保证界面上看到的就是当前真实状态。
        try {
            com.p2plan.HostService.markStopped();
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 清理上次会话状态失败", t);
        }

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            try {
                if (screen instanceof PauseScreen) {
                    addOpenButton(screen);
                } else if (screen instanceof JoinMultiplayerScreen) {
                    addJoinButton(screen);
                }
            } catch (Throwable t) {
                P2PLanMod.LOGGER.warn("[p2plan] 界面注入失败（{}）", screen.getClass().getName(), t);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                String secret = PendingJoin.secret();
                // 令牌按「本机玩家自己的名字」取，绝不能取「仓库里第一个有令牌的」——
                // 房主世界文件里存着访客的令牌，去连别人时带错就是串号（见 UuidStore.tokenForName）。
                // 用 getUser().getName()：这正是本机作为房主时，离线 UUID 经由 mixin 建身份所用的那个键，
                // 两边必须是同一个字符串才能命中自己那条 entry。
                String token = com.p2plan.HostService.localTokenOrEmpty(client.getUser().getName());
                ClientPlayNetworking.send(new com.p2plan.net.P2PHandshakePayload(
                        secret == null ? "" : secret, token));
                P2PLanMod.LOGGER.info("[p2plan] 已发送进服握手（口令{}，令牌{}）",
                        secret == null ? "空" : "有", token.isEmpty() ? "无" : "有");
            } catch (Throwable t) {
                P2PLanMod.LOGGER.warn("[p2plan] 握手发送失败", t);
            }
            // 进服后往聊天栏播报一次连接质量（直连/中继 + 延迟），只播一次
            announceQualityOnce(client);
        });

        // 访客离开服务器时把隧道收掉。
        // 没有这一步，退回主菜单后 endpoint、127.0.0.1 监听、pump 线程会一直存活，
        // 每进出一个房间就留一份（并发审查 H4）。关闭放后台线程，避免卡渲染线程。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Thread t = new Thread(P2PJoin::shutdownForDisconnect, "p2plan-guest-disconnect");
            t.setDaemon(true);
            t.start();
        });

        P2PLanMod.LOGGER.info("[p2plan] 客户端初始化完成（暂停菜单开房 / 多人游戏界面加入，无快捷键）");
    }

    /** 暂停菜单加「P2P 开房」。 */
    private static void addOpenButton(Screen screen) {
        int w = 150;
        int x = Math.max(6, (screen.width - w) / 2);
        // 暂停菜单按钮是两列布局，底部最多到 y≈height/4+... ；放在屏幕下方留白处最稳妥
        int y = Math.min(screen.height - 40, bottomOf(Screens.getWidgets(screen)) + 6);
        if (y < 0) {
            y = 0;
        }
        Button b = Button.builder(
                        Component.translatable("p2plan.button.open_short"),
                        btn -> {
                            // 用记忆端口而不是写死默认值：否则快捷开房用 65535、面板显示 port.txt 里的值，
                            // 两处不一致，且违背「上次是啥端口这次还是啥端口」的预期。
                            P2PHost.open(P2PLanScreen.loadPort());
                            switchScreen(new P2PLanScreen());
                        })
                .bounds(x, y, w, 20).build();
        if (addWidget(screen, b)) {
            P2PLanMod.LOGGER.info("[p2plan] 暂停菜单已添加「开房」按钮 @({},{})", x, y);
        }
    }

    /** 多人游戏界面加「粘贴房间码加入」。 */
    private static void addJoinButton(Screen screen) {
        int w = 170;
        // 服务器列表几乎占满整屏，固定在左上角空白处（列表从 y≈32 开始）
        int x = 6;
        int y = 6;
        Button b = Button.builder(
                        Component.translatable("p2plan.button.join_paste"),
                        btn -> switchScreen(P2PLanScreen.joinMode()))
                .bounds(x, y, w, 20).build();
        if (addWidget(screen, b)) {
            P2PLanMod.LOGGER.info("[p2plan] 多人游戏界面已添加「粘贴房间码」按钮 @({},{})", x, y);
        }
    }

    /**
     * 把控件真正加进界面：先试反射调 {@code addRenderableWidget}，失败再退回 Fabric 的 getWidgets。
     *
     * <p>ponytail: 反射调 protected 方法是这里唯一可靠的注入手段（原版没有公开的 addWidget API）；
     * 升级路径是给 Screen 写一个 accessor mixin（更稳，但要动 mixins.json）。
     */
    private static boolean addWidget(Screen screen, AbstractWidget widget) {
        Method m = addWidgetMethod;
        if (m == null) {
            // 精确取 Screen 上带这三个上界的那个重载
            for (Method cand : Screen.class.getDeclaredMethods()) {
                if (!cand.getName().equals("addRenderableWidget") || cand.getParameterCount() != 1) {
                    continue;
                }
                Class<?> p = cand.getParameterTypes()[0];
                if (GuiEventListener.class.isAssignableFrom(p) || Renderable.class.isAssignableFrom(p)) {
                    cand.setAccessible(true);
                    m = cand;
                    break;
                }
            }
            addWidgetMethod = m;
        }
        if (m != null) {
            try {
                m.invoke(screen, widget);
                return true;
            } catch (Throwable t) {
                P2PLanMod.LOGGER.warn("[p2plan] 反射注入控件失败，退回 getWidgets", t);
            }
        }
        try {
            Screens.getWidgets(screen).add(widget);
            return true;
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 控件注入彻底失败", t);
            return false;
        }
    }

    private static int bottomOf(List<AbstractWidget> widgets) {
        int bottom = 0;
        for (AbstractWidget w : widgets) {
            bottom = Math.max(bottom, w.getY() + w.getHeight());
        }
        return bottom;
    }

    private static void switchScreen(Screen to) {
        Minecraft.getInstance().execute(() -> {
            try {
                Minecraft.getInstance().setScreenAndShow(to);
            } catch (Throwable t) {
                P2PLanMod.LOGGER.warn("[p2plan] 打开面板失败", t);
            }
        });
    }

    /**
     * 进服后往聊天栏提示一次「当前是直连还是中继」+ 延迟。
     *
     * <p>为什么要延迟取：iroh 连上后还会继续打洞，一两秒内会把选中路径从「中继」切到「直连」。
     * 立刻取样会把过渡态报给玩家。
     *
     * <p>为什么只播一次：这是环境信息而不是事件，重复刷屏只会烦人。
     */
    private static void announceQualityOnce(Minecraft client) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2_000);
                com.p2plan.iroh.IrohQuality q = P2PJoin.tunnelQuality();
                if (q == null) {
                    return; // 不是 P2P 进服（比如直连局域网），或质量还没测出来
                }
                String text = "[P2P] 连接方式：" + q.describe();
                client.execute(() -> {
                    try {
                        if (client.player != null) {
                            // 26.3 的 API：LocalPlayer.sendSystemMessage(Component)（旧的
                            // displayClientMessage(String, boolean) 已不在）
                            client.player.sendSystemMessage(
                                    net.minecraft.network.chat.Component.literal(text));
                        }
                    } catch (Throwable ignored) {
                    }
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t2) {
                P2PLanMod.LOGGER.warn("[p2plan] 播报连接质量失败", t2);
            }
        }, "p2plan-quality-announce");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 记住最近一次用过的房间口令，供进服握手使用。
     *
     * <p>会落盘到 {@code config/p2plan/last-secret.txt}：从多人游戏列表**重连**已保存的服务器时，
     * 内存里的口令早就没了，没有持久化就会被房主以「房间口令不正确」踢掉（实测踩到过）。
     */
    public static final class PendingJoin {
        private static final java.nio.file.Path FILE =
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("p2plan").resolve("last-secret.txt");
        // volatile：join 线程写、Netty 的 JOIN 回调读。不加可能在重排下读到
        // loaded=true 但 secret 仍是 null → 握手发空口令 → 被房主以「口令不正确」踢掉，
        // 而且只在时序踩中时复现，极难排查。
        private static volatile String secret;
        private static volatile boolean loaded;

        private PendingJoin() {
        }

        public static void set(String s) {
            secret = s;
            if (s == null || s.isBlank()) {
                return;
            }
            try {
                java.nio.file.Files.createDirectories(FILE.getParent());
                java.nio.file.Files.writeString(FILE, s, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable t) {
                P2PLanMod.LOGGER.warn("[p2plan] 保存房间口令失败", t);
            }
        }

        public static String secret() {
            if (!loaded) {
                loaded = true;
                if (secret == null) {
                    try {
                        if (java.nio.file.Files.isRegularFile(FILE)) {
                            secret = java.nio.file.Files.readString(FILE,
                                    java.nio.charset.StandardCharsets.UTF_8).trim();
                        }
                    } catch (Throwable t) {
                        P2PLanMod.LOGGER.warn("[p2plan] 读取上次房间口令失败", t);
                    }
                }
            }
            return secret;
        }
    }
}