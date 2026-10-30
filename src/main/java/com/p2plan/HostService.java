package com.p2plan;

import com.p2plan.tunnel.Candidate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间状态与握手裁决（纯状态，不 import 任何客户端类，专用服务端也能安全加载）。
 *
 * <p>地址发现与隧道全部在 {@code com.p2plan.tunnel} 里做（房主侧的 STUN 反射地址、
 * 打洞、TCP 搬运）；这里只保存结果、生成房间码、裁决进房口令。
 */
public final class HostService {

    /**
     * 默认开房端口。
     *
     * <p>选 65535 的理由：它在动态/私有端口段末端，既不会和 Minecraft 常用的 25565 撞车，
     * 也不会和本模组的中继默认端口 25600 撞车；玩家改过端口时也一眼能看出「这不是默认值」。
     * 端口可以在房间面板里自定义，这里只是默认值。
     */
    public static final int DEFAULT_PORT = 65535;

    private static volatile boolean hosting = false;
    private static volatile int port = -1;
    private static volatile List<Candidate> candidates = List.of();
    private static volatile String roomCode = null;
    /** 房间码过期时间（epoch 秒），markHosting 生成码时一并记下，避免每 tick 重解码。 */
    private static volatile long roomExpiresAt = 0L;
    private static volatile String inviteSecret = null;
    private static volatile boolean requireSecret = true;
    /** 关房/停世界时由客户端侧注册的隧道关闭回调（房主侧隧道活在客户端对象里）。 */
    private static volatile Runnable tunnelCloser;

    /** 已通过口令校验的玩家 UUID。 */
    private static final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    /** 玩家 UUID -> 进服时间（毫秒），用于超时未握手就踢。 */
    private static final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    /** 握手宽限期：这段时间内没发口令就断线。 */
    private static final long GRACE_MS = 10_000L;

    private HostService() {
    }

    // ------------------------------------------------------------ 状态读写

    public static boolean isHosting() {
        return hosting;
    }

    /** 房主侧隧道监听端口（访客的流量最终落到这个端口）。 */
    public static int port() {
        return port;
    }

    /** 当前房间码里携带的候选地址。 */
    public static List<Candidate> candidates() {
        return candidates;
    }

    /** 首条候选的可读地址，供命令输出使用。 */
    public static String hostSummary() {
        List<Candidate> cs = candidates;
        return cs.isEmpty() ? "无" : cs.get(0).address();
    }

    public static String roomCode() {
        return roomCode;
    }

    /** 房间码过期时间（epoch 秒）；没在开房返回 0。markHosting 已存好，这里纯读。 */
    public static long roomExpiresAt() {
        return roomExpiresAt;
    }

    public static String inviteSecret() {
        return inviteSecret;
    }

    public static boolean requireSecret() {
        return requireSecret;
    }

    public static void setRequireSecret(boolean v) {
        requireSecret = v;
    }

    /** 客户端侧注册隧道关闭回调（开房成功后调用；越早注册越安全）。 */
    public static void setTunnelCloser(Runnable closer) {
        tunnelCloser = closer;
    }

    /** 房主侧隧道与候选就绪后调用：记录端口/候选并生成房间码。 */
    public static synchronized String markHosting(int publishedPort, List<Candidate> ready) {
        if (ready == null || ready.isEmpty()) {
            throw new IllegalArgumentException("没有可用候选地址，无法开房");
        }
        hosting = true;
        port = publishedPort;
        candidates = List.copyOf(ready);
        String[] made = RoomCode.create(candidates);
        roomCode = made[0];
        inviteSecret = made[1];
        // 解码一次把过期时间存下来：与实际房间码内容严格一致（不靠重算 TTL 常量），
        // 且只在开房时发生一次，之后 expiryLine 每 tick 直接读字段。
        RoomCode rc = RoomCode.decode(roomCode);
        roomExpiresAt = rc == null ? 0L : rc.expiresAt();
        verified.clear();
        pending.clear();
        P2PLanMod.LOGGER.info("[p2plan] 已开房：listen={} 候选={} 房间码={}", publishedPort, candidates, roomCode);
        return roomCode;
    }

    /**
     * 只清理房间状态，**不**执行隧道关闭回调。
     *
     * <p>为什么需要它：{@link #markStopped()} 会在调用线程上同步跑隧道 teardown，
     * 而 iroh 的 shutdown 最坏要等 5 秒 —— 放在渲染线程上会让游戏看起来卡住。
     * 所以关房流程改成「先 {@code markStoppedNow()} 让界面立刻干净，
     * 再把隧道收尾丢后台」。调用方负责关隧道。
     */
    public static synchronized void markStoppedNow() {
        boolean was = hosting;
        hosting = false;
        port = -1;
        candidates = List.of();
        roomCode = null;
        roomExpiresAt = 0L;
        inviteSecret = null;
        tunnelCloser = null;
        verified.clear();
        pending.clear();
        if (was) {
            P2PLanMod.LOGGER.info("[p2plan] 已关房（状态已清）");
        }
    }

    /**
     * 清状态并在后台执行隧道关闭回调。
     *
     * <p>closer（iroh endpoint.shutdown）最坏要 runBlocking 等 5 秒，绝不能在
     * 渲染线程或「退出世界」的服务端线程上同步跑 —— 后者会让退存档画面卡住数秒。
     * 状态清理是同步的（界面立刻干净），收尾异步。
     */
    public static synchronized void markStopped() {
        boolean was = hosting;
        hosting = false;
        port = -1;
        candidates = List.of();
        roomCode = null;
        roomExpiresAt = 0L;
        inviteSecret = null;
        verified.clear();
        pending.clear();
        Runnable closer = tunnelCloser;
        tunnelCloser = null;
        if (closer != null) {
            Thread t = new Thread(() -> {
                try {
                    closer.run();
                } catch (Throwable ex) {
                    P2PLanMod.LOGGER.warn("[p2plan] 关闭隧道时异常", ex);
                }
            }, "p2plan-tunnel-close");
            t.setDaemon(true);
            t.start();
        }
        if (was) {
            P2PLanMod.LOGGER.info("[p2plan] 已关房");
        }
    }

    // ------------------------------------------------------------ 进服裁决

    public static void onPlayerJoin(ServerPlayer player) {
        if (!hosting) {
            return;
        }
        if (requireSecret) {
            pending.put(player.getUUID(), System.currentTimeMillis());
        } else {
            verified.add(player.getUUID());
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        pending.remove(player.getUUID());
        verified.remove(player.getUUID());
    }

    /** 每秒扫一次：宽限期内没握手的踢掉。 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (!hosting || !requireSecret || pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : pending.entrySet()) {
            if (now - e.getValue() < GRACE_MS) {
                continue;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            pending.remove(e.getKey());
            if (p != null) {
                p.connection.disconnect(Component.literal(
                        "[P2P LAN] 未提供正确的房间口令，已断开。请用房间码加入。"));
                P2PLanMod.LOGGER.info("[p2plan] {} 握手超时，已断开", p.getName().getString());
            }
        }
    }

    /** 处理客户端握手：校验口令 + 记录/认领身份令牌。 */
    public static void onHandshake(ServerPlayer player, String secret, String token) {
        if (!hosting) {
            return;
        }
        // 长度闸门：secret 正常 6 位、token 正常 24 位。超限的一律当畸形包断开，
        // 绝不把它们送进 UuidStore 的 synchronized 哈希路径（恶意客户端反复发超长串
        // 会在房主主线程放大 CPU/锁竞争）。
        if ((secret != null && secret.length() > 64) || (token != null && token.length() > 64)) {
            player.connection.disconnect(Component.literal("[P2P LAN] 握手数据异常。"));
            return;
        }
        // 房间码过期在这里也是裁决：GUI 层拒绝只是防误操作，不是安全边界。
        // 过期后房主没关房、旧口令仍然有效的窗口必须封掉。
        long exp = roomExpiresAt;
        if (exp > 0 && System.currentTimeMillis() / 1000L > exp) {
            pending.remove(player.getUUID());
            player.connection.disconnect(Component.literal("[P2P LAN] 房间码已过期，请向房主重新要一张。"));
            return;
        }
        UUID uuid = player.getUUID();
        if (requireSecret) {
            if (secret == null || inviteSecret == null || !secret.equalsIgnoreCase(inviteSecret)) {
                pending.remove(uuid);
                player.connection.disconnect(Component.literal("[P2P LAN] 房间口令不正确。"));
                P2PLanMod.LOGGER.info("[p2plan] {} 口令错误，已拒绝", player.getName().getString());
                return;
            }
        }
        pending.remove(uuid);
        verified.add(uuid);
        P2PLanMod.LOGGER.info("[p2plan] {} 握手通过（uuid={}）", player.getName().getString(), uuid);

        if (token != null && !token.isEmpty()) {
            UUID claimed = UuidStore.get().claimByToken(player.getName().getString(), token);
            if (claimed != null && !claimed.equals(uuid)) {
                player.sendSystemMessage(Component.literal(
                        "[P2P LAN] 已识别你的旧身份（" + RoomCode.uuidShort(claimed)
                                + "），重进一次即可接上原有背包与成就。"));
            } else if (claimed == null) {
                // 首次见面：把「令牌 ↔ 本局 UUID」记下来。没有这一步，令牌链是死的 ——
                // 玩家改名后 claimByToken 永远查不到，「改名不丢背包」就只是文档承诺。
                UuidStore.get().recordToken(uuid, player.getName().getString(), token);
            }
        }
    }

    /**
     * 本机玩家（按名字）的可携带身份令牌；未接管身份仓库时返回空串。
     *
     * <p>必须按名字取：世界文件里混存着访客的身份，旧实现返回「第一个有 token 的身份」，
     * 房主去连别人时会把访客的令牌带过去，在对端串号成那个访客（已修，见 UuidStore.tokenForName）。
     */
    public static String localTokenOrEmpty(String playerName) {
        String t = UuidStore.get().tokenForName(playerName);
        return t == null ? "" : t;
    }
}