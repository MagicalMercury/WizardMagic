/*
 * WMVM：法术寄存器机的原生内核（C ABI）。
 *
 * 设计要点：
 *  - 只暴露 C 类型，不跨 JNI 传 C++ 对象（GCC/MSVC 的 C++ ABI 差异就无所谓了）。
 *  - 一次调用处理一批粒子的"一段"，对应 Java 侧 FastProgram.runStep 里的一次 RegVm.execute。
 *  - 不回调、不碰世界、不分配内存（除了 native 侧的 program 对象）。
 */
#ifndef WMVM_H
#define WMVM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 寄存器号上限（指令编码把寄存器号压在 8 位里，与 RegVm.MAX_REG 一致） */
#define WMVM_MAX_REG 256

/** 失败原因（wmvm_run 返回负数时，低 4 位是这个） */
enum
{
    WMVM_ERR_DIV_ZERO = 1,     /* 除以零 */
    WMVM_ERR_MOD_ZERO = 2,     /* 取模除数为零 */
    WMVM_ERR_AXIS_ZERO = 3,    /* tangent/radial 的轴向是零向量 */
    WMVM_ERR_UNKNOWN_OP = 4,   /* 未知指令 */
    WMVM_ERR_UNSUPPORTED = 5   /* 这条指令这一版还没实现（例如 exp/pow/atan2） */
};

typedef struct WmvmProgram WmvmProgram;

/** 一次调用要推进的一批粒子（SoA，全部由 Java 侧持有，native 不拥有） */
typedef struct WmvmBatch
{
    int from;               /* 段起点（含） */
    int to;                 /* 段终点（不含） */

    double* px;
    double* py;
    double* pz;
    double* vx;
    double* vy;
    double* vz;
    float* energy;

    const double* anchor_dx; /* 形状锚点（相对起点） */
    const double* anchor_dy;
    const double* anchor_dz;

    /* 下面几个是 `(i, time)` 的纯函数，Java 侧按块预先算好；不用就传 NULL。
     * axis 拆成三组 SoA（不是 3/粒 交错）：向量内核要连续载入。 */
    const double* axis_x;
    const double* axis_y;
    const double* axis_z;
    const double* target;    /* 1 / 粒 */
    const double* rnd;       /* 1 / 粒 */

    double origin_x, origin_y, origin_z;

    int64_t seed;            /* 云的 castSeed */
    int age_step;            /* 已推进的步数 */
    double time;             /* = age_step * DT（秒） */
    int rand_seq;            /* 进：这一步已经消耗的 rand 序号；出：跑完的序号 */
} WmvmBatch;

/** 初始化（运行期选目标）。返回 0 = 成功。 */
int wmvm_init(void);

/** 运行期选中的 Highway 目标名（"AVX2" 等） */
const char* wmvm_target_name(void);

/** 自动选择的 lane 数（1 / 4 / 8）；未初始化返回 0 */
int wmvm_lanes(void);

/** 强制 lane 数（1/4/8，0 = 自动）。当前目标不支持时返回 0，否则返回实际值 */
int wmvm_force_lanes(int lanes);

/** sin 查表数据（65536 项 float，与 Java SpellMath 同一张表）。不设置就不能用 sin/cos。 */
int wmvm_set_sin_table(const float* table, int count);

/**
 * 编译（缓存）一段指令流。code/consts 会被复制一份，调用方之后可以释放。
 * 返回 NULL 表示失败（见 wmvm_last_error）。
 */
WmvmProgram* wmvm_program_create(const int32_t* code, int ncode,
                                 const double* consts, int nconsts);

void wmvm_program_destroy(WmvmProgram* p);

/**
 * 跑一个段。
 *
 * @return >= 0：本段结束时的 rand 序号；< 0：错误，编码 -(ip * 8 + kind)
 */
int wmvm_run(const WmvmProgram* p, WmvmBatch* b, double* fv, const double* hv);

/** 最近一次失败的说明（线程内） */
const char* wmvm_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* WMVM_H */
