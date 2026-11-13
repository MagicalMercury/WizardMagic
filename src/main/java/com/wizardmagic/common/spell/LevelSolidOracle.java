package com.wizardmagic.common.spell;

import com.wizardmagic.core.spell.CollisionMode;
import com.wizardmagic.core.spell.ParticleCollider;
import com.wizardmagic.core.spell.SolidOracle;
import com.wizardmagic.core.spell.SpellElements;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/**
 * 把 {@link LevelReader} 包成 {@link SolidOracle}。服务端与客户端用同一份实现：
 *
 * <ul>
 *   <li>{@code ServerLevel}：读服务端真值；</li>
 *   <li>{@code ClientLevel}：读客户端本地方块（区块数据本来就是服务端同步下来的）。</li>
 * </ul>
 *
 * <p><b>为什么要先查区块再查方块</b>：{@code Level#getBlockState} 在区块未加载时会
 * <b>同步加载</b>区块。粒子飘出模拟范围时不该因为一次碰撞查询把区块拉进来，所以这里
 * 用 {@code getChunk(..., requireChunk=false)}，拿不到就按空气处理（服务端那边云本身
 * 已经过 {@code hasChunk} 判定，正常情况下不会走到）。</p>
 */
public final class LevelSolidOracle implements SolidOracle
{
    private final LevelReader level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LevelSolidOracle(LevelReader level)
    {
        this.level = level;
    }

    /**
     * 按元素策略造一个碰撞器；该元素可以穿方块（或没有世界）时返回 {@code null}。
     *
     * <p>服务端与客户端都走这一个入口——两端各读各的世界，算法完全相同。</p>
     */
    public static ParticleCollider collideFor(LevelReader level, int elementId)
    {
        CollisionMode mode = SpellElements.collision(elementId);
        if (level == null || mode == CollisionMode.NONE)
        {
            return null;
        }
        return new ParticleCollider(new LevelSolidOracle(level), mode);
    }

    @Override
    public boolean solid(int x, int y, int z)
    {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight())
        {
            return false;
        }
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null)
        {
            return false;
        }
        BlockState state = chunk.getBlockState(pos.set(x, y, z));
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public String blockId(int x, int y, int z)
    {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight())
        {
            return null;
        }
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null)
        {
            return null;
        }
        BlockState state = chunk.getBlockState(pos.set(x, y, z));
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * 按列（固定 x、z，走 y）扫长方体：<b>一列只查一次区块</b>。
     *
     * <p>逐格 {@link #solid} 的耗时大头是 {@code getChunk(cx, cz, ...)}——它落到
     * {@code ChunkSource#getChunk} 的哈希表里。同一列 16～20 格查的是同一个区块对象，
     * 这里把它提到 y 循环外面，等于把「每格一次区查找」变成「每列一次」。</p>
     *
     * <p>区块拿不到（没加载）或 y 出界时整列直接填 0，连方块状态都不问。</p>
     */
    @Override
    public void fill(int x0, int y0, int z0, int sx, int sy, int sz, byte[] out)
    {
        final int minY = level.getMinBuildHeight();
        final int maxY = level.getMaxBuildHeight();
        for (int ix = 0; ix < sx; ix++)
        {
            final int x = x0 + ix;
            final int columnBase = ix * sy * sz;
            for (int iz = 0; iz < sz; iz++)
            {
                final int z = z0 + iz;
                final int base = columnBase + iz;
                ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                if (chunk == null)
                {
                    for (int iy = 0; iy < sy; iy++)
                    {
                        out[base + iy * sz] = 0;
                    }
                    continue;
                }
                int yLo = Math.max(y0, minY);
                int yHi = Math.min(y0 + sy, maxY);
                for (int iy = 0; iy < sy; iy++)
                {
                    int y = y0 + iy;
                    byte v = 0;
                    if (y >= yLo && y < yHi)
                    {
                        BlockState state = chunk.getBlockState(pos.set(x, y, z));
                        v = state.getCollisionShape(level, pos).isEmpty() ? (byte) 0 : (byte) 1;
                    }
                    out[base + iy * sz] = v;
                }
            }
        }
    }
}
