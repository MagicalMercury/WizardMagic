package com.wizardmagic.elements.fluid;

import java.util.Arrays;

/**
 * 96³ 模拟域的 **交错 MAC** 场（4 格网格 → 24³ 格心）。
 *
 * <pre>
 *   格心（24³ = 13,824）: p, T, div
 *   面心（各 14,400）    : u 25×24×24 / v 24×25×24 / w 24×24×25
 * </pre>
 *
 * 交错布局让 {@code div(grad p)} 恰为紧凑 7 点、{@code div(curl ψ) ≡ 0}，
 * 投影因此是精确投影（CHANGE §2.2 / 附录 C.9）。
 *
 * 世界坐标 ↔ 格坐标：{@code gx = (wx - originX) / CELL}；
 * 面 u(i) 位于格边界 {@code originX + i*CELL}。
 */
public final class FluidField {

    public static final int N = 24;
    public static final int CELL = 4;
    public static final int DOMAIN = N * CELL;          // 96
    public static final int NC = N * N * N;             // 13,824
    public static final int NU = (N + 1) * N * N;       // 14,400
    public static final int NV = N * (N + 1) * N;
    public static final int NW = N * N * (N + 1);

    final float[] u = new float[NU];
    final float[] v = new float[NV];
    final float[] w = new float[NW];
    final float[] uOld = new float[NU];
    final float[] vOld = new float[NV];
    final float[] wOld = new float[NW];
    final float[] p = new float[NC];
    final float[] t = new float[NC];
    final float[] div = new float[NC];
    final float[] uc = new float[NC];
    final float[] vc = new float[NC];
    final float[] wc = new float[NC];

    /** 域原点（世界方块坐标，最低角）。 */
    int originX;
    int originY;
    int originZ;

    static int c(int i, int j, int k) {
        return i + N * (j + N * k);
    }

    static int uf(int i, int j, int k) {
        return i + (N + 1) * (j + N * k);
    }

    static int vf(int i, int j, int k) {
        return i + N * (j + (N + 1) * k);
    }

    static int wf(int i, int j, int k) {
        return i + N * (j + N * k);
    }

    void clear() {
        Arrays.fill(u, 0f);
        Arrays.fill(v, 0f);
        Arrays.fill(w, 0f);
        Arrays.fill(uOld, 0f);
        Arrays.fill(vOld, 0f);
        Arrays.fill(wOld, 0f);
        Arrays.fill(p, 0f);
        Arrays.fill(t, 0f);
        Arrays.fill(div, 0f);
        Arrays.fill(uc, 0f);
        Arrays.fill(vc, 0f);
        Arrays.fill(wc, 0f);
    }

    /** 域是否覆盖这个世界点（含 1 格边距）。 */
    boolean contains(double wx, double wy, double wz) {
        return wx >= originX && wx < originX + DOMAIN
                && wy >= originY && wy < originY + DOMAIN
                && wz >= originZ && wz < originZ + DOMAIN;
    }

    /**
     * 世界坐标 → 格心速度（三线性插值）。域外 clamp 到边界。
     *
     * @param out 输出 3 分量（格/秒）
     * @return 0 = 在域内，1 = 被 clamp（调用方可退化为背景风）
     */
    public int sample(double wx, double wy, double wz, float[] out) {
        double gx = (wx - originX) / CELL;
        double gy = (wy - originY) / CELL;
        double gz = (wz - originZ) / CELL;
        boolean clamped = gx < 0 || gy < 0 || gz < 0 || gx >= N || gy >= N || gz >= N;
        gx = clamp(gx, 0.0, N - 1e-6);
        gy = clamp(gy, 0.0, N - 1e-6);
        gz = clamp(gz, 0.0, N - 1e-6);
        int i = (int) gx, j = (int) gy, k = (int) gz;
        float fx = (float) (gx - i), fy = (float) (gy - j), fz = (float) (gz - k);
        int i1 = Math.min(i + 1, N - 1), j1 = Math.min(j + 1, N - 1), k1 = Math.min(k + 1, N - 1);
        float g0 = 1f - fx, g1 = 1f - fy, g2 = 1f - fz;

        out[0] = tri(uc, i, j, k, i1, j1, k1, fx, fy, fz, g0, g1, g2);
        out[1] = tri(vc, i, j, k, i1, j1, k1, fx, fy, fz, g0, g1, g2);
        out[2] = tri(wc, i, j, k, i1, j1, k1, fx, fy, fz, g0, g1, g2);
        return clamped ? 1 : 0;
    }

    private static float tri(float[] f, int i, int j, int k, int i1, int j1, int k1,
                             float fx, float fy, float fz, float g0, float g1, float g2) {
        int p000 = c(i, j, k), p100 = c(i1, j, k), p010 = c(i, j1, k), p110 = c(i1, j1, k);
        int p001 = c(i, j, k1), p101 = c(i1, j, k1), p011 = c(i, j1, k1), p111 = c(i1, j1, k1);
        float w00 = g0 * g1, w10 = fx * g1, w01 = g0 * fy, w11 = fx * fy;
        return (f[p000] * w00 + f[p100] * w10 + f[p010] * w01 + f[p110] * w11) * g2
                + (f[p001] * w00 + f[p101] * w10 + f[p011] * w01 + f[p111] * w11) * fz;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 最大散度（诊断用，格单位）。 */
    float maxDivergence() {
        float max = 0f;
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++)
                for (int k = 0; k < N; k++) {
                    float d = (u[uf(i + 1, j, k)] - u[uf(i, j, k)])
                            + (v[vf(i, j + 1, k)] - v[vf(i, j, k)])
                            + (w[wf(i, j, k + 1)] - w[wf(i, j, k)]);
                    max = Math.max(max, Math.abs(d));
                }
        return max;
    }

    /** 面场速度的最大绝对值（诊断用，不依赖格心缓存）。NaN 会让 Math.max 静默返回旧值，单独计数。 */
    int nanVelocities;

    float maxAbsVelocity() {
        float max = 0f;
        int nan = 0;
        for (float x : u) {
            if (Float.isNaN(x)) {
                nan++;
            } else {
                max = Math.max(max, Math.abs(x));
            }
        }
        for (float x : v) {
            if (Float.isNaN(x)) {
                nan++;
            } else {
                max = Math.max(max, Math.abs(x));
            }
        }
        for (float x : w) {
            if (Float.isNaN(x)) {
                nan++;
            } else {
                max = Math.max(max, Math.abs(x));
            }
        }
        nanVelocities = nan;
        return max;
    }

    /** 平均动能（格/秒）²，直接读面场（格心缓存可能未刷新）。 */
    float meanKinetic() {
        double sum = 0;
        for (float x : u) {
            sum += x * x;
        }
        for (float x : v) {
            sum += x * x;
        }
        for (float x : w) {
            sum += x * x;
        }
        return (float) (sum / (double) (u.length + v.length + w.length));
    }
}
