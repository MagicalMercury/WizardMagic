package com.wizardmagic.api.crucible;

/**
 * 液面高度策略：把只读状态换算成模型空间的液面 y。
 *
 * <p>几何参数（内腔底面与深度）由内容模组按自己的模型传入，核心模组不知道模型长什么样。
 * 默认实现见 {@link FluidSurfaceStrategies}。</p>
 */
@FunctionalInterface
public interface FluidSurfaceStrategy {

    /**
     * @param state        只读状态
     * @param cavityBottom 内腔底面在方块模型中的 y（模型单位，0..16）
     * @param cavityHeight 内腔总深度（模型单位，必须 {@code > 0}）
     * @return 液面所在的 y（模型单位）
     */
    float surfaceHeight(CrucibleState state, float cavityBottom, float cavityHeight);
}
