package com.wizardmagic.core.elements.flow;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 元素系统的统一耗时记账。
 *
 * <p>与旧的「单次均值」不同：这里保留窗口内的全部样本，可以给出 p50 / p99，
 * 避免把常态与病态混成一个数字（CHANGE §8 口径）。
 *
 * <p>单线程使用（元素模拟全程在服务端主线程），不加同步。
 */
public final class ElementBudget {

    private static final int WINDOW = 256;

    private static final class Stat {
        final long[] samples = new long[WINDOW];
        int count;
        int pos;

        void add(long nanos) {
            samples[pos] = nanos;
            pos = (pos + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
        }

        String summary() {
            if (count == 0) {
                return "n/a";
            }
            long[] copy = Arrays.copyOf(samples, count);
            // 均值必须按**窗口内**的样本算：以前用累加的 total / 封顶的 count，
            // 窗口滑满之后 total 继续涨、count 不再涨，均值会越来越大（实测
            // adv 的 p50=0.281 ms 却打出 mean=74.480 ms）。
            long sum = 0L;
            for (int i = 0; i < count; i++) {
                sum += copy[i];
            }
            Arrays.sort(copy);
            long p50 = copy[count / 2];
            long p99 = copy[Math.min(count - 1, (int) (count * 0.99))];
            double mean = sum / (double) count / 1_000_000.0;
            return String.format("p50=%.3f p99=%.3f mean=%.3f n=%d ms",
                    p50 / 1_000_000.0, p99 / 1_000_000.0, mean, count);
        }

        void reset() {
            count = 0;
            pos = 0;
        }
    }

    private static final Map<String, Stat> STATS = new HashMap<>();

    /** 只计数的口径（不需要 p50 的，比如"走了短路几次"） */
    private static final Map<String, long[]> COUNTS = new HashMap<>();

    private ElementBudget() {
    }

    /** 记一次调用耗时（纳秒）。单线程使用：元素模拟全程在服务端主线程 */
    public static void record(String name, long nanos) {
        STATS.computeIfAbsent(name, k -> new Stat()).add(nanos);
    }

    /** 记一次"发生"（不带耗时）。 */
    public static void bump(String name) {
        COUNTS.computeIfAbsent(name, k -> new long[1])[0]++;
    }

    /** 取某个计数口径的累计次数。 */
    public static long count(String name) {
        long[] c = COUNTS.get(name);
        return c == null ? 0L : c[0];
    }

    /** 取某口径的 p50/p99 短格式（用于聊天面板，避免刷屏）。 */
    public static String shortSummary(String name) {
        Stat stat = STATS.get(name);
        if (stat == null || stat.count == 0) {
            return "n/a";
        }
        long[] copy = Arrays.copyOf(stat.samples, stat.count);
        Arrays.sort(copy);
        long p50 = copy[stat.count / 2];
        long p99 = copy[Math.min(stat.count - 1, (int) (stat.count * 0.99))];
        return String.format("%.2f/%.2f", p50 / 1_000_000.0, p99 / 1_000_000.0);
    }

    /** 取某口径的 p50/p99/mean/n 摘要。 */
    public static String summary(String name) {
        Stat stat = STATS.get(name);
        return stat == null ? "n/a" : stat.summary();
    }

    public static void reset() {
        STATS.clear();
        COUNTS.clear();
    }
}
