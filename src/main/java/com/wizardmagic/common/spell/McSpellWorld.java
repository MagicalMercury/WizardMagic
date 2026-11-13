package com.wizardmagic.common.spell;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.script.SpellWorld;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link SpellWorld} 的服务端实现：把脚本的 {@code hurt(...)} / {@code on touch} 变成真实的
 * 实体查询与 {@link LivingEntity#hurt} 调用。
 *
 * <p><b>为什么只能在这里发生</b>：客户端也跑同一份脚本（粒子轨迹要两端逐位一致），但它
 * 拿到的是 {@code null} 出口；而且两端的实体位置永远不可能完全一致（客户端有插值/延迟），
 * 在客户端判定"碰到了谁"必然分叉。所以伤害与接触判定都是**服务端独占**。</p>
 *
 * <p><b>跳过施法者自己</b>：法术落在脚下是常事（火球砸地），连自己一起打会变成自杀。</p>
 *
 * <p><b>伤害类型固定 {@code magic}</b>：吃抗性、无视护甲；将来要做元素对应时改这一处。</p>
 */
public final class McSpellWorld implements SpellWorld
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WizardMagic.MODID + "/spell");

    private final ServerLevel level;
    /** 施法者（跳过；{@code null} = 无主伤害，谁都打） */
    private final UUID owner;

    public McSpellWorld(ServerLevel level, UUID owner)
    {
        this.level = level;
        this.owner = owner;
    }

    @Override
    public int hurt(double x, double y, double z, double radius, double damage)
    {
        AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        int hit = 0;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box))
        {
            if (skip(target))
            {
                continue;
            }
            // 真按球算：AABB 是粗筛，这里补一次距离判定（不然方盒角落也会被打到）
            if (target.distanceToSqr(x, y, z) > radius * radius)
            {
                continue;
            }
            if (target.hurt(level.damageSources().magic(), (float) damage))
            {
                hit++;
            }
        }
        debug("hurt at %.2f/%.2f/%.2f r=%.1f dmg=%.1f → %d 个目标", x, y, z, radius, damage, hit);
        return hit;
    }

    @Override
    public int hurtEntity(long entityId, double damage)
    {
        net.minecraft.world.entity.Entity entity = level.getEntity((int) entityId);
        if (!(entity instanceof LivingEntity target) || skip(target))
        {
            return 0;
        }
        boolean hit = target.hurt(level.damageSources().magic(), (float) damage);
        debug("hurt entity=%d dmg=%.1f → %s", entityId, damage, hit);
        return hit ? 1 : 0;
    }

    /**
     * 本步碰到这团粒子的生物：先按云的包围盒查一次实体，再逐个体看"有没有粒子在它身体里"。
     *
     * <p>只在这份脚本有 {@code on touch} 时才会被调用（见 {@link SpellCloud}），
     * 所以没有钩子的法术完全不花这个扫描成本。</p>
     */
    @Override
    public List<Touch> touches(SpellCloud cloud)
    {
        int n = cloud.count;
        if (n <= 0)
        {
            return List.of();
        }
        double loX = Double.MAX_VALUE;
        double loY = Double.MAX_VALUE;
        double loZ = Double.MAX_VALUE;
        double hiX = -Double.MAX_VALUE;
        double hiY = -Double.MAX_VALUE;
        double hiZ = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            double px = cloud.x(i);
            double py = cloud.y(i);
            double pz = cloud.z(i);
            if (px < loX) loX = px;
            if (px > hiX) hiX = px;
            if (py < loY) loY = py;
            if (py > hiY) hiY = py;
            if (pz < loZ) loZ = pz;
            if (pz > hiZ) hiZ = pz;
        }
        double pr = cloud.particleRadius();
        AABB region = new AABB(loX - pr, loY - pr, loZ - pr, hiX + pr, hiY + pr, hiZ + pr);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, region);
        if (candidates.isEmpty())
        {
            return List.of();
        }
        List<Touch> out = new ArrayList<>(candidates.size());
        for (LivingEntity target : candidates)
        {
            if (skip(target))
            {
                continue;
            }
            AABB body = target.getBoundingBox().inflate(pr);
            for (int i = 0; i < n; i++)
            {
                double px = cloud.x(i);
                double py = cloud.y(i);
                double pz = cloud.z(i);
                if (body.contains(px, py, pz))
                {
                    Vec3 c = target.position();
                    out.add(new Touch(target.getId(),
                            BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                            c.x, c.y, c.z, target.getHealth(), px, py, pz));
                    break;
                }
            }
        }
        return out;
    }

    private boolean skip(LivingEntity target)
    {
        return (owner != null && owner.equals(target.getUUID()))
                || target.isSpectator() || !target.isAlive();
    }

    private static void debug(String fmt, Object... args)
    {
        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("[wizardmagic] spell " + String.format(java.util.Locale.ROOT, fmt, args));
        }
    }
}
