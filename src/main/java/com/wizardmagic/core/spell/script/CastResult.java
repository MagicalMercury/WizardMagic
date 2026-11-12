package com.wizardmagic.core.spell.script;

import java.util.List;
import java.util.Map;

/**
 * {@code on cast} 的结果。
 *
 * @param emits 要生成的云（0..8 朵）
 * @param vars  云级变量（cast 作用域顶层 let/param），随生成包下发
 */
public record CastResult(List<EmitSpec> emits, Map<String, Value> vars)
{
}
