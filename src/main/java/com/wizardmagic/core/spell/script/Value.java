package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.core.spell.SpellCloud;

/** 运行时值。没有 null、没有字符串、没有对象引用泄漏到脚本里。 */
public sealed interface Value
{
    record Num(double v) implements Value
    {
    }

    record Bool(boolean v) implements Value
    {
    }

    record Vec3(double x, double y, double z) implements Value
    {
        public double len()
        {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /** 元素表下标 + 名字（名字只用于错误信息） */
    record Elem(int id, String name) implements Value
    {
    }

    /** 形状 id + 已合并默认值的参数 */
    record Shape(String id, ShapeParams params) implements Value
    {
    }

    /** 逐粒子句柄：属性读写直通 {@link SpellCloud} 的数组 */
    record Particle(SpellCloud cloud, int index) implements Value
    {
    }

    record Void() implements Value
    {
    }

    Void VOID = new Void();
    Bool TRUE = new Bool(true);
    Bool FALSE = new Bool(false);

    static Value num(double v)
    {
        return new Num(v);
    }

    static Value vec(double x, double y, double z)
    {
        return new Vec3(x, y, z);
    }

    /** 能否作为云级变量保存、并随生成包发给客户端 */
    default boolean storable()
    {
        return this instanceof Num || this instanceof Bool || this instanceof Vec3 || this instanceof Elem;
    }

    static String typeOf(Value v)
    {
        if (v instanceof Num)
        {
            return "num";
        }
        if (v instanceof Bool)
        {
            return "bool";
        }
        if (v instanceof Vec3)
        {
            return "vec3";
        }
        if (v instanceof Elem)
        {
            return "element";
        }
        if (v instanceof Shape)
        {
            return "shape";
        }
        if (v instanceof Particle)
        {
            return "particle";
        }
        return "void";
    }
}
