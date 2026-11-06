package com.wizardmagic.core.elements;

import com.wizardmagic.api.elements.ElementTypes;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块对魔力场的通透策略。
 *
 * <p>实现这个接口的方块会被元素场特殊对待：它的每一格都有一个通透度，
 * 扩散与平流经过这些格子时会按比例打折，而不是像普通方块那样完全透明。</p>
 *
 * <p>三个典型取值：</p>
 * <ul>
 *   <li>{@link #BLOCKED}（0）：完全阻断。该格内部元素恒为 0，且不传递任何通量；
 *       这是「魔力拒绝方块」的行为。</li>
 *   <li>{@link #OPEN}（127）：完全通透，与普通方块一致（默认，不需要记录）。</li>
 *   <li>中间值：部分通透。例如 64 表示每步只让约一半的元素通过，
 *       内部仍允许存在元素。</li>
 * </ul>
 *
 * <p>当前实现是「每格一个值，四种元素共用」；如果将来需要按元素区分，
 * 只需在 {@link ElementField} 里把系数数组从 {@code byte[]} 升级为 {@code int[]}，
 * 本接口的签名不用变。</p>
 */
public interface ManaBarrier {

    /** 完全阻断：内部元素恒为 0，不传递通量。 */
    int BLOCKED = 0;

    /** 完全通透：与普通方块一致。 */
    int OPEN = 127;

    /**
     * 该方块格对魔力元素的通透度，取值 0..127。
     *
     * @param state 方块状态
     * @param type  元素类型（当前实现四元素共用同一个值，参数为将来的按元素区分预留）
     */
    int permeability(BlockState state, ElementTypes type);

    /**
     * 四元素共用的整格通透度。
     *
     * <p>默认实现取四种元素通透度的最小值——只要有一种被完全挡住，
     * 整格就按阻断处理，这是保守且可预测的语义。</p>
     */
    default int permeability(BlockState state) {
        int min = OPEN;
        for (ElementTypes type : ElementTypes.values()) {
            min = Math.min(min, permeability(state, type));
        }
        return min;
    }
}
