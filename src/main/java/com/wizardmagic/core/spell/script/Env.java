package com.wizardmagic.core.spell.script;

import java.util.HashMap;
import java.util.Map;

/** 一层词法作用域，链式查找。 */
final class Env
{
    private final Env parent;
    private final Map<String, Value> vars = new HashMap<>();

    Env(Env parent)
    {
        this.parent = parent;
    }

    Value get(String name)
    {
        for (Env e = this; e != null; e = e.parent)
        {
            Value v = e.vars.get(name);
            if (v != null)
            {
                return v;
            }
        }
        return null;
    }

    void define(String name, Value v)
    {
        vars.put(name, v);
    }

    boolean assign(String name, Value v)
    {
        for (Env e = this; e != null; e = e.parent)
        {
            if (e.vars.containsKey(name))
            {
                e.vars.put(name, v);
                return true;
            }
        }
        return false;
    }

    /** 循环体每轮复用同一层，先清掉上一轮的局部变量 */
    void clear()
    {
        vars.clear();
    }

    Map<String, Value> snapshot()
    {
        return new HashMap<>(vars);
    }
}
