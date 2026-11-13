package com.wizardmagic.core.spell.script;

import com.wizardmagic.core.spell.SpellCloud;

import java.util.List;

/**
 * 法术对**世界**的效果与查询（伤害、碰到谁）。目前只有这两件事，所以不做更笼统的抽象。
 *
 * <p><b>为什么要有这一层：</b>脚本运行在 {@code core} 里，不能 import Minecraft；
 * 同时「打伤害 / 判定碰到生物」必须是<b>服务端权威</b>——客户端也跑同一份脚本（粒子轨迹要
 * 两端逐位一致），但它绝不能真的打人、也不该拿实体位置去判定（两端实体位置不可能一致）。
 * 所以：服务端给每朵云挂一个实现，客户端与离机自检挂 {@code null}（调用即 no-op /
 * {@link #touches} 返回空表），脚本写的代码一模一样。</p>
 *
 * <p><b>脚本拿不到返回值。</b>{@code hurt()} 在语言里返回 {@code void}：如果脚本能读到
 * "打到了几个"，客户端读到的一定是 0、服务端是真实值，两端立刻分叉。这条是硬约束，
 * 不要为了"方便"让它返回值。</p>
 */
public interface SpellWorld
{
    /**
     * 以 {@code (x,y,z)} 为球心、{@code radius} 为半径，对范围内的生物造成 {@code damage} 点伤害。
     *
     * @return 实际被打到的数量（只给日志/自检用；脚本读不到）
     */
    int hurt(double x, double y, double z, double radius, double damage);

    /**
     * 只打**一个**实体（脚本里 {@code hurt(0, 伤害)}，半径 0 的语义）。
     *
     * <p>默认实现返回 0（= 打不到指定实体）：这样离机自检可以只实现 {@link #hurt} 用 lambda。
     * 真正的服务端实现必须覆盖它。</p>
     *
     * @param entityId 实体 id（{@code touch.entity.id}）
     * @return 是否真的打中了（只给日志/自检用）
     */
    default int hurtEntity(long entityId, double damage)
    {
        return 0;
    }

    /**
     * 这一步碰到这团粒子的生物（服务端判定；客户端实现不存在，所以默认返回空表）。
     *
     * <p>调用点在 {@link SpellCloud} 的步末，而且<b>只在这份脚本有 {@code on touch} 时</b>
     * 才调用——扫包围盒 + 查实体不便宜，没有钩子的法术一个字节都不该花。</p>
     *
     * @return 每项一个实体（同一实体一步只报一次），按发现顺序
     */
    default List<Touch> touches(SpellCloud cloud)
    {
        return List.of();
    }

    /**
     * 一次"碰到生物"的事实。
     *
     * @param id     实体 id（同一实体在一次游戏里唯一，可以拿来比较）
     * @param type   实体类型 id，例如 {@code "minecraft:zombie"}
     * @param x,y,z  实体中心
     * @param health 当前血量（服务端读的，只在 on touch 里给脚本看）
     * @param px,py,pz 第一个落在它身体里的粒子位置（"接触点"）
     */
    record Touch(long id, String type, double x, double y, double z, double health,
                 double px, double py, double pz)
    {
    }
}
