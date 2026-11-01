package com.wizardmagic.api.crucible;

/**
 * 每帧调用的渲染状态缓存。
 *
 * <p>解决的问题：渲染器每帧都要 `surfaceHeight` 和 `colorArgb`，但这两个值只在
 * 液量、容量、颜色或运行状态变化时才需要重算。缓存用「签名比较」判定是否失效，
 * 命中时不调用任何策略，只做四次字段读取。</p>
 *
 * <p>典型用法（一个方块实体一个缓存实例）：</p>
 * <pre>{@code
 * CrucibleRenderState rs = cache.update(state, surface, color, CAVITY_BOTTOM, CAVITY_HEIGHT);
 * if (!rs.empty()) {
 *     // 用 rs.surfaceHeight() / rs.colorArgb() 绘制
 * }
 * }</pre>
 *
 * <p>内容模组在液体量、颜色、运行状态写入时调用 {@link #invalidate()}，
 * 可以让下一次 {@link #update} 必然重算，避免依赖签名比较捕捉不到的隐藏变化
 * （例如颜色来自会变的配置对象）。</p>
 *
 * <p><b>非线程安全</b>：缓存属于渲染线程上的单个方块实体，不要跨线程共享。</p>
 */
public final class CrucibleRenderCache {

    private boolean valid;
    private int lastAmount;
    private int lastCapacity;
    private int lastColorArgb;
    private boolean lastRunning;
    private CrucibleRenderState cached = CrucibleRenderState.EMPTY;

    /** 主动失效：下一次 {@link #update} 必然重新计算。 */
    public void invalidate() {
        valid = false;
    }

    /**
     * 每帧调用一次。签名不变时返回上次结果，不调用任何策略。
     *
     * @param state        只读状态
     * @param surface      液面策略，{@code null} 时用 {@link FluidSurfaceStrategies#CLAMPED}
     * @param color        颜色策略，{@code null} 时用 {@link FluidColorStrategies#FALLBACK}
     * @param cavityBottom 内腔底面 y（模型单位）
     * @param cavityHeight 内腔深度（模型单位）
     */
    public CrucibleRenderState update(
            CrucibleState state,
            FluidSurfaceStrategy surface,
            FluidColorStrategy color,
            float cavityBottom,
            float cavityHeight) {

        int amount = state.fluidAmount();
        int capacity = state.fluidCapacity();
        int argb = state.fluidColorArgb();
        boolean running = state.running();

        if (valid
                && amount == lastAmount
                && capacity == lastCapacity
                && argb == lastColorArgb
                && running == lastRunning) {
            return cached;
        }

        cached = CrucibleRenderResolver.resolve(state, surface, color, cavityBottom, cavityHeight);
        lastAmount = amount;
        lastCapacity = capacity;
        lastColorArgb = argb;
        lastRunning = running;
        valid = true;
        return cached;
    }

    /** 最近一次解析结果；从未 {@link #update} 过时返回 {@link CrucibleRenderState#EMPTY}。 */
    public CrucibleRenderState current() {
        return cached;
    }

    /** 当前缓存是否有效（至少成功解析过一次且未失效）。 */
    public boolean valid() {
        return valid;
    }
}
