package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.ShapeParams;

/**
 * {@code emit { ... }} 求值后的云规格。
 *
 * @param elementId 元素表下标
 * @param count     粒子数（已 clamp 到 8..4096）
 * @param lifeSec   寿命秒（已 clamp）
 * @param shapeId   形状 id
 * @param params    形状参数
 */
public record EmitSpec(int elementId, int count, float lifeSec, String shapeId, ShapeParams params,
                       double x, double y, double z)
{
}
