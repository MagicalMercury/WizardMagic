package com.wizardmagic.core.elements.fluid;

import com.wizardmagic.api.elements.ElementTypes;
import com.wizardmagic.core.elements.ElementField;
import com.wizardmagic.core.elements.ManaBarrier;
import com.wizardmagic.core.elements.flow.ElementAdvection;

import java.util.Locale;
import java.util.Random;

/**
 * 元素平流的离机自检：<b>新实现 vs 旧实现逐位对拍</b> + 每格成本基准。
 *
 * <p>为什么要有这个：元素场整个子系统此前<b>零</b>离线自检（2026-11-18 前
 * {@code src/test/java} 里 grep 不到 {@code ElementField}），所以「平流一直用错高度的
 * 速度」这种 bug 能活很久。这里的 {@link #legacyCore} 是改动前
 * {@code ElementAdvection} 的逐行抄件，只用于对拍，不参与生产。</p>
 *
 * <p>放在 {@code fluid} 包里是因为要直接写 {@link FluidField} 的
 * {@code uc/vc/wc} 与 {@code originX/Y/Z}（包内可见），这样才能构造出
 * "有流 / 无流 / 域外" 三类输入。</p>
 */
public final class ElementAdvectCheck {

    private static final int MASK = 0x7F;
    private static final int ALL_MASK = 0x7F7F7F7F;
    private static int checks;
    private static int failures;

    private ElementAdvectCheck() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run() {
        checks = 0;
        failures = 0;
        parity();
        benchmark();
        System.out.printf(Locale.ROOT, "checks=%d failures=%d%n", checks, failures);
        return failures;
    }

    // ------------------------------------------------------------ 对拍

    private static void parity() {
        // case, 速度形态, 有无 barrier, base 形态
        parityCase("无流（短路路径生效）", 0, true, true);
        parityCase("无流 + 无 barrier", 0, false, true);
        parityCase("无流 + 全零元素 + base（全零格不许被抬）", 0, false, false, true);
        parityCase("恒定流", 1, true, true);
        parityCase("随机流（不连续）", 2, true, true);
        parityCase("随机流 + 无 barrier", 2, false, true);
        parityCase("域外（采样被 clamp）", 3, true, true);
        parityCase("域外 + 恒定非零边界速度", 4, true, true);
    }

    private static void parityCase(String name, int flowKind, boolean barrier, boolean withBase) {
        parityCase(name, flowKind, barrier, withBase, false);
    }

    private static void parityCase(String name, int flowKind, boolean barrier, boolean withBase,
                                   boolean allZeroCurrent) {
        final int ox = 100;
        final int oy = -40;
        final int oz = -200;

        ElementField a = fixture(12345L, barrier, withBase, allZeroCurrent);
        ElementField b = fixture(12345L, barrier, withBase, allZeroCurrent);
        ElementField c = fixture(12345L, barrier, withBase, allZeroCurrent);
        FluidField fluid = fluid(flowKind, ox, oy, oz);

        boolean changedOld = legacyCore(fluid, ox, oy, oz, a, 1.0);
        boolean changedJava = ElementAdvection.advectJava(fluid, ox, oy, oz, b, 1.0);
        final boolean hasNative = NativeElement.available();
        boolean changedNative = hasNative && ElementAdvection.advectCore(fluid, ox, oy, oz, c, 1.0);

        int[] wa = a.currentWords();
        int[] wb = b.currentWords();
        int[] wc = c.currentWords();
        check("逐位一致（Java vs 旧实现）：「" + name + "」", sameBits(wa, wb));
        if (hasNative) {
            check("逐位一致（原生 vs 旧实现）：「" + name + "」", sameBits(wa, wc));
            check("changed 标志一致：「" + name + "」（旧=" + changedOld + " Java=" + changedJava
                            + " 原生=" + changedNative + "）",
                    changedOld == changedJava && changedOld == changedNative);
            if (!sameBits(wa, wc)) {
                describeFirstDiff(wa, wc);
            }
        } else {
            check("原生库不可用 → 跳过原生对拍（只验 Java）", true);
        }
    }

    private static boolean sameBits(int[] a, int[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static void describeFirstDiff(int[] old, int[] neu) {
        for (int i = 0; i < Math.min(old.length, neu.length); i++) {
            if (old[i] != neu[i]) {
                System.out.printf(Locale.ROOT,
                        "       first diff idx=%d old=0x%08x native=0x%08x%n",
                        i, old[i], neu[i]);
                return;
            }
        }
    }

    /** 造一份确定的元素场：词、base、通透度都由 seed 决定。 */
    private static ElementField fixture(long seed, boolean barrier, boolean withBase,
                                        boolean allZeroCurrent) {
        Random rnd = new Random(seed);
        ElementField f = new ElementField();
        f.setGenerated(true);
        for (int idx = 0; idx < ElementField.SIZE; idx++) {
            for (ElementTypes t : ElementTypes.values()) {
                int cur = allZeroCurrent ? 0 : rnd.nextInt(128);
                f.setCurrent(t, idx, cur);
                int base = 0;
                if (withBase) {
                    // 一半格子 base 比当前高（会被抬升），一半更低（不动）
                    base = rnd.nextBoolean() ? Math.min(127, cur + rnd.nextInt(20)) : rnd.nextInt(128);
                }
                f.setBase(t, idx, base);
            }
            if (barrier) {
                int roll = rnd.nextInt(100);
                if (roll < 3) {
                    f.setPermeability(idx, ManaBarrier.BLOCKED);
                } else if (roll < 8) {
                    f.setPermeability(idx, 1 + rnd.nextInt(ManaBarrier.OPEN - 1));
                }
            }
        }
        return f;
    }

    /** kind: 0 全零 / 1 恒定 / 2 随机 / 3 域外 / 4 域外但边界非零。 */
    private static FluidField fluid(int kind, int ox, int oy, int oz) {
        FluidField f = new FluidField();
        if (kind == 3 || kind == 4) {
            // 把域放到很远的地方：section 完全在域外，sample 会 clamp 到边界格
            f.originX = ox + 10_000;
            f.originY = oy + 10_000;
            f.originZ = oz + 10_000;
            if (kind == 4) {
                for (int c = 0; c < FluidField.NC; c++) {
                    f.uc[c] = 0.4f;
                    f.vc[c] = -0.3f;
                    f.wc[c] = 0.2f;
                }
            }
            return f;
        }
        f.originX = ox - 8;
        f.originY = oy - 8;
        f.originZ = oz - 8;
        if (kind == 0) {
            return f;   // 全零速度
        }
        Random rnd = new Random(777L);
        for (int k = 0; k < FluidField.N; k++) {
            for (int j = 0; j < FluidField.N; j++) {
                for (int i = 0; i < FluidField.N; i++) {
                    int c = FluidField.c(i, j, k);
                    if (kind == 1) {
                        f.uc[c] = 0.5f;
                        f.vc[c] = 0.25f;
                        f.wc[c] = -0.4f;
                    } else {
                        f.uc[c] = (rnd.nextFloat() - 0.5f) * 1.6f;
                        f.vc[c] = (rnd.nextFloat() - 0.5f) * 1.6f;
                        f.wc[c] = (rnd.nextFloat() - 0.5f) * 1.6f;
                    }
                }
            }
        }
        return f;
    }

    // ------------------------------------------------------------ 基准

    private static void benchmark() {
        final int ox = 100;
        final int oy = -40;
        final int oz = -200;
        ElementField field = fixture(999L, false, true, false);
        FluidField noFlow = fluid(0, ox, oy, oz);
        FluidField flow = fluid(1, ox, oy, oz);
        final boolean hasNative = NativeElement.available();

        double nsLegacy = timePerCell(() -> legacyCore(flow, ox, oy, oz, field, 1.0), 200);
        double nsJava = timePerCell(() -> ElementAdvection.advectJava(flow, ox, oy, oz, field, 1.0), 200);
        double nsJavaNoFlow = timePerCell(() -> ElementAdvection.advectJava(noFlow, ox, oy, oz, field, 1.0), 200);
        double nsNative = hasNative
                ? timePerCell(() -> ElementAdvection.advectCore(flow, ox, oy, oz, field, 1.0), 200) : 0.0;
        double nsNativeNoFlow = hasNative
                ? timePerCell(() -> ElementAdvection.advectCore(noFlow, ox, oy, oz, field, 1.0), 200) : 0.0;

        System.out.printf(Locale.ROOT, "[bench] 平流 ns/格（有流）：旧=%.2f Java=%.2f 原生=%.2f%n",
                nsLegacy, nsJava, nsNative);
        System.out.printf(Locale.ROOT, "[bench] 平流 ns/格（无流）：Java=%.2f 原生=%.2f%n",
                nsJavaNoFlow, nsNativeNoFlow);
        check("基准数字合理（都 > 0）", nsLegacy > 0.0 && nsJava > 0.0 && nsJavaNoFlow > 0.0);
        check("无流短路比有流便宜（Java）", nsJavaNoFlow < nsJava);
        if (hasNative) {
            check("原生比 Java 快（有流）", nsNative < nsJava);
            check("无流短路比有流便宜（原生）", nsNativeNoFlow < nsNative);
        }
    }

    private static double timePerCell(Runnable body, int iters) {
        for (int i = 0; i < 20; i++) {
            body.run();     // 预热
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            body.run();
        }
        long dt = System.nanoTime() - t0;
        return dt / (double) iters / ElementField.SIZE;
    }

    // ------------------------------------------------------------ 旧实现（只用于对拍）

    /** 改动前 {@code ElementAdvection.advect} 的逐行抄件。 */
    private static boolean legacyCore(FluidField fluid, int ox, int oy, int oz,
                                      ElementField field, double dt) {
        int[] current = field.currentWords();
        int[] scratch = field.scratchWords();
        int[] base = field.baseWords();
        System.arraycopy(current, 0, scratch, 0, ElementField.SIZE);

        float[] vel = new float[3];
        boolean changed = false;

        for (int idx = 0; idx < ElementField.SIZE; idx++) {
            int perm = field.permeability(idx);
            if (perm <= ManaBarrier.BLOCKED) {
                continue;
            }
            int word = scratch[idx];
            if ((word & ALL_MASK) == 0) {
                continue;
            }
            int x = idx & 15;
            int y = (idx >> 4) & 15;
            int z = idx >> 8;

            double wx = ox + x + 0.5;
            double wy = oy + y + 0.5;
            double wz = oz + z + 0.5;
            fluid.sample(wx, wy, wz, vel);

            double bx = x - vel[0] * dt;
            double by = y - vel[1] * dt;
            double bz = z - vel[2] * dt;

            int advected = legacySampleWord(scratch, bx, by, bz);
            if (perm < ManaBarrier.OPEN) {
                advected = legacyScaleWord(advected, perm);
            }
            int baseWord = base[idx];
            if (baseWord != 0) {
                for (int t = 0; t < ElementTypes.COUNT; t++) {
                    int shift = t * 8;
                    int v = (advected >>> shift) & MASK;
                    int bv = (baseWord >>> shift) & MASK;
                    if (v < bv) {
                        advected = (advected & ~(MASK << shift)) | (bv << shift);
                    }
                }
            }
            if (advected != word) {
                current[idx] = advected;
                changed = true;
            }
        }
        return changed;
    }

    private static int legacyScaleWord(int word, int perm) {
        int out = 0;
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            int shift = t * 8;
            int v = (word >>> shift) & MASK;
            int scaled = v * perm / ManaBarrier.OPEN;
            out |= (scaled & MASK) << shift;
        }
        return out;
    }

    private static int legacySampleWord(int[] words, double x, double y, double z) {
        double cx = clamp(x, 0.0, 15.0);
        double cy = clamp(y, 0.0, 15.0);
        double cz = clamp(z, 0.0, 15.0);
        int i = (int) cx;
        int j = (int) cy;
        int k = (int) cz;
        if (i > 15) {
            i = 15;
        }
        if (j > 15) {
            j = 15;
        }
        if (k > 15) {
            k = 15;
        }
        double fx = cx - i;
        double fy = cy - j;
        double fz = cz - k;
        int i1 = Math.min(i + 1, 15);
        int j1 = Math.min(j + 1, 15);
        int k1 = Math.min(k + 1, 15);
        double g0 = 1.0 - fx;
        double g1 = 1.0 - fy;
        double g2 = 1.0 - fz;
        double w00 = g0 * g1;
        double w10 = fx * g1;
        double w01 = g0 * fy;
        double w11 = fx * fy;

        int p000 = i + 16 * (j + 16 * k);
        int p100 = i1 + 16 * (j + 16 * k);
        int p010 = i + 16 * (j1 + 16 * k);
        int p110 = i1 + 16 * (j1 + 16 * k);
        int p001 = i + 16 * (j + 16 * k1);
        int p101 = i1 + 16 * (j + 16 * k1);
        int p011 = i + 16 * (j1 + 16 * k1);
        int p111 = i1 + 16 * (j1 + 16 * k1);

        int out = 0;
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            int shift = t * 8;
            double v = ((words[p000] >>> shift) & MASK) * w00
                    + ((words[p100] >>> shift) & MASK) * w10
                    + ((words[p010] >>> shift) & MASK) * w01
                    + ((words[p110] >>> shift) & MASK) * w11;
            double v2 = ((words[p001] >>> shift) & MASK) * w00
                    + ((words[p101] >>> shift) & MASK) * w10
                    + ((words[p011] >>> shift) & MASK) * w01
                    + ((words[p111] >>> shift) & MASK) * w11;
            int value = (int) Math.round(v * g2 + v2 * fz);
            if (value < 0) {
                value = 0;
            } else if (value > 127) {
                value = 127;
            }
            out |= value << shift;
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------ 断言

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("[FAIL] " + what);
        } else {
            System.out.println("[ok]   " + what);
        }
    }
}
