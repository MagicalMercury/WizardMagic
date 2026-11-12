package com.wizardmagic.core.spell.script;

import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellElements;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.wizardmagic.core.spell.script.RegVm.*;

/**
 * tick 的快速路径：把 {@code on tick} 里的粒子循环体编成寄存器机指令。
 *
 * <p>为什么要另写一套：树遍历求值每个节点都要 {@code instanceof}、每个数字都要装箱、
 * 每个变量都要查 HashMap，3000 粒一步要 5–6 ms。这里的做法是</p>
 * <ul>
 *   <li>表达式编成三地址寄存器指令，操作数是 {@code double[]} 帧里的槽位，不装箱；</li>
 *   <li>vec3 占 3 个连续槽位，加减乘除是一条指令；</li>
 *   <li><b>变量就是寄存器</b>：{@code let}/{@code =} 不再有 LOAD/STORE，只是把值算进那个槽位；</li>
 *   <li>{@code p.axis} / {@code p.targetR} 每粒每步只算一次（形状采样里有 sin/cos）；</li>
 *   <li>顶层标量与循环体外提量仍然交给 {@link Interpreter}（每步各一次，成本可忽略）。</li>
 * </ul>
 *
 * <p>寄存器分配是最朴素的"水位 + 作用域回退"：寄存器从 0 往上发，表达式结束就把水位
 * 退回去，{@code if} 的两个分支共用同一段。因为每粒都会重跑整段代码、每个槽位都是
 * 先写后读，所以跨粒子复用寄存器是安全的。指令集与热循环在 {@link RegVm}。</p>
 *
 * <p><b>语义契约</b>：编译路径与树遍历路径必须逐位一致。因此运算顺序、除法除零检查、
 * {@code p.energy} 的 float 截断、{@code rand()} 的调用顺序都刻意保持与
 * {@link Interpreter} 相同；遇到无法静态确定的写法（动态类型、顶层 if、tick 内 emit、
 * 写云级变量）直接 {@link Unsupported} 退回树遍历，绝不猜测。</p>
 */
final class FastProgram
{
    // ---------------------------------------------------------------- 类型

    private enum T
    {
        NUM, BOOL, VEC3, ELEM, PARTICLE, VOID
    }

    /** 无法安全编译的写法：退回树遍历 */
    private static final class Unsupported extends RuntimeException
    {
        Unsupported()
        {
            super("unsupported");
        }
    }

    /** 寄存器帧上限由打包编码决定（见 {@link RegVm#MAX_REG}） */
    private static final int MAX_REGS = MAX_REG;

    // ---------------------------------------------------------------- 段

    /** 标量语句（原样交给解释器） */
    private static final class Scalar
    {
        final Ast.Stmt stmt;

        Scalar(Ast.Stmt stmt)
        {
            this.stmt = stmt;
        }
    }

    /** 编译后的粒子循环体 */
    private static final class Loop
    {
        int[] code;
        double[] consts;
        int frameSize;
        /** 自由变量：名字、槽位、类型；每步从 Env 读一次 */
        final List<Free> frees = new ArrayList<>();
        int freeSlots;
        /** 每步算一次、逐粒子复用的表达式（循环体内与粒子无关的 let） */
        final List<Hoist> hoists = new ArrayList<>();
        int hoistSlots;
    }

    private static final class Free
    {
        final String name;
        final T type;
        int offset;

        Free(String name, T type)
        {
            this.name = name;
            this.type = type;
        }
    }

    /** 循环体内与粒子无关的 let：每步求值一次，粒子循环只读 */
    private static final class Hoist
    {
        int slot;
        Ast.Expr expr;
        T type;
        /** 在循环体里是第几个 {@code let}（按语句顺序编号）；读盘时靠它重新挂到语法树上 */
        int ordinal;
    }

    /** 一步的准备工作：搬好的自由变量 + 算好的外提量 */
    private static final class Prepared
    {
        int[] code;
        double[] consts;
        double[] frame;
        double[] fv;
        double[] hv;
    }

    private static final Prepared[] NO_PREPARED = new Prepared[0];

    /**
     * 指令集版本号。
     *
     * <p><b>动 {@link RegVm} 的指令编码、寄存器分配规则，或 {@link #collectLets} 的编号顺序时
     * 必须 +1</b>：存档里的 {@code .spellc} 只认这个号，不认"长得差不多"。</p>
     */
    static final int IR_VERSION = 1;

    /** 类型序号会写进字节码缓存，<b>别重排这个枚举</b> */
    private static final T[] TYPES = T.values();

    private final List<Object> segments = new ArrayList<>();
    private final int loopCount;

    // ---------------------------------------------------------------- 编译入口

    static FastProgram compile(Ast.Program program, Map<String, Value> vars)
    {
        try
        {
            return fromAst(program, vars);
        }
        catch (Unsupported unsupported)
        {
            if (System.getProperty("wizardmagic.script.debug") != null)
            {
                unsupported.printStackTrace();
            }
            return null;
        }
    }

    private static FastProgram fromAst(Ast.Program program, Map<String, Value> vars)
    {
        Scope top = new Scope(null, globalsOf(vars));
        List<Object> segments = new ArrayList<>();
        boolean sawLoop = false;
        int loops = 0;
        for (Ast.Stmt s : program.tick())
        {
            if (s instanceof Ast.ForParticles loop)
            {
                segments.add(buildLoop(loop, top));
                sawLoop = true;
                loops++;
            }
            else if (s instanceof Ast.Let || s instanceof Ast.Assign || s instanceof Ast.ExprStmt)
            {
                if (sawLoop)
                {
                    // 粒子循环之后还有标量语句：为了能"先校验后执行"，这里不支持
                    throw new Unsupported();
                }
                inferStmt(s, top);
                segments.add(new Scalar(s));
            }
            else
            {
                throw new Unsupported();
            }
        }
        if (segments.isEmpty())
        {
            throw new Unsupported();
        }
        return new FastProgram(segments, loops);
    }

    private FastProgram(List<Object> segments, int loopCount)
    {
        this.segments.addAll(segments);
        this.loopCount = loopCount;
    }

    private static Map<String, T> globalsOf(Map<String, Value> vars)
    {
        Map<String, T> g = new HashMap<>();
        g.put("dt", T.NUM);
        g.put("time", T.NUM);
        g.put("life", T.NUM);
        g.put("count", T.NUM);
        g.put("step", T.NUM);
        for (Map.Entry<String, Value> e : vars.entrySet())
        {
            Value v = e.getValue();
            if (v instanceof Value.Num)
            {
                g.put(e.getKey(), T.NUM);
            }
            else if (v instanceof Value.Bool)
            {
                g.put(e.getKey(), T.BOOL);
            }
            else if (v instanceof Value.Vec3)
            {
                g.put(e.getKey(), T.VEC3);
            }
            else if (v instanceof Value.Elem)
            {
                g.put(e.getKey(), T.ELEM);
            }
            else
            {
                g.put(e.getKey(), T.VOID);
            }
        }
        return g;
    }

    // ---------------------------------------------------------------- 落盘格式

    /**
     * 把编好的循环体写出去。
     *
     * <p>只写寄存器机那部分：顶层标量语句与外提表达式仍然是语法树节点（每步交给解释器算一次），
     * 读回时用同一份源码重新解析、再按编号挂回去。所以 {@code .spellc} 是"缓存"而不是
     * "可以脱离源码发布的产物"。</p>
     */
    void writeTo(DataOutputStream out) throws IOException
    {
        out.writeInt(loopCount);
        for (Object seg : segments)
        {
            if (!(seg instanceof Loop L))
            {
                continue;
            }
            out.writeInt(L.frameSize);
            out.writeInt(L.consts.length);
            for (double d : L.consts)
            {
                out.writeDouble(d);
            }
            out.writeInt(L.code.length);
            for (int w : L.code)
            {
                out.writeInt(w);
            }
            out.writeInt(L.frees.size());
            for (Free f : L.frees)
            {
                out.writeUTF(f.name);
                out.writeByte(f.type.ordinal());
                out.writeInt(f.offset);
            }
            out.writeInt(L.hoists.size());
            for (Hoist h : L.hoists)
            {
                if (h.ordinal < 0)
                {
                    // 没有编号 = 读回时挂不回语法树，这份程序不能落盘
                    throw new IOException("外提量缺少编号");
                }
                out.writeInt(h.ordinal);
                out.writeInt(h.slot);
                out.writeByte(h.type.ordinal());
            }
        }
    }

    /**
     * 从落盘格式重建。
     *
     * @return null = 结构与当前源码对不上（源码哈希相同却不匹配说明缓存是坏的），调用方重新编译
     */
    static FastProgram decode(Ast.Program program, DataInputStream in)
    {
        try
        {
            int loopCount = in.readInt();
            List<Ast.ForParticles> loops = new ArrayList<>();
            boolean sawLoop = false;
            for (Ast.Stmt s : program.tick())
            {
                if (s instanceof Ast.ForParticles loop)
                {
                    loops.add(loop);
                    sawLoop = true;
                }
                else if (s instanceof Ast.Let || s instanceof Ast.Assign || s instanceof Ast.ExprStmt)
                {
                    if (sawLoop)
                    {
                        return null;
                    }
                }
                else
                {
                    return null;
                }
            }
            if (loops.size() != loopCount || loops.isEmpty())
            {
                return null;
            }

            List<Object> segments = new ArrayList<>();
            for (Ast.Stmt s : program.tick())
            {
                if (s instanceof Ast.ForParticles loop)
                {
                    Loop L = readLoop(in, loop);
                    if (L == null)
                    {
                        return null;
                    }
                    segments.add(L);
                }
                else
                {
                    segments.add(new Scalar(s));
                }
            }
            return new FastProgram(segments, loopCount);
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    private static Loop readLoop(DataInputStream in, Ast.ForParticles loop) throws IOException
    {
        Loop L = new Loop();
        L.frameSize = in.readInt();
        if (L.frameSize <= 0 || L.frameSize > 64 * 1024)
        {
            return null;
        }

        int nConst = in.readInt();
        if (nConst < 0 || nConst > 64 * 1024)
        {
            return null;
        }
        L.consts = new double[nConst];
        for (int i = 0; i < nConst; i++)
        {
            L.consts[i] = in.readDouble();
        }

        int nCode = in.readInt();
        if (nCode < 0 || nCode > 1024 * 1024)
        {
            return null;
        }
        L.code = new int[nCode];
        for (int i = 0; i < nCode; i++)
        {
            L.code[i] = in.readInt();
        }

        int nFree = in.readInt();
        if (nFree < 0 || nFree > MAX_REGS)
        {
            return null;
        }
        for (int i = 0; i < nFree; i++)
        {
            String name = in.readUTF();
            int typeId = in.readByte();
            int off = in.readInt();
            if (typeId < 0 || typeId >= TYPES.length || off < 0 || off >= L.frameSize)
            {
                return null;
            }
            T type = TYPES[typeId];
            Free f = new Free(name, type);
            f.offset = off;
            L.frees.add(f);
            L.freeSlots = Math.max(L.freeSlots, off + width(type));
        }

        int nHoist = in.readInt();
        if (nHoist < 0 || nHoist > MAX_REGS)
        {
            return null;
        }
        List<Ast.Let> lets = collectLets(loop.body());
        for (int i = 0; i < nHoist; i++)
        {
            int ordinal = in.readInt();
            int slot = in.readInt();
            int typeId = in.readByte();
            if (typeId < 0 || typeId >= TYPES.length || ordinal < 0 || ordinal >= lets.size())
            {
                return null;
            }
            T type = TYPES[typeId];
            Hoist h = new Hoist();
            h.ordinal = ordinal;
            h.slot = slot;
            h.type = type;
            h.expr = lets.get(ordinal).value();
            L.hoists.add(h);
            L.hoistSlots = Math.max(L.hoistSlots, slot + width(type));
        }
        return L;
    }

    /** 循环体里所有 {@code let}，按语句顺序编号；编译期与读盘时共用同一个走法 */
    private static List<Ast.Let> collectLets(List<Ast.Stmt> body)
    {
        List<Ast.Let> out = new ArrayList<>();
        collectLets(body, out);
        return out;
    }

    private static void collectLets(List<Ast.Stmt> body, List<Ast.Let> out)
    {
        for (Ast.Stmt s : body)
        {
            if (s instanceof Ast.Let let)
            {
                out.add(let);
            }
            else if (s instanceof Ast.If branch)
            {
                collectLets(branch.then(), out);
                collectLets(branch.otherwise(), out);
            }
        }
    }

    // ---------------------------------------------------------------- 每步执行

    /**
     * @return true = 已处理；false = 这朵云不适合快速路径，调用方退回解释器
     */
    boolean tick(SpellCloud cloud)
    {
        Env root;
        try
        {
            root = Interpreter.rootEnv(cloud);
        }
        catch (RuntimeException e)
        {
            return false;
        }

        // 先跑标量段（只改 Env，不碰云）
        for (Object seg : segments)
        {
            if (seg instanceof Scalar s)
            {
                Interpreter.runScalar(cloud, s.stmt, root);
            }
        }

        // 再给所有循环做准备（搬自由变量、算外提量）。任何一步不成立就整体退回解释器，
        // 此时粒子还没被动过，退回是干净的。
        Prepared[] prepared = loopCount == 0 ? NO_PREPARED : new Prepared[loopCount];
        int li = 0;
        for (Object seg : segments)
        {
            if (seg instanceof Loop L)
            {
                Prepared p = prepare(L, cloud, root);
                if (p == null)
                {
                    return false;
                }
                prepared[li++] = p;
            }
        }

        double time = cloud.ageSteps() * SpellConstants.DT;
        int count = cloud.count;
        li = 0;
        for (Object seg : segments)
        {
            if (seg instanceof Loop)
            {
                Prepared p = prepared[li++];
                RegVm.execute(p.code, p.consts, p.frame, p.fv, p.hv, cloud, count, time);
            }
        }
        Interpreter.saveRoot(cloud, root);
        return true;
    }

    /**
     * 冷路径：校验并搬自由变量、算外提量。失败返回 null（调用方退回解释器）。
     */
    private static Prepared prepare(Loop L, SpellCloud cloud, Env root)
    {
        Prepared p = new Prepared();
        p.code = L.code;
        p.consts = L.consts;
        p.frame = new double[L.frameSize];

        p.fv = new double[L.freeSlots];
        for (Free f : L.frees)
        {
            if (!store(p.fv, f, root.get(f.name)))
            {
                return null;
            }
        }

        p.hv = new double[L.hoistSlots];
        for (Hoist h : L.hoists)
        {
            Value v;
            try
            {
                v = Interpreter.evalExpr(cloud, root, h.expr);
            }
            catch (RuntimeException e)
            {
                return null;
            }
            if (h.type == T.NUM)
            {
                if (!(v instanceof Value.Num n))
                {
                    return null;
                }
                p.hv[h.slot] = n.v();
            }
            else
            {
                if (!(v instanceof Value.Vec3 x))
                {
                    return null;
                }
                p.hv[h.slot] = x.x();
                p.hv[h.slot + 1] = x.y();
                p.hv[h.slot + 2] = x.z();
            }
        }
        return p;
    }

    private static boolean store(double[] fv, Free f, Value v)
    {
        if (f.type == T.NUM)
        {
            if (!(v instanceof Value.Num n))
            {
                return false;
            }
            fv[f.offset] = n.v();
            return true;
        }
        if (f.type == T.VEC3)
        {
            if (!(v instanceof Value.Vec3 x))
            {
                return false;
            }
            fv[f.offset] = x.x();
            fv[f.offset + 1] = x.y();
            fv[f.offset + 2] = x.z();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 生成

    private static Loop buildLoop(Ast.ForParticles loop, Scope top)
    {
        Loop L = new Loop();
        new Codegen(L, top).compileBody(loop.body(), loop.var());
        return L;
    }

    private static void inferStmt(Ast.Stmt s, Scope sc)
    {
        if (s instanceof Ast.Let let)
        {
            T t = typeOf(let.value(), sc);
            sc.declare(let.name(), t, -1);
        }
        else if (s instanceof Ast.Assign assign)
        {
            T t = typeOf(assign.value(), sc);
            if (assign.path().isEmpty())
            {
                T cur = sc.type(assign.base());
                if (cur != null && cur != t)
                {
                    throw new Unsupported();
                }
            }
        }
        else if (s instanceof Ast.ExprStmt)
        {
            typeOf(((Ast.ExprStmt) s).expr(), sc);
        }
    }

    private static T typeOf(Ast.Expr e, Scope sc)
    {
        if (e instanceof Ast.NumLit)
        {
            return T.NUM;
        }
        if (e instanceof Ast.BoolLit)
        {
            return T.BOOL;
        }
        if (e instanceof Ast.Var v)
        {
            T t = sc.type(v.name());
            if (t != null)
            {
                return t;
            }
            if (SpellElements.indexOf(v.name()) >= 0)
            {
                return T.ELEM;
            }
            throw new Unsupported();
        }
        if (e instanceof Ast.Member m)
        {
            T base = typeOf(m.base(), sc);
            if (base == T.VEC3)
            {
                if (m.name().equals("x") || m.name().equals("y") || m.name().equals("z"))
                {
                    return T.NUM;
                }
                throw new Unsupported();
            }
            if (base == T.PARTICLE)
            {
                return particlePropType(m.name());
            }
            throw new Unsupported();
        }
        if (e instanceof Ast.Unary u)
        {
            T t = typeOf(u.e(), sc);
            if (u.op().equals("not"))
            {
                if (t != T.BOOL)
                {
                    throw new Unsupported();
                }
                return T.BOOL;
            }
            if (t == T.NUM || t == T.VEC3)
            {
                return t;
            }
            throw new Unsupported();
        }
        if (e instanceof Ast.Binary b)
        {
            T a = typeOf(b.a(), sc);
            T c = typeOf(b.b(), sc);
            switch (b.op())
            {
                case "and":
                case "or":
                    if (a != T.BOOL || c != T.BOOL)
                    {
                        throw new Unsupported();
                    }
                    return T.BOOL;
                case "==":
                case "!=":
                case "<":
                case "<=":
                case ">":
                case ">=":
                    if (a != T.NUM || c != T.NUM)
                    {
                        throw new Unsupported();
                    }
                    return T.BOOL;
                case "+":
                case "-":
                    if (a == T.NUM && c == T.NUM)
                    {
                        return T.NUM;
                    }
                    if (a == T.VEC3 && c == T.VEC3)
                    {
                        return T.VEC3;
                    }
                    throw new Unsupported();
                case "*":
                    if (a == T.NUM && c == T.NUM)
                    {
                        return T.NUM;
                    }
                    if (a == T.VEC3 && c == T.NUM)
                    {
                        return T.VEC3;
                    }
                    if (a == T.NUM && c == T.VEC3)
                    {
                        return T.VEC3;
                    }
                    throw new Unsupported();
                case "/":
                    if (a == T.NUM && c == T.NUM)
                    {
                        return T.NUM;
                    }
                    if (a == T.VEC3 && c == T.NUM)
                    {
                        return T.VEC3;
                    }
                    throw new Unsupported();
                default:
                    throw new Unsupported();
            }
        }
        if (e instanceof Ast.Call c)
        {
            return callType(c, sc);
        }
        throw new Unsupported();
    }

    private static T callType(Ast.Call c, Scope sc)
    {
        String fn = c.fn();
        List<Ast.Expr> args = c.args();
        switch (fn)
        {
            case "abs":
            case "sqrt":
            case "sin":
            case "cos":
            case "exp":
            case "floor":
            case "round":
                need(args, 1);
                needNum(args.get(0), sc);
                return T.NUM;
            case "min":
            case "max":
                need(args, 2);
                needNum(args.get(0), sc);
                needNum(args.get(1), sc);
                return T.NUM;
            case "clamp":
            case "smoothstep":
                need(args, 3);
                needNum(args.get(0), sc);
                needNum(args.get(1), sc);
                needNum(args.get(2), sc);
                return T.NUM;
            case "lerp":
            {
                need(args, 3);
                T a = typeOf(args.get(0), sc);
                T b = typeOf(args.get(1), sc);
                needNum(args.get(2), sc);
                if (a == T.NUM && b == T.NUM)
                {
                    return T.NUM;
                }
                if (a == T.VEC3 && b == T.VEC3)
                {
                    return T.VEC3;
                }
                throw new Unsupported();
            }
            case "vec":
                need(args, 3);
                needNum(args.get(0), sc);
                needNum(args.get(1), sc);
                needNum(args.get(2), sc);
                return T.VEC3;
            case "dot":
                need(args, 2);
                needVec(args.get(0), sc);
                needVec(args.get(1), sc);
                return T.NUM;
            case "cross":
                need(args, 2);
                needVec(args.get(0), sc);
                needVec(args.get(1), sc);
                return T.VEC3;
            case "len":
            case "len_xz":
                need(args, 1);
                needVec(args.get(0), sc);
                return T.NUM;
            case "normalize":
                need(args, 1);
                needVec(args.get(0), sc);
                return T.VEC3;
            case "dist":
                need(args, 2);
                needVec(args.get(0), sc);
                needVec(args.get(1), sc);
                return T.NUM;
            case "tangent":
            case "radial":
                need(args, 2);
                needVec(args.get(0), sc);
                needVec(args.get(1), sc);
                return T.VEC3;
            case "noise":
                need(args, 4);
                for (int i = 0; i < 4; i++)
                {
                    needNum(args.get(i), sc);
                }
                return T.NUM;
            case "gravity":
            case "volatility":
                need(args, 1);
                if (typeOf(args.get(0), sc) != T.ELEM || !isElementLiteral(args.get(0), sc))
                {
                    throw new Unsupported();
                }
                return T.NUM;
            default:
                throw new Unsupported();
        }
    }

    private static void need(List<Ast.Expr> args, int n)
    {
        if (args.size() != n)
        {
            throw new Unsupported();
        }
    }

    private static void needNum(Ast.Expr e, Scope sc)
    {
        if (typeOf(e, sc) != T.NUM)
        {
            throw new Unsupported();
        }
    }

    private static void needVec(Ast.Expr e, Scope sc)
    {
        if (typeOf(e, sc) != T.VEC3)
        {
            throw new Unsupported();
        }
    }

    private static boolean isElementLiteral(Ast.Expr e, Scope sc)
    {
        return e instanceof Ast.Var v && sc.type(v.name()) == null
                && SpellElements.indexOf(v.name()) >= 0;
    }

    /** 表达式是否与粒子无关（只依赖云级变量、常量、元素） */
    private static boolean independent(Ast.Expr e, Scope sc)
    {
        if (e instanceof Ast.NumLit || e instanceof Ast.BoolLit)
        {
            return true;
        }
        if (e instanceof Ast.Var v)
        {
            // 外提量本身不在 Env 里，引用它的表达式不能再外提（否则求值时找不到名字）
            return sc.slot(v.name()) == null && sc.hoistSlot(v.name()) == null;
        }
        if (e instanceof Ast.Member m)
        {
            if (typeOf(m.base(), sc) == T.PARTICLE)
            {
                return false;
            }
            return independent(m.base(), sc);
        }
        if (e instanceof Ast.Unary u)
        {
            return independent(u.e(), sc);
        }
        if (e instanceof Ast.Binary b)
        {
            return independent(b.a(), sc) && independent(b.b(), sc);
        }
        if (e instanceof Ast.Call c)
        {
            if (c.fn().equals("rand"))
            {
                return false;
            }
            for (Ast.Expr a : c.args())
            {
                if (!independent(a, sc))
                {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static T particlePropType(String name)
    {
        switch (name)
        {
            case "pos":
            case "v":
            case "anchor":
            case "axis":
            case "local":
                return T.VEC3;
            case "energy":
            case "targetR":
            case "r":
            case "age":
                return T.NUM;
            default:
                throw new Unsupported();
        }
    }

    /** x/y/z → 0/1/2，其它 → -1 */
    private static int component(String name)
    {
        if (name.equals("x"))
        {
            return 0;
        }
        if (name.equals("y"))
        {
            return 1;
        }
        return name.equals("z") ? 2 : -1;
    }

    private static int width(T t)
    {
        return t == T.VEC3 ? 3 : 1;
    }

    // ---------------------------------------------------------------- 作用域

    private static final class Scope
    {
        final Scope parent;
        final Map<String, T> types = new HashMap<>();
        final Map<String, Integer> slots = new HashMap<>();
        final Map<String, Integer> hoists = new HashMap<>();

        Scope(Scope parent, Map<String, T> seed)
        {
            this.parent = parent;
            if (seed != null)
            {
                types.putAll(seed);
            }
        }

        T type(String name)
        {
            for (Scope s = this; s != null; s = s.parent)
            {
                T t = s.types.get(name);
                if (t != null)
                {
                    return t;
                }
            }
            return null;
        }

        Integer slot(String name)
        {
            for (Scope s = this; s != null; s = s.parent)
            {
                Integer i = s.slots.get(name);
                if (i != null)
                {
                    return i;
                }
            }
            return null;
        }

        Integer hoistSlot(String name)
        {
            for (Scope s = this; s != null; s = s.parent)
            {
                Integer i = s.hoists.get(name);
                if (i != null)
                {
                    return i;
                }
            }
            return null;
        }

        /**
         * @param slot 寄存器基址；-1 表示只登记类型（顶层作用域用）
         */
        void declare(String name, T t, int slot)
        {
            types.put(name, t);
            if (slot >= 0)
            {
                slots.put(name, slot);
            }
        }

        void declareHoist(String name, T t, int slot)
        {
            types.put(name, t);
            hoists.put(name, slot);
        }
    }

    // ---------------------------------------------------------------- 代码生成

    /** 三地址寄存器代码生成：每个表达式编进指定槽位 */
    private static final class Codegen
    {
        private final IntList code = new IntList();
        private final DoubleList consts = new DoubleList();
        private final Map<Long, Integer> constIndex = new HashMap<>();
        private final Loop loop;
        private final Scope top;
        private final Map<String, Free> freeIndex = new HashMap<>();
        /** 循环体里每个 let 的编号（落盘后再读回时用来把外提量挂回语法树） */
        private final Map<Ast.Let, Integer> letOrdinals = new IdentityHashMap<>();
        /** 水位：下一个空闲寄存器 */
        private int nextReg;
        /** 用过的最高水位，决定帧大小 */
        private int maxReg;
        /** 在 if 分支里的语句不参与外提（避免把可能抛错/不该执行的计算提前） */
        private int ifDepth;

        Codegen(Loop loop, Scope top)
        {
            this.loop = loop;
            this.top = top;
        }

        void compileBody(List<Ast.Stmt> body, String varName)
        {
            List<Ast.Let> lets = collectLets(body);
            for (int i = 0; i < lets.size(); i++)
            {
                letOrdinals.put(lets.get(i), i);
            }
            Scope sc = new Scope(top, null);
            sc.declare(varName, T.PARTICLE, -1);
            for (Ast.Stmt s : body)
            {
                stmt(s, sc);
            }
            loop.code = code.toArray();
            loop.consts = consts.toArray();
            loop.frameSize = Math.max(maxReg + 4, 16);
            if (System.getProperty("wizardmagic.script.debug") != null)
            {
                System.err.println("[script] 循环体：寄存器 " + maxReg + "，指令流 " + code.size()
                        + " 个 int，外提 " + loop.hoists.size() + "，自由变量 " + loop.frees.size());
            }
        }

        // ------------------------------------------------ 语句

        private void stmt(Ast.Stmt s, Scope sc)
        {
            if (s instanceof Ast.Let let)
            {
                let(let, sc);
                return;
            }
            if (s instanceof Ast.Assign assign)
            {
                assign(assign, sc);
                return;
            }
            if (s instanceof Ast.If branch)
            {
                branch(branch, sc);
                return;
            }
            if (s instanceof Ast.ExprStmt)
            {
                return;
            }
            throw new Unsupported();
        }

        private void let(Ast.Let let, Scope sc)
        {
            T t = typeOf(let.value(), sc);
            if (t != T.NUM && t != T.BOOL && t != T.VEC3)
            {
                throw new Unsupported();
            }
            if (ifDepth == 0 && t != T.BOOL && independent(let.value(), sc))
            {
                // 与粒子无关：每步算一次，循环里只读
                Hoist h = new Hoist();
                h.slot = loop.hoistSlots;
                loop.hoistSlots += width(t);
                if (loop.hoistSlots > MAX_REGS)
                {
                    throw new Unsupported();
                }
                h.expr = let.value();
                h.type = t;
                h.ordinal = letOrdinals.getOrDefault(let, -1);
                loop.hoists.add(h);
                sc.declareHoist(let.name(), t, h.slot);
                return;
            }
            int dst = alloc(t);
            expr(let.value(), sc, dst);
            sc.declare(let.name(), t, dst);
        }

        private void branch(Ast.If branch, Scope sc)
        {
            if (typeOf(branch.cond(), sc) != T.BOOL)
            {
                throw new Unsupported();
            }
            int base = nextReg;
            int cond = alloc(T.BOOL);
            expr(branch.cond(), sc, cond);
            emit2(OP_JZ, cond, 0);
            int jzTarget = code.add(-1);

            // 条件寄存器用完即弃，两个分支共用同一段槽位
            nextReg = base;
            Scope thenScope = new Scope(sc, null);
            ifDepth++;
            for (Ast.Stmt x : branch.then())
            {
                stmt(x, thenScope);
            }
            ifDepth--;

            int jmpTarget = -1;
            boolean hasElse = !branch.otherwise().isEmpty();
            if (hasElse)
            {
                emit1(OP_JMP, 0);
                jmpTarget = code.add(-1);
            }
            code.set(jzTarget, code.size());

            if (hasElse)
            {
                nextReg = base;
                Scope elseScope = new Scope(sc, null);
                ifDepth++;
                for (Ast.Stmt x : branch.otherwise())
                {
                    stmt(x, elseScope);
                }
                ifDepth--;
                code.set(jmpTarget, code.size());
            }
            nextReg = base;
        }

        private void assign(Ast.Assign a, Scope sc)
        {
            T rhs = typeOf(a.value(), sc);

            if (a.path().isEmpty())
            {
                Integer slot = sc.slot(a.base());
                if (slot == null)
                {
                    // 外提量、自由变量、元素：都不支持写
                    throw new Unsupported();
                }
                T cur = sc.type(a.base());
                if (cur != rhs)
                {
                    throw new Unsupported();
                }
                if (a.op().isEmpty())
                {
                    expr(a.value(), sc, slot);
                    return;
                }
                int mark = nextReg;
                int t = alloc(rhs);
                expr(a.value(), sc, t);
                binaryOp(a.op(), cur, rhs, slot, slot, t);
                nextReg = mark;
                return;
            }

            if (!a.path().isEmpty() && !(a.base().equals("p") || sc.type(a.base()) == T.PARTICLE))
            {
                throw new Unsupported();
            }
            if (a.path().size() != 1 && a.path().size() != 2)
            {
                throw new Unsupported();
            }

            int mark = nextReg;
            if (a.path().size() == 2)
            {
                // p.pos.x / p.v.y
                String prop = a.path().get(0);
                String comp = a.path().get(1);
                if (!(prop.equals("pos") || prop.equals("v")) || component(comp) < 0)
                {
                    throw new Unsupported();
                }
                if (rhs != T.NUM)
                {
                    throw new Unsupported();
                }
                int dst = alloc(T.NUM);
                if (a.op().isEmpty())
                {
                    expr(a.value(), sc, dst);
                }
                else
                {
                    emitLoadComponent(prop, comp, dst);
                    int t = alloc(T.NUM);
                    expr(a.value(), sc, t);
                    binaryOp(a.op(), T.NUM, rhs, dst, dst, t);
                }
                emitStoreComponent(prop, comp, dst);
                nextReg = mark;
                return;
            }

            // p.pos / p.v / p.energy
            String prop = a.path().get(0);
            T pt = particlePropType(prop);
            int dst = alloc(pt);
            if (a.op().isEmpty())
            {
                if (pt != rhs)
                {
                    throw new Unsupported();
                }
                expr(a.value(), sc, dst);
            }
            else
            {
                emitLoadProp(prop, dst);
                int t = alloc(rhs);
                expr(a.value(), sc, t);
                binaryOp(a.op(), pt, rhs, dst, dst, t);
            }
            switch (prop)
            {
                case "pos": emit1(OP_SPOS, dst); break;
                case "v": emit1(OP_SVEL, dst); break;
                case "energy": emit1(OP_SENERGY, dst); break;
                default: throw new Unsupported();
            }
            nextReg = mark;
        }

        // ------------------------------------------------ 表达式（写进 dst）

        private void expr(Ast.Expr e, Scope sc, int dst)
        {
            if (e instanceof Ast.NumLit n)
            {
                emitConst(n.v(), dst);
                return;
            }
            if (e instanceof Ast.BoolLit b)
            {
                emitConst(b.v() ? 1.0 : 0.0, dst);
                return;
            }
            if (e instanceof Ast.Var v)
            {
                T t = sc.type(v.name());
                Integer h = sc.hoistSlot(v.name());
                if (h != null)
                {
                    emit2(t == T.VEC3 ? OP_LDH3 : OP_LDH1, dst, h);
                    return;
                }
                Integer slot = sc.slot(v.name());
                if (slot != null)
                {
                    if (slot != dst)
                    {
                        emit2(width(t) == 3 ? OP_MOV3 : OP_MOV, dst, slot);
                    }
                    return;
                }
                if (t != null)
                {
                    emitFree(v.name(), t, dst);
                    return;
                }
                throw new Unsupported();
            }
            if (e instanceof Ast.Member m)
            {
                member(m, sc, dst);
                return;
            }
            if (e instanceof Ast.Unary u)
            {
                if (u.op().equals("not"))
                {
                    int mark = nextReg;
                    int a = alloc(T.BOOL);
                    expr(u.e(), sc, a);
                    emit2(OP_NOT, dst, a);
                    nextReg = mark;
                    return;
                }
                int mark = nextReg;
                T t = typeOf(u.e(), sc);
                int a = alloc(t);
                expr(u.e(), sc, a);
                emit2(t == T.VEC3 ? OP_NEG_V : OP_NEG_N, dst, a);
                nextReg = mark;
                return;
            }
            if (e instanceof Ast.Binary b)
            {
                T a = typeOf(b.a(), sc);
                T c = typeOf(b.b(), sc);
                int mark = nextReg;
                int sa = alloc(a);
                expr(b.a(), sc, sa);
                int sb = alloc(c);
                expr(b.b(), sc, sb);
                binaryOp(b.op(), a, c, dst, sa, sb);
                nextReg = mark;
                return;
            }
            if (e instanceof Ast.Call c)
            {
                call(c, sc, dst);
                return;
            }
            throw new Unsupported();
        }

        private void member(Ast.Member m, Scope sc, int dst)
        {
            T base = typeOf(m.base(), sc);
            if (base == T.PARTICLE)
            {
                emitLoadProp(m.name(), dst);
                return;
            }
            int off = component(m.name());
            if (base != T.VEC3 || off < 0)
            {
                throw new Unsupported();
            }

            // p.pos.x / p.axis.y …：单分量直取，省掉"搬整个向量再 PICK"
            if (m.base() instanceof Ast.Member inner && typeOf(inner.base(), sc) == T.PARTICLE)
            {
                emitLoadComponent(inner.name(), m.name(), dst);
                return;
            }

            int mark = nextReg;
            int src;
            Integer slot = m.base() instanceof Ast.Var mv ? sc.slot(mv.name()) : null;
            if (slot != null)
            {
                src = slot;
            }
            else
            {
                src = alloc(T.VEC3);
                expr(m.base(), sc, src);
            }
            emit3(OP_PICK, dst, src, off);
            nextReg = mark;
        }

        private void call(Ast.Call c, Scope sc, int dst)
        {
            String fn = c.fn();
            List<Ast.Expr> args = c.args();

            if (fn.equals("gravity") || fn.equals("volatility"))
            {
                int id = SpellElements.indexOf(((Ast.Var) args.get(0)).name());
                emitConst(fn.equals("gravity") ? SpellElements.gravity(id) : SpellElements.volatility(id), dst);
                return;
            }
            if (fn.equals("vec"))
            {
                // 三个 num 落在连续槽位上就是 vec3 的布局，不需要指令
                expr(args.get(0), sc, dst);
                expr(args.get(1), sc, dst + 1);
                expr(args.get(2), sc, dst + 2);
                return;
            }

            int op;
            switch (fn)
            {
                case "abs": op = OP_ABS; break;
                case "sqrt": op = OP_SQRT; break;
                case "sin": op = OP_SIN; break;
                case "cos": op = OP_COS; break;
                case "exp": op = OP_EXP; break;
                case "floor": op = OP_FLOOR; break;
                case "round": op = OP_ROUND; break;
                case "min": op = OP_MIN; break;
                case "max": op = OP_MAX; break;
                case "clamp": op = OP_CLAMP; break;
                case "smoothstep": op = OP_SMOOTHSTEP; break;
                case "len": op = OP_LEN; break;
                case "len_xz": op = OP_LEN_XZ; break;
                case "normalize": op = OP_NORMALIZE; break;
                case "dot": op = OP_DOT; break;
                case "cross": op = OP_CROSS; break;
                case "dist": op = OP_DIST; break;
                case "tangent": op = OP_TANGENT; break;
                case "radial": op = OP_RADIAL; break;
                case "noise": op = OP_NOISE; break;
                case "lerp": op = typeOf(args.get(0), sc) == T.VEC3 ? OP_LERP_V : OP_LERP_N; break;
                default: throw new Unsupported();
            }

            int mark = nextReg;
            int[] src = new int[args.size()];
            for (int j = 0; j < src.length; j++)
            {
                Ast.Expr arg = args.get(j);
                src[j] = alloc(typeOf(arg, sc));
                expr(arg, sc, src[j]);
            }
            // 头两个源进打包字，多出来的（clamp/smoothstep/lerp 的第 3 个、noise 的第 3、4 个）
            // 跟在后面当独立 int
            code.add(word(op, dst, src.length > 0 ? src[0] : 0, src.length > 1 ? src[1] : 0));
            for (int j = 2; j < src.length; j++)
            {
                code.add(src[j]);
            }
            nextReg = mark;
        }

        // ------------------------------------------------ 指令拼装

        private void binaryOp(String op, T a, T b, int dst, int sa, int sb)
        {
            switch (op)
            {
                case "+":
                    emit3(a == T.VEC3 ? OP_ADD_V : OP_ADD_N, dst, sa, sb);
                    return;
                case "-":
                    emit3(a == T.VEC3 ? OP_SUB_V : OP_SUB_N, dst, sa, sb);
                    return;
                case "*":
                    if (a == T.VEC3)
                    {
                        emit3(OP_SCALE, dst, sa, sb);
                    }
                    else if (b == T.VEC3)
                    {
                        // num * vec3：IEEE 乘法可交换，直接换序复用同一条指令
                        emit3(OP_SCALE, dst, sb, sa);
                    }
                    else
                    {
                        emit3(OP_MUL_N, dst, sa, sb);
                    }
                    return;
                case "/":
                    emit3(a == T.VEC3 ? OP_DIV_SCALE : OP_DIV_N, dst, sa, sb);
                    return;
                case "<": emit3(OP_LT, dst, sa, sb); return;
                case "<=": emit3(OP_LE, dst, sa, sb); return;
                case ">": emit3(OP_GT, dst, sa, sb); return;
                case ">=": emit3(OP_GE, dst, sa, sb); return;
                case "==": emit3(OP_EQ_N, dst, sa, sb); return;
                case "!=": emit3(OP_NE_N, dst, sa, sb); return;
                case "and": emit3(OP_AND, dst, sa, sb); return;
                case "or": emit3(OP_OR, dst, sa, sb); return;
                default: throw new Unsupported();
            }
        }

        /** 只有目标 */
        private void emit1(int op, int dst)
        {
            code.add(word(op, dst, 0, 0));
        }

        /** 目标 + 一个操作数 */
        private void emit2(int op, int dst, int a)
        {
            code.add(word(op, dst, a, 0));
        }

        /** 目标 + 两个操作数 */
        private void emit3(int op, int dst, int a, int b)
        {
            code.add(word(op, dst, a, b));
        }

        private void emitLoadProp(String prop, int dst)
        {
            int op;
            switch (prop)
            {
                case "pos": op = OP_LPOS; break;
                case "v": op = OP_LVEL; break;
                case "anchor": op = OP_LANCHOR; break;
                case "axis": op = OP_LAXIS; break;
                case "local": op = OP_LLOCAL; break;
                case "energy": op = OP_LENERGY; break;
                case "targetR": op = OP_LTARGET; break;
                case "r": op = OP_LR; break;
                case "age": op = OP_LAGE; break;
                default: throw new Unsupported();
            }
            emit1(op, dst);
        }

        private void emitLoadComponent(String prop, String comp, int dst)
        {
            int k = component(comp);
            if (k < 0)
            {
                throw new Unsupported();
            }
            int op;
            if (prop.equals("pos"))
            {
                op = k == 0 ? OP_LPOSX : k == 1 ? OP_LPOSY : OP_LPOSZ;
            }
            else if (prop.equals("v"))
            {
                op = k == 0 ? OP_LVELX : k == 1 ? OP_LVELY : OP_LVELZ;
            }
            else if (prop.equals("anchor"))
            {
                op = k == 0 ? OP_LANCHORX : k == 1 ? OP_LANCHORY : OP_LANCHORZ;
            }
            else if (prop.equals("local"))
            {
                op = k == 0 ? OP_LLOCALX : k == 1 ? OP_LLOCALY : OP_LLOCALZ;
            }
            else if (prop.equals("axis"))
            {
                op = k == 0 ? OP_LAXISX : k == 1 ? OP_LAXISY : OP_LAXISZ;
            }
            else
            {
                throw new Unsupported();
            }
            emit1(op, dst);
        }

        private void emitStoreComponent(String prop, String comp, int dst)
        {
            int k = component(comp);
            if (k < 0)
            {
                throw new Unsupported();
            }
            if (prop.equals("pos"))
            {
                emit1(k == 0 ? OP_SPOSX : k == 1 ? OP_SPOSY : OP_SPOSZ, dst);
            }
            else
            {
                emit1(k == 0 ? OP_SVELX : k == 1 ? OP_SVELY : OP_SVELZ, dst);
            }
        }

        private void emitConst(double v, int dst)
        {
            long bits = Double.doubleToRawLongBits(v);
            Integer k = constIndex.get(bits);
            if (k == null)
            {
                k = consts.add(v);
                constIndex.put(bits, k);
            }
            code.add(word(OP_LDK, dst, 0, 0));
            code.add(k);
        }

        private void emitFree(String name, T t, int dst)
        {
            Free f = freeIndex.get(name);
            if (f == null)
            {
                f = new Free(name, t);
                f.offset = loop.freeSlots;
                loop.freeSlots += width(t);
                if (loop.freeSlots > MAX_REGS)
                {
                    throw new Unsupported();
                }
                freeIndex.put(name, f);
                loop.frees.add(f);
            }
            emit2(t == T.VEC3 ? OP_LDF3 : OP_LDF1, dst, f.offset);
        }

        // ------------------------------------------------ 寄存器分配

        private int alloc(T t)
        {
            int base = nextReg;
            nextReg += width(t);
            if (nextReg > MAX_REGS)
            {
                throw new Unsupported();
            }
            if (nextReg > maxReg)
            {
                maxReg = nextReg;
            }
            return base;
        }
    }

    // ---------------------------------------------------------------- 容器

    private static final class IntList
    {
        private int[] data = new int[64];
        private int size;

        int add(int v)
        {
            if (size == data.length)
            {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size] = v;
            return size++;
        }

        void set(int i, int v)
        {
            data[i] = v;
        }

        int[] toArray()
        {
            return java.util.Arrays.copyOf(data, size);
        }

        int size()
        {
            return size;
        }
    }

    private static final class DoubleList
    {
        private double[] data = new double[32];
        private int size;

        int add(double v)
        {
            if (size == data.length)
            {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size] = v;
            return size++;
        }

        double[] toArray()
        {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
