package com.wizardmagic.api.crucible;

/**
 * 渲染器真正消费的解析结果。
 *
 * <p>由 {@link CrucibleRenderResolver} 或 {@link CrucibleRenderCache} 产出，
 * 已经把状态与策略合并成「画的时候需要的数」：渲染器不再接触策略，也不再读原始状态。</p>
 *
 * @param empty         是否为空；为 {@code true} 时渲染器应跳过液面
 * @param fillFraction  液面比例 {@code 0..1}（仅用于调试显示或外部联动）
 * @param surfaceHeight 液面 y，模型单位；由 {@link FluidSurfaceStrategy} 算出
 * @param colorArgb     液体颜色 {@code 0xAARRGGBB}
 * @param running       是否运行中；渲染器据此开关呼吸动画
 */
public record CrucibleRenderState(
        boolean empty,
        float fillFraction,
        float surfaceHeight,
        int colorArgb,
        boolean running) {

    /** 空锅的规范值：液面为 0、回退色、未运行。 */
    public static final CrucibleRenderState EMPTY =
            new CrucibleRenderState(true, 0.0F, 0.0F, FluidColorStrategies.FALLBACK_ARGB, false);

    /** 颜色是否带有效 alpha；渲染器可据此决定是否走半透明通道。 */
    public boolean hasAlpha() {
        return (colorArgb >>> 24) < 0xFF;
    }
}
