package com.p2plan.iroh;

import com.p2plan.RoomCode;
import com.p2plan.tunnel.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间码与地址层的**逻辑穷举测试**：往返无损、篡改必检、恶意输入必拒、边界必稳。
 *
 * <p>为什么单独有它：房间码是玩家唯一的手动输入面（粘贴一串文本），
 * 也是安全边界（访客拿它去连地址）。这一层出错的表现是「连不上但看不出为什么」，
 * 最难排，所以必须有不依赖网络的纯逻辑回归。
 *
 * <p>跑法：{@code build.bat roomCodeLogicTest}
 */
public final class RoomCodeLogicTest {

    private static int fail;
    private static int pass;

    private static void ck(boolean cond, String what) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static final String NODE = "965bbddb33176c1e6abe342e7cb7f25ac986812bca35d840cd4624c94ad0b31d";

    public static void main(String[] args) {
        System.out.println("=== 房间码逻辑测试 ===");

        roundTrip();
        inputTolerance();
        tamperDetection();
        securityRejection();
        boundaries();
        legacyAndExpiry();

        System.out.println();
        System.out.printf("通过 %d / %d%n", pass, pass + fail);
        System.out.println(fail == 0 ? "ROOMCODE LOGIC PASS" : "ROOMCODE LOGIC FAIL(" + fail + ")");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 1) 各种地址组合的编解码往返必须无损（含类型与顺序）。 */
    private static void roundTrip() {
        System.out.println("[1] 往返无损");
        List<List<Candidate>> cases = new ArrayList<>();
        cases.add(List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1)));
        cases.add(List.of(
                new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                new Candidate(Candidate.Kind.IPV6, "2408:844f:7306:4f24:2232:a3c6:4824:b11f", 51600),
                new Candidate(Candidate.Kind.IPV4_LAN, "192.168.1.5", 65535),
                new Candidate(Candidate.Kind.IPV4_REFLEXIVE, "203.0.113.7", 49727)));
        // IPv6 压缩写法与整写都必须能还原（房间码里走原始字节，文本形式由 InetAddress 规范化）
        cases.add(List.of(
                new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                new Candidate(Candidate.Kind.IPV6, "::1", 1),
                new Candidate(Candidate.Kind.IPV4_LAN, "0.0.0.0".replace("0.0.0.0", "1.2.3.4"), 1024)));

        for (List<Candidate> cs : cases) {
            for (String secret : new String[]{"ABC123", ""}) {
                String code = RoomCode.of(cs, secret).encode();
                RoomCode back = RoomCode.decode(code);
                ck(back != null, "可解 " + cs.size() + " 候选 secret='" + secret + "'");
                if (back == null) {
                    continue;
                }
                ck(back.secret().equals(secret), "口令往返一致");
                ck(back.candidates().size() == cs.size(), "候选数往返一致");
                for (int i = 0; i < cs.size(); i++) {
                    Candidate a = cs.get(i), b = back.candidates().get(i);
                    ck(a.kind() == b.kind(), "第" + i + "条类型一致 " + a.kind() + " vs " + b.kind());
                    ck(a.port() == b.port(), "第" + i + "条端口一致");
                    // 主机名规范化比较：原始字节 → getHostAddress() 可能与输入写法不同（大小写/压缩）
                    ck(sameHost(a, b), "第" + i + "条地址等价 " + a.host() + " vs " + b.host());
                }
            }
        }
    }

    private static boolean sameHost(Candidate a, Candidate b) {
        if (a.kind() == Candidate.Kind.IROH_NODE) {
            return a.host().equalsIgnoreCase(b.host());
        }
        try {
            byte[] x = java.net.InetAddress.getByName(
                    a.host().replaceAll("^\\[|]$", "")).getAddress();
            byte[] y = java.net.InetAddress.getByName(
                    b.host().replaceAll("^\\[|]$", "")).getAddress();
            return java.util.Arrays.equals(x, y);
        } catch (Exception e) {
            return a.host().equals(b.host());
        }
    }

    /** 2) 玩家粘贴的各种脏格式必须容错（聊天软件会改大小写、加换行）。 */
    private static void inputTolerance() {
        System.out.println("[2] 输入容错");
        var cs = List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                new Candidate(Candidate.Kind.IPV4_LAN, "10.0.0.5", 51600));
        String code = RoomCode.of(cs, "SEC123").encode();

        ck(RoomCode.decode(code.toLowerCase()) != null, "全小写可解");
        ck(RoomCode.decode(code.replace("-", "")) != null, "去掉分组符可解");
        ck(RoomCode.decode("  " + code + "  ") != null, "首尾空白可解");
        ck(RoomCode.decode(code + "\n") != null, "尾随换行可解");
        ck(RoomCode.decode(code.replace("P2P3-", "P2P3")) != null, "缺首个连字符仍可解");
        ck(RoomCode.decode(code.replaceAll("(?<=\\G.{20})", " ")) != null, "每 20 字符插空格可解");
        // 前缀大小写也要容错（有人手打）
        ck(RoomCode.decode("p2p3" + code.substring(4)) != null, "小写前缀可解");
        ck(RoomCode.decode("") == null, "空串判无效");
        ck(RoomCode.decode("hello world") == null, "纯垃圾文本判无效");
        ck(RoomCode.decode(null) == null, "null 判无效");
        ck(RoomCode.decode("P2P3-!!!!") == null, "全非法字符判无效");
    }

    /**
     * 3) 任意一位被改，绝大多必须被 CRC 检出。
     *
     * <p>不要求 100%：Base32 末尾符号有一部分位是**填充位**（不对应任何字节），
     * 改它既不改变内容也不过 CRC —— 玩家手抄时改到那里，粘进来仍能连上同一个房，属良性。
     * 真正要防的是「改一位静默变成另一个有效房间」，所以按拒绝率断言。
     */
    private static void tamperDetection() {
        System.out.println("[3] 篡改必检");
        var cs = List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                new Candidate(Candidate.Kind.IPV6, "2408:844f::1", 51600),
                new Candidate(Candidate.Kind.IPV4_LAN, "192.168.1.5", 25565));
        String code = RoomCode.of(cs, "ABCXYZ").encode();
        final String B32 = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
        int tried = 0;
        int rejected = 0;
        int silentlyDifferent = 0;
        for (int i = 4; i < code.length(); i++) { // 跳过前缀
            char orig = code.charAt(i);
            int oi = B32.indexOf(Character.toUpperCase(orig));
            if (oi < 0) {
                continue; // 连字符等非码位
            }
            for (int j = 0; j < 32; j++) {
                if (j == oi) {
                    continue;
                }
                String t = code.substring(0, i) + B32.charAt(j) + code.substring(i + 1);
                RoomCode r = RoomCode.decode(t);
                tried++;
                if (r == null) {
                    rejected++;
                } else if (!r.secret().equals("ABCXYZ") || r.candidates().size() != cs.size()
                        || !r.candidates().get(2).host().equals("192.168.1.5")) {
                    silentlyDifferent++; // 解出了「另一个」房间：CRC 没拦住，这是危险情况
                }
            }
        }
        System.out.printf("  穷举 %d 个单字符改动：拒绝 %d，可解但内容相同（填充位，良性）%d%n",
                tried, rejected, tried - rejected - silentlyDifferent);
        ck(tried > 1000, "改动样本足够（" + tried + "）");
        ck(silentlyDifferent == 0, "没有「改一位静默变另一房间」的漏检（发现 " + silentlyDifferent + " 例）");
        ck(rejected * 100 / tried >= 95, "拒绝率 ≥95%（实际 " + (rejected * 100 / tried) + "%）");
    }

    /** 4) 安全边界：非字面量主机名（域名）与非法 NodeID 必须拒 —— 防访客侧 DNS/SSRF。 */
    private static void securityRejection() {
        System.out.println("[4] 安全边界");
        try {
            String evil = RoomCode.of(
                    List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                            new Candidate(Candidate.Kind.IPV4_LAN, "evil.example.com", 80)),
                    "X").encode();
            ck(RoomCode.decode(evil) == null, "域名伪装成 IPV4_LAN → 判无效码（阻 DNS/SSRF）");
        } catch (Throwable t) {
            ck(true, "域名候选被生成侧拒绝（也算拦住）: " + t.getClass().getSimpleName());
        }
        try {
            String evil6 = RoomCode.of(
                    List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                            new Candidate(Candidate.Kind.IPV6, "internal.attacker", 25565)),
                    "X").encode();
            ck(RoomCode.decode(evil6) == null, "域名伪装成 IPv6 → 判无效码");
        } catch (Throwable t) {
            ck(true, "非法 IPv6 主机被生成侧拒绝: " + t.getClass().getSimpleName());
        }
        // 非法 NodeID（非 64 hex）
        try {
            String fake = RoomCode.of(List.of(
                    new Candidate(Candidate.Kind.IROH_NODE, "not-hex-at-all", 1)), "X").encode();
            ck(RoomCode.decode(fake) == null, "非法 NodeID → 判无效码");
        } catch (Throwable t) {
            ck(true, "非法 NodeID 生成侧被拒");
        }
        // 握手层的长度闸门在 HostService，这里只验码层能承受 64 位口令往返
        String longSecret = "A".repeat(64);
        RoomCode r = RoomCode.decode(RoomCode.of(
                List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1)), longSecret).encode());
        ck(r != null && r.secret().equals(longSecret), "64 位长口令仍可往返");
    }

    /** 5) 边界与畸形输入：不能抛未捕获异常，必须判无效或明确拒绝。 */
    private static void boundaries() {
        System.out.println("[5] 边界与健壮性");
        // 超长主机名：生成侧必须抛（静默截断会产出「永远连不上」的坏码）
        try {
            RoomCode.of(List.of(new Candidate(Candidate.Kind.RELAY, "a".repeat(256), 1)), "X");
            ck(false, "超长 host 应抛异常");
        } catch (IllegalArgumentException e) {
            ck(e.getMessage().contains("过长"), "超长 host 生成被拒: " + e.getMessage());
        }
        // 空候选
        try {
            RoomCode.of(List.of(), "X");
            ck(false, "空候选应抛异常");
        } catch (IllegalArgumentException e) {
            ck(true, "空候选被拒");
        }
        // 端口越界
        try {
            new Candidate(Candidate.Kind.IPV4_LAN, "1.2.3.4", 0);
            ck(false, "端口 0 应拒绝");
        } catch (IllegalArgumentException e) {
            ck(true, "端口 0 被拒");
        }
        try {
            new Candidate(Candidate.Kind.IPV4_LAN, "1.2.3.4", 70000);
            ck(false, "端口 >65535 应拒绝");
        } catch (IllegalArgumentException e) {
            ck(true, "端口越界被拒");
        }
        // 随机/畸形串喂进 decode：必须 null 或合法返回，绝不抛、绝不崩
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 4000; i++) {
            StringBuilder sb = new StringBuilder("P2P3-");
            int len = 1 + rnd.nextInt(220);
            for (int k = 0; k < len; k++) {
                sb.append("0123456789ABCDEFGHIJKLMNOPQRSTUV-".charAt(rnd.nextInt(33)));
            }
            try {
                RoomCode.decode(sb.toString());
            } catch (Throwable t) {
                ck(false, "畸形串导致抛异常: " + sb + " -> " + t);
                break;
            }
        }
        ck(true, "4000 个随机畸形输入未崩溃");
        // 截断：任意前缀都必须 null 而不是崩
        String code = RoomCode.of(List.of(
                new Candidate(Candidate.Kind.IROH_NODE, NODE, 1),
                new Candidate(Candidate.Kind.IPV4_LAN, "10.0.0.1", 51600)), "ABCDEF").encode();
        for (int i = 0; i < code.length(); i++) {
            try {
                RoomCode.decode(code.substring(0, i));
            } catch (Throwable t) {
                ck(false, "截断到 " + i + " 抛异常: " + t);
                break;
            }
        }
        ck(true, "全部截断前缀未崩溃");
    }

    /** 6) 旧版码不兼容 + 过期语义。 */
    private static void legacyAndExpiry() {
        System.out.println("[6] 旧码与过期");
        ck(RoomCode.decode("P2P2-0C1G2OVV-4G488JRJ") == null, "P2P2 旧前缀判无效");
        ck(RoomCode.decode("P2P1-0C1G2OVV-4G488JRJ") == null, "P2P1 旧前缀判无效");
        ck(RoomCode.decode("0C1G2OVV-4G488JRJ-46MM9O3E") == null, "无前缀判无效（避免误解）");

        var cs = List.of(new Candidate(Candidate.Kind.IROH_NODE, NODE, 1));
        // 默认 24h
        RoomCode fresh = RoomCode.decode(RoomCode.create(cs)[0]);
        ck(fresh != null && !fresh.expired(), "新码未过期");
        ck(fresh.expiresAt() * 1000L - System.currentTimeMillis() > 23 * 3600_000L,
                "新码有效期接近 24 小时");
        // 不限期
        RoomCode forever = RoomCode.decode(RoomCode.create(cs, 0)[0]);
        ck(forever != null && forever.expiresAt() == 0 && !forever.expired(), "ttl=0 = 不限期");
        // 过期
        RoomCode dead = RoomCode.decode(RoomCode.create(cs, 1)[0]);
        ck(dead != null && dead.expiresAt() > 0, "ttl=1s 写入了过期时间");
        ck(dead.expired() || System.currentTimeMillis() / 1000L < dead.expiresAt(),
                "过期判定与时间基准一致");
        // 手工构造一个「已过期」的对象直接验语义
        RoomCode past = RoomCode.of(cs, "X", System.currentTimeMillis() / 1000L - 10);
        ck(past.expired(), "过去时间 → expired()=true");
        ck(past.expiryText().contains("已过期"), "过期文案正确");
        ck(RoomCode.of(cs, "X", 0).expiryText().contains("不限期"), "不限期文案正确");
    }
}
