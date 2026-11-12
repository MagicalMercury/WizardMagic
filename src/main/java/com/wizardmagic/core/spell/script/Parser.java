package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.Shapes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归下降解析器。
 *
 * <p>语法见 {@code plan/spell-script-language.md} §4。两个刻意的决定：</p>
 * <ul>
 *   <li>{@code if} 的条件必须带括号（{@code if (x > 0) {}}），否则
 *       {@code if flag { ... }} 与形状记录 {@code helix { ... }} 无法区分。</li>
 *   <li>形状记录只在 {@link Shapes#exists(String)} 为真时按记录解析，
 *       所以变量名永远不会被误判成形状。</li>
 * </ul>
 */
public final class Parser
{
    private static final int MAX_NODES = 20000;

    private final List<Token> toks;
    private int i;
    private int nodes;

    private Parser(List<Token> toks)
    {
        this.toks = toks;
    }

    public static Ast.Program parse(String source)
    {
        return new Parser(new Lexer(source).lex()).program();
    }

    // ------------------------------------------------------------ 顶层

    private Ast.Program program()
    {
        Map<String, Ast.Expr> params = new LinkedHashMap<>();
        List<Ast.Stmt> cast = null;
        List<Ast.Stmt> tick = null;

        while (!peek(Token.Kind.EOF))
        {
            Token t = peekTok();
            if (t.kind() == Token.Kind.PARAM)
            {
                next();
                Token name = expect(Token.Kind.IDENT, "参数名");
                expect(Token.Kind.ASSIGN, "=");
                params.put(name.text(), expr());
            }
            else if (t.kind() == Token.Kind.ON)
            {
                next();
                Token stage = expect(Token.Kind.IDENT, "阶段名 cast 或 tick");
                if (peek(Token.Kind.LPAREN))
                {
                    next();
                    Token arg = expect(Token.Kind.IDENT, "参数名");
                    if (!arg.text().equals("dt"))
                    {
                        throw err("on tick 的参数名必须是 dt（收到 " + arg.text() + "）", arg);
                    }
                    expect(Token.Kind.RPAREN, ")");
                }
                List<Ast.Stmt> body = block();
                if (stage.text().equals("cast"))
                {
                    if (cast != null)
                    {
                        throw err("重复的 on cast", stage);
                    }
                    cast = body;
                }
                else if (stage.text().equals("tick"))
                {
                    if (tick != null)
                    {
                        throw err("重复的 on tick", stage);
                    }
                    tick = body;
                }
                else
                {
                    throw err("未知阶段 " + stage.text() + "（只有 cast / tick）", stage);
                }
            }
            else
            {
                throw err("顶层只允许 param / on cast / on tick", t);
            }
        }

        if (cast == null)
        {
            throw new ScriptException("缺少 on cast（法术至少要有一次生成）", 1, 1);
        }
        return new Ast.Program(params, cast, tick == null ? List.of() : tick, nodes);
    }

    private List<Ast.Stmt> block()
    {
        expect(Token.Kind.LBRACE, "{");
        List<Ast.Stmt> body = new ArrayList<>();
        while (!peek(Token.Kind.RBRACE))
        {
            if (peek(Token.Kind.EOF))
            {
                throw err("代码块没有闭合（缺少 }）", peekTok());
            }
            body.add(stmt());
        }
        expect(Token.Kind.RBRACE, "}");
        return List.copyOf(body);
    }

    // ------------------------------------------------------------ 语句

    private Ast.Stmt stmt()
    {
        Token t = peekTok();
        switch (t.kind())
        {
            case LET:
            {
                next();
                Token name = expect(Token.Kind.IDENT, "变量名");
                expect(Token.Kind.ASSIGN, "=");
                return n(new Ast.Let(name.text(), expr(), t.line(), t.col()));
            }
            case IF:
            {
                next();
                expect(Token.Kind.LPAREN, "（if 的条件必须带括号）");
                Ast.Expr cond = expr();
                expect(Token.Kind.RPAREN, ")");
                List<Ast.Stmt> then = block();
                List<Ast.Stmt> otherwise = List.of();
                if (peek(Token.Kind.ELSE))
                {
                    next();
                    otherwise = peek(Token.Kind.IF) ? List.of(stmt()) : block();
                }
                return n(new Ast.If(cond, then, otherwise, t.line(), t.col()));
            }
            case FOR:
            {
                next();
                Token name = expect(Token.Kind.IDENT, "循环变量");
                expect(Token.Kind.IN, "in");
                expect(Token.Kind.PARTICLES, "particles");
                return n(new Ast.ForParticles(name.text(), block(), t.line(), t.col()));
            }
            case EMIT:
            {
                next();
                return n(new Ast.Emit(recordFields(), t.line(), t.col()));
            }
            default:
            {
                Ast.Expr e = expr();
                Token op = peekTok();
                if (isAssign(op.kind()))
                {
                    next();
                    return n(assign(e, op));
                }
                return n(new Ast.ExprStmt(e, t.line(), t.col()));
            }
        }
    }

    private Ast.Stmt assign(Ast.Expr target, Token op)
    {
        List<String> path = new ArrayList<>();
        Ast.Expr cur = target;
        while (cur instanceof Ast.Member m)
        {
            path.add(0, m.name());
            cur = m.base();
        }
        if (!(cur instanceof Ast.Var var))
        {
            throw err("赋值左侧必须是变量或粒子属性", op);
        }
        return new Ast.Assign(var.name(), List.copyOf(path), binOp(op.kind()),
                expr(), target.line(), target.col());
    }

    private Map<String, Ast.Expr> recordFields()
    {
        expect(Token.Kind.LBRACE, "{");
        Map<String, Ast.Expr> fields = new LinkedHashMap<>();
        while (!peek(Token.Kind.RBRACE))
        {
            Token key = expect(Token.Kind.IDENT, "字段名");
            expect(Token.Kind.COLON, ":");
            fields.put(key.text(), expr());
            // 逗号可选：允许一行一个字段（.spell 的实际写法）
            if (peek(Token.Kind.COMMA))
            {
                next();
            }
        }
        expect(Token.Kind.RBRACE, "}");
        return fields;
    }

    // ------------------------------------------------------------ 表达式

    private Ast.Expr expr()
    {
        return orExpr();
    }

    private Ast.Expr orExpr()
    {
        Ast.Expr e = andExpr();
        while (peek(Token.Kind.OR))
        {
            Token op = next();
            e = n(new Ast.Binary("or", e, andExpr(), op.line(), op.col()));
        }
        return e;
    }

    private Ast.Expr andExpr()
    {
        Ast.Expr e = notExpr();
        while (peek(Token.Kind.AND))
        {
            Token op = next();
            e = n(new Ast.Binary("and", e, notExpr(), op.line(), op.col()));
        }
        return e;
    }

    private Ast.Expr notExpr()
    {
        if (peek(Token.Kind.NOT))
        {
            Token op = next();
            return n(new Ast.Unary("not", notExpr(), op.line(), op.col()));
        }
        return cmpExpr();
    }

    private Ast.Expr cmpExpr()
    {
        Ast.Expr e = addExpr();
        Token.Kind k = peekTok().kind();
        if (k == Token.Kind.LT || k == Token.Kind.LE || k == Token.Kind.GT
                || k == Token.Kind.GE || k == Token.Kind.EQ || k == Token.Kind.NE)
        {
            Token op = next();
            e = n(new Ast.Binary(op.text(), e, addExpr(), op.line(), op.col()));
        }
        return e;
    }

    private Ast.Expr addExpr()
    {
        Ast.Expr e = mulExpr();
        while (peek(Token.Kind.PLUS) || peek(Token.Kind.MINUS))
        {
            Token op = next();
            e = n(new Ast.Binary(op.text(), e, mulExpr(), op.line(), op.col()));
        }
        return e;
    }

    private Ast.Expr mulExpr()
    {
        Ast.Expr e = unaryExpr();
        while (peek(Token.Kind.STAR) || peek(Token.Kind.SLASH))
        {
            Token op = next();
            e = n(new Ast.Binary(op.text(), e, unaryExpr(), op.line(), op.col()));
        }
        return e;
    }

    private Ast.Expr unaryExpr()
    {
        if (peek(Token.Kind.MINUS))
        {
            Token op = next();
            return n(new Ast.Unary("-", unaryExpr(), op.line(), op.col()));
        }
        return postfix();
    }

    private Ast.Expr postfix()
    {
        Ast.Expr e = primary();
        while (peek(Token.Kind.DOT))
        {
            Token dot = next();
            Token name = expect(Token.Kind.IDENT, "属性名");
            e = n(new Ast.Member(e, name.text(), dot.line(), dot.col()));
        }
        return e;
    }

    private Ast.Expr primary()
    {
        Token t = peekTok();
        switch (t.kind())
        {
            case NUM:
                next();
                return n(new Ast.NumLit(t.num(), t.line(), t.col()));
            case TRUE:
                next();
                return n(new Ast.BoolLit(true, t.line(), t.col()));
            case FALSE:
                next();
                return n(new Ast.BoolLit(false, t.line(), t.col()));
            case IDENT:
            {
                next();
                if (peek(Token.Kind.LPAREN))
                {
                    return n(call(t));
                }
                if (peek(Token.Kind.LBRACE) && Shapes.exists(t.text()))
                {
                    return n(new Ast.RecordLit(t.text(), recordFields(), t.line(), t.col()));
                }
                return n(new Ast.Var(t.text(), t.line(), t.col()));
            }
            case LPAREN:
            {
                next();
                Ast.Expr e = expr();
                expect(Token.Kind.RPAREN, ")");
                return e;
            }
            default:
                throw err("这里需要一个表达式，读到 " + describe(t), t);
        }
    }

    private Ast.Expr call(Token name)
    {
        expect(Token.Kind.LPAREN, "(");
        List<Ast.Expr> args = new ArrayList<>();
        while (!peek(Token.Kind.RPAREN))
        {
            args.add(expr());
            if (!peek(Token.Kind.RPAREN))
            {
                expect(Token.Kind.COMMA, ",");
            }
        }
        expect(Token.Kind.RPAREN, ")");
        return new Ast.Call(name.text(), List.copyOf(args), name.line(), name.col());
    }

    // ------------------------------------------------------------ 工具

    private <T extends Ast.Node> T n(T node)
    {
        if (++nodes > MAX_NODES)
        {
            throw new ScriptException("脚本节点数超过上限 " + MAX_NODES, node.line(), node.col());
        }
        return node;
    }

    private Token peekTok()
    {
        return toks.get(Math.min(i, toks.size() - 1));
    }

    private boolean peek(Token.Kind kind)
    {
        return peekTok().kind() == kind;
    }

    private Token next()
    {
        Token t = peekTok();
        if (i < toks.size() - 1)
        {
            i++;
        }
        return t;
    }

    private Token expect(Token.Kind kind, String what)
    {
        Token t = peekTok();
        if (t.kind() != kind)
        {
            throw err("这里需要 " + what + "，读到 " + describe(t), t);
        }
        return next();
    }

    private static boolean isAssign(Token.Kind k)
    {
        return k == Token.Kind.ASSIGN || k == Token.Kind.PLUS_ASSIGN
                || k == Token.Kind.MINUS_ASSIGN || k == Token.Kind.STAR_ASSIGN
                || k == Token.Kind.SLASH_ASSIGN;
    }

    /** 复合赋值还原成二元运算符 */
    private static String binOp(Token.Kind k)
    {
        switch (k)
        {
            case PLUS_ASSIGN: return "+";
            case MINUS_ASSIGN: return "-";
            case STAR_ASSIGN: return "*";
            case SLASH_ASSIGN: return "/";
            default: return "";
        }
    }

    private static String describe(Token t)
    {
        return t.kind() == Token.Kind.EOF ? "文件结束" : "'" + t.text() + "'";
    }

    private static ScriptException err(String message, Token t)
    {
        return new ScriptException(message, t.line(), t.col());
    }
}
