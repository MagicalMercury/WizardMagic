package com.wizardmagic.elements.flow;

import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementTypes;

/**
 * 单 section 的扩散 + 源项松弛一步。
 *
 * <pre>
 * E' = E + α · Σ_neighbors (E_j − E) + β · (base − E)
 * </pre>
 *
 * <p>越界（section 边界外）用当前格自己的 {@code base} 当邻居，
 * 等价于「无穷远边界」，避免为了扩散强制加载邻 chunk。</p>
 *
 * <p>存储是「每格一个 int、四元素各 8 bit」（见 {@link ElementField}）：
 * 一次遍历就处理完四个元素，邻居格也只读一次 int，访存次数是原来 byte[4][4096]
 * 布局的 1/4。</p>
 */
public final class ElementDiffusion
{
    private static final int COUNT = ElementTypes.COUNT;
    private static final int MASK = 0x7F;
    private static final int ALL_MASK = 0x7F7F7F7F;

    /** 6 邻域偏移 */
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private ElementDiffusion() {
    }

    /**
     * 通量式扩散（类水流）：每格每 tick 最多向每个邻居转移 {@code maxFlux} 点，
     * 而不是按比例抹平。守恒、有明确前沿，视觉上像水一样一格格推进。
     *
     * @param rate     每次最多转移差值的比例（0..1）
     * @param maxFlux  每方向每 tick 的转移上限（1 = 每 tick 推进 1 点，接近水速）
     * @param beta     回流系数
     */
    public static boolean stepFlux(ElementField field, double rate, int maxFlux, double beta) {
        long t0 = System.nanoTime();
        boolean changed = false;

        int[] current = field.currentWords();
        int[] base = field.baseWords();

        // 定点化：内层不再出现 double，差值 * rate 用 (diff * rateQ) >> 8 近似
        final int rateQ = Math.max(1, (int) Math.round(rate * 256.0));
        final int limit = Math.max(1, maxFlux);
        final int betaQ = Math.max(0, (int) Math.round(beta * 256.0));

        for (int index = 0; index < ElementField.SIZE; index++) {
            int word = current[index];
            if ((word & ALL_MASK) == 0) {
                continue; // 四元素全 0，整格跳过
            }
            int x = index & 15;
            int y = (index >> 4) & 15;
            int z = index >> 8;

            // 6 邻居改为索引算术（±1 / ±16 / ±256），不再走 NEIGHBORS 数组与 xyz 解码
            for (int dir = 0; dir < 6; dir++) {
                int neighborIndex;
                switch (dir) {
                    case 0 -> { if (x == 15) continue; neighborIndex = index + 1; }
                    case 1 -> { if (x == 0) continue; neighborIndex = index - 1; }
                    case 2 -> { if (y == 15) continue; neighborIndex = index + 16; }
                    case 3 -> { if (y == 0) continue; neighborIndex = index - 16; }
                    case 4 -> { if (z == 15) continue; neighborIndex = index + 256; }
                    default -> { if (z == 0) continue; neighborIndex = index - 256; }
                }

                int neighborWord = current[neighborIndex];

                for (int t = 0; t < COUNT; t++) {
                    int shift = t * 8;
                    int from = (word >>> shift) & MASK;
                    if (from <= 0) {
                        continue;
                    }
                    int neighborValue = (neighborWord >>> shift) & MASK;
                    int diff = from - neighborValue;
                    if (diff <= 0) {
                        continue;
                    }

                    // 提取比例后不足 1 点就不再扩散（等价于旧的 raw < 1.0）
                    int move = (diff * rateQ) >> 8;
                    if (move <= 0) {
                        continue;
                    }
                    if (move > limit) {
                        move = limit;
                    }
                    if (move > from) {
                        move = from;
                    }

                    word = ElementField.setElem(word, shift, from - move);
                    neighborWord = ElementField.setElem(neighborWord, shift, neighborValue + move);
                    current[neighborIndex] = neighborWord;
                    changed = true;
                }
            }
            current[index] = word;
        }

        // 源项回流（把被抽低/堆高的格子缓慢拉回 base）
        for (int index = 0; index < ElementField.SIZE; index++) {
            int word = current[index];
            int baseWord = base[index];
            if (word == baseWord) {
                continue;
            }
            int out = word;
            boolean any = false;
            for (int t = 0; t < COUNT; t++) {
                int shift = t * 8;
                int value = (word >>> shift) & MASK;
                int baseValue = (baseWord >>> shift) & MASK;
                if (value == baseValue) {
                    continue;
                }
                int delta = baseValue - value;
                int next = value + ((delta * betaQ) >> 8);
                if (next != value) {
                    out = ElementField.setElem(out, shift, next);
                    any = true;
                }
            }
            if (any) {
                current[index] = out;
                changed = true;
            }
        }

        ElementBudget.record("flow", System.nanoTime() - t0);
        return changed;
    }

    /**
     * 拉普拉斯式扩散（旧模型，默认不走这条路径；保留给调试对比）。
     * 用 {@code scratch} 保存旧值，避免方向偏差。
     */
    public static boolean step(ElementField field, double alpha, double beta) {
        boolean changed = false;

        int[] current = field.currentWords();
        int[] base = field.baseWords();
        int[] previous = field.scratchWords();
        System.arraycopy(current, 0, previous, 0, ElementField.SIZE);

        for (int index = 0; index < ElementField.SIZE; index++) {
            int word = previous[index];
            int baseWord = base[index];
            int out = word;
            boolean any = false;

            int x = ElementField.x(index);
            int y = ElementField.y(index);
            int z = ElementField.z(index);

            for (int t = 0; t < COUNT; t++) {
                int shift = t * 8;
                int value = (word >>> shift) & MASK;
                int baseValue = (baseWord >>> shift) & MASK;

                int sum = 0;
                for (int n = 0; n < NEIGHBORS.length; n++) {
                    int[] offset = NEIGHBORS[n];
                    int nx = x + offset[0];
                    int ny = y + offset[1];
                    int nz = z + offset[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
                        sum += baseValue;
                    } else {
                        sum += (previous[ElementField.index(nx, ny, nz)] >>> shift) & MASK;
                    }
                }

                double diffusion = alpha * (sum - (double) NEIGHBORS.length * value);
                double source = beta * (baseValue - value);
                int next = (int) Math.round(value + diffusion + source);
                int clamped = ElementTypes.clamp(next) & MASK;
                if (clamped != value) {
                    out = ElementField.setElem(out, shift, clamped);
                    any = true;
                }
            }

            if (any) {
                current[index] = out;
                changed = true;
            }
        }
        return changed;
    }
}
