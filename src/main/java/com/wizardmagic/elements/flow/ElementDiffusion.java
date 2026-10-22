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
 * <p>采用「先采样旧值再原地写」，避免方向偏差。</p>
 */
public final class ElementDiffusion
{
    private static final ElementTypes[] TYPES = ElementTypes.values();

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
        boolean changed = false;

        for (ElementTypes type : TYPES) {
            byte[] current = field.currentArray(type);
            byte[] base = field.baseArray(type);

            for (int index = 0; index < ElementField.SIZE; index++) {
                int value = current[index] & 0x7F;
                if (value <= 0) {
                    continue;
                }
                int x = ElementField.x(index);
                int y = ElementField.y(index);
                int z = ElementField.z(index);

                for (int[] offset : NEIGHBORS) {
                    int nx = x + offset[0];
                    int ny = y + offset[1];
                    int nz = z + offset[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
                        continue;
                    }

                    int from = current[index] & 0x7F;
                    int neighborIndex = ElementField.index(nx, ny, nz);
                    int neighborValue = current[neighborIndex] & 0x7F;
                    int diff = from - neighborValue;
                    if (diff <= 0) {
                        continue;
                    }

                    // 提取比例后不足 1 点就不再扩散：
                    // 差值 < 1/rate（默认约 2.9）时停止，扩散自然有有限半径
                    double raw = diff * rate;
                    if (raw < 1.0) {
                        continue;
                    }
                    int move = Math.min((int) raw, maxFlux);
                    if (move <= 0 || move > from) {
                        continue;
                    }

                    current[index] = (byte) ElementTypes.clamp(from - move);
                    current[neighborIndex] = (byte) ElementTypes.clamp(neighborValue + move);
                    changed = true;
                }
            }

            // 源项回流（把被抽低/堆高的格子缓慢拉回 base）
            for (int index = 0; index < ElementField.SIZE; index++) {
                int value = current[index] & 0x7F;
                int baseValue = base[index] & 0x7F;
                if (value == baseValue) {
                    continue;
                }
                int next = (int) Math.round(value + beta * (baseValue - value));
                if (next != value) {
                    current[index] = (byte) ElementTypes.clamp(next);
                    changed = true;
                }
            }
        }

        return changed;
    }

    public static boolean step(ElementField field, double alpha, double beta) {
        boolean changed = false;
        for (ElementTypes type : TYPES) {
            byte[] current = field.currentArray(type);
            byte[] base = field.baseArray(type);
            byte[] previous = field.scratchArray(type);
            System.arraycopy(current, 0, previous, 0, ElementField.SIZE);

            for (int index = 0; index < ElementField.SIZE; index++) {
                int x = ElementField.x(index);
                int y = ElementField.y(index);
                int z = ElementField.z(index);

                int value = previous[index] & 0x7F;
                int baseValue = base[index] & 0x7F;

                int sum = 0;
                int count = 0;
                for (int[] offset : NEIGHBORS) {
                    int nx = x + offset[0];
                    int ny = y + offset[1];
                    int nz = z + offset[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
                        sum += baseValue;
                    } else {
                        sum += previous[ElementField.index(nx, ny, nz)] & 0x7F;
                    }
                    count++;
                }

                double diffusion = alpha * (sum - (double) count * value);
                double source = beta * (baseValue - value);
                int next = (int) Math.round(value + diffusion + source);
                byte packed = (byte) ElementTypes.clamp(next);
                if (packed != current[index]) {
                    changed = true;
                }
                current[index] = packed;
            }
        }
        return changed;
    }
}
