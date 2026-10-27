package com.wizardmagic.elements.flow;

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
        long total;
        long max;

        void add(long nanos) {
            samples[pos] = nanos;
            pos = (pos + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
            total += nanos;
            if (nanos > max) {
                max = nanos;
            }
        }

        String summary() {
            if (count == 0) {
                return "n/a";
            }
            long[] copy = Arrays.copyOf(samples, count);
            Arrays.sort(copy);
            long p50 = copy[count / 2];
            long p99 = copy[Math.min(count - 1, (int) (count * 0.99))];
            double mean = total / (double) count / 1_000_000.0;
            return String.format("p50=%.3f p99=%.3f mean=%.3f n=%d ms",
                    p50 / 1_000_000.0, p99 / 1_000_000.0, mean, count);
        }

        void reset() {
            count = 0;
            pos = 0;
            total = 0;
            max = 0;
        }
    }

    private static final Map<String, Stat> STATS = new HashMap<>();

    private ElementBudget() {
    }

    /** 记一次调用耗时（纳秒）。 */
    public static void record(String name, long nanos) {
        STATS.computeIfAbsent(name, k -> new Stat()).add(nanos);
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
    }
}
