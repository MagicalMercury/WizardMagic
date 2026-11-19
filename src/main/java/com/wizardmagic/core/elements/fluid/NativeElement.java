package com.wizardmagic.core.elements.fluid;

import com.wizardmagic.core.elements.ElementField;
import com.wizardmagic.core.jni.NativeLibrary;

/**
 * 元素场原生内核的 Java 门面（实现在 {@code src/main/native/element}）。
 *
 * <p><b>加载交给 {@link NativeLibrary}</b>：一个 JVM 里同一份 DLL 只能 {@code System.load}
 * 一次，加载者必须唯一；{@code NativeLibrary} 就是这个唯一的人。这里只读一次它的
 * {@code available()} 触发初始化，绝不自己 load（自己和法术 VM 各 load 一次会抛
 * {@code UnsatisfiedLinkError: already loaded}）。</p>
 *
 * <p>开关：{@code -Dwizardmagic.element.native=0} 强制走 Java 实现（A/B 与保险用）。</p>
 */
public final class NativeElement {

    /** 关掉原生平流的系统属性值 */
    private static final boolean ENABLED =
            !"0".equals(System.getProperty("wizardmagic.element.native"));

    /** 触发 NativeLibrary 的静态初始化（= 加载 native.dll），并记住成没成 */
    private static final boolean LOADED = ENABLED && NativeLibrary.available();

    private NativeElement() {
    }

    /** 原生平流可用吗（库加载成功 + 没被属性关掉） */
    public static boolean available() {
        return LOADED;
    }

    /**
     * 跑一个 section 的平流。
     *
     * @param dt 时间步长（秒），调度器现在传 1.0
     * @return 1 = 有改动，0 = 没变，负数 = 参数错误（调用方应退回 Java）
     */
    public static int advect(FluidField fluid, int ox, int oy, int oz, ElementField field, double dt) {
        return advect(field.currentWords(), field.scratchWords(), field.baseWords(),
                field.blockedBits(), field.permeabilityBytes(), field.hasBarrier() ? 1 : 0,
                fluid.uc, fluid.vc, fluid.wc,
                fluid.originX, fluid.originY, fluid.originZ,
                ox, oy, oz, dt);
    }

    private static native int advect(int[] current, int[] scratch, int[] base,
            long[] blocked, byte[] permeability, int hasBarrier,
            float[] fu, float[] fv, float[] fw,
            int fluidOriginX, int fluidOriginY, int fluidOriginZ,
            int sectionX, int sectionY, int sectionZ, double dt);
}
