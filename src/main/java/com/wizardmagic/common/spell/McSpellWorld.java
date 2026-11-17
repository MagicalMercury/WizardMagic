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

    // ---- 粒子占用格表（每步按需重建；本对象每朵云一份，服务端主线程串行使用） ----
    /** 格子边长（格）：1 格，和方块对齐 */
    private static final double CELL = 1.0;
    /** 格表上限：超过就退回逐实体全扫（避免一朵大云长期占着几 MB 的格表） */
    private static final int MAX_GRID_CELLS = 1 << 18;
    private int[] cellStart = new int[0];
    private int[] cellCursor = new int[0];
    private int[] cellItem = new int[0];
    private int[] particleCell = new int[0];
    private int originX;
    private int originY;
    private int originZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;

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
        // 粒子占用格表：每步建一次 O(n)，之后每个实体只查它身体覆盖的少数格子。
        // 原来是对每个候选实体都全扫一遍粒子（O(实体数 × n)），生物一多就是几十万次/步。
        // 只有 1–2 个候选时，建表的 O(n) 反而比直接扫更贵，走全扫。
        boolean grid = candidates.size() > 2
                && buildGrid(cloud, loX, loY, loZ, hiX, hiY, hiZ, pr, n);
        List<Touch> out = new ArrayList<>(candidates.size());
        for (LivingEntity target : candidates)
        {
            if (skip(target))
            {
                continue;
            }
            AABB body = target.getBoundingBox().inflate(pr);
            int i = grid ? firstParticleIn(body, cloud) : firstParticleInBrute(body, cloud);
            if (i < 0)
            {
                continue;
            }
            Vec3 c = target.position();
            out.add(new Touch(target.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                    c.x, c.y, c.z, target.getHealth(),
                    cloud.x(i), cloud.y(i), cloud.z(i)));
        }
        return out;
    }

    /** 按当前粒子包围盒建占用格表；太大（> 1M 格）返回 false，调用方退回全扫 */
    private boolean buildGrid(SpellCloud cloud, double loX, double loY, double loZ,
                              double hiX, double hiY, double hiZ, double pr, int n)
    {
        int x0 = floor(loX - pr);
        int y0 = floor(loY - pr);
        int z0 = floor(loZ - pr);
        int x1 = floor(hiX + pr);
        int y1 = floor(hiY + pr);
        int z1 = floor(hiZ + pr);
        long sx = (long) x1 - x0 + 1L;
        long sy = (long) y1 - y0 + 1L;
        long sz = (long) z1 - z0 + 1L;
        long volume = sx * sy * sz;
        if (volume <= 0L || volume > MAX_GRID_CELLS)
        {
            return false;
        }
        int cells = (int) volume;
        if (cellStart.length < cells + 1)
        {
            cellStart = new int[cells + 1];
            cellCursor = new int[cells + 1];
        }
        if (cellItem.length < n)
        {
            cellItem = new int[n];
            particleCell = new int[n];
        }
        originX = x0;
        originY = y0;
        originZ = z0;
        sizeX = (int) sx;
        sizeY = (int) sy;
        sizeZ = (int) sz;

        // 计数排序：O(n + 格数)
        java.util.Arrays.fill(cellStart, 0, cells + 1, 0);
        for (int i = 0; i < n; i++)
        {
            int c = cellOf(cloud.x(i), cloud.y(i), cloud.z(i));
            particleCell[i] = c;
            cellStart[c + 1]++;
        }
        for (int k = 0; k < cells; k++)
        {
            cellStart[k + 1] += cellStart[k];
        }
        System.arraycopy(cellStart, 0, cellCursor, 0, cells);
        for (int i = 0; i < n; i++)
        {
            cellItem[cellCursor[particleCell[i]]++] = i;
        }
        return true;
    }

    /** 实体包围盒里下标最小的粒子（与"逐粒从头扫、取第一个"语义一致） */
    private int firstParticleIn(AABB body, SpellCloud cloud)
    {
        int ix0 = Math.max(0, floor(body.minX) - originX);
        int ix1 = Math.min(sizeX - 1, floor(body.maxX) - originX);
        int iy0 = Math.max(0, floor(body.minY) - originY);
        int iy1 = Math.min(sizeY - 1, floor(body.maxY) - originY);
        int iz0 = Math.max(0, floor(body.minZ) - originZ);
        int iz1 = Math.min(sizeZ - 1, floor(body.maxZ) - originZ);
        int best = Integer.MAX_VALUE;
        for (int ix = ix0; ix <= ix1; ix++)
        {
            for (int iy = iy0; iy <= iy1; iy++)
            {
                for (int iz = iz0; iz <= iz1; iz++)
                {
                    int c = (ix * sizeY + iy) * sizeZ + iz;
                    int end = cellStart[c + 1];
                    for (int k = cellStart[c]; k < end; k++)
                    {
                        int i = cellItem[k];
                        if (i < best && body.contains(cloud.x(i), cloud.y(i), cloud.z(i)))
                        {
                            best = i;
                            if (best == 0)
                            {
                                return 0;
                            }
                        }
                    }
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static int firstParticleInBrute(AABB body, SpellCloud cloud)
    {
        int n = cloud.count;
        for (int i = 0; i < n; i++)
        {
            if (body.contains(cloud.x(i), cloud.y(i), cloud.z(i)))
            {
                return i;
            }
        }
        return -1;
    }

    private int cellOf(double x, double y, double z)
    {
        int ix = clampCell(floor(x) - originX, sizeX);
        int iy = clampCell(floor(y) - originY, sizeY);
        int iz = clampCell(floor(z) - originZ, sizeZ);
        return (ix * sizeY + iy) * sizeZ + iz;
    }

    private static int clampCell(int v, int size)
    {
        return v < 0 ? 0 : (v >= size ? size - 1 : v);
    }

    /** 比 {@code (int) Math.floor(v)} 快，且对负数正确 */
    private static int floor(double v)
    {
        int i = (int) v;
        return v < i ? i - 1 : i;
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
