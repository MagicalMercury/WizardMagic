package com.wizardmagic.core.jni;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 原生库（{@code native.dll}）的<b>唯一加载者</b>，以及库级查询（目标 / lane / 最后的错误）。
 *
 * <p><b>为什么必须只有一处加载</b>：一个 JVM 里同一份 DLL 只能 {@code System.load} 一次。
 * 法术 VM（{@code NativeVm}）和元素场（{@code NativeElement}）都活在这同一个 DLL 里，
 * 各加载各的迟早会撞 {@code UnsatisfiedLinkError: already loaded}。所以：<b>谁都不用自己
 * load，读一下 {@link #available()} 就会触发这里唯一的一次初始化。</b></p>
 *
 * <p>这个类刻意<b>不依赖任何业务代码</b>（不 import spell、不 import elements），
 * 只回答"库在不在、什么目标、几条 lane"。</p>
 *
 * <p>加载顺序：{@code -Dwizardmagic.native.library=<绝对路径>}（开发/自检）→
 * jar 里的 {@code /wizardmagic/native/windows/x86_64/native.dll} 抽到临时目录再 load。
 * 任何一步失败都只是 {@link #available()} = false，调用方各自回退到 Java 实现。</p>
 */
public final class NativeLibrary {

    /** jar 里的库路径（Gradle 的 processResources 负责放进去） */
    private static final String LIB_RESOURCE = "/wizardmagic/native/windows/x86_64/native.dll";

    private static final boolean LOADED;
    /** 加载/初始化失败的原因（人话）；成功时为 null */
    private static final String LOAD_ERROR;
    private static final String TARGET;
    private static final int LANES;

    static {
        boolean loaded = false;
        String error = null;
        String target = "java";
        int lanes = 0;
        try {
            load();
            if (nativeInit() != 0) {
                error = "wmvm_init 返回非 0";
            } else {
                target = nativeTarget();
                lanes = nativeLanes();
                // A/B 与自检：-Dwizardmagic.native.lanes=1|4|8（0/缺省 = 按 CPU 自动）
                // 旧名 -Dwizardmagic.spell.native.lanes 仍然认，免得旧运行配置失效
                int forced = Integer.getInteger("wizardmagic.native.lanes",
                        Integer.getInteger("wizardmagic.spell.native.lanes", 0));
                if (forced > 0) {
                    int actual = nativeForceLanes(forced);
                    if (actual > 0) {
                        lanes = actual;
                    }
                }
                loaded = true;
            }
        } catch (UnsatisfiedLinkError | IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
        TARGET = target;
        LANES = lanes;
    }

    private NativeLibrary() {
    }

    /** 库加载 + 初始化成功了吗 */
    public static boolean available() {
        return LOADED;
    }

    /** 加载失败的原因；成功时为 null */
    public static String loadError() {
        return LOAD_ERROR;
    }

    /** 运行期选中的 Highway 目标名（"AVX2" 等）；不可用时 "java" */
    public static String target() {
        return TARGET;
    }

    /** 自动选中的 lane 数（1/4/8）；不可用时 0 */
    public static int lanes() {
        return LANES;
    }

    /** 强制 lane 数（0 = 自动），返回实际值；不可用或该目标不支持时返回 0 */
    public static int forceLanes(int lanes) {
        return LOADED ? nativeForceLanes(lanes) : 0;
    }

    /** 最近一次原生调用失败的原因（线程内） */
    public static String lastError() {
        return LOADED ? nativeLastError() : String.valueOf(LOAD_ERROR);
    }

    /** 诊断串，给启动日志与命令面板用 */
    public static String describe() {
        return LOADED
                ? String.format(Locale.ROOT, "native/%s/%dlane", TARGET, LANES)
                : "java（原生不可用：" + LOAD_ERROR + "）";
    }

    // ---------------------------------------------------------------- 加载

    private static void load() throws IOException {
        String explicit = System.getProperty("wizardmagic.native.library");
        if (explicit != null && !explicit.isEmpty()) {
            System.load(Path.of(explicit).toAbsolutePath().toString());
            return;
        }
        try (InputStream in = NativeLibrary.class.getResourceAsStream(LIB_RESOURCE)) {
            if (in == null) {
                throw new IOException("jar 里没有 " + LIB_RESOURCE);
            }
            Path dir = Files.createTempDirectory("wizardmagic-native");
            Path dll = dir.resolve("native.dll");
            Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
            dll.toFile().deleteOnExit();
            System.load(dll.toAbsolutePath().toString());
        }
    }

    // ---------------------------------------------------------------- native（库级）

    private static native int nativeInit();

    private static native String nativeTarget();

    private static native int nativeLanes();

    private static native int nativeForceLanes(int lanes);

    private static native String nativeLastError();
}
