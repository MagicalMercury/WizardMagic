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
    /**
     * 单朵云的最多粒子数。
     *
     * <p>默认 8192；压测时可用 {@code -Dwizardmagic.spell.maxParticles=1048576} 抬高
     * （只影响单朵云的上限，准入判断仍然按这个值走）。</p>
     *
     * <p>2026-11-14 从 4096 提到 8192：一朵云吃 8192 粒时 SoA 约 0.95 MB，
     * 快照包 {@code count*7} 个 double 约 459 KB（只发一次，仍在 SimpleChannel 上限内）。</p>
     */
    public static final int MAX_CLOUD_PARTICLES =
            Integer.getInteger("wizardmagic.spell.maxParticles", 8192);
    /** 每个维度同时存在的云数上限 */
    public static final int MAX_CLOUDS_PER_LEVEL = 128;
    /**
     * 每个维度服务端模拟的粒子总数上限 = 512 K。
     *
     * <p>独立于客户端渲染池（后者是 262,144 burst 区）。两者相等只是过去的取值，
     * 不是绑定关系：调大它<b>不需要动渲染池</b>，只是「模拟得起、未必看得全」——
     * 客户端每帧只画前 262,144 个，多出来的粒子在服务端照常推进。</p>
     *
     * <p>2026-11-14 从 262,144 翻倍到 524,288，同时单朵上限 4096 → 8192。三个数的关系：
     * {@code 64 × 8192 = 128 × 4096 = 524,288}，所以满池既可以是一朵朵大云、也可以是两倍的
     * 小云。代价是内存（每粒约 116 B SoA → 满池约 58 MB 服务端）和每步 CPU
     * （满池实测见 {@code gradlew spellMillion}）。</p>
     */
    public static final int MAX_SIM_PARTICLES_PER_LEVEL = 524_288;
    /** 每个维度常驻云（法阵 / 结界等）的数量上限，与一次性法术云分开计 */
    public static final int MAX_ANCHORED_CLOUDS_PER_LEVEL = 32;

    /** 物理每多少 tick 推进一步（10 Hz） */
    public static final int STEP_TICKS = 2;
    /** 一步的物理时长（秒） */
    public static final double DT = 0.1;

    /** 云的寿命下限（秒） */
    public static final double MIN_LIFE_SEC = 0.1;
    /**
     * 云的寿命上限（秒）= **30 分钟**。
     *
     * <p>以前是 60 秒，作者写 {@code life: 240} 会被静默夹成 60（"我写了 4 分钟，怎么 1 分钟就没了"）。
     * 放开之后长驻效果可以按分钟写。真要"永不消失"用常驻云（{@code persistent}，
     * `/spell debug anchor` 那条路），而不是写一个超大的 life——寿命再长也是每步都在推进粒子。</p>
     */
    public static final double MAX_LIFE_SEC = 1800.0;
    /** 把寿命夹到合法区间；非有限值退回下限 */
    public static double clampLifeSec(double seconds)
    {
        if (!Double.isFinite(seconds))
        {
            return MIN_LIFE_SEC;
        }
        return Math.max(MIN_LIFE_SEC, Math.min(MAX_LIFE_SEC, seconds));
    }

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
