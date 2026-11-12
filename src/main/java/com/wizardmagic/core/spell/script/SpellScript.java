package com.wizardmagic.core.spell.script;

import com.wizardmagic.core.spell.SpellCloud;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个编译好的法术脚本。
 *
 * <p>编译只做三件事：词法/语法、节点预算、以及「tick 里不许读世界」的静态检查。
 * 求值本身在 {@link Interpreter} 里，纯函数式地跑在 {@link SpellCloud} 的 SoA 上。</p>
 */
public final class SpellScript
{
    /** tick 里禁止出现的世界查询（否则两端输入不同，轨迹必然分叉） */
    private static final Set<String> WORLD_CALLS = Set.of("field", "light", "weather", "time_of_day", "here");

    private static final int MAX_SOURCE = 64 * 1024;

    public final String id;
    public final String source;
    public final Ast.Program program;

    /** 粒子循环体的寄存器机版本；null = 还没编译或已判定不适用 */
    private volatile FastProgram fast;
    /** {@link #fast} 是按哪一套 cast 变量编的（空串 = 没有云级变量） */
    private volatile String fastSig = "";
    private volatile boolean fastDisabled;

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
        checkTick(program.tick());
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
            f = FastProgram.compile(program, cloud.scriptVars);
            if (f == null)
            {
                fastDisabled = true;
                if (System.getProperty("wizardmagic.script.debug") != null)
                {
                    System.err.println("[script] " + id + " 不支持快速路径，走解释器");
                }
            }
            else
            {
                fast = f;
                fastSig = sig;
            }
        }
        if (f != null)
        {
            if (f.tick(cloud))
            {
                return;
            }
            fast = null;
            fastSig = "";
            fastDisabled = true;
        }
        Interpreter.tick(program, cloud);
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
        FastProgram f = FastProgram.compile(program, vars);
        if (f == null)
        {
            return false;
        }
        fast = f;
        fastSig = sig;
        return true;
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

    // ------------------------------------------------------------ 静态检查

    private static void checkTick(List<Ast.Stmt> body)
    {
        for (Ast.Stmt s : body)
        {
            checkStmt(s);
        }
    }

    private static void checkStmt(Ast.Stmt s)
    {
        if (s instanceof Ast.Let let)
        {
            checkExpr(let.value());
        }
        else if (s instanceof Ast.Assign assign)
        {
            checkExpr(assign.value());
        }
        else if (s instanceof Ast.If branch)
        {
            checkExpr(branch.cond());
            checkTick(branch.then());
            checkTick(branch.otherwise());
        }
        else if (s instanceof Ast.ForParticles loop)
        {
            checkTick(loop.body());
        }
        else if (s instanceof Ast.Emit emit)
        {
            for (Ast.Expr e : emit.fields().values())
            {
                checkExpr(e);
            }
        }
        else if (s instanceof Ast.ExprStmt exprStmt)
        {
            checkExpr(exprStmt.expr());
        }
    }

    private static void checkExpr(Ast.Expr e)
    {
        if (e instanceof Ast.Call call)
        {
            if (WORLD_CALLS.contains(call.fn()))
            {
                throw new ScriptException(call.fn() + "() 只能在 on cast 里调用"
                        + "（tick 双端各跑一遍，读世界会让两端输入不同）", call.line(), call.col());
            }
            for (Ast.Expr arg : call.args())
            {
                checkExpr(arg);
            }
        }
        else if (e instanceof Ast.Member member)
        {
            checkExpr(member.base());
        }
        else if (e instanceof Ast.Unary unary)
        {
            checkExpr(unary.e());
        }
        else if (e instanceof Ast.Binary binary)
        {
            checkExpr(binary.a());
            checkExpr(binary.b());
        }
        else if (e instanceof Ast.RecordLit record)
        {
            for (Ast.Expr v : record.fields().values())
            {
                checkExpr(v);
            }
        }
    }
}
