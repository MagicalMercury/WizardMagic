package com.wizardmagic.core.spell;

/**
 * 粒子撞到方块后的行为。
 *
 * <p>策略由元素决定（见 {@link SpellElements#collision(int)}），不写进脚本：
 * {@code on tick} 里禁止读世界，碰撞是框架在脚本之后做的一次后处理，
 * 这样「脚本只描述力」这条约定不被破坏。</p>
 */
public enum CollisionMode
{
    /** 穿过方块：气、雾、虚空、光、雷、动力这类元素 */
    NONE,
    /**
     * 非弹性碰撞（默认）：法向速度反向并保留 {@link #RESTITUTION}（吸收 20%），切向分量不变。
     *
     * <p>不是完全弹性（那会一直弹），也不是完全非弹性（那会贴墙滑）。</p>
     */
    INELASTIC,
    /** 完全停住：法向速度清零，能量按 {@link #STOP_ENERGY_COST} 额外衰减 */
    STOP;

    /** 非弹性碰撞的恢复系数：法向速度保留 80%，即吸收 20% */
    public static final double RESTITUTION = 0.8;
    /** 撞一次扣多少能量（让撞击处自然变暗） */
    public static final double IMPACT_ENERGY_COST = 1.5;
    /** STOP 模式额外扣的能量 */
    public static final double STOP_ENERGY_COST = 6.0;

    /**
     * 撞击速度门槛（格/秒）：低于这个速度的接触<b>不算一次撞击事件</b>。
     *
     * <p>为什么必须有：静止在地面上的粒子每步都会被判定为"贴着方块"（球心在表面上方
     * 半径处、前沿探进方块），速度在 ±0.0x 之间抖。不卡门槛的话，一朵落地的云会
     * <b>每步</b>都触发一次 {@code on hit}，脚本里的"落地炸开"就变成每 50 ms 炸一次。
     * 1 格/秒 ≈ 每步移动 5 cm，肉眼可见的撞击都远高于它。</p>
     */
    public static final double HIT_MIN_SPEED = 1.0;
}
