package com.p2plan.iroh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * iroh 原生库（{@code iroh_ffi.dll}）的加载器。
 *
 * <p><b>为什么需要它</b>：iroh 的 Java 绑定通过 JNA 调 Rust 编译出的原生库。官方 jar 把库放在
 * {@code win32-x86-64/iroh_ffi.dll}，由 JNA 自己去 classpath 里找；但在 Minecraft 里这条路不稳：
 * <ul>
 *   <li>Fabric 用 Knot 自定义 ClassLoader，JNA 找不到「文件系统上的 jar 路径」；</li>
 *   <li>{@code java.io.tmpdir} 在沙箱化启动器下可能不可写。</li>
 * </ul>
 * 所以这里把 dll 先解压到游戏目录下一个稳定位置，再用 {@code -Djna.library.path} 告诉 JNA 去哪找。
 * 必须在**第一次碰到任何 iroh 类之前**调用 {@link #prepare()}，否则 JNA 会先失败并缓存错误。
 *
 * <p>ponytail: 只处理 Windows x64（本模组只发这个平台）。升级路径：按 os.name/os.arch 选目录，
 * 并把 jar 里其它平台的原生库一起带回来。
 */
public final class IrohNatives {

    /**
     * dll 在 jar 里的路径。构建时已剔除非 Windows 平台的原生库，只留这一个。
     *
     * <p>ponytail: 不要改成「先 gzip 再放进 jar」—— 外层 mod jar 本身就以 DEFLATE 压缩条目，
     * 内层再压属于双重压缩，实测最终产物大小不变，只会白白多出解压代码。
     */
    private static final String IN_JAR = "/win32-x86-64/iroh_ffi.dll";
    private static final String DLL_NAME = "iroh_ffi.dll";

    private static volatile boolean ready;
    private static volatile String error = "未初始化";
    private static volatile Path nativeDir;

    private IrohNatives() {
    }

    /**
     * 准备原生库：解压 dll 并设置 {@code jna.library.path}。
     *
     * @param gameDir 游戏目录（Minecraft 的运行目录），dll 会放在其下的 {@code p2plan/native/}
     * @return 成功返回 null，失败返回原因
     */
    public static synchronized String prepare(Path gameDir) {
        if (ready) {
            return null;
        }
        try {
            if (!isWindowsX64()) {
                error = "当前平台不是 Windows x64，iroh 原生库不适用";
                return error;
            }
            Path dir = gameDir.resolve("p2plan").resolve("native");
            Files.createDirectories(dir);
            Path dll = dir.resolve(DLL_NAME);
            nativeDir = dir;

            // 每次都重写：能避免「升级 mod 后还是旧 dll」这类极难排查的问题。
            try (InputStream in = IrohNatives.class.getResourceAsStream(IN_JAR)) {
                if (in == null) {
                    error = "jar 里找不到 " + DLL_NAME + " —— 打包时原生库可能被裁掉了";
                    return error;
                }
                Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
            }

            // JNA 通过这个属性找库；用绝对路径，避免受工作目录影响
            System.setProperty("jna.library.path", dir.toAbsolutePath().toString());
            // 我们自带 dll，不让 JNA 去资源里提（它在 MC 的 ClassLoader 下容易失败）
            System.setProperty("jna.nounpack", "false");

            ready = true;
            error = null;
            return null;
        } catch (IOException | SecurityException e) {
            error = "解压原生库失败：" + e;
            return error;
        }
    }

    /** 是否已就绪。 */
    public static boolean isReady() {
        return ready;
    }

    /** 最近一次失败原因（就绪时为 null）。 */
    public static String error() {
        return error;
    }

    /** dll 所在目录（诊断用）。 */
    public static Path nativeDir() {
        return nativeDir;
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean win = os.contains("win");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        return win && x64;
    }
}