package com.p2plan.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把一条本机 TCP 连接和一条 {@link ReliableStream} 双向对搬（含半关闭与字节计数）。
 *
 * <p>方向约定以「本隧道端点」为准：TCP→隧道 计 tx，隧道→TCP 计 rx（喂给 {@link TunnelStatus}）。
 * 任何一半收到 EOF 都只做半关闭（shutdownOutput），另一半还能继续收——MC 的协议结束时要靠这个。
 *
 * <p>ponytail: 一条隧道只搬一条 TCP 连接（MC 客户端的行为），多连接（多人同时进房）要靠多实例或按连接建流。
 */
final class TcpBridge implements AutoCloseable {

    private final Socket socket;
    private final ReliableStream stream;
    private final UdpTunnel tunnel;
    private final AtomicInteger finished = new AtomicInteger();
    private volatile boolean closed;

    TcpBridge(Socket socket, ReliableStream stream, UdpTunnel tunnel) {
        this.socket = socket;
        this.stream = stream;
        this.tunnel = tunnel;
    }

    void start() {
        Thread up = new Thread(this::pumpTcpToStream, "p2p-tcp-up");
        Thread down = new Thread(this::pumpStreamToTcp, "p2p-tcp-down");
        up.setDaemon(true);
        down.setDaemon(true);
        up.start();
        down.start();
    }

    private void pumpTcpToStream() {
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = socket.getInputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                stream.write(buf, 0, n);
                tunnel.addTx(n);
            }
            stream.closeOutput();
        } catch (IOException e) {
            tunnel.noteBridge("TCP→隧道 结束：" + e.getMessage());
        } finally {
            finish();
        }
    }

    private void pumpStreamToTcp() {
        byte[] buf = new byte[16 * 1024];
        try {
            OutputStream out = socket.getOutputStream();
            int n;
            while ((n = stream.read(buf, 0, buf.length)) >= 0) {
                if (n == 0) {
                    continue;
                }
                out.write(buf, 0, n);
                out.flush();
                tunnel.addRx(n);
            }
            socket.shutdownOutput();
        } catch (IOException e) {
            tunnel.noteBridge("隧道→TCP 结束：" + e.getMessage());
        } finally {
            finish();
        }
    }

    private void finish() {
        if (finished.incrementAndGet() >= 2) {
            close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}