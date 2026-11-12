package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.api.spell.SpellMath;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellElements;
import com.wizardmagic.core.spell.SpellNoise;
import com.wizardmagic.core.spell.SpellRandom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 树遍历求值器。
 *
 * <p>性能不是 P1 的目标（先用起来再测），正确性和确定性是：
 * 同一份源码 + 同一 seed，服务端与客户端的每一步都必须<strong>逐位</strong>一致。
 * 因此：只用双精度算术、没有哈希表迭代顺序依赖、随机数全部由
 * {@code (seed, step, 调用序号)} 派生。</p>
 */
public final class Interpreter
{
    /** cast 求值预算（节点求值次数） */
    private static final long CAST_BUDGET = 1_000_000L;
    /** tick 预算 = 每粒子 2000 次 + 固定开销，且不超过硬上限 */
    private static final long TICK_PER_PARTICLE = 2_000L;
    private static final long TICK_BUDGET_CAP = 20_000_000L;
    private static final int MAX_EMITS = 8;

    private final Ast.Program program;
    private final Host host;
    private final long seed;

    private long evals;
    private long budget;
    private int randSeq;
    private int emitCount;

    private SpellCloud cloud;
    private int step;
    private double dt;
    private boolean castPhase;
    private final List<EmitSpec> emits = new ArrayList<>();

    private Interpreter(Ast.Program program, Host host, long seed)
    {
        this.program = program;
        this.host = host;
        this.seed = seed;
    }

    // ------------------------------------------------------------ 入口

    public static CastResult cast(Ast.Program program, Host host, long seed)
    {
        Interpreter in = new Interpreter(program, host, seed);
        in.castPhase = true;
        in.budget = CAST_BUDGET;
        Env root = new Env(null);
        for (Map.Entry<String, Ast.Expr> e : program.params().entrySet())
        {
            root.define(e.getKey(), in.eval(e.getValue(), root));
        }
        in.run(program.cast(), root);

        Map<String, Value> vars = new HashMap<>();
        for (Map.Entry<String, Value> e : root.snapshot().entrySet())
        {
            if (!e.getValue().storable())
            {
                throw new ScriptException("云级变量 " + e.getKey() + " 的类型是 " + Value.typeOf(e.getValue())
                        + "，不能跨越 cast → tick 传递", 1, 1);
            }
            vars.put(e.getKey(), e.getValue());
        }
        return new CastResult(List.copyOf(in.emits), vars);
    }

    public static void tick(Ast.Program program, SpellCloud cloud)
    {
        Interpreter in = new Interpreter(program, null, cloud.castSeed);
        in.cloud = cloud;
        in.step = cloud.ageSteps();
        in.dt = SpellConstants.DT;
        in.budget = Math.min(TICK_BUDGET_CAP, TICK_PER_PARTICLE * cloud.count + 10_000L);
        Env root = rootEnv(cloud);
        in.run(program.tick(), root);
        saveRoot(cloud, root);
    }

    /** tick 的云级作用域：dt/time/life/count/step + cast 变量 */
    static Env rootEnv(SpellCloud cloud)
    {
        Env root = new Env(null);
        root.define("dt", Value.num(SpellConstants.DT));
        root.define("time", Value.num(cloud.ageSteps() * SpellConstants.DT));
        root.define("life", Value.num(cloud.lifeSec));
        root.define("count", Value.num(cloud.count));
        root.define("step", Value.num(cloud.ageSteps()));
        for (Map.Entry<String, Value> e : cloud.scriptVars.entrySet())
        {
            root.define(e.getKey(), e.getValue());
        }
        return root;
    }

    /** 快速路径用：只跑一条顶层标量语句（与 {@link #tick} 的求值环境一致） */
    static void runScalar(SpellCloud cloud, Ast.Stmt stmt, Env root)
    {
        Interpreter in = new Interpreter(null, null, cloud.castSeed);
        in.cloud = cloud;
        in.step = cloud.ageSteps();
        in.dt = SpellConstants.DT;
        in.budget = Math.min(TICK_BUDGET_CAP, TICK_PER_PARTICLE * cloud.count + 10_000L);
        in.stmt(stmt, root);
    }

    /** 快速路径用：在给定环境里求一个表达式（外提的每步常量） */
    static Value evalExpr(SpellCloud cloud, Env root, Ast.Expr expr)
    {
        Interpreter in = new Interpreter(null, null, cloud.castSeed);
        in.cloud = cloud;
        in.step = cloud.ageSteps();
        in.dt = SpellConstants.DT;
        in.budget = Math.min(TICK_BUDGET_CAP, TICK_PER_PARTICLE * cloud.count + 10_000L);
        return in.eval(expr, root);
    }

    /** 只回写原有的云级变量（tick 顶层 let 是每步重建的临时量） */
    static void saveRoot(SpellCloud cloud, Env root)
    {
        Map<String, Value> out = new HashMap<>();
        for (String name : cloud.scriptVars.keySet())
        {
            Value v = root.get(name);
            if (v != null)
            {
                out.put(name, v);
            }
        }
        cloud.scriptVars = out;
    }

    // ------------------------------------------------------------ 语句

    private void run(List<Ast.Stmt> body, Env env)
    {
        for (Ast.Stmt s : body)
        {
            stmt(s, env);
        }
    }

    private void stmt(Ast.Stmt s, Env env)
    {
        if (s instanceof Ast.Let let)
        {
            env.define(let.name(), eval(let.value(), env));
            return;
        }
        if (s instanceof Ast.Assign assign)
        {
            assign(assign, env);
            return;
        }
        if (s instanceof Ast.If branch)
        {
            if (bool(eval(branch.cond(), env), branch))
            {
                run(branch.then(), new Env(env));
            }
            else
            {
                run(branch.otherwise(), new Env(env));
            }
            return;
        }
        if (s instanceof Ast.ForParticles loop)
        {
            loop(loop, env);
            return;
        }
        if (s instanceof Ast.Emit emitStmt)
        {
            emit(emitStmt, env);
            return;
        }
        if (s instanceof Ast.ExprStmt exprStmt)
        {
            eval(exprStmt.expr(), env);
            return;
        }
        throw new ScriptException("未知语句", s.line(), s.col());
    }

    private void loop(Ast.ForParticles f, Env env)
    {
        if (cloud == null)
        {
            throw new ScriptException("for p in particles 只能出现在 on tick 里", f.line(), f.col());
        }
        Env holder = new Env(env);
        Env body = new Env(holder);
        for (int i = 0; i < cloud.count; i++)
        {
            body.clear();
            holder.define(f.var(), new Value.Particle(cloud, i));
            run(f.body(), body);
        }
    }

    private void assign(Ast.Assign a, Env env)
    {
        Value rhs = eval(a.value(), env);

        if (a.path().isEmpty())
        {
            if (a.op().isEmpty())
            {
                if (!env.assign(a.base(), rhs))
                {
                    throw new ScriptException("未定义的变量 " + a.base(), a.line(), a.col());
                }
                return;
            }
            Value cur = env.get(a.base());
            if (cur == null)
            {
                throw new ScriptException("未定义的变量 " + a.base(), a.line(), a.col());
            }
            env.assign(a.base(), binary(a.op(), cur, rhs, a));
            return;
        }

        Value base = env.get(a.base());
        if (!(base instanceof Value.Particle p))
        {
            throw new ScriptException("只有粒子属性可以这样赋值（基对象是 " + Value.typeOf(base) + "）",
                    a.line(), a.col());
        }
        Value next = rhs;
        if (!a.op().isEmpty())
        {
            next = binary(a.op(), readPath(p, a.path(), a), rhs, a);
        }
        writePath(p, a.path(), next, a);
    }

    private void emit(Ast.Emit e, Env env)
    {
        if (!castPhase)
        {
            throw new ScriptException("on tick 里暂不支持 emit（P2 会配合事件一起做）", e.line(), e.col());
        }
        if (++emitCount > MAX_EMITS)
        {
            throw new ScriptException("一次施法最多 " + MAX_EMITS + " 次 emit", e.line(), e.col());
        }

        Map<String, Value> f = new HashMap<>();
        for (Map.Entry<String, Ast.Expr> en : e.fields().entrySet())
        {
            f.put(en.getKey(), eval(en.getValue(), env));
        }

        if (!(f.get("element") instanceof Value.Elem elem))
        {
            throw new ScriptException("emit 需要 element: <元素>", e.line(), e.col());
        }
        int elementId = SpellElements.indexOf(elem.name());
        if (elementId < 0)
        {
            throw new ScriptException("未知元素 " + elem.name(), e.line(), e.col());
        }

        int count = (int) Math.round(numOf(f.get("count"), 3000.0, e));
        count = Math.max(SpellConstants.MIN_CLOUD_PARTICLES, Math.min(SpellConstants.MAX_CLOUD_PARTICLES, count));
        double life = Math.max(0.1, Math.min(60.0, numOf(f.get("life"), 4.0, e)));

        String shapeId = Shapes.POINT.id();
        ShapeParams params = Shapes.POINT.defaults();
        if (f.get("shape") instanceof Value.Shape sh)
        {
            shapeId = sh.id();
            params = sh.params();
        }
        else if (f.get("shape") != null && !(f.get("shape") instanceof Value.Void))
        {
            throw new ScriptException("emit 的 shape 必须是形状记录，例如 helix { ... }", e.line(), e.col());
        }

        double x = host == null ? 0.0 : host.hereX();
        double y = host == null ? 0.0 : host.hereY();
        double z = host == null ? 0.0 : host.hereZ();
        if (f.get("at") instanceof Value.Vec3 at)
        {
            x = at.x();
            y = at.y();
            z = at.z();
        }

        emits.add(new EmitSpec(elementId, count, (float) life, shapeId, params, x, y, z));
    }

    // ------------------------------------------------------------ 表达式

    private Value eval(Ast.Expr e, Env env)
    {
        if (++evals > budget)
        {
            throw new ScriptException("本步求值次数超过预算（" + budget + "）", e.line(), e.col());
        }
        if (e instanceof Ast.NumLit n)
        {
            return new Value.Num(n.v());
        }
        if (e instanceof Ast.BoolLit b)
        {
            return b.v() ? Value.TRUE : Value.FALSE;
        }
        if (e instanceof Ast.Var v)
        {
            return var(v, env);
        }
        if (e instanceof Ast.Member m)
        {
            return member(eval(m.base(), env), m.name(), m);
        }
        if (e instanceof Ast.Call c)
        {
            return call(c, env);
        }
        if (e instanceof Ast.RecordLit r)
        {
            return record(r, env);
        }
        if (e instanceof Ast.Unary u)
        {
            return unary(u, env);
        }
        if (e instanceof Ast.Binary b)
        {
            return binary(b.op(), eval(b.a(), env), eval(b.b(), env), b);
        }
        throw new ScriptException("未知表达式", e.line(), e.col());
    }

    private Value var(Ast.Var v, Env env)
    {
        Value found = env.get(v.name());
        if (found != null)
        {
            return found;
        }
        int id = SpellElements.indexOf(v.name());
        if (id >= 0)
        {
            return new Value.Elem(id, v.name());
        }
        throw new ScriptException("未知变量或元素 " + v.name(), v.line(), v.col());
    }

    private Value member(Value base, String name, Ast.Node at)
    {
        if (base instanceof Value.Vec3 v)
        {
            switch (name)
            {
                case "x": return Value.num(v.x());
                case "y": return Value.num(v.y());
                case "z": return Value.num(v.z());
                default: throw new ScriptException("vec3 没有属性 " + name, at.line(), at.col());
            }
        }
        if (base instanceof Value.Particle p)
        {
            return readParticle(p, name, at);
        }
        throw new ScriptException(Value.typeOf(base) + " 没有属性 " + name, at.line(), at.col());
    }

    private Value readParticle(Value.Particle p, String name, Ast.Node at)
    {
        SpellCloud c = p.cloud();
        int i = p.index();
        switch (name)
        {
            case "pos": return Value.vec(c.x(i), c.y(i), c.z(i));
            case "v": return Value.vec(c.velX(i), c.velY(i), c.velZ(i));
            case "energy": return Value.num(c.energy(i));
            case "anchor": return Value.vec(c.anchorX(i), c.anchorYWorld(i), c.anchorZ(i));
            case "axis":
            {
                double[] a = c.axisAt(i, time());
                return Value.vec(a[0], a[1], a[2]);
            }
            case "targetR": return Value.num(c.targetRadius(i, time()));
            case "r": return Value.num(c.random01(i));
            case "age": return Value.num(time());
            case "local": return Value.vec(c.x(i) - c.originX, c.y(i) - c.originY, c.z(i) - c.originZ);
            default: throw new ScriptException("粒子没有属性 " + name, at.line(), at.col());
        }
    }

    private Value readPath(Value.Particle p, List<String> path, Ast.Node at)
    {
        if (path.size() == 1)
        {
            return readParticle(p, path.get(0), at);
        }
        if (path.size() == 2)
        {
            Value base = readParticle(p, path.get(0), at);
            return member(base, path.get(1), at);
        }
        throw new ScriptException("属性链太长", at.line(), at.col());
    }

    private void writePath(Value.Particle p, List<String> path, Value value, Ast.Node at)
    {
        SpellCloud c = p.cloud();
        int i = p.index();
        if (path.size() == 1)
        {
            switch (path.get(0))
            {
                case "pos":
                {
                    Value.Vec3 v = vec(value, at);
                    c.setPos(i, v.x(), v.y(), v.z());
                    return;
                }
                case "v":
                {
                    Value.Vec3 v = vec(value, at);
                    c.setVel(i, v.x(), v.y(), v.z());
                    return;
                }
                case "energy":
                    c.setEnergy(i, num(value, at));
                    return;
                default:
                    throw new ScriptException("粒子属性 " + path.get(0) + " 是只读的", at.line(), at.col());
            }
        }
        if (path.size() == 2)
        {
            Value.Vec3 cur = vec(readParticle(p, path.get(0), at), at);
            double v = num(value, at);
            double x = cur.x();
            double y = cur.y();
            double z = cur.z();
            switch (path.get(1))
            {
                case "x": x = v; break;
                case "y": y = v; break;
                case "z": z = v; break;
                default: throw new ScriptException("vec3 没有属性 " + path.get(1), at.line(), at.col());
            }
            writePath(p, List.of(path.get(0)), Value.vec(x, y, z), at);
            return;
        }
        throw new ScriptException("属性链太长", at.line(), at.col());
    }

    private Value record(Ast.RecordLit r, Env env)
    {
        SpellShapeHolder holder = new SpellShapeHolder(Shapes.byId(r.type()).defaults());
        for (Map.Entry<String, Ast.Expr> f : r.fields().entrySet())
        {
            double v = num(eval(f.getValue(), env), r);
            holder.set(f.getKey(), v, r);
        }
        return new Value.Shape(r.type(), holder.params);
    }

    private Value unary(Ast.Unary u, Env env)
    {
        Value v = eval(u.e(), env);
        if (u.op().equals("not"))
        {
            if (v instanceof Value.Bool b)
            {
                return b.v() ? Value.FALSE : Value.TRUE;
            }
            throw new ScriptException("not 需要 bool，读到 " + Value.typeOf(v), u.line(), u.col());
        }
        if (v instanceof Value.Num n)
        {
            return new Value.Num(-n.v());
        }
        if (v instanceof Value.Vec3 x)
        {
            return Value.vec(-x.x(), -x.y(), -x.z());
        }
        throw new ScriptException("取负需要 num 或 vec3，读到 " + Value.typeOf(v), u.line(), u.col());
    }

    private Value binary(String op, Value a, Value b, Ast.Node at)
    {
        switch (op)
        {
            case "and":
                return bool(a, at) && bool(b, at) ? Value.TRUE : Value.FALSE;
            case "or":
                return bool(a, at) || bool(b, at) ? Value.TRUE : Value.FALSE;
            case "==":
            case "!=":
            {
                boolean eq;
                if (a instanceof Value.Num na && b instanceof Value.Num nb)
                {
                    eq = na.v() == nb.v();
                }
                else if (a instanceof Value.Bool ba && b instanceof Value.Bool bb)
                {
                    eq = ba.v() == bb.v();
                }
                else if (a instanceof Value.Vec3 va && b instanceof Value.Vec3 vb)
                {
                    eq = va.x() == vb.x() && va.y() == vb.y() && va.z() == vb.z();
                }
                else
                {
                    throw new ScriptException("== 两侧类型不同：" + Value.typeOf(a) + " / " + Value.typeOf(b),
                            at.line(), at.col());
                }
                return (op.equals("==") == eq) ? Value.TRUE : Value.FALSE;
            }
            case "<":
            case "<=":
            case ">":
            case ">=":
            {
                double x = num(a, at);
                double y = num(b, at);
                boolean r;
                switch (op)
                {
                    case "<": r = x < y; break;
                    case "<=": r = x <= y; break;
                    case ">": r = x > y; break;
                    default: r = x >= y; break;
                }
                return r ? Value.TRUE : Value.FALSE;
            }
            case "+":
                if (a instanceof Value.Num na && b instanceof Value.Num nb)
                {
                    return new Value.Num(na.v() + nb.v());
                }
                if (a instanceof Value.Vec3 va && b instanceof Value.Vec3 vb)
                {
                    return Value.vec(va.x() + vb.x(), va.y() + vb.y(), va.z() + vb.z());
                }
                break;
            case "-":
                if (a instanceof Value.Num na && b instanceof Value.Num nb)
                {
                    return new Value.Num(na.v() - nb.v());
                }
                if (a instanceof Value.Vec3 va && b instanceof Value.Vec3 vb)
                {
                    return Value.vec(va.x() - vb.x(), va.y() - vb.y(), va.z() - vb.z());
                }
                break;
            case "*":
                if (a instanceof Value.Num na && b instanceof Value.Num nb)
                {
                    return new Value.Num(na.v() * nb.v());
                }
                if (a instanceof Value.Vec3 va && b instanceof Value.Num nb)
                {
                    return Value.vec(va.x() * nb.v(), va.y() * nb.v(), va.z() * nb.v());
                }
                if (a instanceof Value.Num na && b instanceof Value.Vec3 vb)
                {
                    return Value.vec(na.v() * vb.x(), na.v() * vb.y(), na.v() * vb.z());
                }
                break;
            case "/":
                if (a instanceof Value.Num na && b instanceof Value.Num nb)
                {
                    if (nb.v() == 0.0)
                    {
                        throw new ScriptException("除以零", at.line(), at.col());
                    }
                    return new Value.Num(na.v() / nb.v());
                }
                if (a instanceof Value.Vec3 va && b instanceof Value.Num nb)
                {
                    if (nb.v() == 0.0)
                    {
                        throw new ScriptException("除以零", at.line(), at.col());
                    }
                    return Value.vec(va.x() / nb.v(), va.y() / nb.v(), va.z() / nb.v());
                }
                break;
            default:
                throw new ScriptException("未知运算符 " + op, at.line(), at.col());
        }
        throw new ScriptException("运算符 " + op + " 不支持 " + Value.typeOf(a) + " 和 " + Value.typeOf(b),
                at.line(), at.col());
    }

    // ------------------------------------------------------------ 内建函数

    private Value call(Ast.Call c, Env env)
    {
        String fn = c.fn();
        List<Ast.Expr> args = c.args();

        switch (fn)
        {
            case "abs": return Value.num(Math.abs(num(one(args, env, c), c)));
            case "sqrt": return Value.num(Math.sqrt(Math.max(0.0, num(one(args, env, c), c))));
            case "sin": return Value.num(SpellMath.sin(num(one(args, env, c), c)));
            case "cos": return Value.num(SpellMath.cos(num(one(args, env, c), c)));
            case "exp": return Value.num(Math.exp(num(one(args, env, c), c)));
            case "floor": return Value.num(Math.floor(num(one(args, env, c), c)));
            case "round": return Value.num(Math.round(num(one(args, env, c), c)));
            case "rand": return Value.num(SpellRandom.unit(seed, step, randSeq++));
            case "min":
            {
                require(args, 2, c);
                return Value.num(Math.min(num(eval(args.get(0), env), c), num(eval(args.get(1), env), c)));
            }
            case "max":
            {
                require(args, 2, c);
                return Value.num(Math.max(num(eval(args.get(0), env), c), num(eval(args.get(1), env), c)));
            }
            case "clamp":
            {
                require(args, 3, c);
                double x = num(eval(args.get(0), env), c);
                double lo = num(eval(args.get(1), env), c);
                double hi = num(eval(args.get(2), env), c);
                return Value.num(Math.max(lo, Math.min(hi, x)));
            }
            case "lerp":
            {
                require(args, 3, c);
                Value a = eval(args.get(0), env);
                Value b = eval(args.get(1), env);
                double t = num(eval(args.get(2), env), c);
                if (a instanceof Value.Vec3 va && b instanceof Value.Vec3 vb)
                {
                    return Value.vec(va.x() + (vb.x() - va.x()) * t,
                            va.y() + (vb.y() - va.y()) * t,
                            va.z() + (vb.z() - va.z()) * t);
                }
                double x = num(a, c);
                double y = num(b, c);
                return Value.num(x + (y - x) * t);
            }
            case "smoothstep":
            {
                require(args, 3, c);
                double a = num(eval(args.get(0), env), c);
                double b = num(eval(args.get(1), env), c);
                double x = num(eval(args.get(2), env), c);
                return Value.num(smoothstep(a, b, x));
            }
            case "vec":
            {
                require(args, 3, c);
                return Value.vec(num(eval(args.get(0), env), c), num(eval(args.get(1), env), c),
                        num(eval(args.get(2), env), c));
            }
            case "dot":
            {
                require(args, 2, c);
                Value.Vec3 a = vec(eval(args.get(0), env), c);
                Value.Vec3 b = vec(eval(args.get(1), env), c);
                return Value.num(a.x() * b.x() + a.y() * b.y() + a.z() * b.z());
            }
            case "cross":
            {
                require(args, 2, c);
                Value.Vec3 a = vec(eval(args.get(0), env), c);
                Value.Vec3 b = vec(eval(args.get(1), env), c);
                return Value.vec(a.y() * b.z() - a.z() * b.y(),
                        a.z() * b.x() - a.x() * b.z(),
                        a.x() * b.y() - a.y() * b.x());
            }
            case "len": return Value.num(vec(one(args, env, c), c).len());
            case "len_xz":
            {
                Value.Vec3 v = vec(one(args, env, c), c);
                return Value.num(Math.sqrt(v.x() * v.x() + v.z() * v.z()));
            }
            case "normalize":
            {
                Value.Vec3 v = vec(one(args, env, c), c);
                double l = v.len();
                return l < 1.0E-9 ? Value.vec(0.0, 0.0, 0.0) : Value.vec(v.x() / l, v.y() / l, v.z() / l);
            }
            case "dist":
            {
                require(args, 2, c);
                Value.Vec3 a = vec(eval(args.get(0), env), c);
                Value.Vec3 b = vec(eval(args.get(1), env), c);
                double dx = a.x() - b.x();
                double dy = a.y() - b.y();
                double dz = a.z() - b.z();
                return Value.num(Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            case "tangent":
            {
                require(args, 2, c);
                Value.Vec3 axis = vec(eval(args.get(0), env), c);
                Value.Vec3 p = vec(eval(args.get(1), env), c);
                double rx = p.x() - axis.x();
                double rz = p.z() - axis.z();
                double d = Math.sqrt(rx * rx + rz * rz);
                return d < 1.0E-9 ? Value.vec(0.0, 0.0, 0.0) : Value.vec(-rz / d, 0.0, rx / d);
            }
            case "radial":
            {
                require(args, 2, c);
                Value.Vec3 axis = vec(eval(args.get(0), env), c);
                Value.Vec3 p = vec(eval(args.get(1), env), c);
                double rx = p.x() - axis.x();
                double rz = p.z() - axis.z();
                double d = Math.sqrt(rx * rx + rz * rz);
                return d < 1.0E-9 ? Value.vec(0.0, 0.0, 0.0) : Value.vec(rx / d, 0.0, rz / d);
            }
            case "noise":
            {
                require(args, 4, c);
                double x = num(eval(args.get(0), env), c);
                double y = num(eval(args.get(1), env), c);
                double z = num(eval(args.get(2), env), c);
                int stream = (int) Math.round(num(eval(args.get(3), env), c));
                return Value.num(SpellNoise.value3(seed, x, y, z, stream));
            }
            case "gravity": return Value.num(SpellElements.gravity(element(one(args, env, c), c)));
            case "volatility": return Value.num(SpellElements.volatility(element(one(args, env, c), c)));
            case "field":
            {
                if (args.size() != 1 && args.size() != 2)
                {
                    throw new ScriptException("field 需要 1 或 2 个参数", c.line(), c.col());
                }
                requireCast(fn, c);
                int id = element(eval(args.get(0), env), c);
                double x = host.hereX();
                double y = host.hereY();
                double z = host.hereZ();
                if (args.size() == 2)
                {
                    Value.Vec3 at = vec(eval(args.get(1), env), c);
                    x = at.x();
                    y = at.y();
                    z = at.z();
                }
                return Value.num(host.field(id, x, y, z));
            }
            case "light":
            {
                requireCast(fn, c);
                double x = host.hereX();
                double y = host.hereY();
                double z = host.hereZ();
                if (args.size() == 1)
                {
                    Value.Vec3 at = vec(eval(args.get(0), env), c);
                    x = at.x();
                    y = at.y();
                    z = at.z();
                }
                return Value.num(host.light(x, y, z));
            }
            case "weather":
                requireCast(fn, c);
                return Value.num(host.weather());
            case "time_of_day":
                requireCast(fn, c);
                return Value.num(host.dayTime());
            case "here":
                requireCast(fn, c);
                return Value.vec(host.hereX(), host.hereY(), host.hereZ());
            default:
                throw new ScriptException("未知函数 " + fn + "（形状要用记录语法：helix { ... }）",
                        c.line(), c.col());
        }
    }

    private Value one(List<Ast.Expr> args, Env env, Ast.Node at)
    {
        require(args, 1, at);
        return eval(args.get(0), env);
    }

    private static void require(List<Ast.Expr> args, int n, Ast.Node at)
    {
        if (args.size() != n)
        {
            throw new ScriptException("该函数需要 " + n + " 个参数，收到 " + args.size(), at.line(), at.col());
        }
    }

    private void requireCast(String fn, Ast.Node at)
    {
        if (!castPhase || host == null)
        {
            throw new ScriptException(fn + "() 只能在 on cast 里调用（tick 必须双端一致，不能读世界）",
                    at.line(), at.col());
        }
    }

    // ------------------------------------------------------------ 工具

    private double time()
    {
        return step * dt;
    }

    private static double smoothstep(double a, double b, double x)
    {
        if (b == a)
        {
            return 0.0;
        }
        double t = Math.max(0.0, Math.min(1.0, (x - a) / (b - a)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double num(Value v, Ast.Node at)
    {
        if (v instanceof Value.Num n)
        {
            return n.v();
        }
        throw new ScriptException("这里需要数字，读到 " + Value.typeOf(v), at.line(), at.col());
    }

    private static Value.Vec3 vec(Value v, Ast.Node at)
    {
        if (v instanceof Value.Vec3 x)
        {
            return x;
        }
        throw new ScriptException("这里需要 vec3，读到 " + Value.typeOf(v), at.line(), at.col());
    }

    private static boolean bool(Value v, Ast.Node at)
    {
        if (v instanceof Value.Bool b)
        {
            return b.v();
        }
        throw new ScriptException("这里需要 bool，读到 " + Value.typeOf(v), at.line(), at.col());
    }

    private static int element(Value v, Ast.Node at)
    {
        if (v instanceof Value.Elem e)
        {
            return e.id();
        }
        throw new ScriptException("这里需要元素，读到 " + Value.typeOf(v), at.line(), at.col());
    }

    private static double numOf(Value v, double fallback, Ast.Node at)
    {
        return v == null ? fallback : num(v, at);
    }

    /** 形状参数的可变累加器，避免写 11 次 record 构造 */
    private static final class SpellShapeHolder
    {
        private ShapeParams params;

        SpellShapeHolder(ShapeParams initial)
        {
            this.params = initial;
        }

        void set(String field, double v, Ast.Node at)
        {
            ShapeParams p = params;
            switch (field)
            {
                case "radius": p = p.withRadius(v); break;
                case "thickness": p = p.withThickness(v); break;
                case "height": p = p.withHeight(v); break;
                case "turns": p = p.withTurns(v); break;
                case "sides": p = p.withSides((int) Math.round(v)); break;
                case "length": p = p.withLength(v); break;
                case "amplitude": p = p.withAmplitude(v); break;
                case "frequency": p = p.withFrequency(v); break;
                case "radiusPulse":
                    p = new ShapeParams(p.radius(), p.thickness(), p.height(), p.turns(), p.sides(),
                            p.length(), p.amplitude(), p.frequency(), v, p.radiusWave(), p.radiusSpeed());
                    break;
                case "radiusWave":
                    p = new ShapeParams(p.radius(), p.thickness(), p.height(), p.turns(), p.sides(),
                            p.length(), p.amplitude(), p.frequency(), p.radiusPulse(), v, p.radiusSpeed());
                    break;
                case "radiusSpeed":
                    p = new ShapeParams(p.radius(), p.thickness(), p.height(), p.turns(), p.sides(),
                            p.length(), p.amplitude(), p.frequency(), p.radiusPulse(), p.radiusWave(), v);
                    break;
                default:
                    throw new ScriptException("未知形状参数 " + field, at.line(), at.col());
            }
            params = p;
        }
    }
}
