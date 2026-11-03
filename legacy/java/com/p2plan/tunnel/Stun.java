package com.p2plan.tunnel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 最小 STUN 客户端（RFC 5389 §7.2 Binding Request / §7.3 Binding Success Response）。
 *
 * <p><b>关键约束：请求必须从调用方给的那一个 {@link DatagramSocket} 发出去。</b>
 * 同一条 socket 才能保证「发现的公网映射」就是「打洞用的映射」；换一条 socket 拿到的是另一个映射，
 * 打洞必然失败（本机房主侧是对称型 NAT，这点尤其致命）。
 *
 * <p>只做 Binding，不解析 MESSAGE-INTEGRITY/FINGERPRINT（不需要鉴权）。IPv4/IPv6 都支持：
 * XOR-MAPPED-ADDRESS(0x0020) 优先，MAPPED-ADDRESS(0x0001) 兜底。
 */
final class Stun {

    static final int MAGIC_COOKIE = 0x2112A442;
    static final int BINDING_REQUEST = 0x0001;
    static final int BINDING_RESPONSE = 0x0101;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Stun() {
    }

    /** 判断一个 UDP 报文是不是 STUN 应答（前两位 00 + magic cookie）。 */
    static boolean isStun(byte[] buf, int off, int len) {
        return len - off >= 20 && (buf[off] & 0xC0) == 0 && readInt(buf, off + 4) == MAGIC_COOKIE;
    }

    /**
     * 对多个 STUN 服务器<b>同时</b>发请求（同一个 socket），在 timeoutMs 内收集应答。
     *
     * <p>并发发而不是串行等：串行最坏 4×(单次超时) 会让房主开房白等十几秒。
     *
     * @param inbox 由收包线程转投进来的 STUN 应答（必须已经过滤出 STUN 报文）
     * @return 去重后的公网映射地址（可能为空：全部服务器不可达）
     */
    static List<InetSocketAddress> discover(DatagramSocket socket, List<String> servers, int timeoutMs,
                                            BlockingQueue<DatagramPacket> inbox) {
        List<InetSocketAddress> out = new ArrayList<>();
        List<byte[]> pending = new ArrayList<>();
        for (String spec : servers) {
            InetSocketAddress target = resolve(spec);
            if (target == null) {
                continue;
            }
            byte[] tx = new byte[12];
            RANDOM.nextBytes(tx);
            byte[] req = bindingRequest(tx);
            try {
                socket.send(new DatagramPacket(req, req.length, target));
                pending.add(tx);
            } catch (IOException e) {
                // 服务器解析成了本机不可达的地址族（例如 v6-only 的 DNS 结果）：跳过
            }
        }
        long deadline = System.nanoTime() + Math.max(100, timeoutMs) * 1_000_000L;
        while (!pending.isEmpty()) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs <= 0) {
                break;
            }
            DatagramPacket p;
            try {
                p = inbox.poll(leftMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (p == null) {
                break;
            }
            byte[] d = p.getData();
            int off = p.getOffset();
            byte[] matched = matchTransaction(d, off, p.getLength(), pending);
            if (matched == null) {
                continue;
            }
            pending.remove(matched); // 应答过的就别再等了，否则每次都要白等满超时
            InetSocketAddress mapped = parse(d, off, p.getLength());
            if (mapped != null && !out.contains(mapped)) {
                out.add(mapped);
            }
        }
        return out;
    }

    private static byte[] matchTransaction(byte[] d, int off, int len, List<byte[]> pending) {
        if (len - off < 20) {
            return null;
        }
        for (byte[] tx : pending) {
            boolean same = true;
            for (int i = 0; i < 12; i++) {
                if (d[off + 8 + i] != tx[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return tx;
            }
        }
        return null;
    }

    private static byte[] bindingRequest(byte[] tx) {
        byte[] p = new byte[20];
        writeShort(p, 0, BINDING_REQUEST);
        writeShort(p, 2, 0);
        writeInt(p, 4, MAGIC_COOKIE);
        System.arraycopy(tx, 0, p, 8, 12);
        return p;
    }

    /**
     * 解析 Binding Success Response，返回映射地址；不是合法应答/没有地址属性时返回 null。
     *
     * <p>包可见是故意的：SelfTest 用 RFC 5769 的官方字节样例直接验这个解析器。
     */
    static InetSocketAddress parse(byte[] msg, int off, int len) {
        if (len - off < 20 || readShort(msg, off) != BINDING_RESPONSE || readInt(msg, off + 4) != MAGIC_COOKIE) {
            return null;
        }
        int attrs = readShort(msg, off + 2);
        int end = Math.min(off + 20 + attrs, off + len);
        byte[] tx = Arrays.copyOfRange(msg, off + 8, off + 20);
        InetSocketAddress fallback = null;
        int p = off + 20;
        while (p + 4 <= end) {
            int type = readShort(msg, p);
            int alen = readShort(msg, p + 2);
            int v = p + 4;
            if (v + alen > end) {
                break;
            }
            if (type == ATTR_XOR_MAPPED_ADDRESS) {
                InetSocketAddress a = decodeMapped(msg, v, alen, tx);
                if (a != null) {
                    return a; // 优先 0x0020
                }
            } else if (type == ATTR_MAPPED_ADDRESS && fallback == null) {
                fallback = decodeMapped(msg, v, alen, null);
            }
            p = v + ((alen + 3) & ~3); // 属性按 4 字节对齐
        }
        return fallback;
    }

    private static InetSocketAddress decodeMapped(byte[] m, int v, int len, byte[] txId) {
        if (len < 4) {
            return null;
        }
        int family = m[v + 1] & 0xff;
        int port = readShort(m, v + 2);
        boolean xor = txId != null;
        try {
            if (family == 0x01 && len >= 8) {
                byte[] raw = Arrays.copyOfRange(m, v + 4, v + 8);
                if (xor) {
                    xorBytes(raw, 0, cookieBytes(), 0, 4);
                    port ^= MAGIC_COOKIE >>> 16;
                }
                return new InetSocketAddress(InetAddress.getByAddress(raw), port);
            }
            if (family == 0x02 && len >= 20) {
                byte[] raw = Arrays.copyOfRange(m, v + 4, v + 20);
                if (xor) {
                    byte[] mask = new byte[16];
                    System.arraycopy(cookieBytes(), 0, mask, 0, 4);
                    System.arraycopy(txId, 0, mask, 4, 12);
                    xorBytes(raw, 0, mask, 0, 16);
                    port ^= MAGIC_COOKIE >>> 16;
                }
                return new InetSocketAddress(InetAddress.getByAddress(raw), port);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static byte[] cookieBytes() {
        return new byte[]{(byte) (MAGIC_COOKIE >>> 24), (byte) (MAGIC_COOKIE >>> 16),
                (byte) (MAGIC_COOKIE >>> 8), (byte) MAGIC_COOKIE};
    }

    private static void xorBytes(byte[] dst, int dOff, byte[] mask, int mOff, int n) {
        for (int i = 0; i < n; i++) {
            dst[dOff + i] ^= mask[mOff + i];
        }
    }

    private static InetSocketAddress resolve(String spec) {
        int i = spec.lastIndexOf(':');
        if (i <= 0 || i == spec.length() - 1) {
            return null;
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(spec.substring(0, i)),
                    Integer.parseInt(spec.substring(i + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    static int readShort(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xff);
        b[off + 1] = (byte) (v & 0xff);
    }

    static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xff);
        b[off + 1] = (byte) ((v >>> 16) & 0xff);
        b[off + 2] = (byte) ((v >>> 8) & 0xff);
        b[off + 3] = (byte) (v & 0xff);
    }
}