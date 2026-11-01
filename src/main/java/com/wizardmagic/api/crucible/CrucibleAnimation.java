package com.wizardmagic.api.crucible;

/**
 * 动画时间与相位工具。
 *
 * <p>核心模组只负责「时间 → 相位」这一层，<b>不提供任何曲线</b>：
 * 呼吸、闪烁、脉冲的幅度与波形由渲染器自己决定。接口把时间与表现解耦后，
 * 换动画曲线不需要动状态契约。</p>
 *
 * <p>典型用法（呼吸动画，8 帧 × 4 tick 的循环）：</p>
 * <pre>{@code
 * float phase = CrucibleAnimation.phase(gameTime, partialTick, 32.0F);
 * float breath = 0.5F + 0.5F * (float) Math.sin(phase * Math.PI * 2.0);  // 曲线在渲染器里
 * }</pre>
 */
public final class CrucibleAnimation {

    /**
     * 把时间归一化成 {@code [0, 1)} 相位。
     *
     * @param timeTicks   已经包含插值的时间（tick，可为小数）
     * @param periodTicks 一个循环的 tick 数，必须 {@code > 0}
     * @return 相位；{@code periodTicks <= 0} 时返回 0
     */
    public static float phase(double timeTicks, double periodTicks) {
        if (!(periodTicks > 0.0)) {
            return 0.0F;
        }
        double cycles = timeTicks / periodTicks;
        return (float) (cycles - Math.floor(cycles));
    }

    /** 便捷重载：把整数 tick 与帧插值合成小数时间后再取相位。 */
    public static float phase(long gameTime, float partialTick, float periodTicks) {
        return phase((double) gameTime + (double) partialTick, periodTicks);
    }

    private CrucibleAnimation() {
    }
}
