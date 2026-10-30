package com.p2plan.iroh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本机 iroh 身份与监听端口的持久化 —— 让「房间码」在 IP 不变时保持稳定。
 *
 * <p><b>为什么需要它</b>：iroh 的 NodeID 由私钥派生。如果每次启动都随机生成密钥，
 * NodeID 每次都变，房间码就每次都变，朋友手里的旧码立刻失效；历史房间码功能也无从谈起。
 * 把 32 字节私钥存到 {@code config/p2plan/iroh-key.bin} 后，NodeID 恒定。
 *
 * <p>端口同理：iroh 监听端口默认随机（{@code :0}），而房间码里带着 {@code IP:端口} 直连地址，
 * 端口一变码就变。固定一个默认端口（并在被占用时回退随机）能让码真正稳定下来。
 *
 * <p>代价与取舍（实测认知）：IP 仍会随网络变化（换 Wi-Fi、重播号），此时码还是会变 ——
 * iroh 这个绑定版本没有内置 discovery（没有 Discovery/Pkarr 类），无法只靠 NodeID 找对端，
 * 所以必须带地址。想让码彻底固定只能长期租用固定 IP 或自建中继。
 *
 * <p>ponytail: 私钥明文存在本地 config 里（等同 MC 的 session 文件），没有做口令保护；
 * 升级路径是接 Windows 凭据管理器（DPAPI）。
 */
public final class IrohIdentity {

    /** 默认监听端口。选 51600：避开 MC 25565、本模组中继 25600、以及常见服务端口。 */
    public static final int DEFAULT_PORT = 51600;

    private static final String KEY_FILE = "iroh-key.bin";
    private static final String PORT_FILE = "iroh-port.txt";

    private final Path dir;
    /** 文件名后缀；同一目录下要放两个身份时用它区分（自检场景）。 */
    private final String namespace;
    private byte[] secretKey;
    private int port;

    private IrohIdentity(Path dir, String namespace) {
        this.dir = dir;
        this.namespace = namespace;
    }

    /**
     * 带命名空间地加载身份。
     *
     * <p>namespace 用于**同一进程内需要两个不同身份**的场景（进程内自检的 host/guest 就是这种，
     * 否则两者读到同一份密钥 → 同一个 NodeID → iroh 拒绝自连）。
     * 真实游戏里两个客户端各有各的 config 目录，都传空串。
     *
     * @param namespace 空表示默认身份；非空则把文件名加后缀，互不干扰
     */
    public static IrohIdentity loadOrCreate(Path configDir, String namespace) {
        IrohIdentity id = new IrohIdentity(configDir, namespace == null ? "" : namespace);
        try {
            Files.createDirectories(configDir);
        } catch (IOException ignored) {
            // 目录建不出来时仍可继续：下面每个操作都会各自兜底
        }
        id.secretKey = id.readKey();
        id.port = id.readPort();
        return id;
    }

    /** 32 字节私钥；首次调用时会生成并落盘。 */
    public synchronized byte[] secretKey() {
        if (secretKey == null) {
            secretKey = IrohEndpoint.generateSecretKey();
            try {
                Files.write(keyPath(), secretKey);
            } catch (IOException ignored) {
                // 写不进去就退化成「本次会话临时身份」，不影响联机
            }
        }
        return secretKey;
    }

    /** 固定监听端口（默认 {@link #DEFAULT_PORT}）。 */
    public int port() {
        return port;
    }

    /** 端口被占用时改用这个临时端口，并记住它，避免每次都撞同一个占用端口。 */
    public synchronized void rememberPort(int actualPort) {
        if (actualPort == port) {
            return;
        }
        port = actualPort;
        try {
            Files.writeString(portPath(), String.valueOf(actualPort), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 「0.0.0.0:端口」形式的绑定地址。 */
    public String bindAddr() {
        return "0.0.0.0:" + port;
    }

    // ------------------------------------------------------------------ 读写

    private byte[] readKey() {
        try {
            Path p = keyPath();
            if (!Files.isRegularFile(p)) {
                return null;
            }
            byte[] b = Files.readAllBytes(p);
            // iroh 私钥必须正好 32 字节；文件坏了就当没有，重新生成
            return b.length == 32 ? b : null;
        } catch (IOException e) {
            return null;
        }
    }

    private int readPort() {
        try {
            Path p = portPath();
            if (Files.isRegularFile(p)) {
                int v = Integer.parseInt(Files.readString(p, StandardCharsets.UTF_8).trim());
                if (v >= 1024 && v <= 65535) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_PORT;
    }

    private Path keyPath() {
        return dir.resolve(namespace.isEmpty() ? KEY_FILE : KEY_FILE + "." + namespace);
    }

    private Path portPath() {
        return dir.resolve(namespace.isEmpty() ? PORT_FILE : PORT_FILE + "." + namespace);
    }
}
