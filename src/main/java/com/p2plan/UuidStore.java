package com.p2plan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离线身份仓库：把「玩家名」稳定映射到一个 UUID，改名/换设备后仍指向同一个人。
 *
 * <p>两份存储（用户选定的方案）：
 * <ul>
 *   <li>世界内：{@code <world>/p2plan/identities.json}（房主侧，跟世界走）</li>
 *   <li>玩家本地：{@code <gameDir>/config/p2plan/identity.txt}（跟人走，可拷到别的机器）</li>
 * </ul>
 *
 * <p>关键点：原版离线 UUID 是 {@code nameUUIDFromBytes("OfflinePlayer:" + name)}，
 * 名字一改 UUID 就变、数据全丢。本仓库按下面的优先级给出「稳定 UUID」：
 * <ol>
 *   <li>已存在的原版离线 UUID 对应 <b>有玩家数据</b>（playerdata/&lt;uuid&gt;.dat）→ 沿用原版值，
 *       绝不迁移丢档（老存档无缝）。</li>
 *   <li>本地/世界仓库里已记录该名字或该 token → 用记录值（改名后靠 token 认领旧身份）。</li>
 *   <li>都没有 → 生成随机 v4 UUID 并落盘（新身份从第一天起就是稳定的）。</li>
 * </ol>
 *
 * <p>线程安全：所有读写都在 synchronized 内；落盘用「临时文件 + 原子改名」。
 */
public final class UuidStore {

    /** 一条身份记录。 */
    public static final class Entry {
        public final UUID uuid;
        /** 该身份用过的所有名字（首个为原始名）。 */
        public final List<String> names = new ArrayList<>();
        /** 玩家自带的可携带令牌（换设备/改名时用来认领身份），可为 null。 */
        public String token;

        Entry(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private static final UuidStore INSTANCE = new UuidStore();

    public static UuidStore get() {
        return INSTANCE;
    }

    /** 小写名 -> uuid。 */
    private final Map<String, UUID> byName = new LinkedHashMap<>();
    /** token -> uuid。 */
    private final Map<String, UUID> byToken = new LinkedHashMap<>();
    /** uuid -> Entry。 */
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private Path worldFile;
    private Path playerFile;
    private Path worldPlayerDataDir;
    /** 是否已接管（没接管时 mixin 必须退回原版行为，避免在没有世界上下文时乱写盘）。 */
    private boolean attached;

    private UuidStore() {
    }

    /**
     * 绑定世界目录（由 SERVER_STARTED 调用）。
     *
     * @param worldDir 世界根目录，例如 {@code saves/新的世界01}
     */
    public synchronized void attachWorld(Path worldDir, Path gameDir) {
        this.worldFile = worldDir.resolve("p2plan").resolve("identities.txt");
        this.worldPlayerDataDir = worldDir.resolve("playerdata");
        this.playerFile = gameDir.resolve("config").resolve("p2plan").resolve("identity.txt");
        this.byName.clear();
        this.byToken.clear();
        this.entries.clear();
        load(playerFile);
        load(worldFile);
        this.attached = true;
        P2PLanMod.LOGGER.info("[p2plan] 身份仓库已接管：world={} player={} 已知身份={}",
                worldFile, playerFile, entries.size());
    }

    public synchronized void detach() {
        this.attached = false;
    }

    public synchronized boolean isAttached() {
        return attached;
    }

    /**
     * 取该名字的稳定 UUID（必要时创建并落盘）。未接管时返回 null，调用方应退回原版行为。
     */
    public synchronized UUID lookupOrCreate(String name) {
        if (!attached || name == null || name.isEmpty()) {
            return null;
        }
        String key = name.toLowerCase();

        UUID known = byName.get(key);
        if (known != null) {
            return known;
        }

        // 老存档保护：原版离线 UUID 已有玩家数据 → 沿用，不迁移
        UUID vanilla = vanillaOfflineUuid(name);
        if (hasPlayerData(vanilla)) {
            adopt(vanilla, name, null);
            saveAll();
            P2PLanMod.LOGGER.info("[p2plan] {} 沿用原版离线 UUID（已有玩家数据）: {}", name, vanilla);
            return vanilla;
        }

        UUID created = UUID.randomUUID();
        adopt(created, name, null);
        saveAll();
        P2PLanMod.LOGGER.info("[p2plan] 为 {} 新建稳定 UUID: {}", name, created);
        return created;
    }

    /** 用令牌认领身份（二期握手用）：成功则把新名字并入旧身份。 */
    public synchronized UUID claimByToken(String name, String token) {
        if (!attached || token == null || token.isEmpty()) {
            return null;
        }
        UUID uuid = byToken.get(token);
        if (uuid == null) {
            return null;
        }
        Entry e = entries.get(uuid);
        if (e != null && name != null && !name.isEmpty()) {
            String key = name.toLowerCase();
            if (!e.names.contains(key)) {
                e.names.add(key);
            }
            byName.put(key, uuid);
            saveAll();
            P2PLanMod.LOGGER.info("[p2plan] 令牌认领成功：{} -> {}（旧身份 {}）", name, uuid, e.names.get(0));
        }
        return uuid;
    }

    /** 房主手动把「新名字」并入某个已有身份（改名的兜底手段）。 */
    public synchronized boolean mergeNameInto(UUID target, String newName) {
        Entry e = entries.get(target);
        if (e == null || newName == null || newName.isEmpty()) {
            return false;
        }
        String key = newName.toLowerCase();
        e.names.add(key);
        byName.put(key, target);
        saveAll();
        return true;
    }

    /**
     * 记下访客自带的令牌与本局 UUID 的对应关系（握手时首次见面调用）。
     *
     * <p>没有这一步整个「改名不丢背包」是死的：本机只会**带出**令牌，
     * 而房主从不**记录**令牌 → {@link #claimByToken} 永远查不到 → 玩家改名后仍被当成新人。
     *
     * <p>安全边界（诚实说明）：令牌是**持有即有效**的凭据（bearer token），只存在玩家自己的
     * {@code config/p2plan/identity.txt} 里、经 iroh 端到端加密的隧道传输，不藏在房间码里。
     * 能拿到别人令牌的人就能在对端认领其身份 —— 与拿到对方本地文件同等信任级别，是本设计的已知天花板。
     */
    public synchronized void recordToken(UUID uuid, String name, String token) {
        if (!attached || uuid == null || token == null || token.isEmpty() || token.length() > 64) {
            return;
        }
        UUID existing = byToken.get(token);
        if (existing != null && !existing.equals(uuid)) {
            // 一个令牌已被记到别的身份上：不覆盖（防串号），只记日志
            P2PLanMod.LOGGER.warn("[p2plan] 令牌 {}… 已绑定另一个身份，忽略本次登记", token.substring(0, 4));
            return;
        }
        adopt(uuid, name, token);
        saveAll();
        P2PLanMod.LOGGER.info("[p2plan] 已记录 {} 的身份令牌（uuid={}），改名后可凭它认领背包", name, uuid);
    }

    /**
     * 本机玩家自己（按名字）的可携带 token；没有就当场生成并落盘。
     *
     * <p>为什么按名字而不是「随便返回第一个有 token 的身份」：世界文件里存着**所有来过这个服的
     * 访客**的 token。旧实现 {@code localToken()} 遍历取第一个非空 token，房主再去连别人的服务器时
     * 就会把某个访客的 token 带过去，在对端被认领成那个访客 → 串号、拿错背包（数据安全 bug）。
     */
    public synchronized String tokenForName(String name) {
        if (!attached || name == null || name.isEmpty()) {
            return null;
        }
        UUID uuid = lookupOrCreate(name);
        if (uuid == null) {
            return null;
        }
        Entry e = entries.get(uuid);
        if (e == null) {
            return null;
        }
        if (e.token == null || e.token.isEmpty()) {
            e.token = RoomCode.randomSecret(24);
            byToken.put(e.token, uuid);
            saveAll();
            P2PLanMod.LOGGER.info("[p2plan] 为本机玩家 {} 生成可携带身份令牌", name);
        }
        return e.token;
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    // ---------------------------------------------------------------- 内部

    private void adopt(UUID uuid, String name, String token) {
        Entry e = entries.computeIfAbsent(uuid, Entry::new);
        if (name != null && !name.isEmpty()) {
            String key = name.toLowerCase();
            if (!e.names.contains(key)) {
                e.names.add(key);
            }
            byName.put(key, uuid);
        }
        if (token != null && !token.isEmpty()) {
            e.token = token;
            byToken.put(token, uuid);
        }
    }

    private boolean hasPlayerData(UUID uuid) {
        if (worldPlayerDataDir == null) {
            return false;
        }
        try {
            return Files.exists(worldPlayerDataDir.resolve(uuid + ".dat"))
                    || Files.exists(worldPlayerDataDir.resolve(uuid + ".dat_old"));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 原版离线 UUID 算法（等价于 UUID.nameUUIDFromBytes("OfflinePlayer:" + name)）。 */
    public static UUID vanillaOfflineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            P2PLanMod.LOGGER.warn("[p2plan] 读取身份文件失败 {}: {}", file, e.toString());
            return;
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(parts[0].trim());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String token = parts[1].trim();
            Entry e = entries.computeIfAbsent(uuid, Entry::new);
            if (!token.isEmpty() && !"-".equals(token)) {
                e.token = token;
                byToken.put(token, uuid);
            }
            for (String n : parts[2].split(",")) {
                String key = n.trim().toLowerCase();
                if (!key.isEmpty()) {
                    if (!e.names.contains(key)) {
                        e.names.add(key);
                    }
                    byName.put(key, uuid);
                }
            }
        }
    }

    private void saveAll() {
        save(playerFile);
        save(worldFile);
    }

    private void save(Path file) {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# p2plan identities v1  (uuid|token|names)\n");
        for (Entry e : entries.values()) {
            sb.append(e.uuid).append('|')
              .append(e.token == null || e.token.isEmpty() ? "-" : e.token).append('|')
              .append(String.join(",", e.names)).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            P2PLanMod.LOGGER.warn("[p2plan] 写入身份文件失败 {}: {}", file, e.toString());
        }
    }
}