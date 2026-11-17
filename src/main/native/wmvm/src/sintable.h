/*
 * sin/cos 查表（与 Java 的 SpellMath 同一张表、同一套 float 运算）。
 *
 * 表由 Java 侧在 NativeVm 初始化时灌进来（65536 项 float）。刻意不用 libm：
 * SpellMath 的建表用的是 StrictMath.sin，而 libm 的 sin 在末位上并不保证一致。
 *
 * 单独拆成头文件是因为 vm-inl.h（内核）和 shapes.h（形状采样）都要用它，
 * 而 shapes.h 又不能 include vm-inl.h。
 */
#ifndef WMVM_SINTABLE_H
#define WMVM_SINTABLE_H

#include "jmath.h"

namespace wmvm
{

/** 由 Java 侧灌进来的 65536 项 float 表（与 SpellMath 同一张） */
extern const float* g_sin_table;
extern int g_sin_count;

static inline double table_sin(double radians)
{
    const float scaled = static_cast<float>(radians) * 10430.378f;
    const int idx = jd2i(static_cast<double>(scaled)) & 65535;
    return static_cast<double>(g_sin_table[idx]);
}

static inline double table_cos(double radians)
{
    const float scaled = static_cast<float>(radians) * 10430.378f + 16384.0f;
    const int idx = jd2i(static_cast<double>(scaled)) & 65535;
    return static_cast<double>(g_sin_table[idx]);
}

}  // namespace wmvm

#endif  // WMVM_SINTABLE_H
