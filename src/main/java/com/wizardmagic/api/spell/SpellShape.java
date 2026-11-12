package com.wizardmagic.api.spell;

/**
 * 法术形状：把一个形状"铺"成 {@code count} 个粒子的初始位置。
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li><b>纯函数、确定性</b>：同一 {@code (seed, index, count, params)} 必须得到同一位置，
 *       服务端与客户端调用同一实现，因此轨迹逐位一致。禁止 {@link java.util.Random}、
 *       禁止依赖线程或时间。</li>
 *   <li><b>局部坐标</b>：{@code y} 轴向上，原点在形状中心/底部（具体见各实现），
 *       之后由施法者按 {@code dir} 旋转到世界朝向。</li>
 *   <li><b>不要在这里做运动</b>：形状只决定"出生在哪"，怎么动是六定律的事。</li>
 * </ul>
 *
 * <p>内容模组可以实现本接口并通过 {@link Shapes#register(SpellShape)} 注册自定义形状。</p>
 */
public interface SpellShape
{
    /** 稳定 id（脚本与网络包用它引用形状），小写下划线，例如 {@code helix} */
    String id();

    /** 脚本/命令缺省参数 */
    ShapeParams defaults();

    /**
     * 采样第 {@code index} 个粒子（共 {@code count} 个）的位置，写入 {@code out[0..2]}。
     *
     * @param seed  施法种子（决定抖动），同一 seed 必须可复现
     * @param index 粒子下标，0..count-1
     * @param count 粒子总数（≥1）
     * @param params 形状参数
     * @param out    长度 ≥ 3 的输出数组（局部坐标）
     */
    void sample(int index, int count, ShapeParams params, long seed, double[] out);

    /**
     * 该粒子的**局部旋转中心**（六定律的切向/径向都以它为原点），写入 {@code out[0..2]}。
     *
     * <p>默认返回原点——环、球、圆盘这些绕世界原点转就对了；只有"轴会摆动"的形状
     * （如 {@code helix} 的随机光滑轴）需要覆写，让每个高度绕自己的轴点旋转。</p>
     */
    default void center(int index, int count, ShapeParams params, long seed, double[] out)
    {
        out[0] = 0.0;
        out[1] = 0.0;
        out[2] = 0.0;
    }

    /**
     * 形状是否**随时间演化**。
     *
     * <p>返回 true 时，六定律每步会用 {@link #centerAt} 取当前时刻的旋转中心
     * （内容不推翻六定律：只有"轴"动，粒子仍由速度积分驱动）。</p>
     */
    default boolean animated()
    {
        return false;
    }

    /**
     * 每粒的静态系数（生成时算一次，之后每步复用）。布局由形状自己定义，默认全 0。
     *
     * <p>存在的理由：{@code helix} 的 {@code A(y)} 与 {@code R0(y)} 都含
     * {@code Math.pow(t, 常数)}，而 {@code t} 只跟粒子下标有关——每步每粒重算一次
     * {@code pow} 是纯浪费。默认实现不做事，所以其它形状行为不变。</p>
     */
    default void prepare(int count, ShapeParams params, long seed, double[] out)
    {
    }

    /** 带静态系数的 {@link #centerAt}；默认忽略 {@code prep} */
    default void centerAt(int index, int count, ShapeParams params, long seed,
                          double timeSeconds, double[] prep, double[] out)
    {
        centerAt(index, count, params, seed, timeSeconds, out);
    }

    /** 带静态系数的 {@link #targetRadiusAt}；默认忽略 {@code prep} */
    default double targetRadiusAt(int index, int count, ShapeParams params, long seed,
                                  double timeSeconds, double[] prep)
    {
        return targetRadiusAt(index, count, params, seed, timeSeconds);
    }

    /**
     * 时刻 {@code timeSeconds} 的局部旋转中心（局部坐标）。
     *
     * <p>默认退化到静态 {@link #center}，所以只有显式覆写并让 {@link #animated()} 返回
     * true 的形状才会随时间变化。</p>
     */
    default void centerAt(int index, int count, ShapeParams params, long seed,
                          double timeSeconds, double[] out)
    {
        center(index, count, params, seed, out);
    }

    /**
     * 该粒子的**目标半径**（到当前轴点的距离），用于径向约束力。
     *
     * <p>返回 ≤ 0 表示不约束（默认）。`helix` 返回 `R0(y)`——配合 {@code hold}
     * 的弹簧-阻尼力，形状可以长时间保持圆锥而不是被噪声吹散。</p>
     */
    default double targetRadius(int index, int count, ShapeParams params, long seed)
    {
        return -1.0;
    }

    /**
     * 时刻 {@code timeSeconds} 的目标半径（可随时间脉动，如 {@code R(y,t)}）。
     *
     * <p>默认退化到静态 {@link #targetRadius}；六定律的径向弹簧会追它。</p>
     */
    default double targetRadiusAt(int index, int count, ShapeParams params, long seed,
                                  double timeSeconds)
    {
        return targetRadius(index, count, params, seed);
    }
}
