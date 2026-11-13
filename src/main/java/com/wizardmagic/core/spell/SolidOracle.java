package com.wizardmagic.core.spell;

/**
 * 「这个方块坐标是否阻挡粒子」的唯一输入。
 *
 * <p>碰撞层刻意不认识 Minecraft：服务端实现包 {@code Level}，客户端实现也包同一个
 * {@code Level} 类型，离机自检则塞一个纯算术的假实现。这样 {@link ParticleCollider}
 * 可以完全在 JVM 里测成本，而 {@link SpellCloud} 仍然没有 MC 依赖。</p>
 *
 * <p>实现必须<b>无副作用、可重复</b>：同一坐标在同一步里问两次必须得到同一答案
 * （{@link ParticleCollider} 会缓存一次）。</p>
 */
@FunctionalInterface
public interface SolidOracle
{
    /** 该坐标的方块是否阻挡粒子（有碰撞体积） */
    boolean solid(int x, int y, int z);

    /**
     * 该坐标方块的 id（{@code "minecraft:stone"} 这种）；不知道就返回 {@code null}。
     *
     * <p>脚本里以 {@code hit.block} 出现，用来"看撞到了什么"（冰面溅水花、岩浆点燃）。
     * 默认返回 {@code null}：离机自检的假世界不关心方块身份，只有真实世界（服务端/客户端）
     * 才给得出。</p>
     */
    default String blockId(int x, int y, int z)
    {
        return null;
    }

    /**
     * 把长方体 {@code [x0, x0+sx) × [y0, y0+sy) × [z0, z0+sz)} 的实心状态一次填进
     * {@code out}，下标 {@code ((ix*sy)+iy)*sz+iz}，{@code 1} = 实心、{@code 0} = 不挡。
     *
     * <p><b>为什么要这个入口：</b>{@link ParticleCollider} 有一种「整盒一次遍历」的填法
     * （{@code ParticleCollider.Fill#BOX}，默认不开），它一步把整朵云的包围盒填成一张
     * 格位图。逐格调 {@link #solid} 时，真实实现每一格都要重新 {@code getChunk(...)}；
     * 按这个入口写就能整列复用同一个 {@code ChunkAccess}（见 {@code LevelSolidOracle#fill}）。</p>
     *
     * <p>默认实现就是逐格 {@link #solid}——离机自检里的假世界、以及任何只实现单格
     * 接口的实现都不用改。{@code out} 的长度保证 ≥ {@code sx*sy*sz}，实现必须写满每一格。</p>
     */
    default void fill(int x0, int y0, int z0, int sx, int sy, int sz, byte[] out)
    {
        int i = 0;
        for (int ix = 0; ix < sx; ix++)
        {
            int x = x0 + ix;
            for (int iy = 0; iy < sy; iy++)
            {
                int y = y0 + iy;
                for (int iz = 0; iz < sz; iz++)
                {
                    out[i++] = solid(x, y, z0 + iz) ? (byte) 1 : (byte) 0;
                }
            }
        }
    }
}
