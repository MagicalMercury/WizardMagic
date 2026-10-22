package com.wizardmagic.elements.generation;

/**
 * 确定性 3D 值噪声（不依赖世界状态，纯函数）。
 *
 * <p>同样的 {@code (seed, x, y, z)} 永远得到同样的值，因此 chunk 卸载再加载、
 * 或者换存档后同 seed 重算，结果一致。</p>
 */
public final class ElementNoise
{
    private ElementNoise() {
    }

    /**
     * 多倍频叠加。
     *
     * @return -1..1
     */
    public static double fbm(long seed, double x, double y, double z, int octaves) {
        double sum = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0 / 64.0;
        double norm = 0.0;

        for (int i = 0; i < octaves; i++) {
            sum += amplitude * value(seed + i * 0x9E3779B97F4A7C15L, x * frequency, y * frequency, z * frequency);
            norm += amplitude;
            amplitude *= 0.5;
            frequency *= 2.5;
        }
        return norm == 0.0 ? 0.0 : sum / norm;
    }

    private static double value(long seed, double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);

        double fx = smooth(x - x0);
        double fy = smooth(y - y0);
        double fz = smooth(z - z0);

        double c000 = hash(seed, x0, y0, z0);
        double c100 = hash(seed, x0 + 1, y0, z0);
        double c010 = hash(seed, x0, y0 + 1, z0);
        double c110 = hash(seed, x0 + 1, y0 + 1, z0);
        double c001 = hash(seed, x0, y0, z0 + 1);
        double c101 = hash(seed, x0 + 1, y0, z0 + 1);
        double c011 = hash(seed, x0, y0 + 1, z0 + 1);
        double c111 = hash(seed, x0 + 1, y0 + 1, z0 + 1);

        double x00 = lerp(fx, c000, c100);
        double x10 = lerp(fx, c010, c110);
        double x01 = lerp(fx, c001, c101);
        double x11 = lerp(fx, c011, c111);

        double y0v = lerp(fy, x00, x10);
        double y1v = lerp(fy, x01, x11);

        return lerp(fz, y0v, y1v);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** 整数格点哈希 -> -1..1 */
    private static double hash(long seed, int x, int y, int z) {
        long h = seed;
        h += x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0x3C79AC492BA7B653L;
        h += y * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 31) * 0x1C69B3F74AC4AE35L;
        h += z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;

        // 取 21 位 -> 0..2097151，映射到 -1..1
        return ((h >>> 11) & 0x1FFFFFL) / 1048575.0 - 1.0;
    }
}
