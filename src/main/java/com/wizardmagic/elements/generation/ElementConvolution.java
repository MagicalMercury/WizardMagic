package com.wizardmagic.elements.generation;

import com.wizardmagic.elements.ElementTypes;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 方块卷积：4×4×4 粗网格统计 → 距离加权平滑 → 直接加到 {@link ElementGeneration} 的粗格矩阵。
 *
 * <p>优化要点（CHANGE.md P0-1 / P0-3）：</p>
 * <ul>
 *   <li>不再单独插值：结果直接累加到粗格，与噪声共用同一次插值；</li>
 *   <li>125 个邻域的 {@code 1/(1+距离)} 预计算成常量表，分母按位置预计算，
 *       消除每格每元素的 {@code sqrt}。</li>
 * </ul>
 *
 * <p>必须在主线程调用（读方块状态）。</p>
 */
public final class ElementConvolution
{
    private static final int COARSE = ElementGeneration.COARSE;
    private static final int CELL = ElementGeneration.CELL;
    private static final int COUNT = ElementTypes.COUNT;

    /** 平滑半径（粗格单位，2 ≈ 影响 8 格方块） */
    private static final int SMOOTH_RADIUS = 2;

    /** 权重表：1/(1+距离)，索引 [dx+2][dy+2][dz+2] */
    private static final double[][][] WEIGHTS = new double[5][5][5];
    /** 每个粗格的归一化分母（越界邻域不计入） */
    private static final double[][][] NORM = new double[COARSE][COARSE][COARSE];

    static {
        for (int dx = -SMOOTH_RADIUS; dx <= SMOOTH_RADIUS; dx++) {
            for (int dy = -SMOOTH_RADIUS; dy <= SMOOTH_RADIUS; dy++) {
                for (int dz = -SMOOTH_RADIUS; dz <= SMOOTH_RADIUS; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    WEIGHTS[dx + SMOOTH_RADIUS][dy + SMOOTH_RADIUS][dz + SMOOTH_RADIUS] = 1.0 / (1.0 + distance);
                }
            }
        }
        for (int x = 0; x < COARSE; x++) {
            for (int y = 0; y < COARSE; y++) {
                for (int z = 0; z < COARSE; z++) {
                    double sum = 0.0;
                    for (int dx = -SMOOTH_RADIUS; dx <= SMOOTH_RADIUS; dx++) {
                        for (int dy = -SMOOTH_RADIUS; dy <= SMOOTH_RADIUS; dy++) {
                            for (int dz = -SMOOTH_RADIUS; dz <= SMOOTH_RADIUS; dz++) {
                                int nx = x + dx;
                                int ny = y + dy;
                                int nz = z + dz;
                                if (nx < 0 || ny < 0 || nz < 0 || nx >= COARSE || ny >= COARSE || nz >= COARSE) {
                                    continue;
                                }
                                sum += WEIGHTS[dx + SMOOTH_RADIUS][dy + SMOOTH_RADIUS][dz + SMOOTH_RADIUS];
                            }
                        }
                    }
                    NORM[x][y][z] = sum == 0.0 ? 1.0 : sum;
                }
            }
        }
    }

    /** 方块采样缓冲（主线程复用） */
    private static final int[][][][] ACC = new int[COARSE][COARSE][COARSE][COUNT];

    private ElementConvolution() {
    }

    /**
     * 把卷积结果累加到粗格矩阵（与噪声相加后统一插值）。
     */
    public static void addToCoarse(LevelChunkSection section, double[][][][] coarse) {
        if (section == null || section.hasOnlyAir()) {
            return;
        }

        // 粗格内按 2 步长采样 8 个点
        final int sampleStep = 2;
        final int volume = (CELL / sampleStep) * (CELL / sampleStep) * (CELL / sampleStep);

        for (int cx = 0; cx < COARSE; cx++) {
            for (int cy = 0; cy < COARSE; cy++) {
                for (int cz = 0; cz < COARSE; cz++) {
                    int[] acc = ACC[cx][cy][cz];
                    acc[0] = 0;
                    acc[1] = 0;
                    acc[2] = 0;
                    acc[3] = 0;
                    for (int dx = 0; dx < CELL; dx += sampleStep) {
                        for (int dy = 0; dy < CELL; dy += sampleStep) {
                            for (int dz = 0; dz < CELL; dz += sampleStep) {
                                int[] contribution = BlockElementWeights.contribution(
                                        section.getBlockState(cx * CELL + dx, cy * CELL + dy, cz * CELL + dz));
                                acc[0] += contribution[0];
                                acc[1] += contribution[1];
                                acc[2] += contribution[2];
                                acc[3] += contribution[3];
                            }
                        }
                    }
                    for (int t = 0; t < COUNT; t++) {
                        acc[t] /= volume;
                    }
                }
            }
        }

        // 距离加权平滑后累加到粗格
        for (int x = 0; x < COARSE; x++) {
            for (int y = 0; y < COARSE; y++) {
                for (int z = 0; z < COARSE; z++) {
                    double norm = NORM[x][y][z];
                    for (int t = 0; t < COUNT; t++) {
                        double sum = 0.0;
                        for (int dx = -SMOOTH_RADIUS; dx <= SMOOTH_RADIUS; dx++) {
                            for (int dy = -SMOOTH_RADIUS; dy <= SMOOTH_RADIUS; dy++) {
                                for (int dz = -SMOOTH_RADIUS; dz <= SMOOTH_RADIUS; dz++) {
                                    int nx = x + dx;
                                    int ny = y + dy;
                                    int nz = z + dz;
                                    if (nx < 0 || ny < 0 || nz < 0 || nx >= COARSE || ny >= COARSE || nz >= COARSE) {
                                        continue;
                                    }
                                    sum += ACC[nx][ny][nz][t]
                                            * WEIGHTS[dx + SMOOTH_RADIUS][dy + SMOOTH_RADIUS][dz + SMOOTH_RADIUS];
                                }
                            }
                        }
                        coarse[x][y][z][t] += sum / norm;
                    }
                }
            }
        }
    }
}
