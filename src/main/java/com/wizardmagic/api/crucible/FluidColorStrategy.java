package com.wizardmagic.api.crucible;

/**
 * 液体颜色策略：把只读状态换算成 ARGB 颜色。
 *
 * <p>颜色来源（流体类型、元素组合、NBT、配置……）完全由内容模组决定；
 * 核心模组只规定签名与回退行为，见 {@link FluidColorStrategies}。</p>
 *
 * <p><b>调用频率</b>：只在 {@link CrucibleRenderCache} 判定失效时调用，
 * 不是每帧调用。因此这里可以放相对昂贵、仍不算「每帧重查询」的逻辑。</p>
 */
@FunctionalInterface
public interface FluidColorStrategy {

    /** 液体颜色，{@code 0xAARRGGBB}。 */
    int colorArgb(CrucibleState state);
}
