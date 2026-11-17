package com.wizardmagic.api.spell;

/**
 * 查表三角函数，与 Minecraft {@code net.minecraft.util.Mth.sin/cos} 的实现一致。
 *
 * <p>为什么不用 {@link Math#sin}：{@code Math.sin} 走 libm，单次约 15–25 ns；
 * 法术每步每粒要算 3 次（轴心 cos/sin + 目标半径 sin），3000 粒一步就是 0.1–0.2 ms。
 * MC 自己做了一张 65536 项的 float 表，查表只要几 ns。</p>
 *
 * <p>为什么不直接调 {@code Mth}：{@code api/spell} 与语言层必须能脱离 Minecraft
 * 离机自检（{@code verify/spell}），所以这里复制同一张表、同一套索引。<b>数值与
 * {@code Mth} 完全一致</b>（都走 float 运算），不是近似替代。代价是 ~256 KB 静态内存
 * 和一次 65536 次 {@code Math.sin} 的类初始化。</p>
 *
 * <p>精度：float（约 7 位有效数字，绝对误差 ≤ 1e-5），比 double 低。对粒子位置来说
 * 远小于状态哈希的 1/4096 格量化，肉眼看不出；两端用同一张表，所以逐位一致。</p>
 */
public final class SpellMath
{
    /** 2π 一整圈切成 65536 份 */
    private static final float[] SIN = new float[65536];

    /** MC 用的弧度→表下标系数：65536 / 2π */
    private static final float INDEX_SCALE = 10430.378F;

    static
    {
        for (int i = 0; i < 65536; i++)
        {
            // StrictMath.sin 是 JLS 保证"跨平台逐位相同"的 fdlibm；Math.sin 是平台 libm。
            // 表一旦不一致，所有形状采样都会跟着漂，所以这里必须用 StrictMath。
            SIN[i] = (float) StrictMath.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
    }

    private SpellMath()
    {
    }

    public static double sin(double radians)
    {
        return SIN[(int) ((float) radians * INDEX_SCALE) & 65535];
    }

    public static double cos(double radians)
    {
        return SIN[(int) ((float) radians * INDEX_SCALE + 16384.0F) & 65535];
    }

    /**
     * 整张表的快照（原生内核要用同一张表，否则 sin/cos 的末位会分叉）。
     *
     * <p>只有 {@code NativeVm} 初始化时取一次；256 KB 的一次性拷贝，不进热路径。</p>
     */
    public static float[] sinTableSnapshot()
    {
        return SIN.clone();
    }
}
