/*
 * 元素平流的向量内核：把一个 32 位"字"里的 4 个元素通道放进 4 条 lane 并行。
 *
 * <p><b>AVX2 = 4 lane（一次 1 格）；AVX-512 = 8 lane（一次 2 格）。</b>
 * 两条内核是同一份模板按 {@code NCELLS} 实例化出来的，差别只有"一个向量塞几格"。
 * 更宽的 SIMD 只在这里出现一次，不在两个函数里各写一遍。</p>
 *
 * <p><b>数值契约</b>（与 {@code element-advect.cpp} 标量版逐位一致）：中间量全是 double，
 * 乘法加法保持左结合顺序，取整用"floor + 小数 >= 0.5 进一"（与 {@code jround_long} 同构，
 * 不是 {@code floor(x+0.5)}）；打包时每 lane 的值 < 256，互不串位。</p>
 *
 * <p><b>降级</b>：SSE2/SSSE3 目标没有 4×double 的 256 位寄存器（int32→double 那级桥要它），
 * 所以那些目标上本文件的入口直接转调 {@code advect_scalar}，不编向量内核。</p>
 *
 * <p><b>为什么 lane 内按通道切、而不是按格切</b>：按格切要 gather 每格独立的 8 个角点字，
 * 按通道切时一次广播一个标量字就能喂满 4 条 lane，采样里最贵的"8 角点 × 4 通道加权求和"
 * 整段变成向量指令。三线性采样的坐标/速度那半段仍是标量（它本来就按格独立，向量化要 gather）。</p>
 */
#include "element-advect.h"

#include <cstring>

#include "element-advect-inl.h"

#undef HWY_TARGET_INCLUDE
#define HWY_TARGET_INCLUDE "element-advect-simd.cpp"
#include "hwy/foreach_target.h"
#include "hwy/highway.h"

HWY_BEFORE_NAMESPACE();
namespace hwy
{
namespace HWY_NAMESPACE
{

#if HWY_TARGET == HWY_AVX2 || HWY_TARGET == HWY_AVX3

namespace hn = hwy::HWY_NAMESPACE;

/** 一格的采样参数：8 个角点下标 + 权重（全部在 double 里算好，向量部分只吃这些） */
struct CellSample
{
    int p[8];
    double w00, w10, w01, w11, fz, g2;
};

/** 与标量 `sample_word` 的前半段逐字相同：clamp 到 [0,15]、取整、算 8 个角点下标与权重 */
HWY_INLINE void PrepareSample(double x, double y, double z, CellSample& s)
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
    s.w00 = g0 * g1;
    s.w10 = fx * g1;
    s.w01 = g0 * fy;
    s.w11 = fx * fy;
    s.fz = fz;
    s.g2 = g2;
    s.p[0] = i + 16 * (j + 16 * k);
    s.p[1] = i1 + 16 * (j + 16 * k);
    s.p[2] = i + 16 * (j1 + 16 * k);
    s.p[3] = i1 + 16 * (j1 + 16 * k);
    s.p[4] = i + 16 * (j + 16 * k1);
    s.p[5] = i1 + 16 * (j + 16 * k1);
    s.p[6] = i + 16 * (j1 + 16 * k1);
    s.p[7] = i1 + 16 * (j1 + 16 * k1);
}

/** 把一个标量摊满 NCELLS*4 条 lane：NCELLS=1 直接广播；NCELLS=2 两个半宽广播后拼起来 */
template <int NCELLS, class DI, class DHI>
HWY_INLINE auto SpreadI32(const DI di, const DHI dhi, int w0, int w1)
{
    if constexpr (NCELLS == 1)
    {
        (void)dhi;
        (void)w1;
        return hn::Set(di, w0);
    }
    else
    {
        return hn::Combine(di, hn::Set(dhi, w0), hn::Set(dhi, w1));
    }
}

/** 同上，double 版（权重按格展开：4 条 lane 同值） */
template <int NCELLS, class DD, class DH>
HWY_INLINE auto SpreadD(const DD dd, const DH dh, double w0, double w1)
{
    if constexpr (NCELLS == 1)
    {
        (void)dh;
        (void)w1;
        return hn::Set(dd, w0);
    }
    else
    {
        return hn::Combine(dd, hn::Set(dh, w0), hn::Set(dh, w1));
    }
}

/**
 * 对 NCELLS 个格一起采样（4 通道三线性 + 取整 + 打包）。
 *
 * @param words 旧值数组（长度 4096）
 * @param cells NCELLS 个格的采样参数；调用方保证 `cells[1]` 也可读（NCELLS=1 时不用但会被求值）
 * @param out   每格一个打包后的字（4 通道 × 7 位）
 */
template <int NCELLS>
HWY_INLINE void SampleWords(const int* words, const CellSample cells[2], int* out)
{
    constexpr int LANES = 4 * NCELLS;
    static_assert(LANES == 4 || LANES == 8, "只做 4 lane（AVX2）与 8 lane（AVX-512）");
    constexpr int kMask = ::wmvm::element::MASK;

    const hn::ScalableTag<double> dd;               // LANES 条 double lane
    const hn::Rebind<int32_t, decltype(dd)> di;     // LANES 条 int32 lane（总位数减半）
    const hn::Rebind<uint32_t, decltype(dd)> du;
    const hn::Rebind<float, decltype(dd)> df;
    const hn::Half<decltype(di)> dhi;                // 半宽的 int32（NCELLS=2 拼 lane 用）
    const hn::Half<decltype(dd)> dhd;

    // lane 内通道号 = lane % 4 → 右移位数 {0,8,16,24, 0,8,16,24}
    const auto laneShift = hn::Mul(hn::And(hn::Iota(du, 0), hn::Set(du, 3)), hn::Set(du, 8));
    const auto byteMask = hn::Set(du, kMask);
    const auto half = hn::Set(dd, 0.5);
    const auto oneD = hn::Set(dd, 1.0);
    const auto zeroD = hn::Set(dd, 0.0);
    const auto zeroI = hn::Zero(di);
    const auto maxI = hn::Set(di, kMask);

    // 取一个角点的 4 个通道值：广播 → 按 lane 变位数右移 → 掩出 7 位 → 转 double
    auto corner = [&](int k)
    {
        const auto v = SpreadI32<NCELLS>(di, dhi, words[cells[0].p[k]], words[cells[1].p[k]]);
        const auto bytes = hn::And(hn::BitCast(du, v) >> laneShift, byteMask);
        return hn::PromoteTo(dd, hn::ConvertTo(df, hn::BitCast(di, bytes)));
    };

    const auto b0 = corner(0);
    const auto b1 = corner(1);
    const auto b2 = corner(2);
    const auto b3 = corner(3);
    const auto b4 = corner(4);
    const auto b5 = corner(5);
    const auto b6 = corner(6);
    const auto b7 = corner(7);

    const auto w00 = SpreadD<NCELLS>(dd, dhd, cells[0].w00, cells[1].w00);
    const auto w10 = SpreadD<NCELLS>(dd, dhd, cells[0].w10, cells[1].w10);
    const auto w01 = SpreadD<NCELLS>(dd, dhd, cells[0].w01, cells[1].w01);
    const auto w11 = SpreadD<NCELLS>(dd, dhd, cells[0].w11, cells[1].w11);
    const auto vfz = SpreadD<NCELLS>(dd, dhd, cells[0].fz, cells[1].fz);
    const auto vg2 = SpreadD<NCELLS>(dd, dhd, cells[0].g2, cells[1].g2);

    // 左结合顺序与标量源码逐字相同：((b0*w00 + b1*w10) + b2*w01) + b3*w11
    const auto v = hn::Add(hn::Add(hn::Add(hn::Mul(b0, w00), hn::Mul(b1, w10)),
                                  hn::Mul(b2, w01)),
                           hn::Mul(b3, w11));
    const auto v2 = hn::Add(hn::Add(hn::Add(hn::Mul(b4, w00), hn::Mul(b5, w10)),
                                   hn::Mul(b6, w01)),
                            hn::Mul(b7, w11));
    const auto sum = hn::Add(hn::Mul(v, vg2), hn::Mul(v2, vfz));

    // 与 jround_long 同构：floor + 小数 >= 0.5 进一（结果落在 [0,128]，转 int 无歧义）
    const auto fl = hn::Floor(sum);
    const auto frac = hn::Sub(sum, fl);
    const auto bump = hn::IfThenElse(hn::Ge(frac, half), oneD, zeroD);
    // 两级窄化：f64 → f32 → i32 截断。值此时已是精确整数且 <= 128，两步都无损。
    auto rounded = hn::ConvertTo(di, hn::DemoteTo(df, hn::Add(fl, bump)));
    rounded = hn::Min(hn::Max(rounded, zeroI), maxI);

    // 打包：lane[4c..4c+3] 就是第 c 格的 4 个通道，按 8t 左移拼成一个字
    alignas(64) int32_t obuf[LANES];
    hn::StoreU(rounded, di, obuf);
    for (int c = 0; c < NCELLS; ++c)
    {
        int packed = 0;
        for (int l = 0; l < 4; ++l)
        {
            packed |= (obuf[4 * c + l] & kMask) << (8 * l);
        }
        out[c] = packed;
    }
}

/** 单格完整一步，走标量 `sample_word`（部分格活着时用，保证与标量路径逐位一致） */
HWY_INLINE void StepOne(const ::wmvm::element::Input& in, int idx, int perm, int word,
        bool& changed)
{
    using namespace ::wmvm::element;
    const int x = idx & 15;
    const int y = (idx >> 4) & 15;
    const int z = idx >> 8;
    const double wx = static_cast<double>(in.section_x + x) + 0.5;
    const double wy = static_cast<double>(in.section_y + y) + 0.5;
    const double wz = static_cast<double>(in.section_z + z) + 0.5;
    double vel[3] = {0.0, 0.0, 0.0};
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

/** 内核主体：NCELLS 格一批，全活才走向量，部分活就逐格走标量（结果逐位一致） */
template <int NCELLS>
int AdvectVec(const ::wmvm::element::Input& in)
{
    using namespace ::wmvm::element;
    if (in.current == nullptr || in.scratch == nullptr || in.base == nullptr)
    {
        return -1;
    }
    if (!has_flow_in(in))
    {
        return (no_flow_step(in) ? 1 : 0) | 2;
    }

    // 采样要读旧值：整节拷一份（与 Java 的 System.arraycopy 等价）
    std::memcpy(in.scratch, in.current, sizeof(int) * SIZE);

    bool changed = false;
    for (int base = 0; base < SIZE; base += NCELLS)
    {
        int perm[NCELLS];
        int word[NCELLS];
        int live = 0;
        for (int c = 0; c < NCELLS; ++c)
        {
            const int idx = base + c;
            perm[c] = permeability_at(in, idx);
            word[c] = in.scratch[idx];
            if (perm[c] > BARRIER_BLOCKED && (word[c] & ALL_MASK) != 0)
            {
                ++live;
            }
        }
        if (live == 0)
        {
            continue;
        }
        if (live < NCELLS)
        {
            for (int c = 0; c < NCELLS; ++c)
            {
                if (perm[c] > BARRIER_BLOCKED && (word[c] & ALL_MASK) != 0)
                {
                    StepOne(in, base + c, perm[c], word[c], changed);
                }
            }
            continue;
        }

        CellSample cells[2] = {};
        for (int c = 0; c < NCELLS; ++c)
        {
            const int idx = base + c;
            const int x = idx & 15;
            const int y = (idx >> 4) & 15;
            const int z = idx >> 8;
            const double wx = static_cast<double>(in.section_x + x) + 0.5;
            const double wy = static_cast<double>(in.section_y + y) + 0.5;
            const double wz = static_cast<double>(in.section_z + z) + 0.5;
            double vel[3] = {0.0, 0.0, 0.0};
            sample_velocity(in, wx, wy, wz, vel);
            PrepareSample(static_cast<double>(x) - vel[0] * in.dt,
                    static_cast<double>(y) - vel[1] * in.dt,
                    static_cast<double>(z) - vel[2] * in.dt, cells[c]);
        }

        int adv[2] = {0, 0};
        SampleWords<NCELLS>(in.scratch, cells, adv);
        for (int c = 0; c < NCELLS; ++c)
        {
            const int idx = base + c;
            int advected = adv[c];
            if (perm[c] < BARRIER_OPEN)
            {
                advected = scale_word(advected, perm[c]);
            }
            const int base_word = in.base[idx];
            if (base_word != 0)
            {
                advected = raise_to_base(advected, base_word);
            }
            if (!same_word(advected, word[c]))
            {
                in.current[idx] = advected;
                changed = true;
            }
        }
    }
    return changed ? 1 : 0;
}

#endif  // HWY_TARGET == HWY_AVX2 || HWY_TARGET == HWY_AVX3

/** 每个目标一份入口：AVX-512 走 8 lane、AVX2 走 4 lane、更窄的目标转标量 */
int AdvectSimd(const ::wmvm::element::Input& in)
{
#if HWY_TARGET == HWY_AVX3
    return AdvectVec<2>(in);
#elif HWY_TARGET == HWY_AVX2
    return AdvectVec<1>(in);
#else
    return ::wmvm::element::advect_scalar(in);
#endif
}

/** 本目标实际用了几条通道 lane（0 = 落到标量回落） */
int SimdLanes()
{
#if HWY_TARGET == HWY_AVX3
    return 8;
#elif HWY_TARGET == HWY_AVX2
    return 4;
#else
    return 0;
#endif
}

const char* SimdTargetName()
{
    return TargetName(HWY_TARGET);
}

}  // namespace HWY_NAMESPACE

#if HWY_ONCE
HWY_EXPORT(AdvectSimd);
HWY_EXPORT(SimdLanes);
HWY_EXPORT(SimdTargetName);
#endif

}  // namespace hwy
HWY_AFTER_NAMESPACE();

#if HWY_ONCE
using namespace hwy;   // HWY_DYNAMIC_DISPATCH 展开出来的分派表在 hwy:: 里
namespace wmvm
{
namespace element
{

int advect_simd(const Input& in)
{
    return HWY_DYNAMIC_DISPATCH(AdvectSimd)(in);
}

int advect_simd_lanes()
{
    return HWY_DYNAMIC_DISPATCH(SimdLanes)();
}

const char* advect_simd_target()
{
    return HWY_DYNAMIC_DISPATCH(SimdTargetName)();
}

}  // namespace element
}  // namespace wmvm
#endif  // HWY_ONCE
