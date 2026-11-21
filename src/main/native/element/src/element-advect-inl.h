/*
 * 元素平流的共享辅助（标量内核与 SIMD 内核共用）。
 *
 * <p>为什么要单独成头：{@code element-advect-simd.cpp} 要用 Highway 的
 * {@code foreach_target.h}，那个宏会把**整个文件**按目标重编 N 遍；共享辅助若留在同一个
 * .cpp 里就会重复定义、编不过。放这里以后两边各得一份内部链接的副本，互不影响。</p>
 *
 * <p><b>数值契约不变</b>：这些函数与 Java 实现逐位一致（同一套 double 运算顺序 +
 * 同一条 {@code Math.round}），SIMD 内核也是调它们、只是把"4 个通道"放进 lane 并行。</p>
 */
#ifndef WMVM_ELEMENT_ADVECT_INL_H
#define WMVM_ELEMENT_ADVECT_INL_H

#include <cmath>
#include <cstdint>

#include "element-advect.h"
#include "jmath.h"

namespace wmvm
{
namespace element
{

namespace
{

/** Java `Math.floorDiv(a, b)`（b > 0） */
inline int floor_div(int a, int b)
{
    int q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0)))
    {
        --q;
    }
    return q;
}

inline int clamp_int(int v, int lo, int hi)
{
    return v < lo ? lo : (v > hi ? hi : v);
}

inline bool same_word(int a, int b)
{
    return a == b;
}

/** `ElementField.permeability(index)` 的逐位等价版本 */
inline int permeability_at(const Input& in, int index)
{
    if (in.has_barrier == 0)
    {
        return BARRIER_OPEN;
    }
    if ((in.blocked[index >> 6] & (static_cast<uint64_t>(1) << (index & 63))) != 0)
    {
        return BARRIER_BLOCKED;
    }
    return in.permeability == nullptr ? BARRIER_OPEN
                                      : static_cast<int>(in.permeability[index]);
}

/**
 * 这一节覆盖的粗格里有没有非零速度。
 *
 * 与 `FluidField.hasFlowIn` 同一口径：采样点会被 clamp 到 `[0, N-1]` 并用格 i 与 i+1，
 * 所以两端各放宽一格，再把两个端点夹回 `[0, N-1]`（区间单调，夹完仍 i0 <= i1）。
 */
bool has_flow_in(const Input& in)
{
    const int span = EDGE;
    const int i0 = clamp_int(floor_div(in.section_x - in.fluid_origin_x, FLUID_CELL), 0, FLUID_N - 1);
    const int i1 = clamp_int(floor_div(in.section_x + span - 1 - in.fluid_origin_x, FLUID_CELL) + 1,
            0, FLUID_N - 1);
    const int j0 = clamp_int(floor_div(in.section_y - in.fluid_origin_y, FLUID_CELL), 0, FLUID_N - 1);
    const int j1 = clamp_int(floor_div(in.section_y + span - 1 - in.fluid_origin_y, FLUID_CELL) + 1,
            0, FLUID_N - 1);
    const int k0 = clamp_int(floor_div(in.section_z - in.fluid_origin_z, FLUID_CELL), 0, FLUID_N - 1);
    const int k1 = clamp_int(floor_div(in.section_z + span - 1 - in.fluid_origin_z, FLUID_CELL) + 1,
            0, FLUID_N - 1);
    for (int k = k0; k <= k1; ++k)
    {
        for (int j = j0; j <= j1; ++j)
        {
            const int row = FLUID_N * (j + FLUID_N * k);
            for (int i = i0; i <= i1; ++i)
            {
                const int c = i + row;
                if (in.fluid_u[c] != 0.0f || in.fluid_v[c] != 0.0f || in.fluid_w[c] != 0.0f)
                {
                    return true;
                }
            }
        }
    }
    return false;
}

/** 与 `FluidField.tri` 同一条式子（float 运算，顺序不变） */
inline float tri(const float* f, int i, int j, int k, int i1, int j1, int k1,
        float fx, float fy, float fz, float g0, float g1, float g2)
{
    const int row0 = FLUID_N * (j + FLUID_N * k);
    const int row1 = FLUID_N * (j1 + FLUID_N * k);
    const int row2 = FLUID_N * (j + FLUID_N * k1);
    const int row3 = FLUID_N * (j1 + FLUID_N * k1);
    const int p000 = i + row0;
    const int p100 = i1 + row0;
    const int p010 = i + row1;
    const int p110 = i1 + row1;
    const int p001 = i + row2;
    const int p101 = i1 + row2;
    const int p011 = i + row3;
    const int p111 = i1 + row3;
    const float w00 = g0 * g1;
    const float w10 = fx * g1;
    const float w01 = g0 * fy;
    const float w11 = fx * fy;
    return (f[p000] * w00 + f[p100] * w10 + f[p010] * w01 + f[p110] * w11) * g2
            + (f[p001] * w00 + f[p101] * w10 + f[p011] * w01 + f[p111] * w11) * fz;
}

/** 与 `FluidField.sample` 同一条式子；域外按 clamp 处理 */
inline void sample_velocity(const Input& in, double wx, double wy, double wz, double* out)
{
    const double nLimit = static_cast<double>(FLUID_N) - 1.0E-6;
    double gx = (wx - in.fluid_origin_x) / FLUID_CELL;
    double gy = (wy - in.fluid_origin_y) / FLUID_CELL;
    double gz = (wz - in.fluid_origin_z) / FLUID_CELL;
    gx = gx < 0.0 ? 0.0 : (gx > nLimit ? nLimit : gx);
    gy = gy < 0.0 ? 0.0 : (gy > nLimit ? nLimit : gy);
    gz = gz < 0.0 ? 0.0 : (gz > nLimit ? nLimit : gz);
    const int i = static_cast<int>(gx);
    const int j = static_cast<int>(gy);
    const int k = static_cast<int>(gz);
    const float fx = static_cast<float>(gx - i);
    const float fy = static_cast<float>(gy - j);
    const float fz = static_cast<float>(gz - k);
    const int i1 = i + 1 < FLUID_N ? i + 1 : FLUID_N - 1;
    const int j1 = j + 1 < FLUID_N ? j + 1 : FLUID_N - 1;
    const int k1 = k + 1 < FLUID_N ? k + 1 : FLUID_N - 1;
    const float g0 = 1.0f - fx;
    const float g1 = 1.0f - fy;
    const float g2 = 1.0f - fz;
    out[0] = static_cast<double>(tri(in.fluid_u, i, j, k, i1, j1, k1, fx, fy, fz, g0, g1, g2));
    out[1] = static_cast<double>(tri(in.fluid_v, i, j, k, i1, j1, k1, fx, fy, fz, g0, g1, g2));
    out[2] = static_cast<double>(tri(in.fluid_w, i, j, k, i1, j1, k1, fx, fy, fz, g0, g1, g2));
}

/** 与 `ElementAdvection.sampleWord` 同一条式子（4 通道三线性 + Math.round） */
inline int sample_word(const int* words, double x, double y, double z)
{
    const double cx = x < 0.0 ? 0.0 : (x > 15.0 ? 15.0 : x);
    const double cy = y < 0.0 ? 0.0 : (y > 15.0 ? 15.0 : y);
    const double cz = z < 0.0 ? 0.0 : (z > 15.0 ? 15.0 : z);
    int i = static_cast<int>(cx);
    int j = static_cast<int>(cy);
    int k = static_cast<int>(cz);
    if (i > 15)
    {
        i = 15;
    }
    if (j > 15)
    {
        j = 15;
    }
    if (k > 15)
    {
        k = 15;
    }
    const double fx = cx - i;
    const double fy = cy - j;
    const double fz = cz - k;
    const int i1 = i + 1 < 16 ? i + 1 : 15;
    const int j1 = j + 1 < 16 ? j + 1 : 15;
    const int k1 = k + 1 < 16 ? k + 1 : 15;
    const double g0 = 1.0 - fx;
    const double g1 = 1.0 - fy;
    const double g2 = 1.0 - fz;
    const double w00 = g0 * g1;
    const double w10 = fx * g1;
    const double w01 = g0 * fy;
    const double w11 = fx * fy;

    const int p000 = i + 16 * (j + 16 * k);
    const int p100 = i1 + 16 * (j + 16 * k);
    const int p010 = i + 16 * (j1 + 16 * k);
    const int p110 = i1 + 16 * (j1 + 16 * k);
    const int p001 = i + 16 * (j + 16 * k1);
    const int p101 = i1 + 16 * (j + 16 * k1);
    const int p011 = i + 16 * (j1 + 16 * k1);
    const int p111 = i1 + 16 * (j1 + 16 * k1);

    int out = 0;
    for (int t = 0; t < 4; ++t)
    {
        const int shift = t * 8;
        const double v = static_cast<double>((static_cast<unsigned>(words[p000]) >> shift) & MASK) * w00
                + static_cast<double>((static_cast<unsigned>(words[p100]) >> shift) & MASK) * w10
                + static_cast<double>((static_cast<unsigned>(words[p010]) >> shift) & MASK) * w01
                + static_cast<double>((static_cast<unsigned>(words[p110]) >> shift) & MASK) * w11;
        const double v2 = static_cast<double>((static_cast<unsigned>(words[p001]) >> shift) & MASK) * w00
                + static_cast<double>((static_cast<unsigned>(words[p101]) >> shift) & MASK) * w10
                + static_cast<double>((static_cast<unsigned>(words[p011]) >> shift) & MASK) * w01
                + static_cast<double>((static_cast<unsigned>(words[p111]) >> shift) & MASK) * w11;
        int value = jround_to_i(v * g2 + v2 * fz);
        if (value < 0)
        {
            value = 0;
        }
        else if (value > 127)
        {
            value = 127;
        }
        out |= (value & MASK) << shift;
    }
    return out;
}

/** 与 `ElementAdvection.scaleWord` 同一条式子（整数除法，向零截断） */
inline int scale_word(int word, int perm)
{
    int out = 0;
    for (int t = 0; t < 4; ++t)
    {
        const int shift = t * 8;
        const int v = (static_cast<unsigned>(word) >> shift) & MASK;
        const int scaled = v * perm / BARRIER_OPEN;
        out |= (scaled & MASK) << shift;
    }
    return out;
}

/** 逐通道取 max(word, base) */
inline int raise_to_base(int word, int base_word)
{
    int out = word;
    for (int t = 0; t < 4; ++t)
    {
        const int shift = t * 8;
        const int v = (static_cast<unsigned>(out) >> shift) & MASK;
        const int bv = (static_cast<unsigned>(base_word) >> shift) & MASK;
        if (v < bv)
        {
            out = (out & ~(MASK << shift)) | (bv << shift);
        }
    }
    return out;
}

/** 速度恒为 0 时的等价快路径：只剩通透度缩放 + base 下限 */
bool no_flow_step(const Input& in)
{
    bool changed = false;
    for (int idx = 0; idx < SIZE; ++idx)
    {
        const int perm = permeability_at(in, idx);
        if (perm <= BARRIER_BLOCKED)
        {
            continue;
        }
        const int word = in.current[idx];
        if ((word & ALL_MASK) == 0)
        {
            continue;
        }
        int advected = perm < BARRIER_OPEN ? scale_word(word, perm) : word;
        const int base_word = in.base[idx];
        if (base_word != 0)
        {
            advected = raise_to_base(advected, base_word);
        }
        if (!same_word(advected, word))
        {
            in.current[idx] = advected;
            changed = true;
        }
    }
    return changed;
}

}  // namespace

}  // namespace element
}  // namespace wmvm

#endif  // WMVM_ELEMENT_ADVECT_INL_H
