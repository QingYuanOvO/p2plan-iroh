package com.p2plan;

import com.p2plan.tunnel.Candidate;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 房间码 v3：把「一组成员候选地址（IPv6 直连 / STUN 反射打洞 / 局域网）+ 邀请口令」编码成一串短码，
 * 形如 {@code P2P3-eyJ2Ijo...}。
 *
 * <p><b>为什么有 v3</b>：v2 把地址按 ASCII 文本存（一条 IPv6 要 39 字符），8 条候选时整串码长达 393 字符，
 * 玩家根本没法复制/手输。v3 做了两处纯编码优化，<b>不损失任何候选</b>（成功率不降）：
 * <ol>
 *   <li>地址存<b>原始字节</b>：IPv6 固定 16 字节、IPv4 固定 4 字节（文本 → 二进制的无损压缩）；</li>
 *   <li><b>Base64URL</b>（6 bit/字符）替代 Crockford Base32（5 bit/字符），再省约 17%。</li>
 * </ol>
 * 实测 8 条候选：393 字符 → 约 230 字符；3 条候选约 100 字符。
 *
 * <p>载荷布局（全部二进制，最后整体 Base64URL 去填充）：
 * <pre>
 * 版本(1)=3 | 候选数(1)
 *   | [类型(1) | 端口(2,大端) | 地址(4 或 16 字节，按类型决定)] × 候选数
 *   | 口令长度(1) | 口令(ASCII) | CRC16(2)
 * </pre>
 *
 * <p>口令是明文嵌在码里的：持有这串码本身就是邀请凭据；关房重开会换新码。
 * 改动任意一位都会让 CRC 校验失败，直接判为无效码。
 *
 * <p>ponytail: Base64 不是加密，只是编码 —— 谁都能解开看地址，这是设计如此（对方必须知道地址才能连）。
 * 口令也只是准入凭据、不做传输加密；要防窃听得在隧道载荷上加 AEAD。
 */
public record RoomCode(int version, List<Candidate> candidates, String secret, int crc,
                       /** 过期时间（epoch 秒）；0 = 不限期（旧码或未设置）。 */
                       long expiresAt) {

    public static final int VERSION = 3;
    private static final String PREFIX = "P2P3";
    private static final int SECRET_LEN = 6;
    private static final int MAX_CANDIDATES = 8;
    /** 默认有效期：24 小时。房间码是「一次联机」的邀请凭据，不该永久有效。 */
    public static final long DEFAULT_TTL_SECONDS = 24 * 60 * 60;

    public RoomCode {
        candidates = List.copyOf(candidates);
        secret = secret == null ? "" : secret;
    }

    /** 兼容旧构造：不带过期时间。 */
    public RoomCode(int version, List<Candidate> candidates, String secret, int crc) {
        this(version, candidates, secret, crc, 0L);
    }

    /** 用候选列表生成房间码，口令随机，默认 24 小时有效；返回 {@code [码文本, 口令]}。 */
    public static String[] create(List<Candidate> candidates) {
        return create(candidates, DEFAULT_TTL_SECONDS);
    }

    /** 指定有效期（秒）生成房间码；{@code ttlSeconds <= 0} 表示不限期。 */
    public static String[] create(List<Candidate> candidates, long ttlSeconds) {
        String secret = randomSecret(SECRET_LEN);
        long exp = ttlSeconds <= 0 ? 0L : (System.currentTimeMillis() / 1000L) + ttlSeconds;
        return new String[]{of(candidates, secret, exp).encode(), secret};
    }

    public static RoomCode of(List<Candidate> candidates, String secret) {
        return of(candidates, secret, 0L);
    }

    public static RoomCode of(List<Candidate> candidates, String secret, long expiresAt) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("房间码至少要有一条候选地址");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("候选地址过多：" + candidates.size());
        }
        List<Candidate> cs = List.copyOf(candidates);
        String s = secret == null ? "" : secret;
        return new RoomCode(VERSION, cs, s, crc16(body(cs, s, expiresAt)), expiresAt);
    }

    /** 是否已过期（过期时间 0 视为不过期）。 */
    public boolean expired() {
        if (expiresAt <= 0) {
            return false;
        }
        return System.currentTimeMillis() / 1000L > expiresAt;
    }

    /** 剩余有效期文字（给 GUI 显示）。 */
    public String expiryText() {
        if (expiresAt <= 0) {
            return "不限期";
        }
        long left = expiresAt - System.currentTimeMillis() / 1000L;
        if (left <= 0) {
            return "已过期";
        }
        long h = left / 3600;
        if (h >= 1) {
            return "剩 " + h + " 小时";
        }
        return "剩 " + (left / 60) + " 分钟";
    }

    // ------------------------------------------------------------------ 编码

    private static byte[] body(List<Candidate> candidates, String secret, long expiresAt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VERSION);
        out.write(candidates.size());
        for (Candidate c : candidates) {
            byte[] addr = rawAddress(c.host());
            // 类型字节的高位标记「地址是不是原始字节」：解析不了的主机名退回文本存法
            if (addr != null) {
                out.write(c.kind().wireId);
                out.write((c.port() >>> 8) & 0xff);
                out.write(c.port() & 0xff);
                out.writeBytes(addr);
            } else {
                byte[] txt = c.host().getBytes(StandardCharsets.US_ASCII);
                // 长度字节只有 1 字节：>255 会被静默截断，导致生成的码自解后 host 变短、
                // 而 CRC 算的是截断后的 body 仍匹配 —— 表现为「码看着正常却永远连不上」。
                // 与其产出这种坏码，不如直接拒绝（调用方应传字面量地址，不该有这么长的主机名）。
                if (txt.length > 255) {
                    throw new IllegalArgumentException("候选主机名过长（" + txt.length + " 字节 > 255）：" + c.host());
                }
                out.write(c.kind().wireId | 0x80);
                out.write((c.port() >>> 8) & 0xff);
                out.write(c.port() & 0xff);
                out.write(txt.length);
                out.writeBytes(txt);
            }
        }
        byte[] sec = secret.getBytes(StandardCharsets.US_ASCII);
        if (sec.length > 255) {
            throw new IllegalArgumentException("口令过长（" + sec.length + " 字节 > 255）");
        }
        out.write(sec.length);
        out.writeBytes(sec);
        // 过期时间（4 字节大端，epoch 秒）。0 = 不限期；旧码没有这 4 字节，解码时会识别。
        out.write((int) ((expiresAt >>> 24) & 0xff));
        out.write((int) ((expiresAt >>> 16) & 0xff));
        out.write((int) ((expiresAt >>> 8) & 0xff));
        out.write((int) (expiresAt & 0xff));
        return out.toByteArray();
    }

    /** 生成可直接分享的短码。 */
    public String encode() {
        byte[] b = body(candidates, secret, expiresAt);
        byte[] withCrc = new byte[b.length + 2];
        System.arraycopy(b, 0, withCrc, 0, b.length);
        withCrc[b.length] = (byte) ((crc >>> 8) & 0xff);
        withCrc[b.length + 1] = (byte) (crc & 0xff);
        // Base32hex（0-9 + A-V）：大小写无关、无 '+' '/' '=' 之类要转义的字符，
        // 玩家被聊天软件改成全大写/全小写也能解回来（Base64 区分大小写，一改就废）。
        String b32 = base32hex(withCrc);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < b32.length(); i += 8) {
            sb.append('-').append(b32, i, Math.min(i + 8, b32.length()));
        }
        return sb.toString();
    }

    /** 解析用户粘贴的码；返回 null 表示不是合法房间码。 */
    public static RoomCode decode(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.length() >= 4 && s.regionMatches(true, 0, PREFIX, 0, 4)) {
            s = s.substring(4);
        } else if (s.length() >= 4 && (s.regionMatches(true, 0, "P2P2", 0, 4)
                || s.regionMatches(true, 0, "P2P1", 0, 4))) {
            return null; // 旧版码不兼容
        }
        // 大小写无关：统一转大写后按 Base32hex 字母表过滤（丢弃分组符与空白）
        byte[] raw;
        try {
            raw = unbase32hex(s);
        } catch (RuntimeException e) {
            return null;
        }
        if (raw.length < 8) {
            return null;
        }
        int bodyLen = raw.length - 2;
        byte[] b = new byte[bodyLen];
        System.arraycopy(raw, 0, b, 0, bodyLen);
        int crc = ((raw[bodyLen] & 0xff) << 8) | (raw[bodyLen + 1] & 0xff);
        if (crc != crc16(b)) {
            return null;
        }
        int p = 0;
        if (b[p++] != VERSION) {
            return null;
        }
        int n = b[p++] & 0xff;
        if (n <= 0 || n > MAX_CANDIDATES) {
            return null;
        }
        List<Candidate> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (p + 3 > b.length) {
                return null;
            }
            int typeByte = b[p++] & 0xff;
            boolean textual = (typeByte & 0x80) != 0;
            Candidate.Kind kind = Candidate.Kind.fromWire((byte) (typeByte & 0x7f));
            if (kind == null) {
                return null;
            }
            int port = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
            p += 2;
            String host;
            if (textual) {
                if (p >= b.length) {
                    return null;
                }
                int len = b[p++] & 0xff;
                if (p + len > b.length) {
                    return null;
                }
                host = new String(b, p, len, StandardCharsets.US_ASCII);
                p += len;
                // 信任边界校验：本模组生成的合法码里，IP 类型候选都走「原始字节」分支；
                // 文本分支只有 iroh NodeID（64 位 hex）。其它任何主机名（尤其 evil.example 这类
                // 域名）会在访客侧触发 DNS 解析/连接（内网探测面），一律判无效码拒掉。
                // RELAY 也不例外：iroh 版根本用不到它，含 RELAY 的跨版本旧码本就连不上，
                // 与其带着一段可能触发解析的字符串继续跑，不如干净地报「无效码」。
                if (kind == Candidate.Kind.IROH_NODE) {
                    if (!host.matches("(?i)[0-9a-f]{64}")) {
                        return null; // NodeID 必须是 64 位 hex
                    }
                } else if (rawAddress(host) == null) {
                    return null; // 非字面量 IP 的文本主机名 = 伪造码
                }
            } else {
                int len = kind == Candidate.Kind.IPV6 ? 16 : 4;
                if (p + len > b.length) {
                    return null;
                }
                byte[] a = new byte[len];
                System.arraycopy(b, p, a, 0, len);
                p += len;
                try {
                    host = InetAddress.getByAddress(a).getHostAddress();
                } catch (Exception e) {
                    return null;
                }
            }
            try {
                list.add(new Candidate(kind, host, port));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (p >= b.length) {
            return null;
        }
        int secLen = b[p++] & 0xff;
        if (p + secLen > b.length) {
            return null;
        }
        String secret = new String(b, p, secLen, StandardCharsets.US_ASCII);
        p += secLen;
        // 过期时间是可选尾字段（4 字节）：旧码没有，按「不限期」处理，保证旧码仍能解。
        long exp = 0L;
        if (p + 4 <= b.length) {
            exp = ((long) (b[p] & 0xff) << 24)
                    | ((long) (b[p + 1] & 0xff) << 16)
                    | ((long) (b[p + 2] & 0xff) << 8)
                    | (long) (b[p + 3] & 0xff);
        }
        return new RoomCode(VERSION, list, secret, crc, exp);
    }

    /** 校验口令是否与本房间码一致（忽略大小写）。 */
    public boolean secretMatches(String other) {
        return other != null && other.equalsIgnoreCase(secret);
    }

    /** 给日志/GUI 的多行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("P2P房间（" + candidates.size() + " 条候选）");
        for (Candidate c : candidates) {
            sb.append("\n  - ").append(c.describe());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把地址文本解析成原始字节（IPv4 4 字节 / IPv6 16 字节）；解析不了返回 null。
     * 只接受字面量地址，不做 DNS（避免房间码依赖域名解析）。
     */
    private static byte[] rawAddress(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        // 明显的非字面量（含字母但不像 IPv6）直接放弃，免得触发 DNS
        boolean looksV6 = h.indexOf(':') >= 0;
        boolean looksV4 = h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!looksV6 && !looksV4) {
            return null;
        }
        try {
            InetAddress a = InetAddress.getByName(h);
            if (looksV4 && !(a instanceof java.net.Inet4Address)) {
                return null;
            }
            if (looksV6 && !(a instanceof java.net.Inet6Address)) {
                return null;
            }
            return a.getAddress();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 随机邀请口令（人可读，去掉易混字符）。 */
    public static String randomSecret(int len) {
        final String ab = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < len; i++) {
            sb.append(ab.charAt(r.nextInt(ab.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Base32hex

    private static final String B32 = "0123456789ABCDEFGHIJKLMNOPQRSTUV";

    /** 5 bit/字符，大小写无关，无需要转义的字符。 */
    private static String base32hex(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    private static byte[] unbase32hex(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char raw : s.toCharArray()) {
            char c = Character.toUpperCase(raw);
            int v = B32.indexOf(c);
            if (v < 0) {
                continue; // 丢弃分组符、空白等无关字符
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int crc16(byte[] data) {
        int crc = 0xffff;
        for (byte b : data) {
            crc ^= (b & 0xff) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
            }
        }
        return crc;
    }

    public static String uuidShort(UUID u) {
        return u == null ? "-" : u.toString().substring(0, 8);
    }
}