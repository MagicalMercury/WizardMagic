/*
 * 确定性噪声与随机数：`SpellNoise.value3` / `SpellRandom.mix|unit` 的逐位移植。
 *
 * 只依赖整数（uint64_t）与 IEEE 双精度四则，不用任何 libm —— 跨编译器/跨平台天然逐位一致。
 * 两个容易写错的地方：
 *   1. Java 的 long 是带符号、`>>>` 是无符号右移；这里一律用 uint64_t 做位运算，补码表示逐位相同。
 *   2. `hash` 里的 `(long) x` 是**符号扩展**的 int64，不是零扩展。
 */
#ifndef WMVM_NOISE_H
#define WMVM_NOISE_H

#include <cstdint>

#include "jmath.h"

/** 强制内联（GCC/Clang 用 attribute，MSVC 用 __forceinline） */
#if defined(_MSC_VER)
#define WMVM_ALWAYS_INLINE __forceinline
#elif defined(__GNUC__) || defined(__clang__)
#define WMVM_ALWAYS_INLINE inline __attribute__((always_inline))
#else
#define WMVM_ALWAYS_INLINE inline
#endif

namespace wmvm
{

static inline double fade(double t)
{
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

static inline double lerp(double a, double b, double t)
{
    return a + (b - a) * t;
}

/** splitmix64 收尾混合（与 SpellRandom.mix 一致） */
static inline uint64_t mix64(uint64_t z)
{
    z += 0x9E3779B97F4A7C15ull;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

/** [0,1) 均匀分布（与 SpellRandom.unit 一致） */
static inline double random_unit(int64_t seed, int index, int stream)
{
    uint64_t h = static_cast<uint64_t>(seed);
    h ^= static_cast<uint64_t>(static_cast<int64_t>(index)) * 0x9E3779B97F4A7C15ull;
    h ^= static_cast<uint64_t>(static_cast<int64_t>(stream)) * 0xC2B2AE3D27D4EB4Full;
    return static_cast<double>(mix64(h) >> 11) * 0x1.0p-53;
}

/**
 * 上一次调用留下的"角点基哈希"缓存。
 *
 * <p>firestorm 这类写法的三次 noise 只有 {@code step} 不同（x/y/z 逐位相同），
 * 而 8 个角点的基哈希 {@code seed ^ x*C1 ^ y*C2 ^ z*C3} 只跟 x/y/z 有关。
 * 命中缓存就能省掉 8 次角点哈希与 3 次 fade（约 55% 的工作量），<b>数值完全不变</b>。</p>
 *
 * <p>为什么不做成 NOISE3 指令：那要改字节码 + IR_VERSION + 两个 Java 后端；
 * 缓存版只动 native 一侧，零语义风险。线程安全靠 thread_local（粒子循环在工作线程上跑）。</p>
 */
struct NoiseCache
{
    bool valid = false;
    int64_t seed = 0;
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
    int xi = 0;
    int yi = 0;
    int zi = 0;
    double u = 0.0;
    double v = 0.0;
    double w = 0.0;
    /** 8 个角点的基哈希（不含 step 项） */
    uint64_t base[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    /** 诊断：命中/未命中次数 */
    long hits = 0;
    long misses = 0;
};

inline thread_local NoiseCache g_noise_cache;

/** 一个角点的基哈希：(x,y,z) 部分，不含 step */
static inline uint64_t corner_base(int64_t seed, int x, int y, int z)
{
    uint64_t h = static_cast<uint64_t>(seed);
    h ^= static_cast<uint64_t>(static_cast<int64_t>(x)) * 0x9E3779B97F4A7C15ull;
    h ^= static_cast<uint64_t>(static_cast<int64_t>(y)) * 0xC2B2AE3D27D4EB4Full;
    h ^= static_cast<uint64_t>(static_cast<int64_t>(z)) * 0x165667B19E3779F9ull;
    return h;
}

/** 一个格点的哈希值，映射到 [-1, 1] */
static inline double corner(int64_t seed, int x, int y, int z, int step)
{
    uint64_t h = static_cast<uint64_t>(seed);
    h ^= static_cast<uint64_t>(static_cast<int64_t>(x)) * 0x9E3779B97F4A7C15ull;
    h ^= static_cast<uint64_t>(static_cast<int64_t>(y)) * 0xC2B2AE3D27D4EB4Full;
    h ^= static_cast<uint64_t>(static_cast<int64_t>(z)) * 0x165667B19E3779F9ull;
    h ^= static_cast<uint64_t>(static_cast<int64_t>(step)) * 0x27D4EB2F165667C5ull;
    return static_cast<double>(mix64(h) >> 11) * 0x1.0p-53 * 2.0 - 1.0;
}

/**
 * 三维 value noise，返回 [-1, 1]（与 SpellNoise.value3 逐位一致）。
 *
 * <p><b>缓存为什么要显式传进来</b>：原来这里直接抓 {@code g_noise_cache}（thread_local）。
 * 标量内核里它被内联掉了，没事；但向量内核的 switch 太大、这个函数没被内联，
 * 于是每次调用都要为 <b>每一次</b> {@code g_noise_cache} 访问走一遍 MinGW 的
 * thread_local 取址，单次 noise 从 ~20 ns 涨到 ~114 ns（实测，3x NOISE 那组基准
 * 向量 352 ns/粒 vs 标量 61）。改成由调用方传引用后，取址只剩一次。</p>
 */
WMVM_ALWAYS_INLINE static double value3_in(NoiseCache& c, int64_t seed, double x, double y,
        double z, int step)
{
    // 三次连续调用只有 step 不同时，这里会命中（firestorm 就是这种写法）
    if (c.valid && c.seed == seed && c.x == x && c.y == y && c.z == z)
    {
        ++c.hits;
    }
    else
    {
        ++c.misses;
        c.valid = true;
        c.seed = seed;
        c.x = x;
        c.y = y;
        c.z = z;
        c.xi = jfloor_to_i(x);
        c.yi = jfloor_to_i(y);
        c.zi = jfloor_to_i(z);
        c.u = fade(x - static_cast<double>(c.xi));
        c.v = fade(y - static_cast<double>(c.yi));
        c.w = fade(z - static_cast<double>(c.zi));
        const int xi = c.xi;
        const int yi = c.yi;
        const int zi = c.zi;
        c.base[0] = corner_base(seed, xi, yi, zi);
        c.base[1] = corner_base(seed, xi + 1, yi, zi);
        c.base[2] = corner_base(seed, xi, yi + 1, zi);
        c.base[3] = corner_base(seed, xi + 1, yi + 1, zi);
        c.base[4] = corner_base(seed, xi, yi, zi + 1);
        c.base[5] = corner_base(seed, xi + 1, yi, zi + 1);
        c.base[6] = corner_base(seed, xi, yi + 1, zi + 1);
        c.base[7] = corner_base(seed, xi + 1, yi + 1, zi + 1);
    }

    const uint64_t stepTerm =
            static_cast<uint64_t>(static_cast<int64_t>(step)) * 0x27D4EB2F165667C5ull;
    const double unit = 0x1.0p-53;
    auto val = [&](int k) -> double
    {
        return static_cast<double>(mix64(c.base[k] ^ stepTerm) >> 11) * unit * 2.0 - 1.0;
    };
    const double c000 = val(0);
    const double c100 = val(1);
    const double c010 = val(2);
    const double c110 = val(3);
    const double c001 = val(4);
    const double c101 = val(5);
    const double c011 = val(6);
    const double c111 = val(7);

    const double u = c.u;
    const double v = c.v;
    const double w = c.w;
    const double x00 = lerp(c000, c100, u);
    const double x10 = lerp(c010, c110, u);
    const double x01 = lerp(c001, c101, u);
    const double x11 = lerp(c011, c111, u);
    const double y0 = lerp(x00, x10, v);
    const double y1 = lerp(x01, x11, v);
    return lerp(y0, y1, w);
}

/** 标量内核用的便捷包装：thread_local 取址在这里只发生一次/每次调用 */
static inline double value3(int64_t seed, double x, double y, double z, int step)
{
    return value3_in(g_noise_cache, seed, x, y, z, step);
}

}  // namespace wmvm

#endif  // WMVM_NOISE_H
