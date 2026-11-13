package com.wizardmagic.core.spell.script;

import java.util.List;
import java.util.Map;

/**
 * 语法树。全部不可变记录，节点自带行列号（诊断定位用）。
 *
 * <p>语句里没有 {@code return}/{@code while}/函数定义；表达式也没有字符串。
 * 这是刻意的：脚本要能被<b>完整地</b>序列化、重放、双端逐位复现。</p>
 */
public final class Ast
{
    private Ast()
    {
    }

    public interface Node
    {
        int line();

        int col();
    }

    /** 表达式 */
    public sealed interface Expr extends Node
    {
    }

    public record NumLit(double v, int line, int col) implements Expr
    {
    }

    public record BoolLit(boolean v, int line, int col) implements Expr
    {
    }

    /**
     * 字符串字面量。只用于和"引擎给的名字"比较（目前只有 {@code hit.block}），
     * 不能拼接、不能参与算术——见 {@link Value.Str}。
     */
    public record StrLit(String v, int line, int col) implements Expr
    {
    }

    /** 变量或元素 id 字面量（求值时先查作用域，再查元素表） */
    public record Var(String name, int line, int col) implements Expr
    {
    }

    /** 成员访问：vec3 的 x/y/z 或粒子的属性 */
    public record Member(Expr base, String name, int line, int col) implements Expr
    {
    }

    public record Call(String fn, List<Expr> args, int line, int col) implements Expr
    {
    }

    /** 记录构造：形状（helix/ring/...） */
    public record RecordLit(String type, Map<String, Expr> fields, int line, int col) implements Expr
    {
    }

    public record Unary(String op, Expr e, int line, int col) implements Expr
    {
    }

    public record Binary(String op, Expr a, Expr b, int line, int col) implements Expr
    {
    }

    /** 语句 */
    public sealed interface Stmt extends Node
    {
    }

    public record Let(String name, Expr value, int line, int col) implements Stmt
    {
    }

    /**
     * 赋值。{@code base} 是变量名，{@code path} 是属性链：
     * {@code x = 1} 时为空，{@code p.v = ...} 时为 {@code ["v"]}，
     * {@code p.pos.x = ...} 时为 {@code ["pos","x"]}。
     */
    public record Assign(String base, List<String> path, String op, Expr value,
                         int line, int col) implements Stmt
    {
    }

    public record If(Expr cond, List<Stmt> then, List<Stmt> otherwise,
                     int line, int col) implements Stmt
    {
    }

    public record ForParticles(String var, List<Stmt> body, int line, int col) implements Stmt
    {
    }

    public record Emit(Map<String, Expr> fields, int line, int col) implements Stmt
    {
    }

    /**
     * {@code call 名字(实参...)}：执行一个自定义事件（{@code on 名字(形参...) { ... }}）。
     *
     * <p>没有返回值——它不是函数，是"给一段代码起名字"。形参只是被调事件的局部变量。</p>
     */
    public record CallEvent(String name, List<Expr> args, int line, int col) implements Stmt
    {
    }

    /**
     * 自定义事件。
     *
     * @param params 形参名（按声明顺序；实参按位绑定）
     * @param body   事件体
     */
    public record Event(String name, List<String> params, List<Stmt> body, int line, int col)
    {
    }

    public record ExprStmt(Expr expr, int line, int col) implements Stmt
    {
    }

    /**
     * 一个 .spell 的编译产物。
     *
     * @param params 顶层 {@code param}，按声明顺序求值
     * @param cast   {@code on cast} 体（可为空）
     * @param tick   {@code on tick} 体（可为空 = 静止云）
     * @param hit    {@code on hit} 体（可为空 = 撞了也不通知脚本）
     * @param touch  {@code on touch} 体（可为空 = 碰到生物也不通知脚本）
     * @param expire {@code on expire} 体（可为空 = 寿终直接消失）
     * @param events 自定义事件（{@code on 名字(形参) { ... }}），按声明顺序；{@code call} 触发
     * @param nodes  节点总数（预算检查用）
     */
    public record Program(Map<String, Expr> params, List<Stmt> cast, List<Stmt> tick,
                          List<Stmt> hit, List<Stmt> touch, List<Stmt> expire,
                          Map<String, Event> events, int nodes)
    {
    }
}
