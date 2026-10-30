package com.p2plan.client;

import com.p2plan.HostService;
import com.p2plan.P2PLanMod;
import com.p2plan.RoomCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 游戏内房间面板：开房 / 关房 / 复制房间码 / 粘贴房间码加入 / 启停本机中继。
 *
 * <p>渲染线程只做「点按钮 + 读 volatile 状态」，所有等待（等候选地址、等打洞、中继绑定）都在
 * {@link P2PHost} / {@link P2PJoin} / {@link P2PRelay} 的后台线程里；这里在 {@link #tick()} 里刷新文案。
 *
 * <p>房间码放在可编辑框里，方便三连击选中复制；「复制」按钮直接写系统剪贴板。
 */
public class P2PLanScreen extends Screen {

    /** 状态区行数：① 开房状态 ② 加入状态 ③ 当前路径 / 连接地址 ④ 本机中继状态。 */
    private static final int LINES = 4;
    /** 临时提示（「已复制」之类）显示多久。 */
    private static final long NOTE_MS = 6_000L;

    private EditBox codeBox;
    /** 开房端口输入框（默认 65535，可自定义）。 */
    private EditBox portBox;
    /** 验证模式切换按钮（正版验证 ↔ 离线 UUID）。 */
    private Button verifyToggle;
    /**
     * 删除历史项触发的界面重建时，跨实例暂存玩家已输入的房间码。
     * 用静态字段：重建走的是 {@code setScreenAndShow(new ...)}，实例字段传不过去。
     */
    private static String pendingInput;
    private final StringWidget[] lines = new StringWidget[LINES];
    private final String[] shown = new String[LINES];

    private Component note;
    private long noteUntil;

    /** 加入模式：从多人游戏界面进来时只关心「粘贴房间码 → 加入」，自动聚焦输入框。 */
    private final boolean joinMode;
    /** 返回时该回到哪个界面（多人游戏界面 / 暂停菜单）；null 表示直接关掉。 */
    private final Screen parent;

    public P2PLanScreen() {
        this(false, null);
    }

    private P2PLanScreen(boolean joinMode, Screen parent) {
        super(Component.translatable("p2plan.screen.title"));
        this.joinMode = joinMode;
        this.parent = parent;
    }

    /** 多人游戏界面上的「粘贴房间码」入口用这个。 */
    public static P2PLanScreen joinMode() {
        return new P2PLanScreen(true, null);
    }

    @Override
    public void onClose() {
        if (this.parent != null) {
            Minecraft.getInstance().setScreenAndShow(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4;
        var font = Minecraft.getInstance().font;

        // 状态行放控件上方。起点从 y-72 起、每行 11px，四行最底到 y-28，
        // 给下面的端口框（y-24 起）留出 4px 间隙 —— 之前用 y-58/12px 间距时
        // 第 4 行会压在端口框上（实测重叠 12px）。小窗口时夹到屏幕内。
        int lineTop = Math.max(2, y - 72);
        for (int i = 0; i < LINES; i++) {
            this.lines[i] = new StringWidget(0, lineTop + i * 11, this.width, 11,
                    Component.empty(), font);
            addRenderableWidget(this.lines[i]);
            this.shown[i] = null;
        }

        // 加入模式（从多人游戏界面进来）：粘贴框 + 历史房间码。
        // 不显示开房那些房主控件，免得访客看着一堆用不上的东西不知点哪个。
        if (this.joinMode) {
            // 只保留第 0 行（note/状态提示，比如「已填入房间码」），其余状态行隐藏。
            // 注意别连 lines[0] 一起隐藏：note 全靠它显示。
            for (int i = 1; i < LINES; i++) {
                this.lines[i].visible = false;
            }
            this.codeBox = new EditBox(font, cx - 155, y - 6, 310, 20, Component.translatable("p2plan.code.label"));
            this.codeBox.setMaxLength(512);
            this.codeBox.setHint(Component.translatable("p2plan.code.hint"));
            // 删除历史触发的界面重建，把玩家之前输入的内容接回来（一次性，取用即清）
            if (pendingInput != null) {
                this.codeBox.setValue(pendingInput);
                pendingInput = null;
            }
            addRenderableWidget(this.codeBox);

            addRenderableWidget(Button.builder(Component.translatable("p2plan.button.join"), b -> {
                join();
                sync();
            }).bounds(cx - 155, y + 20, 200, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("p2plan.button.done"), b -> this.onClose())
                    .bounds(cx + 55, y + 20, 100, 20).build());

            // 历史房间码：单击填入输入框，双击直接加入
            List<RoomHistory.Entry> history = RoomHistory.load();
            // 最多 5 行，且不许超出屏幕底部（小窗/高 GUI 缩放时有效高度只有 ~180px，
            // 固定 5 行会把最后的行裁出屏幕，玩家看不见也点不到）
            int rowH = 22;
            int fit = Math.max(0, (this.height - 8 - (y + 62)) / rowH);
            int maxShow = Math.min(history.size(), Math.min(5, fit));
            if (maxShow > 0) {
                addRenderableWidget(new StringWidget(0, y + 48, this.width, 12,
                        Component.translatable("p2plan.history.title"), font));
            }
            for (int i = 0; i < maxShow; i++) {
                RoomHistory.Entry e = history.get(i);
                int rowY = y + 62 + i * 22;
                // 单击填入输入框（让玩家看清），400ms 内再点一下 = 双击 = 直接加入。
                // MC 的 Button 只有单击回调，只能这样近似双击。
                final long[] lastClick = {0L};
                Button b2 = Button.builder(Component.literal(e.display()), btn -> {
                    long now = System.currentTimeMillis();
                    if (now - lastClick[0] < 400L) {
                        this.codeBox.setValue(e.code());
                        join();
                    } else {
                        lastClick[0] = now;
                        this.codeBox.setValue(e.code());
                        note(Component.translatable("p2plan.history.clicked"));
                    }
                    sync();
                }).bounds(cx - 155, rowY, 278, 20)
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                                Component.translatable("p2plan.history.tip")))
                        .build();
                addRenderableWidget(b2);
                // 右侧小「×」：删除这条历史（失效码不该一直占着列表）。
                // 用独立按钮而不是右键 —— MC 的 Button 没有右键回调，独立按钮更稳也更好发现。
                final String codeToForget = e.code();
                addRenderableWidget(Button.builder(Component.literal("×"), btn -> {
                    RoomHistory.forget(codeToForget);
                    // 重建界面：列表条目数是 init() 里定的，重开一次最省事。
                    // 玩家正粘贴到一半的内容不能跟着界面重建丢了 —— 先藏进 static 再恢复。
                    pendingInput = this.codeBox.getValue();
                    Minecraft.getInstance().setScreenAndShow(new P2PLanScreen(true, this.parent));
                }).bounds(cx + 127, rowY, 28, 20)
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                                Component.translatable("p2plan.history.forget")))
                        .build());
            }

            this.setFocused(this.codeBox);
            this.codeBox.setFocused(true);
            sync();
            return;
        }

        addRenderableWidget(Button.builder(
                Component.translatable("p2plan.button.open", ""), b -> open())
                .bounds(cx - 155, y, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("p2plan.button.close"), b -> {
            P2PHost.close();
            sync();
        }).bounds(cx + 5, y, 150, 20).build());

        // 「开房端口」：默认 65535，可自定义。开着房时改端口不会立即生效，需先关房再开。
        this.portBox = new EditBox(font, cx - 155, y - 24, 150, 20, Component.translatable("p2plan.port.label"));
        this.portBox.setMaxLength(5);
        // ponytail: 26.3 的 EditBox 没有 setFilter（旧版 API 已移除），所以这里不做输入期过滤，
        // 统一在 parsePort() 里校验：非数字/越界一律回退默认端口，玩家不会因为手滑开不了房。
        this.portBox.setValue(String.valueOf(loadPort()));
        this.portBox.setHint(Component.translatable("p2plan.port.hint"));
        addRenderableWidget(this.portBox);

        // 「验证模式」切换按钮（与端口同一行右侧）：正版验证 ↔ 离线 UUID。
        // 这是房主自己的策略选择，所以不自动切，由玩家点。
        this.verifyToggle = Button.builder(Component.literal(OfflineMode.currentLabel(Minecraft.getInstance())),
                        b -> {
                            note(Component.literal(OfflineMode.toggle(Minecraft.getInstance())));
                            b.setMessage(Component.literal(OfflineMode.currentLabel(Minecraft.getInstance())));
                            b.setTooltip(Tooltip.create(Component.literal(OfflineMode.tooltip(Minecraft.getInstance()))));
                        })
                .bounds(cx + 5, y - 24, 150, 20)
                .tooltip(Tooltip.create(Component.literal(OfflineMode.tooltip(Minecraft.getInstance()))))
                .build();
        addRenderableWidget(this.verifyToggle);

        // 中继区块已整体移除：iroh 版走自己的 DERP 公共中继兜底（自动、无需配置），
        // 旧 UDP 实现的「自建中继地址」在 IrohTunnel.addCandidate 里是空操作 ——
        // 留着输入框只会让玩家以为填了有用（误导）。用户也明确说过「不想要中继」。

        this.codeBox = new EditBox(font, cx - 155, y + 26, 310, 20, Component.translatable("p2plan.code.label"));
        this.codeBox.setMaxLength(512);
        this.codeBox.setHint(Component.translatable("p2plan.code.hint"));
        String existing = HostService.roomCode();
        if (existing != null) {
            this.codeBox.setValue(existing);
        }
        addRenderableWidget(this.codeBox);

        addRenderableWidget(Button.builder(Component.translatable("p2plan.button.copy"), b -> {
            copyRoomCode();
            sync();
        }).bounds(cx - 155, y + 50, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("p2plan.button.join"), b -> {
            join();
            sync();
        }).bounds(cx + 5, y + 50, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("p2plan.button.done"), b -> this.onClose())
                .bounds(cx - 50, y + 76, 100, 20).build());

        // 加入模式：把光标直接放进输入框，玩家按 Ctrl+V 就能加入
        if (this.joinMode && this.codeBox != null) {
            this.setFocused(this.codeBox);
            this.codeBox.setFocused(true);
        }

        sync();
    }

    // ------------------------------------------------------------ 按钮动作

    /**
     * 开房：端口取「端口」框（非法/空则回退默认），并把「中继地址」框里的可选兜底候选一起带上。
     * 中继地址为空/非法时照常开房 —— 中继只是可选路径，不能因为它把开房搞失败。
     */
    private void open() {
        int port = parsePort();
        savePort(port);
        // 不再有「中继地址」参数：iroh 版自动用 DERP 公共中继兜底，自建中继地址在这版无效。
        P2PHost.open(port);
        sync();
    }

    /** 读端口框；空或越界时回退 {@link HostService#DEFAULT_PORT}。 */
    private int parsePort() {
        String s = this.portBox == null ? "" : this.portBox.getValue().trim();
        if (s.isEmpty()) {
            return HostService.DEFAULT_PORT;
        }
        try {
            int p = Integer.parseInt(s);
            return (p >= 1024 && p <= 65535) ? p : HostService.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return HostService.DEFAULT_PORT;
        }
    }

    /** 端口配置文件：记住玩家上次用的端口，重开面板不用重新填。 */
    private static java.nio.file.Path portFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("p2plan").resolve("port.txt");
    }

    /** 上次记忆的开房端口（{@code port.txt}）。同包的 {@link P2PLanClient} 快捷开房按钮也用它，保证两处一致。 */
    static int loadPort() {
        try {
            java.nio.file.Path f = portFile();
            if (java.nio.file.Files.isRegularFile(f)) {
                int p = Integer.parseInt(java.nio.file.Files.readString(f).trim());
                if (p >= 1024 && p <= 65535) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就用默认值，不值得打扰玩家
        }
        return HostService.DEFAULT_PORT;
    }

    private static void savePort(int port) {
        try {
            java.nio.file.Path f = portFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, String.valueOf(port));
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 保存端口失败", t);
        }
    }

    private void copyRoomCode() {
        String code = HostService.roomCode();
        if (code == null || code.isBlank()) {
            code = this.codeBox.getValue();
        }
        if (code == null || code.isBlank()) {
            note(Component.translatable("p2plan.state.no_code"));
            return;
        }
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(code.trim());
            note(Component.translatable("p2plan.state.copied"));
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 写剪贴板失败", t);
            note(Component.translatable("p2plan.state.copy_failed"));
        }
    }

    private void join() {
        RoomCode code;
        try {
            code = RoomCode.decode(this.codeBox.getValue());
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 房间码解析异常", t);
            code = null;
        }
        if (code == null) {
            note(Component.translatable("p2plan.state.join_invalid"));
            return;
        }
        // 过期就直接拒绝：与其白等 30 秒超时，不如立刻要新码
        if (code.expired()) {
            note(Component.translatable("p2plan.state.code_expired"));
            return;
        }
        P2PJoin.start(code, this);
    }

    // ------------------------------------------------------------ 状态刷新

    @Override
    public void tick() {
        super.tick();
        sync();
    }

    /**
     * 把四行状态同步到界面上；文案没变就不动，免得每 tick 重排。
     */
    private void sync() {
        if (this.lines[0] == null) {
            return;
        }
        setLine(0, headline());
        setLine(1, P2PJoin.status());
        setLine(2, detailLine());
        // 第 4 行：房间码剩余有效期（iroh 版没有 NAT 判定，也移除了自建中继 UI，
        // 这一行改成对玩家真正有用的信息）。
        setLine(3, expiryLine());
        // 验证模式按钮：标签跟随当前状态（进/出世界时也会变），不在世界里就置灰
        if (this.verifyToggle != null) {
            Minecraft mc = Minecraft.getInstance();
            this.verifyToggle.setMessage(Component.literal(OfflineMode.currentLabel(mc)));
            this.verifyToggle.active = OfflineMode.inWorld(mc);
        }
    }

    /** 房间码有效期提示（没开房/不限期时留空行）。直接读 HostService 存好的过期时间。 */
    private static Component expiryLine() {
        if (!HostService.isHosting()) {
            return Component.empty();
        }
        long exp = HostService.roomExpiresAt();
        if (exp <= 0) {
            return Component.empty();
        }
        long left = exp - System.currentTimeMillis() / 1000L;
        if (left <= 0) {
            return Component.literal("⚠ 房间码已过期，重新开房并要新码");
        }
        long h = left / 3600;
        return Component.literal("房间码剩 " + (h >= 1 ? h + " 小时" : (left / 60) + " 分钟"));
    }

    private Component headline() {
        Component n = this.note;
        if (n != null) {
            if (System.currentTimeMillis() < this.noteUntil) {
                return n;
            }
            this.note = null;
        }
        // 加入模式下 lines[1..] 全隐藏，P2PJoin 的进度/失败文案（超时提示、口令错误等）
        // 只能借第 0 行显示 —— 否则访客点了加入后界面永远没有任何反馈。
        return this.joinMode ? P2PJoin.status() : P2PHost.status();
    }

    private Component detailLine() {
        Component path = P2PJoin.path();
        if (path != null) {
            return Component.translatable("p2plan.state.guest_target", path, String.valueOf(P2PJoin.address()));
        }
        if (HostService.isHosting()) {
            // 按当前验证模式给准确引导：文案不能写死「离线已自动开启」——
            // 验证模式现在是手动按钮，首次玩家没点过时朋友会被「无效会话」挡在门外。
            Minecraft mc = Minecraft.getInstance();
            String hint = OfflineMode.isOffline(mc)
                    ? "离线模式已开，任何账户都能进"
                    : "正版验证中，离线朋友进不来——点上方「验证模式」按钮切离线";
            return Component.translatable("p2plan.state.hosting_simple", hint);
        }
        return Component.translatable("p2plan.state.hint");
    }

    private void setLine(int index, Component text) {
        String flat = text.getString();
        if (flat.equals(this.shown[index])) {
            return;
        }
        // 加入模式下只保留第 0 行（状态/提示），其余状态行隐藏。
        // 在 setLine 里也挡一道：否则 sync() 每次 setMessage 会把 visible 改回来导致重新显示。
        if (this.joinMode && index > 0) {
            this.lines[index].visible = false;
            return;
        }
        this.lines[index].setMessage(text);
        this.shown[index] = flat;
    }

    private void note(Component text) {
        this.note = text;
        this.noteUntil = System.currentTimeMillis() + NOTE_MS;
        sync();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}