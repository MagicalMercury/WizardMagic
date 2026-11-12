package com.wizardmagic.core.spell.script;

/**
 * 词法单元（行号、列号均从 1 开始）。
 *
 * @param text 标识符/运算符的原文；数字时为空串
 * @param num  仅 {@link Kind#NUM} 有意义
 */
public record Token(Kind kind, String text, double num, int line, int col)
{
    public enum Kind
    {
        NUM, IDENT,
        LPAREN, RPAREN, LBRACE, RBRACE, COMMA, COLON, DOT,
        ASSIGN, EQ, NE, LT, LE, GT, GE,
        PLUS, MINUS, STAR, SLASH,
        PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
        LET, IF, ELSE, FOR, IN, PARTICLES, ON, EMIT, PARAM,
        AND, OR, NOT, TRUE, FALSE,
        EOF
    }

    public static Token eof(int line, int col)
    {
        return new Token(Kind.EOF, "<eof>", 0.0, line, col);
    }

    public Ast.Node at()
    {
        return new SpanNode(line, col);
    }

    /** 只带位置的最小节点，用于不产生 AST 的错误/中间量 */
    private record SpanNode(int line, int col) implements Ast.Node
    {
    }
}
