package com.wizardmagic.elements.generation;

import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementTypes;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 强源的逐格指数衰减贡献。
 *
 * <pre>
 * 增量 = 强度 · exp(-距离 / (半径 / 3))     距离 ≤ 半径，之外为 0
 * </pre>
 *
 * <p>半径：岩浆直径 3（半径 1）、火直径 5（半径 2）、其余半径 6。</p>
 *
 * <p>优化要点（CHANGE.md P0-4）：</p>
 * <ul>
 *   <li>先用 {@code section.maybeHas()} 判断是否存在强源，地下纯石 section 直接返回；</li>
 *   <li>衰减值按距离平方预计算成表，内层不再做 {@code sqrt + exp}；</li>
 *   <li>球体偏移预计算并打包成 int 数组。</li>
 * </ul>
 */
public final class BlockElementInfluence
{
    /** 每 section 最多处理多少个强源 */
    private static final int MAX_SOURCES = 256;

    /** 三种半径：岩浆 1、火 2、其余 6 */
    private static final int[] RADII = {1, 2, 6};
    private static final double[] FALLOFF = new double[RADII.length];
    /** 每个半径的衰减表：distSq -> factor */
    private static final double[][] FACTOR = new double[RADII.length][];
    /** 每个半径的球体偏移（打包: (dx+R)<<8 | (dy+R)<<4 | (dz+R)） */
    private static final int[][] OFFSETS = new int[RADII.length][];

    static {
        for (int r = 0; r < RADII.length; r++) {
            int radius = RADII[r];
            FALLOFF[r] = radius / 3.0;
            FACTOR[r] = new double[radius * radius + 1];
            for (int distSq = 0; distSq <= radius * radius; distSq++) {
                FACTOR[r][distSq] = Math.exp(-Math.sqrt(distSq) / FALLOFF[r]);
            }
            int[] packed = new int[0];
            int count = 0;
            int[] tmp = new int[(2 * radius + 1) * (2 * radius + 1) * (2 * radius + 1)];
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx * dx + dy * dy + dz * dz > radius * radius) {
                            continue;
                        }
                        tmp[count++] = ((dx + 6) << 8) | ((dy + 6) << 4) | (dz + 6);
                    }
                }
            }
            packed = new int[count];
            System.arraycopy(tmp, 0, packed, 0, count);
            OFFSETS[r] = packed;
        }
    }

    private BlockElementInfluence()
    {
    }

    public static void applyHotSources(LevelChunkSection section, ElementField field) {
        if (section == null || section.hasOnlyAir()) {
            return;
        }
        // 快速排除：没有强源就没有工作
        if (!section.maybeHas(BlockElementInfluence::isHotSource)) {
            return;
        }

        int sources = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int code = hotSourceCode(section.getBlockState(x, y, z));
                    if (code == 0) {
                        continue;
                    }
                    if (++sources > MAX_SOURCES) {
                        return;
                    }
                    int strength = code >>> 8;
                    int radiusIndex = code & 0xFF;
                    double[] factor = FACTOR[radiusIndex];
                    int[] offsets = OFFSETS[radiusIndex];

                    for (int packed : offsets) {
                        int dx = ((packed >> 8) & 0xF) - 6;
                        int dy = ((packed >> 4) & 0xF) - 6;
                        int dz = (packed & 0xF) - 6;
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
                            continue;
                        }
                        int add = (int) Math.round(strength * factor[dx * dx + dy * dy + dz * dz]);
                        if (add != 0) {
                            field.addBase(ElementTypes.Fire, ElementField.index(nx, ny, nz), add);
                        }
                    }
                }
            }
        }
    }

    private static boolean isHotSource(BlockState state) {
        return hotSourceCode(state) != 0;
    }

    /** 返回 (强度 << 8) | 半径索引；0 表示不是强源 */
    private static int hotSourceCode(BlockState state) {
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            return (30 << 8) | 1;
        }
        if (state.is(Blocks.LAVA)) {
            return (80 << 8) | 0;
        }
        if (state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT)) {
            return (22 << 8) | 2;
        }
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            return (18 << 8) | 2;
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return (14 << 8) | 2;
        }
        return 0;
    }
}
