/*
 * 元素场平流（原生）。逐位移植 `com.wizardmagic.core.elements.flow.ElementAdvection`。
 *
 * 为什么搬到这里：profile 里 `ElementAdvection.advectCore` 占服务端线程 16%
 * （5.38 s / 33.6 s），是模组第一热点。Java 那边每格 68 ns，C++ 标量通常能到 1.8–2×，
 * 而且不用改语义（同一套 double 运算顺序 + 同一条 `Math.round`）。
 *
 * 数值契约（与 Java 逐位一致的三个前提）：
 *   1. 所有中间量用 double（速度场是 float，按 Java 的 `float` 语义参与运算）；
 *   2. 运算顺序与 Java 源码逐字对齐，编译期 `-ffp-contract=off -fno-fast-math`；
 *   3. `Math.round` 用 jmath.h 的 `jround_to_i`（floor + 小数比较，不是 floor(x+0.5)）。
 *
 * 布局：元素打包成 int，每 8 位一个元素（0..127，四个元素）；
 * section 内下标 `index = (y << 8) | (z << 4) | x`（与 `ElementField.index` 一致）。
 */
#ifndef WMVM_ELEMENT_ADVECT_H
#define WMVM_ELEMENT_ADVECT_H

#include <cstdint>

namespace wmvm
{
namespace element
{

/** 16³ 格；与 ElementField.SIZE / EDGE 一致 */
constexpr int SIZE = 16 * 16 * 16;
constexpr int EDGE = 16;
/** 每 8 位一个元素，四个元素打包 */
constexpr int MASK = 0x7F;
constexpr int ALL_MASK = 0x7F7F7F7F;
/** 魔力屏障：0 = 完全阻断，127 = 完全通透（ManaBarrier） */
constexpr int BARRIER_BLOCKED = 0;
constexpr int BARRIER_OPEN = 127;

/** 流体粗网格：24³ 个格子，每格 4 方块（FluidField.N / CELL） */
constexpr int FLUID_N = 24;
constexpr int FLUID_CELL = 4;
constexpr int FLUID_NC = FLUID_N * FLUID_N * FLUID_N;

/**
 * 一次平流所需的全部输入。指针都由 Java 侧持有，native 不拥有、不释放。
 *
 * `permeability` 可以为空（等价于"处处 OPEN"），与 Java 的懒分配一致。
 */
struct Input
{
    int* current;                 // in/out：4 元素打包的当前值
    int* scratch;                 // 供采样用的旧值缓冲（长度同 current）
    const int* base;              // 回流目标
    const uint64_t* blocked;      // 屏障位图，SIZE/64 = 64 个 long
    const uint8_t* permeability;  // 逐格通透度（0..127），可为空
    int has_barrier;              // 0 = 这一节没有屏障（短路掉整条屏障判定）

    const float* fluid_u;         // 格心速度，FLUID_NC 个
    const float* fluid_v;
    const float* fluid_w;
    int fluid_origin_x;           // 流体域最低角（世界方块坐标）
    int fluid_origin_y;
    int fluid_origin_z;

    int section_x;                // 本 section 的世界原点
    int section_y;
    int section_z;

    double dt;                    // 时间步长（秒）；调度器现在传 1.0
};

/**
 * 对单个 section 跑一步平流（带运行期分派的正门）。
 *
 * 默认走向量内核，`WMVM_ELEMENT_SIMD=0` 退回标量；两条路逐位一致。
 *
 * @return 位组合：bit0 = 有元素被改动，bit1 = 走的是「无流短路」路径；负数 = 参数错误。
 */
int advect(const Input& in);

/** 纯标量内核（`advect` 的回退路，也是向量内核里"部分格活着"时用的路） */
int advect_scalar(const Input& in);

/**
 * 向量内核：一个字节字（4 个元素通道）占用 4 条 lane 并行。
 *
 * AVX2 = 4 lane（一次 1 格）；AVX-512 = 8 lane（一次 2 格）；
 * SSE2/SSSE3 = 没有 4×double 寄存器可用，直接内部转调 `advect_scalar`。
 * 与 `advect_scalar` 逐位一致（同一 double 运算顺序、同一条取整规则）。
 */
int advect_simd(const Input& in);

/** 当前 CPU 上实际选中的向量宽度（0 = 落到标量回落），诊断/基准用 */
int advect_simd_lanes();

/** 当前 CPU 上实际选中的 Highway 目标名，诊断/基准用 */
const char* advect_simd_target();

}  // namespace element
}  // namespace wmvm

#endif  // WMVM_ELEMENT_ADVECT_H
