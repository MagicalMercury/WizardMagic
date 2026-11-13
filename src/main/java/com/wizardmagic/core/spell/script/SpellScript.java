package com.wizardmagic.core.spell.script;

import com.wizardmagic.core.spell.SpellCloud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个编译好的法术脚本。
 *
 * <p>编译只做三件事：词法/语法、节点预算、以及「tick / hit / expire 里不许读世界」的静态检查。
 * 求值本身在 {@link Interpreter} 里，纯函数式地跑在 {@link SpellCloud} 的 SoA 上。</p>
 *
 * <p>{@code on cast} 只有一次（生成时），{@code on tick} 每步、{@code on hit} 撞击后、
 * {@code on expire} 销毁前。后三个都跑在 SoA 上，能读能写粒子属性、能 emit 子云。</p>
 */
public final class SpellScript
{
    /** tick / hit / expire 里禁止出现的世界查询（否则两端输入不同，轨迹必然分叉） */
    private static final Set<String> WORLD_CALLS =
            Set.of("field", "light", "weather", "time_of_day", "here", "look", "aim");
    /** 只在服务端真正结算的内建（客户端执行但什么也不做） */
    private static final String HURT_CALL = "hurt";
    /** 立刻结束这朵云（不触发 {@code on expire}） */
    private static final String END_CALL = "end";
    /** 只能由引擎触发的阶段名：不能出现在 {@code call} 后面 */
    private static final Set<String> RESERVED_STAGES =
            Set.of("cast", "tick", "hit", "touch", "expire");
    /** 每个阶段作用域里都有的内建量：事件形参不能重名 */
    private static final Set<String> STAGE_VARS =
            Set.of("dt", "time", "life", "count", "step", "hit", "touch");

    private static final int MAX_SOURCE = 64 * 1024;

    public final String id;
    public final String source;
    public final Ast.Program program;

    /** 粒子循环体的寄存器机版本；null = 还没编译或已判定不适用 */
    private volatile FastProgram fast;
    /** {@link #fast} 是按哪一套 cast 变量编的（空串 = 没有云级变量） */
    private volatile String fastSig = "";
    private volatile boolean fastDisabled;
    /** 为什么没有快速路径（人话）；有快速路径时为 null */
    private volatile String fastReason;

    private SpellScript(String id, String source, Ast.Program program)
    {
        this.id = id;
        this.source = source;
        this.program = program;
    }

    /**
     * @param id     脚本 id（资源名或命令参数，仅用于显示与缓存）
     * @param source .spell 全文
     * @throws ScriptException 语法错误、预算超限、tick 里读世界
     */
    public static SpellScript compile(String id, String source)
    {
        if (source != null && source.length() > MAX_SOURCE)
        {
            throw new ScriptException("脚本超过 " + MAX_SOURCE + " 字符", 1, 1);
        }
        Ast.Program program = Parser.parse(source);
        // 自定义事件要按"谁能调用到它"来检查（hurt / 世界查询都是按阶段限制的）
        Map<String, Set<String>> reachable = eventStages(program);
        checkCalls(program, reachable);

        // cast 也过一遍：世界查询在那里是允许的，但 hurt 不是（那时还没有云）
        checkStage("cast", program.cast(), false, program);
        checkStage("tick", program.tick(), false, program);
        checkStage("hit", program.hit(), false, program);
        checkStage("touch", program.touch(), false, program);
        checkStage("expire", program.expire(), false, program);
        for (Map.Entry<String, Ast.Event> e : program.events().entrySet())
        {
            // 事件体本身按"被调用到的所有阶段"来判：{@code on boom} 被 on hit 和 on tick
            // 同时调用时，里面就不能打伤害（tick 是双端每步跑的）
            Set<String> stages = reachable.get(e.getKey());
            checkStageSet(e.getKey(), stages, e.getValue().body(), program);
        }
        return new SpellScript(id, source, program);
    }

    public CastResult cast(Host host, long seed)
    {
        return Interpreter.cast(program, host, seed);
    }

    public void tick(SpellCloud cloud)
    {
        if (program.tick().isEmpty())
        {
            return;
        }
        Step step = prepareStep(cloud);
        if (step == null)
        {
            Interpreter.tick(program, cloud);
            return;
        }
        runStep(step, cloud, 0, cloud.count);
        completeStep(step, cloud);
    }

    /**
     * 一步的准备工作（编译快速路径、搬 free 变量、算外提量）。**必须在调用线程上做**。
     *
     * <p>返回 null = 这朵云走解释器（没有 tick / 不支持快速路径）。此时云还没被改动，
     * 退回解释器是干净的。</p>
     */
    public Step prepareStep(SpellCloud cloud)
    {
        if (program.tick().isEmpty())
        {
            return null;
        }
        // 自由变量的类型是按 cast 变量推断的：换了一套变量就必须重编，不能沿用旧代码
        String sig = SpellBytecode.varsSignature(cloud.scriptVars);
        FastProgram f = fast;
        if (f != null && !sig.equals(fastSig))
        {
            fast = null;
            fastSig = "";
            f = null;
        }
        if (f == null && !fastDisabled)
        {
            FastProgram.Result result = FastProgram.compileChecked(program, cloud.scriptVars);
            f = result.program;
            if (f == null)
            {
                fastDisabled = true;
                fastReason = result.reason;
                if (System.getProperty("wizardmagic.script.debug") != null)
                {
                    System.err.println("[script] " + id + " 不支持快速路径：" + result.reason);
                }
            }
            else
            {
                fast = f;
                fastSig = sig;
                fastReason = null;
            }
        }
        if (f == null)
        {
            return null;
        }
        FastProgram.Step inner = f.prepareStep(cloud);
        if (inner == null)
        {
            fast = null;
            fastSig = "";
            fastDisabled = true;
            fastReason = "运行期准备失败：" + f.prepareReason();
            // 正常施法在生成前就编译好了，走到这里说明编译产物在运行期搬不动变量，
            // 退回解释器以保正确性，但要让日志看得见。
            System.err.println("[wizardmagic] 法术 " + id + " 退回解释器：" + fastReason);
            return null;
        }
        return new Step(f, inner);
    }

    /** 跑 {@code [from, to)}；可在任意线程并发调用（每段有自己的寄存器帧） */
    public void runStep(Step step, SpellCloud cloud, int from, int to)
    {
        step.program.runStep(step.inner, cloud, from, to);
    }

    /** 所有区间 join 之后调用一次：写回云级变量 */
    public void completeStep(Step step, SpellCloud cloud)
    {
        step.program.completeStep(step.inner, cloud);
    }

    /** 一步的中间态；只对同一朵云、同一步有效 */
    public static final class Step
    {
        private final FastProgram program;
        private final FastProgram.Step inner;

        private Step(FastProgram program, FastProgram.Step inner)
        {
            this.program = program;
            this.inner = inner;
        }

        /**
         * false = 这段代码不能按粒子区间切段（里面有 {@code rand()}：
         * 它的序列按粒子顺序消耗，切段就变了）。这种云要整段一次跑完。
         */
        public boolean parallelSafe()
        {
            return program.parallelSafe();
        }
    }

    /**
     * 用给定的一套云级变量先把快速路径编出来（预热 / 落盘用），不需要真的有一朵云。
     *
     * @return 是否已有可用的快速路径
     */
    public boolean prepareFastPath(Map<String, Value> vars)
    {
        if (program.tick().isEmpty())
        {
            return false;
        }
        String sig = SpellBytecode.varsSignature(vars);
        if (fast != null)
        {
            return sig.equals(fastSig);
        }
        FastProgram.Result result = FastProgram.compileChecked(program, vars);
        if (result.program == null)
        {
            fastReason = result.reason;
            return false;
        }
        fast = result.program;
        fastSig = sig;
        fastReason = null;
        return true;
    }

    /**
     * 为什么这朵云没有快速路径（人话）；有快速路径时为 {@code null}。
     *
     * <p>给生成日志和 {@code /spell debug scripts} 用：以前退化只打一行笼统的
     * "不支持快速路径"，作者看不出是哪一行、哪种写法。</p>
     */
    public String fastPathReason()
    {
        return fastReason;
    }

    /**
     * 当前快速路径的 {@code .spellc} 内容。
     *
     * @return null = 还没编出快速路径，这份脚本没有可缓存的东西
     */
    public byte[] fastBytecode()
    {
        FastProgram f = fast;
        if (f == null)
        {
            return null;
        }
        return SpellBytecode.pack(source, fastSig, f);
    }

    /**
     * 采用磁盘上的编译产物，跳过代码生成。
     *
     * <p>只认源码哈希、IR 版本、信封校验和都对得上的文件；变量签名先记下来，
     * 第一次 {@link #tick} 时再跟那朵云的变量核对，不一致就丢掉重编。</p>
     *
     * @return 是否采纳
     */
    public boolean adoptFastBytecode(byte[] blob)
    {
        if (program.tick().isEmpty())
        {
            return false;
        }
        SpellBytecode.Unpacked unpacked = SpellBytecode.unpack(this, blob);
        if (unpacked == null)
        {
            return false;
        }
        fast = unpacked.program();
        fastSig = unpacked.varsSig();
        fastDisabled = false;
        fastReason = null;
        return true;
    }

    /** 快速路径是按哪一套 cast 变量编的；诊断用 */
    public String fastVarsSignature()
    {
        return fastSig;
    }

    public int nodes()
    {
        return program.nodes();
    }

    /** 快速路径（粒子循环体字节码）是否已启用；诊断用 */
    public boolean fastPathActive()
    {
        return fast != null && !fastDisabled;
    }

    /** 强制走解释器（离线自检对照用） */
    public void tickInterpreted(SpellCloud cloud)
    {
        Interpreter.tick(program, cloud);
    }

    public boolean hasTick()
    {
        return !program.tick().isEmpty();
    }

    /** 是否有 {@code on hit}（没有就完全跳过撞击事件的求值） */
    public boolean hasHit()
    {
        return !program.hit().isEmpty();
    }

    /** 是否有 {@code on touch}（没有就完全跳过"碰到生物"的判定与扫盒子） */
    public boolean hasTouch()
    {
        return !program.touch().isEmpty();
    }

    /** 是否有 {@code on expire} */
    public boolean hasExpire()
    {
        return !program.expire().isEmpty();
    }

    /**
     * 撞击事件求值（由 {@link SpellCloud} 在碰撞之后调用，调用线程）。
     *
     * @param facts 本步的撞击事实，脚本里以 {@code hit} 出现
     */
    public void hit(SpellCloud cloud, SpellCloud.HitFacts facts)
    {
        Interpreter.hit(program, cloud, facts);
    }

    /**
     * 碰到生物事件求值（由 {@link SpellCloud} 在步末调用，**只在服务端**）。
     *
     * @param fact 碰到的那个实体，脚本里以 {@code touch} 出现
     */
    public void touch(SpellCloud cloud, SpellWorld.Touch fact)
    {
        Interpreter.touch(program, cloud, fact);
    }

    /** 寿终事件求值（由 {@code SpellCloudManager} 在销毁这朵云之前调用，只跑服务端） */
    public void expire(SpellCloud cloud)
    {
        Interpreter.expire(program, cloud);
    }

    /** 自定义事件（{@code on 名字}）；诊断与测试用 */
    public java.util.Set<String> eventNames()
    {
        return program.events().keySet();
    }

    public boolean hasEvent(String name)
    {
        return program.events().containsKey(name);
    }

    // ------------------------------------------------------------ 静态检查

    /**
     * 每个自定义事件"可能被哪些阶段调用到"。
     *
     * <p>这是自定义事件唯一复杂的地方：{@code hurt} 只许在 hit/expire 用、世界查询只许在 cast 用，
     * 而一段被 {@code call} 的代码不知道自己从哪来。所以先做一遍调用图不动点：
     * 事件被 {@code on tick} 调用 → 它也算 tick；它又 {@code call} 别人 → 别人也算 tick。</p>
     *
     * @return 事件名 → 可能调用到它的阶段集合（永不被调用的就是空集）
     */
    private static Map<String, Set<String>> eventStages(Ast.Program program)
    {
        Map<String, Set<String>> reach = new LinkedHashMap<>();
        for (String name : program.events().keySet())
        {
            reach.put(name, new LinkedHashSet<>());
        }
        Map<String, List<Ast.Stmt>> stageBodies = new LinkedHashMap<>();
        stageBodies.put("cast", program.cast());
        stageBodies.put("tick", program.tick());
        stageBodies.put("hit", program.hit());
        stageBodies.put("touch", program.touch());
        stageBodies.put("expire", program.expire());

        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (Map.Entry<String, List<Ast.Stmt>> e : stageBodies.entrySet())
            {
                for (String target : callTargets(e.getValue()))
                {
                    Set<String> set = reach.get(target);
                    if (set != null && set.add(e.getKey()))
                    {
                        changed = true;
                    }
                }
            }
            for (Map.Entry<String, Ast.Event> e : program.events().entrySet())
            {
                Set<String> from = reach.get(e.getKey());
                if (from.isEmpty())
                {
                    continue;
                }
                for (String target : callTargets(e.getValue().body()))
                {
                    Set<String> set = reach.get(target);
                    if (set != null && set.addAll(from))
                    {
                        changed = true;
                    }
                }
            }
        }
        return reach;
    }

    /** 一段代码里出现过的全部 {@code call} 目标（含 if/循环里嵌套的） */
    private static List<String> callTargets(List<Ast.Stmt> body)
    {
        List<String> out = new ArrayList<>();
        collectCalls(body, out);
        return out;
    }

    private static void collectCalls(List<Ast.Stmt> body, List<String> out)
    {
        for (Ast.Stmt s : body)
        {
            if (s instanceof Ast.CallEvent call)
            {
                out.add(call.name());
            }
            else if (s instanceof Ast.If branch)
            {
                collectCalls(branch.then(), out);
                collectCalls(branch.otherwise(), out);
            }
            else if (s instanceof Ast.ForParticles loop)
            {
                collectCalls(loop.body(), out);
            }
        }
    }

    /** 未定义的事件、把内置阶段当事件调、循环调用 */
    private static void checkCalls(Ast.Program program, Map<String, Set<String>> reach)
    {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        checkCallList("on cast", program.cast(), program);
        graph.put("cast", callTargets(program.cast()));
        checkCallList("on tick", program.tick(), program);
        graph.put("tick", callTargets(program.tick()));
        checkCallList("on hit", program.hit(), program);
        graph.put("hit", callTargets(program.hit()));
        checkCallList("on expire", program.expire(), program);
        graph.put("expire", callTargets(program.expire()));
        for (Map.Entry<String, Ast.Event> e : program.events().entrySet())
        {
            checkCallList("on " + e.getKey(), e.getValue().body(), program);
            graph.put(e.getKey(), callTargets(e.getValue().body()));
        }

        // 循环调用：a → b → a。语言里没有递归，转成编译期错误比运行期栈溢出强
        Set<String> done = new LinkedHashSet<>();
        for (String start : program.events().keySet())
        {
            detectCycle(start, graph, new LinkedHashSet<>(), done);
        }
        checkEventParams(program);
    }

    /**
     * 形参名不能和内建量、云级变量重名，也不能重复。
     *
     * <p>这几条都是"静默出错"的高危区：形参一旦和云级变量同名，事件体里的赋值会写到
     * 形参上，云级变量一动不动——表面看就是"状态莫名其妙没变"。</p>
     */
    private static void checkEventParams(Ast.Program program)
    {
        Set<String> cloudVars = new LinkedHashSet<>(program.params().keySet());
        for (Ast.Stmt s : program.cast())
        {
            if (s instanceof Ast.Let let)
            {
                cloudVars.add(let.name());
            }
        }
        for (Ast.Event e : program.events().values())
        {
            Set<String> seen = new LinkedHashSet<>();
            for (String p : e.params())
            {
                if (STAGE_VARS.contains(p))
                {
                    throw new ScriptException("自定义事件 " + e.name() + " 的形参不能叫 " + p
                            + "（那是每个阶段都有的内建量）", e.line(), e.col());
                }
                if (cloudVars.contains(p))
                {
                    throw new ScriptException("自定义事件 " + e.name() + " 的形参不能和云级变量同名："
                            + p + "（形参会把它遮住，事件里的赋值就写不到云级变量上了）",
                            e.line(), e.col());
                }
                if (!seen.add(p))
                {
                    throw new ScriptException("自定义事件 " + e.name() + " 的形参重复：" + p,
                            e.line(), e.col());
                }
            }
        }
    }

    private static void checkCallList(String where, List<Ast.Stmt> body, Ast.Program program)
    {
        for (Ast.CallEvent call : calls(body))
        {
            if (RESERVED_STAGES.contains(call.name()))
            {
                throw new ScriptException("call 只能调自定义事件（" + call.name()
                        + " 由引擎触发，不能手动调）", call.line(), call.col());
            }
            Ast.Event target = program.events().get(call.name());
            if (target == null)
            {
                throw new ScriptException(where + " 里 call 了未定义的事件 " + call.name()
                        + "（先写 on " + call.name() + " { ... }）", call.line(), call.col());
            }
            if (call.args().size() != target.params().size())
            {
                throw new ScriptException("call " + call.name() + " 需要 " + target.params().size()
                        + " 个实参，给了 " + call.args().size()
                        + " 个（on " + call.name() + " 的形参：" + String.join(", ", target.params()) + "）",
                        call.line(), call.col());
            }
        }
    }

    private static List<Ast.CallEvent> calls(List<Ast.Stmt> body)
    {
        List<Ast.CallEvent> out = new ArrayList<>();
        collectCallNodes(body, out);
        return out;
    }

    private static void collectCallNodes(List<Ast.Stmt> body, List<Ast.CallEvent> out)
    {
        for (Ast.Stmt s : body)
        {
            if (s instanceof Ast.CallEvent call)
            {
                out.add(call);
            }
            else if (s instanceof Ast.If branch)
            {
                collectCallNodes(branch.then(), out);
                collectCallNodes(branch.otherwise(), out);
            }
            else if (s instanceof Ast.ForParticles loop)
            {
                collectCallNodes(loop.body(), out);
            }
        }
    }

    private static void detectCycle(String at, Map<String, List<String>> graph,
                                    Set<String> path, Set<String> done)
    {
        if (done.contains(at))
        {
            return;
        }
        if (!path.add(at))
        {
            throw new ScriptException("自定义事件循环调用：" + String.join(" → ", path)
                    + " → " + at + "（事件不能递归）", 1, 1);
        }
        for (String next : graph.getOrDefault(at, List.of()))
        {
            if (graph.containsKey(next))
            {
                detectCycle(next, graph, path, done);
            }
        }
        path.remove(at);
        done.add(at);
    }

    private static void checkStage(String stage, List<Ast.Stmt> body, boolean inLoop, Ast.Program program)
    {
        checkBody(Set.of(stage), body, inLoop, program);
    }

    private static void checkStageSet(String name, Set<String> stages, List<Ast.Stmt> body,
                                      Ast.Program program)
    {
        if (stages.isEmpty())
        {
            // 永不被调用的事件：不检查阶段相关的规则（它永远不会跑），但语句本身仍然要合法
            checkBody(Set.of("cast", "tick", "hit", "expire"), body, false, program);
            return;
        }
        checkBody(stages, body, false, program);
    }

    private static void checkBody(Set<String> stages, List<Ast.Stmt> body, boolean inLoop,
                                  Ast.Program program)
    {
        for (Ast.Stmt s : body)
        {
            checkStmt(stages, s, inLoop, program);
        }
    }

    private static void checkStmt(Set<String> stages, Ast.Stmt s, boolean inLoop, Ast.Program program)
    {
        if (s instanceof Ast.Let let)
        {
            checkExpr(stages, let.value(), let, inLoop);
        }
        else if (s instanceof Ast.Assign assign)
        {
            checkExpr(stages, assign.value(), assign, inLoop);
        }
        else if (s instanceof Ast.CallEvent call)
        {
            // 被调用的事件看不到 p，写在循环里几乎总是笔误，而且"每粒一次 emit/hurt"会绕过
            // 前面那些"不许写在循环里"的保护，所以直接拒掉。
            if (inLoop)
            {
                throw new ScriptException("call 不能写在粒子循环里"
                        + "（被调用的事件看不到 p，写在这里只会把同一段代码重复 N 次）",
                        call.line(), call.col());
            }
        }
        else if (s instanceof Ast.If branch)
        {
            checkExpr(stages, branch.cond(), branch, inLoop);
            checkBody(stages, branch.then(), inLoop, program);
            checkBody(stages, branch.otherwise(), inLoop, program);
        }
        else if (s instanceof Ast.ForParticles loop)
        {
            checkBody(stages, loop.body(), true, program);
        }
        else if (s instanceof Ast.Emit emit)
        {
            // tick / hit / expire 里都可以生云，但只能写在粒子循环**外**：循环里一句 emit
            // 就是每粒一次，3000 粒的云会瞬间塞满世界。每步/终身的配额在 SpellCloud 里卡。
            if (inLoop)
            {
                throw new ScriptException("emit 只能写在粒子循环外"
                        + "（循环里每粒一次会把世界塞满）", emit.line(), emit.col());
            }
            for (Ast.Expr e : emit.fields().values())
            {
                checkExpr(stages, e, emit, inLoop);
            }
        }
        else if (s instanceof Ast.ExprStmt exprStmt)
        {
            checkExpr(stages, exprStmt.expr(), exprStmt, inLoop);
        }
    }

    private static void checkExpr(Set<String> stages, Ast.Expr e, Ast.Node at, boolean inLoop)
    {
        if (e instanceof Ast.Call call)
        {
            if (WORLD_CALLS.contains(call.fn()) && !stages.equals(Set.of("cast")))
            {
                throw new ScriptException(call.fn() + "() 只能在 on cast 里调用"
                        + "（这段代码还会被 " + stageNames(stages) + " 调用：各阶段双端跑，"
                        + "读世界会让轨迹分叉）", call.line(), call.col());
            }
            if (HURT_CALL.equals(call.fn()))
            {
                // 伤害在世界里结算，只有服务端有出口；客户端也跑同一份脚本，所以：
                //   · on tick 里不许打 —— 每步都打一次，而且 tick 是双端同跑的
                //   · on cast 里不许打 —— 那时还没有云、也没有"事件点"
                boolean ok = stages.stream()
                        .allMatch(s -> s.equals("hit") || s.equals("touch") || s.equals("expire"));
                if (!ok)
                {
                    throw new ScriptException("hurt() 只能写在 on hit / on touch / on expire 里"
                            + "（这段代码还会被 " + stageNames(stages) + " 调用）",
                            call.line(), call.col());
                }
                if (inLoop)
                {
                    throw new ScriptException("hurt() 不能写在粒子循环里"
                            + "（每粒一次会把范围伤害叠成天文数字）", call.line(), call.col());
                }
            }
            if (END_CALL.equals(call.fn()))
            {
                // end() 立刻结束这朵云；tick 是编译执行的，寄存器机没有"中途结束"的通道，
                // 所以只允许写在几个一次性钩子里。
                boolean ok = stages.stream()
                        .allMatch(s -> s.equals("hit") || s.equals("touch") || s.equals("expire"));
                if (!ok)
                {
                    throw new ScriptException("end() 只能写在 on hit / on touch / on expire 里"
                            + "（这段代码还会被 " + stageNames(stages) + " 调用；on tick 里"
                            + "用寿命/phase 控制结束）", call.line(), call.col());
                }
                if (inLoop)
                {
                    throw new ScriptException("end() 不能写在粒子循环里", call.line(), call.col());
                }
            }
            for (Ast.Expr arg : call.args())
            {
                checkExpr(stages, arg, at, inLoop);
            }
        }
        else if (e instanceof Ast.Member member)
        {
            checkExpr(stages, member.base(), at, inLoop);
        }
        else if (e instanceof Ast.Unary unary)
        {
            checkExpr(stages, unary.e(), at, inLoop);
        }
        else if (e instanceof Ast.Binary binary)
        {
            checkExpr(stages, binary.a(), at, inLoop);
            checkExpr(stages, binary.b(), at, inLoop);
        }
        else if (e instanceof Ast.RecordLit record)
        {
            for (Ast.Expr v : record.fields().values())
            {
                checkExpr(stages, v, at, inLoop);
            }
        }
    }

    private static String stageNames(Set<String> stages)
    {
        List<String> out = new ArrayList<>();
        for (String s : stages)
        {
            out.add("on " + s);
        }
        return String.join(" / ", out);
    }
}
