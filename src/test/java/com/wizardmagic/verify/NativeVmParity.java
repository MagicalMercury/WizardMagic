package com.wizardmagic.verify;

import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.script.CastResult;
import com.wizardmagic.core.spell.script.EmitSpec;
import com.wizardmagic.core.spell.script.Host;
import com.wizardmagic.core.spell.script.NativeVm;
import com.wizardmagic.core.spell.script.SpellScript;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 原生内核的整段对拍：同一份编译产物、同一颗 seed，Java 后端与原生后端各跑 N 步，
 * 逐个粒子比较 {@code x,y,z,vx,vy,vz,energy} 的 raw bit。
 *
 * <p>为什么不用 {@code stateHash()}：它把位置量化到 1/4096，1 ulp 的差异经常看不出来。
 * 这里比的是 {@code doubleToRawLongBits}。</p>
 *
 * <p>没有原生库时整体跳过（CI 的 Linux runner）。</p>
 */
public final class NativeVmParity
{
    private static int checks;
    private static int failures;

    private NativeVmParity()
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
            System.out.println("[skip] 原生库不可用：" + NativeVm.loadError());
            return 0;
        }
        System.out.println("[info] 原生内核：" + NativeVm.describe());

        // 真实内置法术：firestorm 的粒子循环 = 115 条指令，含 axis/targetR/noise/分支
        parity("firestorm", builtin("/data/wizardmagic/spells/firestorm.spell"), 11L, 30);
        // 同一份 helix 脚本，但朝 +X 投出：tilted=true，轴心要过倾斜矩阵再平移
        // （生产里实体/投射物把朝向传进来，这条路径不能只靠"竖直向上"的用例覆盖）
        parity("firestorm 倾斜", builtin("/data/wizardmagic/spells/firestorm.spell"), 11L, 30,
                new double[]{ 1.0, 0.0, 0.0 });
        parity("frost_nova", builtin("/data/wizardmagic/spells/frost_nova.spell"), 12L, 30);
        parity("flame_pillar", builtin("/data/wizardmagic/spells/flame_pillar.spell"), 13L, 30);
        parity("meteor", builtin("/data/wizardmagic/spells/meteor.spell"), 14L, 30);
        parity("fireball", builtin("/data/wizardmagic/spells/fireball.spell"), 15L, 30);

        // 合成用例：把各个指令族单独压一遍
        parity("向量与数学", tickOnly("""
                p.pos = p.pos + normalize(vec(1, 2, 3)) * 0.01
                p.v = cross(p.v, vec(0, 1, 0)) + p.pos * 0.5
                p.energy = clamp(p.age * 2, 0, 100) + sqrt(abs(p.pos.y))
                """), 21L, 20);
        parity("分支与短踔", tickOnly("""
                if (p.age > 0.5) { p.pos.y = p.pos.y + 1 } else { p.pos.y = p.pos.y - 1 }
                if ((p.energy > 10) and (p.age > 2)) { p.pos.z = p.pos.z * 0.5 }
                """), 22L, 20);
        parity("noise 与 rand", tickOnly("""
                p.pos.x = p.pos.x + noise(p.pos.x, p.pos.y, p.pos.z, step * 3) * 0.1
                p.v.y = p.v.y + rand() * 0.01
                """), 23L, 20);
        parity("切向径向与旋转", tickOnly("""
                let t = tangent(p.axis, p.pos)
                let r = radial(p.axis, p.pos)
                p.v = (t + r) * 0.5 + rotate(p.v, vec(0, 1, 1), p.age * 0.3)
                """), 24L, 20);
        parity("云级变量与能量", tickOnly("""
                let n = p.energy
                p.energy = p.energy + 0.25
                p.pos.x = p.pos.x + n * 0.01
                """), 25L, 20);

        System.out.println("checks=" + checks + " failures=" + failures
                + "  原生执行次数=" + SpellScript.nativeExecutions());
        return failures;
    }

    // ------------------------------------------------------------ 单个用例

    private static void parity(String name, String source, long seed, int steps)
    {
        parity(name, source, seed, steps, null);
    }

    /** @param face 云的朝向（null = 竖直向上，不倾斜） */
    private static void parity(String name, String source, long seed, int steps, double[] face)
    {
        if (source == null)
        {
            check("跳过（脚本不存在）：" + name, true);
            return;
        }
        SpellScript script = SpellScript.compile("parity_" + name, source);
        long before = SpellScript.nativeExecutions();

        double[] javaBits;
        double[] nativeBits;
        NativeVm.setEnabled(false);
        try
        {
            javaBits = after(script, seed, steps, face);
        }
        finally
        {
            NativeVm.setEnabled(true);
        }
        nativeBits = after(script, seed, steps, face);

        long used = SpellScript.nativeExecutions() - before;
        check(name + "：原生真的跑了（" + used + " 段）", used > 0);
        check(name + "：整段逐位一致", sameBits(javaBits, nativeBits));
        if (!sameBits(javaBits, nativeBits))
        {
            System.out.println("       " + describeFirstDiff(javaBits, nativeBits));
        }
    }

    private static double[] after(SpellScript script, long seed, int steps, double[] face)
    {
        SpellCloud c = cloud(script, seed, face);
        for (int i = 0; i < steps; i++)
        {
            c.step();
        }
        return c.snapshot();
    }

    // ------------------------------------------------------------ 工具

    private static final Host ZERO = new Host()
    {
        @Override
        public double field(int elementId, double x, double y, double z)
        {
            return 0.0;
        }

        @Override
        public double light(double x, double y, double z)
        {
            return 1.0;
        }

        @Override
        public int weather()
        {
            return 0;
        }

        @Override
        public long dayTime()
        {
            return 1000L;
        }

        @Override
        public double hereX()
        {
            return 0.0;
        }

        @Override
        public double hereY()
        {
            return 64.0;
        }

        @Override
        public double hereZ()
        {
            return 0.0;
        }

        @Override
        public double lookX()
        {
            return 0.6;
        }

        @Override
        public double lookY()
        {
            return 0.0;
        }

        @Override
        public double lookZ()
        {
            return 0.8;
        }
    };

    private static final String HEAD = "on cast { emit { element: fire, count: 64 } }\n";

    private static String tickOnly(String body)
    {
        return HEAD + "on tick(dt) {\n  for p in particles {\n    "
                + body.replace("\n", "\n    ") + "\n  }\n}\n";
    }

    private static SpellCloud cloud(SpellScript script, long seed, double[] face)
    {
        CastResult r = script.cast(ZERO, seed);
        EmitSpec e = r.emits().get(0);
        if (face == null)
        {
            return new SpellCloud(1, seed, e.elementId(), e.count(), e.x(), e.y(), e.z(),
                    e.lifeSec(), e.shapeId(), e.params(), script, r.vars());
        }
        return new SpellCloud(1, seed, e.elementId(), e.count(), e.x(), e.y(), e.z(),
                e.lifeSec(), e.shapeId(), e.params(), script, r.vars(),
                face[0], face[1], face[2]);
    }

    private static String builtin(String resource)
    {
        try (InputStream in = NativeVmParity.class.getResourceAsStream(resource))
        {
            if (in == null)
            {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean sameBits(double[] a, double[] b)
    {
        if (a.length != b.length)
        {
            return false;
        }
        for (int i = 0; i < a.length; i++)
        {
            if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(b[i]))
            {
                return false;
            }
        }
        return true;
    }

    private static String describeFirstDiff(double[] a, double[] b)
    {
        for (int i = 0; i < Math.min(a.length, b.length); i++)
        {
            if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(b[i]))
            {
                return String.format(Locale.ROOT, "首个差异 field=%d java=%s native=%s",
                        i, Double.toHexString(a[i]), Double.toHexString(b[i]));
            }
        }
        return "长度不同";
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
}
