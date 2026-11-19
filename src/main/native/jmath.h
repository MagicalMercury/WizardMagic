/*
 * Java 数学语义的端口：类型转换、floor、round、min/max 的边界行为。
 *
 * <p><b>位置说明</b>：这个头文件是 {@code wmvm/}（法术 VM）和 {@code element/}
 * （元素场）<b>共用</b>的，所以放在 {@code native/} 根下、由根 CMakeLists 用
 * {@code include_directories} 统一暴露。别把它塞回某个模块里。</p>
 *
 * 这些地方 C++ 的默认语义与 Java 不同，写错就是 1 ulp 起步的静默分叉：
 *   - `(int) double`：Java 是饱和转换（NaN→0，越界→MIN/MAX）；C++ 强转是 UB。
 *   - `Math.floor(double)`：两边都精确，直接对应。
 *   - `Math.round(double)`：返回 long，再被 `(double)` 或 `(int)` 用掉。
 *   - `Math.min/max`：NaN 必须传播；`min(+0.0, -0.0)` 要给出 -0.0（`std::min` 不保证）。
 */
#ifndef WMVM_JMATH_H
#define WMVM_JMATH_H

#include <climits>
#include <cmath>
#include <cstdint>

namespace wmvm
{

/** Java `(int) double`：饱和 */
static inline int jd2i(double v)
{
    if (v != v)
    {
        return 0;
    }
    if (v >= 2147483648.0)
    {
        return INT_MAX;
    }
    if (v <= -2147483649.0)
    {
        return INT_MIN;
    }
    return static_cast<int>(v);
}

/** Java `SpellNoise.floor(double)` 那个私有实现：截断后不足再减一 */
static inline int jfloor_to_i(double v)
{
    const int i = jd2i(v);
    if (v < static_cast<double>(i))
    {
        return static_cast<int>(static_cast<unsigned int>(i) - 1u);
    }
    return i;
}

/** Java `Math.round(double)`（返回 long 的版本），越界饱和、NaN→0 */
static inline int64_t jround_long(double v)
{
    if (v != v)
    {
        return 0;                       // NaN → 0
    }
    // |v| >= 2^52 时 v 本身就是整数（ulp >= 1），直接饱和转换
    if (v >= 4503599627370496.0)
    {
        return (v >= 9223372036854775808.0) ? INT64_MAX : static_cast<int64_t>(v);
    }
    if (v <= -4503599627370496.0)
    {
        return (v <= -9223372036854775808.0) ? INT64_MIN : static_cast<int64_t>(v);
    }
    // |v| < 2^52：floor 与小数部分都精确，可以安全地做"半值进一"
    // （注意不能用 floor(v + 0.5)：0.49999999999999994 + 0.5 会舍入成 1.0，
    //   2^52+1 那种再 +0.5 会进一 —— 实测都和 Java 不一致）
    const double f = std::floor(v);
    const double frac = v - f;
    int64_t r = static_cast<int64_t>(f);
    if (frac >= 0.5)
    {
        r += 1;                         // 平局向 +∞（Java 的规定）
    }
    return r;
}

/** `(double) Math.round(v)` —— 寄存器机 OP_ROUND 写回寄存器的就是这个 */
static inline double jround_double(double v)
{
    return static_cast<double>(jround_long(v));
}

/** `(int) Math.round(v)`：long 再窄化成 int（取低 32 位） */
static inline int jround_to_i(double v)
{
    const int64_t l = jround_long(v);
    return static_cast<int32_t>(static_cast<uint32_t>(static_cast<uint64_t>(l)));
}

static inline double jmin(double a, double b)
{
    // 与 JDK Double.min 同构：NaN 原样返回（保住 payload），两个 0 时取 -0.0
    if (a != a)
    {
        return a;
    }
    if (a == 0.0 && b == 0.0 && std::signbit(b))
    {
        return b;
    }
    return (a <= b) ? a : b;
}

static inline double jmax(double a, double b)
{
    if (a != a)
    {
        return a;
    }
    if (a == 0.0 && b == 0.0 && std::signbit(a))
    {
        return b;
    }
    return (a >= b) ? a : b;
}

/** Java `Math.signum` 不参与（脚本里用比较写出来），这里不用 */

}  // namespace wmvm

#endif  // WMVM_JMATH_H
