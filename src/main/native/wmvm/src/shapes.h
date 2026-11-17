/*
 * 形状采样：`SpellShape.centerAt` / `targetRadiusAt` 的原生移植。
 *
 * 为什么只有 helix 一支：在 `Shapes.java` 里，只有 `HelixShape` 覆写了
 * `centerAt` / `targetRadiusAt`；其它形状走接口默认实现，局部轴心是零向量、
 * targetRadius 是 -1。也就是说"轴心 = origin、目标半径 = -1"就是默认形状的精确语义，
 * 不必再为 ten 种形状各写一遍。
 *
 * 为什么不需要 fdlibm 的 e_pow.c：helix 的两个 pow(t, ·) 只跟下标有关（静态），
 * Java 侧在云构造时算好放进 `shapePrep`（2 / 粒），每步只读。这里读同一份数组。
 * sin/cos 走 sintable.h 的同一张表，所以整条链是逐位一致的。
 *
 * 与 Java 的求值顺序严格对齐（浮点结合律不成立，顺序错了末位就分叉）：
 *   A(y)  = A_b + (A_t − A_b)·(y/H)^q                     （AXIS_BASE = 0，直接省掉）
 *   phase = ky·y − ω·t + 2π·unit(seed, 0, 7)
 *   R(y,t)= R0(y)·[1 + ε_r·sin(k_r·y − ω_r·t + 2π·unit(seed, 0, 9))]
 *   R0(y) = R_t·(0.02 + (1 − 0.02)·prep)
 */
#ifndef WMVM_SHAPES_H
#define WMVM_SHAPES_H

#include "wmvm.h"

#include "jmath.h"
#include "noise.h"
#include "sintable.h"

namespace wmvm
{

enum : int
{
    /** 轴心就是 origin，targetR = -1（除 helix 外的全部形状） */
    SHAPE_KIND_NONE = 0,
    SHAPE_KIND_HELIX = 1
};

/** `Math.PI * 2.0`：×2 只动指数位，逐位无损，所以与 Java 的写法完全等价 */
static constexpr double kTwoPi = 6.283185307179586;

/** 当前时刻的轴心（世界坐标），对应 `SpellCloud.axisAt(i, time, out)` */
static inline void shape_axis(const WmvmBatch& b, int i, double* out)
{
    double lx = 0.0;
    double ly = 0.0;
    double lz = 0.0;

    if (b.shape_kind == SHAPE_KIND_HELIX && b.shape_prep != nullptr && b.shape_count > 0)
    {
        const double t = (static_cast<double>(i) + 0.5) / static_cast<double>(b.shape_count);
        ly = t * b.shape_height;
        // A(y) = A_b + (A_t − A_b)·(y/H)^q，其中 A_b = 0 ⇒ 只剩 A_t·prep
        const double axisRadius = b.shape_length * b.shape_prep[i * 2 + 1];
        const double ky = kTwoPi * b.shape_frequency / jmax(0.01, b.shape_height);
        const double phi = kTwoPi * random_unit(b.seed, 0, 7);
        const double phase = ky * ly - b.shape_turns * b.time + phi;
        lx = axisRadius * table_cos(phase);
        lz = axisRadius * table_sin(phase);
    }

    if (b.tilted != 0)
    {
        const double x = lx;
        const double y = ly;
        const double z = lz;
        const double* m = b.tilt;
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[3] * x + m[4] * y + m[5] * z;
        out[2] = m[6] * x + m[7] * y + m[8] * z;
    }
    else
    {
        out[0] = lx;
        out[1] = ly;
        out[2] = lz;
    }
    out[0] += b.origin_x;
    out[1] += b.origin_y;
    out[2] += b.origin_z;
}

/** 当前时刻的目标半径；≤ 0 表示不约束。对应 `SpellCloud.targetRadius(i, time)` */
static inline double shape_target(const WmvmBatch& b, int i)
{
    if (b.shape_kind != SHAPE_KIND_HELIX || b.shape_prep == nullptr || b.shape_count <= 0)
    {
        return -1.0;
    }

    const double t = (static_cast<double>(i) + 0.5) / static_cast<double>(b.shape_count);
    // RADIUS_BASE_RATIO = 0.02，写成算式而不是 0.98：0.02 不是精确二进制数，舍入要跟 Java 一样
    const double r0 = b.shape_radius * (0.02 + (1.0 - 0.02) * b.shape_prep[i * 2]);
    if (b.shape_radius_pulse <= 0.0)
    {
        return r0;
    }

    const double y = t * b.shape_height;
    const double kr = kTwoPi * b.shape_radius_wave / jmax(0.01, b.shape_height);
    const double phi = kTwoPi * random_unit(b.seed, 0, 9);
    const double phase = kr * y - b.shape_radius_speed * b.time + phi;
    const double r = r0 * (1.0 + b.shape_radius_pulse * table_sin(phase));
    return jmax(0.02, r);
}

/** 每粒稳定的 [0,1) 随机数，对应 `SpellCloud.random01(i)`（stream = 91） */
static inline double shape_rnd(const WmvmBatch& b, int i)
{
    return random_unit(b.seed, i, 91);
}

}  // namespace wmvm

#endif  // WMVM_SHAPES_H
