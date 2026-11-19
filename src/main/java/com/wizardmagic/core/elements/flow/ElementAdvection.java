package com.wizardmagic.core.elements.flow;

import com.wizardmagic.core.elements.ElementField;
import com.wizardmagic.api.elements.ElementTypes;
import com.wizardmagic.core.elements.ManaBarrier;
import com.wizardmagic.core.elements.fluid.FluidField;
import com.wizardmagic.core.elements.fluid.FluidManager;
import com.wizardmagic.core.elements.fluid.NativeElement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 元素层的被动标量平流（CHANGE §3）。
 *
 * <pre>
 *   c(x, t+dt) = c(x − u·dt, t)      半拉格朗日回溯 + 三线性插值
 * </pre>
 *
 * 速度从流体粗网格（4 格）三线性插值得到。回溯点在本 section 内 clamp，
 * 不做跨 section 采样——边界处元素会轻微堆积，但扩散与回流会把它抹掉。
 *
 * <p>核心 {@link #advectCore} 只依赖「流体场 + 元素场」，不碰 {@code Level}，
 * 所以能离机逐位对拍（见 {@code verify/ElementAdvectCheck}）。</p>
 */
public final class ElementAdvection {

    private static final int MASK = 0x7F;
    private static final int ALL_MASK = 0x7F7F7F7F;
    /** 一个 section 的边长（世界格）。 */
    private static final int SECTION_SPAN = 16;

    private ElementAdvection() {
    }

    /**
     * 对单个 section 做一步平流。
     *
     * @param dt 时间步长（秒）
     * @return 是否有元素被搬运
     */
    public static boolean advect(net.minecraft.server.level.ServerLevel level, BlockPos sectionOrigin,
                                 ElementField field, double dt) {
        long t0 = System.nanoTime();
        FluidField fluid = FluidManager.field(level);
        if (fluid == null) {
            return false;
        }
        boolean changed = advectCore(fluid, sectionOrigin.getX(), sectionOrigin.getY(),
                sectionOrigin.getZ(), field, dt);
        ElementBudget.record("adv", System.nanoTime() - t0);
        return changed;
    }

    /**
     * 平流核心：只依赖流体场与元素场，<b>不碰 Level</b>（离机对拍用的就是它）。
     *
     * <p>默认走原生内核（{@code native/element}，实测 45 ns/格 vs Java 68 ns/格）；
     * 库不可用或 {@code -Dwizardmagic.element.native=0} 时退回 {@link #advectJava}。</p>
     */
    public static boolean advectCore(FluidField fluid, int ox, int oy, int oz,
                                     ElementField field, double dt) {
        if (NativeElement.available()) {
            int rc = NativeElement.advect(fluid, ox, oy, oz, field, dt);
            if (rc >= 0) {
                // 返回码：bit0 = 有改动，bit1 = 走的是无流短路（原生侧自己算的，不重复判断）
                ElementBudget.bump((rc & 2) != 0 ? "adv.short" : "adv.full");
                return (rc & 1) != 0;
            }
            // 参数不对（理论上不会）：这一次退回 Java，别让元素场停摆
        }
        return advectJava(fluid, ox, oy, oz, field, dt);
    }

    /**
     * Java 实现（对照/回退用）。
     *
     * <p><b>无流短路</b>：如果这一节覆盖的粗格里速度全为 0，三线性插值的权重是
     * {@code fx=fy=fz=0}（精确 1/0），采样结果**精确等于**原值，于是平流退化成
     * 「通透度缩放 + base 下限」。</p>
     */
    public static boolean advectJava(FluidField fluid, int ox, int oy, int oz,
                                     ElementField field, double dt) {
        int[] current = field.currentWords();
        int[] scratch = field.scratchWords();
        int[] base = field.baseWords();
        final boolean barrier = field.hasBarrier();

        if (!fluid.hasFlowIn(ox, oy, oz, SECTION_SPAN)) {
            ElementBudget.bump("adv.short");
            return noFlowStep(field, current, base, barrier);
        }
        ElementBudget.bump("adv.full");

        System.arraycopy(current, 0, scratch, 0, ElementField.SIZE);

        float[] vel = new float[3];
        boolean changed = false;

        for (int idx = 0; idx < ElementField.SIZE; idx++) {
            // 魔力屏障：完全阻断的格子平流结果不写回（保持 0，元素不能流过它）
            int perm = barrier ? field.permeability(idx) : ManaBarrier.OPEN;
            if (perm <= ManaBarrier.BLOCKED) {
                continue;
            }
            int word = scratch[idx];
            if ((word & ALL_MASK) == 0) {
                continue; // 全零格无需搬运
            }
            int x = idx & 15;
            int y = (idx >> 4) & 15;
            int z = idx >> 8;

            double wx = ox + x + 0.5;
            double wy = oy + y + 0.5;
            double wz = oz + z + 0.5;
            fluid.sample(wx, wy, wz, vel);

            // 回溯到上一时刻的位置（section 局部坐标，0..15）
            double bx = x - vel[0] * dt;
            double by = y - vel[1] * dt;
            double bz = z - vel[2] * dt;

            int advected = sampleWord(scratch, bx, by, bz);
            // 魔力屏障：部分通透的格子，平流进来的量按比例打折
            if (perm < ManaBarrier.OPEN) {
                advected = scaleWord(advected, perm);
            }
            // 源下限：元素不允许被吹到 base 以下（岩浆/火是持续输出的源，
            // 否则风一吹岩浆格的火就掉到阈值以下、光点直接消失）。
            int baseWord = base[idx];
            if (baseWord != 0) {
                advected = raiseToBase(advected, baseWord);
            }
            if (advected != word) {
                current[idx] = advected;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 速度恒为 0 时的等价快路径。
     *
     * <p>为什么等价：{@code sampleWord(scratch, x, y, z)} 在整数坐标上
     * {@code fx=fy=fz=0}、{@code w00=1} 其余权重全 0，得到 {@code round(word)} = word，
     * 逐通道 clamp 后仍是 word。所以唯一会改变结果的是「通透度缩放」与「base 下限」。</p>
     *
     * <p>注意：全零格<b>跳过</b> base 抬升——这是原路径就有的行为
     * （{@code (word & ALL_MASK) == 0} 就先 continue 了），这里必须保持一致。</p>
     */
    private static boolean noFlowStep(ElementField field, int[] current, int[] base,
                                      boolean barrier) {
        boolean changed = false;
        for (int idx = 0; idx < ElementField.SIZE; idx++) {
            int perm = barrier ? field.permeability(idx) : ManaBarrier.OPEN;
            if (perm <= ManaBarrier.BLOCKED) {
                continue;
            }
            int word = current[idx];
            if ((word & ALL_MASK) == 0) {
                continue;
            }
            int advected = perm < ManaBarrier.OPEN ? scaleWord(word, perm) : word;
            int baseWord = base[idx];
            if (baseWord != 0) {
                advected = raiseToBase(advected, baseWord);
            }
            if (advected != word) {
                current[idx] = advected;
                changed = true;
            }
        }
        return changed;
    }

    /** 每通道取 {@code max(word, baseWord)}（逐通道，7 位）。 */
    private static int raiseToBase(int word, int baseWord) {
        int out = word;
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            int shift = t * 8;
            int v = (word >>> shift) & MASK;
            int bv = (baseWord >>> shift) & MASK;
            if (v < bv) {
                out = (out & ~(MASK << shift)) | (bv << shift);
            }
        }
        return out;
    }

    /** 按通透度缩放一个打包 int 的四个元素（0..127）。 */
    private static int scaleWord(int word, int perm) {
        int out = 0;
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            int shift = t * 8;
            int v = (word >>> shift) & MASK;
            int scaled = v * perm / ManaBarrier.OPEN;
            out |= (scaled & MASK) << shift;
        }
        return out;
    }

    /** 三线性采样（4 元素分别插值后重新打包）。 */
    private static int sampleWord(int[] words, double x, double y, double z) {
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
}
