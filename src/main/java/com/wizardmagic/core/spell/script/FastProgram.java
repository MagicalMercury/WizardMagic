package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.ShapeParams;
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

    /**
     * 无法安全编译的写法：退回树遍历。
     *
     * <p>带<b>人话原因</b>：以前这里没有 message，作者只看到"这朵云退回解释器"，
     * 不知道是哪一行、哪种写法让它掉的。原因经 {@link SpellScript#fastPathReason()}
     * 传到生成日志与 {@code /spell debug scripts}。</p>
     */
    private static final class Unsupported extends RuntimeException
    {
        Unsupported(String reason)
        {
            // 关掉栈记录：这是"预期内的拒绝"，不是异常路径
            super(reason, null, false, false);
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
        /** 每条指令的源码位置（{@code line<<12|col}），运行期报错定位用 */
        int[] pos;
        double[] consts;
        int frameSize;
        /** 自由变量：名字、槽位、类型；每步从 Env 读一次 */
        final List<Free> frees = new ArrayList<>();
        int freeSlots;
        /** 每步算一次、逐粒子复用的表达式（循环体内与粒子无关的 let） */
        final List<Hoist> hoists = new ArrayList<>();
        int hoistSlots;
        /** 顶层 if 守门量所在的 {@code hv} 槽；-1 = 这个循环没有守卫 */
        int guardSlot = -1;
        /** true = 循环在 {@code else} 分支里（条件为假时才跑） */
        boolean guardNegate;
        /** 原生内核句柄（懒创建；0 = 没建或不可用） */
        long nativeHandle;
        /** 指令流扫描结果：含 fdlibm 指令则整段留 Java */
        boolean scanned;
        boolean nativeUnsafe;
    }

    private static final class Free
    {
        final String name;
        final T type;
        int offset;
        /** true = 循环体里给这个变量赋过值（整段跑完后要搬回 Env 并落盘到云上） */
        boolean written;

        Free(String name, T type)
        {
            this.name = name;
            this.type = type;
        }
    }

    /** 循环体内与粒子无关的 let：每步求值一次，粒子循环只读 */
    private static final class Hoist
    {
        /** 循环体里的 {@code let} */
        static final int KIND_LET = 0;
        /** 顶层 {@code if} 的守门条件：每步算一次，进循环前判一次 */
        static final int KIND_GUARD = 1;
        /** 循环体内与粒子无关的任意子表达式（LICM）：靠结构键落盘 */
        static final int KIND_EXPR = 2;

        int slot;
        Ast.Expr expr;
        T type;
        /** 在循环体里是第几个 {@code let}（按语句顺序编号）；读盘时靠它重新挂到语法树上 */
        int ordinal;
        /** KIND_EXPR 的结构键（读盘时在语法树里找回同一个子表达式） */
        String key;
        int kind = KIND_LET;
        /** 守门条件在"顶层可守门的 if"里的序号；只有 {@link #KIND_GUARD} 用 */
        int guardOrdinal = -1;
    }

    /** 一步的准备工作：搬好的自由变量 + 算好的外提量 */
    private static final class Prepared
    {
        int[] code;
        /** 和 {@code code} 一一对应的源码位置 */
        int[] pos;
        double[] consts;
        /** 寄存器帧大小；帧本身每次 runStep 新建（可能被多个线程同时跑） */
        int frameSize;
        double[] fv;
        double[] hv;
        /** 这段指令流可以走 4 粒通道执行器（不写云级变量、无 rand、无未知指令） */
        boolean lanes;
        /** 原生内核句柄；0 = 这段走 Java（不可用 / 含 fdlibm 指令） */
        long nativeHandle;
    }

    private static final Prepared[] NO_PREPARED = new Prepared[0];

    /**
     * 指令集版本号。
     *
     * <p><b>动 {@link RegVm} 的指令编码、寄存器分配规则，或 {@link #collectLets} 的编号顺序时
     * 必须 +1</b>：存档里的 {@code .spellc} 只认这个号，不认"长得差不多"。</p>
     */
    static final int IR_VERSION = 8;

    /** 类型序号会写进字节码缓存，<b>别重排这个枚举</b> */
    private static final T[] TYPES = T.values();

    /** 循环不变子表达式外提开关：{@code -Dwizardmagic.spell.licm=0} 关闭（A/B 对照用） */
    private static final boolean LICM =
            !"0".equals(System.getProperty("wizardmagic.spell.licm"));

    /**
     * if-conversion（JZ→SELECT）开关：{@code -Dwizardmagic.spell.ifconv=0} 关闭（A/B 对照用）。
     *
     * <p>把"两条分支都是给同一个 let 赋纯表达式"的 if 编译成 SELECT 而不是 JZ，
     * 好处是这一分支不会让向量内核撞上"组内分歧"（JZ 分歧 0.34× vs SELECT 1.22×）。
     * 关掉它回到 JZ 版本，用来量这一步到底值多少。</p>
     */
    private static final boolean IFCONV =
            !"0".equals(System.getProperty("wizardmagic.spell.ifconv"));

    /** if-conversion 的判据被拒原因（{@code -Dwizardmagic.spell.ifconv.debug=1} 打到 stderr） */
    private static final boolean IFCONV_DEBUG =
            System.getProperty("wizardmagic.spell.ifconv.debug") != null;

    private final List<Object> segments = new ArrayList<>();
    private final int loopCount;
    /** 能不能按粒子区间切段并行（{@code rand()} 会把它压成 false，见 {@link #parallelSafe()}） */
    private final boolean parallelSafe;
    /** 最近一次运行期准备失败的原因；成功时为 null */
    private volatile String prepareReason;

    // ---------------------------------------------------------------- 编译入口

    /** 编译结果：要么有程序，要么有「为什么编不出来」 */
    static final class Result
    {
        /** 编好的程序；null = 不支持快速路径，看 {@link #reason} */
        final FastProgram program;
        /** 退化原因（人话）；编译成功时为 null */
        final String reason;

        private Result(FastProgram program, String reason)
        {
            this.program = program;
            this.reason = reason;
        }
    }

    static FastProgram compile(Ast.Program program, Map<String, Value> vars)
    {
        return compileChecked(program, vars).program;
    }

    /**
     * 编译，失败时把原因带出来。
     *
     * @return {@code program == null} 时 {@code reason} 说明是哪条写法不支持快速路径
     */
    static Result compileChecked(Ast.Program program, Map<String, Value> vars)
    {
        try
        {
            return new Result(fromAst(program, vars), null);
        }
        catch (Unsupported unsupported)
        {
            String reason = unsupported.getMessage();
            if (reason == null || reason.isEmpty())
            {
                reason = "不支持快速路径";
            }
            if (System.getProperty("wizardmagic.script.debug") != null)
            {
                System.err.println("[script] 快速路径不支持：" + reason);
            }
            return new Result(null, reason);
        }
    }

    /**
     * 表达式里有没有 {@code rand()}（含嵌套）。
     *
     * <p>它是唯一<b>有状态</b>的内建：序列按执行顺序消耗。所以它只能出现在粒子循环体里
     * （那里编译成 {@code OP_RAND}，序列跟着指令走）；写在顶层标量或守卫条件里就得由解释器
     * 用<b>另一个</b> Interpreter 实例算，序号从 0 重新开始，和"整段都在解释器里跑"对不上。</p>
     * /
    /** 一段语句里（递归）有没有 {@code call 事件()} */
    private static boolean containsCall(List<Ast.Stmt> body)
    {
        for (Ast.Stmt s : body)
        {
            if (s instanceof Ast.CallEvent)
            {
                return true;
            }
            if (s instanceof Ast.If branch)
            {
                if (containsCall(branch.then()) || containsCall(branch.otherwise()))
                {
                    return true;
                }
            }
            else if (s instanceof Ast.ForParticles loop && containsCall(loop.body()))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRand(Ast.Expr e)
    {
        if (e instanceof Ast.Call c)
        {            if (c.fn().equals("rand"))
            {
                return true;
            }
            for (Ast.Expr a : c.args())
            {
                if (containsRand(a))
                {
                    return true;
                }
            }
            return false;
        }
        if (e instanceof Ast.Member m)
        {
            return containsRand(m.base());
        }
        if (e instanceof Ast.Unary u)
        {
            return containsRand(u.e());
        }
        if (e instanceof Ast.Binary b)
        {
            return containsRand(b.a()) || containsRand(b.b());
        }
        if (e instanceof Ast.RecordLit r)
        {
            for (Ast.Expr v : r.fields().values())
            {
                if (containsRand(v))
                {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** 这条顶层标量语句里有没有 {@code rand()} */    private static boolean stmtHasRand(Ast.Stmt s)
    {
        if (s instanceof Ast.Let l)
        {
            return containsRand(l.value());
        }
        if (s instanceof Ast.Assign a)
        {
            return containsRand(a.value());
        }
        if (s instanceof Ast.ExprStmt es)
        {
            return containsRand(es.expr());
        }
        return false;
    }

    private static FastProgram fromAst(Ast.Program program, Map<String, Value> vars)
    {
        Scope top = new Scope(null, globalsOf(vars));
        java.util.Set<String> writable = vars == null ? java.util.Set.of() : vars.keySet();
        List<Object> segments = new ArrayList<>();
        boolean sawLoop = false;
        int loops = 0;
        int guardOrdinal = 0;
        // call 出现在 tick 的任何位置（含顶层 if 里、循环体里）都不能编：
        // 事件体是解释器跑的一整段代码，寄存器机没法中途跳出去跑一段再回来。
        if (containsCall(program.tick()))
        {
            throw new Unsupported("on tick 里用了 call（自定义事件由解释器执行，"
                    + "把 call 挪到 on hit / on expire 里就不会掉快速路径）");
        }
        for (Ast.Stmt s : program.tick())
        {
            if (s instanceof Ast.ForParticles loop)
            {
                segments.add(buildLoop(loop, top, writable));
                sawLoop = true;
                loops++;
            }
            else if (s instanceof Ast.If branch)
            {
                // 顶层 if：展开成"带守卫的循环"。条件每步只算一次（进 hv），
                // 循环开头判一下——这样"时间/阶段到了才进循环"也能编译。
                List<Guarded> plan = guardPlan(branch);
                if (plan == null)
                {
                    throw new Unsupported("on tick 顶层的 if 里要有粒子循环（"
                            + "if (cond) { for p in particles { ... } }）");
                }
                if (typeOf(branch.cond(), top) != T.BOOL)
                {
                    throw new Unsupported("顶层 if 的条件必须是 bool");
                }
                if (containsRand(branch.cond()))
                {
                    throw new Unsupported("rand() 只能写在粒子循环里（守卫条件每步只算一次，"
                            + "序列和解释器对不上）");
                }
                if (!independent(branch.cond(), top))
                {
                    throw new Unsupported("顶层 if 的条件必须与粒子无关");
                }
                for (Guarded g : plan)
                {
                    segments.add(buildLoop(g.loop(), top, branch.cond(), guardOrdinal, g.negate(), writable));
                    loops++;
                }
                sawLoop = true;
                guardOrdinal++;
            }
            else if (s instanceof Ast.Let || s instanceof Ast.Assign || s instanceof Ast.ExprStmt)
            {
                if (sawLoop)
                {
                    // 粒子循环之后还有标量语句：为了能"先校验后执行"，这里不支持
                    throw new Unsupported("粒子循环之后还有标量语句（快速路径要「先校验后执行」）");
                }
                if (stmtHasRand(s))
                {
                    throw new Unsupported("rand() 只能写在粒子循环里（循环外它由另一个解释器"
                            + "实例求值，随机序列和整段解释执行对不上）");
                }
                inferStmt(s, top);
                segments.add(new Scalar(s));
            }
            else if (s instanceof Ast.Emit)
            {
                // tick 里的 emit 目前只能在解释器里跑：生成子云要等整步结束（服务端管理器处理），
                // 而寄存器机是"每粒一段"跑完就完事，塞不进这个时机。
                throw new Unsupported("on tick 里的 emit 目前只能用解释器（子云要等整步结束后生成）");
            }
            else if (s instanceof Ast.CallEvent)
            {
                // 自定义事件的体是解释器跑的一整段代码，寄存器机没法在中间"跳进去跑一段"。
                // 想保留快速路径就把 call 挪到 on hit / on expire 里（那两个阶段本来就解释执行）。
                throw new Unsupported("on tick 里用了 call（自定义事件由解释器执行，"
                        + "把 call 挪到 on hit / on expire 里就不会掉快速路径）");
            }
            else
            {
                throw new Unsupported("on tick 顶层只支持 let / 赋值 / 表达式 / for p in particles / if / emit");
            }
        }
        if (segments.isEmpty())
        {
            throw new Unsupported("on tick 里没有粒子循环");
        }        // 写云级变量：值先落在 fv 里、整段跑完才搬回 Env。多条循环会各自持有快照，
        // 第二段读不到第一段写进去的值（解释器里是立刻可见的），所以只支持单循环。
        if (loops > 1)
        {
            for (Object seg : segments)
            {
                if (seg instanceof Loop L && writesCloudVar(L))
                {
                    throw new Unsupported("写云级变量目前只支持单个粒子循环");
                }
            }
        }
        return new FastProgram(segments, loops);
    }

    /** 这个循环体写过云级变量吗 */
    private static boolean writesCloudVar(Loop L)
    {
        for (Free f : L.frees)
        {
            if (f.written)
            {
                return true;
            }
        }
        return false;
    }

    private FastProgram(List<Object> segments, int loopCount)
    {
        this.segments.addAll(segments);
        this.loopCount = loopCount;
        boolean safe = true;
        for (Object seg : this.segments)
        {
            if (seg instanceof Loop L && L.code != null)
            {
                if (!rangeSafe(L.code) || writesCloudVar(L))
                {
                    // rand() 的序列、云级变量的写回都依赖"按粒子顺序整段执行"
                    safe = false;
                }
            }
        }
        this.parallelSafe = safe;
    }

    /**
     * 这段指令能不能按粒子区间切段并行。
     *
     * <p>{@code rand()} 的序列是"按粒子顺序"消耗的（第 0 粒的几次、第 1 粒的几次……），
     * 切段之后每个工作线程的起点不同，序列就变了。所以带 {@code OP_RAND} 的循环体整朵云
     * 只能一次跑完——{@link SpellCloud#beginStep()} 会看到 {@link #parallelSafe()} 为假，
     * 直接把整段跑掉、不进并行池。</p>
     */
    boolean parallelSafe()
    {
        return parallelSafe;
    }

    private static boolean rangeSafe(int[] code)
    {
        for (int w : code)
        {
            if ((w >>> 24) == RegVm.OP_RAND)
            {
                return false;
            }
        }
        return true;
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
            // 指令的位置表（运行期报错的行列）；长度必须和指令流一致
            out.writeInt(L.pos.length);
            for (int x : L.pos)
            {
                out.writeInt(x);
            }
            out.writeInt(L.frees.size());
            for (Free f : L.frees)
            {
                out.writeUTF(f.name);
                out.writeByte(f.type.ordinal());
                out.writeInt(f.offset);
                out.writeBoolean(f.written);
            }
            out.writeInt(L.hoists.size());
            for (Hoist h : L.hoists)
            {
                // kind + 编号（let 的序号 / 守门条件在所有可守门 if 里的序号）
                out.writeByte(h.kind);
                if (h.kind == Hoist.KIND_GUARD)
                {
                    if (h.guardOrdinal < 0)
                    {
                        throw new IOException("守门条件缺少编号");
                    }
                    out.writeInt(h.guardOrdinal);
                }
                else if (h.kind == Hoist.KIND_EXPR)
                {
                    if (h.key == null || h.key.isEmpty())
                    {
                        throw new IOException("子表达式外提缺少结构键");
                    }
                    out.writeInt(0);
                    out.writeUTF(h.key);
                }
                else
                {
                    if (h.ordinal < 0)
                    {
                        // 没有编号 = 读回时挂不回语法树，这份程序不能落盘
                        throw new IOException("外提量缺少编号");
                    }
                    out.writeInt(h.ordinal);
                }
                out.writeInt(h.slot);
                out.writeByte(h.type.ordinal());
            }
        }
    }

    /**
     * 读盘用：把 {@code on tick} 展平成"循环 / 标量"清单，和 {@link #fromAst} 同一个走法。
     *
     * <p>顶层 {@code if} 会被展成带守卫的循环（见 {@link #guardPlan}），所以缓存里存的是
     * "每个循环 + 它的守门条件序号"，读回时必须按同一顺序重建。</p>
     */
    private record Plan(Object node, boolean negate, int guardOrdinal)
    {
    }

    private static List<Plan> plan(List<Ast.Stmt> tick)
    {
        List<Plan> out = new ArrayList<>();
        boolean sawLoop = false;
        int guardOrdinal = 0;
        for (Ast.Stmt s : tick)
        {
            if (s instanceof Ast.ForParticles loop)
            {
                out.add(new Plan(loop, false, -1));
                sawLoop = true;
            }
            else if (s instanceof Ast.If branch)
            {
                List<Guarded> gs = guardPlan(branch);
                if (gs == null)
                {
                    return null;
                }
                for (Guarded g : gs)
                {
                    out.add(new Plan(g.loop(), g.negate(), guardOrdinal));
                }
                sawLoop = true;
                guardOrdinal++;
            }
            else if (s instanceof Ast.Let || s instanceof Ast.Assign || s instanceof Ast.ExprStmt)
            {
                if (sawLoop)
                {
                    return null;
                }
                out.add(new Plan(s, false, -1));
            }
            else
            {
                return null;
            }
        }
        return out;
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
            List<Plan> plans = plan(program.tick());
            if (plans == null)
            {
                return null;
            }
            int loopCount = in.readInt();
            int loops = 0;
            for (Plan p : plans)
            {
                if (p.node() instanceof Ast.ForParticles)
                {
                    loops++;
                }
            }
            if (loops != loopCount || loops == 0)
            {
                return null;
            }
            List<Ast.Expr> guards = guardConditions(program.tick());

            List<Object> segments = new ArrayList<>();
            for (Plan p : plans)
            {
                if (p.node() instanceof Ast.ForParticles loop)
                {
                    Loop L = readLoop(in, loop, guards, p.negate());
                    if (L == null)
                    {
                        return null;
                    }
                    segments.add(L);
                }
                else
                {
                    segments.add(new Scalar((Ast.Stmt) p.node()));
                }
            }
            return new FastProgram(segments, loopCount);
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    private static Loop readLoop(DataInputStream in, Ast.ForParticles loop,
                                 List<Ast.Expr> guards, boolean negate) throws IOException
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

        int nPos = in.readInt();
        if (nPos != nCode)
        {
            return null;
        }
        L.pos = new int[nPos];
        for (int i = 0; i < nPos; i++)
        {
            L.pos[i] = in.readInt();
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
            boolean written = in.readBoolean();
            if (typeId < 0 || typeId >= TYPES.length || off < 0 || off >= L.frameSize)
            {
                return null;
            }
            T type = TYPES[typeId];
            Free f = new Free(name, type);
            f.offset = off;
            f.written = written;
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
            int kind = in.readByte();
            int id = in.readInt();
            String exprKey = kind == Hoist.KIND_EXPR ? in.readUTF() : null;
            int slot = in.readInt();
            int typeId = in.readByte();
            if (typeId < 0 || typeId >= TYPES.length || slot < 0 || slot >= MAX_REGS)
            {
                return null;
            }
            T type = TYPES[typeId];
            Hoist h = new Hoist();
            h.kind = kind;
            h.slot = slot;
            h.type = type;
            if (kind == Hoist.KIND_GUARD)
            {
                if (id < 0 || id >= guards.size() || type != T.BOOL)
                {
                    return null;
                }
                h.guardOrdinal = id;
                h.expr = guards.get(id);
                L.guardSlot = slot;
                L.guardNegate = negate;
            }
            else if (kind == Hoist.KIND_LET)
            {
                if (id < 0 || id >= lets.size())
                {
                    return null;
                }
                h.ordinal = id;
                h.expr = lets.get(id).value();
            }
            else if (kind == Hoist.KIND_EXPR)
            {
                Ast.Expr expr = findExpr(loop.body(), exprKey);
                if (expr == null)
                {
                    return null;
                }
                h.kind = Hoist.KIND_EXPR;
                h.key = exprKey;
                h.expr = expr;
            }
            else
            {
                return null;
            }
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

    /**
     * 收集循环体里（含 if 分支内）被赋值过的名字。
     *
     * <p>用途见 {@code Codegen.assignedInBody}：这类名字如果又是 {@code let}，就**不能外提**，
     * 否则体内那句赋值会抛 Unsupported，整段掉进解释器。</p>
     */
    private static void collectAssigned(List<Ast.Stmt> body, java.util.Set<String> out)
    {
        for (Ast.Stmt s : body)
        {
            if (s instanceof Ast.Assign a)
            {
                out.add(a.base());
            }
            else if (s instanceof Ast.If branch)
            {
                collectAssigned(branch.then(), out);
                collectAssigned(branch.otherwise(), out);
            }
        }
    }

    // ---------------------------------------------------------------- 每步执行

    /**
     * @return true = 已处理；false = 这朵云不适合快速路径，调用方退回解释器
     */
    boolean tick(SpellCloud cloud)
    {
        Step step = prepareStep(cloud);
        if (step == null)
        {
            return false;
        }
        runStep(step, cloud, 0, cloud.count);
        completeStep(step, cloud);
        return true;
    }

    /**
     * 一步的「准备」：跑标量段、搬自由变量、算外提量。**必须在调用线程上做一次**，
     * 之后才能把任意粒子区间丢给工作线程。
     *
     * @return null = 这朵云不支持快速路径（此时粒子还没被动过，退回解释器是干净的）
     */
    static final class Step
    {
        private final Prepared[] prepared;
        private final double time;
        private final Env root;

        Step(Prepared[] prepared, double time, Env root)
        {
            this.prepared = prepared;
            this.time = time;
            this.root = root;
        }
    }

    Step prepareStep(SpellCloud cloud)
    {
        prepareReason = null;
        Env root;
        try
        {
            root = Interpreter.rootEnv(cloud);
        }
        catch (RuntimeException e)
        {
            prepareReason = "运行期环境准备失败：" + e.getMessage();
            return null;
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
                String[] why = new String[1];
                Prepared p = prepare(L, cloud, root, why);
                if (p == null)
                {
                    prepareReason = why[0] == null ? "运行期准备失败" : why[0];
                    return null;
                }
                prepared[li++] = p;
            }
        }
        return new Step(prepared, cloud.ageSteps() * SpellConstants.DT, root);
    }

    /** 最近一次 {@link #prepareStep} 失败的原因（人话）；成功时为 null */
    String prepareReason()
    {
        return prepareReason;
    }

    /** 跑 {@code [from, to)}；可以在任意线程上并发调用（寄存器帧每次新建） */
    void runStep(Step step, SpellCloud cloud, int from, int to)
    {
        int li = 0;
        // 同一步里可能有多个循环：rand() 的序列要接着上一条往下数（与解释器一致）
        int randSeq = 0;
        boolean lanes = parallelSafe;
        for (Object seg : segments)
        {
            if (seg instanceof Loop)
            {
                Prepared p = step.prepared[li++];
                if (p.nativeHandle != 0)
                {
                    // 原生内核：1 lane 标量实现，语义与 RegVm 逐条对齐
                    randSeq = runNative(p, cloud, from, to, step.time, randSeq);
                }
                else if (lanes && p.lanes)
                {
                    // 4 粒通道：同一指令流一次分派，4 条 lane 各执行一遍（逐位一致）
                    RegVm4.execute(p.code, p.pos, p.consts,
                            new double[p.frameSize * RegVm4.LANES],
                            p.fv, p.hv, cloud, from, to, step.time);
                }
                else
                {
                    randSeq = RegVm.execute(p.code, p.pos, p.consts, new double[p.frameSize],
                            p.fv, p.hv, cloud, from, to, step.time, randSeq);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 原生内核
    //
    // 只搬"逐粒子"的那一段：标量段、外提、自由变量搬运都还在 Java。
    // 含 exp/pow/atan2 的程序整段留在 Java（这三条要 fdlibm，native 还没移植），
    // 这样"能跑 native"和"结果逐位正确"是两个独立的事实，不会互相污染。

    /** 原生内核真正跑过多少段（诊断/自检：区分"跑过且一致"和"根本没走原生"） */
    private static final java.util.concurrent.atomic.AtomicLong NATIVE_STEPS =
            new java.util.concurrent.atomic.AtomicLong();

    static long nativeSteps()
    {
        return NATIVE_STEPS.get();
    }

    /**
     * 形状倾斜矩阵的复用缓冲（每段一次，不分配；不倾斜时整段不用）。
     *
     * <p>axis / target / rnd 原先也在这里预算成五组数组再交给原生，现在原生自己按
     * {@code (i, time)} 算（见 {@code native/wmvm/src/shapes.h}），这几块缓冲随之删掉。</p>
     */
    private static final ThreadLocal<double[]> SCRATCH_TILT = ThreadLocal.withInitial(() -> new double[9]);

    private static double[] tiltScratch()
    {
        return SCRATCH_TILT.get();
    }

    /** 扫描指令流：有没有原生内核这一版还实现不了的 fdlibm 指令 */
    private static void scanNative(Loop L)
    {
        if (L.scanned)
        {
            return;
        }
        L.scanned = true;
        int pc = 0;
        final int n = L.code.length;
        while (pc < n)
        {
            final int w = L.code[pc];
            final int op = w >>> 24;
            if (op == RegVm.OP_EXP || op == RegVm.OP_POW || op == RegVm.OP_ATAN2)
            {
                L.nativeUnsafe = true;
            }
            pc += RegVm.length(op);
        }
    }

    private static long nativeHandleFor(Loop L)
    {
        if (L.nativeUnsafe || !NativeVm.enabled())
        {
            return 0L;
        }
        long h = L.nativeHandle;
        if (h == 0L)
        {
            h = NativeVm.create(L.code, L.consts);
            L.nativeHandle = h;
        }
        return h;
    }

    /** 跑一段原生；返回本段结束时的 rand 序号 */
    private static int runNative(Prepared p, SpellCloud cloud, int from, int to, double time, int randSeq)
    {
        // 轴心 / 目标半径 / 每粒随机数都由原生按 (i, time) 自己算：每步少一遍全量预算与
        // 五组数组的写 + 读（形状参数与 pow 缓存都是每朵云不变的，只传指针/标量）。
        final ShapeParams sp = cloud.shapeParams;
        final boolean tilted = cloud.nativeTilted();
        double[] tilt = null;
        if (tilted)
        {
            tilt = tiltScratch();
            cloud.nativeTilt(tilt);
        }
        NATIVE_STEPS.incrementAndGet();
        final int rc = NativeVm.run(p.nativeHandle, from, to,
                cloud.rawX(), cloud.rawY(), cloud.rawZ(),
                cloud.rawVx(), cloud.rawVy(), cloud.rawVz(),
                cloud.rawEnergy(),
                cloud.rawAnchorDx(), cloud.rawAnchorDy(), cloud.rawAnchorDz(),
                cloud.nativeShapeKind(), cloud.count, cloud.nativeShapePrep(),
                sp.radius(), sp.length(), sp.height(), sp.turns(),
                sp.frequency(), sp.radiusPulse(), sp.radiusWave(), sp.radiusSpeed(),
                tilted ? 1 : 0, tilt,
                cloud.originX, cloud.originY, cloud.originZ,
                cloud.castSeed, cloud.ageSteps(), time, randSeq, p.fv, p.hv);
        if (rc < 0)
        {
            throw nativeError(p, rc);
        }
        return rc;
    }

    /** 原生错误码 {@code -(ip*8+kind)} → 与 RegVm 同措辞的 ScriptException */
    private static ScriptException nativeError(Prepared p, int rc)
    {
        final int code = -rc;
        final int ip = code >>> 3;
        final int kind = code & 7;
        final String msg = switch (kind)
        {
            case 1 -> "除以零";
            case 2 -> "取模的除数为零";
            case 3 -> "tangent / radial 的第三个参数（轴向）不能是零向量";
            case 4 -> "未知指令";
            default -> "这条指令原生内核还不支持";
        };
        final int at = p.pos != null && ip >= 0 && ip < p.pos.length ? p.pos[ip] : 0;
        return new ScriptException(msg, at >>> 12, at & 0xFFF);
    }

    /** 收尾：把写过的云级变量搬回 Env，再统一写回云上 */
    void completeStep(Step step, SpellCloud cloud)
    {
        int li = 0;
        for (Object seg : segments)
        {
            if (seg instanceof Loop L)
            {
                Prepared p = step.prepared[li++];
                for (Free f : L.frees)
                {
                    if (f.written)
                    {
                        step.root.define(f.name, valueOf(p.fv, f));
                    }
                }
            }
        }
        Interpreter.saveRoot(cloud, step.root);
    }

    /** 从 fv 里取出一个自由变量的值 */
    private static Value valueOf(double[] fv, Free f)
    {
        if (f.type == T.BOOL)
        {
            return fv[f.offset] != 0.0 ? Value.TRUE : Value.FALSE;
        }
        if (f.type == T.VEC3)
        {
            return Value.vec(fv[f.offset], fv[f.offset + 1], fv[f.offset + 2]);
        }
        return Value.num(fv[f.offset]);
    }

    /**
     * 冷路径：校验并搬自由变量、算外提量。失败返回 null（调用方退回解释器）并写明原因。
     */
    private static Prepared prepare(Loop L, SpellCloud cloud, Env root, String[] why)
    {
        Prepared p = new Prepared();
        p.code = L.code;
        p.pos = L.pos;
        p.consts = L.consts;
        p.frameSize = L.frameSize;
        scanNative(L);
        p.nativeHandle = nativeHandleFor(L);

        p.fv = new double[L.freeSlots];
        for (Free f : L.frees)
        {
            if (!store(p.fv, f, root.get(f.name)))
            {
                why[0] = "自由变量 " + f.name + " 运行期的类型搬不动（bool / element）";
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
                why[0] = "外提量求值失败：" + e.getMessage();
                return null;
            }
            if (h.type == T.NUM)
            {
                if (!(v instanceof Value.Num n))
                {
                    why[0] = "外提量运行期不是 num";
                    return null;
                }
                p.hv[h.slot] = n.v();
            }
            else if (h.type == T.BOOL)
            {
                // 守门量（顶层 if 的条件）：进 hv 的时候按 0/1 存
                if (!(v instanceof Value.Bool b))
                {
                    why[0] = "守门条件运行期不是 bool";
                    return null;
                }
                p.hv[h.slot] = b.v() ? 1.0 : 0.0;
            }
            else
            {
                if (!(v instanceof Value.Vec3 x))
                {
                    why[0] = "外提量运行期不是 vec3";
                    return null;
                }
                p.hv[h.slot] = x.x();
                p.hv[h.slot + 1] = x.y();
                p.hv[h.slot + 2] = x.z();
            }
        }
        p.lanes = RegVm4.supported(p.code);
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
        if (f.type == T.BOOL)
        {
            if (!(v instanceof Value.Bool b))
            {
                return false;
            }
            fv[f.offset] = b.v() ? 1.0 : 0.0;
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

    private static Loop buildLoop(Ast.ForParticles loop, Scope top, java.util.Set<String> writable)
    {
        return buildLoop(loop, top, null, -1, false, writable);
    }

    /**
     * 编一个粒子循环；{@code guard != null} 时先在循环开头判一次守门条件。
     *
     * <p>守门量自己占一个 {@code hv} 槽，<b>必须在编循环体之前占</b>——循环体里的外提量
     * 是按 {@code hoistSlots} 递增分槽的，晚占就会撞槽。</p>
     */
    private static Loop buildLoop(Ast.ForParticles loop, Scope top, Ast.Expr guard,
                                  int guardOrdinal, boolean negate, java.util.Set<String> writable)
    {
        Loop L = new Loop();
        if (guard != null)
        {
            Hoist g = new Hoist();
            g.expr = guard;
            g.type = T.BOOL;
            g.kind = Hoist.KIND_GUARD;
            g.guardOrdinal = guardOrdinal;
            g.slot = L.hoistSlots;
            L.hoistSlots += 1;
            L.hoists.add(g);
            L.guardSlot = g.slot;
            L.guardNegate = negate;
        }
        new Codegen(L, top, writable).compileBody(loop.body(), loop.var());
        return L;
    }

    /**
     * 顶层 {@code if} 展开成"带守卫的循环"清单。
     *
     * <p>支持三种形状：</p>
     * <pre>
     * if (c) { for p in particles { ... } }
     * if (c) { for p ... } else { for p ... }
     * if (c) { } else { for p ... }
     * </pre>
     *
     * <p>别的一律返回 {@code null}（退回解释器）：宁可慢，也不要猜语义。</p>
     */
    private static List<Guarded> guardPlan(Ast.If branch)
    {
        Ast.ForParticles then = singleLoop(branch.then());
        Ast.ForParticles other = singleLoop(branch.otherwise());
        if (then != null)
        {
            List<Guarded> out = new ArrayList<>(2);
            out.add(new Guarded(then, false));
            if (other != null)
            {
                out.add(new Guarded(other, true));
            }
            else if (!branch.otherwise().isEmpty())
            {
                return null;
            }
            return out;
        }
        if (branch.then().isEmpty() && other != null)
        {
            return List.of(new Guarded(other, true));
        }
        return null;
    }

    /** 一条"带守卫的粒子循环"：循环体 + 条件为假时才跑 */
    private record Guarded(Ast.ForParticles loop, boolean negate)
    {
    }

    /** 语句序列是不是"正好一条粒子循环" */
    private static Ast.ForParticles singleLoop(List<Ast.Stmt> body)
    {
        return body.size() == 1 && body.get(0) instanceof Ast.ForParticles loop ? loop : null;
    }

    /**
     * 顶层所有"可守门 if"的条件，按出现顺序。
     *
     * <p>编译与读盘走同一个走法：守门量的序号就是它在这个列表里的下标。</p>
     */
    private static List<Ast.Expr> guardConditions(List<Ast.Stmt> tick)
    {
        List<Ast.Expr> out = new ArrayList<>();
        for (Ast.Stmt s : tick)
        {
            if (s instanceof Ast.If branch && guardPlan(branch) != null)
            {
                out.add(branch.cond());
            }
        }
        return out;
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
                    throw new Unsupported("顶层变量改类型");
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
        if (e instanceof Ast.StrLit)
        {
            // 字符串只出现在事件体里（hit.block 的比较），寄存器机根本不跑那些代码
            throw new Unsupported("字符串只能用在 on hit / on expire 里（快速路径里没有字符串）");
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
            throw new Unsupported("变量没有可推断的类型");
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
                throw new Unsupported("vec3 只有 .x / .y / .z");
            }
            if (base == T.PARTICLE)
            {
                return particlePropType(m.name());
            }
            throw new Unsupported("这个成员访问快速路径不支持");
        }
        if (e instanceof Ast.Unary u)
        {
            T t = typeOf(u.e(), sc);
            if (u.op().equals("not"))
            {
                if (t != T.BOOL)
                {
                    throw new Unsupported("not 只接受 bool");
                }
                return T.BOOL;
            }
            if (t == T.NUM || t == T.VEC3)
            {
                return t;
            }
            throw new Unsupported("负号只支持 num 或 vec3");
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
                        throw new Unsupported("and / or 两侧必须是 bool");
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
                        throw new Unsupported("比较在快速路径里只支持 num（vec3 / element 比较会退回解释器）");
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
                    throw new Unsupported("+ / - 只支持 num+num 或 vec3+vec3");
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
                    throw new Unsupported("* 只支持 num*num、vec3*num、num*vec3");
                case "/":
                    if (a == T.NUM && c == T.NUM)
                    {
                        return T.NUM;
                    }
                    if (a == T.VEC3 && c == T.NUM)
                    {
                        return T.VEC3;
                    }
                    throw new Unsupported("/ 只支持 num/num 或 vec3/num");
                case "%":
                    if (a == T.NUM && c == T.NUM)
                    {
                        return T.NUM;
                    }
                    throw new Unsupported("% 只支持 num % num");
                default:
                    throw new Unsupported("未知运算符");
            }
        }
        if (e instanceof Ast.Call c)
        {
            return callType(c, sc);
        }
        throw new Unsupported("这个表达式快速路径不支持");
    }

    private static T callType(Ast.Call c, Scope sc)
    {
        String fn = c.fn();
        List<Ast.Expr> args = c.args();
        switch (fn)
        {
            case "abs":
            case "sqrt":
            case "sign":
            case "sin":
            case "cos":
            case "exp":
            case "floor":
            case "round":
                need(args, 1);
                needNum(args.get(0), sc);
                return T.NUM;
            case "rand":
                // 序列是按粒子顺序消耗的，所以带它的循环体不能切段（见 parallelSafe()）
                need(args, 0);
                return T.NUM;
            case "min":
            case "max":
            case "pow":
            case "atan2":
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
                throw new Unsupported("lerp 只支持 num 或 vec3");
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
                // 2 参绕世界 Y，3 参绕任意轴
                if (args.size() != 2 && args.size() != 3)
                {
                    throw new Unsupported("tangent / radial 需要 2 或 3 个参数");
                }
                for (Ast.Expr a : args)
                {
                    needVec(a, sc);
                }
                return T.VEC3;
            case "rotate":
                need(args, 3);
                needVec(args.get(0), sc);
                needVec(args.get(1), sc);
                needNum(args.get(2), sc);
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
                    throw new Unsupported("gravity() / volatility() 的参数必须是元素字面量，例如 gravity(fire)");
                }
                return T.NUM;
            default:
                throw new Unsupported("快速路径不支持这个函数");
        }
    }

    private static void need(List<Ast.Expr> args, int n)
    {
        if (args.size() != n)
        {
            throw new Unsupported("内建函数的实参个数不对");
        }
    }

    private static void needNum(Ast.Expr e, Scope sc)
    {
        if (typeOf(e, sc) != T.NUM)
        {
            throw new Unsupported("这里需要 num");
        }
    }

    private static void needVec(Ast.Expr e, Scope sc)
    {
        if (typeOf(e, sc) != T.VEC3)
        {
            throw new Unsupported("这里需要 vec3");
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

    /** 子表达式的结构键：只由"运算符/名字/字面量位模式"决定，编译期与读盘时一致 */
    private static String exprKey(Ast.Expr e)
    {
        if (e instanceof Ast.NumLit n)
        {
            return "n" + Double.doubleToRawLongBits(n.v());
        }
        if (e instanceof Ast.BoolLit b)
        {
            return b.v() ? "t" : "f";
        }
        if (e instanceof Ast.Var v)
        {
            return "v" + v.name();
        }
        if (e instanceof Ast.Member m)
        {
            return "m" + m.name() + "(" + exprKey(m.base()) + ")";
        }
        if (e instanceof Ast.Unary u)
        {
            return "u" + u.op() + "(" + exprKey(u.e()) + ")";
        }
        if (e instanceof Ast.Binary b)
        {
            return "b" + b.op() + "(" + exprKey(b.a()) + "," + exprKey(b.b()) + ")";
        }
        if (e instanceof Ast.Call c)
        {
            StringBuilder sb = new StringBuilder("c").append(c.fn()).append('(');
            for (Ast.Expr arg : c.args())
            {
                sb.append(exprKey(arg)).append(',');
            }
            return sb.append(')').toString();
        }
        return "?";
    }

    /** 读盘用：在语法树里按结构键找回子表达式 */
    private static Ast.Expr findExpr(List<Ast.Stmt> body, String key)
    {
        for (Ast.Stmt s : body)
        {
            Ast.Expr e = findExpr(s, key);
            if (e != null)
            {
                return e;
            }
        }
        return null;
    }

    private static Ast.Expr findExpr(Ast.Stmt s, String key)
    {
        if (s instanceof Ast.Let let)
        {
            return findExpr(let.value(), key);
        }
        if (s instanceof Ast.Assign a)
        {
            return findExpr(a.value(), key);
        }
        if (s instanceof Ast.If branch)
        {
            Ast.Expr e = findExpr(branch.cond(), key);
            if (e != null)
            {
                return e;
            }
            e = findExpr(branch.then(), key);
            return e != null ? e : findExpr(branch.otherwise(), key);
        }
        if (s instanceof Ast.ExprStmt es)
        {
            return findExpr(es.expr(), key);
        }
        if (s instanceof Ast.ForParticles loop)
        {
            return findExpr(loop.body(), key);
        }
        return null;
    }

    private static Ast.Expr findExpr(Ast.Expr e, String key)
    {
        if (exprKey(e).equals(key))
        {
            return e;
        }
        if (e instanceof Ast.Member m)
        {
            return findExpr(m.base(), key);
        }
        if (e instanceof Ast.Unary u)
        {
            return findExpr(u.e(), key);
        }
        if (e instanceof Ast.Binary b)
        {
            Ast.Expr x = findExpr(b.a(), key);
            return x != null ? x : findExpr(b.b(), key);
        }
        if (e instanceof Ast.Call c)
        {
            for (Ast.Expr arg : c.args())
            {
                Ast.Expr x = findExpr(arg, key);
                if (x != null)
                {
                    return x;
                }
            }
        }
        return null;
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
                throw new Unsupported("不认识的粒子属性");
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
        /** cast 里声明过的变量名：只有它们可写（内置的 dt/time/… 只读） */
        private final java.util.Set<String> writable;
        /** 循环体里每个 let 的编号（落盘后再读回时用来把外提量挂回语法树） */
        private final Map<Ast.Let, Integer> letOrdinals = new IdentityHashMap<>();
        /** 水位：下一个空闲寄存器 */
        private int nextReg;
        /** 用过的最高水位，决定帧大小 */
        private int maxReg;
        /** 在 if 分支里的语句不参与外提（避免把可能抛错/不该执行的计算提前） */
        private int ifDepth;

        // ---- 粒子帧：pos / v / anchor 在循环开头各加载一次，body 内直接引用 ----
        /** pos 的固定槽（-1 = 不缓存；每次读都发 LPOS） */
        private int framePos = -1;
        private int frameVel = -1;
        private int frameAnchor = -1;
        /** 只读自由变量（dt/time/life…）的固定槽：整步不变，循环开头从 fv 搬一次 */
        private final Map<String, Integer> freeFrame = new HashMap<>();
        /** 循环不变子表达式（LICM）：结构键 → hv 槽 */
        private final Map<String, Integer> hoistKeys = new HashMap<>();
        /** 循环体里被赋过值的自由变量名：这些名字不能参与外提（值在 body 内会变） */
        private java.util.Set<String> writtenVars = java.util.Set.of();
        /**
         * 循环体里（含 if 分支内）被赋值过的名字。
         *
         * <p>外提判据要连这个一起看：{@code let k = 1.0} 的初值虽然循环不变，但只要体内
         * 还有 {@code k = ...}，外提之后那句就变成"给外提量赋值" ⇒ 整个 tick 抛
         * Unsupported ⇒ **整段退化到解释器**（实测 ~196 ns/粒 vs 快路径 ~15 ns/粒，13 倍）。
         * 这是"写法看起来完全正常却掉 20 倍"的悬崖，所以不外提这类 let。</p>
         */
        private java.util.Set<String> assignedInBody = java.util.Set.of();

        Codegen(Loop loop, Scope top, java.util.Set<String> writable)
        {
            this.loop = loop;
            this.top = top;
            this.writable = writable == null ? java.util.Set.of() : writable;
        }

        void compileBody(List<Ast.Stmt> body, String varName)
        {
            List<Ast.Let> lets = collectLets(body);
            for (int i = 0; i < lets.size(); i++)
            {
                letOrdinals.put(lets.get(i), i);
            }
            // 体内被赋过值的名字：其中的 let 一律不外提（外提会让那句赋值直接抛 Unsupported，
            // 整段掉进解释器）
            java.util.Set<String> assigned = new java.util.LinkedHashSet<>();
            collectAssigned(body, assigned);
            assignedInBody = assigned;
            Scope sc = new Scope(top, null);
            sc.declare(varName, T.PARTICLE, -1);
            Usage use = analyze(body, varName);
            // 顶层 if 的守卫：每步算过一次（在 hv 里），这里每条粒子判一下再进循环体
            int guardSkip = -1;
            if (loop.guardSlot >= 0)
            {
                int r = alloc(T.BOOL);
                emit2(OP_LDH1, r, loop.guardSlot);
                emit2(loop.guardNegate ? OP_JNZ : OP_JZ, r, 0);
                guardSkip = code.add(-1);
                // 守卫寄存器用完即弃：循环体从 0 号槽重新开始分
                nextReg = 0;
            }

            // 粒子帧：读两次以上的属性、以及会被写的属性，装一次到固定槽。
            // 必须放在守卫之后——守卫为假时连加载都跳过。
            // p.local 的语义是"当前位置 - 原点"：如果 pos 会被写，帧里延迟写回会让 local
            // 读到旧值，这种组合直接退回逐次读写（语义优先，不猜）。
            boolean posFrameOk = !(use.localRead > 0 && use.posWrite);
            if (posFrameOk && (use.posWrite || use.posReads >= 2))
            {
                framePos = alloc(T.VEC3);
                emit1(OP_LPOS, framePos);
            }
            if (use.velWrite || use.velReads >= 2)
            {
                frameVel = alloc(T.VEC3);
                emit1(OP_LVEL, frameVel);
            }
            if (use.anchorReads >= 2)
            {
                frameAnchor = alloc(T.VEC3);
                emit1(OP_LANCHOR, frameAnchor);
            }
            // 只读自由变量：整步不变，循环开头从 fv 搬进寄存器，body 里直接引用
            preloadFrees(body);

            for (Ast.Stmt s : body)
            {
                stmt(s, sc);
            }
            // 统一写回：粒子循环体内部没有观察者（世界查询 / hurt / emit 都被禁止），
            // 所以"整段末尾写一次"与"每次赋值立即写"对下一步的可见状态完全等价。
            if (framePos >= 0 && use.posWrite)
            {
                emit1(OP_SPOS, framePos);
            }
            if (frameVel >= 0 && use.velWrite)
            {
                emit1(OP_SVEL, frameVel);
            }
            if (guardSkip >= 0)
            {
                code.set(guardSkip, code.size());
            }
            loop.code = code.toArray();
            loop.pos = code.positions();
            loop.consts = consts.toArray();
            loop.frameSize = Math.max(maxReg + 4, 16);
            if (System.getProperty("wizardmagic.script.debug") != null)
            {
                System.err.println("[script] 循环体：寄存器 " + maxReg + "，指令流 " + code.size()
                        + " 个 int，外提 " + loop.hoists.size() + "，自由变量 " + loop.frees.size());
            }
        }

        // ------------------------------------------------ 粒子属性使用统计

        /** 循环体对粒子属性的读写次数（只用来决定要不要建帧） */
        private static final class Usage
        {
            int posReads;
            int velReads;
            int anchorReads;
            int localRead;
            boolean posWrite;
            boolean velWrite;
        }

        private static Usage analyze(List<Ast.Stmt> body, String varName)
        {
            Usage u = new Usage();
            for (Ast.Stmt s : body)
            {
                scanStmt(s, varName, u);
            }
            return u;
        }

        private static void scanStmt(Ast.Stmt s, String varName, Usage u)
        {
            if (s instanceof Ast.Let let)
            {
                scanExpr(let.value(), varName, u);
                return;
            }
            if (s instanceof Ast.Assign a)
            {
                if (a.base().equals(varName) && !a.path().isEmpty())
                {
                    String prop = a.path().get(0);
                    if (prop.equals("pos"))
                    {
                        u.posWrite = true;
                    }
                    else if (prop.equals("v"))
                    {
                        u.velWrite = true;
                    }
                }
                scanExpr(a.value(), varName, u);
                return;
            }
            if (s instanceof Ast.If branch)
            {
                scanExpr(branch.cond(), varName, u);
                for (Ast.Stmt x : branch.then())
                {
                    scanStmt(x, varName, u);
                }
                for (Ast.Stmt x : branch.otherwise())
                {
                    scanStmt(x, varName, u);
                }
                return;
            }
            if (s instanceof Ast.ExprStmt es)
            {
                scanExpr(es.expr(), varName, u);
            }
        }

        private static void scanExpr(Ast.Expr e, String varName, Usage u)
        {
            if (e instanceof Ast.Member m)
            {
                // 只统计最内层粒子属性：p.pos.x 只算一次 pos 读
                if (m.base() instanceof Ast.Var v && v.name().equals(varName))
                {
                    switch (m.name())
                    {
                        case "pos": u.posReads++; break;
                        case "v": u.velReads++; break;
                        case "anchor": u.anchorReads++; break;
                        case "local": u.localRead++; break;
                        default: break;
                    }
                    return;
                }
                scanExpr(m.base(), varName, u);
                return;
            }
            if (e instanceof Ast.Call c)
            {
                for (Ast.Expr arg : c.args())
                {
                    scanExpr(arg, varName, u);
                }
                return;
            }
            if (e instanceof Ast.Unary un)
            {
                scanExpr(un.e(), varName, u);
                return;
            }
            if (e instanceof Ast.Binary b)
            {
                scanExpr(b.a(), varName, u);
                scanExpr(b.b(), varName, u);
                return;
            }
            if (e instanceof Ast.RecordLit r)
            {
                for (Ast.Expr v : r.fields().values())
                {
                    scanExpr(v, varName, u);
                }
            }
        }

        /** 这个属性当前有没有帧寄存器；没有返回 -1 */
        private int framedProp(String prop)
        {
            return switch (prop)
            {
                case "pos" -> framePos;
                case "v" -> frameVel;
                case "anchor" -> frameAnchor;
                default -> -1;
            };
        }

        /**
         * 把循环体里用到的、整步不变的只读自由变量搬进固定寄存器。
         *
         * <p>{@code dt / time / life / step} 这些在一步之内对每颗粒子都是同一个值，
         * 但原来的代码每粒都发一条 {@code LDF}。这里在循环开头各装一次，body 里直接引用。
         * 会被赋值的名字（云级变量）不搬——它们的值在 body 内会变，必须走 fv。</p>
         */
        private void preloadFrees(List<Ast.Stmt> body)
        {
            java.util.Set<String> used = new java.util.LinkedHashSet<>();
            java.util.Set<String> declared = new java.util.LinkedHashSet<>();
            java.util.Set<String> assigned = new java.util.LinkedHashSet<>();
            for (Ast.Stmt s : body)
            {
                collectVarNames(s, used, declared, assigned);
            }
            writtenVars = assigned;
            for (String name : used)
            {
                if (declared.contains(name) || assigned.contains(name))
                {
                    continue;
                }
                T t = top.type(name);
                // bool / element 没有 fv 表示；只在 num / vec3 上做
                if (t != T.NUM && t != T.VEC3)
                {
                    continue;
                }
                Free f = free(name, t);
                int reg = alloc(t);
                emit2(t == T.VEC3 ? OP_LDF3 : OP_LDF1, reg, f.offset);
                freeFrame.put(name, reg);
            }
        }

        private static void collectVarNames(Ast.Stmt s, java.util.Set<String> used,
                                            java.util.Set<String> declared,
                                            java.util.Set<String> assigned)
        {
            if (s instanceof Ast.Let let)
            {
                declared.add(let.name());
                collectExprNames(let.value(), used);
                return;
            }
            if (s instanceof Ast.Assign a)
            {
                if (a.path().isEmpty())
                {
                    assigned.add(a.base());
                }
                collectExprNames(a.value(), used);
                return;
            }
            if (s instanceof Ast.If branch)
            {
                collectExprNames(branch.cond(), used);
                for (Ast.Stmt x : branch.then())
                {
                    collectVarNames(x, used, declared, assigned);
                }
                for (Ast.Stmt x : branch.otherwise())
                {
                    collectVarNames(x, used, declared, assigned);
                }
                return;
            }
            if (s instanceof Ast.ExprStmt es)
            {
                collectExprNames(es.expr(), used);
            }
        }

        private static void collectExprNames(Ast.Expr e, java.util.Set<String> used)
        {
            if (e instanceof Ast.Var v)
            {
                used.add(v.name());
                return;
            }
            if (e instanceof Ast.Member m)
            {
                collectExprNames(m.base(), used);
                return;
            }
            if (e instanceof Ast.Call c)
            {
                for (Ast.Expr arg : c.args())
                {
                    collectExprNames(arg, used);
                }
                return;
            }
            if (e instanceof Ast.Unary u)
            {
                collectExprNames(u.e(), used);
                return;
            }
            if (e instanceof Ast.Binary b)
            {
                collectExprNames(b.a(), used);
                collectExprNames(b.b(), used);
                return;
            }
            if (e instanceof Ast.RecordLit r)
            {
                for (Ast.Expr v : r.fields().values())
                {
                    collectExprNames(v, used);
                }
            }
        }

        /**
         * 表达式是否已经"现成"在某个寄存器里：粒子帧、let 槽位、vec3 分量。
         *
         * <p>返回 -1 表示必须走 {@link #expr} 现算。调用方（二元运算、函数实参）
         * 可以直接把返回的槽当成源操作数，省掉一次 MOV。</p>
         */
        private int preloaded(Ast.Expr e, Scope sc)
        {
            if (e instanceof Ast.Var v)
            {
                Integer frame = freeFrame.get(v.name());
                if (frame != null)
                {
                    return frame;
                }
                Integer slot = sc.slot(v.name());
                return slot == null ? -1 : slot;
            }
            if (e instanceof Ast.Member m)
            {
                if (m.base() instanceof Ast.Var v && typeOf(v, sc) == T.PARTICLE)
                {
                    return framedProp(m.name());
                }
                if (typeOf(m.base(), sc) == T.VEC3)
                {
                    int base = preloaded(m.base(), sc);
                    int k = component(m.name());
                    return base >= 0 && k >= 0 ? base + k : -1;
                }
            }
            return -1;
        }

        /** 取一个操作数：能直取就直取，否则分配临时槽并求值 */
        private int operand(Ast.Expr e, Scope sc)
        {
            int pre = preloaded(e, sc);
            if (pre >= 0)
            {
                return pre;
            }
            int s = alloc(typeOf(e, sc));
            expr(e, sc, s);
            return s;
        }

        /** 这个表达式能不能外提到"每步一次"（与粒子无关、不会抛错、不读 body 内会改的变量） */
        private boolean hoistable(Ast.Expr e, Scope sc)
        {
            boolean compound = e instanceof Ast.Binary || e instanceof Ast.Unary
                    || (e instanceof Ast.Call c && !c.fn().equals("rand")
                        && !c.fn().equals("gravity") && !c.fn().equals("volatility"));
            if (!compound || !independent(e, sc) || !safeToHoist(e) || mentionsWritten(e))
            {
                return false;
            }
            T t = typeOf(e, sc);
            return t == T.NUM || t == T.BOOL || t == T.VEC3;
        }

        /** 除法/取模会抛错：提出循环会改变"什么情况下报错"，所以不外提 */
        private static boolean safeToHoist(Ast.Expr e)
        {
            if (e instanceof Ast.Binary b)
            {
                if (b.op().equals("/") || b.op().equals("%"))
                {
                    return false;
                }
                return safeToHoist(b.a()) && safeToHoist(b.b());
            }
            if (e instanceof Ast.Unary u)
            {
                return safeToHoist(u.e());
            }
            if (e instanceof Ast.Member m)
            {
                return safeToHoist(m.base());
            }
            if (e instanceof Ast.Call c)
            {
                for (Ast.Expr arg : c.args())
                {
                    if (!safeToHoist(arg))
                    {
                        return false;
                    }
                }
            }
            return true;
        }

        /** 表达式里是否引用了循环体内会被写的变量 */
        private boolean mentionsWritten(Ast.Expr e)
        {
            if (writtenVars.isEmpty())
            {
                return false;
            }
            if (e instanceof Ast.Var v)
            {
                return writtenVars.contains(v.name());
            }
            if (e instanceof Ast.Member m)
            {
                return mentionsWritten(m.base());
            }
            if (e instanceof Ast.Unary u)
            {
                return mentionsWritten(u.e());
            }
            if (e instanceof Ast.Binary b)
            {
                return mentionsWritten(b.a()) || mentionsWritten(b.b());
            }
            if (e instanceof Ast.Call c)
            {
                for (Ast.Expr arg : c.args())
                {
                    if (mentionsWritten(arg))
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 取（或新建）一个外提槽；返回 -1 = 槽位不够，放弃外提 */
        private int hoistExpr(Ast.Expr e, Scope sc)
        {
            String key = exprKey(e);
            Integer old = hoistKeys.get(key);
            if (old != null)
            {
                return old;
            }
            T t = typeOf(e, sc);
            int w = width(t);
            if (loop.hoistSlots + w > MAX_REGS)
            {
                return -1;
            }
            Hoist h = new Hoist();
            h.kind = Hoist.KIND_EXPR;
            h.key = key;
            h.slot = loop.hoistSlots;
            loop.hoistSlots += w;
            h.expr = e;
            h.type = t;
            h.ordinal = -1;
            loop.hoists.add(h);
            hoistKeys.put(key, h.slot);
            return h.slot;
        }

        /** 表达式里是否读了粒子属性 {@code prop}（含 p.prop.x） */
        private static boolean readsParticleProp(Ast.Expr e, String prop, Scope sc)
        {
            if (e instanceof Ast.Member m)
            {
                if (m.name().equals(prop) && m.base() instanceof Ast.Var v
                        && sc.type(v.name()) == T.PARTICLE)
                {
                    return true;
                }
                return readsParticleProp(m.base(), prop, sc);
            }
            if (e instanceof Ast.Binary b)
            {
                return readsParticleProp(b.a(), prop, sc) || readsParticleProp(b.b(), prop, sc);
            }
            if (e instanceof Ast.Unary u)
            {
                return readsParticleProp(u.e(), prop, sc);
            }
            if (e instanceof Ast.Call c)
            {
                for (Ast.Expr arg : c.args())
                {
                    if (readsParticleProp(arg, prop, sc))
                    {
                        return true;
                    }
                }
                return false;
            }
            if (e instanceof Ast.RecordLit r)
            {
                for (Ast.Expr v : r.fields().values())
                {
                    if (readsParticleProp(v, prop, sc))
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        // ------------------------------------------------ 语句

        private void stmt(Ast.Stmt s, Scope sc)
        {
            // 之后的指令都记成这条语句的位置：运行期报错（除零等）才指得到行
            code.at(s.line(), s.col());
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
            throw new Unsupported("循环体里只支持 let / 赋值 / if / 表达式");
        }

        private void let(Ast.Let let, Scope sc)
        {
            T t = typeOf(let.value(), sc);
            if (t != T.NUM && t != T.BOOL && t != T.VEC3)
            {
                throw new Unsupported("循环体里的 let 只能是 num / bool / vec3");
            }
            if (ifDepth == 0 && t != T.BOOL && independent(let.value(), sc)
                    && !assignedInBody.contains(let.name()))
            {
                // 与粒子无关：每步算一次，循环里只读
                Hoist h = new Hoist();
                h.slot = loop.hoistSlots;
                loop.hoistSlots += width(t);
                if (loop.hoistSlots > MAX_REGS)
                {
                    throw new Unsupported("外提量太多（超过 256 个槽）");
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
                throw new Unsupported("if 的条件必须是 bool");
            }
            int base = nextReg;
            int cond = alloc(T.BOOL);
            expr(branch.cond(), sc, cond);
            // 先试 if-conversion（两条分支都是"给同一个 let 赋纯表达式"时改成 SELECT）。
            // 保住 cond 槽（nextReg 停在 cond+1），否则 saved/thenVal 会盖掉它。
            nextReg = cond + 1;
            if (tryIfConvert(branch, sc, cond))
            {
                nextReg = base;
                return;
            }
            nextReg = base;
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

        /**
         * if-conversion：把
         * <pre>
         *   if (c) { x = A } else { x = B }        // A/B 都是纯表达式，x 是同一个 let
         * </pre>
         * 改写成"两路都算，再 SELECT 挑一个"，指令流里不再有 JZ。
         *
         * <p><b>为什么要这一步</b>：向量内核撞上"组内分歧"的 JZ 时两条路都要跑，实测掉到
         * <b>0.34×</b>；而同一份程序用 SELECT 表达时，即使条件逐粒子相反也有 <b>1.22×</b>
         * （见 {@code native/wmvm/selftest} 的 §9-11）。所以"含分支 ⇒ 跑标量"之外还有第三条路：
         * 把可 if-conversion 的分支编译成 SELECT，让分歧型脚本也能吃到向量收益。</p>
         *
         * <p><b>保守判据</b>（任一不满足就退回 JZ，行为不变）：</p>
         * <ul>
         *   <li>两条分支各只有**一条**赋值语句，目标是同一个**纯寄存器**（{@code let}），不是
         *       {@code p.v} 这类属性链，也不是新声明的 let；</li>
         *   <li>右值是**纯表达式**：不含 {@code /}、{@code %}（除零会抛错，两路都算会凭空报错）、
         *       {@code rand}（两路都算会把随机序列吃掉）、{@code tangent}/{@code radial}
         *       （退化轴向会抛错）；</li>
         *   <li>两边类型一致且是 num/vec3；</li>
         *   <li>没有 {@code else} 也可以（else 值 = 进入 if 之前的旧值）。</li>
         * </ul>
         *
         * @return true = 已经按 SELECT 发完指令（调用方不要再发 JZ/JMP）
         */
        private boolean tryIfConvert(Ast.If branch, Scope sc, int cond)
        {
            if (!IFCONV)
            {
                return false;
            }
            String why = null;
            Ast.Assign thenA = soleAssign(branch.then());
            if (thenA == null || !thenA.path().isEmpty() || !thenA.op().isEmpty())
            {
                why = "then 体不是单条 let 赋值（size=" + branch.then().size() + "，首节点="
                        + (branch.then().isEmpty() ? "-" : branch.then().get(0).getClass().getSimpleName())
                        + (thenA != null ? "，op=" + thenA.op() + "，path=" + thenA.path() : "") + "）";
            }
            Ast.Assign elseA = null;
            if (why == null && !branch.otherwise().isEmpty())
            {
                elseA = soleAssign(branch.otherwise());
                if (elseA == null || !elseA.path().isEmpty() || !elseA.op().isEmpty()
                        || !elseA.base().equals(thenA.base()))
                {
                    why = "else 体形态不匹配";
                }
            }
            Integer slot = why == null ? sc.slot(thenA.base()) : null;
            if (why == null && slot == null)
            {
                why = "目标 " + thenA.base() + " 不是本作用域的纯寄存器";
            }
            T t = why == null ? typeOf(thenA.value(), sc) : T.BOOL;
            if (why == null && t != T.NUM && t != T.VEC3)
            {
                why = "类型不是 num/vec3: " + t;
            }
            if (why == null && sc.type(thenA.base()) != t)
            {
                // 需要隐式转换时不做（两路写法可能不一致）
                why = "目标类型 " + sc.type(thenA.base()) + " 与右值 " + t + " 不一致";
            }
            if (why == null && !pure(thenA.value()))
            {
                why = "then 右值不纯（含 / % rand tangent radial 等）";
            }
            if (why == null && elseA != null && (typeOf(elseA.value(), sc) != t || !pure(elseA.value())))
            {
                why = "else 右值类型不符或不纯";
            }
            if (why != null)
            {
                if (IFCONV_DEBUG)
                {
                    System.err.println("[ifconv] 跳过（第 " + branch.line() + " 行）：" + why);
                }
                return false;
            }
            final int w = width(t);
            final int dst = slot;
            final int saved = alloc(t);      // 进 if 之前的旧值
            final int thenVal = alloc(t);    // then 路的结果
            copyReg(w, saved, dst);
            int bodyBase = nextReg;
            ifDepth++;
            stmt(thenA, new Scope(sc, null));
            ifDepth--;
            copyReg(w, thenVal, dst);
            nextReg = bodyBase;
            copyReg(w, dst, saved);          // 先恢复成旧值，给 else 当输入
            if (elseA != null)
            {
                ifDepth++;
                stmt(elseA, new Scope(sc, null));
                ifDepth--;
                nextReg = bodyBase;
            }
            // dst = cond ? thenVal : dst（vec3 是三个连续槽，逐个 SELECT）
            for (int c = 0; c < w; c++)
            {
                emit3(OP_SELECT, dst + c, cond, thenVal + c);
                code.add(dst + c);
            }
            return true;
        }

        /** 分支体恰好是一条赋值语句时返回它，否则 null */
        private static Ast.Assign soleAssign(java.util.List<Ast.Stmt> body)
        {
            if (body.size() != 1 || !(body.get(0) instanceof Ast.Assign a))
            {
                return null;
            }
            return a;
        }

        private void copyReg(int w, int dst, int src)
        {
            emit2(w == 3 ? OP_MOV3 : OP_MOV, dst, src);
        }

        /**
         * 纯表达式：可以两路都求值、不会抛错、不消耗随机序列。
         *
         * <p>排除 {@code /}、{@code %}（除零会在运行期把云标记 broken —— 两路都算等于凭空制造
         * 一个会被走到的错误）、{@code rand}（序列会多消耗）、{@code tangent}/{@code radial}
         * （轴向退化会抛错）。</p>
         */
        private boolean pure(Ast.Expr e)
        {
            if (e instanceof Ast.NumLit || e instanceof Ast.BoolLit || e instanceof Ast.Var)
            {
                return true;
            }
            if (e instanceof Ast.Member m)
            {
                return pure(m.base());
            }
            if (e instanceof Ast.Unary u)
            {
                return pure(u.e());
            }
            if (e instanceof Ast.Binary b)
            {
                if ("/".equals(b.op()) || "%".equals(b.op()))
                {
                    return false;
                }
                return pure(b.a()) && pure(b.b());
            }
            if (e instanceof Ast.Call c)
            {
                String fn = c.fn();
                if ("rand".equals(fn) || "tangent".equals(fn) || "radial".equals(fn))
                {
                    return false;
                }
                for (Ast.Expr arg : c.args())
                {
                    if (!pure(arg))
                    {
                        return false;
                    }
                }
                return true;
            }
            return false;   // 字符串 / 记录构造 等
        }

        private void assign(Ast.Assign a, Scope sc)        {
            T rhs = typeOf(a.value(), sc);

            if (a.path().isEmpty())
            {
                Integer slot = sc.slot(a.base());
                if (slot == null)
                {
                    // 不是本作用域里的寄存器 → 可能是外提量（只读）、内置量（只读）、
                    // 或者云级变量（可写：整段跑完后搬回 Env）
                    if (sc.hoistSlot(a.base()) != null)
                    {
                        throw new Unsupported("不能给外提量赋值（它在循环外每步只算一次）");
                    }
                    T cur = sc.type(a.base());
                    if (cur == null)
                    {
                        throw new Unsupported("这只变量没有可推断的类型");
                    }
                    if (!writable.contains(a.base()))
                    {
                        throw new Unsupported("不能给内置量 " + a.base()
                                + " 赋值（只有 cast 里声明的变量可写）");
                    }
                    if (cur != rhs)
                    {
                        throw new Unsupported("变量改类型");
                    }
                    int mark0 = nextReg;
                    int dst = alloc(rhs);
                    if (a.op().isEmpty())
                    {
                        expr(a.value(), sc, dst);
                    }
                    else
                    {
                        emitFree(a.base(), cur, dst);
                        int t = alloc(rhs);
                        expr(a.value(), sc, t);
                        binaryOp(a.op(), cur, rhs, dst, dst, t);
                    }
                    emitFreeStore(a.base(), cur, dst);
                    nextReg = mark0;
                    return;
                }
                T cur = sc.type(a.base());
                if (cur != rhs)
                {
                    throw new Unsupported("变量改类型");
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
                throw new Unsupported("只能给粒子属性赋值（p.xxx）");
            }
            if (a.path().size() != 1 && a.path().size() != 2)
            {
                throw new Unsupported("赋值路径太长");
            }

            int mark = nextReg;
            if (a.path().size() == 2)
            {
                // p.pos.x / p.v.y
                String prop = a.path().get(0);
                String comp = a.path().get(1);
                if (!(prop.equals("pos") || prop.equals("v")) || component(comp) < 0)
                {
                    throw new Unsupported("粒子只有 pos / v 能逐分量赋值");
                }
                if (rhs != T.NUM)
                {
                    throw new Unsupported("pos / v 的分量只能赋 num");
                }
                int k = component(comp);
                int framedBase = framedProp(prop);
                int dst = framedBase >= 0 ? framedBase + k : alloc(T.NUM);
                if (a.op().isEmpty())
                {
                    expr(a.value(), sc, dst);
                }
                else
                {
                    // 帧里已经是当前值，不用再从粒子读一次
                    if (framedBase < 0)
                    {
                        emitLoadComponent(prop, comp, dst);
                    }
                    int t = alloc(T.NUM);
                    expr(a.value(), sc, t);
                    binaryOp(a.op(), T.NUM, rhs, dst, dst, t);
                }
                if (framedBase < 0)
                {
                    emitStoreComponent(prop, comp, dst);
                }
                nextReg = mark;
                return;
            }

            // p.pos / p.v / p.energy
            String prop = a.path().get(0);
            T pt = particlePropType(prop);
            // 只有 pos / v 会建帧；anchor 只读，energy 是 float（帧里存 double 会绕过截断）
            int framed = prop.equals("pos") || prop.equals("v") ? framedProp(prop) : -1;
            int dst = framed >= 0 ? framed : alloc(pt);
            if (a.op().isEmpty())
            {
                if (pt != rhs)
                {
                    throw new Unsupported("给粒子属性赋的值类型不对");
                }
                // vec 字面量是"边求值边写 dst 的连续槽"；如果实参还读同一个帧，
                // 先算进临时槽再整体搬，避免覆盖源分量
                boolean aliasVec = framed >= 0 && pt == T.VEC3
                        && a.value() instanceof Ast.Call c
                        && c.fn().equals("vec")
                        && readsParticleProp(c, prop, sc);
                if (aliasVec)
                {
                    int tmp = alloc(pt);
                    expr(a.value(), sc, tmp);
                    emit2(OP_MOV3, dst, tmp);
                }
                else
                {
                    expr(a.value(), sc, dst);
                }
            }
            else
            {
                if (framed < 0)
                {
                    emitLoadProp(prop, dst);
                }
                int t = alloc(rhs);
                expr(a.value(), sc, t);
                binaryOp(a.op(), pt, rhs, dst, dst, t);
            }
            if (framed < 0)
            {
                switch (prop)
                {
                    case "pos": emit1(OP_SPOS, dst); break;
                    case "v": emit1(OP_SVEL, dst); break;
                    case "energy": emit1(OP_SENERGY, dst); break;
                    default: throw new Unsupported("不认识的粒子属性");
                }
            }
            nextReg = mark;
        }

        // ------------------------------------------------ 表达式（写进 dst）

        private void expr(Ast.Expr e, Scope sc, int dst)
        {
            // 已经在槽里的值（粒子帧 / let / vec3 分量）直接搬，或干脆不搬
            int pre = preloaded(e, sc);
            if (pre >= 0)
            {
                if (pre != dst)
                {
                    emit2(typeOf(e, sc) == T.VEC3 ? OP_MOV3 : OP_MOV, dst, pre);
                }
                return;
            }
            // 循环不变子表达式：每步算一次，粒子循环里只读 hv
            if (LICM && hoistable(e, sc))
            {
                int slot = hoistExpr(e, sc);
                if (slot >= 0)
                {
                    emit2(typeOf(e, sc) == T.VEC3 ? OP_LDH3 : OP_LDH1, dst, slot);
                    return;
                }
            }
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
                throw new Unsupported("变量不在任何作用域里");
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
                int a = operand(u.e(), sc);
                emit2(t == T.VEC3 ? OP_NEG_V : OP_NEG_N, dst, a);
                nextReg = mark;
                return;
            }
            if (e instanceof Ast.Binary b)
            {
                if (b.op().equals("and") || b.op().equals("or"))
                {
                    // 短路求值：左边定性了就跳过右边。
                    // 与解释器同一语义——d != 0 and 1/d > x 这种守卫写法不能炸。
                    int mark = nextReg;
                    expr(b.a(), sc, dst);
                    emit2(b.op().equals("and") ? OP_JZ : OP_JNZ, dst, 0);
                    int skip = code.add(-1);
                    expr(b.b(), sc, dst);
                    code.set(skip, code.size());
                    nextReg = mark;
                    return;
                }
                T a = typeOf(b.a(), sc);
                T c = typeOf(b.b(), sc);
                int mark = nextReg;
                int sa = operand(b.a(), sc);
                int sb = operand(b.b(), sc);
                binaryOp(b.op(), a, c, dst, sa, sb);
                nextReg = mark;
                return;
            }
            if (e instanceof Ast.Call c)
            {
                call(c, sc, dst);
                return;
            }
            throw new Unsupported("这个表达式快速路径不支持");
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
                throw new Unsupported("成员访问只支持 .x / .y / .z 和粒子属性");
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
                case "sign": op = OP_SIGN; break;
                case "sin": op = OP_SIN; break;
                case "cos": op = OP_COS; break;
                case "exp": op = OP_EXP; break;
                case "floor": op = OP_FLOOR; break;
                case "round": op = OP_ROUND; break;
                case "min": op = OP_MIN; break;
                case "max": op = OP_MAX; break;
                case "pow": op = OP_POW; break;
                case "atan2": op = OP_ATAN2; break;
                case "clamp": op = OP_CLAMP; break;
                case "smoothstep": op = OP_SMOOTHSTEP; break;
                case "len": op = OP_LEN; break;
                case "len_xz": op = OP_LEN_XZ; break;
                case "normalize": op = OP_NORMALIZE; break;
                case "dot": op = OP_DOT; break;
                case "cross": op = OP_CROSS; break;
                case "dist": op = OP_DIST; break;
                case "tangent": op = args.size() == 3 ? OP_TANGENT_UP : OP_TANGENT; break;
                case "radial": op = args.size() == 3 ? OP_RADIAL_UP : OP_RADIAL; break;
                case "rotate": op = OP_ROTATE; break;
                case "noise": op = OP_NOISE; break;
                case "rand": op = OP_RAND; break;
                case "lerp": op = typeOf(args.get(0), sc) == T.VEC3 ? OP_LERP_V : OP_LERP_N; break;
                default: throw new Unsupported("快速路径不支持这个函数");
            }

            int mark = nextReg;
            int[] src = new int[args.size()];
            for (int j = 0; j < src.length; j++)
            {
                src[j] = operand(args.get(j), sc);
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
                case "%":
                    emit3(OP_MOD_N, dst, sa, sb);
                    return;
                case "<": emit3(OP_LT, dst, sa, sb); return;
                case "<=": emit3(OP_LE, dst, sa, sb); return;
                case ">": emit3(OP_GT, dst, sa, sb); return;
                case ">=": emit3(OP_GE, dst, sa, sb); return;
                case "==": emit3(OP_EQ_N, dst, sa, sb); return;
                case "!=": emit3(OP_NE_N, dst, sa, sb); return;
                case "and": emit3(OP_AND, dst, sa, sb); return;
                case "or": emit3(OP_OR, dst, sa, sb); return;
                default: throw new Unsupported("未知运算符");
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
                default: throw new Unsupported("不认识的粒子属性");
            }
            emit1(op, dst);
        }

        private void emitLoadComponent(String prop, String comp, int dst)
        {
            int k = component(comp);
            if (k < 0)
            {
                throw new Unsupported("分量只能是 .x / .y / .z");
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
                throw new Unsupported("这个属性没有逐分量读取");
            }
            emit1(op, dst);
        }

        private void emitStoreComponent(String prop, String comp, int dst)
        {
            int k = component(comp);
            if (k < 0)
            {
                throw new Unsupported("分量只能是 .x / .y / .z");
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
            code.add(word(OP_LDK, dst, 0, 0));
            code.add(constSlot(v));
        }

        /** 常量表里这个 double 的下标（按原始位模式去重） */
        private int constSlot(double v)
        {
            long bits = Double.doubleToRawLongBits(v);
            Integer k = constIndex.get(bits);
            if (k == null)
            {
                k = consts.add(v);
                constIndex.put(bits, k);
            }
            return k;
        }

        private void emitFree(String name, T t, int dst)
        {
            Free f = free(name, t);
            emit2(t == T.VEC3 ? OP_LDF3 : OP_LDF1, dst, f.offset);
        }

        /** 写回 cast 变量：先记在 fv 里，整段跑完由 {@link #completeStep} 搬回 Env */
        private void emitFreeStore(String name, T t, int src)
        {
            Free f = free(name, t);
            f.written = true;
            emit3(t == T.VEC3 ? OP_STF3 : OP_STF1, 0, f.offset, src);
        }

        /** 取（或新建）一个自由变量的槽 */
        private Free free(String name, T t)
        {
            Free f = freeIndex.get(name);
            if (f == null)
            {
                f = new Free(name, t);
                f.offset = loop.freeSlots;
                loop.freeSlots += width(t);
                if (loop.freeSlots > MAX_REGS)
                {
                    throw new Unsupported("cast 变量太多（超过 256 个槽）");
                }
                freeIndex.put(name, f);
                loop.frees.add(f);
            }
            return f;
        }

        // ------------------------------------------------ 寄存器分配

        private int alloc(T t)
        {
            int base = nextReg;
            nextReg += width(t);
            if (nextReg > MAX_REGS)
            {
                throw new Unsupported("表达式太复杂（寄存器超过 256 个）");
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
        /** 每条指令的源码位置（{@code line<<12|col}），运行期报错用；与 data 同步增长 */
        private int[] pos = new int[64];
        /** 接下来 add 的指令记成这个位置 */
        private int curPos;
        private int size;

        /**
         * 声明"接下来的指令来自这几行"。行号/列号压进 12 位（脚本最多 20000 节点，
         * 单行超过 4095 列不现实），不够的部分截断——定位只需要大致指到那一行。
         */
        void at(int line, int col)
        {
            int l = Math.max(0, Math.min(0xFFF, line));
            int c = Math.max(0, Math.min(0xFFF, col));
            curPos = (l << 12) | c;
        }

        int add(int v)
        {
            if (size == data.length)
            {
                data = java.util.Arrays.copyOf(data, size * 2);
                pos = java.util.Arrays.copyOf(pos, size * 2);
            }
            data[size] = v;
            pos[size] = curPos;
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

        int[] positions()
        {
            return java.util.Arrays.copyOf(pos, size);
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
