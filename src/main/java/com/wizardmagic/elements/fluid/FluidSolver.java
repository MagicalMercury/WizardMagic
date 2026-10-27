package com.wizardmagic.elements.fluid;

import java.util.Arrays;

/**
 * 不可压缩 Euler 的投影分裂解（Chorin），MAC 交错网格。
 *
 * <pre>
 *   1. advect      u = advect(u, u, dt)      半拉格朗日，面→心→面
 *   2. divergence  div = ∇·u                 紧凑差分
 *   3. project     solve L p = div (MG)，u -= ∇p
 *   4. advectScalar T / c                    格心被动标量
 * </pre>
 *
 * 压力求解：4 层多重网格（24→12→6→3），红黑 GS V(2,2)×3，
 * 粗层带 {@code 4^-l} 标度——缺这两条就不收敛（CHANGE §6 D2）。
 */
public final class FluidSolver {

    private static final int[] LV = {FluidField.N, FluidField.N / 2, FluidField.N / 4, FluidField.N / 8};

    private static float[][] pL;
    private static float[][] rL;
    private static float[][] bL;

    private FluidSolver() {
    }

    /** 一次完整求解（1 Hz 由调用方控制）。 */
    public static void solve(FluidField f) {
        long t0 = System.nanoTime();
        advectVelocity(f);
        long t1 = System.nanoTime();
        divergence(f);
        project(f);
        long t2 = System.nanoTime();
        advectScalar(f);
        long t3 = System.nanoTime();

        ElementBudgetRef.record("fluid.advect", t1 - t0);
        ElementBudgetRef.record("fluid.project", t2 - t1);
        ElementBudgetRef.record("fluid.scalar", t3 - t2);
        ElementBudgetRef.record("fluid.total", t3 - t0);
    }

    // ---------------- 1. 速度自平流 ----------------

    static void advectVelocity(FluidField f) {
        System.arraycopy(f.u, 0, f.uOld, 0, FluidField.NU);
        System.arraycopy(f.v, 0, f.vOld, 0, FluidField.NV);
        System.arraycopy(f.w, 0, f.wOld, 0, FluidField.NW);

        faceToCell(f);

        float dt = 1.0f;
        float[] vel = new float[3];
        int n = FluidField.N;

        for (int i = 0; i <= n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    float x = i, y = j + 0.5f, z = k + 0.5f;
                    sampleCell(f, x, y, z, vel);
                    f.u[FluidField.uf(i, j, k)] = sampleFace(f.uOld, 'u', x - vel[0] * dt,
                            y - vel[1] * dt, z - vel[2] * dt);
                }
        for (int i = 0; i < n; i++)
            for (int j = 0; j <= n; j++)
                for (int k = 0; k < n; k++) {
                    float x = i + 0.5f, y = j, z = k + 0.5f;
                    sampleCell(f, x, y, z, vel);
                    f.v[FluidField.vf(i, j, k)] = sampleFace(f.vOld, 'v', x - vel[0] * dt,
                            y - vel[1] * dt, z - vel[2] * dt);
                }
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k <= n; k++) {
                    float x = i + 0.5f, y = j + 0.5f, z = k;
                    sampleCell(f, x, y, z, vel);
                    f.w[FluidField.wf(i, j, k)] = sampleFace(f.wOld, 'w', x - vel[0] * dt,
                            y - vel[1] * dt, z - vel[2] * dt);
                }
    }

    /** 面 → 格心（法向平均），结果写入 uc/vc/wc。 */
    static void faceToCell(FluidField f) {
        int n = FluidField.N;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    int id = FluidField.c(i, j, k);
                    f.uc[id] = 0.5f * (f.u[FluidField.uf(i, j, k)] + f.u[FluidField.uf(i + 1, j, k)]);
                    f.vc[id] = 0.5f * (f.v[FluidField.vf(i, j, k)] + f.v[FluidField.vf(i, j + 1, k)]);
                    f.wc[id] = 0.5f * (f.w[FluidField.wf(i, j, k)] + f.w[FluidField.wf(i, j, k + 1)]);
                }
    }

    private static void sampleCell(FluidField f, float x, float y, float z, float[] out) {
        out[0] = triCell(f.uc, x, y, z);
        out[1] = triCell(f.vc, x, y, z);
        out[2] = triCell(f.wc, x, y, z);
    }

    private static float triCell(float[] arr, float x, float y, float z) {
        int n = FluidField.N;
        x = Math.max(0, Math.min(n - 1e-4f, x - 0.5f));
        y = Math.max(0, Math.min(n - 1e-4f, y - 0.5f));
        z = Math.max(0, Math.min(n - 1e-4f, z - 0.5f));
        int i = (int) x, j = (int) y, k = (int) z;
        float fx = x - i, fy = y - j, fz = z - k;
        int i1 = Math.min(i + 1, n - 1), j1 = Math.min(j + 1, n - 1), k1 = Math.min(k + 1, n - 1);
        float g0 = 1f - fx, g1 = 1f - fy, g2 = 1f - fz;
        int p000 = FluidField.c(i, j, k), p100 = FluidField.c(i1, j, k);
        int p010 = FluidField.c(i, j1, k), p110 = FluidField.c(i1, j1, k);
        int p001 = FluidField.c(i, j, k1), p101 = FluidField.c(i1, j, k1);
        int p011 = FluidField.c(i, j1, k1), p111 = FluidField.c(i1, j1, k1);
        float w00 = g0 * g1, w10 = fx * g1, w01 = g0 * fy, w11 = fx * fy;
        return (arr[p000] * w00 + arr[p100] * w10 + arr[p010] * w01 + arr[p110] * w11) * g2
                + (arr[p001] * w00 + arr[p101] * w10 + arr[p011] * w01 + arr[p111] * w11) * fz;
    }

    /** 面场三线性采样：用 uf/vf/wf 的真实面布局索引（面 i 位于格边界 i）。 */
    static float sampleFace(float[] arr, char comp, float fx, float fy, float fz) {
        int n = FluidField.N;
        fx = Math.max(0, Math.min(n, fx));
        fy = Math.max(0, Math.min(n - 1e-4f, fy));
        fz = Math.max(0, Math.min(n - 1e-4f, fz));
        int i = Math.min((int) fx, n - 1);
        int j = (int) fy;
        int k = (int) fz;
        float dx = fx - i, dy = fy - j, dz = fz - k;
        int i1 = Math.min(i + 1, n), j1 = Math.min(j + 1, n - 1), k1 = Math.min(k + 1, n - 1);
        int p000 = idx(comp, i, j, k), p100 = idx(comp, i1, j, k);
        int p010 = idx(comp, i, j1, k), p110 = idx(comp, i1, j1, k);
        int p001 = idx(comp, i, j, k1), p101 = idx(comp, i1, j, k1);
        int p011 = idx(comp, i, j1, k1), p111 = idx(comp, i1, j1, k1);
        float g0 = 1f - dx, g1 = 1f - dy, g2 = 1f - dz;
        float w00 = g0 * g1, w10 = dx * g1, w01 = g0 * dy, w11 = dx * dy;
        return (arr[p000] * w00 + arr[p100] * w10 + arr[p010] * w01 + arr[p110] * w11) * g2
                + (arr[p001] * w00 + arr[p101] * w10 + arr[p011] * w01 + arr[p111] * w11) * dz;
    }

    private static int idx(char comp, int i, int j, int k) {
        return switch (comp) {
            case 'u' -> FluidField.uf(i, j, k);
            case 'v' -> FluidField.vf(i, j, k);
            default -> FluidField.wf(i, j, k);
        };
    }

    // ---------------- 2. 散度 ----------------

    static void divergence(FluidField f) {
        int n = FluidField.N;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    f.div[FluidField.c(i, j, k)] =
                            (f.u[FluidField.uf(i + 1, j, k)] - f.u[FluidField.uf(i, j, k)])
                                    + (f.v[FluidField.vf(i, j + 1, k)] - f.v[FluidField.vf(i, j, k)])
                                    + (f.w[FluidField.wf(i, j, k + 1)] - f.w[FluidField.wf(i, j, k)]);
                }
    }

    // ---------------- 3. 压力投影（MG） ----------------

    static void project(FluidField f) {
        ensureMG();
        // rbgs 解的是 (6x − Σ邻居) = -∇²p，所以要满足 ∇²p = div，
        // 右端必须取 -div。
        for (int q = 0; q < FluidField.NC; q++) {
            bL[0][q] = -f.div[q];
        }
        Arrays.fill(pL[0], 0f);

        for (int vc = 0; vc < 3; vc++) {
            vcycle(0);
        }

        // Neumann 泊松只在 mean-free 空间唯一
        double mean = 0;
        for (int q = 0; q < FluidField.NC; q++) mean += pL[0][q];
        mean /= FluidField.NC;

        int n = FluidField.N;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    int id = FluidField.c(i, j, k);
                    float pc = (float) (pL[0][id] - mean);
                    float px = i > 0 ? (float) (pL[0][FluidField.c(i - 1, j, k)] - mean) : pc;
                    float py = j > 0 ? (float) (pL[0][FluidField.c(i, j - 1, k)] - mean) : pc;
                    float pz = k > 0 ? (float) (pL[0][FluidField.c(i, j, k - 1)] - mean) : pc;
                    f.u[FluidField.uf(i, j, k)] -= pc - px;
                    f.v[FluidField.vf(i, j, k)] -= pc - py;
                    f.w[FluidField.wf(i, j, k)] -= pc - pz;
                }
        // 镜像边界上的法向通量保持（Neumann）
        for (int j = 0; j < n; j++)
            for (int k = 0; k < n; k++) {
                f.u[FluidField.uf(n, j, k)] = f.u[FluidField.uf(n - 1, j, k)];
                f.v[FluidField.vf(0, n, k)] = f.v[FluidField.vf(0, n - 1, k)];
                f.w[FluidField.wf(0, 0, n)] = f.w[FluidField.wf(0, 0, n - 1)];
            }

        // 暴露压力场（诊断 / 后续元素层可能用到）
        System.arraycopy(pL[0], 0, f.p, 0, FluidField.NC);
    }

    private static void ensureMG() {
        if (pL != null) {
            return;
        }
        pL = new float[4][];
        rL = new float[4][];
        bL = new float[4][];
        for (int l = 0; l < 4; l++) {
            int n = LV[l];
            pL[l] = new float[n * n * n];
            rL[l] = new float[n * n * n];
            bL[l] = new float[n * n * n];
        }
    }

    private static void vcycle(int l) {
        int n = LV[l];
        float[] x = pL[l];
        float[] b = bL[l];
        if (l == 3) {
            for (int it = 0; it < 50; it++) {
                rbgs(n, x, b, l);
            }
            return;
        }
        rbgs(n, x, b, l);
        rbgs(n, x, b, l);
        residual(n, x, b, rL[l], l);
        int cn = LV[l + 1];
        restrict(n, rL[l], bL[l + 1], cn);
        meanRemove(bL[l + 1]);
        Arrays.fill(pL[l + 1], 0f);
        vcycle(l + 1);
        prolongate(cn, pL[l + 1], x, n);
        rbgs(n, x, b, l);
        rbgs(n, x, b, l);
    }

    private static int widx(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    private static void meanRemove(float[] a) {
        double m = 0;
        for (float v : a) {
            m += v;
        }
        m /= a.length;
        if (m == 0) {
            return;
        }
        for (int i = 0; i < a.length; i++) {
            a[i] -= (float) m;
        }
    }

    private static void rbgs(int n, float[] x, float[] b, int l) {
        float scale = (float) Math.pow(4.0, -l);
        for (int color = 0; color < 2; color++) {
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    for (int k = 0; k < n; k++) {
                        if (((i + j + k) & 1) != color) {
                            continue;
                        }
                        int id = i + n * (j + n * k);
                        float sum = 0f;
                        sum += (i > 0) ? x[id - 1] : x[id];
                        sum += (i < n - 1) ? x[id + 1] : x[id];
                        sum += (j > 0) ? x[id - n] : x[id];
                        sum += (j < n - 1) ? x[id + n] : x[id];
                        sum += (k > 0) ? x[id - n * n] : x[id];
                        sum += (k < n - 1) ? x[id + n * n] : x[id];
                        x[id] = (sum + b[id] / scale) / 6f;
                    }
        }
    }

    private static void residual(int n, float[] x, float[] b, float[] r, int l) {
        float scale = (float) Math.pow(4.0, -l);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    int id = i + n * (j + n * k);
                    float sum = 0f;
                    sum += (i > 0) ? x[id - 1] : x[id];
                    sum += (i < n - 1) ? x[id + 1] : x[id];
                    sum += (j > 0) ? x[id - n] : x[id];
                    sum += (j < n - 1) ? x[id + n] : x[id];
                    sum += (k > 0) ? x[id - n * n] : x[id];
                    sum += (k < n - 1) ? x[id + n * n] : x[id];
                    // r = b - A x,  A = scale * (6I - 邻域和)
                    r[id] = b[id] - scale * (6f * x[id] - sum);
                }
    }

    /** 4×4×4 加权限制（0.125 / 0.375 / 0.375 / 0.125），与 MacProjectionBench 一致。 */
    private static void restrict(int n, float[] fine, float[] coarse, int cn) {
        final float[] w = {0.125f, 0.375f, 0.375f, 0.125f};
        for (int i = 0; i < cn; i++)
            for (int j = 0; j < cn; j++)
                for (int k = 0; k < cn; k++) {
                    float sum = 0f;
                    for (int a = 0; a < 4; a++) {
                        int sx = widx(2 * i + a - 1, n);
                        for (int b = 0; b < 4; b++) {
                            int sy = widx(2 * j + b - 1, n);
                            float wy = w[a] * w[b];
                            for (int c = 0; c < 4; c++) {
                                int sz = widx(2 * k + c - 1, n);
                                sum += wy * w[c] * fine[sx + n * (sy + n * sz)];
                            }
                        }
                    }
                    coarse[i + cn * (j + cn * k)] = sum;
                }
    }

    /** 双线性插值延拓（偶坐标 0.25/0.75，奇坐标 0.75/0.25），累加到细层。 */
    private static void prolongate(int cn, float[] coarse, float[] fine, int n) {
        for (int x = 0; x < n; x++) {
            int I = x / 2;
            int ax;
            int bx;
            float wa;
            float wb;
            if ((x & 1) == 0) {
                ax = widx(I - 1, cn);
                wa = 0.25f;
                bx = I;
                wb = 0.75f;
            } else {
                ax = I;
                wa = 0.75f;
                bx = widx(I + 1, cn);
                wb = 0.25f;
            }
            for (int y = 0; y < n; y++) {
                int J = y / 2;
                int ay;
                int by;
                float wya;
                float wyb;
                if ((y & 1) == 0) {
                    ay = widx(J - 1, cn);
                    wya = 0.25f;
                    by = J;
                    wyb = 0.75f;
                } else {
                    ay = J;
                    wya = 0.75f;
                    by = widx(J + 1, cn);
                    wyb = 0.25f;
                }
                for (int z = 0; z < n; z++) {
                    int K = z / 2;
                    int az;
                    int bz;
                    float wza;
                    float wzb;
                    if ((z & 1) == 0) {
                        az = widx(K - 1, cn);
                        wza = 0.25f;
                        bz = K;
                        wzb = 0.75f;
                    } else {
                        az = K;
                        wza = 0.75f;
                        bz = widx(K + 1, cn);
                        wzb = 0.25f;
                    }
                    float v = 0f;
                    v += wa * wya * wza * coarse[ax + cn * (ay + cn * az)];
                    v += wa * wya * wzb * coarse[ax + cn * (ay + cn * bz)];
                    v += wa * wyb * wza * coarse[ax + cn * (by + cn * az)];
                    v += wa * wyb * wzb * coarse[ax + cn * (by + cn * bz)];
                    v += wb * wya * wza * coarse[bx + cn * (ay + cn * az)];
                    v += wb * wya * wzb * coarse[bx + cn * (ay + cn * bz)];
                    v += wb * wyb * wza * coarse[bx + cn * (by + cn * az)];
                    v += wb * wyb * wzb * coarse[bx + cn * (by + cn * bz)];
                    fine[x + n * (y + n * z)] += v;
                }
            }
        }
    }

    // ---------------- 4. 标量平流 ----------------

    static void advectScalar(FluidField f) {
        int n = FluidField.N;
        float[] src = new float[FluidField.NC];
        System.arraycopy(f.t, 0, src, 0, FluidField.NC);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    float x = i + 0.5f, y = j + 0.5f, z = k + 0.5f;
                    float bx = x - triCell(f.uc, x, y, z);
                    float by = y - triCell(f.vc, x, y, z);
                    float bz = z - triCell(f.wc, x, y, z);
                    f.t[FluidField.c(i, j, k)] = triCell(src, bx, by, bz);
                }
    }

    /** 计时转发到 ElementBudget（避免 fluid 包反向依赖 flow 包的类型）。 */
    static final class ElementBudgetRef {
        private ElementBudgetRef() {
        }

        static void record(String name, long nanos) {
            com.wizardmagic.elements.flow.ElementBudget.record(name, nanos);
        }
    }
}
