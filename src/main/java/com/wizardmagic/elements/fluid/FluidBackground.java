package com.wizardmagic.elements.fluid;

/**
 * 背景风：程序化 curl-noise 构造的**离散无散**速度场（CHANGE §2.5）。
 *
 * <pre>
 *   ψ  = (ψx, ψy, ψz)   三个正弦叠加的矢量势
 *   u_bg = ∇ × ψ        curl 的离散形式在 MAC 面上直接求
 * </pre>
 *
 * 因为 u_bg 是旋度场，{@code div(u_bg) ≡ 0}（离散恒等式），
 * 所以它**不参与压力投影**——投影只需处理元素驱动的扰动。
 * 这让 1 Hz 的求解成本保持很低，同时给场景一个持续的大尺度风向。
 */
public final class FluidBackground {

    /** 背景风整体速度（格/秒） */
    private static final float SPEED = 0.8f;

    // 三个正弦分量的波数与角频率（互质，避免锁相成规则图案）
    private static final double AX = 0.055;
    private static final double BY = 0.041;
    private static final double CX = 0.047;
    private static final double DZ = 0.062;
    private static final double W1 = 0.13;
    private static final double W2 = 0.09;

    private FluidBackground() {
    }

    /** 把背景风覆写进面场（速度单位：格/秒）。 */
    public static void apply(FluidField f, double time) {
        int n = FluidField.N;
        int cell = FluidField.CELL;

        // u 面：x = i, y = j+0.5, z = k+0.5
        for (int i = 0; i <= n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) {
                    double x = i * cell;
                    double y = (j + 0.5) * cell;
                    double z = (k + 0.5) * cell;
                    f.u[FluidField.uf(i, j, k)] = (float) (curlX(x, y, z, time) * SPEED);
                }
        // v 面：x = i+0.5, y = j, z = k+0.5
        for (int i = 0; i < n; i++)
            for (int j = 0; j <= n; j++)
                for (int k = 0; k < n; k++) {
                    double x = (i + 0.5) * cell;
                    double y = j * cell;
                    double z = (k + 0.5) * cell;
                    f.v[FluidField.vf(i, j, k)] = (float) (curlY(x, y, z, time) * SPEED);
                }
        // w 面：x = i+0.5, y = j+0.5, z = k
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k <= n; k++) {
                    double x = (i + 0.5) * cell;
                    double y = (j + 0.5) * cell;
                    double z = k * cell;
                    f.w[FluidField.wf(i, j, k)] = (float) (curlZ(x, y, z, time) * SPEED);
                }
    }

    /**
     * u = ∂ψz/∂y − ∂ψy/∂z
     * <pre>
     *   ψz = sin(AX·x + W1·t) · cos(BY·y)   → ∂ψz/∂y = -BY·sin(...)·sin(BY·y)
     *   ψy = cos(CX·x − W2·t) · sin(DZ·z)   → ∂ψy/∂z =  DZ·cos(...)·cos(DZ·z)
     * </pre>
     */
    private static double curlX(double x, double y, double z, double t) {
        double dPsiZ_dy = -BY * Math.sin(AX * x + W1 * t) * Math.sin(BY * y);
        double dPsiY_dz = DZ * Math.cos(CX * x - W2 * t) * Math.cos(DZ * z);
        return dPsiZ_dy - dPsiY_dz;
    }

    /**
     * v = ∂ψx/∂z − ∂ψz/∂x
     * <pre>
     *   ψx = cos(BY·y − W1·t) · sin(DZ·z)   → ∂ψx/∂z =  DZ·cos(...)·cos(DZ·z)
     *   ψz = sin(AX·x + W1·t) · cos(BY·y)   → ∂ψz/∂x =  AX·cos(...)·cos(BY·y)
     * </pre>
     */
    private static double curlY(double x, double y, double z, double t) {
        double dPsiX_dz = DZ * Math.cos(BY * y - W1 * t) * Math.cos(DZ * z);
        double dPsiZ_dx = AX * Math.cos(AX * x + W1 * t) * Math.cos(BY * y);
        return dPsiX_dz - dPsiZ_dx;
    }

    /**
     * w = ∂ψy/∂x − ∂ψx/∂y
     * <pre>
     *   ψy = cos(CX·x − W2·t) · sin(DZ·z)   → ∂ψy/∂x = -CX·sin(...)·sin(DZ·z)
     *   ψx = cos(BY·y − W1·t) · sin(DZ·z)   → ∂ψx/∂y = -BY·sin(...)·sin(DZ·z)
     * </pre>
     */
    private static double curlZ(double x, double y, double z, double t) {
        double dPsiY_dx = -CX * Math.sin(CX * x - W2 * t) * Math.sin(DZ * z);
        double dPsiX_dy = -BY * Math.sin(BY * y - W1 * t) * Math.sin(DZ * z);
        return dPsiY_dx - dPsiX_dy;
    }
}
