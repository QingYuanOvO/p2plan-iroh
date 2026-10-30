package com.p2plan.client;

import com.p2plan.RoomCode;
import com.p2plan.P2PLanMod;
import com.p2plan.tunnel.Candidate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史房间码：记住最近连过/开过的房间，加入界面能双击直接重连。
 *
 * <p><b>价值</b>：本版本已持久化 NodeID 与监听端口，所以 IP 不变时房间码**完全不变** ——
 * 历史码大概率还能直接用。省掉「找朋友要码 → 复制 → 粘贴」整个过程。
 *
 * <p><b>诚实的天花板</b>：房间码里含房主的 {@code IP:端口}。房主换网络（换 Wi-Fi、重播号、
 * 开关热点）后码就变了，旧码会连不上。所以每条历史都记录「上次成功连通的时间」，
 * 并在列表里明确标出「可能已失效」，而不是让玩家对着一个死码反复重试。
 *
 * <p>存储：{@code config/p2plan/history.txt}，每行
 * {@code 时间戳制表符房间码制表符备注}。最多保留 {@link #MAX} 条，最近用的排最前。
 */
public final class RoomHistory {

    /** 最多保留多少条。 */
    private static final int MAX = 8;
    /** 「新鲜」期限：超过这个时长没成功连过，就提示可能失效。 */
    private static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;

    /** 一条历史记录。 */
    public record Entry(String code, long lastUsedMs, String note) {

        /** 房间码是否还能解析（格式没坏）。 */
        public boolean parseable() {
            return RoomCode.decode(code) != null;
        }

        /** 房主的 NodeID（短），用于显示；解析失败返回 "?"。 */
        public String hostNodeShort() {
            RoomCode rc = RoomCode.decode(code);
            if (rc == null) {
                return "?";
            }
            for (Candidate c : rc.candidates()) {
                if (c.kind() == Candidate.Kind.IROH_NODE) {
                    String h = c.host();
                    return h.length() <= 8 ? h : h.substring(0, 8);
                }
            }
            return "?";
        }

        /** 是否可能已失效（太久没成功连过）。 */
        public boolean stale() {
            return System.currentTimeMillis() - lastUsedMs > STALE_MS;
        }

        /** 列表里显示的一行文字。 */
        public String display() {
            StringBuilder sb = new StringBuilder();
            sb.append("房主 ").append(hostNodeShort());
            if (note != null && !note.isBlank()) {
                sb.append(" · ").append(note);
            }
            long days = (System.currentTimeMillis() - lastUsedMs) / (24L * 60 * 60 * 1000);
            if (days <= 0) {
                sb.append(" · 今天");
            } else {
                sb.append(" · ").append(days).append(" 天前");
            }
            if (stale()) {
                sb.append(" · 可能已失效");
            } else if (!parseable()) {
                sb.append(" · 格式损坏");
            }
            return sb.toString();
        }
    }

    private RoomHistory() {
    }

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("p2plan").resolve("history.txt");
    }

    /** 读全部历史（最近优先）。 */
    public static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) {
                return out;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\t", 3);
                if (p.length < 2) {
                    continue;
                }
                long ts;
                try {
                    ts = Long.parseLong(p[0].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String note = p.length >= 3 ? p[2] : "";
                out.add(new Entry(p[1].trim(), ts, note));
            }
        } catch (Throwable t) {
            P2PLanMod.LOGGER.warn("[p2plan] 读取历史房间码失败", t);
        }
        out.sort((a, b) -> Long.compare(b.lastUsedMs(), a.lastUsedMs()));
        return out;
    }

    /**
     * 记录一次成功使用（去重：同一串码只保留最新一条）。
     *
     * @param note 备注，例如房主名或「上次成功」
     */
    public static void remember(String code, String note) {
        if (code == null || code.isBlank()) {
            return;
        }
        String trimmed = code.trim();
        if (RoomCode.decode(trimmed) == null) {
            return; // 解析不了的不入库，免得污染列表
        }
        List<Entry> all = load();
        all.removeIf(e -> e.code().equals(trimmed));
        all.add(0, new Entry(trimmed, System.currentTimeMillis(), note == null ? "" : note));
        if (all.size() > MAX) {
            all = all.subList(0, MAX);
        }
        save(all);
    }

    /** 删除一条（玩家手动清理失效的）。 */
    public static void forget(String code) {
        List<Entry> all = load();
        all.removeIf(e -> e.code().equals(code));
        save(all);
    }

    private static void save(List<Entry> entries) {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                sb.append(e.lastUsedMs()).append('\t')
                        .append(e.code()).append('\t')
                        .append(e.note() == null ? "" : e.note().replace('\n', ' '))
                        .append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException t) {
            P2PLanMod.LOGGER.warn("[p2plan] 保存历史房间码失败", t);
        }
    }
}
