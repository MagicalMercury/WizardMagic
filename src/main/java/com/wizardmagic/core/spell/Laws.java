package com.wizardmagic.core.spell;

/**
 * 基础定律：**元素浮力 + 线性阻尼 + 噪声扩散 + 能量衰减 + 积分**。
 *
 * <p>只描述「一团元素在空中怎么散」这一件通用的事，参数只有
 * {@code drag / energyInit / energyDecay} 与元素表里的 gravity / volatility。</p>
 *
 * <p>旋涡、上升气流、形状约束、消散爆发这些**表现**已经从引擎里移出：它们写在
 * {@code .spell} 里（例如 {@code firestorm.spell}），由 {@link com.wizardmagic.core.spell.script.FastProgram}
 * 或 {@link com.wizardmagic.core.spell.script.Interpreter} 执行。这里不再有
 * 「为某个法术定制的常量」。</p>
 *
 * <p>确定性要求：只用 {@code + - * /} 与 {@code sqrt}，不用三角函数与幂函数，
 * 服务端与客户端在同一 seed 下逐位一致。</p>
 */
public final class Laws
{
    /** 速度上限（格/秒）：防止任何参数组合下数值爆掉 */
    private static final double SPEED_CAP = 30.0;
    private static final double SPEED_CAP_SQ = SPEED_CAP * SPEED_CAP;

    private Laws()
    {
    }

    public static void step(SpellCloud c)
    {
        final double dt = SpellConstants.DT;
        final double gravityAccel = -SpellElements.gravity(c.elementId) * 9.8 * dt;
        final double volatility = SpellElements.volatility(c.elementId);
        final double damping = Math.max(0.0, 1.0 - c.drag * dt);
        final double decay = c.energyDecay * dt;
        final long seed = c.castSeed;
        final int step = c.ageSteps();

        for (int i = 0; i < c.count; i++)
        {
            double px = c.x[i];
            double py = c.y[i];
            double pz = c.z[i];
            double vx = c.vx[i];
            double vy = c.vy[i];
            double vz = c.vz[i];

            // 1 元素浮力（glyph 表：正 = 下沉，负 = 上浮）
            vy += gravityAccel;

            // 2 阻尼
            vx *= damping;
            vy *= damping;
            vz *= damping;

            // 3 扩散（三个方向用不同的噪声流）
            vx += SpellNoise.value3(seed, px * 0.5, py * 0.5, pz * 0.5, step * 3) * volatility * dt;
            vy += SpellNoise.value3(seed, px * 0.5, py * 0.5, pz * 0.5, step * 3 + 1) * volatility * dt;
            vz += SpellNoise.value3(seed, px * 0.5, py * 0.5, pz * 0.5, step * 3 + 2) * volatility * dt;

            // 4 速度上限
            double speedSq = vx * vx + vy * vy + vz * vz;
            if (speedSq > SPEED_CAP_SQ)
            {
                double scale = SPEED_CAP / Math.sqrt(speedSq);
                vx *= scale;
                vy *= scale;
                vz *= scale;
            }

            // 5 积分
            c.x[i] = px + vx * dt;
            c.y[i] = py + vy * dt;
            c.z[i] = pz + vz * dt;
            c.vx[i] = vx;
            c.vy[i] = vy;
            c.vz[i] = vz;

            // 6 能量衰减
            float energy = c.energy[i] - (float) decay;
            c.energy[i] = energy < 0.0F ? 0.0F : energy;
        }
    }
}
