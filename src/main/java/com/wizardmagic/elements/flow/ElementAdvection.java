package com.wizardmagic.elements.flow;

import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementTypes;
import com.wizardmagic.elements.fluid.FluidField;
import com.wizardmagic.elements.fluid.FluidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 元素层的被动标量平流（CHANGE §3）。
 *
 * <pre>
 *   c(x, t+dt) = c(x − u·dt, t)      半拉格朗日回溯 + 三线性插值
 * </pre>
 *
 * 速度从流体粗网格（4 格）三线性插值得到。回溯点在本 section 内 clamp，
 * 不做跨 section 采样——边界处元素会轻微堆积，但扩散与回流会把它抹掉。
 */
public final class ElementAdvection {

    private static final int MASK = 0x7F;
    private static final int ALL_MASK = 0x7F7F7F7F;

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

        int[] current = field.currentWords();
        int[] scratch = field.scratchWords();
        int[] base = field.baseWords();
        System.arraycopy(current, 0, scratch, 0, ElementField.SIZE);

        float[] vel = new float[3];
        boolean changed = false;
        int ox = sectionOrigin.getX();
        int oy = sectionOrigin.getY();
        int oz = sectionOrigin.getZ();

        for (int idx = 0; idx < ElementField.SIZE; idx++) {
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
            // 源下限：元素不允许被吹到 base 以下（岩浆/火是持续输出的源，
            // 否则风一吹岩浆格的火就掉到阈值以下、光点直接消失）。
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
        com.wizardmagic.elements.flow.ElementBudget.record("adv", System.nanoTime() - t0);
        return changed;
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
