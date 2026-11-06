package com.wizardmagic.api.crucible;

/**
 * 通用坩埚的内腔几何常量。
 *
 * <p>模型与渲染器必须用同一组数值，否则液面会和内壁错开。
 * 单位是模型单位（16 = 1 格）。</p>
 *
 * <p>当前数值对应 {@code wizardmagic:block/crucible/crucible} 这套几何：
 * 墙厚 2 格、内底 y=3、内腔深 11 格。内容模组若换用别的几何，
 * 在自己的方块实体里覆写 {@code cavityBottom()} / {@code cavityHeight()} 即可。</p>
 */
public final class CrucibleGeometry {

    /** 内腔底面 y（已含 0.03 防共面偏移）。 */
    public static final float CAVITY_BOTTOM = 3.03F;

    /** 内腔深度：液面从底面到锅沿可行进的距离。 */
    public static final float CAVITY_HEIGHT = 11.0F;

    /** 液面在水平方向的起始（内缩，避免与内壁共面）。 */
    public static final float FLUID_INSET = 2.03F;

    /** 液面在水平方向的结束。 */
    public static final float FLUID_OUTER = 13.97F;

    /**
     * 一张液面贴图覆盖的模型单位跨度。
     *
     * <p>取液面自身宽度（{@code FLUID_OUTER - FLUID_INSET} = 11.94），
     * 让一张贴图正好铺满液面，波纹不会被裁掉。
     * 这是 BER 程序化绘制的 UV，不受模型 JSON“面尺寸 = UV 尺寸”的约束。</p>
     */
    public static final float FLUID_TEXTURE_SPAN = FLUID_OUTER - FLUID_INSET;

    /** 内腔顶面 y，即满液时的上限。 */
    public static final float CAVITY_TOP = CAVITY_BOTTOM + CAVITY_HEIGHT;

    private CrucibleGeometry() {
    }
}
