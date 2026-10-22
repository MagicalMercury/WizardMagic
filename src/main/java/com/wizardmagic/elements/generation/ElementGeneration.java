package com.wizardmagic.elements.generation;

import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementFieldStorage;
import com.wizardmagic.elements.ElementTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 元素场生成入口：噪声 + 群系 + 高度 + 方块卷积 → 一次插值写入 base。
 *
 * <p>优化要点（CHANGE.md P0-1 / P0-2）：</p>
 * <ul>
 *   <li>噪声与卷积都在 4×4×4 粗格上计算，先相加再插值（插值是线性的，数学等价），
 *       避免对 16³ 扫两遍；</li>
 *   <li>分离式插值：x → y → z 三遍，权重与索引预计算成表，消除逐格 {@code Math.floor}
 *       和除法，lerp 次数从 28.7K 降到 5.4K；</li>
 *   <li>粗格与中间缓冲全部复用，不每 section 分配。</li>
 * </ul>
 *
 * <p>必须在主线程调用（卷积要读方块）。</p>
 */
public final class ElementGeneration
{
    private static final int OCTAVES = 3;
    private static final double NOISE_AMPLITUDE = 35.0;
    private static final long ELEMENT_SALT = 0x51ED2701L;
    private static final double SEA_LEVEL = 63.0;

    /** 粗网格边长 */
    static final int COARSE = 4;
    /** 每个粗格覆盖的方块数 */
    static final int CELL = 16 / COARSE;

    private static final ElementTypes[] TYPES = ElementTypes.values();
    private static final int COUNT = ElementTypes.COUNT;

    // ---------------------------------------------------- 分离式插值表（4 -> 16）
    private static final int[] IDX0 = new int[16];
    private static final int[] IDX1 = new int[16];
    private static final double[] WEIGHT = new double[16];

    static {
        for (int i = 0; i < 16; i++) {
            double g = i / (double) CELL;
            int i0 = (int) Math.floor(g);
            i0 = Math.min(i0, COARSE - 1);
            int i1 = Math.min(i0 + 1, COARSE - 1);
            IDX0[i] = i0;
            IDX1[i] = i1;
            WEIGHT[i] = g - i0;
        }
    }

    // ------------------------------------------------------------- 复用缓冲
    /** 粗格累计值 [x][y][z][t] */
    private static final double[][][][] COARSE_BUF = new double[COARSE][COARSE][COARSE][COUNT];
    /** x 方向插值结果 [x*64 + y*16 + z*4 + t] */
    private static final double[] X_BUF = new double[16 * COARSE * COARSE * COUNT];
    /** y 方向插值结果 [x*256 + y*16 + z*4 + t] */
    private static final double[] Y_BUF = new double[16 * 16 * COARSE * COUNT];
    /** 局部刷新偏差 */
    private static final byte[] REFRESH_SCRATCH = new byte[COUNT * ElementField.SIZE];

    // ------------------------------------------------------------- 计时统计
    private static final int TIMING_WINDOW = 64;
    /** 当前窗口累计纳秒：0=noise/biome 1=convolution 2=interpolate 3=hot-sources */
    private static final long[] TIMING_SUM = new long[4];
    /** 上一个完整窗口的累计值 */
    private static final long[] TIMING_LAST = new long[4];
    private static int timingSamples;
    private static int lastSamples;

    private ElementGeneration() {
    }

    /** 生成一个 section；空气 section 也会生成（只是跳过方块卷积），保证高空也能读元素 */
    public static boolean generateSection(Level level, LevelChunk chunk, ElementFieldStorage storage, int sectionIndex) {
        ElementField field = storage.getOrCreate(sectionIndex);
        rebuildBase(chunk, sectionIndex, field);
        // 生成：current 直接对齐 base，处于稳态
        field.resetToBase();
        field.setGenerated(true);
        field.setDirty(false);
        return true;
    }

    /**
     * 方块变化后的局部刷新：重算 base，但保留玩家对 current 的扰动偏差。
     */
    public static void refreshBase(LevelChunk chunk, int sectionIndex, ElementField field) {
        for (int t = 0; t < COUNT; t++) {
            ElementTypes type = TYPES[t];
            for (int i = 0; i < ElementField.SIZE; i++) {
                int delta = field.get(type, i) - field.getBase(type, i);
                REFRESH_SCRATCH[t * ElementField.SIZE + i] =
                        (byte) (delta < -128 ? -128 : (delta > 127 ? 127 : delta));
            }
        }

        rebuildBase(chunk, sectionIndex, field);

        for (int t = 0; t < COUNT; t++) {
            ElementTypes type = TYPES[t];
            for (int i = 0; i < ElementField.SIZE; i++) {
                field.setCurrent(type, i, field.getBase(type, i) + REFRESH_SCRATCH[t * ElementField.SIZE + i]);
            }
        }
        // base 变了，current 需要重新向新 base 收敛
        field.setDirty(true);
        field.setLastStepTick(0L);
    }

    /** 只写 base：噪声 + 群系 + 高度 + 卷积（粗格合并）→ 一次插值 → 强源逐格 */
    private static void rebuildBase(LevelChunk chunk, int sectionIndex, ElementField field) {
        LevelChunkSection section = chunk.getSection(sectionIndex);
        long t0 = System.nanoTime();

        double[][][][] coarse = COARSE_BUF;
        fillCoarse(chunk, sectionIndex, coarse);
        long t1 = System.nanoTime();

        boolean hasBlocks = section != null && !section.hasOnlyAir();
        if (hasBlocks) {
            ElementConvolution.addToCoarse(section, coarse);
        }
        long t2 = System.nanoTime();

        interpolate(coarse, field);
        long t3 = System.nanoTime();

        if (hasBlocks) {
            BlockElementInfluence.applyHotSources(section, field);
        }
        long t4 = System.nanoTime();

        recordTiming(t1 - t0, t2 - t1, t3 - t2, t4 - t3);
    }

    private static void recordTiming(long noise, long conv, long interp, long hot) {
        TIMING_SUM[0] += noise;
        TIMING_SUM[1] += conv;
        TIMING_SUM[2] += interp;
        TIMING_SUM[3] += hot;
        if (++timingSamples >= TIMING_WINDOW) {
            System.arraycopy(TIMING_SUM, 0, TIMING_LAST, 0, 4);
            lastSamples = timingSamples;
            for (int i = 0; i < 4; i++) {
                TIMING_SUM[i] = 0;
            }
            timingSamples = 0;
        }
    }

    /** 最近窗口的平均耗时（毫秒），顺序: noise, conv, interp, hot */
    public static double[] averageTiming() {
        double[] out = new double[4];
        int n = lastSamples > 0 ? lastSamples : timingSamples;
        if (n <= 0) {
            return out;
        }
        long[] source = lastSamples > 0 ? TIMING_LAST : TIMING_SUM;
        for (int i = 0; i < 4; i++) {
            out[i] = source[i] / 1_000_000.0 / n;
        }
        return out;
    }

    /** 粗格：biome 基础值 + 噪声 + 高度风 */
    private static void fillCoarse(LevelChunk chunk, int sectionIndex, double[][][][] coarse) {
        long worldSeed = 0L;
        if (chunk.getLevel() instanceof ServerLevel serverLevel) {
            worldSeed = serverLevel.getSeed();
        }

        int baseY = chunk.getMinBuildHeight() + (sectionIndex << 4);
        int chunkX = chunk.getPos().getMinBlockX();
        int chunkZ = chunk.getPos().getMinBlockZ();

        for (int gx = 0; gx < COARSE; gx++) {
            for (int gy = 0; gy < COARSE; gy++) {
                for (int gz = 0; gz < COARSE; gz++) {
                    int sampleX = chunkX + gx * CELL + CELL / 2;
                    int sampleY = baseY + gy * CELL + CELL / 2;
                    int sampleZ = chunkZ + gz * CELL + CELL / 2;
                    int worldY = baseY + gy * CELL + CELL / 2;

                    int[] biomeBase = BiomeElementTable.base(
                            chunk.getNoiseBiome(sampleX >> 2, sampleY >> 2, sampleZ >> 2));
                    double windBonus = Math.max(0.0, worldY - SEA_LEVEL) * 0.5;

                    double[] cell = coarse[gx][gy][gz];
                    for (int t = 0; t < COUNT; t++) {
                        double n = ElementNoise.fbm(
                                worldSeed + ELEMENT_SALT + t * 7919L,
                                sampleX, sampleY, sampleZ, OCTAVES);
                        double value = biomeBase[t] + n * NOISE_AMPLITUDE;
                        if (t == ElementTypes.Wind.ordinal()) {
                            value += windBonus;
                        }
                        cell[t] = value;
                    }
                }
            }
        }
    }

    /**
     * 分离式插值：x（4→16）→ y（4→16）→ z（4→16），结果四舍五入后写 base。
     */
    private static void interpolate(double[][][][] coarse, ElementField field) {
        final double[] xBuf = X_BUF;
        final double[] yBuf = Y_BUF;

        // 1) 沿 x：4 → 16  索引: (x*COARSE + y)*COARSE*COUNT + z*COUNT + t
        for (int y = 0; y < COARSE; y++) {
            for (int z = 0; z < COARSE; z++) {
                for (int t = 0; t < COUNT; t++) {
                    for (int x = 0; x < 16; x++) {
                        int i0 = IDX0[x];
                        int i1 = IDX1[x];
                        double w = WEIGHT[x];
                        double a = coarse[i0][y][z][t];
                        double b = coarse[i1][y][z][t];
                        xBuf[((x * COARSE + y) * COARSE + z) * COUNT + t] = a + (b - a) * w;
                    }
                }
            }
        }

        // 2) 沿 y：4 → 16  索引: (x*16 + y)*COARSE*COUNT + z*COUNT + t
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < COARSE; z++) {
                for (int t = 0; t < COUNT; t++) {
                    for (int y = 0; y < 16; y++) {
                        int j0 = IDX0[y];
                        int j1 = IDX1[y];
                        double w = WEIGHT[y];
                        double a = xBuf[((x * COARSE + j0) * COARSE + z) * COUNT + t];
                        double b = xBuf[((x * COARSE + j1) * COARSE + z) * COUNT + t];
                        yBuf[((x * 16 + y) * COARSE + z) * COUNT + t] = a + (b - a) * w;
                    }
                }
            }
        }

        // 3) 沿 z：4 → 16，直接写 base
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int t = 0; t < COUNT; t++) {
                    ElementTypes type = TYPES[t];
                    int rowBase = ((x * 16 + y) * COARSE) * COUNT + t;
                    for (int z = 0; z < 16; z++) {
                        int k0 = IDX0[z];
                        int k1 = IDX1[z];
                        double w = WEIGHT[z];
                        double a = yBuf[rowBase + k0 * COUNT];
                        double b = yBuf[rowBase + k1 * COUNT];
                        int value = (int) Math.round(a + (b - a) * w);
                        field.setBase(type, ElementField.index(x, y, z), value);
                    }
                }
            }
        }
    }
}
