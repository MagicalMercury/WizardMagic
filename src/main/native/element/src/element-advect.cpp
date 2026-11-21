#include "element-advect.h"

#include <cmath>
#include <cstdlib>
#include <cstring>

#include "element-advect-inl.h"

namespace wmvm
{
namespace element
{

namespace
{

/**
 * 向量内核开关：默认开，`WMVM_ELEMENT_SIMD=0` 关。
 *
 * 读一次就缓存（每步拿一次 getenv 会真的去查环境块，步进是热路径）。
 */
bool simd_enabled()
{
    static const bool on = []()
    {
        const char* v = std::getenv("WMVM_ELEMENT_SIMD");
        return v == nullptr || std::strcmp(v, "0") != 0;
    }();
    return on;
}

}  // namespace

int advect(const Input& in)
{
    return simd_enabled() ? advect_simd(in) : advect_scalar(in);
}

int advect_scalar(const Input& in)
{
    if (in.current == nullptr || in.scratch == nullptr || in.base == nullptr)
    {
        return -1;
    }
    if (!has_flow_in(in))
    {
        // 返回码：bit0 = 有改动，bit1 = 走的是无流短路（统计口径用）
        return (no_flow_step(in) ? 1 : 0) | 2;
    }

    // 采样要读旧值：整节拷一份（与 Java 的 System.arraycopy 等价）
    std::memcpy(in.scratch, in.current, sizeof(int) * SIZE);

    bool changed = false;
    double vel[3] = {0.0, 0.0, 0.0};

    for (int idx = 0; idx < SIZE; ++idx)
    {
        const int perm = permeability_at(in, idx);
        if (perm <= BARRIER_BLOCKED)
        {
            continue;
        }
        const int word = in.scratch[idx];
        if ((word & ALL_MASK) == 0)
        {
            continue;
        }
        const int x = idx & 15;
        const int y = (idx >> 4) & 15;
        const int z = idx >> 8;

        const double wx = static_cast<double>(in.section_x + x) + 0.5;
        const double wy = static_cast<double>(in.section_y + y) + 0.5;
        const double wz = static_cast<double>(in.section_z + z) + 0.5;
        sample_velocity(in, wx, wy, wz, vel);

        const double bx = static_cast<double>(x) - vel[0] * in.dt;
        const double by = static_cast<double>(y) - vel[1] * in.dt;
        const double bz = static_cast<double>(z) - vel[2] * in.dt;

        int advected = sample_word(in.scratch, bx, by, bz);
        if (perm < BARRIER_OPEN)
        {
            advected = scale_word(advected, perm);
        }
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
    return changed ? 1 : 0;
}

}  // namespace element
}  // namespace wmvm
