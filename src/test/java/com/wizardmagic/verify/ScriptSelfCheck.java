package com.wizardmagic.verify;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellRandom;
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
        // 固定朝 +Z：投射物用例（fireball）靠它算飞行方向
        public double lookX() { return 0.0; }
        public double lookY() { return 0.0; }
        public double lookZ() { return 1.0; }
    };

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0 && args[0].equals("perf"))
        {
            // 单独测：一个新 JVM 里只跑火焰风暴，避免多个脚本共用 runLoop 反复 deopt
            perfBreakdown();
            performance();
            return;
        }
        if (args.length > 0 && args[0].equals("million"))
        {
            million();
            return;
        }
        if (args.length > 0 && args[0].equals("prof"))
        {
            profile();
            return;
        }
        if (args.length > 0 && args[0].equals("micro"))
        {
            micro();
            return;
        }
        System.exit(run(args.length == 0 || !args[0].equals("quick")) == 0 ? 0 : 1);
    }

    /**
     * 跑一遍全部检查。
     *
     * @param withPerf 是否包含性能测量（JUnit 壳子传 false，避免门禁又慢又抖）
     * @return 失败条数；给 JUnit 壳子用，不退出 JVM
     */
    public static int run(boolean withPerf) throws Exception
    {
        checks = 0;
        failures = 0;
        syntax();
        castResult();
        determinism();
        equivalence();
        batchEquivalence();
        interpretedEquivalence();
        batchThreadSafety();
        bytecodeCache();
        confinement();
        release();
        frostNova();
        brokenRuntime();
        builtins();
        if (withPerf)
        {
            perfBreakdown();
            performance();
        }

        System.out.println("checks=" + checks + " failures=" + failures);
        return failures;
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
        // 只钉"量级/结构"，不钉具体数值：这几个数会随观感反复调（火光/大小/寿命），
        // 钉死了每次调窗口都得改自检，反而让人不敢调。
        check("粒子数是上千级（" + e.count() + "）",
                e.count() >= 1000 && e.count() <= com.wizardmagic.core.spell.SpellConstants.MAX_CLOUD_PARTICLES);
        check("寿命是正数秒（" + e.lifeSec() + "s）",
                e.lifeSec() > 1.0F
                        && e.lifeSec() <= com.wizardmagic.core.spell.SpellConstants.MAX_LIFE_SEC);
        check("形状是 helix", "helix".equals(e.shapeId()));
        check("高度参数为正（" + e.params().height() + "）", e.params().height() > 0.0);
        check("半径脉动非负（" + e.params().radiusPulse() + "）", e.params().radiusPulse() >= 0.0);
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

    // ------------------------------------------------------------ 批量调度等价性

    /**
     * 批量调度（所有云的粒子区间拼成一条、一次并行）必须和逐朵推进逐位一致。
     *
     * <p>服务端与客户端现在都走批量；切段边界怎么划都不该改变结果，这条守着它。</p>
     */
    private static void batchEquivalence() throws Exception
    {
        String src = load("firestorm");
        java.util.List<SpellCloud> batched = new java.util.ArrayList<>();
        java.util.List<SpellCloud> oneByOne = new java.util.ArrayList<>();
        for (int k = 0; k < 3; k++)
        {
            batched.add(cloud(SpellScript.compile("batch-" + k, src), 9000L + k));
            oneByOne.add(cloud(SpellScript.compile("batch-" + k, src), 9000L + k));
        }
        boolean same = true;
        for (int st = 0; st < 60 && same; st++)
        {
            com.wizardmagic.core.spell.SpellBatch.step(batched);
            for (SpellCloud c : oneByOne)
            {
                c.step();
            }
            for (int k = 0; k < batched.size() && same; k++)
            {
                same = batched.get(k).stateHash() == oneByOne.get(k).stateHash()
                        && batched.get(k).ageSteps() == oneByOne.get(k).ageSteps();
            }
        }
        check("批量调度与逐朵推进逐位一致（3 朵 × 60 步）", same);
    }

    /**
     * 正常施法（编译执行）与调试云（强制解释器）必须逐位一致。
     *
     * <p>调试命令生成的云走解释器当参考实现；只要两边哈希一样，就说明"实战路径"
     * 没有因为换了后端而改变行为。</p>
     */
    private static void interpretedEquivalence() throws Exception
    {
        String src = load("firestorm");
        SpellScript compiled = SpellScript.compile("compiled", src);
        SpellScript debug = SpellScript.compile("debug", src);
        SpellCloud a = cloud(compiled, 4242L);
        SpellCloud b = cloud(debug, 4242L);
        b.setInterpreted(true);
        compiled.prepareFastPath(java.util.Map.of());
        check("正常施法在生成前就编好了寄存器机", compiled.fastPathActive());
        boolean same = true;
        for (int st = 0; st < 40 && same; st++)
        {
            a.step();
            b.step();
            same = a.stateHash() == b.stateHash() && a.ageSteps() == b.ageSteps();
        }
        check("调试云（解释器）与正常施法（编译）逐位一致（40 步）", same);
    }

    /**
     * 批量调度必须线程安全：单人游戏里服务端线程和客户端渲染线程会同时调进来。
     *
     * <p>两个线程各自推进一组完全相同的云，结果必须等于串行推进的参考值。
     * 缓冲是 static 的时候这条会挂（两端互相清空列表 → 哈希分叉）。</p>
     */
    private static void batchThreadSafety() throws Exception
    {
        String src = load("firestorm");
        java.util.List<SpellCloud> refA = new java.util.ArrayList<>();
        java.util.List<SpellCloud> refB = new java.util.ArrayList<>();
        java.util.List<SpellCloud> runA = new java.util.ArrayList<>();
        java.util.List<SpellCloud> runB = new java.util.ArrayList<>();
        for (int k = 0; k < 2; k++)
        {
            refA.add(cloud(SpellScript.compile("thr-a" + k, src), 3100L + k));
            refB.add(cloud(SpellScript.compile("thr-b" + k, src), 3200L + k));
            runA.add(cloud(SpellScript.compile("thr-a" + k, src), 3100L + k));
            runB.add(cloud(SpellScript.compile("thr-b" + k, src), 3200L + k));
        }
        for (int i = 0; i < 40; i++)
        {
            com.wizardmagic.core.spell.SpellBatch.step(refA);
            com.wizardmagic.core.spell.SpellBatch.step(refB);
        }

        Thread ta = new Thread(() ->
        {
            for (int i = 0; i < 40; i++)
            {
                com.wizardmagic.core.spell.SpellBatch.step(runA);
            }
        }, "selfcheck-a");
        Thread tb = new Thread(() ->
        {
            for (int i = 0; i < 40; i++)
            {
                com.wizardmagic.core.spell.SpellBatch.step(runB);
            }
        }, "selfcheck-b");
        ta.start();
        tb.start();
        ta.join();
        tb.join();

        boolean same = true;
        for (int k = 0; k < 2; k++)
        {
            same &= refA.get(k).stateHash() == runA.get(k).stateHash()
                    && refB.get(k).stateHash() == runB.get(k).stateHash();
        }
        check("批量调度线程安全（两线程同时推进，结果与串行一致）", same);
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

    /**
     * 火焰风暴的"测试专用副本"：粒子数压到 512（跑得快）、**寿命钉成 12 秒**。
     *
     * <p>内置脚本的 `count` / `life` 会随观感反复调（实测被调到过 8192 粒 / 240 秒），
     * 而 `confinement` / `release` 这两条断言依赖"释放相在 8.4–11.4 秒"这个时间线。
     * 钉死这两个数会让作者每次调窗口都要改自检；所以这里用正则把它们改成本文件自己的值，
     * 测的还是同一份 tick 逻辑。</p>
     */
    private static SpellScript firestormForTest() throws Exception
    {
        String src = load("firestorm")
                .replaceFirst("count:\\s*\\d+", "count: 512")
                .replaceFirst("life:\\s*[0-9.]+", "life: 12");
        return SpellScript.compile("firestorm-test", src);
    }

    private static void confinement() throws Exception
    {
        SpellCloud c = cloud(firestormForTest(), 99L);
        for (int i = 0; i < 60; i++)
        {
            c.step();
        }
        double r = meanRadius(c);
        check(String.format("6s 后仍被约束成柱（平均半径 %.2f < 4.0）", r), r < 4.0);
    }

    private static void release() throws Exception
    {
        SpellCloud c = cloud(firestormForTest(), 123L);
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
        // tick 里的 emit 现在是编译期拒绝（以前编译通过、首帧才 broken）
        boolean rejected = false;
        String message = "";
        try
        {
            SpellScript.compile("tick_emit",
                    "on cast { emit { element: fire, count: 32 } }\n"
                            + "on tick(dt) { for p in particles { emit { element: fire } } }");
        }
        catch (ScriptException e)
        {
            rejected = true;
            message = e.getMessage();
        }
        check("tick 里 emit 在编译期就被拒（不再首帧 broken）", rejected && message.contains("emit"));

        // 运行期出错仍要能标记 broken：用除零（tick 里 p.age / 0 是运行期才知道的）
        SpellScript div = SpellScript.compile("tick_div0",
                "on cast { emit { element: fire, count: 32 } }\n"
                        + "on tick(dt) { for p in particles { let k = 0\n p.v = p.v * (1 / k) } }");
        SpellCloud c = cloud(div, 1L);
        c.step();
        check("运行期除零把云标记 broken", c.broken);
        check("broken 后 isDead()", c.isDead());
    }

    // ------------------------------------------------------------ 内置脚本

    private static void builtins() throws Exception
    {
        for (String id : new String[]{"firestorm", "frost_nova", "flame_pillar", "meteor", "fireball"})
        {
            SpellScript s = SpellScript.compile(id, load(id));
            check(id + " 编译通过（" + s.nodes() + " 节点）", s.nodes() > 0 && s.nodes() < 20000);
            check(id + " 有 on tick", s.hasTick());
        }

        // meteor 是事件钩子的完整示例（on expire 已按观感去掉；那一阶段的语义由
        // ScriptSpecCheck 的事件用例钉着，不靠这个内置脚本）
        SpellScript meteor = SpellScript.compile("meteor", load("meteor"));
        check("meteor 有 on hit", meteor.hasHit());
        // 快速路径要按"这套云级变量"来编（phase / cx / cy / cz 的类型是 cast 定下来的）
        boolean meteorFast = meteor.prepareFastPath(meteor.cast(ZERO, 1L).vars());
        if (!meteorFast)
        {
            System.out.println("[diag] meteor 退化原因：" + meteor.fastPathReason());
        }
        check("meteor 的 tick 仍能编出快速路径（事件不拖累主循环）", meteorFast);

        // 真跑一段：往下砸 → 撞地 → 整团摊成环 → 环向外扩散
        SpellCloud ball = cloud(meteor, 2024L);
        for (int i = 0; i < ball.count; i++)
        {
            ball.setPos(i, 0.5 + (i % 8) * 0.05, 50.5, 0.5);
            ball.setVel(i, 0.0, -16.0, 0.0);
        }
        ball.setCollider(new com.wizardmagic.core.spell.ParticleCollider(
                (x, y, z) -> y < 50, com.wizardmagic.core.spell.CollisionMode.INELASTIC));
        ball.setEmitsEnabled(true);
        ball.setServerSide(true);
        // 服务端才有伤害出口；离机这里用记录型，验证"打在哪、打多少"
        java.util.List<double[]> hurtCalls = new java.util.ArrayList<>();
        ball.setWorld((x, y, z, r, d) ->
        {
            hurtCalls.add(new double[]{x, y, z, r, d});
            return 1;
        });

        // 第 1 步：穿过地面 → 撞击 → 事件把整团摆成环，并留下一口不继承脚本的烟
        ball.step();
        java.util.List<com.wizardmagic.core.spell.script.EmitSpec> afterHit = ball.drainEmits();
        check("meteor 撞地后进入冲击波阶段（phase=1）",
                ball.scriptVars.get("phase") instanceof com.wizardmagic.core.spell.script.Value.Num p
                        && p.v() == 1.0);
        check("meteor 撞击时只生成一口烟（" + afterHit.size() + " 条）", afterHit.size() == 1);
        check("meteor 的烟不继承脚本（inherit=false，才会往上飘）",
                afterHit.size() == 1 && !afterHit.get(0).inherit());
        check("meteor 的烟是雾元素", afterHit.size() == 1
                && afterHit.get(0).elementId() == com.wizardmagic.core.spell.SpellElements.indexOf("mist"));
        check("meteor 撞地时打出一次 4 格 8 点伤害（" + hurtCalls.size() + " 次）",
                hurtCalls.size() == 1
                        && Math.abs(hurtCalls.get(0)[3] - 4.0) < 1.0E-9
                        && Math.abs(hurtCalls.get(0)[4] - 8.0) < 1.0E-9);
        check("meteor 的伤害落在撞击点上",
                hurtCalls.size() == 1
                        && Math.abs(hurtCalls.get(0)[0] - numVar(ball, "cx")) < 1.0E-9
                        && Math.abs(hurtCalls.get(0)[2] - numVar(ball, "cz")) < 1.0E-9);

        // 环：贴着撞击面，半径落在脚本给的 0.35..1.45 之间
        double cx = numVar(ball, "cx");
        double cy = numVar(ball, "cy");
        double cz = numVar(ball, "cz");
        double near = Double.MAX_VALUE;
        double far = 0.0;
        double offPlane = 0.0;
        for (int i = 0; i < ball.count; i++)
        {
            double dx = ball.x(i) - cx;
            double dy = ball.y(i) - cy;
            double dz = ball.z(i) - cz;
            double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
            near = Math.min(near, r);
            far = Math.max(far, r);
            offPlane = Math.max(offPlane, Math.abs(dy - 0.2)); // 环离地只有一个法向偏移
        }
        check(String.format("meteor 撞地后摊成一个环（半径 %.2f..%.2f）", near, far),
                near > 0.4 && far < 1.5);
        check(String.format("meteor 的环贴在撞击面上（离地偏移误差 %.4f）", offPlane), offPlane < 1.0E-6);

        // 之后每步往外挪：半径变大
        for (int i = 0; i < 5; i++)
        {
            ball.step();
            ball.drainEmits();
        }
        double nearLater = Double.MAX_VALUE;
        for (int i = 0; i < ball.count; i++)
        {
            double dx = ball.x(i) - cx;
            double dy = ball.y(i) - cy;
            double dz = ball.z(i) - cz;
            nearLater = Math.min(nearLater, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        check(String.format("meteor 的冲击波半径在扩大（%.2f → %.2f）", near, nearLater),
                nearLater > near + 1.0);

        for (int i = 0; i < 40; i++)
        {
            ball.step();
            ball.drainEmits();
        }
        boolean finite = true;
        for (int i = 0; i < ball.count; i++)
        {
            finite &= finite(ball.x(i)) && finite(ball.y(i)) && finite(ball.z(i));
        }
        check("meteor 40 步后没有 NaN/Inf", finite);
        check("meteor 没有被事件打成 broken", !ball.broken);

        // 撞竖墙：同一个脚本、同一条式子，环应当立在墙面上（这是"任何角度都能撞"的关键）
        SpellCloud onWall = cloud(meteor, 2025L);
        for (int i = 0; i < onWall.count; i++)
        {
            // 这一步会从 x=4.2 砸到 x=3.45（进墙格），原点 4.2 还在墙外
            onWall.setPos(i, 4.2, 60.0, 0.5);
            onWall.setVel(i, -15.0, 0.0, 0.0);
        }
        onWall.setCollider(new com.wizardmagic.core.spell.ParticleCollider(
                (x, y, z) -> x == 3, com.wizardmagic.core.spell.CollisionMode.INELASTIC));
        onWall.setEmitsEnabled(true);
        onWall.setServerSide(true);
        onWall.step();
        onWall.drainEmits();
        check("meteor 撞墙：整团都参与了撞击（" + onWall.hitCount() + " 粒）",
                onWall.hitCount() == onWall.count);
        double wx = numVar(onWall, "cx");
        double wy = numVar(onWall, "cy");
        double wz = numVar(onWall, "cz");
        double offWall = 0.0;
        double spreadY = 0.0;
        double spreadZ = 0.0;
        for (int i = 0; i < onWall.count; i++)
        {
            offWall = Math.max(offWall, Math.abs(onWall.x(i) - wx - 0.2));
            spreadY = Math.max(spreadY, Math.abs(onWall.y(i) - wy));
            spreadZ = Math.max(spreadZ, Math.abs(onWall.z(i) - wz));
        }
        check(String.format("meteor 撞墙：环立在墙面上（法向偏移误差 %.5f，竖向张角 %.2f）",
                        offWall, spreadY),
                offWall < 1.0E-6 && spreadY > 0.3 && spreadZ > 0.3);

        // ---- 炎爆弹：朝视线飞出去的投射物 ----
        fireball();
    }

    /**
     * 炎爆弹：飞 → 撞墙 → 炸一次（火壳 + 烟 + 伤害）→ 到期不再炸第二次。
     *
     * <p>{@link #ZERO} 的 {@code look()} 固定是 +Z，所以这条用例里火球就是往 +Z 飞的。</p>
     */
    private static void fireball() throws Exception
    {
        SpellScript script = SpellScript.compile("fireball", load("fireball"));
        check("fireball 有 on hit / on expire", script.hasHit() && script.hasExpire());
        boolean fast = script.prepareFastPath(script.cast(ZERO, 1L).vars());
        if (!fast)
        {
            System.out.println("[diag] fireball 退化原因：" + script.fastPathReason());
        }
        check("fireball 的 tick 能编出快速路径（投射物不该掉性能）", fast);

        SpellCloud bolt = cloud(script, 777L);
        java.util.List<double[]> hurts = new java.util.ArrayList<>();
        bolt.setWorld((x, y, z, r, d) ->
        {
            hurts.add(new double[]{x, y, z, r, d});
            return 1;
        });
        bolt.setEmitsEnabled(true);
        bolt.setServerSide(true);
        // 一堵在 z=3 的墙：火球从 z=0 出发，第二步就会撞进去
        bolt.setCollider(new com.wizardmagic.core.spell.ParticleCollider(
                (x, y, z) -> z == 3, com.wizardmagic.core.spell.CollisionMode.INELASTIC));

        int steps = 0;
        while (steps < 10 && numVar(bolt, "phase") == 0.0)
        {
            bolt.step();
            steps++;
        }
        java.util.List<com.wizardmagic.core.spell.script.EmitSpec> boom = bolt.drainEmits();
        check("fireball 飞出去后撞墙引爆（用了 " + steps + " 步）",
                numVar(bolt, "phase") == 2.0 && steps >= 1);
        check("fireball 引爆时生成了火壳 + 烟（" + boom.size() + " 条）", boom.size() == 2);
        check("火壳继承脚本（要向外扩），烟不继承（要往上飘）",
                boom.size() == 2 && boom.get(0).inherit() && !boom.get(1).inherit());
        check("fireball 引爆只打一次范围伤害（半径/伤害在合法范围，具体数值随观感调）",
                hurts.size() == 1 && hurts.get(0)[3] >= 0.5 && hurts.get(0)[3] <= 16.0
                        && hurts.get(0)[4] > 0.0 && hurts.get(0)[4] <= 40.0);
        check("伤害落在墙上（z ≈ 3）",
                hurts.size() == 1 && Math.abs(hurts.get(0)[2] - 3.0) < 1.2);

        // 火球本体撞完还活着，跑到寿命结束再手动触一次 expire：绝不能二次爆炸
        while (!bolt.isDead())
        {
            bolt.step();
            bolt.drainEmits();
        }
        bolt.runExpireHook();
        check("fireball 撞过墙之后到期不再炸第二次（armed 生效）",
                bolt.drainEmits().isEmpty() && hurts.size() == 1);

        // 飞到天上没撞到东西：到期在半空炸
        SpellCloud air = cloud(script, 778L);
        java.util.List<double[]> airHurts = new java.util.ArrayList<>();
        air.setWorld((x, y, z, r, d) ->
        {
            airHurts.add(new double[]{x, y, z, r, d});
            return 1;
        });
        air.setEmitsEnabled(true);
        air.setServerSide(true);
        while (!air.isDead())
        {
            air.step();
            air.drainEmits();
        }
        double[] mid = air.centroid();
        air.runExpireHook();
        check("fireball 没撞到东西就在半空炸（" + air.drainEmits().size() + " 条子云）",
                airHurts.size() == 1);
        check("半空炸的伤害落在粒子质心",
                airHurts.size() == 1 && Math.abs(airHurts.get(0)[1] - mid[1]) < 1.0E-6
                        && Math.abs(airHurts.get(0)[2] - mid[2]) < 1.0E-6);

        // ---- 碰到生物：只用 hurt(0) 打那一只，炸完立刻结束 ----
        SpellCloud bolt2 = cloud(script, 901L);
        TouchWorld world = new TouchWorld();
        world.report = java.util.List.of(new com.wizardmagic.core.spell.script.SpellWorld.Touch(
                31L, "minecraft:zombie", 0.0, 65.0, 2.0, 20.0, 0.0, 65.2, 2.0));
        bolt2.setWorld(world);
        bolt2.setEmitsEnabled(true);
        bolt2.setServerSide(true);
        bolt2.step();
        check("fireball 碰到生物：hurt(0) 只打了那一只（id=31，伤害随观感调）",
                world.single.size() == 1 && world.single.get(0)[0] == 31.0
                        && world.single.get(0)[1] > 0.0 && world.single.get(0)[1] <= 40.0);
        java.util.List<com.wizardmagic.core.spell.script.EmitSpec> touchBoom = bolt2.drainEmits();
        check("fireball 碰到生物后照样炸开（" + touchBoom.size() + " 条子云）", touchBoom.size() == 2);
        check("碰到生物时不会再用范围伤害重复结算", world.areas.isEmpty());
        check("fireball 碰到生物后立刻结束（end）", bolt2.ended() && bolt2.isDead());
        bolt2.runExpireHook();
        check("end() 之后到期不再炸（伤害仍只有那一次）", world.single.size() == 1);
    }

    /** 记录型世界出口：能报告"这一步碰到了谁"，也能单独打某个实体（on touch 用例） */
    private static final class TouchWorld implements com.wizardmagic.core.spell.script.SpellWorld
    {
        final java.util.List<double[]> areas = new java.util.ArrayList<>();
        final java.util.List<double[]> single = new java.util.ArrayList<>();
        java.util.List<Touch> report = java.util.List.of();

        @Override
        public int hurt(double x, double y, double z, double radius, double damage)
        {
            areas.add(new double[]{x, y, z, radius, damage});
            return 1;
        }

        @Override
        public int hurtEntity(long entityId, double damage)
        {
            single.add(new double[]{entityId, damage});
            return 1;
        }

        @Override
        public java.util.List<Touch> touches(SpellCloud cloud)
        {
            java.util.List<Touch> out = report;
            report = java.util.List.of();
            return out;
        }
    }

    /** 读一个 num 型云级变量（脚本自检里看 phase / 撞击点用） */
    private static double numVar(SpellCloud cloud, String name)
    {
        Object v = cloud.scriptVars.get(name);
        if (v instanceof com.wizardmagic.core.spell.script.Value.Num n)
        {
            return n.v();
        }
        throw new IllegalStateException("云级变量 " + name + " 不是 num：" + v);
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

    // ------------------------------------------------------------ 百万粒子压测

    /**
     * 突破 {@code MAX_CLOUDS_PER_LEVEL}/{@code MAX_SIM_PARTICLES_PER_LEVEL} 的压测。
     *
     * <p>不是“正常会不会这么跑”，而是量「线性放大的斜率」：单粒成本是否稳定、
     * 单朵与多朵有没有额外的固定开销。</p>
     */
    private static void million() throws Exception
    {
        System.out.println("[million] 本机单步基线（3000 粒）先跑一遍，用于换算斜率");
        SpellCloud base = cloud(SpellScript.compile("firestorm", load("firestorm")), 7000L);
        for (int i = 0; i < 100; i++)
        {
            base.step();
        }
        long bt = System.nanoTime();
        for (int i = 0; i < 20; i++)
        {
            base.step();
        }
        double baseMs = (System.nanoTime() - bt) / 1.0E6 / 20.0;
        System.out.printf(java.util.Locale.ROOT,
                "[million] %d 粒：%.3f ms/步（%.1f ns/粒）%n",
                base.count, baseMs, baseMs * 1.0E6 / base.count);

        runScale("单朵 1,000,000", 1, 1_000_000);
        runScale("245 朵 × 4096 = 1,003,520", 245, 4096);
        // 新上限下的满池 = 524,288，两种凑法都测：64 朵 × 8192（单朵上限）与 128 朵 × 4096
        runScale("64 朵 × 8192 = 524,288（新满池·大云）", 64, 8192);
        runScale("128 朵 × 4096 = 524,288（新满池·多云）", 128, 4096);
    }

    private static void runScale(String label, int clouds, int perCloud) throws Exception
    {
        // 用正则替换 count，别再钉 "count: 3000"：内置脚本的粒子数会随观感调整
        String src = load("firestorm").replaceFirst("count:\\s*\\d+", "count: " + perCloud);
        SpellScript script = SpellScript.compile("firestorm-" + perCloud, src);

        java.util.List<SpellCloud> list = new java.util.ArrayList<>(clouds);
        long buildStart = System.nanoTime();
        for (int k = 0; k < clouds; k++)
        {
            SpellCloud c = cloud(script, 5000L + k);
            list.add(c);
        }
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000L;
        int actual = list.get(0).count;
        if (actual != perCloud)
        {
            System.out.printf(java.util.Locale.ROOT,
                    "[million] %s：跳过（单朵上限 %d，需要 -Dwizardmagic.spell.maxParticles=%d）%n",
                    label, actual, perCloud);
            return;
        }
        long total = (long) actual * clouds;

        // 预热：让 C2 把 runLoop 编完（1M 时 3 步就够）
        for (int w = 0; w < 3; w++)
        {
            com.wizardmagic.core.spell.SpellBatch.step(list);
        }
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++)
        {
            long t0 = System.nanoTime();
            for (int s = 0; s < 5; s++)
            {
                // 和服务端同一条路径：所有云的粒子区间拼成一条，一次并行调度
                com.wizardmagic.core.spell.SpellBatch.step(list);
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1.0E6 / 5.0);
        }

        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        System.out.printf(java.util.Locale.ROOT,
                "[million] %s：%.1f ms/步（%.0f ns/粒），内存 %d MB，建云 %d ms，"
                        + "占 10 Hz 单步预算 %.0f%%%n",
                label, best, best * 1.0E6 / total, heapMb, buildMs, best / 100.0 * 100.0);
    }

    // ------------------------------------------------------------ 采样剖面

    /**
     * 给 JFR/async-profiler 采样用的纯负载：一直跑火焰风暴，跑满指定毫秒数。
     *
     * <p>{@code -Dwizardmagic.prof.ms=15000} 控制时长；不打印结果，只提供热点。</p>
     */
    private static void profile() throws Exception
    {
        SpellScript script = SpellScript.compile("firestorm", load("firestorm"));
        SpellCloud c = cloud(script, 1234L);
        long ms = Long.getLong("wizardmagic.prof.ms", 15000L);
        long steps = 0L;
        long start = System.nanoTime();
        long deadline = start + ms * 1_000_000L;
        while (System.nanoTime() < deadline)
        {
            for (int k = 0; k < 50; k++)
            {
                c.step();
                steps++;
            }
        }
        double msPerStep = (System.nanoTime() - start) / 1.0E6 / steps;
        System.out.printf(java.util.Locale.ROOT,
                "[prof] %d 步，%.3f ms/步（%.0f ns/粒）%n", steps, msPerStep, msPerStep * 1.0E6 / c.count);
    }

    // ------------------------------------------------------------ 微基准

    /** 每颗函数的单次成本；结果写入 volatile，避免被优化掉 */
    private static volatile double SINK;

    private static void micro() throws Exception
    {
        final int n = 5_000_000;
        int fire = com.wizardmagic.core.spell.SpellElements.indexOf("fire");
        ShapeParams p = new ShapeParams(2.6, 0.35, 8.0, 1.2, 6, 1.2, 2.2, 1.0, 0.18, 2.0, 2.5);
        double[] prep = new double[3000 * 2];
        com.wizardmagic.api.spell.Shapes.HELIX.prepare(3000, p, 7L, prep);
        double[] out = new double[3];
        long seed = 1234L;

        System.out.println("[micro] cores=" + Runtime.getRuntime().availableProcessors()
                + " parallel=" + com.wizardmagic.core.spell.SpellParallel.enabled()
                + " threads=" + com.wizardmagic.core.spell.SpellParallel.threads());
        // 纯分发开销：空任务切成 3 段
        int reps = 500;
        long dispatch = System.nanoTime();
        for (int i = 0; i < reps; i++)
        {
            com.wizardmagic.core.spell.SpellParallel.forEachRange(3000, (f, to) -> SINK = f + to);
        }
        System.out.printf(java.util.Locale.ROOT, "[micro] 空分发      %.1f us/次%n",
                (System.nanoTime() - dispatch) / 1000.0 / reps);

        System.out.printf(java.util.Locale.ROOT, "[micro] unit        %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                s += SpellRandom.unit(seed, i, 7);
            }
            return s;
        }));
        System.out.printf(java.util.Locale.ROOT, "[micro] sin(表)     %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                s += com.wizardmagic.api.spell.SpellMath.sin(i * 0.013);
            }
            return s;
        }));
        System.out.printf(java.util.Locale.ROOT, "[micro] centerAt    %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                com.wizardmagic.api.spell.Shapes.HELIX.centerAt(i % 3000, 3000, p, seed, 1.25, prep, out);
                s += out[0];
            }
            return s;
        }));
        System.out.printf(java.util.Locale.ROOT, "[micro] targetR     %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                s += com.wizardmagic.api.spell.Shapes.HELIX.targetRadiusAt(i % 3000, 3000, p, seed, 1.25, prep);
            }
            return s;
        }));
        System.out.printf(java.util.Locale.ROOT, "[micro] noise       %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                s += com.wizardmagic.core.spell.SpellNoise.value3(seed, i * 0.37, 2.5, 3.5, 7);
            }
            return s;
        }));
        System.out.printf(java.util.Locale.ROOT, "[micro] exp         %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                s += Math.exp(-3.0 * 0.1);
            }
            return s;
        }));
        System.out.printf(java.util.Locale.ROOT, "[micro] sqrt        %.2f ns%n", bench(n, () -> {
            double s = 0;
            for (int i = 0; i < n; i++)
            {
                s += Math.sqrt(i * 0.001);
            }
            return s;
        }));

        // 最小脚本（只有 pos += v*dt）与完整火焰风暴的对比，用来估 VM 每指令成本
        SpellScript tiny = SpellScript.compile("tiny",
                "on cast { emit { element: fire, count: 3000, life: 12 } }\n"
                        + "on tick(dt) { for p in particles { p.pos = p.pos + p.v * dt } }");
        System.out.printf(java.util.Locale.ROOT, "[micro] 最小脚本     %.1f ns/粒%n",
                nsPerParticle(tiny, 3000L));
        System.out.printf(java.util.Locale.ROOT, "[micro] 火焰风暴     %.1f ns/粒%n",
                nsPerParticle(SpellScript.compile("firestorm", load("firestorm")), 3000L));
        System.out.println("[micro] fire=" + fire);
    }

    private static double bench(int n, java.util.function.DoubleSupplier body)
    {
        // 预热 3 轮
        for (int w = 0; w < 3; w++)
        {
            SINK = body.getAsDouble();
        }
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++)
        {
            long t = System.nanoTime();
            SINK = body.getAsDouble();
            best = Math.min(best, System.nanoTime() - t);
        }
        return best / (double) n;
    }

    private static double nsPerParticle(SpellScript script, long seed) throws Exception
    {
        SpellCloud c = cloud(script, seed);
        for (int i = 0; i < 500; i++)
        {
            c.step();
        }
        SpellCloud d = cloud(script, seed);
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++)
        {
            long t = System.nanoTime();
            for (int i = 0; i < 50; i++)
            {
                d.step();
            }
            best = Math.min(best, System.nanoTime() - t);
        }
        return best / 50.0 / d.count;
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
