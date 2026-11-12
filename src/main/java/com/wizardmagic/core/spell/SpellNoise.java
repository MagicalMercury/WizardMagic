package com.wizardmagic.core.spell;

/**
 * 确定性三维 value noise，供六定律的「扩散」项使用。
 *
 * <p>只用整数混合与 {@code + - * /}，服务端与客户端逐位一致；禁止用
 * {@code Math.sin/cos/pow}（跨 JVM 不保证末位相同）与 Minecraft 的噪声实现
 * （可能随版本变化）。</p>
 */
public final class SpellNoise
{
    private static final double UNIT = 0x1.0p-53;

    private SpellNoise()
    {
    }

    /**
     * 三维 value noise，返回 [-1, 1]。
     *
     * @param step 时间维（步号），让同一个位置随时间变化
     */
    public static double value3(long seed, double x, double y, double z, int step)
    {
        int xi = floor(x);
        int yi = floor(y);
        int zi = floor(z);
        double fx = x - xi;
        double fy = y - yi;
        double fz = z - zi;
        double u = fade(fx);
        double v = fade(fy);
        double w = fade(fz);

        double c000 = hash(seed, xi, yi, zi, step);
        double c100 = hash(seed, xi + 1, yi, zi, step);
        double c010 = hash(seed, xi, yi + 1, zi, step);
        double c110 = hash(seed, xi + 1, yi + 1, zi, step);
        double c001 = hash(seed, xi, yi, zi + 1, step);
        double c101 = hash(seed, xi + 1, yi, zi + 1, step);
        double c011 = hash(seed, xi, yi + 1, zi + 1, step);
        double c111 = hash(seed, xi + 1, yi + 1, zi + 1, step);

        double x00 = lerp(c000, c100, u);
        double x10 = lerp(c010, c110, u);
        double x01 = lerp(c001, c101, u);
        double x11 = lerp(c011, c111, u);
        double y0 = lerp(x00, x10, v);
        double y1 = lerp(x01, x11, v);
        return lerp(y0, y1, w);
    }

    private static double hash(long seed, int x, int y, int z, int step)
    {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) z * 0x165667B19E3779F9L;
        h ^= (long) step * 0x27D4EB2F165667C5L;
        return (SpellRandom.mix(h) >>> 11) * UNIT * 2.0 - 1.0;
    }

    private static int floor(double v)
    {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** 五次平滑（smootherstep），避免格子感的硬边 */
    private static double fade(double t)
    {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t)
    {
        return a + (b - a) * t;
    }
}
