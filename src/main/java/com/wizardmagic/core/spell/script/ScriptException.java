package com.wizardmagic.core.spell.script;

/** 脚本编译或求值错误，带源码位置。 */
public class ScriptException extends RuntimeException
{
    public final int line;
    public final int col;

    public ScriptException(String message, int line, int col)
    {
        super(message);
        this.line = line;
        this.col = col;
    }

    /** 命令与日志里显示的格式 */
    public String display()
    {
        return "第 " + line + " 行第 " + col + " 列：" + getMessage();
    }
}
