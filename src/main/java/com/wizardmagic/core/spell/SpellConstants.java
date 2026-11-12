package com.wizardmagic.core.spell;

/**
 * 法术粒子模拟的全局常量。
 *
 * <p><b>服务端模拟池与客户端渲染池是两回事</b>：</p>
 * <ul>
 *   <li>{@link #MAX_SIM_PARTICLES_PER_LEVEL}：本维度服务端**模拟**的粒子总数，
 *       与能否渲染无关。常驻效果（法阵、结界）即使附近没有玩家也会继续推进。</li>
 *   <li>客户端每帧能画多少由 {@code SparkBatch.MAX_DRAW}（= burst 区 262,144）决定，
 *       只约束「看得见的部分」，不参与服务端准入。</li>
 * </ul>
 */
public final class SpellConstants
{
    /** 单朵云的最少粒子数 */
    public static final int MIN_CLOUD_PARTICLES = 8;
    /** 单朵云的最多粒子数 */
    public static final int MAX_CLOUD_PARTICLES = 4096;
    /** 每个维度同时存在的云数上限 */
    public static final int MAX_CLOUDS_PER_LEVEL = 64;
    /**
     * 每个维度服务端模拟的粒子总数上限。
     *
     * <p>独立于客户端渲染池（后者是 262,144 burst 区）。两者相等只是当前取值，
     * 不是绑定关系：调大它不需要动渲染池，只是「模拟得起、未必看得全」。</p>
     */
    public static final int MAX_SIM_PARTICLES_PER_LEVEL = 262_144;
    /** 每个维度常驻云（法阵 / 结界等）的数量上限，与一次性法术云分开计 */
    public static final int MAX_ANCHORED_CLOUDS_PER_LEVEL = 32;

    /** 物理每多少 tick 推进一步（10 Hz） */
    public static final int STEP_TICKS = 2;
    /** 一步的物理时长（秒） */
    public static final double DT = 0.1;

    /** 调试用的初始散布半径（格）；P2 形状采样会替换掉它 */
    public static final double DEBUG_SPREAD = 0.6;

    /** 玩家进入/离开可见范围时，服务端每多少 tick 核对一次（10 tick = 0.5 s） */
    public static final int VISIBILITY_SYNC_TICKS = 10;

    /**
     * 可见性三态（服务端按玩家距离同步）：
     * <ul>
     *   <li>{@code FULL}：≤ 64 格，完整数据（进入时发快照/生成包）</li>
     *   <li>{@code SEMI}：64–160 格，半可见——只发生成包（不传大快照），客户端按距离淡出</li>
     *   <li>{@code HIDDEN}：&gt; 176 格，发移除包</li>
     * </ul>
     * 离开阈值比进入阈值大，避免在边界来回走时反复同步。
     */
    public static final double VIS_FULL_RANGE = 64.0;
    public static final double VIS_FULL_EXIT = 96.0;
    public static final double VIS_SEMI_RANGE = 160.0;
    public static final double VIS_SEMI_EXIT = 176.0;

    /** 客户端着色器对法术粒子的剔除距离（比半可见上限再远一圈，避免边界硬切） */
    public static final double RENDER_HARD_RANGE = 192.0;

    /** 云的默认寿命（秒） */
    public static final float DEFAULT_LIFE_SEC = 3.0F;

    /** 调试默认阻尼（1/s） */
    public static final float DEFAULT_DRAG = 0.06F;
    /** 调试默认能量参数 */
    public static final float DEFAULT_ENERGY = 60.0F;
    public static final float DEFAULT_ENERGY_DECAY = 0.03F;

    private SpellConstants()
    {
    }
}
