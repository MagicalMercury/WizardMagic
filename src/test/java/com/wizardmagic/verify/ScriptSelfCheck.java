package com.wizardmagic.verify;

import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.script.CastResult;
import com.wizardmagic.core.spell.script.EmitSpec;
import com.wizardmagic.core.spell.script.Host;
import com.wizardmagic.core.spell.script.ScriptException;
import com.wizardmagic.core.spell.script.SpellScript;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 行为语言的自检：不启动 Minecraft，直接编译/执行 .spell 文件，
 * 验证语法、错误定位、施法结果、双端一致性、物理方向与性能。
 *
 * <p>运行（项目根目录）：</p>
 * <pre>
 * javac -cp "build/classes/java/main;src/main/resources;gson.jar" -d verify/spell/out verify/spell/ScriptSelfCheck.java
 * java  -cp "verify/spell/out;build/classes/java/main;src/main/resources;gson.jar" ScriptSelfCheck
 * </pre>
 */
public final class ScriptSelfCheck
{
    private static final Host ZERO = new Host()
    {
        public double field(int elementId, double x, double y, double z) { return 0.0; }
        public double light(double x, double y, double z) { return 1.0; }
        public int weather() { return 0; }
        public long dayTime() { return 1000L; }
        public double hereX() { return 0.0; }
        public double hereY() { return 64.0; }
        public double hereZ() { return 0.0; }
    };

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0 && args[0].equals("perf"))
        {
            // 单独测：一个新 JVM 里只跑火焰风暴，避免多个脚本共用 runLoop 反复 deopt
            performance();
            return;
        }
        // quick：跳过性能测量，给 gradle 的 spellScriptCheck 门禁用
        boolean quick = args.length > 0 && args[0].equals("quick");
        syntax();
        castResult();
        determinism();
        equivalence();
        bytecodeCache();
        confinement();
        release();
        frostNova();
        brokenRuntime();
        builtins();
        if (!quick)
        {
            perfBreakdown();
            performance();
        }

        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures != 0)
        {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 语法与诊断

    private static void syntax() throws Exception
    {
        SpellScript ok = SpellScript.compile("ok", load("firestorm"));
        check("火焰风暴能编译", ok.nodes() > 0 && ok.hasTick());

        try
        {
            SpellScript.compile("bad", "on cast {\n  let x =\n}\n");
            check("缺表达式应报错", false);
        }
        catch (ScriptException e)
        {
            check("缺表达式应报错", true);
            check("错误行号指向第 3 行（收到 " + e.line + "）", e.line == 3);
        }

        try
        {
            SpellScript bad = SpellScript.compile("bad", "on cast { emit { element: not_an_element } }");
            bad.cast(ZERO, 1L);
            check("未知元素应报错", false);
        }
        catch (ScriptException e)
        {
            check("未知元素应报错", e.getMessage().contains("not_an_element"));
        }

        try
        {
            SpellScript.compile("bad", "on cast { emit { element: fire } }\non tick(dt) { let x = field(fire) }");
            check("tick 里读世界应在编译期被拒", false);
        }
        catch (ScriptException e)
        {
            check("tick 里读世界应在编译期被拒", e.getMessage().contains("on cast"));
        }

        try
        {
            SpellScript.compile("bad", "on tick(dt) { }");
            check("缺 on cast 应报错", false);
        }
        catch (ScriptException e)
        {
            check("缺 on cast 应报错", e.getMessage().contains("on cast"));
        }

        // 类型错误在运行期才报：cast 里把 vec3 和 num 相加
        SpellScript typed = SpellScript.compile("typed",
                "on cast { let x = vec(1, 2, 3) + 1  emit { element: fire } }");
        try
        {
            typed.cast(ZERO, 1L);
            check("类型错误应报错", false);
        }
        catch (ScriptException e)
        {
            check("类型错误应报错", e.getMessage().contains("vec3"));
        }
    }

    // ------------------------------------------------------------ 施法结果

    private static void castResult() throws Exception
    {
        SpellScript script = SpellScript.compile("firestorm", load("firestorm"));
        CastResult r = script.cast(ZERO, 777L);
        check("cast 产出 1 朵云", r.emits().size() == 1);
        EmitSpec e = r.emits().get(0);
        check("元素是 fire", "fire".equals(nameOf(e.elementId())));
        check("粒子数 3000", e.count() == 3000);
        check("寿命 12s", Math.abs(e.lifeSec() - 12.0F) < 1.0E-6);
        check("形状是 helix", "helix".equals(e.shapeId()));
        check("高度参数 8", Math.abs(e.params().height() - 8.0) < 1.0E-9);
        check("半径脉动 0.18", Math.abs(e.params().radiusPulse() - 0.18) < 1.0E-9);
        check("施法原点 = here()", Math.abs(e.y() - 64.0) < 1.0E-9);
    }

    // ------------------------------------------------------------ 确定性

    private static void determinism() throws Exception
    {
        SpellScript script = SpellScript.compile("firestorm", load("firestorm"));
        SpellCloud a = cloud(script, 4242L);
        SpellCloud b = cloud(script, 4242L);
        boolean same = true;
        for (int step = 0; step < 120 && same; step++)
        {
            a.step();
            b.step();
            same = a.stateHash() == b.stateHash();
        }
        check("同一 seed 两次运行逐位一致（120 步）", same);
        check("120 步后没有 NaN/Inf", finite(a));
        check("脚本云没有被标记 broken", !a.broken);

        // 诊断：2 秒时的形态与绕轴角速度（人眼看"转不转"就靠这个数）
        SpellCloud probe = cloud(script, 4242L);
        for (int i = 0; i < 20; i++)
        {
            probe.step();
        }
        double omega = meanOmega(probe);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[diag] 2s：meanR=%.3f meanSpeed=%.3f 平均角速度=%.3f",
                meanRadius(probe), meanSpeed(probe), omega));
        check("粒子绕轴旋转（角速度 > 0.3 rad/s）", omega > 0.3);
    }

    /** 平均绕轴角速度 ω = (r × v)_y / |r|² */
    private static double meanOmega(SpellCloud c)
    {
        double t = c.ageSteps() * SpellConstants.DT;
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < c.count; i++)
        {
            double[] a = c.axisAt(i, t);
            double rx = c.x(i) - a[0];
            double rz = c.z(i) - a[2];
            double d2 = rx * rx + rz * rz;
            if (d2 < 1.0E-6)
            {
                continue;
            }
            sum += (rx * c.velZ(i) - rz * c.velX(i)) / d2;
            n++;
        }
        return n == 0 ? 0.0 : sum / n;
    }

    // ------------------------------------------------------------ 字节码 vs 解释器

    private static void equivalence() throws Exception
    {
        String[] ids = {"firestorm", "frost_nova", "flame_pillar"};
        for (String id : ids)
        {
            SpellScript ref = SpellScript.compile(id, load(id));
            SpellScript opt = SpellScript.compile(id, load(id));
            // 先用一朵探针云触发快速路径编译，再换新云做对照
            SpellCloud probe = cloud(opt, 20261112L);
            opt.tick(probe);
            check(id + " 快速路径已启用", opt.fastPathActive());

            SpellCloud a = cloud(ref, 20261112L);
            SpellCloud b = cloud(opt, 20261112L);
            int steps = id.equals("firestorm") ? 120 : 60;
            boolean same = true;
            for (int st = 0; st < steps && same; st++)
            {
                ref.tickInterpreted(a);
                a.setAgeSteps(a.ageSteps() + 1);
                b.step();
                same = a.stateHash() == b.stateHash();
            }
            check(id + " 字节码与解释器逐位一致（" + steps + " 步）", same);
        }
    }

    // ------------------------------------------------------------ 字节码缓存（.spellc）

    /**
     * 落盘 → 读回 → 直接用缓存跑，必须与解释器逐位一致。
     * 还要保证坏文件（改一个字节 / 截断 / 源码变了）不会被当成有效缓存。
     */
    private static void bytecodeCache() throws Exception
    {
        for (String id : new String[]{"firestorm", "frost_nova", "flame_pillar"})
        {
            String src = load(id);
            SpellScript writer = SpellScript.compile(id, src);
            check(id + " 能编出快速路径（缓存前提）", writer.prepareFastPath(java.util.Map.of()));
            byte[] blob = writer.fastBytecode();
            check(id + " 编出了可落盘的字节码（" + (blob == null ? 0 : blob.length) + " B）",
                    blob != null && blob.length > 48);

            // 读回：新脚本 + 只用缓存，不经过代码生成
            SpellScript reader = SpellScript.compile(id, src);
            check(id + " 缓存被采纳", reader.adoptFastBytecode(blob));
            check(id + " 采纳后立刻是快速路径", reader.fastPathActive());

            // 跑起来对比解释器
            SpellScript refScript = SpellScript.compile(id, src);
            SpellCloud a = cloud(refScript, 424242L);
            SpellCloud b = cloud(reader, 424242L);
            int steps = id.equals("firestorm") ? 60 : 30;
            boolean same = true;
            for (int st = 0; st < steps && same; st++)
            {
                refScript.tickInterpreted(a);
                a.setAgeSteps(a.ageSteps() + 1);
                b.step();
                same = a.stateHash() == b.stateHash();
            }
            check(id + " 缓存版与解释器逐位一致（" + steps + " 步）", same);

            // 坏字节：校验和应该挡住
            byte[] tampered = blob.clone();
            tampered[tampered.length / 2] ^= 0x40;
            check(id + " 改动字节后缓存被拒", !SpellScript.compile(id, src).adoptFastBytecode(tampered));

            // 截断
            byte[] cut = java.util.Arrays.copyOf(blob, blob.length / 2);
            check(id + " 截断的缓存被拒", !SpellScript.compile(id, src).adoptFastBytecode(cut));

            // 源码变了：哈希对不上
            SpellScript moved = SpellScript.compile(id, src + "\n# 改了一行注释\n");
            check(id + " 源码变更后缓存被拒", !moved.adoptFastBytecode(blob));

            // 走一遍真实文件（和存档里同样的命名），确认读写没问题
            if (id.equals("firestorm"))
            {
                Path dir = Files.createTempDirectory("wm-spellc");
                Path file = dir.resolve(id + ".spellc");
                Files.write(file, blob);
                SpellScript fromFile = SpellScript.compile(id, src);
                check("从 .spellc 文件读回的缓存被采纳（" + Files.size(file) + " B）",
                        fromFile.adoptFastBytecode(Files.readAllBytes(file)) && fromFile.fastPathActive());
                Files.deleteIfExists(file);
                Files.deleteIfExists(dir);
            }
        }
    }

    // ------------------------------------------------------------ 物理方向

    private static void confinement() throws Exception
    {
        SpellScript script = SpellScript.compile("firestorm", load("firestorm"));
        SpellCloud c = cloud(script, 99L);
        for (int i = 0; i < 60; i++)
        {
            c.step();
        }
        double r = meanRadius(c);
        check(String.format("6s 后仍被约束成柱（平均半径 %.2f < 4.0）", r), r < 4.0);
    }

    private static void release() throws Exception
    {
        SpellScript script = SpellScript.compile("firestorm", load("firestorm"));
        SpellCloud c = cloud(script, 123L);
        for (int i = 0; i < 84; i++)
        {
            c.step();
        }
        double before = meanRadius(c);
        for (int i = 84; i < 114; i++)
        {
            c.step();
        }
        double after = meanRadius(c);
        check(String.format("释放后显著外扩（%.2f → %.2f）", before, after), after > before * 1.3);
        check("释放后仍是有限值", finite(c));
    }

    private static void frostNova() throws Exception
    {
        SpellScript script = SpellScript.compile("frost_nova", load("frost_nova"));
        SpellCloud c = cloud(script, 55L);
        double r0 = meanRadius(c);
        for (int i = 0; i < 12; i++)
        {
            c.step();
        }
        double r1 = meanRadius(c);
        double speedEarly = meanSpeed(c);
        for (int i = 12; i < 36; i++)
        {
            c.step();
        }
        double speedLate = meanSpeed(c);
        check(String.format("冰霜新星向外扩散（%.2f → %.2f）", r0, r1), r1 > r0 + 0.5);
        check(String.format("摩擦吃掉速度（%.2f → %.2f）", speedEarly, speedLate), speedLate < speedEarly);
        check("冰霜新星没有 NaN", finite(c));
    }

    // ------------------------------------------------------------ 运行时错误

    private static void brokenRuntime() throws Exception
    {
        SpellScript script = SpellScript.compile("tick_emit",
                "on cast { emit { element: fire, count: 32 } }\n"
                        + "on tick(dt) { for p in particles { emit { element: fire } } }");
        SpellCloud c = cloud(script, 1L);
        c.step();
        check("tick 里 emit 在运行期被拒（P2 未实现）", c.broken);
        check("broken 后 isDead()", c.isDead());
    }

    // ------------------------------------------------------------ 内置脚本

    private static void builtins() throws Exception
    {
        for (String id : new String[]{"firestorm", "frost_nova", "flame_pillar"})
        {
            SpellScript s = SpellScript.compile(id, load(id));
            check(id + " 编译通过（" + s.nodes() + " 节点）", s.nodes() > 0 && s.nodes() < 20000);
            check(id + " 有 on tick", s.hasTick());
        }
    }

    // ------------------------------------------------------------ 性能实测

    /** 拆开看钱花在哪：空循环 / 无噪声 / 完整 */
    private static void perfBreakdown() throws Exception
    {
        String loopOnly = "on cast { emit { element: fire, count: 3000, life: 12 } }\n"
                + "on tick(dt) { for p in particles { p.energy = p.energy } }";
        String noNoise = "on cast { emit { element: fire, count: 3000, life: 12,"
                + " shape: helix { radius: 2.6, thickness: 0.35, height: 8, turns: 1.2, length: 1.2,"
                + " amplitude: 2.2, frequency: 1.0, radiusPulse: 0.18, radiusWave: 2.0, radiusSpeed: 2.5 } } }\n"
                + "on tick(dt) {\n"
                + " let rel = smoothstep(0.73, 1.0, time / life)\n"
                + " let hold = 8.0 * (1 - rel)\n"
                + " let spin = 3.5 * (1 - 0.75 * rel)\n"
                + " let burst = 28.0 * rel\n"
                + " let damp = max(0, 1 - 0.05 * dt)\n"
                + " let g = gravity(fire)\n"
                + " for p in particles {\n"
                + "  let d = len_xz(p.pos - p.axis)\n"
                + "  let t = tangent(p.axis, p.pos)\n"
                + "  let rd = radial(p.axis, p.pos)\n"
                + "  let blend = 1 - exp(-3 * dt)\n"
                + "  if (d > 0.0001) {\n"
                + "   let vt = dot(p.v, t)\n"
                + "   let vr = dot(p.v, rd)\n"
                + "   vt = vt + (spin * d - vt) * blend\n"
                + "   let aR = -vt * vt / d + burst\n"
                + "   if (hold > 0 and p.targetR > 0) { aR = aR - hold * (d - p.targetR) - 2 * sqrt(hold) * vr }\n"
                + "   vr = vr + aR * dt\n"
                + "   p.v = t * vt + rd * vr\n"
                + "  }\n"
                + "  p.v = p.v * damp\n"
                + "  p.v = p.v + vec(0, -g * 9.8 * dt, 0)\n"
                + "  p.v = p.v + vec(0, -hold * (p.pos.y - p.anchor.y) * dt, 0)\n"
                + "  p.v = p.v + vec(0, 1.2 * dt, 0)\n"
                + "  p.pos = p.pos + p.v * dt\n"
                + " } }";

        SpellScript a = SpellScript.compile("loop_only", loopOnly);
        SpellScript b = SpellScript.compile("no_noise", noNoise);
        double msLoop = msPerStep(a);
        double msNoNoise = msPerStep(b);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[perf] 拆分：空循环 %.3f ms，同体型去噪声 %.3f ms，完整 %.3f ms",
                msLoop, msNoNoise, msPerStep(SpellScript.compile("firestorm", load("firestorm")))));
        check("空循环也要能跑（>0）", msLoop > 0.0);
    }

    private static double msPerStep(SpellScript script) throws Exception
    {
        SpellCloud warm = cloud(script, 777L);
        // 预热到 JIT 稳定：runLoop 是每步调用一次，需要足够多的调用/回边才会被 C2 编译
        for (int i = 0; i < 300; i++)
        {
            warm.step();
        }
        SpellCloud c = cloud(script, 777L);
        long t = System.nanoTime();
        int steps = 100;
        for (int i = 0; i < steps; i++)
        {
            c.step();
        }
        return (System.nanoTime() - t) / 1.0E6 / steps;
    }

    private static void performance() throws Exception
    {
        int steps = 100;
        SpellScript fastScript = SpellScript.compile("firestorm", load("firestorm"));
        SpellCloud warm = cloud(fastScript, 2024L);
        for (int i = 0; i < 800; i++)
        {
            warm.step();
        }
        SpellCloud fast = cloud(fastScript, 2024L);
        long t0 = System.nanoTime();
        for (int i = 0; i < steps; i++)
        {
            fast.step();
        }
        double fastMs = (System.nanoTime() - t0) / 1.0E6 / steps;
        check("快速路径已启用（性能测量有效）", fastScript.fastPathActive());

        SpellScript ref = SpellScript.compile("firestorm-ref", load("firestorm"));
        SpellCloud warmRef = cloud(ref, 2024L);
        for (int i = 0; i < 20; i++)
        {
            ref.tickInterpreted(warmRef);
            warmRef.setAgeSteps(warmRef.ageSteps() + 1);
        }
        SpellCloud slow = cloud(ref, 2024L);
        long t1 = System.nanoTime();
        for (int i = 0; i < steps; i++)
        {
            ref.tickInterpreted(slow);
            slow.setAgeSteps(slow.ageSteps() + 1);
        }
        double refMs = (System.nanoTime() - t1) / 1.0E6 / steps;

        System.out.println(String.format(java.util.Locale.ROOT,
                "[perf] firestorm 3000 粒：解释器 %.3f ms/步 → 字节码 %.3f ms/步（%.1f×），"
                        + "10 Hz 下单核 %.1f%%",
                refMs, fastMs, refMs / fastMs, fastMs / 100.0 * 100.0));
        check("字节码路径比解释器快", fastMs < refMs);
    }

    // ------------------------------------------------------------ 工具

    private static SpellCloud cloud(SpellScript script, long seed)
    {
        CastResult r = script.cast(ZERO, seed);
        if (r.emits().isEmpty())
        {
            throw new IllegalStateException("脚本没有 emit");
        }
        EmitSpec e = r.emits().get(0);
        return new SpellCloud(1, seed, e.elementId(), e.count(), e.x(), e.y(), e.z(),
                e.lifeSec(), e.shapeId(), e.params(), script, r.vars());
    }

    private static double meanRadius(SpellCloud c)
    {
        double t = c.ageSteps() * SpellConstants.DT;
        double sum = 0.0;
        for (int i = 0; i < c.count; i++)
        {
            double[] a = c.axisAt(i, t);
            double dx = c.x(i) - a[0];
            double dz = c.z(i) - a[2];
            sum += Math.sqrt(dx * dx + dz * dz);
        }
        return sum / c.count;
    }

    private static double meanSpeed(SpellCloud c)
    {
        double sum = 0.0;
        for (int i = 0; i < c.count; i++)
        {
            double vx = c.velX(i);
            double vy = c.velY(i);
            double vz = c.velZ(i);
            sum += Math.sqrt(vx * vx + vy * vy + vz * vz);
        }
        return sum / c.count;
    }

    private static boolean finite(SpellCloud c)
    {
        for (int i = 0; i < c.count; i++)
        {
            if (!finite(c.x(i)) || !finite(c.y(i)) || !finite(c.z(i))
                    || !finite(c.velX(i)) || !finite(c.velY(i)) || !finite(c.velZ(i)))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean finite(double v)
    {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String nameOf(int id)
    {
        return com.wizardmagic.core.spell.SpellElements.id(id);
    }

    private static String load(String id) throws Exception
    {
        // 走类路径（主资源），不依赖当前工作目录
        try (java.io.InputStream in = ScriptSelfCheck.class.getResourceAsStream(
                "/data/wizardmagic/spells/" + id + ".spell"))
        {
            if (in == null)
            {
                throw new IllegalStateException("类路径上没有 /data/wizardmagic/spells/" + id + ".spell");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void check(String name, boolean ok)
    {
        checks++;
        if (!ok)
        {
            failures++;
            System.out.println("[FAIL] " + name);
        }
        else
        {
            System.out.println("[ok] " + name);
        }
    }
}
