package com.wizardmagic.elements.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地形掩码：固体格把所在的面通量清零（CHANGE §2.7）。
 *
 * <p>24³ 全量采样 `getBlockState` 太贵（18–53 ms），这里做两件事：
 * <ul>
 *   <li>每格只抽 2×2×2 = 8 个点，>7/8 为固体即判为固体格；</li>
 *   <li>掩码按域原点缓存，域不重建就复用（域跟随每 16 格才移动一次）。</li>
 * </ul>
 */
public final class FluidMask {

    private static final byte[] CACHE = new byte[FluidField.NC];
    private static int cacheX;
    private static int cacheY;
    private static int cacheZ;
    private static boolean valid;

    private FluidMask() {
    }

    /** 重建掩码（域原点变化时才真正扫描）。 */
    public static void update(ServerLevel level, FluidField f) {
        if (valid && cacheX == f.originX && cacheY == f.originY && cacheZ == f.originZ) {
            return;
        }
        long t0 = System.nanoTime();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int n = FluidField.N;
        int cell = FluidField.CELL;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    int solid = 0;
                    for (int dx = 0; dx < 2; dx++)
                        for (int dy = 0; dy < 2; dy++)
                            for (int dz = 0; dz < 2; dz++) {
                                int wx = f.originX + i * cell + dx * (cell / 2);
                                int wy = f.originY + j * cell + dy * (cell / 2);
                                int wz = f.originZ + k * cell + dz * (cell / 2);
                                pos.set(wx, wy, wz);
                                BlockState state = level.getBlockState(pos);
                                if (!state.isAir()) {
                                    solid++;
                                }
                            }
                    CACHE[FluidField.c(i, j, k)] = (byte) (solid >= 7 ? 1 : 0);
                }
        cacheX = f.originX;
        cacheY = f.originY;
        cacheZ = f.originZ;
        valid = true;
        com.wizardmagic.elements.flow.ElementBudget.record("fluid.mask", System.nanoTime() - t0);
    }

    /** 把固体格的六个面通量清零（在背景风写入之后、求解之前调用）。 */
    public static void apply(FluidField f) {
        int n = FluidField.N;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    if (CACHE[FluidField.c(i, j, k)] == 0) {
                        continue;
                    }
                    f.u[FluidField.uf(i, j, k)] = 0f;
                    f.u[FluidField.uf(i + 1, j, k)] = 0f;
                    f.v[FluidField.vf(i, j, k)] = 0f;
                    f.v[FluidField.vf(i, j + 1, k)] = 0f;
                    f.w[FluidField.wf(i, j, k)] = 0f;
                    f.w[FluidField.wf(i, j, k + 1)] = 0f;
                }
    }

    /** 固体格占比（诊断）。 */
    public static float solidFraction() {
        int solid = 0;
        for (byte b : CACHE) {
            if (b != 0) {
                solid++;
            }
        }
        return solid / (float) CACHE.length;
    }

    public static void invalidate() {
        valid = false;
    }
}
