package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.ShapeParams;

/**
 * {@code emit { ... }} 求值后的云规格。
 *
 * @param elementId 元素表下标
 * @param count     粒子数（已 clamp 到 8..8192，见 SpellConstants.MAX_CLOUD_PARTICLES）
 * @param lifeSec   寿命秒（已 clamp）
 * @param shapeId   形状 id
 * @param params    形状参数
 * @param x         出生点 X
 * @param y         出生点 Y
 * @param z         出生点 Z
 * @param faceX     形状朝向 X（形状本地 +Y 轴指向它；默认 0,1,0 即竖直向上）
 * @param faceY     形状朝向 Y
 * @param faceZ     形状朝向 Z
 * @param inherit   子云是否继承父云脚本（false = 只按元素定律飘，见 {@code emit inherit}）
 */
public record EmitSpec(int elementId, int count, float lifeSec, String shapeId, ShapeParams params,
                       double x, double y, double z,
                       double faceX, double faceY, double faceZ, boolean inherit)
{
    /** 竖直向上（旧行为）：没写 {@code facing} 时用它 */
    public EmitSpec(int elementId, int count, float lifeSec, String shapeId, ShapeParams params,
                    double x, double y, double z)
    {
        this(elementId, count, lifeSec, shapeId, params, x, y, z, 0.0, 1.0, 0.0, true);
    }

    /** 带朝向，继承脚本（旧行为） */
    public EmitSpec(int elementId, int count, float lifeSec, String shapeId, ShapeParams params,
                    double x, double y, double z,
                    double faceX, double faceY, double faceZ)
    {
        this(elementId, count, lifeSec, shapeId, params, x, y, z, faceX, faceY, faceZ, true);
    }
}
