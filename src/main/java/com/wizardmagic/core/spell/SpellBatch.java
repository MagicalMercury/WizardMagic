package com.wizardmagic.core.spell;

import java.util.ArrayList;
import java.util.List;

/**
 * 一批云的一步推进：**三段式**，把并行调度从「每朵云一次」压成「整批一次」。
 *
 * <pre>
 * 阶段 1（调用线程）  每朵云 beginStep()：标量段、外提量、自由变量、解释器兜底都在这
 * 阶段 2（工作线程）  所有云的 [0,count) 首尾相接成一条，一次 forEachRange 走完
 * 阶段 3（调用线程）  finishStep()：写回云级变量、方块碰撞、年龄
 * </pre>
 *
 * <p><b>为什么对：</b>每颗粒子只碰自己的下标，形状采样/随机/噪声全由 {@code (seed, index, step)}
 * 决定，所以切段边界怎么划都不影响结果——与单线程逐位相同。碰撞读世界，永远留在调用线程。</p>
 *
 * <p><b>为什么要整批：</b>实测 64 朵 × 4096 粒，每朵各自 dispatch 只有 2.8× 加速，
 * 而同样 26 万粒放在一朵云里能到 5.5×；差的全是 64 次 fork/join 的调度开销。</p>
 *
 * <p>与 Minecraft 无关：所以离机自检和压测探针能直接调这个类。</p>
 */
public final class SpellBatch
{
    /**
     * 每个调用线程一份缓冲。
     *
     * <p><b>必须是线程私有的</b>：单人游戏里服务端线程和客户端渲染线程会同时调进来
     * （一朵云在服务端、一份镜像在客户端），共享的 static 列表会被对方清空，
     * 结果就是两端哈希对不上（`compare=MISMATCH`）。</p>
     */
    private static final class Scratch
    {
        final List<SpellCloud> started = new ArrayList<>();
        final List<SpellCloud> ranges = new ArrayList<>();
        int[] starts = new int[16];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private SpellBatch()
    {
    }

    /** 把一批云推进一步；空列表直接返回 */
    public static void step(List<SpellCloud> clouds)
    {
        Scratch s = SCRATCH.get();
        final List<SpellCloud> started = s.started;
        final List<SpellCloud> ranges = s.ranges;
        started.clear();
        ranges.clear();
        if (s.starts.length < clouds.size() + 1)
        {
            s.starts = new int[clouds.size() + 1];
        }
        final int[] starts = s.starts;

        // 阶段 1：准备（串行）
        int total = 0;
        for (int k = 0; k < clouds.size(); k++)
        {
            SpellCloud cloud = clouds.get(k);
            started.add(cloud);
            if (cloud.beginStep())
            {
                starts[ranges.size()] = total;
                ranges.add(cloud);
                total += cloud.count;
            }
        }

        // 阶段 2：一次并行调度（只有真的有条目时才进池子）
        if (total > 0)
        {
            starts[ranges.size()] = total;
            final int count = ranges.size();
            final int[] prefix = starts;
            SpellParallel.forEachRange(total, (from, to) ->
            {
                int ci = 0;
                for (int pos = from; pos < to; )
                {
                    while (ci < count && pos >= prefix[ci + 1])
                    {
                        ci++;
                    }
                    SpellCloud c = ranges.get(ci);
                    int local = pos - prefix[ci];
                    int len = Math.min(to - pos, c.count - local);
                    c.stepRange(local, local + len);
                    pos += len;
                }
            });
        }

        // 阶段 3：收尾（串行）
        for (int k = 0; k < started.size(); k++)
        {
            started.get(k).finishStep();
        }
        started.clear();
        ranges.clear();
    }
}
