package com.wizardmagic.core.spell;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 粒子的数据并行：把 {@code [0, count)} 切成固定大小的块，线程池 + 调用线程动态领块。
 *
 * <p><b>为什么可以并行</b>：每颗粒子的一步只读写它自己的下标，形状采样、随机数、
 * 噪声全部由 {@code (seed, index, step)} 决定，粒子之间没有任何交互。所以怎么切、
 * 谁先谁后都不影响结果——与单线程<strong>逐位相同</strong>，不需要归约、不需要同步、不会漂移。</p>
 *
 * <p><b>不能并行的部分</b>：碰撞要读世界（{@code getBlockState} 不是线程安全的），
 * 所以碰撞永远留在调用线程、在所有段 join 之后做。好在它只有个位数百分比。</p>
 *
 * <p>线程数默认 {@code 核数 - 1}（给服务端主线程留一个），可用
 * {@code -Dwizardmagic.spell.threads=N} 覆盖，{@code 0} = 关闭并行。
 * 单人游戏里服务端线程和客户端渲染线程共用这一个池，所以两边的等待在很大程度上是
 * "对方在用 CPU"，不是浪费。</p>
 */
public final class SpellParallel
{
    /** 一段粒子 */
    public interface RangeTask
    {
        void run(int from, int to);
    }

    private static final int THREADS = Math.max(0,
            Math.min(16, Integer.getInteger("wizardmagic.spell.threads",
                    Runtime.getRuntime().availableProcessors() - 1)));

    /** 上一次 dispatch 的统计：总块数 / 调用线程自己干的块数（诊断"池子到底帮了多少"） */
    private static volatile int lastChunks;
    private static volatile int lastCallerChunks;
    private static volatile long lastNanos;

    public static int lastChunks()
    {
        return lastChunks;
    }

    public static int lastCallerChunks()
    {
        return lastCallerChunks;
    }

    public static long lastNanos()
    {
        return lastNanos;
    }

    /**
     * 上一次 dispatch 里**所有 worker 实际干活的时间之和**（纳秒）。
     *
     * <p>和 {@link #lastNanos()}（墙钟）一起用就能算出"有效并行度"和"单粒 CPU 成本"。
     * 这两个数一分开，"步进慢"到底是"并行没起来"还是"每颗粒子本来就贵"就一眼可见。</p>
     */
    public static long lastCpuNanos()
    {
        return lastCpuNanos;
    }

    private static volatile long lastCpuNanos;
    private static final java.util.concurrent.atomic.LongAdder CPU_NANOS =
            new java.util.concurrent.atomic.LongAdder();

    /** 调用线程干了多少比例的块（1.0 = 完全没并行） */
    public static double lastCallerShare()
    {
        int c = lastChunks;
        return c <= 0 ? 1.0 : (double) lastCallerChunks / c;
    }

    /** 段的最小规模：太小的云分给线程池，唤醒成本比算得还贵（本机一次分发 ~50 µs） */
    private static final int MIN_CHUNK = 1024;
    /** 每个线程大约领多少块（块越多越能自动均衡，但调度开销也越大） */
    private static final int CHUNKS_PER_THREAD = 8;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ExecutorService POOL = THREADS <= 0 ? null : Executors.newFixedThreadPool(
            THREADS, runnable ->
            {
                Thread thread = new Thread(runnable, "wizardmagic-spell-" + COUNTER.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            });

    private SpellParallel()
    {
    }

    public static boolean enabled()
    {
        return POOL != null;
    }

    public static int threads()
    {
        return THREADS;
    }

    /**
     * 把 {@code [0, count)} 切成固定大小的块，**谁空谁领**（原子计数器），调用线程也参与。
     *
     * <p><b>为什么不是静态切段</b>：以前是"按线程数等分成 P 段、调用线程跑第一段"。
     * 一批粒子里每颗粒子的成本并不一样（不同法术/元素/脚本），等分只是"粒子数等分"，
     * 不是"工作量等分"；快的段跑完就在 join 上等着。spark 采样里服务端线程
     * {@code FutureTask.get} 的 park 占 13.2%、客户端 10.1%，就是这段等待。</p>
     *
     * <p><b>为什么动态也不影响确定性</b>：每颗粒子只读写自己的下标，谁跑哪一块与结果无关；
     * 块边界只是"哪些下标一起跑"，写回顺序不影响任何数值。</p>
     *
     * <p>任意一段抛异常：等所有段结束后把第一个异常原样抛给调用线程，
     * 由 {@link SpellCloud#step()} 记成 {@code broken}。</p>
     */
    public static void forEachRange(int count, RangeTask task)
    {
        if (count <= 0)
        {
            return;
        }
        int threads = THREADS;
        if (POOL == null || threads <= 0 || count <= MIN_CHUNK)
        {
            task.run(0, count);
            lastChunks = 1;
            lastCallerChunks = 1;
            return;
        }
        // 每线程大约领 CHUNKS_PER_THREAD 块：块太小调度开销大，块太大又会失衡
        int chunk = Math.max(MIN_CHUNK, count / (threads * CHUNKS_PER_THREAD));
        int chunks = (count + chunk - 1) / chunk;
        AtomicInteger next = new AtomicInteger();
        AtomicInteger callerChunks = new AtomicInteger();
        final long t0 = System.nanoTime();
        Runnable worker = () ->
        {
            int k;
            long cpu = 0L;
            while ((k = next.getAndIncrement()) < chunks)
            {
                int from = k * chunk;
                long t = System.nanoTime();
                task.run(from, Math.min(count, from + chunk));
                cpu += System.nanoTime() - t;
            }
            CPU_NANOS.add(cpu);
        };
        // 调用线程自己的那份：同一段代码，但要单独记账（诊断用）
        Runnable caller = () ->
        {
            int k;
            long cpu = 0L;
            while ((k = next.getAndIncrement()) < chunks)
            {
                callerChunks.incrementAndGet();
                int from = k * chunk;
                long t = System.nanoTime();
                task.run(from, Math.min(count, from + chunk));
                cpu += System.nanoTime() - t;
            }
            CPU_NANOS.add(cpu);
        };

        Future<?>[] futures = new Future<?>[threads];
        int submitted = 0;
        for (int i = 0; i < threads; i++)
        {
            futures[submitted++] = POOL.submit(worker);
        }

        // 调用线程也领块，不干等
        RuntimeException error = null;
        try
        {
            caller.run();
        }
        catch (RuntimeException e)
        {
            error = e;
        }
        for (int i = 0; i < submitted; i++)
        {
            try
            {
                futures[i].get();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (ExecutionException e)
            {
                if (error == null)
                {
                    error = e.getCause() instanceof RuntimeException r
                            ? r : new RuntimeException(e.getCause());
                }
            }
        }
        lastNanos = System.nanoTime() - t0;
        lastChunks = chunks;
        lastCallerChunks = callerChunks.get();
        long cpu = CPU_NANOS.sum();
        CPU_NANOS.reset();
        lastCpuNanos = cpu;
        if (error != null)
        {
            throw error;
        }
    }
}
