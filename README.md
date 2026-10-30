# p2plan —— Minecraft 26.3 纯模组 P2P 联机

**装上就能连，不需要任何外部客户端、不需要开服务器、不需要端口映射。**

> 本仓库是 **iroh 版**。另有一个更早的「纯 Java 自研 UDP 打洞 + 自建中继」版本（`p2plan-26.3`，
> 不在本仓库内）：它无原生依赖、可跨平台，但打洞率不如 iroh，双边对称 NAT 需自己架中继。
> 日常用 iroh 版即可。

## 下载

从 [**Releases 页面**](https://github.com/QingYuanOvO/p2plan-iroh/releases/latest) 下载
`p2plan-iroh-0.1.0.jar`（约 9 MB），放进 `.minecraft/mods/`。

| 当前版本 | 适用环境 |
|---|---|
| v0.1.0 | Minecraft **26.3** / Fabric Loader 0.19.5+ / **仅 Windows x64** |

> ⚠️ **仅 Windows x64**。Linux / macOS 玩家需自行改 `build.gradle` 的原生库裁剪规则重新构建（见「自行扩展」）。

---

## 快速开始（两人联机）

**前置**：双方都装 [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5+ 与 Minecraft 26.3，并把
上面下载的 jar 和 [Fabric API](https://modrinth.com/mod/fabric-api) 放进 `mods/` 目录。

| 步骤 | 房主 | 访客 |
|---|---|---|
| 1 | 进入一个单人存档 | 启动游戏，停在主菜单 |
| 2 | 按 `Esc` → 点「**P2P 开房**」 | 点「**多人游戏**」 |
| 3 | 点「**验证模式：正版**」切到「**验证模式：离线**」（朋友用离线账户时必做） | 点左上角「**粘贴 P2P 房间码加入**」 |
| 4 | 点「**开房**」→ 点「**复制房间码**」 | 把房间码粘进输入框（或双击历史列表里那条） |
| 5 | 把房间码发给朋友 | 点「**加入**」 |

进服后聊天栏会播报一次连接方式：`[P2P] 连接方式：直连 · 42 ms · [2408:...]:51600`

### 关于「验证模式」
- **正版**（默认）：只有正版账户能进。朋友的账户是正版就选这个。
- **离线**：离线账户也能进；玩家名会被固定成一个稳定 UUID（本模组负责），**改名不丢背包**。
- 朋友出现「**登录失败：无效会话**」就是这个开关没切。

---

## 功能

- **纯服务端+客户端同在模组内**：没有外部程序、没有后台服务
- **游戏内 GUI**：暂停菜单开房、多人游戏界面加入，**不占用任何快捷键**
- **房间码**：携带房主 NodeID + 多条直连地址 + 邀请口令，含 CRC 校验，改动一位即失效；默认 **24 小时**有效
- **历史房间码**：最近连过的房间列表，**单击填入、双击直接重连**、右侧「×」删除
- **离线 UUID 修复**：与 [mcwifipnp](https://github.com/satxm/mcwifipnp) 自动分工（检测到它的 UUID 修复开启时本模组让位，避免抢同一个 Mixin 注入点导致 UUID 不一致）
- **连接质量提示**：进服后聊天栏显示「直连 / 中继 + 延迟」
- **稳定身份**：NodeID 与监听端口持久化 —— **IP 不变时房间码完全不变**
- **端到端加密**：由 iroh（QUIC / TLS 1.3）提供，游戏数据不裸奔

## 房间码长什么样

```
P2P3-0C1G2OVV-4G488JRJ-46MM9O3E-1U69N17J-SG12B11T-UA0PG0U1-...
```

约 170～300 字符（取决于候选地址条数）。含 NodeID + 各网卡直连地址 + 6 位口令 + 过期时间。
`P2P3` 前缀是格式版本；旧前缀（P2P1/P2P2）已不兼容。

---

## 构建

自带构建脚本，**不需要单独装 JDK**（自动找 Minecraft 自带的 JDK 25）：

```bat
cd p2plan-iroh
build.bat
```

产物：`p2plan-iroh/build/libs/p2plan-iroh-*.jar`

前置：**Gradle 9.6+**。脚本按以下顺序找 JDK 与 Gradle，全部可用环境变量覆盖，无需改脚本本身：

| 变量 | 含义 | 不设时 |
|---|---|---|
| `P2PLAN_JDK` | 强制使用的 JDK 目录 | 自动探测：`JAVA_HOME`（仅当 ≥21）、`%APPDATA%\.minecraft\runtime\*`（取最高版本）、PATH |
| `GRADLE_USER_HOME` | Gradle 缓存目录 | `%USERPROFILE%\.gradle` |
| `GRADLE_BIN` | `gradle.bat` 路径 | 找 PATH 上的 `gradle`，再找同级 `.tools\gradle-9.6.0\` |

> 为什么 JDK 要「取最高版本」：MC 26.x 要 JDK 25，且本项目 `release=24` 需要 javac ≥24。
> 很多机器同时装着老 JDK（系统 `JAVA_HOME` 常是 8/11/17），脚本会跳过 <21 的，
> 从 Minecraft 运行时里挑一个够新的。

依赖全部走 Maven Central。

### 自检

```bat
build.bat irohSelfTest        :: 进程内 iroh 隧道（QUIC 流 + TCP 桥）
build.bat irohTunnelTest      :: 走 Tunnels.host/guest（游戏同一代码路径）
build.bat irohLongSessionTest :: 保持会话 100 秒（防「约 60 秒必断」回归）
build.bat roomCodeLogicTest   :: 房间码逻辑穷举（往返/容错/篡改/安全边界/畸形输入）
build.bat scenarioMatrix      :: 工况矩阵 S1-S5（中继/纯P2P/并发访客/候选乱序/端口撞车回退）
build.bat irohStability       :: 验证 NodeID 持久化 → 房间码稳定
build.bat irohRelayProbe      :: 当前网络能否连上 iroh 公共中继（对称 NAT 兜底）
build.bat irohCrossHost       :: 跨进程探针，房主侧（另开窗口跑 Guest）
build.bat irohCrossGuest      :: 跨进程探针，访客侧
```

作者机器实测：
- `irohSelfTest` / `irohTunnelTest` → **PASS**（1 MB / 512 KB 双向逐字节一致）
- `irohLongSessionTest` → **PASS**（100 秒全程保持，含 512 KB 突发）
- `roomCodeLogicTest` → **PASS**（含 ~6600 个单字符篡改穷举、4000 个随机畸形输入不崩溃）
- `scenarioMatrix` → **S1-S5 全 PASS**（单机可验的路径矩阵；真实 NAT 拓扑见上表，需两台不同网络的机器）
- `irohCrossHost` + `irohCrossGuest` → **CROSS_GUEST_PASS 524288 bytes 94ms 5446KB/s**（两个独立 JVM、真实 QUIC）

---

## 联机工况与限制（**请先读这节**）

| 网络情况 | 能否联机 | 说明 |
|---|---|---|
| 双方都有公网 IPv6 | ✅ 最稳 | 首选路径，实测可用 |
| 一方锥型 NAT | ✅ | iroh 自动打洞 |
| 双方对称型 NAT / CGNAT | ⚠️ 走公共中继 | iroh 的 DERP 中继兜底；能连但延迟高、带宽受限 |
| 同一局域网 / 同机双开 | ✅ | 局域网地址直连 |
| 双层 NAT（光猫拨号 + 二级路由） | ✅ 通常可以 | 不依赖你自己开端口 —— 这正是用 iroh 而非手写打洞的原因 |
| 国内连不上 iroh 公共中继 | ⚠️ | 此时只剩 IPv6 / 打洞两条路；若双方都无 IPv6 且都是对称 NAT，则连不上 |
| 只是换网络（Wi-Fi 切换、重播号） | ⚠️ 房间码会变 | 房间码含 IP；IP 变了旧码失效，需重新要码 |

**平台限制**：当前 jar **只含 Windows x64 原生库**（Linux/macOS 玩家用不了，见下方「自行扩展」）。

**安全边界**：房间码自带邀请口令，但口令只是**准入凭据**（在 MC 协议层校验），不是加密密钥；
传输加密由 iroh 提供。别把房间码公开发到群里。

---

## 自行扩展

| 想做的事 | 怎么做 |
|---|---|
| 支持 Linux/macOS | 改 `p2plan-iroh/build.gradle` 里 `trimIrohInJar` 任务的 `exclude` 列表，把对应平台的原生库留下（每个平台约 +5 MB） |
| 换 iroh 版本 | 改 `gradle.properties` 的 `iroh_version`（当前 1.2.0） |
| 换 MC 版本 | 改 `gradle.properties` 的 `minecraft_version` / `fabric_api_version` / `loader_version`，并检查 `client/` 里是否有被改名的 API |
| 调整房间码有效期 | `RoomCode.DEFAULT_TTL_SECONDS`（默认 24 小时） |
| 关掉进服播报 | 注释 `client/P2PLanClient.java` 里的 `announceQualityOnce(client)` |
| 关掉公共中继（纯 P2P） | 启动参数加 `-Dp2plan.relay=false`。**慎用**：手机热点/校园网/CGNAT 下打洞必失败，关掉后这些网络彻底连不上；只在双方都有公网 IPv6 或同一局域网时才建议关 |

---

## 技术说明（给想看代码的人）

```
client/     GUI 与流程：P2PLanScreen（面板）/ P2PHost（开房）/ P2PJoin（加入）
            P2PLanClient（原版界面注入）/ OfflineMode（验证模式切换）
            RoomHistory（历史码 + 可用地址缓存）
iroh/       IrohEndpoint.kt   Kotlin↔Java 桥（suspend → runBlocking）
            IrohBridge.kt     QUIC 流 ↔ 本机 TCP 互搬
            IrohTunnel.java   适配成既有 P2PTunnel 接口（上层零改动）
            IrohIdentity.java NodeID/端口持久化
            IrohNatives.java  原生库解压 + jna.library.path
tunnel/     P2PTunnel 接口 + Candidate + RoomCode 用到的类型
            （UdpTunnel/Stun/ReliableStream 是旧版实现，保留作参考与回退）
```

几处踩过的坑（都在代码注释里标注了原因，避免后人重踩）：
1. Java 手写 `Continuation` 调 iroh 的 suspend 函数 → 永远等不到回调，必须 `runBlocking`
2. `Endpoint.bind()` 不声明 `alpns` → 连接报 `peer doesn't support any known protocol`
3. `connect()` 不带房主 `directAddresses` → `Connection reset`
4. `Connection.use{}` 会让连接在传输中途被关闭 → 「软件导致连接中止」
5. 嵌套的 iroh jar 没有 `fabric.mod.json` → **Fabric 静默忽略** → 运行期 `NoClassDefFoundError`
6. Kotlin 2.2.x 最高只支持 `JVM_24` 目标（Java 侧需一起对齐；跑在 MC 的 JDK 25 上没问题）

---

## 许可

MIT。iroh 本身是 MIT/Apache-2.0（见 [iroh](https://github.com/n0-computer/iroh)）。

不是 Mojang 官方产品，未获 Mojang/Microsoft 批准或关联。