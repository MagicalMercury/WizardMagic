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
import java.util.Set;

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
    /** {@code hurt} 的半径下限/上限（格）与单次伤害上限：挡掉"一键清场"级别的写法 */
    static final double MIN_HURT_RADIUS = 0.5;
    static final double MAX_HURT_RADIUS = 16.0;
    static final double MAX_HURT_DAMAGE = 40.0;

    /** 云的寿命夹到 {@link SpellConstants#MIN_LIFE_SEC}..{@link SpellConstants#MAX_LIFE_SEC} */
    static double clampLife(double seconds)
    {
        return SpellConstants.clampLifeSec(seconds);
    }
    /** {@code emit} 认的字段名；拼错就报错，不再静默吃默认值 */
    private static final Set<String> EMIT_FIELDS =
            Set.of("element", "count", "life", "shape", "at", "facing", "inherit");

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
    /** 当前阶段的根作用域（{@code call} 从这里派生新作用域，不继承调用者局部变量） */
    private Env root;
    /** {@code call} 的嵌套深度（防御性上限；编译期已经禁掉递归） */
    private int callDepth;
    private static final int MAX_CALL_DEPTH = 16;
    private Stage stage = Stage.CAST;
    private final List<EmitSpec> emits = new ArrayList<>();

    /**
     * 当前事件点：{@code on hit} 是撞击点，{@code on expire} 是粒子质心，其它阶段为 {@code null}。
     *
     * <p>{@code emit} 的默认出生点和 {@code hurt} 的默认落点都用它——"打在你撞到的地方"
     * 和"烟从你散掉的地方冒"是同一件事。</p>
     */
    private double[] eventPoint;

    /** {@code on touch} 里那个实体的 id（{@code hurt(0, …)} 用它决定打谁） */
    private long touchEntityId = -1;

    /**
     * 求值的是哪个阶段。影响三件事：能不能读世界（cast 专属）、{@code emit} 的默认出生点、
     * 以及 {@code hurt} / {@code end} 能不能用（hit / touch / expire）。
     */
    private enum Stage
    {
        CAST,
        TICK,
        HIT,
        TOUCH,
        EXPIRE
    }

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
        in.stage = Stage.CAST;
        in.budget = CAST_BUDGET;
        Env root = new Env(null);
        for (Map.Entry<String, Ast.Expr> e : program.params().entrySet())
        {
            root.define(e.getKey(), in.eval(e.getValue(), root));
        }
        in.root = root;
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
        runStage(program, cloud, program.tick(), Stage.TICK, null, null);
    }

    /**
     * 撞击事件：粒子撞到方块之后由框架调用（一步之内最多一次）。
     *
     * @param facts 撞击事实（位置/速度是撞击粒子的平均，法向是撞击面朝向），在脚本里叫 {@code hit}
     */
    public static void hit(Ast.Program program, SpellCloud cloud, SpellCloud.HitFacts facts)
    {
        runStage(program, cloud, program.hit(), Stage.HIT, facts, null);
    }

    /**
     * 碰到生物事件：步末由框架调用，**每个实体一次**（只在服务端）。
     *
     * @param fact 碰到的那个实体，在脚本里叫 {@code touch}
     */
    public static void touch(Ast.Program program, SpellCloud cloud, SpellWorld.Touch fact)
    {
        runStage(program, cloud, program.touch(), Stage.TOUCH, null, fact);
    }

    /** 寿终事件：云被销毁之前由框架调用一次 */
    public static void expire(Ast.Program program, SpellCloud cloud)
    {
        runStage(program, cloud, program.expire(), Stage.EXPIRE, null, null);
    }

    /**
     * 各阶段共用的求值入口：建云级作用域 → 跑一段体 → 交出 emit 与云级变量。
     *
     * <p>事件本体用"事件点"（{@code emit} 的默认出生点、{@code hurt} 的默认落点）：
     * hit = 撞击点、touch = 接触点、expire = 粒子质心。</p>
     */
    private static void runStage(Ast.Program program, SpellCloud cloud, List<Ast.Stmt> body,
                                 Stage stage, SpellCloud.HitFacts facts, SpellWorld.Touch touch)
    {
        Interpreter in = new Interpreter(program, null, cloud.castSeed);
        in.cloud = cloud;
        in.step = cloud.ageSteps();
        in.dt = SpellConstants.DT;
        in.stage = stage;
        in.budget = Math.min(TICK_BUDGET_CAP, TICK_PER_PARTICLE * cloud.count + 10_000L);
        Env root = rootEnv(cloud);
        if (facts != null)
        {
            root.define("hit", Value.Hit.of(facts));
            in.eventPoint = new double[] {facts.x(), facts.y(), facts.z()};
        }
        else if (touch != null)
        {
            root.define("touch", Value.Touch.of(touch));
            in.touchEntityId = touch.id();
            in.eventPoint = new double[] {touch.px(), touch.py(), touch.pz()};
        }
        else if (stage == Stage.EXPIRE)
        {
            // 消散时的"事件点"就是粒子现在的质心：烟、伤害都该落在它实际散掉的地方
            in.eventPoint = cloud.centroid();
        }
        in.root = root;
        // 阶段体跑在根作用域的子层里：顶层 let 是这一步的临时量，不该混进"云级变量"。
        // 更要紧的是 call：被调用的事件从根作用域派生，这样才能真正看不到调用者的局部变量
        //（on cast 例外——它的顶层 let 本来就该成为云级变量，所以 cast 直接跑在 root 上）。
        in.run(body, new Env(root));
        // tick / hit 里也能 emit：收集起来交给云，由服务端决定要不要真的生成
        // （客户端也照样求值，这样 rand() 的序列两端一致，只是没人取用）
        cloud.offerEmits(in.emits);
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
        if (s instanceof Ast.CallEvent call)
        {
            callEvent(call, env);
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

    /**
     * {@code call 名字()}：跑一段自定义事件。
     *
     * <p><b>作用域是刻意的</b>：被调用的事件拿到的是<b>根作用域</b>的子层（云级变量、
     * dt/time/life/count/step，以及在 on hit 里的 {@code hit}），<b>看不到调用者的局部变量</b>。
     * 这样同一段代码被谁调用行为都一样，也不会出现"动态作用域"那种越看越晕的写法。
     * 反过来，它对云级变量的读写会正常写回去（assignment 会一路找到根作用域）。</p>
     */
    private void callEvent(Ast.CallEvent call, Env env)
    {
        Env base = root != null ? root : env;
        Ast.Event event = program == null ? null : program.events().get(call.name());
        if (event == null)
        {
            throw new ScriptException("未定义的自定义事件 " + call.name(), call.line(), call.col());
        }
        if (++callDepth > MAX_CALL_DEPTH)
        {
            throw new ScriptException("自定义事件嵌套太深（>" + MAX_CALL_DEPTH + "）",
                    call.line(), call.col());
        }
        try
        {
            // 实参在**调用者**作用域里求值（所以可以传局部变量、hit.pos……），
            // 形参则是被调事件的局部变量。
            List<Value> values = new ArrayList<>(call.args().size());
            for (Ast.Expr arg : call.args())
            {
                values.add(eval(arg, env));
            }
            Env scope = new Env(base);
            for (int i = 0; i < event.params().size(); i++)
            {
                scope.define(event.params().get(i), values.get(i));
            }
            run(event.body(), scope);
        }
        finally
        {
            callDepth--;
        }
    }

    private void loop(Ast.ForParticles f, Env env)
    {
        if (cloud == null)
        {
            throw new ScriptException("for p in particles 只能出现在有粒子的阶段里", f.line(), f.col());
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
        // 拼错的字段以前会被静默丢掉（`cout` 打成默认 3000 粒），这里直接报
        for (String key : e.fields().keySet())
        {
            if (!EMIT_FIELDS.contains(key))
            {
                throw new ScriptException("emit 不认识的字段 " + key
                        + "（可用：element / count / life / shape / at）", e.line(), e.col());
            }
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
        double life = clampLife(numOf(f.get("life"), 4.0, e));

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

        // 默认位置，按阶段取：
        //   cast   → 施法者脚下（here）
        //   hit    → 撞击点（"就在落地处炸"）
        //   expire → 粒子质心（"消散在哪就在哪留一口烟"，不是回到出生点）
        //   tick   → 这朵云的出生点（"就生在我这儿"）
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        if (stage == Stage.CAST)
        {
            if (host != null)
            {
                x = host.hereX();
                y = host.hereY();
                z = host.hereZ();
            }
        }
        else if (eventPoint != null)
        {
            x = eventPoint[0];
            y = eventPoint[1];
            z = eventPoint[2];
        }
        else if (cloud != null)
        {
            x = cloud.originX;
            y = cloud.originY;
            z = cloud.originZ;
        }
        if (f.get("at") instanceof Value.Vec3 at)
        {
            x = at.x();
            y = at.y();
            z = at.z();
        }

        // 形状朝向：形状本地 +Y 轴指向它。不写就是竖直向上（旧行为）
        double fx = 0.0;
        double fy = 1.0;
        double fz = 0.0;
        if (f.get("facing") instanceof Value.Vec3 face)
        {
            fx = face.x();
            fy = face.y();
            fz = face.z();
            double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (!(len > 1.0E-9) || !Double.isFinite(len))
            {
                throw new ScriptException("emit 的 facing 不能是零向量", e.line(), e.col());
            }
        }
        else if (f.get("facing") != null && !(f.get("facing") instanceof Value.Void))
        {
            throw new ScriptException("emit 的 facing 必须是 vec3，例如 facing: vec(1, 0, 0)",
                    e.line(), e.col());
        }

        // 子云要不要继承这朵云的脚本。默认继承（拖尾/分裂/二次爆裂都靠它），
        // 写 inherit: false 就只按元素定律飘——烟、灰尘、碎片这类"不听话"的东西。
        boolean inherit = true;
        if (f.get("inherit") != null && !(f.get("inherit") instanceof Value.Void))
        {
            if (!(f.get("inherit") instanceof Value.Bool b))
            {
                throw new ScriptException("emit 的 inherit 必须是 true / false", e.line(), e.col());
            }
            inherit = b.v();
        }

        emits.add(new EmitSpec(elementId, count, (float) life, shapeId, params, x, y, z,
                fx, fy, fz, inherit));
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
        if (e instanceof Ast.StrLit s)
        {
            return new Value.Str(s.v());
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
            if (b.op().equals("and") || b.op().equals("or"))
            {
                return logical(b, env);
            }
            return binary(b.op(), eval(b.a(), env), eval(b.b(), env), b);
        }
        throw new ScriptException("未知表达式", e.line(), e.col());
    }

    /**
     * {@code and} / {@code or} 短路求值。
     *
     * <p>以前两侧都先求值再交给 {@code binary}，于是 {@code d != 0 and 1/d > x} 这种
     * 守卫写法在 {@code d == 0} 时照样除零，把整朵云打成 {@code broken}。短路之后
     * 右侧只在需要时求值——和寄存器机编出的条件跳转（{@code JZ}/{@code JNZ}）一致。</p>
     */
    private Value logical(Ast.Binary b, Env env)
    {
        boolean and = b.op().equals("and");
        if (and && !bool(eval(b.a(), env), b))
        {
            return Value.FALSE;
        }
        if (!and && bool(eval(b.a(), env), b))
        {
            return Value.TRUE;
        }
        return bool(eval(b.b(), env), b) ? Value.TRUE : Value.FALSE;
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
        if (base instanceof Value.Hit h)
        {
            switch (name)
            {
                case "pos": return Value.vec(h.x(), h.y(), h.z());
                case "vel": return Value.vec(h.vx(), h.vy(), h.vz());
                case "normal": return Value.vec(h.nx(), h.ny(), h.nz());
                case "block": return new Value.Str(h.block() == null ? "" : h.block());
                case "count": return Value.num(h.count());
                case "speed": return Value.num(Math.sqrt(h.vx() * h.vx() + h.vy() * h.vy() + h.vz() * h.vz()));
                default: throw new ScriptException("hit 没有属性 " + name
                        + "（可用：pos / vel / normal / block / count / speed）", at.line(), at.col());
            }
        }
        if (base instanceof Value.Touch t)
        {
            switch (name)
            {
                case "entity": return t.entity();
                case "pos": return Value.vec(t.px(), t.py(), t.pz());
                default: throw new ScriptException("touch 没有属性 " + name
                        + "（可用：entity / pos）", at.line(), at.col());
            }
        }
        if (base instanceof Value.Entity e)
        {
            switch (name)
            {
                case "type": return new Value.Str(e.type() == null ? "" : e.type());
                case "pos": return Value.vec(e.x(), e.y(), e.z());
                case "id": return Value.num(e.id());
                case "health": return Value.num(e.health());
                default: throw new ScriptException("实体没有属性 " + name
                        + "（可用：type / pos / id / health）", at.line(), at.col());
            }
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
                else if (a instanceof Value.Str sa && b instanceof Value.Str sb)
                {
                    // 字符串只用于比较（地块 id 这类"引擎给的名字"），不能拼接、不能算
                    eq = sa.v().equals(sb.v());
                }
                else if (a instanceof Value.Entity ea && b instanceof Value.Entity eb)
                {
                    // 实体按 id 比较（同一只生物一步里只会触发一次 touch）
                    eq = ea.id() == eb.id();
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
            case "%":
                if (a instanceof Value.Num na && b instanceof Value.Num nb)
                {
                    if (nb.v() == 0.0)
                    {
                        throw new ScriptException("取模的除数为零", at.line(), at.col());
                    }
                    return new Value.Num(na.v() % nb.v());
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
            case "sign":
            {
                double x = num(one(args, env, c), c);
                return Value.num(x > 0.0 ? 1.0 : x < 0.0 ? -1.0 : 0.0);
            }
            case "pow":
            {
                require(args, 2, c);
                double x = num(eval(args.get(0), env), c);
                double y = num(eval(args.get(1), env), c);
                // StrictMath：Math.pow 允许 ±1 ulp 的平台差异，两端是不同 JVM，必须用严格版
                return Value.num(StrictMath.pow(x, y));
            }
            case "atan2":
            {
                require(args, 2, c);
                double y = num(eval(args.get(0), env), c);
                double x = num(eval(args.get(1), env), c);
                return Value.num(StrictMath.atan2(y, x));
            }
            case "sin": return Value.num(SpellMath.sin(num(one(args, env, c), c)));
            case "cos": return Value.num(SpellMath.cos(num(one(args, env, c), c)));
            case "exp": return Value.num(Math.exp(num(one(args, env, c), c)));
            case "floor": return Value.num(Math.floor(num(one(args, env, c), c)));
            case "round": return Value.num(Math.round(num(one(args, env, c), c)));
            case "rand":
            {
                // 以前完全不看实参：rand(0, 10) 静默返回 [0,1)，脚本作者以为拿到了范围随机
                if (!args.isEmpty())
                {
                    throw new ScriptException("rand() 不接受参数；要范围随机写 "
                            + "lo + rand() * (hi - lo)", c.line(), c.col());
                }
                return Value.num(SpellRandom.unit(seed, step, randSeq++));
            }
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
            case "end":
            {
                // 立刻结束这朵云（不触发 on expire）。tick 里不给用：那边是编译执行的，
                // 寄存器机没有"中途结束"的通道，用寿命/phase 控制。
                if (stage != Stage.HIT && stage != Stage.TOUCH && stage != Stage.EXPIRE)
                {
                    throw new ScriptException("end() 只能写在 on hit / on touch / on expire 里",
                            c.line(), c.col());
                }
                require(args, 0, c);
                if (cloud != null)
                {
                    cloud.kill();
                }
                return Value.VOID;
            }
            case "hurt":
            {
                // 只写不读：返回 void。脚本要是能读到"打到了几个"，客户端读到 0、
                // 服务端读到真实值，两端立刻分叉（这是硬约束，见 SpellWorld）。
                if (stage != Stage.HIT && stage != Stage.TOUCH && stage != Stage.EXPIRE)
                {
                    throw new ScriptException("hurt() 只能在 on hit / on touch / on expire 里调用",
                            c.line(), c.col());
                }
                if (args.size() != 2 && args.size() != 3)
                {
                    throw new ScriptException("hurt 需要 hurt(半径, 伤害) 或 hurt(半径, 伤害, 位置)",
                            c.line(), c.col());
                }
                double radius = num(eval(args.get(0), env), c);
                double damage = num(eval(args.get(1), env), c);
                if (!Double.isFinite(damage) || damage <= 0.0 || damage > MAX_HURT_DAMAGE)
                {
                    throw new ScriptException("hurt 的伤害必须在 (0, " + MAX_HURT_DAMAGE
                            + "] 之间（收到 " + damage + "）", c.line(), c.col());
                }
                // 半径 0 = 只打"碰到的那个实体"（只有 on touch 有这个对象）
                if (radius == 0.0)
                {
                    if (stage != Stage.TOUCH)
                    {
                        throw new ScriptException("hurt 的半径为 0 只能在 on touch 里用"
                                + "（那里的 0 表示只打碰到的那个生物）", c.line(), c.col());
                    }
                    if (cloud != null && touchEntityId >= 0)
                    {
                        cloud.hurtEntity(touchEntityId, damage);
                    }
                    return Value.VOID;
                }
                if (!Double.isFinite(radius) || radius < MIN_HURT_RADIUS || radius > MAX_HURT_RADIUS)
                {
                    throw new ScriptException("hurt 的半径必须在 " + MIN_HURT_RADIUS + ".."
                            + MAX_HURT_RADIUS + " 之间（收到 " + radius + "）", c.line(), c.col());
                }
                double hx;
                double hy;
                double hz;
                if (args.size() == 3)
                {
                    Value.Vec3 at = vec(eval(args.get(2), env), c);
                    hx = at.x();
                    hy = at.y();
                    hz = at.z();
                }
                else if (eventPoint != null)
                {
                    hx = eventPoint[0];
                    hy = eventPoint[1];
                    hz = eventPoint[2];
                }
                else if (cloud != null)
                {
                    hx = cloud.originX;
                    hy = cloud.originY;
                    hz = cloud.originZ;
                }
                else
                {
                    return Value.VOID;
                }
                if (cloud != null)
                {
                    // 客户端（没有出口）与配额用完都走这条：静默丢弃，脚本不需要知道
                    cloud.hurt(hx, hy, hz, radius, damage);
                }
                return Value.VOID;
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
            case "radial":
            {
                // 两参：绕世界 Y 轴（旧行为，逐位不变）
                // 三参：绕任意轴 tangent(axis, pos, up) / radial(axis, pos, up)
                if (args.size() != 2 && args.size() != 3)
                {
                    throw new ScriptException("tangent / radial 需要 2 或 3 个参数", c.line(), c.col());
                }
                Value.Vec3 axis = vec(eval(args.get(0), env), c);
                Value.Vec3 p = vec(eval(args.get(1), env), c);
                double ux = 0.0;
                double uy = 1.0;
                double uz = 0.0;
                if (args.size() == 3)
                {
                    Value.Vec3 up = vec(eval(args.get(2), env), c);
                    double ul = up.len();
                    if (!(ul > 1.0E-9))
                    {
                        throw new ScriptException("tangent / radial 的第三个参数（轴向）不能是零向量",
                                c.line(), c.col());
                    }
                    ux = up.x() / ul;
                    uy = up.y() / ul;
                    uz = up.z() / ul;
                }
                return tangentOf(axis, p, ux, uy, uz, fn.equals("tangent"));
            }
            case "rotate":
            {
                require(args, 3, c);
                Value.Vec3 v = vec(eval(args.get(0), env), c);
                Value.Vec3 k = vec(eval(args.get(1), env), c);
                double angle = num(eval(args.get(2), env), c);
                return rotate(v, k, angle);
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
            case "look":
                // 施法朝向：投射物法术（火球飞出去再炸）唯一的"方向"来源
                requireCast(fn, c);
                require(args, 0, c);
                return Value.vec(host.lookX(), host.lookY(), host.lookZ());
            case "aim":
            {
                // 瞄准点：here + look × 距离。投射物在身前 1.5 格出生就写 at: aim(1.5)
                requireCast(fn, c);
                require(args, 1, c);
                double dist = num(eval(args.get(0), env), c);
                return Value.vec(host.hereX() + host.lookX() * dist,
                        host.hereY() + host.lookY() * dist,
                        host.hereZ() + host.lookZ() * dist);
            }
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
        if (stage != Stage.CAST || host == null)
        {
            throw new ScriptException(fn + "() 只能在 on cast 里调用（其它阶段必须双端一致，不能读世界）",
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

    /**
     * 绕任意轴的切向 / 径向：先把 {@code p - axis} 里的**轴向分量投影掉**，
     * 再在垂直于 up 的平面里取方向。
     *
     * <p>两参版本（up = 世界 Y）与旧实现逐位一致：投影后相对向量是 {@code (rx, 0, rz)}，
     * 径向 = 它本身、切向 = {@code rp × up = (-rz, 0, rx)}，都除以同一个长度。</p>
     */
    private static Value tangentOf(Value.Vec3 axis, Value.Vec3 p,
            double ux, double uy, double uz, boolean tangent)
    {
        double rx = p.x() - axis.x();
        double ry = p.y() - axis.y();
        double rz = p.z() - axis.z();
        double axial = rx * ux + ry * uy + rz * uz;
        double px = rx - ux * axial;
        double py = ry - uy * axial;
        double pz = rz - uz * axial;
        double l = Math.sqrt(px * px + py * py + pz * pz);
        if (l < 1.0E-9)
        {
            return Value.vec(0.0, 0.0, 0.0);
        }
        if (!tangent)
        {
            return Value.vec(px / l, py / l, pz / l);
        }
        return Value.vec((py * uz - pz * uy) / l, (pz * ux - px * uz) / l,
                (px * uy - py * ux) / l);
    }

    /** 绕任意轴旋转（右手定则）；轴退化成零向量时原样返回 */
    private static Value rotate(Value.Vec3 v, Value.Vec3 k, double angle)
    {
        double l = k.len();
        if (!(l > 1.0E-9))
        {
            return v;
        }
        double kx = k.x() / l;
        double ky = k.y() / l;
        double kz = k.z() / l;
        // 用 SpellMath 的 sin/cos：跨 JVM 逐位一致（Math.sin 不保证）
        double c = SpellMath.cos(angle);
        double s = SpellMath.sin(angle);
        double vx = v.x();
        double vy = v.y();
        double vz = v.z();
        double cx = ky * vz - kz * vy;
        double cy = kz * vx - kx * vz;
        double cz = kx * vy - ky * vx;
        double dot = kx * vx + ky * vy + kz * vz;
        double t = 1.0 - c;
        return Value.vec(vx * c + cx * s + kx * dot * t,
                vy * c + cy * s + ky * dot * t,
                vz * c + cz * s + kz * dot * t);
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
