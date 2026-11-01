package com.wizardmagic.api.crucible;

/**
 * 核心模组提供的液体颜色策略与回退。
 *
 * <p>核心模组不认识任何流体或元素体系，因此默认只有一个固定回退色；
 * 真正的颜色来源由内容模组自己实现 {@link FluidColorStrategy}。</p>
 */
public final class FluidColorStrategies {

    /** 回退色：中性紫色（与示例方块的法阵配色一致），不透明。 */
    public static final int FALLBACK_ARGB = 0xFF8B4DD6;

    /** 永远返回回退色的策略。 */
    public static final FluidColorStrategy FALLBACK = state -> FALLBACK_ARGB;

    /**
     * 空安全包装：传 {@code null} 时返回 {@link #FALLBACK}。
     *
     * <p>用于渲染器接受可选策略的场合，避免每个调用点都写一次判空。</p>
     */
    public static FluidColorStrategy orFallback(FluidColorStrategy strategy) {
        return strategy == null ? FALLBACK : strategy;
    }

    private FluidColorStrategies() {
    }
}
