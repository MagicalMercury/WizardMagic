package com.wizardmagic.core.spell;

/**
 * 确定性随机：服务端与客户端用同一套纯函数，保证同一 {@code castSeed} 生成完全相同的粒子。
 *
 * <p>刻意不使用 {@link java.util.Random}（实现可能随 JDK 变化），也不依赖任何游戏状态。
 * 只使用整数混合、{@code + - * /} 与 {@code sqrt}，这些在 IEEE 754 双精度下逐位确定。</p>
 */
public final class SpellRandom
{
    /** 2^-53，把 long 的高 53 位映射到 [0,1) */
    private static final double UNIT = 0x1.0p-53;

    private SpellRandom()
    {
    }

    /** splitmix64 收尾混合 */
    public static long mix(long z)
    {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** [0,1) 均匀分布 */
    public static double unit(long seed, int index, int stream)
    {
        long h = seed;
        h ^= (long) index * 0x9E3779B97F4A7C15L;
        h ^= (long) stream * 0xC2B2AE3D27D4EB4FL;
        return (mix(h) >>> 11) * UNIT;
    }

    public static double range(long seed, int index, int stream, double lo, double hi)
    {
        return lo + (hi - lo) * unit(seed, index, stream);
    }

    /**
     * 均匀球面方向（无三角函数，逐位可复现）。
     *
     * <p>用 Marsaglia 的拒绝采样：在圆内取点 (a,b)，再映射到球面。最多试 8 次，
     * 全失败时退回 (0,1,0)（概率 &lt; 1e-12）。</p>
     *
     * @param out 长度 3 的输出数组
     */
    public static void sphere(long seed, int index, int stream, double[] out)
    {
        for (int attempt = 0; attempt < 8; attempt++)
        {
            double a = 2.0 * unit(seed, index, stream + attempt * 3) - 1.0;
            double b = 2.0 * unit(seed, index, stream + attempt * 3 + 1) - 1.0;
            double s = a * a + b * b;
            if (s >= 1.0 || s < 1.0E-9)
            {
                continue;
            }
            double f = 2.0 * Math.sqrt(1.0 - s);
            out[0] = a * f;
            out[1] = b * f;
            out[2] = 1.0 - 2.0 * s;
            return;
        }
        out[0] = 0.0;
        out[1] = 1.0;
        out[2] = 0.0;
    }
}
