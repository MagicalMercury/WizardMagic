package com.wizardmagic.verify;

import com.wizardmagic.core.spell.CollisionMode;
import com.wizardmagic.core.spell.ParticleCollider;
import com.wizardmagic.core.spell.SolidOracle;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellElements;
import com.wizardmagic.core.spell.script.CastResult;
import com.wizardmagic.core.spell.script.EmitSpec;
import com.wizardmagic.core.spell.script.Host;
import com.wizardmagic.core.spell.script.SpellWorld;
import com.wizardmagic.core.spell.script.ScriptException;
import com.wizardmagic.core.spell.script.SpellScript;
import com.wizardmagic.core.spell.script.Value;

import java.util.Map;

/**
 * 脚本语言<b>正确性</b>专项自检：每条用例都在问「这门语言的实际行为是不是我以为的那样」。
 *
 * <p>和 {@link ScriptSelfCheck} 的分工：那边测「整套东西能不能用」（内置脚本、双端一致、
 * 字节码缓存、性能），这边只盯语言本身——运算符、短路、类型、内建函数、emit 校验、
 * 编译期检查、运行期定位，以及<b>两个后端（解释器 / 寄存器机）和字节码缓存的结果必须
 * 逐位相同</b>。</p>
 *
 * <p>写用例的姿势：所有片段都套同一个壳（{@code on cast} 发一朵云 + {@code on tick} 一个
 * 粒子循环），用 {@link #num} / {@link #flag} / {@link #energyAfter} 把表达式的值写进
 * 粒子状态再读回来——脚本语言没有 print，这是唯一干净的观测方式。</p>
 */
public final class ScriptSpecCheck
{
    /** 没有世界的宿主：任何 world 查询都给常量，保证用例可复现 */
    private static final Host ZERO = new Host()
    {
        public double field(int elementId, double x, double y, double z) { return 0.0; }
        public double light(double x, double y, double z) { return 1.0; }
        public int weather() { return 0; }
        public long dayTime() { return 1000L; }
        public double hereX() { return 0.0; }
        public double hereY() { return 64.0; }
        public double hereZ() { return 0.0; }
        public double lookX() { return 0.6; }
        public double lookY() { return 0.0; }
        public double lookZ() { return 0.8; }
    };

    private static final String HEAD = "on cast { emit { element: fire, count: 32 } }\n";

    private static int checks;
    private static int failures;

    public static void main(String[] args)
    {
        System.exit(run() == 0 ? 0 : 1);
    }

    /**
     * 跑一遍全部用例。
     *
     * @return 失败条数；给 JUnit 壳子用，不退出 JVM
     */
    public static int run()
    {
        checks = 0;
        failures = 0;
        try
        {
            literals();
            operators();
            shortCircuit();
            typeErrors();
            builtins();
            emitValidation();
            tickChecks();
            tickEmit();
            events();
            customEvents();
            damage();
            touch();
            runtimeDiagnostics();
            fastPathDiagnostics();
            fastPathCoverage();
            bytecodeRoundTrip();
            backendEquivalence();
        }
        catch (Exception e)
        {
            failures++;
            System.out.println("[FAIL] 用例异常终止：" + e);
            e.printStackTrace(System.out);
        }
        System.out.println("checks=" + checks + " failures=" + failures);
        return failures;
    }

    // ------------------------------------------------------------ 字面量与注释

    private static void literals()
    {
        check("十进制整数", num("1 + 2") == 3.0);
        check("小数", num("1.5 * 2") == 3.0);
        check("一元负号", num("-2.5 + 0.5") == -2.0);
        check("括号优先于运算优先级", num("(1 + 2) * 3") == 9.0);
        check("乘除优先于加减", num("2 + 3 * 4") == 14.0);
        check("乘除左结合", num("8 / 4 / 2") == 1.0);
        check("# 注释被忽略", num("1 + 1") == 2.0 && ok("p.pos.x = 1 + 1 # 注释"));
        check("// 注释被忽略", ok("p.pos.x = 2 * 3 // 注释"));
        check("比较不能连写（0 < t < 1）", compileFails(HEAD
                + "on tick(dt) { for p in particles { if (0 < p.age < 1) { p.energy = 1 } } }"));
    }

    // ------------------------------------------------------------ 运算符

    private static void operators()
    {
        check("比较 < 为真", flag("2 < 3") == 1.0);
        check("比较 >= 为假", flag("2 >= 3") == 0.0);
        check("== 对 num", flag("2.5 == 2.5") == 1.0);
        check("!= 对 num", flag("2.5 != 2.5") == 0.0);
        check("and 两边都真", flag("(1 < 2) and (2 < 3)") == 1.0);
        check("and 一边假", flag("(1 < 2) and (3 < 2)") == 0.0);
        check("or 一边真", flag("(3 < 2) or (2 < 3)") == 1.0);
        check("not", flag("not (1 < 2)") == 0.0);
        check("复合赋值 +=", numAfter("let a = 1\n a += 2\n p.pos.x = a") == 3.0);
        check("复合赋值 -= / *=", numAfter("let a = 10\n a -= 2\n a *= 3\n p.pos.x = a") == 24.0);
        check("vec3 逐分量读写", yAfter("p.pos = vec(1, 2, 3)\n p.pos.y = p.pos.y + 10") == 12.0);
        check("vec3 加 / 标量乘", num("len(vec(1, 1, 1) * 2)") > 3.46 && num("len(vec(1, 1, 1) * 2)") < 3.47);
    }

    // ------------------------------------------------------------ 短路求值

    /**
     * 短路是本轮修掉的真缺陷：以前 {@code and}/{@code or} 两侧都先求值，
     * 守卫写法 {@code d != 0 and 1/d > 0} 照样除零，把整朵云打成 broken。
     */
    private static void shortCircuit()
    {
        check("and 短路：右侧不执行", energyAfter(
                "let d = 0\n if ((d != 0) and (1 / d > 0)) { p.energy = 1 } else { p.energy = 2 }") == 2.0F);
        check("or 短路：右侧不执行", energyAfter(
                "let d = 0\n if ((d == 0) or (1 / d > 0)) { p.energy = 3 } else { p.energy = 4 }") == 3.0F);
        check("非短路写法确实会炸（对照）", broken("let d = 0\n if ((1 / d > 0) and (d != 0)) { p.energy = 1 }"));
        check("短路时结果值正确（假 and 真 = 假）", flag("(1 > 2) and (2 > 1)") == 0.0);
        check("短路时结果值正确（真 or 假 = 真）", flag("(2 > 1) or (1 > 2)") == 1.0);
        check("短路时两个后端逐位一致", sameHash(
                "let d = 0\n"
                        + "if ((d != 0) and (1 / d > 0)) { p.energy = 99 }\n"
                        + "if ((d == 0) or (1 / d > 0)) { p.energy = p.energy - 1 }", 12));
        check("短路时两个后端都不炸（寄存器机也跳过了右边）",
                !broken("let d = 0\n if ((d != 0) and (1 / d > 0)) { p.energy = 1 }")
                        && !brokenInterp("let d = 0\n if ((d != 0) and (1 / d > 0)) { p.energy = 1 }"));
        check("and 短路不推进 rand 序列（同一脚本两次结果一致）",
                energyAfter("let d = 0\n if ((d != 0) and (rand() > 0.5)) { p.energy = 1 }") == 100.0F);
    }

    // ------------------------------------------------------------ 类型错误

    private static void typeErrors()
    {
        check("if 的条件必须 bool（1 不行）", broken("if (1) { p.energy = 1 }"));
        check("and 两侧必须 bool", broken("if ((1 < 2) and 1) { p.energy = 1 }"));
        check("num 不能当 bool", excepts("if (p.age) { p.energy = 1 }", "bool"));
        check("element 不能比较", broken("let e = fire\n if (e == fire) { p.energy = 1 }"));
        check("vec3 不能比大小", broken("if (p.pos < p.pos) { p.energy = 1 }"));
        check("num 位置不能塞 vec3", broken("p.pos.x = vec(1, 1, 1)"));
        check("未知变量报错", excepts("p.pos.x = nosuchvar", "未知变量"));
        check("div 除零在运行期报错（不是编译期）", broken("let d = 0\n p.pos.x = 1 / d"));
    }

    // ------------------------------------------------------------ 内建函数

    private static void builtins()
    {
        check("rand() 无参可用", flag("rand() >= 0 and rand() < 1") == 1.0);
        check("rand(0, 10) 报错（以前静默返回 [0,1)）", excepts("p.pos.x = rand(0, 10)", "不接受参数"));
        check("sqrt 负数取 0（不报错）", num("sqrt(0 - 1)") == 0.0);
        check("abs", num("abs(0 - 3)") == 3.0);
        check("min / max", num("min(2, 5) + max(2, 5)") == 7.0);
        check("clamp", num("clamp(9, 0, 4)") == 4.0);
        check("lerp num", num("lerp(0, 10, 0.25)") == 2.5);
        check("smoothstep 端点", num("smoothstep(0, 1, 0)") == 0.0);
        check("dot", num("dot(vec(1, 2, 3), vec(4, 5, 6))") == 32.0);
        check("cross 的 z 分量", zAfter("p.pos = cross(vec(1, 0, 0), vec(0, 1, 0))") == 1.0);
        check("len", num("len(vec(3, 4, 0))") == 5.0);
        check("dist", num("dist(vec(1, 1, 1), vec(1, 1, 4))") == 3.0);
        check("normalize 后长度为 1", Math.abs(num("len(normalize(vec(9, 0, 0)))") - 1.0) < 1.0E-9);
        check("实参个数不对要报错", excepts("p.pos.x = min(1)", "参数"));
        check("未知函数要报错", excepts("p.pos.x = nope(1)", "未知函数"));
        check("gravity(fire) 能走快速路径",
                reasonOf("", "for p in particles { p.pos.x = gravity(fire) }") == null);

        // ---- 新增内建：% / sign / pow / atan2 ----
        check("取模 10 % 3 = 1", Math.abs(num("10 % 3") - 1.0) < 1.0E-12);
        check("取模跟随被除数符号（-10 % 3 = -1，与 Java 一致）",
                Math.abs(num("-10 % 3") + 1.0) < 1.0E-12);
        check("取模支持小数 10.5 % 3 = 1.5", Math.abs(num("10.5 % 3") - 1.5) < 1.0E-12);
        check("取模的除数为零会报错",
                broken("p.pos.x = 1 % 0") && contains(breakReason(tickOnly("p.pos.x = 1 % 0"), false), "取模"));
        check("取模优先级与乘除同级（2 + 10 % 3 = 3）", Math.abs(num("2 + 10 % 3") - 3.0) < 1.0E-12);
        check("sign(-3) = -1", Math.abs(num("sign(-3)") + 1.0) < 1.0E-12);
        check("sign(0) = 0", Math.abs(num("sign(0)")) < 1.0E-12);
        check("sign(2.5) = 1", Math.abs(num("sign(2.5)") - 1.0) < 1.0E-12);
        check("pow(2, 10) = 1024", Math.abs(num("pow(2, 10)") - 1024.0) < 1.0E-9);
        check("pow(2, -1) = 0.5", Math.abs(num("pow(2, -1)") - 0.5) < 1.0E-12);
        check("atan2(1, 0) = π/2", Math.abs(num("atan2(1, 0)") - Math.PI / 2.0) < 1.0E-12);
        check("atan2(0, 1) = 0", Math.abs(num("atan2(0, 1)")) < 1.0E-15);
        check("atan2 参数顺序是 (y, x)", Math.abs(num("atan2(-1, 0)") + Math.PI / 2.0) < 1.0E-12);
        check("% / sign / pow / atan2 都能走快速路径",
                reasonOf("", "for p in particles { p.pos.x = (10 % 3) + sign(-1) + pow(2, 3) "
                        + "+ atan2(1, 1) }") == null);
        check("pow / atan2 实参个数不对会报错", excepts("p.pos.x = pow(2)", "参数"));
        check("取模只支持 num % num", broken("p.pos = p.pos % 2"));
        check("字符串不能参与算术",
                broken("p.pos.x = 1 + \"minecraft:ice\""));

        // look()：投射物法术唯一的"方向"来源，只能在 cast 里取
        CastResult aimed = SpellScript.compile("look_cast",
                "on cast { let dir = look()\n emit { element: fire, count: 8 } }\n").cast(ZERO, 1L);
        check("look() 在 cast 里可用，且存得住（云级变量 vec3）",
                aimed.vars().get("dir") instanceof Value.Vec3 d
                        && Math.abs(d.x() - 0.6) < 1.0E-9 && Math.abs(d.z() - 0.8) < 1.0E-9);
        check("on tick 里读 look() 在编译期被拒",
                compileFails(HEAD + "on tick(dt) { let d = look() }\n"));
        check("on hit 里读 look() 也被拒",
                compileFails(HEAD + "on hit { let d = look() }\n"));

        // aim(d)：瞄准点 = here + look × d。投射物的"枪口"位置就靠它
        CastResult aim0 = SpellScript.compile("aim0",
                "on cast { emit { element: fire, count: 8, at: aim(0) } }\n").cast(ZERO, 1L);
        CastResult aim2 = SpellScript.compile("aim2",
                "on cast { emit { element: fire, count: 8, at: aim(2) } }\n").cast(ZERO, 1L);
        check("aim(0) 就是施法原点（here）",
                Math.abs(aim0.emits().get(0).x() - 0.0) < 1.0E-9
                        && Math.abs(aim0.emits().get(0).y() - 64.0) < 1.0E-9
                        && Math.abs(aim0.emits().get(0).z() - 0.0) < 1.0E-9);
        check("aim(2) = here + look × 2（ZERO 的 look 是 0.6/0/0.8）",
                Math.abs(aim2.emits().get(0).x() - 1.2) < 1.0E-9
                        && Math.abs(aim2.emits().get(0).y() - 64.0) < 1.0E-9
                        && Math.abs(aim2.emits().get(0).z() - 1.6) < 1.0E-9);
        check("on tick 里读 aim() 在编译期被拒",
                compileFails(HEAD + "on tick(dt) { let p = aim(1) }\n"));
        check("aim 需要 1 个参数",
                castFails("on cast { let p = aim()\n emit { element: fire, count: 8 } }\n", "参数"));
        // 投射物：cast 存方向 → tick 沿它推进（这就是 fireball 的核心两行）
        SpellCloud bolt = cloud(SpellScript.compile("look_fly",
                "on cast { let dir = look()\n emit { element: fire, count: 8 } }\n"
                        + "on tick(dt) { for p in particles { p.pos = p.pos + dir } }\n"),
                4L, false);
        double x0 = bolt.x(0);
        double z0 = bolt.z(0);
        bolt.step();
        check("方向存进云级变量后，tick 里能沿它推进（位移 +0.6x / +0.8z）",
                Math.abs((bolt.x(0) - x0) - 0.6) < 1.0E-9
                        && Math.abs((bolt.z(0) - z0) - 0.8) < 1.0E-9);

        // rotate：罗德里格斯，右手定则
        double[] rz = posAfter("p.pos = rotate(vec(1, 0, 0), vec(0, 0, 1), 1.5707963267948966)");
        check("rotate 绕 Z 轴 90°：(1,0,0) → (0,1,0)",
                Math.abs(rz[0]) < 1.0E-9 && Math.abs(rz[1] - 1.0) < 1.0E-9 && Math.abs(rz[2]) < 1.0E-9);
        double[] ry = posAfter("p.pos = rotate(vec(1, 0, 0), vec(0, 1, 0), 1.5707963267948966)");
        check("rotate 绕 Y 轴 90°：(1,0,0) → (0,0,-1)",
                Math.abs(ry[0]) < 1.0E-9 && Math.abs(ry[1]) < 1.0E-9 && Math.abs(ry[2] + 1.0) < 1.0E-9);
        double[] r0 = posAfter("p.pos = rotate(vec(1, 2, 3), vec(0, 1, 0), 0)");
        check("rotate 角度 0 原样", r0[0] == 1.0 && r0[1] == 2.0 && r0[2] == 3.0);
        double[] rn = posAfter("p.pos = rotate(vec(1, 2, 3), vec(0, 0, 0), 1.5)");
        check("rotate 零轴原样返回", rn[0] == 1.0 && rn[1] == 2.0 && rn[2] == 3.0);
        double[] rl = posAfter("p.pos = rotate(vec(1, 2, 2), vec(1, 1, 0), 1.1)");
        check(String.format(java.util.Locale.ROOT, "rotate 长度不变（%.6f，得 (%.3f, %.3f, %.3f)）",
                        len(rl), rl[0], rl[1], rl[2]),
                // SpellMath 的 sin/cos 是近似式，矩阵正交性有 ~1e-9 的漂移，不能按 1e-9 卡
                Math.abs(len(rl) - 3.0) < 1.0E-6);

        // tangent / radial：两参仍是绕 Y（旧行为），三参绕任意轴
        double[] t2 = posAfter("p.pos = tangent(vec(0, 0, 0), vec(1, 0, 0))");
        check("tangent 两参仍绕 Y（1,0,0 → 0,0,1）",
                Math.abs(t2[0]) < 1.0E-12 && t2[1] == 0.0 && Math.abs(t2[2] - 1.0) < 1.0E-12);
        double[] t3 = posAfter("p.pos = tangent(vec(0, 0, 0), vec(1, 0, 0), vec(0, 0, 1))");
        check("tangent 三参绕 Z 轴（1,0,0 → 0,-1,0）",
                Math.abs(t3[0]) < 1.0E-12 && Math.abs(t3[1] + 1.0) < 1.0E-12 && Math.abs(t3[2]) < 1.0E-12);
        // 点在轴上时径向无定义 → 零向量；离轴时才是竖直方向
        double[] d3 = posAfter("p.pos = radial(vec(0, 0, 0), vec(1, 0, 2), vec(0, 1, 0))");
        double d3l = len(d3);
        check("radial 三参（轴向上）沿水平方向",
                Math.abs(d3l - 1.0) < 1.0E-12 && Math.abs(d3[1]) < 1.0E-12 && d3[0] > 0.4 && d3[2] > 0.8);
        double[] dOnAxis = posAfter("p.pos = radial(vec(0, 0, 0), vec(0, 2, 0), vec(0, 1, 0))");
        check("radial 三参：点在轴上 → 零向量",
                dOnAxis[0] == 0.0 && dOnAxis[1] == 0.0 && dOnAxis[2] == 0.0);
        check("tangent 轴向零向量报错",
                excepts("p.pos = tangent(vec(0, 0, 0), vec(1, 0, 0), vec(0, 0, 0))", "零向量"));

        check("gravity 用变量是退化，不是报错",
                contains(reasonOf("let e = fire", "for p in particles { p.pos.x = gravity(e) }"),
                        "元素字面量"));
    }

    // ------------------------------------------------------------ emit 校验

    private static void emitValidation()
    {
        check("emit 未知字段报错（以前静默吃默认值）",
                castFails("on cast { emit { element: fire, cout: 32 } }", "cout"));
        check("emit 缺 element 报错", castFails("on cast { emit { count: 32 } }", "element"));
        check("emit 未知元素报错", castFails("on cast { emit { element: not_an_element } }", "not_an_element"));
        check("emit 的 at 生效", castAt("on cast { emit { element: fire, count: 16, at: vec(3, 70, -2) } }"));
        check("emit 的 count 生效", castCount("on cast { emit { element: fire, count: 128 } }") == 128);
        check("emit 的 life 生效", Math.abs(castLife("on cast { emit { element: fire, life: 7.5 } }") - 7.5F) < 1.0E-6);
        // 寿命上限放开到 30 分钟（以前 60 秒，写 life: 240 会被静默夹成 60）
        check("emit 的 life 可以超过 60 秒（240 就是 240）",
                Math.abs(castLife("on cast { emit { element: fire, life: 240 } }") - 240.0F) < 1.0E-3);
        check("emit 的 life 上限是 " + (int) SpellConstants.MAX_LIFE_SEC + " 秒",
                Math.abs(castLife("on cast { emit { element: fire, life: 100000 } }")
                        - SpellConstants.MAX_LIFE_SEC) < 1.0E-3);
        check("emit 的 life 有下限（0 会被夹到最小值）",
                Math.abs(castLife("on cast { emit { element: fire, life: 0 } }")
                        - SpellConstants.MIN_LIFE_SEC) < 1.0E-6);
        check("一次施法超过 8 次 emit 报错",
                castFails("on cast {\n" + "  emit { element: fire }\n".repeat(9) + "}", "最多"));
        check("shape 必须是形状记录", castFails("on cast { emit { element: fire, shape: 1 } }", "shape"));
        check("emit 的 facing 生效（形状本地 +Y 指向它）",
                castFacing("on cast { emit { element: fire, count: 8, facing: vec(1, 0, 0) } }"));
        check("emit 的 facing 必须是 vec3",
                castFails("on cast { emit { element: fire, facing: 1 } }", "facing"));
        check("emit 的 facing 不能是零向量",
                castFails("on cast { emit { element: fire, facing: vec(0, 0, 0) } }", "零向量"));
    }

    // ------------------------------------------------------------ tick 静态检查

    private static void tickChecks()
    {
        check("tick 里读世界在编译期被拒",
                compileFails(HEAD + "on tick(dt) { let x = field(fire) }"));
        check("tick 里循环内的 emit 被拒（每粒一次会塞满世界）",
                compileFails(HEAD + "on tick(dt) { for p in particles { emit { element: fire } } }"));
        check("tick 里 if 里的 emit 允许（只要不在循环里）", !compileFails(
                HEAD + "on tick(dt) { if (dt > 0) { emit { element: fire } } }"));
        check("tick 里 here() 也被拒",
                compileFails(HEAD + "on tick(dt) { let x = here() }"));
        check("tick 里 light() 也被拒",
                compileFails(HEAD + "on tick(dt) { let x = light(0, 64, 0) }"));
        check("cast 里读世界是允许的", !compileFails(
                "on cast { let x = field(fire)\n emit { element: fire } }\n"
                        + "on tick(dt) { for p in particles { p.energy = 1 } }"));
    }

    // ------------------------------------------------------------ tick 里 emit

    /**
     * tick 里生云：只在服务端生效、每步/终身有配额、子云不再生孙云。
     *
     * <p>客户端也会跑同一份脚本（保持 {@code rand()} 序列一致），但 {@code emitsEnabled=false}
     * 时只是把请求丢掉——这条用例用"开/不开 emit 的云状态哈希相同"来钉住。</p>
     */
    private static void tickEmit()
    {
        String src = HEAD + "on tick(dt) {\n"
                + "  emit { element: water, count: 64, at: vec(1, 64, 0), facing: vec(0, 0, 1) }\n"
                + "  for p in particles { p.pos.y = p.pos.y + 0.01 }\n"
                + "}\n";

        SpellCloud server = cloud(SpellScript.compile("emit_s", src), 31L, false);
        server.setEmitsEnabled(true);
        server.step();
        java.util.List<EmitSpec> got = server.drainEmits();
        check("tick 里 emit：服务端一步产出一条", got.size() == 1);
        if (got.size() == 1)
        {
            EmitSpec e = got.get(0);
            check("tick emit 的字段都带过去了", e.elementId() == SpellElements.indexOf("water")
                    && e.count() == 64 && e.faceZ() == 1.0
                    && Math.abs(e.x() - 1.0) < 1.0E-9 && Math.abs(e.y() - 64.0) < 1.0E-9);
        }
        check("取过之后队列清空", server.drainEmits().isEmpty());

        // 不写 at 时默认生在"这朵云的出生点"（不是世界原点）
        SpellCloud here = cloud(SpellScript.compile("emit_here", HEAD + "on tick(dt) {\n"
                + "  emit { element: fire, count: 8 }\n"
                + "  for p in particles { p.pos.x = p.pos.x + 0.01 }\n}\n"), 71L, false);
        here.setEmitsEnabled(true);
        here.step();
        java.util.List<EmitSpec> h = here.drainEmits();
        check("tick emit 不写 at 时用云的出生点",
                h.size() == 1 && Math.abs(h.get(0).x() - here.originX) < 1.0E-9
                        && Math.abs(h.get(0).y() - here.originY) < 1.0E-9);

        SpellCloud client = cloud(SpellScript.compile("emit_c", src), 31L, false);
        client.step();
        check("客户端（emitsEnabled=false）不产出", client.drainEmits().isEmpty());

        // 关掉 emit 不能影响这朵云自己的状态（否则两端哈希会分叉）
        SpellCloud on = cloud(SpellScript.compile("emit_on", src), 41L, false);
        on.setEmitsEnabled(true);
        SpellCloud off = cloud(SpellScript.compile("emit_off", src), 41L, false);
        for (int i = 0; i < 10; i++)
        {
            on.step();
            off.step();
            on.drainEmits();
        }
        check("开不开 emit，这朵云自己的状态一致", on.stateHash() == off.stateHash());

        // 终身配额
        SpellCloud many = cloud(SpellScript.compile("emit_many", src), 51L, false);
        many.setEmitsEnabled(true);
        int total = 0;
        for (int i = 0; i < 40; i++)
        {
            many.step();
            total += many.drainEmits().size();
        }
        check("tick emit 终身配额是 " + SpellCloud.MAX_EMITS_TOTAL + "（收到 " + total + "）",
                total == SpellCloud.MAX_EMITS_TOTAL);

        // 用顶层 if 卡时间：前两步不生，之后才生
        String gated = HEAD + "on tick(dt) {\n"
                + "  if (step > 1) { emit { element: fire, count: 8 } }\n"
                + "  for p in particles { p.pos.x = p.pos.x + 0.01 }\n"
                + "}\n";
        SpellCloud g = cloud(SpellScript.compile("emit_g", gated), 61L, false);
        g.setEmitsEnabled(true);
        g.step();
        boolean s0 = g.drainEmits().isEmpty();
        g.step();
        boolean s1 = g.drainEmits().isEmpty();
        g.step();
        boolean s2 = !g.drainEmits().isEmpty();
        check("用顶层 if 可以把 emit 卡到指定步数（step>1 才开始）", s0 && s1 && s2);

        // 快路径：tick 里有 emit 的脚本会退回解释器，但要说清原因
        check("带 tick emit 的脚本退化原因提到 emit/解释器",
                contains(reasonOf("", "emit { element: fire }\n"
                        + "for p in particles { p.pos.x = 1 }"), "emit"));
    }

    // ------------------------------------------------------------ 事件（hit / expire）

    /** 地面在 y<50 的假世界：事件用例靠它造出一次真实撞击 */
    private static final SolidOracle FLOOR = (x, y, z) -> y < 50;
    /** 一堵在 x=3 这一列的竖墙（不是半空间，否则"墙里"和"墙外"分不开） */
    private static final SolidOracle WALL = (x, y, z) -> x == 3;
    /** 会报出方块身份的假地面：{@code hit.block} 用例用（真世界里是 {@code minecraft:ice} 这种） */
    private static final SolidOracle ICE_FLOOR = new SolidOracle()
    {
        @Override
        public boolean solid(int x, int y, int z)
        {
            return y < 50;
        }

        @Override
        public String blockId(int x, int y, int z)
        {
            return "test:ice";
        }
    };

    /**
     * {@code on hit} / {@code on expire} 的语义。
     *
     * <p>观测姿势和别处一样：脚本没有 print，所以让事件体把要测的值写进粒子/云变量再读回来。</p>
     */
    private static void events()
    {
        // ---- 语法与静态检查 ----
        check("on hit / on expire 都能编译", !compileFails(HEAD
                + "on hit { for p in particles { p.pos.x = 1 } }\n"
                + "on expire { emit { element: mist, count: 8 } }\n"));
        check("重复的 on hit 报错", compileFails(HEAD + "on hit { }\non hit { }\n"));
        check("重复的 on expire 报错", compileFails(HEAD + "on expire { }\non expire { }\n"));
        check("on hit(dt) 报错（只有 tick 带参数）", compileFails(HEAD + "on hit(dt) { }\n"));
        check("on expire 里读世界在编译期被拒", compileFails(HEAD + "on expire { let h = here() }\n"));
        check("on hit 里循环内 emit 被拒", compileFails(HEAD
                + "on hit { for p in particles { emit { element: fire } } }\n"));

        // ---- on hit：什么时候触发 ----
        String countHook = HEAD + "on hit { for p in particles { p.pos.x = hit.count } }\n";
        SpellCloud drop = falling(SpellScript.compile("hit_count", countHook), 5L);
        drop.step();
        check("on hit 触发：记下撞击粒子数（" + (int) drop.x(0) + " 粒）", drop.x(0) == drop.count);
        check("撞击事件发生在碰撞之后（速度已经反弹）", drop.velY(0) > 0.0);

        // 每步最多一次：32 粒同时砸地，事件计数只加 1
        String once = "on cast {\n  let n = 0\n  emit { element: earth, count: 32 }\n}\n"
                + "on hit { n = n + 1 }\n";
        SpellCloud counter = cloud(SpellScript.compile("hit_once", once), 6L, false);
        double startY = 49.5;
        for (int i = 0; i < counter.count; i++)
        {
            counter.setPos(i, 0.5, startY, 0.5);
            counter.setVel(i, 0.0, -10.0, 0.0);
        }
        counter.setCollider(new ParticleCollider(FLOOR, CollisionMode.INELASTIC));
        counter.step();
        Object n = counter.scriptVars.get("n");
        check("on hit 每步只触发一次（n=" + n + "）",
                n instanceof Value.Num num && num.v() == 1.0);

        // ---- on hit 的事实 ----
        // 粒子散开摆（x 各不相同），事件体把所有粒子的 x 改成"撞击点的 x"：
        // 只有 hit.pos 真的是这批撞击粒子的平均时才写得对，否则读到的是自己的坐标。
        String posHook = HEAD + "on hit { for p in particles { p.pos.x = hit.pos.x\n"
                + " p.pos.z = hit.pos.z } }\n";
        SpellCloud at = falling(SpellScript.compile("hit_pos", posHook), 7L);
        double sumX = 0.0;
        for (int i = 0; i < at.count; i++)
        {
            double px = 3.25 + i * 0.125;
            sumX += px;
            at.setPos(i, px, 49.5, -1.75);
            at.setVel(i, 0.0, -10.0, 0.0);
        }
        double meanX = sumX / at.count;
        at.step();
        boolean allAtMean = true;
        for (int i = 0; i < at.count; i++)
        {
            allAtMean &= Math.abs(at.x(i) - meanX) < 1.0E-9;
        }
        check(String.format("on hit 的 hit.pos 是撞击点的平均（%.3f，原本 3.250..%s）",
                        meanX, String.format(java.util.Locale.ROOT, "%.3f", 3.25 + (at.count - 1) * 0.125)),
                allAtMean && Math.abs(at.z(0) + 1.75) < 1.0E-9);

        String speedHook = HEAD + "on hit { for p in particles { p.pos.x = hit.speed } }\n";
        SpellCloud fast = falling(SpellScript.compile("hit_speed", speedHook), 8L);
        for (int i = 0; i < fast.count; i++)
        {
            fast.setPos(i, 0.5, 49.5, 0.5);
            fast.setVel(i, 0.0, -10.0, 0.0);
        }
        fast.step();
        check(String.format("on hit 的 hit.speed 是撞前速度（%.2f）", fast.x(0)),
                Math.abs(fast.x(0) - 10.0) < 1.0E-9);

        // 没有碰撞器 = 没有撞击事件（穿方块的元素、离机无世界都走这条）
        SpellCloud noWorld = cloud(SpellScript.compile("hit_none", countHook), 9L, false);
        for (int i = 0; i < noWorld.count; i++)
        {
            noWorld.setPos(i, 0.5, 49.5, 0.5);
            noWorld.setVel(i, 0.0, -10.0, 0.0);
        }
        noWorld.step();
        check("没有碰撞器就没有撞击事件", noWorld.x(0) == 0.5 && !noWorld.broken);

        // ---- on hit 能改写粒子状态（"落地熄火"） ----
        String kill = HEAD + "on hit { for p in particles { p.v = vec(0, 0, 0) } }\n";
        SpellCloud dead = falling(SpellScript.compile("hit_kill", kill), 10L);
        dead.step();
        check("on hit 里可以给粒子改状态（速度清零）",
                dead.velX(0) == 0.0 && dead.velY(0) == 0.0 && dead.velZ(0) == 0.0);

        // ---- on hit 里 emit：默认生在撞击点 ----
        String burst = HEAD + "on hit { emit { element: fire, count: 16 } }\n";
        SpellCloud boom = falling(SpellScript.compile("hit_emit", burst), 11L);
        boom.setEmitsEnabled(true);
        for (int i = 0; i < boom.count; i++)
        {
            boom.setPos(i, 2.5, 49.5, -3.5);
            boom.setVel(i, 0.0, -10.0, 0.0);
        }
        boom.step();
        java.util.List<EmitSpec> burstOut = boom.drainEmits();
        check("on hit 里 emit 不写 at 时生在撞击点",
                burstOut.size() == 1
                        && Math.abs(burstOut.get(0).x() - 2.5) < 1.0E-6
                        && Math.abs(burstOut.get(0).z() + 3.5) < 1.0E-6);

        // 客户端（emitsEnabled=false）照样跑事件、只是不生成子云：粒子状态必须一致
        SpellCloud serverSide = falling(SpellScript.compile("hit_side", burst), 12L);
        SpellCloud clientSide = falling(SpellScript.compile("hit_side_c", burst), 12L);
        serverSide.setEmitsEnabled(true);
        for (int i = 0; i < serverSide.count; i++)
        {
            for (SpellCloud c : new SpellCloud[]{ serverSide, clientSide })
            {
                c.setPos(i, 0.5, 49.5, 0.5);
                c.setVel(i, 0.0, -10.0, 0.0);
            }
        }
        serverSide.step();
        clientSide.step();
        check("on hit：客户端也跑（粒子状态与产生子云的一侧一致）",
                serverSide.stateHash() == clientSide.stateHash()
                        && !serverSide.drainEmits().isEmpty() && clientSide.drainEmits().isEmpty());

        // ---- on hit 的事实：法向 ----
        String normalHook = HEAD + "on hit { for p in particles { p.pos.x = hit.normal.x\n"
                + " p.pos.y = hit.normal.y } }\n";
        SpellCloud floorHit = falling(SpellScript.compile("hit_n", normalHook), 15L);
        floorHit.step();
        check("撞地：hit.normal 朝上（0,1,0）",
                floorHit.x(0) == 0.0 && floorHit.y(0) == 1.0);

        SpellCloud wallHit = cloud(SpellScript.compile("hit_wall", normalHook), 16L, false);
        for (int i = 0; i < wallHit.count; i++)
        {
            // 摆成"这一步刚好撞进去"：pos 已在墙里，速度 -X，碰撞器会用 pos - v*dt 还原起点
            wallHit.setPos(i, 3.7, 60.0, 0.5);
            wallHit.setVel(i, -10.0, 0.0, 0.0);
        }
        wallHit.setCollider(new ParticleCollider(WALL, CollisionMode.INELASTIC));
        wallHit.step();
        check("撞墙：hit.normal 朝 +X（冲击波就立在墙面上）",
                wallHit.x(0) == 1.0 && wallHit.y(0) == 0.0);

        // ---- on expire：默认生成位置是"粒子现在的质心"，不是出生点 ----
        String byeAt = "on cast { emit { element: mist, count: 16, life: 0.5 } }\n"
                + "on expire { emit { element: fire, count: 8 } }\n";
        SpellCloud moved = cloud(SpellScript.compile("expire_where", byeAt), 18L, false);
        moved.setEmitsEnabled(true);
        moved.setServerSide(true);
        for (int i = 0; i < moved.count; i++)
        {
            moved.setPos(i, 5.0, 51.0, -2.0);
        }
        while (!moved.isDead())
        {
            moved.step();
        }
        moved.runExpireHook();
        java.util.List<EmitSpec> puff = moved.drainEmits();
        check("on expire 的 emit 生在粒子现在的位置（质心），不是云的出生点",
                puff.size() == 1
                        && Math.abs(puff.get(0).x() - 5.0) < 1.0E-9
                        && Math.abs(puff.get(0).y() - 51.0) < 1.0E-9
                        && Math.abs(puff.get(0).z() + 2.0) < 1.0E-9);

        // ---- emit inherit：默认继承脚本，false 就不带脚本（只按元素定律飘） ----
        check("emit 默认继承父云脚本", firstEmit(HEAD).inherit());
        check("emit inherit: false 生效",
                !firstEmit(HEAD.replace("count: 32 }", "count: 32, inherit: false }")).inherit());
        check("emit inherit 必须是 bool",
                castFails(HEAD.replace("count: 32 }", "count: 32, inherit: 1 }"), "inherit"));
        String bye = "on cast {\n  let n = 0\n  emit { element: mist, count: 16, life: 0.5 }\n}\n"
                + "on expire {\n  n = n + 1\n  emit { element: fire, count: 8 }\n}\n";
        SpellScript byeScript = SpellScript.compile("expire", bye);
        SpellCloud dying = cloud(byeScript, 13L, false);
        dying.setEmitsEnabled(true);
        int alive = 0;
        while (!dying.isDead() && alive < 1000)
        {
            dying.step();
            alive++;
        }
        check("寿终前没有触发 on expire（走了 " + alive + " 步）", dying.isDead()
                && dying.drainEmits().isEmpty());
        check("on expire 之前 n 还是 0", dying.scriptVars.get("n") instanceof Value.Num zero
                && zero.v() == 0.0);
        check("跑 on expire 成功", dying.runExpireHook());
        Object exp = dying.scriptVars.get("n");
        check("on expire 跑了一次（n=" + exp + "）", exp instanceof Value.Num en && en.v() == 1.0);
        java.util.List<EmitSpec> farewell = dying.drainEmits();
        double[] mid = dying.centroid();
        check("on expire 里 emit 不写 at 时用粒子的质心", farewell.size() == 1
                && Math.abs(farewell.get(0).x() - mid[0]) < 1.0E-9
                && Math.abs(farewell.get(0).y() - mid[1]) < 1.0E-9
                && Math.abs(farewell.get(0).z() - mid[2]) < 1.0E-9);

        SpellCloud muted = cloud(byeScript, 14L, false);
        while (!muted.isDead())
        {
            muted.step();
        }
        muted.runExpireHook();
        check("客户端不产出 on expire 的 emit", muted.drainEmits().isEmpty());

        // ---- 事件不拖累编译路径 ----
        String withEvents = HEAD + "on hit { for p in particles { p.pos.x = hit.pos.x } }\n"
                + "on expire { emit { element: fire, count: 8 } }\n"
                + "on tick(dt) {\n  for p in particles { p.pos.y = p.pos.y + 0.01 }\n}\n";
        SpellScript both = SpellScript.compile("events_fast", withEvents);
        check("带 on hit / on expire 的脚本，tick 仍能编出快速路径",
                both.prepareFastPath(Map.of()));
        check("带事件的双后端逐位一致（事件不影响编译路径）",
                hash(both, false, 3) == hash(both, true, 3));

        // hit 只出现在 on hit 里；on tick 里读它必须是"未知变量"
        String misuse = HEAD + "on tick(dt) {\n  for p in particles { p.pos.x = hit.count }\n}\n";
        check("on tick 里没有 hit 这个变量",
                contains(breakReason(misuse, true), "hit"));
    }

    /** 造一朵"压在方格里、正往下砸"的云，用它触发一次真实撞击 */
    private static SpellCloud falling(SpellScript script, long seed)
    {
        return falling(script, seed, FLOOR);
    }

    /** 同上，但用给定的假世界（{@code hit.block} 用例要一个会报方块身份的） */
    private static SpellCloud falling(SpellScript script, long seed, SolidOracle oracle)
    {
        SpellCloud c = cloud(script, seed, false);
        for (int i = 0; i < c.count; i++)
        {
            c.setPos(i, 0.5, 49.5, 0.5);
            c.setVel(i, 1.5, -10.0, -2.0);
        }
        c.setCollider(new ParticleCollider(oracle, CollisionMode.INELASTIC));
        return c;
    }

    // ------------------------------------------------------------ 自定义事件（call）

    /**
     * {@code on 名字 { ... }} + {@code call 名字()}：没有参数、没有返回值的"给一段代码起名字"。
     *
     * <p>钉住的是四件事：能被多个阶段共用、作用域只有云级变量（看不到调用者局部变量）、
     * 静态检查能看穿调用图（hurt/世界查询按"被谁调用"判）、循环调用编译期就拒。</p>
     */
    private static void customEvents()
    {
        String shared = "on cast { let n = 0\n emit { element: fire, count: 8 } }\n"
                + "on boom { n = n + 1 }\n"
                + "on hit { call boom() }\n";
        SpellCloud one = falling(SpellScript.compile("ev1", shared), 31L);
        one.step();
        check("自定义事件能被 on hit 调用（n=" + numVarOf(one, "n") + "）", numVarOf(one, "n") == 1.0);

        SpellCloud both = falling(SpellScript.compile("ev2", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on boom { n = n + 1 }\n"
                + "on hit { call boom() }\n"
                + "on expire { call boom() }\n"), 32L);
        both.step();
        both.runExpireHook();
        check("同一段代码被 on hit 和 on expire 共用（n=" + numVarOf(both, "n") + "）",
                numVarOf(both, "n") == 2.0);

        // 被调用的事件能读写云级变量、能看到 hit，但看不到调用者的局部变量
        SpellCloud sees = falling(SpellScript.compile("ev3", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on boom { n = hit.count }\n"
                + "on hit { call boom() }\n"), 33L);
        sees.step();
        check("被调用的事件能看到 hit（n=" + numVarOf(sees, "n") + "）", numVarOf(sees, "n") == 8.0);

        SpellCloud opaque = falling(SpellScript.compile("ev4", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on boom { n = callerLocal }\n"
                + "on hit { let callerLocal = 5\n call boom() }\n"), 34L);
        opaque.step();
        check("被调用的事件看不到调用者的局部变量（不是动态作用域）",
                opaque.broken && contains(opaque.breakReason, "callerLocal"));

        // 一层套一层：a 调 b
        SpellCloud nested = falling(SpellScript.compile("ev5", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on a { call b() }\n"
                + "on b { n = n + 10 }\n"
                + "on hit { call a() }\n"), 35L);
        nested.step();
        check("事件可以调用别的事件（a → b，n=" + numVarOf(nested, "n") + "）",
                numVarOf(nested, "n") == 10.0);

        // ---- 静态检查 ----
        String cast = "on cast { emit { element: fire, count: 8 } }\n";
        check("call 未定义的事件在编译期被拒", compileFails(cast + "on hit { call nope() }\n"));
        check("未定义的错误信息提到事件名", contains(compileError(cast + "on hit { call nope() }\n"), "nope"));
        check("重复的自定义事件被拒", compileFails(cast + "on boom { }\non boom { }\n"));
        check("循环调用在编译期被拒", compileFails(cast
                + "on a { call b() }\non b { call a() }\non hit { call a() }\n"));
        check("循环调用的错误信息提到循环", contains(compileError(cast
                + "on a { call b() }\non b { call a() }\non hit { call a() }\n"), "循环"));
        check("不能 call 内置阶段", compileFails(cast + "on hit { call cast() }\n"));
        check("call 不能写在粒子循环里",
                compileFails(cast + "on boom { }\n"
                        + "on tick(dt) { for p in particles { call boom() } }\n"));

        // ---- 跨事件的阶段分析 ----
        String hurtInEvent = cast + "on boom { hurt(2.0, 3.0) }\n";
        check("被 on hit 调用的自定义事件里可以打伤害",
                !compileFails(hurtInEvent + "on hit { call boom() }\n"));
        String hurtFromTick = compileError(hurtInEvent
                + "on tick(dt) { call boom()\n for p in particles { p.pos.x = 1 } }\n");
        check("同一个事件被 on tick 调用后，里面就不许打伤害", !hurtFromTick.isEmpty());
        check("拒绝原因点名了 on tick", contains(hurtFromTick, "on tick"));

        String worldInEvent = "on cast { call boom()\n emit { element: fire, count: 8 } }\n"
                + "on boom { let h = here() }\n";
        check("被 on cast 调用的自定义事件里可以读世界", !compileFails(worldInEvent));
        check("被 on hit 调用的自定义事件里不许读世界",
                compileFails("on cast { emit { element: fire, count: 8 } }\n"
                        + "on boom { let h = here() }\n"
                        + "on hit { call boom() }\n"));
        check("同一事件既被 cast 又被 hit 调用时，世界查询也不许用",
                compileFails("on cast { call boom()\n emit { element: fire, count: 8 } }\n"
                        + "on boom { let h = here() }\n"
                        + "on hit { call boom() }\n"));

        // ---- 自定义事件带参数 ----
        SpellCloud args = falling(SpellScript.compile("ev7", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on boom(r, d) { n = n + r * d }\n"
                + "on hit { call boom(2.0, 3.0) }\n"), 36L);
        args.step();
        check("实参按位绑到形参（n=" + numVarOf(args, "n") + "）", numVarOf(args, "n") == 6.0);

        SpellCloud twoSites = falling(SpellScript.compile("ev8", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on boom(r) { n = n + r }\n"
                + "on hit { call boom(1.0) }\n"
                + "on expire { call boom(10.0) }\n"), 37L);
        twoSites.step();
        twoSites.runExpireHook();
        check("同一个事件两处调用可以给不同实参（n=" + numVarOf(twoSites, "n") + "）",
                numVarOf(twoSites, "n") == 11.0);

        SpellCloud fromLocal = falling(SpellScript.compile("ev9", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on boom(k) { n = k + hit.count }\n"
                + "on hit { let k = 5\n call boom(k) }\n"), 38L);
        fromLocal.step();
        check("实参在调用者作用域里求值（能传局部变量、hit.count）",
                numVarOf(fromLocal, "n") == 13.0);

        SpellCloud pass = falling(SpellScript.compile("ev10", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on a(x) { call b(x + 1) }\n"
                + "on b(y) { n = y }\n"
                + "on hit { call a(7) }\n"), 39L);
        pass.step();
        check("参数能一层层传下去（n=" + numVarOf(pass, "n") + "）", numVarOf(pass, "n") == 8.0);

        check("实参个数不对在编译期被拒",
                compileFails(cast + "on boom(r) { }\non hit { call boom() }\n"));
        check("实参过多的错误信息给出个数",
                contains(compileError(cast + "on boom(r) { }\non hit { call boom(1, 2) }\n"), "2"));
        check("形参不能叫内建量（dt/step/hit…）",
                compileFails(cast + "on boom(step) { }\non hit { call boom(1) }\n"));
        check("形参不能和云级变量同名",
                compileFails("on cast { let n = 0\n emit { element: fire, count: 8 } }\n"
                        + "on boom(n) { }\non hit { call boom(1) }\n"));
        check("形参不能重复", compileFails(cast + "on boom(r, r) { }\non hit { call boom(1, 2) }\n"));

        // ---- hit.block：撞到的是什么方块（配合字符串字面量） ----
        SpellCloud ice = falling(SpellScript.compile("blk1", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on hit { if (hit.block == \"test:ice\") { n = 1 } }\n"), 41L, ICE_FLOOR);
        ice.step();
        check("hit.block 能按名字比对（撞到 test:ice）", numVarOf(ice, "n") == 1.0);

        SpellCloud stone = falling(SpellScript.compile("blk2", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on hit { if (hit.block == \"test:stone\") { n = 1 } }\n"), 42L, ICE_FLOOR);
        stone.step();
        check("撞错方块时比对为假", numVarOf(stone, "n") == 0.0);

        SpellCloud unknown = falling(SpellScript.compile("blk3", "on cast { let n = 0\n"
                + " emit { element: fire, count: 8 } }\n"
                + "on hit { if (hit.block == \"\") { n = 1 } }\n"), 43L);
        unknown.step();
        check("世界说不出方块身份时 hit.block 是空串（离机假世界）", numVarOf(unknown, "n") == 1.0);

        check("字符串不能存进云级变量（不上生成包）",
                castFails("on cast { let s = \"x\"\n emit { element: fire, count: 8 } }\n", "str"));
        check("字符串进不了快速路径（tick 里用会被拒并说明）",
                contains(reasonOf("", "let s = \"x\"\n for p in particles { p.pos.x = 1 }"), "字符串"));

        // ---- 快速路径 ----
        String callInTick = cast + "on boom { let k = 1 }\n"
                + "on tick(dt) { if (step > 0) { call boom() }\n for p in particles { p.pos.y = p.pos.y } }\n";
        SpellScript slow = SpellScript.compile("ev6", callInTick);
        boolean fast = slow.prepareFastPath(slow.cast(ZERO, 1L).vars());
        check("on tick 里用 call 会掉快速路径（并说明原因）",
                !fast && contains(slow.fastPathReason(), "call"));
    }

    /** 读一个 num 型云级变量（事件用例里看计数用） */
    private static double numVarOf(SpellCloud cloud, String name)
    {
        Object v = cloud.scriptVars.get(name);
        return v instanceof Value.Num n ? n.v() : Double.NaN;
    }

    // ------------------------------------------------------------ 伤害（hurt）

    /**
     * {@code hurt(半径, 伤害[, 位置])} 的语义。
     *
     * <p>这条链上"打没打中真人"只能实机验证（要 ServerLevel），离机这边钉的是三件能钉的事：
     * <b>打在哪、打多少、打几次</b>，以及<b>客户端（没有出口）绝不能有任何副作用</b>。</p>
     */
    private static void damage()
    {
        // ---- on hit 打伤害：默认落在撞击点 ----
        RecordingHurt sink = new RecordingHurt();
        SpellCloud ball = falling(SpellScript.compile("hurt_hit",
                HEAD + "on hit { hurt(4.0, 8.0) }\n"), 21L);
        ball.setWorld(sink);
        ball.step();
        check("on hit 里 hurt 打了一次（" + sink.calls.size() + "）", sink.calls.size() == 1);
        if (!sink.calls.isEmpty())
        {
            double[] c = sink.calls.get(0);
            check(String.format("hurt 的默认落点是撞击点（%.2f, %.2f, %.2f）", c[0], c[1], c[2]),
                    Math.abs(c[0] - 0.5) < 1.0E-9
                            && Math.abs(c[1] - (50.0 + ball.particleRadius())) < 1.0E-3
                            && Math.abs(c[2] - 0.5) < 1.0E-9);
            check("hurt 的半径与伤害原样传下去", Math.abs(c[3] - 4.0) < 1.0E-9
                    && Math.abs(c[4] - 8.0) < 1.0E-9);
        }

        // ---- 显式 at ----
        RecordingHurt at = new RecordingHurt();
        SpellCloud atBall = falling(SpellScript.compile("hurt_at",
                HEAD + "on hit { hurt(2.0, 3.0, vec(1.5, 60.0, -2.5)) }\n"), 22L);
        atBall.setWorld(at);
        atBall.step();
        check("hurt 的第三个参数就是落点",
                at.calls.size() == 1 && Math.abs(at.calls.get(0)[0] - 1.5) < 1.0E-9
                        && Math.abs(at.calls.get(0)[1] - 60.0) < 1.0E-9
                        && Math.abs(at.calls.get(0)[2] + 2.5) < 1.0E-9);

        // ---- on expire 打伤害：落在质心 ----
        RecordingHurt late = new RecordingHurt();
        SpellCloud dying = cloud(SpellScript.compile("hurt_expire",
                "on cast { emit { element: fire, count: 16, life: 0.5 } }\n"
                        + "on expire { hurt(3.0, 5.0) }\n"), 23L, false);
        dying.setWorld(late);
        for (int i = 0; i < dying.count; i++)
        {
            dying.setPos(i, -4.0, 52.0, 7.0);
        }
        while (!dying.isDead())
        {
            dying.step();
        }
        dying.runExpireHook();
        check("on expire 里 hurt 打在粒子质心上",
                late.calls.size() == 1 && Math.abs(late.calls.get(0)[0] + 4.0) < 1.0E-9
                        && Math.abs(late.calls.get(0)[1] - 52.0) < 1.0E-9
                        && Math.abs(late.calls.get(0)[2] - 7.0) < 1.0E-9);

        // ---- 终身配额 ----
        RecordingHurt many = new RecordingHurt();
        StringBuilder burst = new StringBuilder(HEAD + "on hit {\n");
        for (int i = 0; i < 12; i++)
        {
            burst.append("  hurt(1.0, 1.0)\n");
        }
        burst.append("}\n");
        SpellCloud spam = falling(SpellScript.compile("hurt_spam", burst.toString()), 24L);
        spam.setWorld(many);
        spam.step();
        check("hurt 每步配额是 " + SpellCloud.MAX_HURT_CALLS_PER_STEP + "（一步里写 12 次 → 收到 "
                + many.calls.size() + " 次）", many.calls.size() == SpellCloud.MAX_HURT_CALLS_PER_STEP);

        // ---- 客户端（没有出口）：什么也不发生，而且不影响粒子轨迹 ----
        RecordingHurt serverSide = new RecordingHurt();
        SpellCloud withSink = falling(SpellScript.compile("hurt_side",
                HEAD + "on hit { hurt(4.0, 8.0)\n for p in particles { p.pos.x = 1.0 } }\n"), 25L);
        SpellCloud noSink = falling(SpellScript.compile("hurt_side_c",
                HEAD + "on hit { hurt(4.0, 8.0)\n for p in particles { p.pos.x = 1.0 } }\n"), 25L);
        withSink.setWorld(serverSide);
        withSink.step();
        noSink.step();
        check("没有出口时 hurt 是 no-op（客户端不报错、不崩）", !noSink.broken
                && noSink.hurtCalls() == 0);
        check("打不打伤害不影响粒子轨迹（两端 hash 一致）",
                withSink.stateHash() == noSink.stateHash());

        // ---- 静态检查 ----
        check("on tick 里 hurt 在编译期被拒",
                compileFails(HEAD + "on tick(dt) { hurt(2.0, 3.0) }\n"));
        check("on cast 里 hurt 在编译期被拒",
                compileFails("on cast { hurt(2.0, 3.0)\n emit { element: fire, count: 8 } }\n"));
        check("粒子循环里 hurt 在编译期被拒",
                compileFails(HEAD + "on hit { for p in particles { hurt(2.0, 3.0) } }\n"));
        check("拒绝原因说清了是哪条规矩",
                contains(compileError(HEAD + "on tick(dt) { hurt(2.0, 3.0) }\n"), "on hit"));
        check("循环里拒绝的原因提到循环",
                contains(compileError(HEAD + "on hit { for p in particles { hurt(2.0, 3.0) } }\n"),
                        "循环"));

        // ---- 参数校验（运行期，因为实参是表达式） ----
        RecordingHurt bad = new RecordingHurt();
        SpellCloud tooBig = falling(SpellScript.compile("hurt_big",
                HEAD + "on hit { hurt(99.0, 8.0) }\n"), 26L);
        tooBig.setWorld(bad);
        tooBig.step();
        check("半径超上限在运行期报错（云 broken）", tooBig.broken && bad.calls.isEmpty());
        check("半径报错信息提到范围", contains(tooBig.breakReason, "半径"));

        SpellCloud zeroDamage = falling(SpellScript.compile("hurt_zero",
                HEAD + "on hit { hurt(2.0, 0.0) }\n"), 27L);
        zeroDamage.setWorld(bad);
        zeroDamage.step();
        check("伤害为 0 在运行期报错", zeroDamage.broken && contains(zeroDamage.breakReason, "伤害"));

        // ---- 返回值是 void：脚本读不到"打到了几个" ----
        SpellCloud voidUse = falling(SpellScript.compile("hurt_void",
                HEAD + "on hit { let d = hurt(2.0, 3.0)\n for p in particles { p.pos.x = d } }\n"), 29L);
        voidUse.step();
        check("hurt 的返回值是 void，当数字用会报错", voidUse.broken);
    }

    /** 记录型世界出口：验证"打在哪、打多少、打了几次"，以及碰到谁、单点打了谁 */
    private static final class RecordingHurt implements SpellWorld
    {
        final java.util.List<double[]> calls = new java.util.ArrayList<>();
        /** {@code hurt(0, …)} 的单点伤害：{ entityId, damage } */
        final java.util.List<double[]> entityCalls = new java.util.ArrayList<>();
        /** 本步要报告的"碰到的实体"（由用例设置） */
        java.util.List<Touch> nextTouches = java.util.List.of();

        @Override
        public int hurt(double x, double y, double z, double radius, double damage)
        {
            calls.add(new double[]{x, y, z, radius, damage});
            return 1;
        }

        @Override
        public int hurtEntity(long entityId, double damage)
        {
            entityCalls.add(new double[]{entityId, damage});
            return 1;
        }

        @Override
        public java.util.List<Touch> touches(com.wizardmagic.core.spell.SpellCloud cloud)
        {
            java.util.List<Touch> out = nextTouches;
            nextTouches = java.util.List.of();   // 只报一次，模拟"这一步碰到了"
            return out;
        }
    }

    // ------------------------------------------------------------ 碰到生物（on touch）

    /**
     * {@code on touch}：碰到生物实体的事件（**只在服务端**，每个实体一步一次）。
     *
     * <p>离机这边钉的是四件事：事件能拿到哪个实体、{@code hurt(0, …)} 只打那个实体、
     * 接触点当默认落点、{@code end()} 能立刻结束这朵云（并且不触发 {@code on expire}）。</p>
     */
    private static void touch()
    {
        String cast = "on cast { let n = 0\n emit { element: fire, count: 8 } }\n";
        RecordingHurt world = new RecordingHurt();

        // ---- 触发与实体信息 ----
        SpellCloud simple = cloud(SpellScript.compile("t1", cast + "on touch { n = n + 1 }\n"),
                51L, false);
        simple.setWorld(world);
        world.nextTouches = java.util.List.of(new SpellWorld.Touch(42L, "minecraft:zombie",
                1.0, 64.0, 2.0, 18.0, 1.2, 64.4, 2.1));
        simple.step();
        check("on touch 每碰到一个实体触发一次（n=" + numVarOf(simple, "n") + "）",
                numVarOf(simple, "n") == 1.0);

        SpellCloud info = cloud(SpellScript.compile("t2", cast
                + "on touch { if (touch.entity.type == \"minecraft:zombie\") { n = 1 } }\n"), 52L, false);
        info.setWorld(world);
        world.nextTouches = java.util.List.of(new SpellWorld.Touch(7L, "minecraft:zombie",
                0.0, 64.0, 0.0, 20.0, 0.0, 64.0, 0.0));
        info.step();
        check("能按 touch.entity.type 判断是什么生物", numVarOf(info, "n") == 1.0);

        SpellCloud nums = cloud(SpellScript.compile("t3", cast
                + "on touch { n = touch.entity.id + touch.entity.health + touch.entity.pos.y"
                + " + touch.pos.y }\n"), 53L, false);
        nums.setWorld(world);
        world.nextTouches = java.util.List.of(new SpellWorld.Touch(5L, "minecraft:cow",
                3.0, 70.0, 4.0, 10.0, 3.5, 71.0, 4.5));
        nums.step();
        check("实体 id / 血量 / 位置 / 接触点都能读（n=" + numVarOf(nums, "n") + "）",
                numVarOf(nums, "n") == 5.0 + 10.0 + 70.0 + 71.0);

        // ---- hurt(0, …)：只打碰到的那个实体 ----
        RecordingHurt single = new RecordingHurt();
        SpellCloud poke = cloud(SpellScript.compile("t4", cast + "on touch { hurt(0, 6.0) }\n"),
                54L, false);
        poke.setWorld(single);
        single.nextTouches = java.util.List.of(new SpellWorld.Touch(99L, "minecraft:pig",
                0.0, 64.0, 0.0, 10.0, 0.0, 64.0, 0.0));
        poke.step();
        check("hurt(0, 伤害) 只打碰到的那个实体（收到 " + single.entityCalls.size() + " 次单点伤害）",
                single.entityCalls.size() == 1 && single.entityCalls.get(0)[0] == 99.0
                        && Math.abs(single.entityCalls.get(0)[1] - 6.0) < 1.0E-9);
        check("半径 0 不会顺手打一片（没有范围伤害调用）", single.calls.isEmpty());

        // ---- 半径 > 0：以接触点为圆心 ----
        RecordingHurt area = new RecordingHurt();
        SpellCloud splash = cloud(SpellScript.compile("t5", cast + "on touch { hurt(2.5, 4.0) }\n"),
                55L, false);
        splash.setWorld(area);
        area.nextTouches = java.util.List.of(new SpellWorld.Touch(11L, "minecraft:zombie",
                1.0, 64.0, 2.0, 20.0, 1.5, 64.5, 2.5));
        splash.step();
        check("on touch 里写半径 > 0 时以接触点为圆心",
                area.calls.size() == 1
                        && Math.abs(area.calls.get(0)[0] - 1.5) < 1.0E-9
                        && Math.abs(area.calls.get(0)[1] - 64.5) < 1.0E-9
                        && Math.abs(area.calls.get(0)[2] - 2.5) < 1.0E-9);

        // ---- 一个实体一步只触发一次；多个实体各自触发 ----
        RecordingHurt multi = new RecordingHurt();
        SpellCloud crowd = cloud(SpellScript.compile("t6", cast + "on touch { n = n + 1 }\n"),
                56L, false);
        crowd.setWorld(multi);
        java.util.List<SpellWorld.Touch> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            many.add(new SpellWorld.Touch(100 + i, "minecraft:zombie", 0, 64, 0, 20, 0, 64, 0));
        }
        multi.nextTouches = many;
        crowd.step();
        check("一步最多对 " + SpellCloud.MAX_TOUCHES_PER_STEP + " 个实体触发（收到 "
                        + (int) numVarOf(crowd, "n") + "）",
                numVarOf(crowd, "n") == SpellCloud.MAX_TOUCHES_PER_STEP);

        // ---- 配额是"每步"的，不是终身的 ----
        // 实机反馈：火焰风暴糊在一群铁傀儡身上，打两下之后再也不掉血——根因是终身 8 次的伤害
        // 配额被"每步 4 个实体 × 2 步"烧光了，后面所有 hurt 都被静默丢弃。
        RecordingHurt ongoing = new RecordingHurt();
        SpellCloud dot = cloud(SpellScript.compile("t9", cast + "on touch { hurt(0, 2.0) }\n"),
                60L, false);
        dot.setWorld(ongoing);
        for (int i = 0; i < 20; i++)
        {
            ongoing.nextTouches = java.util.List.of(new SpellWorld.Touch(7L, "minecraft:iron_golem",
                    0.0, 64.0, 0.0, 100.0, 0.0, 64.0, 0.0));
            dot.step();
        }
        check("常在的云每一步都能打伤害（20 步 → " + ongoing.entityCalls.size() + " 次）",
                ongoing.entityCalls.size() == 20);

        RecordingHurt perStep = new RecordingHurt();
        SpellCloud burst = cloud(SpellScript.compile("t10", cast + "on touch { hurt(0, 1.0) }\n"),
                61L, false);
        burst.setWorld(perStep);
        java.util.List<SpellWorld.Touch> pack = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++)
        {
            pack.add(new SpellWorld.Touch(200 + i, "minecraft:zombie", 0, 64, 0, 20, 0, 64, 0));
        }
        perStep.nextTouches = pack;
        burst.step();
        perStep.nextTouches = pack;
        burst.step();
        check("每步配额会重置（两步 × 12 个实体 → 16 次单点伤害）",
                perStep.entityCalls.size() == 2 * SpellCloud.MAX_HURT_CALLS_PER_STEP);

        // ---- end()：立刻结束这朵云，而且不跑 on expire ----
        RecordingHurt byEnd = new RecordingHurt();
        SpellCloud boom = cloud(SpellScript.compile("t7", cast
                + "on touch { hurt(0, 3.0)\n end() }\n"
                + "on expire { n = n + 100 }\n"), 57L, false);
        boom.setWorld(byEnd);
        byEnd.nextTouches = java.util.List.of(new SpellWorld.Touch(1L, "minecraft:zombie",
                0.0, 64.0, 0.0, 20.0, 0.0, 64.0, 0.0));
        boom.step();
        check("end() 让云立刻死亡（isDead）", boom.ended() && boom.isDead());
        boom.runExpireHook();
        check("end() 结束的云不跑 on expire（n 仍是 0）", numVarOf(boom, "n") == 0.0);

        // ---- 客户端（没有出口）：不触发、不报错 ----
        SpellCloud client = cloud(SpellScript.compile("t8", cast + "on touch { n = n + 1 }\n"),
                58L, false);
        client.step();
        check("没有世界出口时 on touch 不触发（客户端）",
                !client.broken && numVarOf(client, "n") == 0.0);

        // ---- 静态检查 ----
        check("半径 0 写在 on hit 里运行期报错（只有 on touch 才有被碰到的那个实体）",
                hitZeroRadiusFails());
        check("on tick 里 end() 在编译期被拒",
                compileFails(cast + "on tick(dt) { end()\n for p in particles { p.pos.x = 1 } }\n"));
        check("on touch 里可以读 touch.pos / touch.entity",
                !compileFails(cast + "on touch { let d = dist(touch.pos, touch.entity.pos) }\n"));
        check("事件形参不能叫 touch",
                compileFails(cast + "on boom(touch) { }\non hit { call boom(1) }\n"));
    }

    /** {@code hurt(0, …)} 在 on hit 里应该在运行期报错（不是静默无害） */
    private static boolean hitZeroRadiusFails()
    {
        SpellCloud falling = falling(SpellScript.compile("zero_r",
                HEAD + "on hit { hurt(0, 3.0) }\n"), 59L);
        falling.step();
        return falling.broken && contains(falling.breakReason, "on touch");
    }

    // ------------------------------------------------------------ 运行期诊断

    /**
     * 运行期出错必须指到真实行：以前寄存器机固定写 {@code (1, 1)}，
     * tick 里除零只能看到"第 1 行第 1 列"，等于没有。
     */
    private static void runtimeDiagnostics()
    {
        String src = HEAD + "on tick(dt) {\n"      // 1
                + "  let k = 0\n"                   // 3
                + "  for p in particles {\n"        // 4
                + "    p.energy = p.energy / k\n"   // 5
                + "  }\n"
                + "}\n";
        check("解释器：除零报在第 5 行", breakLine(src, true) == 5);
        check("寄存器机：除零报在第 5 行（不再是 1,1）", breakLine(src, false) == 5);
        check("broken 的原因带着行列", breakReason(src, false).contains("第 5 行"));

        String src2 = HEAD + "on tick(dt) {\n"
                + "  let d = 0\n"
                + "  for p in particles { p.energy = 1 / d }\n"
                + "}\n";
        check("另一个位置也指得对（第 4 行）", breakLine(src2, false) == 4
                && breakLine(src2, true) == 4);
        check("两个后端的报错行一致", breakLine(src, false) == breakLine(src, true));
    }

    // ------------------------------------------------------------ 快路径诊断

    /** 退化原因不能只说"不支持"：要能指到是哪条写法 */
    private static void fastPathDiagnostics()
    {
        check("普通脚本能编出快速路径",
                reasonOf("", "for p in particles { p.pos.x = p.pos.x + 0.1 }") == null);
        check("顶层 if 的退化原因指向顶层",
                contains(reasonOf("", "if (time > 1) { }\nfor p in particles { p.pos.x = 1 }"), "顶层"));
        check("循环后还有语句的退化原因",
                contains(reasonOf("", "for p in particles { p.pos.x = 1 }\nlet a = 1"), "循环之后"));
        check("不支持的内建函数给出原因（rand 已经能编了，用未知函数测）",
                contains(reasonOf("", "for p in particles { p.pos.x = nope(1) }"), "函数"));
        check("不支持的写法给出原因：element 型 cast 变量只能当字面量用",
                contains(reasonOf("let e = fire", "for p in particles { p.pos.x = gravity(e) }"),
                        "元素字面量"));
        check("读 num 型 cast 变量能编",
                reasonOf("let g = 1", "for p in particles { p.pos.x = p.pos.x + g }") == null);
        check("读 vec3 型 cast 变量能编",
                reasonOf("let v = vec(1, 2, 3)", "for p in particles { p.pos = p.pos + v * dt }") == null);
    }

    // ------------------------------------------------------------ 快路径覆盖

    /**
     * 哪些写法从"只能解释"变成"能编译"了。
     *
     * <p>这一组是实战性能的直接来源：掉出快路径就是 2.5–5.5 倍慢，
     * 所以每放开一条写法都要有语义对拍的用例钉住。</p>
     */
    private static void fastPathCoverage()
    {
        // rand()
        check("rand() 能编进快速路径", reasonOf("", "for p in particles { p.pos.x = rand() }") == null);
        check("rand() 的语义与解释器逐位一致（30 步）",
                sameHash("p.pos.x = p.pos.x + rand() * 0.01", 30));
        check("rand() 参与速度也一致",
                sameHash("p.v = p.v + vec(rand() - 0.5, 0, rand() - 0.5) * 0.1", 30));
        check("两个循环都用 rand() 时序列接着数（与解释器一致）",
                sameHashTick("for p in particles { p.pos.x = p.pos.x + rand() * 0.01 }\n"
                        + "for p in particles { p.pos.y = p.pos.y + rand() * 0.01 }", 20));
        check("顶层 let 用 rand() 会退化并说明原因",
                contains(reasonOf("", "let r = rand()\nfor p in particles { p.pos.x = r }"), "rand"));
        check("守卫条件里用 rand() 也会退化", contains(reasonOf("",
                "if (rand() > 0.5) { for p in particles { p.pos.x = 1 } }"), "rand"));
        check("带 rand() 的云不允许切段并行（序列按粒子顺序）",
                !parallelSafe("p.pos.x = rand()"));
        check("不带 rand() 的云允许切段并行",
                parallelSafe("p.pos.x = p.pos.x + dt"));

        // 顶层 if
        check("顶层 if 能编进快速路径",
                reasonOf("", "if (step > 1000) { for p in particles { p.pos.x = 1 } }") == null);
        check("顶层 if（条件为假）不动粒子",
                Math.abs(xAfter("if (step > 1000) { for p in particles { p.pos.x = 5 } }", 5)) < 1.0);
        check("顶层 if（条件为真）动粒子",
                Math.abs(xAfter("if (step >= 0) { for p in particles { p.pos.x = 5 } }", 1) - 5.0) < 1.0E-9);
        check("顶层 if 的 else 分支也能编",
                Math.abs(xAfter("if (step > 1000) { for p in particles { p.pos.x = 5 } }"
                        + " else { for p in particles { p.pos.x = 7 } }", 1) - 7.0) < 1.0E-9);
        check("顶层 if 双后端逐位一致（条件为真）",
                sameHashTick("if (step >= 0) { for p in particles { p.pos.y = p.pos.y + 0.1 } }", 20));
        check("顶层 if 双后端逐位一致（走 else）",
                sameHashTick("if (step > 999) { for p in particles { p.pos.x = 1 } }"
                        + " else { for p in particles { p.pos.y = p.pos.y - 0.05 } }", 20));
        check("顶层 if 的守卫进得了 .spellc 缓存", guardCache());
        check("顶层 if 里没有粒子循环 → 退化并说明原因",
                contains(reasonOf("", "if (step > 0) { let a = 1 }"), "粒子循环"));

        // bool 型 cast 变量
        check("bool 型 cast 变量能编进快速路径",
                reasonOf("let b = true", "for p in particles { if (b) { p.pos.x = 1 } }") == null);
        check("bool 型 cast 变量的语义与解释器一致",
                sameHashTickVar("let b = true", "if (b) { for p in particles { p.pos.y = p.pos.y + 0.01 } }", 20));

        // 写云级变量（跨 tick 状态）
        check("写云级变量能编进快速路径",
                reasonOf("let hits = 0", "for p in particles { hits = hits + 1 }") == null);
        check("写云级变量：累计值与解释器一致（3 步 × 8 粒 = 24）",
                numVar("let hits = 0", "for p in particles { hits = hits + 1 }", 3, "hits", false) == 24.0
                        && numVar("let hits = 0", "for p in particles { hits = hits + 1 }", 3, "hits", true) == 24.0);
        check("写云级变量：复合赋值 += 也一致（2 步 × 8 粒 = 16）",
                numVar("let hits = 0", "for p in particles { hits += 1 }", 2, "hits", false) == 16.0
                        && numVar("let hits = 0", "for p in particles { hits += 1 }", 2, "hits", true) == 16.0);
        check("写 vec3 型云级变量也一致",
                sameHashTickVar("let v = vec(1, 0, 0)",
                        "for p in particles { v = v * 0.9\n p.pos = p.pos + v * dt }", 10));
        check("写云级变量会反馈到粒子（双后端一致）",
                sameHashTickVar("let hits = 0",
                        "for p in particles { hits = hits + 1\n p.pos.x = hits * 0.01 }", 10));
        check("写云级变量的云不切段并行", !parallelSafeVar("let hits = 0", "hits = hits + 1"));
        check("多个循环都写云级变量 → 退化并说明原因", contains(reasonOf("let hits = 0",
                "for p in particles { hits = hits + 1 }\nfor p in particles { hits = hits + 1 }"),
                "单个粒子循环"));
        check("给内置量赋值 → 退化并说明原因", contains(reasonOf("", "for p in particles { dt = 1 }"), "内置量"));
        check("给外提量赋值 → 退化并说明原因", contains(reasonOf("",
                "for p in particles { let k = 1\n k = 2 }"), "外提量"));
    }

    // ------------------------------------------------------------ 字节码往返

    private static void bytecodeRoundTrip()
    {
        String src = HEAD + "on tick(dt) {\n"
                + "  for p in particles {\n"
                + "    p.pos.y = p.pos.y + 0.05 * sin(p.age * 3)\n"
                + "    if (p.energy > 40) { p.energy = p.energy - 0.5 }\n"
                + "  }\n"
                + "}\n";
        SpellScript script = SpellScript.compile("spec_rt", src);
        check("往返用例本身能编出快速路径", script.prepareFastPath(Map.of()));
        byte[] blob = script.fastBytecode();
        check("能落盘成 .spellc", blob != null && blob.length > 64);

        SpellScript reloaded = SpellScript.compile("spec_rt2", src);
        check("缓存被采纳", reloaded.adoptFastBytecode(blob));
        check("采纳后就是快速路径", reloaded.fastPathActive());
        check("缓存版与解释器逐位一致（40 步）", hash(reloaded, false, 40) == hash(reloaded, true, 40));

        String errSrc = HEAD + "on tick(dt) {\n"
                + "  let k = 0\n"
                + "  for p in particles {\n"
                + "    p.energy = p.energy / k\n"
                + "  }\n"
                + "}\n";
        SpellScript errScript = SpellScript.compile("spec_rt_err", errSrc);
        errScript.prepareFastPath(Map.of());
        byte[] errBlob = errScript.fastBytecode();
        SpellScript errReloaded = SpellScript.compile("spec_rt_err2", errSrc);
        check("错误用例的缓存被采纳", errReloaded.adoptFastBytecode(errBlob));
        check("缓存版的除零仍报第 5 行", breakReasonOf(errReloaded, false).contains("第 5 行"));
    }

    // ------------------------------------------------------------ 双后端对拍

    /**
     * 一批「应该是快速路径能编」的片段，逐个跑解释器与寄存器机，结果必须逐位一致。
     *
     * <p>本套最值钱的一条：语言语义、指令集、寄存器分配改动之后，哪个后端悄悄变了
     * 都会在这里露出来。</p>
     */
    private static void backendEquivalence()
    {
        String[][] snippets = {
                {"算术与比较", "p.pos.x = (p.pos.y * 2 + 1) / 3 - 0.5"},
                {"分支", "if (p.age > 0.5) { p.energy = p.energy - 1 } else { p.energy = p.energy + 1 }"},
                {"嵌套分支", "if (p.age > 0.2) { if (p.energy > 30) { p.pos.z = 1 } }"},
                {"vec3 运算", "p.pos = p.pos + vec(0.1, 0.2, 0.3) * p.age"},
                {"向量函数", "p.pos = p.pos + normalize(vec(1, 2, 3)) * 0.01"},
                {"短路 and", "if ((p.energy > 10) and (p.age > 0.1)) { p.pos.y = p.pos.y + 0.01 }"},
                {"短路 or", "if ((p.energy > 999) or (p.age > 0.1)) { p.pos.y = p.pos.y - 0.01 }"},
                {"not 与比较", "if (not (p.age > 0.3)) { p.pos.x = 0.5 }"},
                {"内建数学", "p.pos.y = p.pos.y + clamp(smoothstep(0, 2, p.age), 0, 1) * 0.02"},
                {"取模", "p.pos.x = p.pos.x + (10 % 3) + (p.age % 2.5)"},
                {"符号", "p.pos.y = p.pos.y + sign(p.age - 0.5) * 0.01"},
                {"幂与反正切", "p.pos.z = pow(p.age + 1, 1.5) * 0.001 + atan2(p.age, 1) * 0.01"},
                {"循环外常量", "let k = dt * 2\n p.pos.y = p.pos.y + k"},
                {"能量 float 截断", "p.energy = p.energy + 0.3333333333333333"},
                {"复合赋值", "p.pos.x += 0.25\n p.energy -= 1.5"},
                {"粒子属性", "p.pos.z = p.targetR + p.r"},
                {"取模替代（floor 回绕）", "p.pos.x = p.pos.x - floor(p.pos.x / 2) * 2"},
                {"绕任意轴旋转", "p.pos = rotate(p.pos, vec(0, 1, 1), p.age * 0.5)"},
                {"任意轴切向", "p.pos = p.pos + tangent(p.axis, p.pos, vec(0, 0, 1)) * 0.01"},
                {"任意轴径向", "p.pos = p.pos + radial(p.axis, p.pos, vec(1, 0, 0)) * 0.01"},
        };
        for (String[] s : snippets)
        {
            String src = tickOnly(s[1]);
            long compiled = hash(SpellScript.compile("eq_" + s[0], src), false, 30);
            long interpreted = hash(SpellScript.compile("eq_" + s[0] + "_i", src), true, 30);
            check("双后端逐位一致：" + s[0], compiled == interpreted);
        }
    }

    // ------------------------------------------------------------ 用例工具

    /** 只写循环体的片段：套上固定的 on cast 头，方便逐条比语义 */
    private static String tickOnly(String body)
    {
        return HEAD + "on tick(dt) {\n  for p in particles {\n    "
                + body.replace("\n", "\n    ") + "\n  }\n}\n";
    }

    /** 把表达式写进 p.pos.x 再读回来（num 表达式） */
    private static double num(String expr)
    {
        SpellCloud c = cloud(SpellScript.compile("num", tickOnly("p.pos.x = " + expr)), 3L, false);
        c.step();
        return c.x(0);
    }

    /** 把一个 bool 表达式变成 1/0 写进 p.pos.x */
    private static double flag(String condExpr)
    {
        SpellCloud c = cloud(SpellScript.compile("flag",
                tickOnly("if (" + condExpr + ") { p.pos.x = 1 } else { p.pos.x = 0 }")), 3L, false);
        c.step();
        return c.x(0);
    }

    /** 循环体里执行若干语句，返回第 0 颗粒子的 x */
    private static double numAfter(String loopBody)
    {
        SpellCloud c = cloud(SpellScript.compile("after", tickOnly(loopBody)), 3L, false);
        c.step();
        return c.x(0);
    }

    private static double yAfter(String loopBody)
    {
        SpellCloud c = cloud(SpellScript.compile("afterY", tickOnly(loopBody)), 3L, false);
        c.step();
        return c.y(0);
    }

    private static double zAfter(String loopBody)
    {
        SpellCloud c = cloud(SpellScript.compile("afterZ", tickOnly(loopBody)), 3L, false);
        c.step();
        return c.z(0);
    }

    /** 跑一步后第 0 颗粒子的位置 */
    private static double[] posAfter(String loopBody)
    {
        SpellCloud c = cloud(SpellScript.compile("posAfter", tickOnly(loopBody)), 3L, false);
        c.step();
        return new double[]{c.x(0), c.y(0), c.z(0)};
    }

    private static double len(double[] v)
    {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static float energyAfter(String loopBody)
    {
        SpellCloud c = cloud(SpellScript.compile("energy", tickOnly(loopBody)), 3L, false);
        c.step();
        return c.energy(0);
    }

    private static boolean ok(String loopBody)
    {
        return !broken(loopBody);
    }

    private static boolean broken(String loopBody)
    {
        return broken(loopBody, false);
    }

    /** 强制解释器跑，看它炸不炸 */
    private static boolean brokenInterp(String loopBody)
    {
        return broken(loopBody, true);
    }

    private static boolean broken(String loopBody, boolean interpreted)
    {
        SpellCloud c = cloud(SpellScript.compile("b", tickOnly(loopBody)), 5L, interpreted);
        c.step();
        return c.broken;
    }

    /** 错误消息里含 sub：编译期抛的、或者运行期 broken 的，都算 */
    private static boolean excepts(String loopBody, String sub)
    {
        try
        {
            SpellCloud c = cloud(SpellScript.compile("e", tickOnly(loopBody)), 5L, false);
            c.step();
            return c.broken && c.breakReason != null && c.breakReason.contains(sub);
        }
        catch (ScriptException e)
        {
            return e.getMessage() != null && e.getMessage().contains(sub);
        }
    }

    private static boolean compileFails(String src)
    {
        try
        {
            SpellScript.compile("c", src);
            return false;
        }
        catch (ScriptException e)
        {
            return true;
        }
    }

    /** 编译期错误的文本（编译通过返回空串） */
    private static String compileError(String src)
    {
        try
        {
            SpellScript.compile("c", src);
            return "";
        }
        catch (ScriptException e)
        {
            return e.getMessage() == null ? "" : e.getMessage();
        }
    }

    private static boolean castFails(String src, String sub)
    {
        try
        {
            SpellScript.compile("c", src).cast(ZERO, 1L);
            return false;
        }
        catch (ScriptException e)
        {
            return e.getMessage() != null && e.getMessage().contains(sub);
        }
    }

    private static boolean castAt(String src)
    {
        EmitSpec e = firstEmit(src);
        return e != null && Math.abs(e.x() - 3.0) < 1.0E-9 && Math.abs(e.y() - 70.0) < 1.0E-9
                && Math.abs(e.z() + 2.0) < 1.0E-9;
    }

    private static int castCount(String src)
    {
        EmitSpec e = firstEmit(src);
        return e == null ? -1 : e.count();
    }

    private static float castLife(String src)
    {
        EmitSpec e = firstEmit(src);
        return e == null ? -1.0F : e.lifeSec();
    }

    /** 施法产出的云朝向是不是 +X */
    private static boolean castFacing(String src)
    {
        EmitSpec e = firstEmit(src);
        return e != null && Math.abs(e.faceX() - 1.0) < 1.0E-9
                && Math.abs(e.faceY()) < 1.0E-9 && Math.abs(e.faceZ()) < 1.0E-9;
    }

    private static EmitSpec firstEmit(String src)
    {
        CastResult r = SpellScript.compile("c", src).cast(ZERO, 1L);
        return r.emits().isEmpty() ? null : r.emits().get(0);
    }

    private static SpellCloud cloud(SpellScript script, long seed, boolean interpreted)
    {
        CastResult r = script.cast(ZERO, seed);
        if (r.emits().isEmpty())
        {
            throw new IllegalStateException("脚本没有 emit");
        }
        EmitSpec e = r.emits().get(0);
        SpellCloud c = new SpellCloud(1, seed, e.elementId(), e.count(), e.x(), e.y(), e.z(),
                e.lifeSec(), e.shapeId(), e.params(), script, r.vars());
        c.setInterpreted(interpreted);
        return c;
    }

    private static long hash(SpellScript script, boolean interpreted, int steps)
    {
        SpellCloud c = cloud(script, 11L, interpreted);
        for (int i = 0; i < steps; i++)
        {
            c.step();
        }
        return c.stateHash();
    }

    private static boolean sameHash(String loopBody, int steps)
    {
        String src = tickOnly(loopBody);
        return hash(SpellScript.compile("eq", src), false, steps)
                == hash(SpellScript.compile("eq_i", src), true, steps);
    }

    /** broken 的原因文本（没坏就是空串） */
    private static String breakReason(String src, boolean interpreted)
    {
        SpellCloud c = cloud(SpellScript.compile("br", src), 7L, interpreted);
        c.step();
        return c.broken && c.breakReason != null ? c.breakReason : "";
    }

    private static String breakReasonOf(SpellScript script, boolean interpreted)
    {
        SpellCloud c = cloud(script, 7L, interpreted);
        c.step();
        return c.broken && c.breakReason != null ? c.breakReason : "";
    }

    /** broken 原因里报的行号；没坏返回 -1 */
    private static int breakLine(String src, boolean interpreted)
    {
        String reason = breakReason(src, interpreted);
        int i = reason.indexOf("第 ");
        int j = reason.indexOf(" 行", i);
        if (i < 0 || j < 0)
        {
            return -1;
        }
        try
        {
            return Integer.parseInt(reason.substring(i + 2, j).trim());
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    /** 快速路径退化原因（null = 有快速路径），走真实路径：cast → prepare */
    private static String reasonOf(String castStatements, String tickStatements)
    {
        String src = "on cast {\n"
                + (castStatements.isEmpty() ? "" : "  " + castStatements.replace("\n", "\n  ") + "\n")
                + "  emit { element: fire, count: 32 }\n}\n"
                + "on tick(dt) {\n" + tickStatements.replace("\n", "\n  ") + "\n}\n";
        SpellScript script = SpellScript.compile("reason", src);
        SpellCloud c = cloud(script, 9L, false);
        c.step();
        return script.fastPathReason();
    }

    /** 这段循环体编出来的程序允许切段并行吗 */
    private static boolean parallelSafe(String loopBody)
    {
        SpellScript script = SpellScript.compile("par", tickOnly(loopBody));
        if (!script.prepareFastPath(Map.of()))
        {
            return false;
        }
        SpellCloud c = cloud(script, 5L, false);
        SpellScript.Step step = script.prepareStep(c);
        if (step == null)
        {
            return false;
        }
        boolean safe = step.parallelSafe();
        script.completeStep(step, c);
        return safe;
    }

    /** 跑 tick 层语句 {@code steps} 步，返回第 0 颗粒子的 x（走编译路径） */
    private static double xAfter(String tickStatements, int steps)
    {
        SpellCloud c = cloud(SpellScript.compile("xafter", HEAD + "on tick(dt) {\n"
                + tickStatements.replace("\n", "\n  ") + "\n}\n"), 3L, false);
        for (int i = 0; i < steps; i++)
        {
            c.step();
        }
        return c.x(0);
    }

    /** tick 层语句的双后端对拍 */
    private static boolean sameHashTick(String tickStatements, int steps)
    {
        String src = HEAD + "on tick(dt) {\n" + tickStatements.replace("\n", "\n  ") + "\n}\n";
        return hash(SpellScript.compile("eqt", src), false, steps)
                == hash(SpellScript.compile("eqt_i", src), true, steps);
    }

    /** 带一段 cast 语句的双后端对拍（云级变量在 cast 里声明） */
    private static boolean sameHashTickVar(String castStatements, String tickStatements, int steps)
    {
        String src = "on cast {\n  " + castStatements.replace("\n", "\n  ") + "\n"
                + "  emit { element: fire, count: 32 }\n}\n"
                + "on tick(dt) {\n" + tickStatements.replace("\n", "\n  ") + "\n}\n";
        return hash(SpellScript.compile("eqv", src), false, steps)
                == hash(SpellScript.compile("eqv_i", src), true, steps);
    }

    /** 跑 {@code steps} 步后某个云级数字变量的值（读 cloud.scriptVars） */
    private static double numVar(String castStatements, String tickStatements, int steps,
                                 String name, boolean interpreted)
    {
        String src = "on cast {\n  " + castStatements.replace("\n", "\n  ") + "\n"
                + "  emit { element: fire, count: 8 }\n}\n"
                + "on tick(dt) {\n" + tickStatements.replace("\n", "\n  ") + "\n}\n";
        SpellCloud c = cloud(SpellScript.compile("nv", src), 21L, interpreted);
        for (int i = 0; i < steps; i++)
        {
            c.step();
        }
        Object v = c.scriptVars.get(name);
        return v instanceof Value.Num n ? n.v() : Double.NaN;
    }

    /** 带 cast 变量的"这段循环体允许切段并行吗" */
    private static boolean parallelSafeVar(String castStatements, String loopBody)
    {
        String src = "on cast {\n  " + castStatements.replace("\n", "\n  ") + "\n"
                + "  emit { element: fire, count: 32 }\n}\n"
                + "on tick(dt) { for p in particles { " + loopBody + " } }\n";
        SpellScript script = SpellScript.compile("parv", src);
        SpellCloud c = cloud(script, 5L, false);
        SpellScript.Step step = script.prepareStep(c);
        if (step == null)
        {
            return false;
        }
        boolean safe = step.parallelSafe();
        script.completeStep(step, c);
        return safe;
    }

    /** 顶层 if 的守卫量能不能随缓存往返 */
    private static boolean guardCache()
    {
        String src = HEAD + "on tick(dt) {\n"
                + "  if (step > 1000) { for p in particles { p.pos.x = 1 } }\n"
                + "  else { for p in particles { p.pos.y = p.pos.y + 0.01 } }\n}\n";
        SpellScript a = SpellScript.compile("gc1", src);
        if (!a.prepareFastPath(Map.of()))
        {
            return false;
        }
        byte[] blob = a.fastBytecode();
        if (blob == null)
        {
            return false;
        }
        SpellScript b = SpellScript.compile("gc2", src);
        if (!b.adoptFastBytecode(blob))
        {
            return false;
        }
        return hash(b, false, 20) == hash(b, true, 20);
    }

    /** text 含 sub（exact 表示 null 即通过） */
    private static boolean contains(String text, String sub)
    {
        if (sub == null)
        {
            return text == null;
        }
        return text != null && text.contains(sub);
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
