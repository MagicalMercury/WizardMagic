package com.wizardmagic.verify;

import com.wizardmagic.core.spell.script.NativeVm;

import java.util.Locale;

/**
 * 原生内核的 JNI 冒烟测试：不启动 Minecraft，不接法术系统。
 *
 * <p>它只回答三件事：库能不能加载、指令能不能跑、错误码能不能传回来。
 * 真正的"逐位对拍"要靠 {@code NativeVmParity}（下一个用例）。</p>
 *
 * <p>运行前需要把库路径传进来：{@code -Dwizardmagic.native.library=<build-native>/wmvm/wmvm.dll}；
 * 没有原生库时整体跳过（CI 的 ubuntu runner 就是这种情况）。</p>
 */
public final class NativeVmCheck
{
    private static int checks;
    private static int failures;

    private NativeVmCheck()
    {
    }

    public static void main(String[] args)
    {
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run()
    {
        checks = 0;
        failures = 0;
        if (!NativeVm.available())
        {
            // 游戏内路径验证：必须能加载，加载不到就是失败（而不是"跳过"）
            boolean must = System.getProperty("wizardmagic.native.mustLoad") != null;
            checks++;
            if (must)
            {
                failures++;
                System.out.println("[FAIL] 必须能加载原生库，但失败了：" + NativeVm.loadError());
            }
            else
            {
                System.out.println("[skip] 原生库不可用：" + NativeVm.loadError());
            }
            System.out.println("checks=" + checks + " failures=" + failures);
            return failures;
        }
        System.out.println("[info] 原生内核：" + NativeVm.describe());

        handleLifecycle();
        posPlusVel();
        divByZero();
        noisyArgs();

        System.out.println("checks=" + checks + " failures=" + failures);
        return failures;
    }

    private static void check(String what, boolean ok)
    {
        checks++;
        if (!ok)
        {
            failures++;
            System.out.println("[FAIL] " + what);
        }
        else
        {
            System.out.println("[ok]   " + what);
        }
    }

    private static void checkBits(String what, double got, double want)
    {
        checks++;
        if (Double.doubleToRawLongBits(got) != Double.doubleToRawLongBits(want))
        {
            failures++;
            System.out.println(String.format(Locale.ROOT, "[FAIL] %s got=%s want=%s", what,
                    Double.toHexString(got), Double.toHexString(want)));
        }
        else
        {
            System.out.println("[ok]   " + what);
        }
    }

    // ------------------------------------------------------------ 用例

    private static void handleLifecycle()
    {
        long h = NativeVm.create(new int[]{ (1 << 24) }, new double[]{ 1.0 });
        check("句柄非 0", h != 0L);
        NativeVm.destroy(h);
        check("销毁后再次创建仍可用",
                NativeVm.create(new int[]{ (1 << 24) }, new double[]{ 2.0 }) != 0L);
    }

    /** p.pos = p.pos + p.v，三段粒子 */
    private static void posPlusVel()
    {
        // LDK 3,1 / LPOS 0 / LVEL 3 / ADD_V 0,0,3 / SPOS 0
        int[] code = {
                w(1, 6, 0, 0), 0,          // LDK 6 = 1.0（常量 0 号）
                w(8, 0, 0, 0),             // LPOS 0..2
                w(12, 3, 0, 0),            // LVEL 3..5
                w(46, 0, 0, 3),            // ADD_V 0 = 0 + 3
                w(32, 0, 0, 0),            // SPOS 0..
        };
        long h = NativeVm.create(code, new double[]{ 1.0 });
        check("程序注册", h != 0L);

        double[] px = { 1.0, 2.0, 3.0 };
        double[] py = { 0.0, 0.0, 0.0 };
        double[] pz = { 0.0, 0.0, 0.0 };
        double[] vx = { 0.5, -0.25, 10.0 };
        double[] vy = { 1.0, 1.0, 1.0 };
        double[] vz = { 0.0, 0.0, 0.0 };
        float[] en = { 1.0F, 1.0F, 1.0F };
        double[] zero = { 0.0, 0.0, 0.0 };
        double[] fv = new double[8];
        double[] hv = new double[8];

        int rc = NativeVm.run(h, 0, 3, px, py, pz, vx, vy, vz, en,
                zero, zero, zero, null, null, null, null, null, 0.0, 0.0, 0.0, 7L, 0, 0.0, 0, fv, hv);
        check("run 返回 0", rc == 0);
        checkBits("pos[0] += vx", px[0], 1.5);
        checkBits("pos[1] += vx", px[1], 1.75);
        checkBits("pos[2] += vx", px[2], 13.0);
        checkBits("py[0] += vy", py[0], 1.0);
        NativeVm.destroy(h);
    }

    /** 除零要回一个编码过的错误码，而不是崩 */
    private static void divByZero()
    {
        // LDK 0,0 = 1.0 / LDK 1,0 = 0.0 / DIV_N 2,0,1
        int[] code = {
                w(1, 0, 0, 0), 0,
                w(1, 1, 0, 0), 1,
                w(44, 2, 0, 1),
        };
        long h = NativeVm.create(code, new double[]{ 1.0, 0.0 });
        double[] one = { 0.0 };
        double[] zero = { 0.0 };
        float[] en = { 0.0F };
        double[] fv = new double[8];
        double[] hv = new double[8];
        int rc = NativeVm.run(h, 0, 1, one, one, one, one, one, one, en,
                zero, zero, zero, null, null, null, null, null, 0.0, 0.0, 0.0, 1L, 0, 0.0, 0, fv, hv);
        // ip = 4（第 3 条指令的字下标），kind = 1（除零）
        check("除零返回错误码 -(4*8+1)", rc == -(4 * 8 + 1));
        check("错误说明非空", !NativeVm.lastError().isEmpty());
        NativeVm.destroy(h);
    }

    /** 未实现的 exp/pow/atan2 要明确报"不支持"，不能静默算错 */
    private static void noisyArgs()
    {
        int[] code = { w(66, 0, 0, 0) };   // OP_EXP
        long h = NativeVm.create(code, null);
        double[] one = { 0.0 };
        float[] en = { 0.0F };
        double[] zero = { 0.0 };
        double[] fv = new double[8];
        double[] hv = new double[8];
        int rc = NativeVm.run(h, 0, 1, one, one, one, one, one, one, en,
                zero, zero, zero, null, null, null, null, null, 0.0, 0.0, 0.0, 1L, 0, 0.0, 0, fv, hv);
        check("exp 返回 UNSUPPORTED", rc == -(0 * 8 + 5));
        NativeVm.destroy(h);
    }

    private static int w(int op, int d, int a, int b)
    {
        return (op << 24) | ((d & 0xFF) << 16) | ((a & 0xFF) << 8) | (b & 0xFF);
    }
}
