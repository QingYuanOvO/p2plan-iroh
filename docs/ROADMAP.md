# 路线图

> 本文件只写计划，**不代表已实施**。已实施的部分会在 CHANGELOG 里出现。

## A. v0.1.2 改动计划

### 已在工作区完成（尚未 commit / 未发版）

1. **发布文件名规范**：`<mod名>-mc<游戏版本>-<加载器>-<mod版本>`
   → 实测产物 `p2plan_iroh-mc26.3-fabric-0.1.2.jar`。
   `gradle.properties` 只保留 `archives_base_name=p2plan_iroh` 这一段，`mc26.3-fabric`
   由 `build.gradle` 从 `minecraft_version` 拼出来——这样升游戏版本时不会「文件名写 26.3、实际打 26.4」。
2. **CI 权限修复**：`build.yml` 顶层补 `permissions: contents: write`。
   这是 v0.1.1 的 Release 没自动发出去的**已查证根因**（默认 `GITHUB_TOKEN` 只读 →
   `softprops/action-gh-release` 建 Release 必 403）。注意它的迷惑表现：分支 push 的 run 是绿的
   （那次 `if:` 不成立、根本不跑 release 步骤），只有 tag 那次红。
3. **重复状态文案修复**：`P2PHost.label()` 里，当 `detail` 与阶段名同义（CLOSED 的 detail 就叫「已关闭」）
   时不再拼接，避免渲染成「已关闭（已关闭）」。修在**唯一的渲染出口**而不是逐个改 `emit`，
   这样以后新增状态不会再犯；同时刻意**不吞掉**有信息量的 detail（如「房主地址里没有 iroh NodeID」）。

### v0.1.2 还应做（按价值排）

| # | 事项 | 说明 |
|---|---|---|
| 1 | 在两个真实网络上**长时间游玩**（>10 分钟）验证 | 「60 秒必断」虽有 100 秒回归测试兜底，但只有真人长时间玩能验完整个链路。**这件事只能用户做** |
| 2 | 把 0.1.2 产物部署到 `26.3-p2pA` / `26.3-p2pB` / `26.3-Fabric生存111` | 三个实例目前都还是 `0.1.0` 的 jar |
| 3 | README / CHANGELOG 的文件名与版本引用同步 | 文档里还写着 `p2plan-iroh-0.1.1.jar` 这类旧名 |
| 4 | 决定 `promo/`（宣传视频工程）是否进仓库 | 倾向**不进 git**：里面是截图副本与 MP4，会让仓库变大且非源码。若要留在工作区，加进 `.gitignore` |
| 5 | 收外部反馈 | 让别人用后回收日志，才知道哪些网络真连不上（单机测试只能验代码路径，验不了 NAT 拓扑） |

### 明确**不放进** v0.1.2

跨平台支持（用户 2026-11-03 指示暂不做）、多版本适配（见 B 节）、上 Modrinth（跨平台之前上会让 Linux/macOS 用户装上直接崩）。

---

## B. 多版本适配计划（26.2 / 26.1 / 1.21.x / 1.20.4）

**状态：纯计划，未开始。** 且下面每条「旧版本 API」都必须实测才能落实——本工作区当时**无法核实**
（本机 Loom 缓存里只有 26.3 是 Mojang 映射，1.21 那份是 intermediary 映射；联网查询工具当时不可用）。
凡未核实处都标了「待实测」，**照抄这些名字去改代码一定会错**。

### B.1 事实基线（已用本机 26.3 mojmap jar 核实）

`minecraft-merged-deobf-26.3.jar` 中确实存在：

```
net/minecraft/core/UUIDUtil
net/minecraft/client/server/IntegratedServer        （不是 server.integrated）
net/minecraft/server/MinecraftServer$MultiplayerScope
net/minecraft/network/codec/StreamCodec
net/minecraft/resources/Identifier                  （此 jar 内无 ResourceLocation）
net/minecraft/client/multiplayer/resolver/ServerAddress
net/minecraft/world/level/storage/LevelResource
net/minecraft/client/gui/components/{EditBox,StringWidget,Tooltip}
```

当前模组对 Minecraft 的**全部**依赖面（grep `import net.minecraft.` 得到 40 处，收敛为 6 类风险）：

| 风险点 | 用在哪个文件 | 跨版本变动可能性 |
|---|---|---|
| 离线 UUID 注入点 `UUIDUtil.createOfflinePlayerUUID` | `mixin/UUIDUtilMixin` | 中（类名/方法名/静态性都可能在旧版不同）→ **单点，最值得先测** |
| 开房 API（26.3 为 `publishServer(MultiplayerScope, boolean, int)` + `unpublishServer`） | `client/P2PHost` | **高**（旧版很可能是另一个方法名/签名/返回值） |
| `IntegratedServer` 所在包 + `getSingleplayerServer()` + `usesAuthentication()` | `P2PHost` / `OfflineMode` | 中（26.x 的包位置就与更早版本不同） |
| 自定义载荷（`CustomPacketPayload` + `StreamCodec` + `ByteBufCodecs` + `Identifier`） | `net/P2PHandshakePayload` | **高**（`StreamCodec` 是较新版本才引入；`ResourceLocation`/`Identifier` 命名也变过） |
| GUI 组件（`EditBox`/`StringWidget`/`Tooltip`/`Button` 构造签名） | `client/P2PLanScreen` / `P2PLanClient` | **高**（构造参数与字体相关签名跨版本改动频繁） |
| 聊天播报 `player.sendSystemMessage(Component)` | `P2PLanClient` / `HostService` | 中（更早版本可能是别的方法） |

**好消息**：`RoomCode` + `tunnel/` + `iroh/`（Kotlin 侧）这套**完全不依赖 Minecraft**（这是当初为了让纯
JVM 自检能跑而刻意做的分层）。也就是说隧道/房间码/UUID 存储的逻辑在跨版本时**不用动**，
真正要适配的只有上面这 6 类，集中在 `client/` 与 `mixin/`。

### B.2 架构决定：一个仓库 + 版本适配器，不做分支复制

推荐**方案 A**：

```
src/main/java/com/p2plan/            ← 版本无关的核心（RoomCode/tunnel/UuidStore/HostService 逻辑）
src/mcLatest/java/com/p2plan/adapter/ ← 26.x 适配（现有代码搬进来）
src/mc121/java/com/p2plan/adapter/    ← 1.21.x 适配
src/mc1204/java/com/p2plan/adapter/   ← 1.20.4 适配
build.gradle：-Pmc=26.3|26.2|1.21|1.20.4 选择 sourceSet + 对应 loom minecraft 版本
```

- 定义一个窄接口 `McAdapter`：只暴露那 6 类风险点（开房/关房、取集成服、离线 UUID 入口、
  注册自定义载荷、拼 GUI 控件、发系统消息）。现有 `client/*` 只调接口。
- CI 用 matrix 一次跑多版本编译，**版本号不一致会立刻暴露**，比靠手测可靠。
- 不选「每个版本一条 git 分支」：那样每修一个 bug 要 cherry-pick N 次，迟早漂移。

### B.3 字节码/运行时这条硬约束（最容易翻车，先算清楚）

当前构建是 **`release=24` / `jvmTarget=JVM_24`**（Kotlin 2.2.21 的上限），因为 MC 26.3 跑在 JDK 25 上。

- Minecraft 从某个版本起才要求更高 JDK，**1.20.4 这一档玩家很可能还在用更旧的 Java**。
  字节码 24 的 jar 在旧 Java 上会直接 `UnsupportedClassVersionError`——**这是「装了就打不开」级别的错**。
- 所以每个目标版本必须**各自确定** `release` / `jvmTarget` / Loom / Fabric Loader / fabric-api 的组合，
  并**实测启动**，不能沿用 26.3 的配置。Kotlin 版本也可能要跟着降。
- 同理 `computer.iroh:iroh` 与其 Kotlin/协程依赖在旧 JDK 上的可用性也要实测（它有原生 dll，
  还要 jna；旧版本 MC 自带的 jna 版本可能更旧 → 现在「靠 MC 自带 jna、只 compileOnly」这个取巧做法在旧版可能不成立）。

### B.4 执行顺序（从便宜到贵）

| 步 | 目标 | 为什么这个顺序 |
|---|---|---|
| 0 | **先只做「探测」不做适配**：把 26.2 / 1.21 / 1.20.4 各建一个空壳工程跑 `gradle build`，用 `jar tf` + 编译错误**实测**出上面 6 类风险点的真实签名 | 一切后续判断都依赖这份「版本 API 对照表」，先拿到事实再谈改造 |
| 1 | **26.2**（本机已有实例与 `fabric_MC_mod/` 开发环境） | 与 26.3 同代，预计几乎零改动，用来验证「适配器抽象」切得对不对 |
| 2 | **26.1** | 同上，顺带验证同代多版本能否共用一个 sourceSet |
| 3 | **1.21.x** | 跨代的第一次真检验：开房 API、载荷、GUI 签名大概率要改 |
| 4 | **1.20.4** | 预计最贵：`StreamCodec` 可能不存在、GUI 签名差异大、JDK/字节码档位不同 |

每一步的「完成」定义（不是「编译过」而是）：
1. `gradle build` 出 jar；
2. 两个实例**真机联机成功**且**持续 >10 分钟不断线**；
3. 离线 UUID 在该版本确实生效（改名后背包还在）；
4. 现有逻辑/工况测试在该版本 profile 下全 PASS；
5. Release 附件按命名规范给出该版本的 jar。

### B.5 适配之外要顺带做的（发布渠道）

- 每版本 jar 命名已经带 `mc<版本>`，天然能一 Release 挂多 jar（或每版本一个 Release，二选一，**先定下来**）。
- 上 Modrinth 的前置条件是「跨平台」或「明确只发 Windows」；多版本 + 单平台会让受众很小，
  发布前想清楚目标用户是谁。
