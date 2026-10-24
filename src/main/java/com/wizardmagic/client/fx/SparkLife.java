package com.wizardmagic.client.fx;

/**
 * 光点生命周期策略：每 tick 由池子问一次「这个光点还该活着吗」。
 *
 * <p>把「什么时候消失」从池内部策略（固定 life）变成外部可插拔的钩子：
 * 定时、由元素值决定、由法术状态决定、永不自动死亡（只能 {@code kill}）都可以实现。</p>
 *
 * <p>实现应当是<b>无状态的共享实例</b>（不要为每个光点 new 一个 lambda），
 * 光点只持有引用，不会产生额外分配。</p>
 */
@FunctionalInterface
public interface SparkLife
{
    /**
     * @param spark 光点本身（可读写位置/alpha 等）
     * @param age   已存在的 tick 数
     * @return false 表示本 tick 结束后移除该光点
     */
    boolean tick(SparkBatch.Spark spark, int age);

    /** 永不自动死亡：只能由 {@link SparkBatch#kill} 移除（元素场光点用） */
    static SparkLife persistent() {
        return (spark, age) -> true;
    }

    /** 按 tick 数存活 */
    static SparkLife timed(int maxAge) {
        return (spark, age) -> age < maxAge;
    }

    /** 存活若干 tick 后淡出，返回 false 时移除（可选的光点自淡出策略） */
    static SparkLife fading(int maxAge, int fadeTicks) {
        int fadeStart = Math.max(0, maxAge - fadeTicks);
        return (spark, age) -> {
            if (age >= maxAge) {
                return false;
            }
            if (age >= fadeStart) {
                spark.alphaMul = Math.max(0.0F, 1.0F - (float) (age - fadeStart) / Math.max(1, fadeTicks));
            }
            return true;
        };
    }
}
