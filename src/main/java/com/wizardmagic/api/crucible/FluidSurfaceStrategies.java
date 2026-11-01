package com.wizardmagic.api.crucible;

/**
 * 核心模组提供的液面高度策略。
 *
 * <p>全部实现都是无状态函数，可以安全地跨方块实体、跨维度共用同一个常量。</p>
 */
public final class FluidSurfaceStrategies {

    /** 纯线性：{@code bottom + fraction * height}，不做夹紧。 */
    public static final FluidSurfaceStrategy LINEAR =
            (state, bottom, height) -> bottom + state.fillFraction() * height;

    /**
     * 线性 + 夹紧到内腔范围（核心模组默认策略）。
     *
     * <p>液量超出容量或为负时，液面不会跑出内腔。</p>
     */
    public static final FluidSurfaceStrategy CLAMPED = (state, bottom, height) -> {
        float y = bottom + state.fillFraction() * height;
        float top = bottom + height;
        if (y < bottom) {
            return bottom;
        }
        return y > top ? top : y;
    };

    /**
     * 线性夹紧到自定义比例区间。
     *
     * <p>用来做「空锅仍有薄薄一层挂壁」或「满锅不满到锅沿」这类表现，
     * 例如 {@code clamped(0.0F, 0.92F)}。</p>
     */
    public static FluidSurfaceStrategy clamped(float minFraction, float maxFraction) {
        if (minFraction < 0.0F || maxFraction > 1.0F || minFraction > maxFraction) {
            throw new IllegalArgumentException(
                    "fraction range must satisfy 0 <= min <= max <= 1: " + minFraction + ".." + maxFraction);
        }
        return (state, bottom, height) -> {
            float fraction = state.fillFraction();
            float lo = minFraction;
            float hi = maxFraction;
            if (fraction < lo) {
                fraction = lo;
            } else if (fraction > hi) {
                fraction = hi;
            }
            return bottom + fraction * height;
        };
    }

    private FluidSurfaceStrategies() {
    }
}
