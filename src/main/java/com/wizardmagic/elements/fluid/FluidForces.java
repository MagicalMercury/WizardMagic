package com.wizardmagic.elements.fluid;

import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementTypes;
import com.wizardmagic.elements.flow.ElementBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 把元素浓度梯度变成流体速度的源项。
 *
 * <p><b>性能要点（相对旧版）</b>：旧版每个格点直接调 {@code ElementAccess.get}，
 * 13,824 格 × 3 元素 = 4 万次 {@code hasChunkAt → getChunk → getCapability → HashMap<Integer>}。
 * 现在每个 {@code apply} 周期建一次 {@code Map<Long, ElementField>} 缓存（按 chunk×section），
 * 之后逐格只做一次数组访问。缓存生命周期 = 一次 solve，不跨 tick。
 */
public final class FluidForces {

    /** 浮力强度：每单位 (fire - water) 在竖直面上产生的速度。 */
    private static final float BUOYANCY = 0.05f;
    /** 风推力：每单位 wind 在其水平方向上的推力。 */
    private static final float WIND_FORCE = 0.04f;

    /** 元素采样步长（格）。1 = 每格都采，避免棋盘状力场被投影削掉。 */
    private static final int SAMPLE_STEP = 1;

    /** 最近一次 apply 见到的元素峰值（诊断）。 */
    public static int lastMaxFire;
    public static int lastMaxWater;
    public static int lastMaxWind;

    private FluidForces() {
    }

    public static void apply(ServerLevel level, FluidField f) {
        long t0 = System.nanoTime();
        Map<Long, ElementField> cache = new HashMap<>(64);

        int maxFire = 0;
        int maxWater = 0;
        int maxWind = 0;

        for (int i = 0; i < FluidField.N; i += SAMPLE_STEP)
            for (int j = 0; j < FluidField.N; j += SAMPLE_STEP)
                for (int k = 0; k < FluidField.N; k += SAMPLE_STEP) {
                    double wx = f.originX + (i + 0.5) * FluidField.CELL;
                    double wy = f.originY + (j + 0.5) * FluidField.CELL;
                    double wz = f.originZ + (k + 0.5) * FluidField.CELL;

                    int bx = (int) Math.floor(wx);
                    int by = (int) Math.floor(wy);
                    int bz = (int) Math.floor(wz);

                    ElementField ef = fieldAt(level, bx, by, bz, cache);
                    int fire = 0;
                    int water = 0;
                    int wind = 0;
                    if (ef != null) {
                        int idx = ElementField.index(bx & 15, by & 15, bz & 15);
                        fire = ef.get(ElementTypes.Fire, idx);
                        water = ef.get(ElementTypes.Water, idx);
                        wind = ef.get(ElementTypes.Wind, idx);
                    }
                    if (fire > maxFire) maxFire = fire;
                    if (water > maxWater) maxWater = water;
                    if (wind > maxWind) maxWind = wind;

                    float buoy = (fire - water) * BUOYANCY;
                    f.v[FluidField.vf(i, j, k)] += buoy * FluidField.CELL;

                    if (wind > 0) {
                        float push = wind * WIND_FORCE;
                        f.u[FluidField.uf(i, j, k)] += push;
                        f.w[FluidField.wf(i, j, k)] -= push * 0.25f;
                    }
                }

        lastMaxFire = maxFire;
        lastMaxWater = maxWater;
        lastMaxWind = maxWind;
        ElementBudget.record("fluid.forces", System.nanoTime() - t0);
    }

    /**
     * 按 (chunk, section) 缓存 ElementField。
     * 键 = chunkX(16) | sectionY(8) | chunkZ(16)，一次 apply 内有效。
     */
    private static ElementField fieldAt(ServerLevel level, int x, int y, int z, Map<Long, ElementField> cache) {
        long key = ((long) (x >> 4) << 32) | ((long) (y >> 4) << 16) | (z >> 4 & 0xFFFFL);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        ElementField ef = ElementAccess.field(level, new BlockPos(x, y, z));
        cache.put(key, ef);   // HashMap 允许 null 值，避免重复查询
        return ef;
    }
}
