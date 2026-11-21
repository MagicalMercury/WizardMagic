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

    /* 形状：轴心 / 目标半径 / 每粒随机数都在 native 里算（原来由 Java 按块预算成
     * axis_x/axis_y/axis_z/target/rnd 五组数组再传进来，多一遍全量写读）。 */
    int shape_kind;            /* 0 = 无（轴心 = origin，目标半径 = -1）；1 = helix */
    int shape_count;           /* 云的粒子总数（不是段长：t = (i+0.5)/count 用绝对下标） */
    const double* shape_prep;  /* 2 / 粒，helix 构造时算好的 pow(t, ·) 缓存 */
    double shape_radius;       /* R_t */
    double shape_length;       /* A_t（顶部轴半径） */
    double shape_height;       /* H */
    double shape_turns;        /* ω（轴角速度） */
    double shape_frequency;    /* k_y（全高波数） */
    double shape_radius_pulse; /* ε_r */
    double shape_radius_wave;  /* k_r */
    double shape_radius_speed; /* ω_r */
    int tilted;                /* 0 = 不旋转 */
    double tilt[9];            /* 行优先 m00..m22：局部 → 世界方向 */

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

/**
 * 自检/基准专用：忽略"含 noise 或分支就留在标量"的判据，强行让向量内核跑。
 *
 * <p>生产路径永远不调它（Java 侧没有绑定）。存在的理由是<b>测量</b>：判据一旦关掉，
 * 就永远测不到"向量内核跑 firestorm 会怎样"，只能靠猜。</p>
 */
int wmvm_force_vec(int on);

/**
 * 自适应降级开关（默认开）。
 *
 * <p>背景：向量内核遇到"组内分歧"的分支时两条路都要跑，实测掉到 <b>0.34×</b>；
 * 但分支<b>全真/全假</b>时是 <b>1.26×</b>。firestorm 的两个 if 几乎永远全真，
 * 一刀切禁掉向量是白丢 26%。所以默认策略是：含分支照样先走向量，运行期统计分歧率，
 * 超阈值就把这个程序永久降级到标量。</p>
 *
 * @param on 0 = 关（回到"含分支就标量"以外的固定策略，供 A/B 用），非 0 = 开
 * @return 生效后的值
 */
int wmvm_set_adaptive(int on);

/** 分歧率阈值（百分比，默认 10）。累计分歧次数占比超过它即降级。 */
int wmvm_set_adapt_divergence_pct(int pct);

/**
 * 读一个程序的运行期统计（诊断/自检用）。
 *
 * @param jzTotal     向量内核里已求值的分支次数（每 JZ × 每 lane 组算一次），可为 NULL
 * @param jzDivergent 其中"组内既有人跳也有人不跳"的次数，可为 NULL
 * @param downgraded  1 = 已被自适应降级为标量，可为 NULL
 * @return 0 = 成功
 */
int wmvm_program_stats(const WmvmProgram* p, int* jzTotal, int* jzDivergent, int* downgraded);

/** 清零统计并把 keep_scalar 复位（自检/基准里做 A/B 用）。 */
void wmvm_program_reset_stats(WmvmProgram* p);

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
