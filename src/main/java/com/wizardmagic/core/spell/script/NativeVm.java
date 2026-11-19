package com.wizardmagic.core.spell.script;

import com.wizardmagic.core.jni.NativeLibrary;

/**
 * 法术寄存器机原生内核的 JNI 桥。
 *
 * <p><b>设计</b>：只做"传数组、收返回码"，不做任何业务判断。库的加载、目标/lane 查询、
 * 最后的错误串都在中立的 {@link NativeLibrary} 里（一个 JVM 只能 {@code System.load} 一次，
 * 元素场也共用这份 DLL）。库不在就 {@link #available()} = false，调用方回退到 Java 的
 * {@link RegVm}——<strong>不装原生库也能玩</strong>，只是慢。</p>
 *
 * <p><b>数组方向</b>：粒子的 SoA 数组用 {@code GetPrimitiveArrayCritical} 钉住，零拷贝；
 * 出参（px/py/pz/vx/vy/vz/energy/fv）由 native 原地改，入参（anchor/axis/target/rnd/hv）只读。</p>
 *
 * <p><b>线程</b>：内核不碰 Env、不回调、状态全在栈上，所以服务端线程和客户端渲染线程
 * 可以并发调用同一个 program（它们各跑各自的粒子区间）。</p>
 */
public final class NativeVm
{
    /** 加载失败的原因（人话），成功后为 null */
    private static final String LOAD_ERROR;
    /** 诊断/自检用的强制开关；null = 跟随系统属性 */
    private static volatile Boolean FORCED;
    private static final boolean LOADED;

    static
    {
        String error = null;
        boolean loaded = false;
        if (!NativeLibrary.available())
        {
            error = NativeLibrary.loadError();
        }
        else if (nativeSetSinTable(com.wizardmagic.api.spell.SpellMath.sinTableSnapshot()) != 0)
        {
            error = "sin 表灌入失败";
        }
        else
        {
            loaded = true;
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private NativeVm()
    {
    }

    /**
     * 能不能用原生内核。
     *
     * <p>除了"库加载成功"，还要求启动自检通过 —— 由调用方（{@code SpellScripts.warmUp}）
     * 跑一次指纹对拍后决定是否真的启用；这里只回答"库在不在 + sin 表灌进去了没有"。</p>
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

    /** 运行期选中的 Highway 目标名（"AVX2" 等） */
    public static String target()
    {
        return NativeLibrary.target();
    }

    /** 自动选中的 lane 数（1/4/8）；不可用时 0 */
    public static int lanes()
    {
        return LOADED ? NativeLibrary.lanes() : 0;
    }

    /** 强制 lane 数（0 = 自动），返回实际值；不可用或该目标不支持时返回 0 */
    public static int forceLanes(int lanes)
    {
        return LOADED ? NativeLibrary.forceLanes(lanes) : 0;
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
        return LOADED ? NativeLibrary.lastError() : String.valueOf(LOAD_ERROR);
    }

    /** 诊断串：给启动日志与 /spell debug stats 用 */
    public static String describe()
    {
        return LOADED ? NativeLibrary.describe()
                : "java（原生不可用：" + LOAD_ERROR + "）";
    }

    // ---------------------------------------------------------------- native（VM 专用）

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
}
