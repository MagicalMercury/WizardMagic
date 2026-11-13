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

    /**
     * 字符串：**只用来比较**，不能拼接、不能参与算术。
     *
     * <p>存在的唯一理由是"引擎给的名字"——目前只有 {@code hit.block}（撞到的方块 id，
     * 例如 {@code "minecraft:ice"}）和脚本里的字面量 {@code "minecraft:ice"}。
     * 它不可存储（不进云级变量、不上生成包），所以不影响双端一致性与存档格式。</p>
     */
    record Str(String v) implements Value
    {
    }

    /**
     * 一个生物实体（只在 {@code on touch} 里出现，服务端读）。
     *
     * <p>只读：`type`（{@code "minecraft:zombie"}）、`pos`、`id`、`health`。`id` 可以拿来比较
     * （同一只生物一步里不会触发两次），但不可存储——客户端根本没跑过这个事件。</p>
     */
    record Entity(long id, String type, double x, double y, double z, double health)
            implements Value
    {
        static Entity of(SpellWorld.Touch t)
        {
            return new Entity(t.id(), t.type(), t.x(), t.y(), t.z(), t.health());
        }
    }

    /**
     * 一次"碰到生物"事件：{@code on touch} 里的 {@code touch}。
     *
     * @param entity 碰到的那个生物
     * @param px,py,pz 接触点（第一个落在它身体里的粒子位置）
     */
    record Touch(Entity entity, double px, double py, double pz) implements Value
    {
        static Touch of(SpellWorld.Touch t)
        {
            return new Touch(Entity.of(t), t.px(), t.py(), t.pz());
        }
    }

    /**
     * 一次撞击事件（只在 {@code on hit} 里以 {@code hit} 这个名字出现）。
     *
     * <p>位置、速度、法向都是本步<b>所有撞击粒子的平均</b>：一百颗粒子同时砸在墙上时，
     * 平均点就是那面墙的中心，比"随便挑第一颗"稳定得多。法向已归一化，
     * 它是"被撞的那一面"朝向外部的方向。</p>
     */
    record Hit(double x, double y, double z, double vx, double vy, double vz,
               double nx, double ny, double nz, String block, int count) implements Value
    {
        static Hit of(SpellCloud.HitFacts f)
        {
            return new Hit(f.x(), f.y(), f.z(), f.vx(), f.vy(), f.vz(),
                    f.nx(), f.ny(), f.nz(), f.block(), f.count());
        }
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
        if (v instanceof Hit)
        {
            return "hit";
        }
        if (v instanceof Str)
        {
            return "str";
        }
        if (v instanceof Entity)
        {
            return "entity";
        }
        if (v instanceof Touch)
        {
            return "touch";
        }
        return "void";
    }
}
