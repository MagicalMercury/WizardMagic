package com.wizardmagic.api.crucible;

/**
 * 把只读状态与策略合并成 {@link CrucibleRenderState}。
 *
 * <p>这是核心模组给的默认解析路径，本身不缓存、不持有时序；
 * 需要每帧调用时请改用 {@link CrucibleRenderCache}，避免重复调用策略。</p>
 */
public final class CrucibleRenderResolver {

    /**
     * @param state        只读状态
     * @param surface      液面策略，{@code null} 时用 {@link FluidSurfaceStrategies#CLAMPED}
     * @param color        颜色策略，{@code null} 时用 {@link FluidColorStrategies#FALLBACK}
     * @param cavityBottom 内腔底面 y（模型单位）
     * @param cavityHeight 内腔深度（模型单位）
     */
    public static CrucibleRenderState resolve(
            CrucibleState state,
            FluidSurfaceStrategy surface,
            FluidColorStrategy color,
            float cavityBottom,
            float cavityHeight) {

        if (cavityHeight <= 0.0F) {
            throw new IllegalArgumentException("cavityHeight must be > 0: " + cavityHeight);
        }
        FluidSurfaceStrategy surfaceStrategy = surface == null ? FluidSurfaceStrategies.CLAMPED : surface;
        FluidColorStrategy colorStrategy = FluidColorStrategies.orFallback(color);

        boolean empty = state.empty();
        float fraction = state.fillFraction();
        float surfaceY = surfaceStrategy.surfaceHeight(state, cavityBottom, cavityHeight);
        int argb = colorStrategy.colorArgb(state);

        return new CrucibleRenderState(empty, fraction, surfaceY, argb, state.running());
    }

    private CrucibleRenderResolver() {
    }
}
