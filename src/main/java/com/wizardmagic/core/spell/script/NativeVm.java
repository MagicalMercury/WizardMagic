package com.wizardmagic.core.spell.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 原生内核（C++/SIMD）的 JNI 桥。
 *
 * <p><b>设计</b>：只有"加载库 + 传数组"两件事，不做任何业务判断。找不到库、架构不对、
 * 版本对不上，一律返回 {@link #available()} = false，调用方回退到 Java 的 {@link RegVm}。
 * 所以<strong>不装原生库也能玩</strong>，只是慢。</p>
 *
 * <p><b>数组方向</b>：粒子的 SoA 数组用 {@code GetPrimitiveArrayCritical} 钉住，零拷贝；
 * 出参（px/py/pz/vx/vy/vz/energy/fv）由 native 原地改，入参（anchor/axis/target/rnd/hv）只读。</p>
 *
 * <p><b>线程</b>：内核不碰 Env、不回调、状态全在栈上，所以服务端线程和客户端渲染线程
 * 可以并发调用同一个 program（它们各跑各自的粒子区间）。</p>
 */
public final class NativeVm
{
    private static final String LIB_RESOURCE =
            "/wizardmagic/native/windows/x86_64/wmvm.dll";

    /** 加载失败的原因（人话），成功后为 null */
    private static final String LOAD_ERROR;
    /** 诊断/自检用的强制开关；null = 跟随系统属性 */
    private static volatile Boolean FORCED;
    private static final boolean LOADED;
    private static final String TARGET;
    private static final int LANES;

    static
    {
        String error = null;
        boolean loaded = false;
        String target = "java";
        int lanes = 0;
        try
        {
            load();
            if (nativeInit() != 0)
            {
                error = "wmvm_init 返回非 0";
            }
            else if (nativeSetSinTable(com.wizardmagic.api.spell.SpellMath.sinTableSnapshot()) != 0)
            {
                error = "sin 表灌入失败";
            }
            else
            {
                target = nativeTarget();
                lanes = nativeLanes();
                // A/B 用：-Dwizardmagic.spell.native.lanes=1|4|8（0/缺省 = 按 CPU 自动）
                final int forced = Integer.getInteger("wizardmagic.spell.native.lanes", 0);
                if (forced > 0)
                {
                    final int actual = nativeForceLanes(forced);
                    if (actual > 0)
                    {
                        lanes = actual;
                    }
                }
                loaded = true;
            }
        }
        catch (UnsatisfiedLinkError | IOException | RuntimeException e)
        {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
        TARGET = target;
        LANES = lanes;
    }

    private NativeVm()
    {
    }

    /**
     * 能不能用原生内核。
     *
     * <p>除了"库加载成功"，还要求启动自检通过 —— 由调用方（{@code SpellScripts.warmUp}）
     * 跑一次指纹对拍后决定是否真的启用；这里只回答"库在不在"。</p>
     */
    public static boolean available()
    {
        return LOADED;
    }

    /** 诊断/自检：强制开关（null = 跟随 {@code -Dwizardmagic.spell.native}，默认开） */
    public static void setEnabled(Boolean on)
    {
        FORCED = on;
    }

    /**
     * 这一段要不要走原生后端。
     *
     * <p>三道闸：库加载成功、没有被 {@code -Dwizardmagic.spell.native=0} 关掉、
     * 没有在自检里被强制关掉（指纹对不上时 {@code SpellScripts.warmUp} 会这么做）。</p>
     */
    public static boolean enabled()
    {
        if (FORCED != null)
        {
            return FORCED && LOADED;
        }
        return LOADED && !"0".equals(System.getProperty("wizardmagic.spell.native", "1"));
    }

    public static String loadError()
    {
        return LOAD_ERROR;
    }

    /** 运行期选中的目标名（"AVX2" 等） */
    public static String target()
    {
        return TARGET;
    }

    /** 自动选中的 lane 数（1/4/8）；不可用时 0 */
    public static int lanes()
    {
        return LANES;
    }

    /** 强制 lane 数（0 = 自动），返回实际值；不可用或该目标不支持时返回 0 */
    public static int forceLanes(int lanes)
    {
        return LOADED ? nativeForceLanes(lanes) : 0;
    }

    /** 注册一段指令流（不可变），返回句柄；0 = 失败 */
    public static long create(int[] code, double[] consts)
    {
        return LOADED ? nativeCreate(code, consts) : 0L;
    }

    public static void destroy(long handle)
    {
        if (LOADED && handle != 0L)
        {
            nativeDestroy(handle);
        }
    }

    /**
     * 跑一段。返回 {@code >= 0} 是消耗到的 rand 序号，{@code < 0} 是
     * {@code -(ip * 8 + kind)}（kind 见 {@code wmvm.h}）。
     */
    public static int run(long handle, int from, int to,
            double[] px, double[] py, double[] pz,
            double[] vx, double[] vy, double[] vz,
            float[] energy,
            double[] anchorDx, double[] anchorDy, double[] anchorDz,
            int shapeKind, int shapeCount, double[] shapePrep,
            double sRadius, double sLength, double sHeight, double sTurns,
            double sFrequency, double sPulse, double sWave, double sSpeed,
            int tilted, double[] tilt,
            double originX, double originY, double originZ,
            long seed, int ageStep, double time, int randSeq,
            double[] fv, double[] hv)
    {
        return nativeRun(handle, from, to,
                px, py, pz, vx, vy, vz, energy,
                anchorDx, anchorDy, anchorDz,
                shapeKind, shapeCount, shapePrep,
                sRadius, sLength, sHeight, sTurns, sFrequency, sPulse, sWave, sSpeed,
                tilted, tilt,
                originX, originY, originZ, seed, ageStep, time, randSeq, fv, hv);
    }

    /** 最近一次失败的说明 */
    public static String lastError()
    {
        return LOADED ? nativeLastError() : String.valueOf(LOAD_ERROR);
    }

    // ---------------------------------------------------------------- 加载

    private static void load() throws IOException
    {
        // 1) 开发/自检：-Dwizardmagic.native.library=<绝对路径>
        // 2) 发布：从 jar 里抽到临时目录再 System.load
        String explicit = System.getProperty("wizardmagic.native.library");
        if (explicit != null && !explicit.isEmpty())
        {
            System.load(Path.of(explicit).toAbsolutePath().toString());
            return;
        }
        try (InputStream in = NativeVm.class.getResourceAsStream(LIB_RESOURCE))
        {
            if (in == null)
            {
                throw new IOException("jar 里没有 " + LIB_RESOURCE);
            }
            Path dir = Files.createTempDirectory("wizardmagic-native");
            Path dll = dir.resolve("wmvm.dll");
            Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
            dll.toFile().deleteOnExit();
            System.load(dll.toAbsolutePath().toString());
        }
    }

    /** 诊断串：给启动日志与 /spell debug stats 用 */
    public static String describe()
    {
        return LOADED
                ? String.format(Locale.ROOT, "native/%s/%dlane", TARGET, LANES)
                : "java（原生不可用：" + LOAD_ERROR + "）";
    }

    // ---------------------------------------------------------------- native

    private static native int nativeInit();

    private static native String nativeTarget();

    private static native int nativeLanes();

    private static native int nativeForceLanes(int lanes);

    private static native int nativeSetSinTable(float[] table);

    private static native long nativeCreate(int[] code, double[] consts);

    private static native void nativeDestroy(long handle);

    private static native int nativeRun(long handle, int from, int to,
            double[] px, double[] py, double[] pz,
            double[] vx, double[] vy, double[] vz,
            float[] energy,
            double[] anchorDx, double[] anchorDy, double[] anchorDz,
            int shapeKind, int shapeCount, double[] shapePrep,
            double sRadius, double sLength, double sHeight, double sTurns,
            double sFrequency, double sPulse, double sWave, double sSpeed,
            int tilted, double[] tilt,
            double originX, double originY, double originZ,
            long seed, int ageStep, double time, int randSeq,
            double[] fv, double[] hv);

    private static native String nativeLastError();
}
