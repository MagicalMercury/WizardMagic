package com.wizardmagic.api.crucible;

/**
 * 坩埚的只读状态契约。
 *
 * <p>实现方（通常是内容模组的方块实体上的快照）只回答「现在是什么样」，
 * 不负责算液面、不持有渲染资源、也不认识任何具体方块类。全部方法都必须是无副作用的读取，
 * 允许每帧调用。</p>
 *
 * <p>单位约定：{@link #fluidAmount()} 与 {@link #fluidCapacity()} 用同一单位
 * （mB 或格，由内容模组决定），只有两者之比有意义；{@link #fluidColorArgb()} 使用
 * {@code 0xAARRGGBB}。</p>
 */
public interface CrucibleState {

    /** 当前液体量。 */
    int fluidAmount();

    /** 最大容量。返回 {@code <= 0} 视为无效状态，渲染方按空锅处理。 */
    int fluidCapacity();

    /** 液体颜色，ARGB。 */
    int fluidColorArgb();

    /** 是否处于运行（炼制）状态。呼吸动画由渲染方根据它开关。 */
    boolean running();

    /** 动画时间，单位 tick；内容模组通常直接返回 {@code level.getGameTime()}。 */
    long animationTime();

    /** 是否为空。默认实现：液体量 {@code <= 0}。 */
    default boolean empty() {
        return fluidAmount() <= 0;
    }

    /**
     * 液面比例，夹紧到 {@code [0, 1]}。
     *
     * <p>这是核心模组给的默认算法：{@code amount / capacity}，容量非法时返回 0。
     * 内容模组如果用的是非线性容器（例如横截面积随高度变化），可以覆写本方法。</p>
     */
    default float fillFraction() {
        int capacity = fluidCapacity();
        if (capacity <= 0) {
            return 0.0F;
        }
        float fraction = (float) fluidAmount() / (float) capacity;
        if (fraction < 0.0F) {
            return 0.0F;
        }
        return fraction > 1.0F ? 1.0F : fraction;
    }
}
