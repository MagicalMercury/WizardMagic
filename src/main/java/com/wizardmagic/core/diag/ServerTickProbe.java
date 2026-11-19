package com.wizardmagic.core.diag;

import com.wizardmagic.WizardMagic;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 服务端 tick 计时探针：**把"每秒一次的 ~100 ms 数据缺口"拆开看**。
 *
 * <p>要回答的问题只有一个：那个缺口是"某个 tick 里干了重活"，还是"tick 之间被挂住"
 * （GC / CPU 被抢 / 休眠过头）？两者的对策完全不同，而现有的日志（`mirror probe`）
 * 只能看到**抵达间隔**，看不到归因。</p>
 *
 * <p>做法：在 {@link TickEvent.ServerTickEvent} 的 START/END 各打一个时间点，于是</p>
 * <ul>
 *   <li>{@code intick} = 这一 tick 真正干活的时间（START→END，含原版与所有 mod）；</li>
 *   <li>{@code gap} = 上一 tick 结束到下一 tick 开始（休眠 + 被挂住的时间）。</li>
 * </ul>
 * <p>若 {@code gap} 大而 {@code intick} 小 ⇒ 不是谁在 tick 里算得慢，而是线程没被调度/被暂停
 * （GC、CPU 抢占、电源管理），这时去砍算法是白干。反之才轮到砍具体任务。</p>
 *
 * <p>同时把几个已知的周期性任务的最大耗时并进来（{@link #note}）：
 * {@code spellStep}（法术步，10 Hz）、{@code fluid1Hz}（流体求解，1 Hz）、
 * {@code chunkQueue}/{@code elem20}（元素场队列与 20 tick 的登记，1 Hz）。</p>
 *
 * <p>打印策略：默认**只在有法术步的那一秒打印**（不刷无意义的空闲日志）；
 * {@code -Dwizardmagic.tickprobe=1} 强制每秒打印，用来对照空闲态。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class ServerTickProbe
{
    /** 强制每秒打印（否则只在有法术在跑的那一秒打印） */
    private static final boolean ALWAYS = Boolean.getBoolean("wizardmagic.tickprobe");

    /** 具名任务在本窗口内的 [最大耗时 ns, 次数] */
    private static final Map<String, long[]> JOBS = new LinkedHashMap<>(8);

    private static long beginNanos;
    private static long lastEndNanos;
    private static long windowStart;

    private static int ticks;
    private static long tickMin = Long.MAX_VALUE;
    private static long tickMax;
    private static long tickSum;
    private static long gapMin = Long.MAX_VALUE;
    private static long gapMax;
    private static long gapSum;
    private static long spellSteps;

    private ServerTickProbe()
    {
    }

    /** 记一次某个具名任务的耗时（纳秒）。只在服务端主线程调用。 */
    public static void note(String name, long nanos)
    {
        long[] j = JOBS.computeIfAbsent(name, k -> new long[2]);
        if (nanos > j[0])
        {
            j[0] = nanos;
        }
        j[1]++;
    }

    /** 只计次数（不记耗时），打印成 {@code name n=..}。用于"吞吐有没有被限流吃掉"这类核对。 */
    public static void count(String name)
    {
        JOBS.computeIfAbsent(name, k -> new long[2])[1]++;
    }

    /** 法术步（10 Hz）：兼作"这一秒要不要打印"的开关。 */
    public static void noteSpellStep(long nanos)
    {
        spellSteps++;
        note("spellStep", nanos);
    }

    /**
     * START 阶段：**最早**执行（HIGHEST），这样 {@code begin} 之前没有别人的 tick 工作。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTickStart(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.START)
        {
            return;
        }
        long now = System.nanoTime();
        beginNanos = now;
        if (lastEndNanos != 0L)
        {
            long gap = now - lastEndNanos;
            gapMin = Math.min(gapMin, gap);
            gapMax = Math.max(gapMax, gap);
            gapSum += gap;
        }
    }

    /**
     * END 阶段：**最后**执行（LOWEST）。
     *
     * <p>这一点很关键：别的 mod 的 tick 处理（例如 {@code ElementTickHandler} 里的元素场扩散）
     * 也是 END 阶段的监听器。如果本探针先执行，它们耗掉的时间就会被算到**下一个窗口的 gap** 里，
     * 于是出现"intick 只有 6 ms、可 elemFlow 却 131 ms"这种自相矛盾的数字（第一版就是这样，
     * 差点把归因搞反）。用 LOWEST 保证 {@code intick} 包含所有 mod 的 tick 内工作。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickEnd(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        long now = System.nanoTime();
        if (beginNanos != 0L)
        {
            long inTick = now - beginNanos;
            tickMin = Math.min(tickMin, inTick);
            tickMax = Math.max(tickMax, inTick);
            tickSum += inTick;
            ticks++;
        }
        lastEndNanos = now;
        if (windowStart == 0L)
        {
            windowStart = now;
            return;
        }
        if (now - windowStart >= 1_000_000_000L)
        {
            flush(now);
        }
    }

    private static void flush(long now)
    {
        if (ticks > 0 && (ALWAYS || spellSteps > 0))
        {
            StringBuilder sb = new StringBuilder(192);
            sb.append(String.format(Locale.ROOT, "[wizardmagic] srv tick: n=%d gap %s | intick %s",
                    ticks, triple(gapMin, gapSum, gapMax, ticks), triple(tickMin, tickSum, tickMax, ticks)));
            for (Map.Entry<String, long[]> e : JOBS.entrySet())
            {
                // 1 Hz 的任务平均没意义，只报最大 + 次数；纯计数口径只报次数
                if (e.getValue()[0] > 0L)
                {
                    sb.append(String.format(Locale.ROOT, " | %s max=%.2fms n=%d",
                            e.getKey(), e.getValue()[0] / 1.0E6, e.getValue()[1]));
                }
                else
                {
                    sb.append(String.format(Locale.ROOT, " | %s n=%d", e.getKey(), e.getValue()[1]));
                }
            }
            com.mojang.logging.LogUtils.getLogger().info(sb.toString());
        }
        ticks = 0;
        tickMin = Long.MAX_VALUE;
        tickMax = 0L;
        tickSum = 0L;
        gapMin = Long.MAX_VALUE;
        gapMax = 0L;
        gapSum = 0L;
        spellSteps = 0L;
        for (long[] v : JOBS.values())
        {
            v[0] = 0L;
            v[1] = 0L;
        }
        windowStart = now;
    }

    private static String triple(long min, long sum, long max, int n)
    {
        if (n <= 0 || min == Long.MAX_VALUE)
        {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f/%.1f/%.1fms",
                min / 1.0E6, sum / (double) n / 1.0E6, max / 1.0E6);
    }
}
