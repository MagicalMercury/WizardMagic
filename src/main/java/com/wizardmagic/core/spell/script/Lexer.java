package com.wizardmagic.core.spell.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法分析：源码 → Token 流。
 *
 * <p>注释：{@code #} 或 {@code //} 到行尾。换行只用于计数，不产生 token。</p>
 */
public final class Lexer
{
    private static final Map<String, Token.Kind> KEYWORDS = new HashMap<>();

    static
    {
        KEYWORDS.put("let", Token.Kind.LET);
        KEYWORDS.put("if", Token.Kind.IF);
        KEYWORDS.put("else", Token.Kind.ELSE);
        KEYWORDS.put("for", Token.Kind.FOR);
        KEYWORDS.put("in", Token.Kind.IN);
        KEYWORDS.put("particles", Token.Kind.PARTICLES);
        KEYWORDS.put("on", Token.Kind.ON);
        KEYWORDS.put("emit", Token.Kind.EMIT);
        KEYWORDS.put("param", Token.Kind.PARAM);
        KEYWORDS.put("and", Token.Kind.AND);
        KEYWORDS.put("or", Token.Kind.OR);
        KEYWORDS.put("not", Token.Kind.NOT);
        KEYWORDS.put("true", Token.Kind.TRUE);
        KEYWORDS.put("false", Token.Kind.FALSE);
    }

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    public Lexer(String src)
    {
        this.src = src == null ? "" : src;
    }

    public List<Token> lex()
    {
        List<Token> out = new ArrayList<>();
        while (true)
        {
            skipBlank();
            if (pos >= src.length())
            {
                out.add(Token.eof(line, col));
                return out;
            }

            char c = src.charAt(pos);
            int l = line;
            int k = col;
            if (isDigit(c))
            {
                out.add(number(l, k));
            }
            else if (isIdentStart(c))
            {
                out.add(ident(l, k));
            }
            else
            {
                out.add(punct(l, k));
            }
        }
    }

    private void skipBlank()
    {
        while (pos < src.length())
        {
            char c = src.charAt(pos);
            if (c == '\n')
            {
                pos++;
                line++;
                col = 1;
            }
            else if (c == '\r' || c == ' ' || c == '\t')
            {
                pos++;
                col++;
            }
            else if (c == '#')
            {
                while (pos < src.length() && src.charAt(pos) != '\n')
                {
                    pos++;
                    col++;
                }
            }
            else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/')
            {
                while (pos < src.length() && src.charAt(pos) != '\n')
                {
                    pos++;
                    col++;
                }
            }
            else
            {
                return;
            }
        }
    }

    private Token number(int l, int k)
    {
        int start = pos;
        while (pos < src.length() && isDigit(src.charAt(pos)))
        {
            pos++;
            col++;
        }
        if (pos + 1 < src.length() && src.charAt(pos) == '.' && isDigit(src.charAt(pos + 1)))
        {
            pos++;
            col++;
            while (pos < src.length() && isDigit(src.charAt(pos)))
            {
                pos++;
                col++;
            }
        }
        String text = src.substring(start, pos);
        try
        {
            return new Token(Token.Kind.NUM, "", Double.parseDouble(text), l, k);
        }
        catch (NumberFormatException e)
        {
            throw new ScriptException("非法数字 " + text, l, k);
        }
    }

    private Token ident(int l, int k)
    {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos)))
        {
            pos++;
            col++;
        }
        String text = src.substring(start, pos);
        Token.Kind kw = KEYWORDS.get(text);
        return new Token(kw == null ? Token.Kind.IDENT : kw, text, 0.0, l, k);
    }

    private Token punct(int l, int k)
    {
        char c = src.charAt(pos);
        char d = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
        switch (c)
        {
            case '(': return one(Token.Kind.LPAREN, "(", l, k);
            case ')': return one(Token.Kind.RPAREN, ")", l, k);
            case '{': return one(Token.Kind.LBRACE, "{", l, k);
            case '}': return one(Token.Kind.RBRACE, "}", l, k);
            case ',': return one(Token.Kind.COMMA, ",", l, k);
            case ':': return one(Token.Kind.COLON, ":", l, k);
            case '.': return one(Token.Kind.DOT, ".", l, k);
            case '=':
                if (d == '=')
                {
                    return two(Token.Kind.EQ, "==", l, k);
                }
                return one(Token.Kind.ASSIGN, "=", l, k);
            case '!':
                if (d == '=')
                {
                    return two(Token.Kind.NE, "!=", l, k);
                }
                throw new ScriptException("非法字符 '!'（比较用 !=，取反用 not）", l, k);
            case '<':
                return d == '=' ? two(Token.Kind.LE, "<=", l, k) : one(Token.Kind.LT, "<", l, k);
            case '>':
                return d == '=' ? two(Token.Kind.GE, ">=", l, k) : one(Token.Kind.GT, ">", l, k);
            case '+':
                return d == '=' ? two(Token.Kind.PLUS_ASSIGN, "+=", l, k) : one(Token.Kind.PLUS, "+", l, k);
            case '-':
                return d == '=' ? two(Token.Kind.MINUS_ASSIGN, "-=", l, k) : one(Token.Kind.MINUS, "-", l, k);
            case '*':
                return d == '=' ? two(Token.Kind.STAR_ASSIGN, "*=", l, k) : one(Token.Kind.STAR, "*", l, k);
            case '/':
                return d == '=' ? two(Token.Kind.SLASH_ASSIGN, "/=", l, k) : one(Token.Kind.SLASH, "/", l, k);
            default:
                throw new ScriptException("非法字符 '" + c + "'", l, k);
        }
    }

    private Token one(Token.Kind kind, String text, int l, int k)
    {
        pos++;
        col++;
        return new Token(kind, text, 0.0, l, k);
    }

    private Token two(Token.Kind kind, String text, int l, int k)
    {
        pos += 2;
        col += 2;
        return new Token(kind, text, 0.0, l, k);
    }

    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c)
    {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentPart(char c)
    {
        return isIdentStart(c) || isDigit(c);
    }
}
