package com.p2plan.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * 已打通的 UDP socket 之上的有序可靠字节流（给 MC 的 TCP 流当管子用）。
 *
 * <p>协议：每个 UDP 报文 = {@link UdpTunnel#HDR} 字节头 + 载荷。
 * 序号从 0 开始按段递增（段长 ≤ {@link #PAYLOAD_MAX}），ACK 是累计确认（携带接收方期望的下一个序号），
 * 另外用窗口字段做接收侧流控；超时按 go-back-N 重传，RTO 用 SRTT/RTTVAR 估计。
 * 对外只有阻塞式 {@link InputStream}/{@link OutputStream}。
 *
 * <p>ponytail: go-back-N + 每包必回 ACK，够 MC 的流量用；丢包率高的链路上应该换选择性重传（SACK）和延迟 ACK。
 */
final class ReliableStream implements AutoCloseable {

    /** 发送一个已经拼好的报文（UdpTunnel 负责路由到当前对端端点）。 */
    interface Sender {
        void send(byte[] packet, int len);
    }

    /** 段载荷上限：IPv6 最小 MTU 1280 - 40(IPv6头) - 8(UDP头) - 20(本协议头) 之后留足余量。 */
    static final int PAYLOAD_MAX = 1100;
    private static final int WINDOW = 128;
    private static final long RCV_BUF = (long) WINDOW * PAYLOAD_MAX;
    private static final int OOO_LIMIT = WINDOW * 2;
    private static final long MIN_RTO_NS = 60L * 1_000_000L;
    private static final long MAX_RTO_NS = 2_000L * 1_000_000L;
    private static final long WRITE_TIMEOUT_NS = 30L * 1_000_000_000L;
    private static final int MAX_RETRANSMIT = 60;
    private static final int FIN_TRIES = 12;

    private static final class Seg {
        final byte[] data;
        final long sentAt;

        Seg(byte[] data, long sentAt) {
            this.data = data;
            this.sentAt = sentAt;
        }
    }

    private static final class Chunk {
        final byte[] data;
        int off;

        Chunk(byte[] data) {
            this.data = data;
        }
    }

    private final Sender sender;
    private final Object lock = new Object();
    private final ArrayDeque<Seg> inflight = new ArrayDeque<>();
    private final TreeMap<Integer, byte[]> outOfOrder = new TreeMap<>();
    private final ArrayDeque<Chunk> ready = new ArrayDeque<>();
    private final Thread timer;

    private int sndUna;
    private int sndNxt;
    private int rcvNext;
    private long readyBytes;
    private long oooBytes;
    private int peerWnd = WINDOW;
    private int lastWndSent = -1;
    private boolean finSent;
    private boolean finAcked;
    private boolean finReceived;
    private long finSentAt;
    private int finTries;
    private volatile boolean closed;
    private long srttNs = -1;
    private long rttvarNs;
    private long rtoNs = 300L * 1_000_000L;
    private long timerStart;
    private int retransmits;

    private final InputStream input = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return ReliableStream.this.read(b, off, len);
        }
    };

    private final OutputStream output = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ReliableStream.this.write(b, off, len);
        }

        /** UDP 上一个 write 已经把数据交出去了；这里不做「等到 ACK」的阻塞语义（会把流水线变成一次 RTT 一发）。 */
        @Override
        public void flush() {
        }
    };

    ReliableStream(Sender sender) {
        this.sender = sender;
        this.timer = new Thread(this::timerLoop, "p2p-stream-timer");
        this.timer.setDaemon(true);
        this.timer.start();
    }

    InputStream input() {
        return input;
    }

    OutputStream output() {
        return output;
    }

    // ------------------------------------------------------------------ 发送

    void write(byte[] b, int off, int len) throws IOException {
        int left = len;
        while (left > 0) {
            byte[] packet;
            int seq;
            synchronized (lock) {
                long deadline = System.nanoTime() + WRITE_TIMEOUT_NS;
                while (!closed && !finSent && inflight.size() >= Math.min(WINDOW, peerWnd)) {
                    long waitMs = (deadline - System.nanoTime()) / 1_000_000L;
                    if (waitMs <= 0) {
                        throw new IOException("隧道发送超时：对端长时间没有确认");
                    }
                    try {
                        lock.wait(Math.max(1, Math.min(200, waitMs)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("写入被中断");
                    }
                }
                if (closed || finSent) {
                    throw new IOException("隧道已关闭，无法再发送");
                }
                int n = Math.min(PAYLOAD_MAX, left);
                byte[] seg = Arrays.copyOfRange(b, off, off + n);
                seq = sndNxt++;
                inflight.addLast(new Seg(seg, System.nanoTime()));
                if (inflight.size() == 1) {
                    timerStart = System.nanoTime();
                }
                packet = UdpTunnel.buildPacket(UdpTunnel.T_DATA, rcvNext, advertisedWnd(), seq, seg, seg.length);
                off += n;
                left -= n;
            }
            sender.send(packet, packet.length);
        }
    }

    /** 半关闭：等自己在途的数据被确认后发 FIN（等不到也要发，对端有权知道自己该收尾了）。 */
    void closeOutput() {
        byte[] packet = null;
        synchronized (lock) {
            if (closed || finSent) {
                return;
            }
            long deadline = System.nanoTime() + 10L * 1_000_000_000L;
            while (!closed && !inflight.isEmpty()) {
                long waitMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (waitMs <= 0) {
                    break;
                }
                try {
                    lock.wait(Math.max(1, Math.min(200, waitMs)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            finSent = true;
            finSentAt = System.nanoTime();
            finTries = 0;
            packet = UdpTunnel.buildPacket(UdpTunnel.T_FIN, rcvNext, advertisedWnd(), 0, null, 0);
        }
        sender.send(packet, packet.length);
    }

    /** 保活：维持 NAT 映射，同时顺手把窗口信息带给对端。 */
    void sendKeepalive() {
        byte[] packet;
        synchronized (lock) {
            if (closed) {
                return;
            }
            packet = UdpTunnel.buildPacket(UdpTunnel.T_KA, rcvNext, advertisedWnd(), 0, null, 0);
        }
        sender.send(packet, packet.length);
    }

    // ------------------------------------------------------------------ 接收

    int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int total = 0;
        boolean sendWindowUpdate = false;
        synchronized (lock) {
            while (!closed && readyBytes == 0 && !finReceived) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取被中断");
                }
            }
            if (readyBytes > 0) {
                while (len > 0 && !ready.isEmpty()) {
                    Chunk c = ready.peekFirst();
                    int n = Math.min(len, c.data.length - c.off);
                    System.arraycopy(c.data, c.off, b, off, n);
                    c.off += n;
                    off += n;
                    len -= n;
                    total += n;
                    readyBytes -= n;
                    if (c.off >= c.data.length) {
                        ready.pollFirst();
                    }
                }
                sendWindowUpdate = advertisedWnd() - Math.max(0, lastWndSent) >= WINDOW / 4;
                lock.notifyAll();
            } else if (finReceived) {
                return -1;
            } else {
                throw new IOException("隧道已关闭");
            }
        }
        if (sendWindowUpdate) {
            sendAck();
        }
        return total;
    }

    /** 收包线程把已识别为流协议的报文交到这里。 */
    void onPacket(byte[] d, int off, int len, int type) {
        int ack = Stun.readInt(d, off + 8);
        int wnd = Stun.readInt(d, off + 12);
        switch (type) {
            case UdpTunnel.T_DATA -> {
                int plen = Stun.readShort(d, off + 6);
                int seq = Stun.readInt(d, off + 16);
                if (plen <= 0 || off + UdpTunnel.HDR + plen > len) {
                    return;
                }
                byte[] payload = Arrays.copyOfRange(d, off + UdpTunnel.HDR, off + UdpTunnel.HDR + plen);
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    peerWnd = clampWnd(wnd);
                    handleAck(ack);
                    if (seq == rcvNext) {
                        deliver(payload);
                        rcvNext++;
                        drainOutOfOrder();
                    } else if (seq > rcvNext && seq - rcvNext <= WINDOW + OOO_LIMIT) {
                        if (outOfOrder.size() < OOO_LIMIT && !outOfOrder.containsKey(seq)) {
                            outOfOrder.put(seq, payload);
                            oooBytes += payload.length;
                        }
                    }
                    lock.notifyAll();
                }
                sendAck();
            }
            case UdpTunnel.T_ACK, UdpTunnel.T_KA -> {
                synchronized (lock) {
                    peerWnd = clampWnd(wnd);
                    handleAck(ack);
                    lock.notifyAll();
                }
            }
            case UdpTunnel.T_FIN -> {
                synchronized (lock) {
                    peerWnd = clampWnd(wnd);
                    handleAck(ack);
                    finReceived = true;
                    lock.notifyAll();
                }
                sendFinAck();
            }
            case UdpTunnel.T_FIN_ACK -> {
                synchronized (lock) {
                    finAcked = true;
                    lock.notifyAll();
                }
            }
            default -> {
            }
        }
    }

    private void deliver(byte[] payload) {
        ready.addLast(new Chunk(payload));
        readyBytes += payload.length;
    }

    private void drainOutOfOrder() {
        byte[] p;
        while ((p = outOfOrder.remove(rcvNext)) != null) {
            oooBytes -= p.length;
            deliver(p);
            rcvNext++;
        }
    }

    private void sendAck() {
        int ack;
        int wnd;
        synchronized (lock) {
            ack = rcvNext;
            wnd = advertisedWnd();
            lastWndSent = wnd;
        }
        byte[] packet = UdpTunnel.buildPacket(UdpTunnel.T_ACK, ack, wnd, 0, null, 0);
        sender.send(packet, packet.length);
    }

    private void sendFinAck() {
        byte[] packet;
        synchronized (lock) {
            packet = UdpTunnel.buildPacket(UdpTunnel.T_FIN_ACK, rcvNext, advertisedWnd(), 0, null, 0);
        }
        sender.send(packet, packet.length);
    }

    private int advertisedWnd() {
        long free = RCV_BUF - readyBytes - oooBytes;
        if (free < 0) {
            free = 0;
        }
        return (int) Math.max(0, Math.min(WINDOW, free / PAYLOAD_MAX));
    }

    private static int clampWnd(int wnd) {
        // 下限 1：窗口为 0 时允许一个探针段，避免双方一起死等（零窗口探测）
        return Math.max(1, Math.min(WINDOW, wnd));
    }

    private void handleAck(int ack) {
        if (ack <= sndUna || ack > sndNxt) {
            return; // 重复/无效 ACK
        }
        Seg oldest = inflight.peekFirst();
        int advance = ack - sndUna;
        for (int i = 0; i < advance && !inflight.isEmpty(); i++) {
            inflight.pollFirst();
        }
        sndUna = ack;
        retransmits = 0;
        long now = System.nanoTime();
        if (oldest != null) {
            updateRto(now - oldest.sentAt);
        }
        timerStart = inflight.isEmpty() ? 0 : now;
    }

    private void updateRto(long sampleNs) {
        if (sampleNs <= 0) {
            return;
        }
        if (srttNs < 0) {
            srttNs = sampleNs;
            rttvarNs = sampleNs / 2;
        } else {
            long diff = Math.abs(srttNs - sampleNs);
            rttvarNs = (3 * rttvarNs + diff) / 4;
            srttNs = (7 * srttNs + sampleNs) / 8;
        }
        rtoNs = Math.max(MIN_RTO_NS, Math.min(MAX_RTO_NS, srttNs + 4 * rttvarNs));
    }

    private void timerLoop() {
        while (!closed) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
            List<byte[]> resend = new ArrayList<>();
            synchronized (lock) {
                long now = System.nanoTime();
                if (sndUna < sndNxt && timerStart != 0 && now - timerStart >= rtoNs) {
                    int seq = sndUna;
                    for (Seg s : inflight) {
                        resend.add(UdpTunnel.buildPacket(UdpTunnel.T_DATA, rcvNext, advertisedWnd(),
                                seq++, s.data, s.data.length));
                    }
                    retransmits++;
                    timerStart = now;
                    rtoNs = Math.min(rtoNs * 2, MAX_RTO_NS);
                    if (retransmits > MAX_RETRANSMIT) {
                        closed = true;
                        lock.notifyAll();
                        return;
                    }
                }
                if (finSent && !finAcked && finTries < FIN_TRIES && now - finSentAt >= 250_000_000L) {
                    resend.add(UdpTunnel.buildPacket(UdpTunnel.T_FIN, rcvNext, advertisedWnd(), 0, null, 0));
                    finTries++;
                    finSentAt = now;
                }
            }
            for (byte[] p : resend) {
                sender.send(p, p.length);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        timer.interrupt();
    }
}