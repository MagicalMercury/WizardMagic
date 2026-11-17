/*
 * WMVM 内核（1 lane）。指令语义逐条对齐 Java 侧 RegVm.execute，包括：
 *   - 每颗粒子复用同一个寄存器帧（先写后读，跨粒子安全）；
 *   - 分支、除零/取模零/切向零轴向的报错位置；
 *   - 所有数学都走 jmath.h / noise.h 的 Java 语义端口（不用 libm 的 sin/exp/pow）。
 *
 * 4/8 lane 的向量版下一步加：同一套 opcode、同一个 ProgramData，
 * 帧改成 `Vec<D> r[MAX_REG]` + 组掩码（照 RegVm4 的锁步语义）。
 */
#ifndef WMVM_VM_INL_H
#define WMVM_VM_INL_H

#include <cmath>
#include <cstdio>
#include <cstring>

#include "wmvm.h"

#include "jmath.h"
#include "noise.h"
#include "shapes.h"
#include "sintable.h"

namespace wmvm
{

// ---------------------------------------------------------------- opcode
// 必须与 RegVm.java 一致（编码不变，.spellc 就不用重编）
enum : int
{
    OP_LDK = 1, OP_MOV = 2, OP_MOV3 = 3, OP_LDF1 = 4, OP_LDF3 = 5,
    OP_LDH1 = 6, OP_LDH3 = 7,
    OP_LPOS = 8, OP_LPOSX = 9, OP_LPOSY = 10, OP_LPOSZ = 11,
    OP_LVEL = 12, OP_LVELX = 13, OP_LVELY = 14, OP_LVELZ = 15,
    OP_LENERGY = 16,
    OP_LANCHOR = 17, OP_LANCHORX = 18, OP_LANCHORY = 19, OP_LANCHORZ = 20,
    OP_LLOCAL = 21, OP_LLOCALX = 22, OP_LLOCALY = 23, OP_LLOCALZ = 24,
    OP_LAXIS = 25, OP_LAXISX = 26, OP_LAXISY = 27, OP_LAXISZ = 28,
    OP_LTARGET = 29, OP_LR = 30, OP_LAGE = 31,
    OP_SPOS = 32, OP_SPOSX = 33, OP_SPOSY = 34, OP_SPOSZ = 35,
    OP_SVEL = 36, OP_SVELX = 37, OP_SVELY = 38, OP_SVELZ = 39,
    OP_SENERGY = 40,
    OP_ADD_N = 41, OP_SUB_N = 42, OP_MUL_N = 43, OP_DIV_N = 44, OP_NEG_N = 45,
    OP_ADD_V = 46, OP_SUB_V = 47, OP_SCALE = 48, OP_DIV_SCALE = 49, OP_NEG_V = 50,
    OP_LT = 51, OP_LE = 52, OP_GT = 53, OP_GE = 54,
    OP_EQ_N = 55, OP_NE_N = 56, OP_AND = 57, OP_OR = 58, OP_NOT = 59,
    OP_JZ = 60, OP_JMP = 61,
    OP_ABS = 62, OP_SQRT = 63, OP_SIN = 64, OP_COS = 65, OP_EXP = 66,
    OP_FLOOR = 67, OP_ROUND = 68,
    OP_MIN = 69, OP_MAX = 70,
    OP_CLAMP = 71, OP_SMOOTHSTEP = 72, OP_LERP_N = 73, OP_LERP_V = 74,
    OP_DOT = 75, OP_CROSS = 76, OP_LEN = 77, OP_LEN_XZ = 78,
    OP_NORMALIZE = 79, OP_DIST = 80,
    OP_TANGENT = 81, OP_RADIAL = 82, OP_PICK = 83, OP_NOISE = 84,
    OP_JNZ = 85, OP_RAND = 86, OP_STF1 = 87, OP_STF3 = 88,
    OP_TANGENT_UP = 89, OP_RADIAL_UP = 90, OP_ROTATE = 91,
    OP_MOD_N = 92, OP_SIGN = 93, OP_POW = 94, OP_ATAN2 = 95,
    /** [SEL|d][cond|x] + [y]：d ← (r[cond] != 0) ? r[x] : r[y]（if-conversion 用） */
    OP_SELECT = 96
};

/** 指令占几个 int（多字指令自己带后续词） */
static inline int op_length(int op)
{
    switch (op)
    {
        case OP_LDK:
        case OP_JZ:
        case OP_JMP:
        case OP_JNZ:
        case OP_CLAMP:
        case OP_SMOOTHSTEP:
        case OP_LERP_N:
        case OP_LERP_V:
        case OP_TANGENT_UP:
        case OP_RADIAL_UP:
        case OP_ROTATE:
        case OP_SELECT:
            return 2;
        case OP_NOISE:
            return 3;
        default:
            return 1;
    }
}

static constexpr int DST_SHIFT = 16;
static constexpr int A_SHIFT = 8;
static constexpr int FIELD = 0xFF;

// ---------------------------------------------------------------- 形状采样
// table_sin/table_cos 在 sintable.h；轴心与目标半径的采样在 shapes.h

// ---------------------------------------------------------------- 程序
struct Program
{
    const int32_t* code = nullptr;
    int ncode = 0;
    const double* consts = nullptr;
    int nconsts = 0;
    /** 含 rand / 写云级变量 → 只能 1 lane（序列与写回顺序都依赖粒子顺序） */
    bool lane1_only = false;
    /**
     * 能不能走向量内核。
     *
     * <p>实测（firestorm）：纯算术 4 lane 是 1.5×，但含 noise 或分支就会掉到 0.6×——
     * noise 现在还是逐 lane 标量，分支会让组内 lane 分裂进慢路径。所以"按需调用"：
     * 只有<strong>既没有 noise、也没有分支</strong>的程序才进向量内核，其余留在标量。
     * 等 noise 向量化 + 谓词化做完，再放宽这个判据。</p>
     */
    bool vec_ok = true;
};

/** 每个线程最近一次的失败说明 */
extern thread_local char g_error[256];

static inline int fail(int kind, int ip, const char* msg)
{
    std::snprintf(g_error, sizeof(g_error), "%s (ip=%d)", msg, ip);
    return -(ip * 8 + kind);
}

static inline double smoothstep(double a, double b, double x)
{
    if (b == a)
    {
        return 0.0;
    }
    const double t = jmax(0.0, jmin(1.0, (x - a) / (b - a)));
    return t * t * (3.0 - 2.0 * t);
}

/**
 * 切向/径向。`up_reg < 0` 表示世界 Y 轴（与 RegVm.tangentUp 同一份式子）。
 * 输出直接写寄存器，方便与 Java 的 r[d..d+2] 对齐。
 */
static inline void tangent_into(double* r, int d, int a, int b, int up_reg, bool tangent)
{
    double ux = 0.0, uy = 1.0, uz = 0.0;
    if (up_reg >= 0)
    {
        const double ul = std::sqrt(r[up_reg] * r[up_reg] + r[up_reg + 1] * r[up_reg + 1]
                + r[up_reg + 2] * r[up_reg + 2]);
        if (!(ul > 1.0E-9))
        {
            r[d] = r[d + 1] = r[d + 2] = 0.0;
            return;
        }
        ux = r[up_reg] / ul;
        uy = r[up_reg + 1] / ul;
        uz = r[up_reg + 2] / ul;
    }

    const double rx = r[b] - r[a];
    const double ry = r[b + 1] - r[a + 1];
    const double rz = r[b + 2] - r[a + 2];
    const double axial = rx * ux + ry * uy + rz * uz;
    const double px = rx - ux * axial;
    const double py = ry - uy * axial;
    const double pz = rz - uz * axial;
    const double l = std::sqrt(px * px + py * py + pz * pz);
    if (l < 1.0E-9)
    {
        r[d] = r[d + 1] = r[d + 2] = 0.0;
        return;
    }
    if (!tangent)
    {
        r[d] = px / l;
        r[d + 1] = py / l;
        r[d + 2] = pz / l;
        return;
    }
    r[d] = (py * uz - pz * uy) / l;
    r[d + 1] = (pz * ux - px * uz) / l;
    r[d + 2] = (px * uy - py * ux) / l;
}

/** 绕任意轴旋转（右手定则），轴退化时原样返回 */
static inline void rotate_into(double* r, int d, int a, int b, int angle_reg)
{
    const double kl = std::sqrt(r[b] * r[b] + r[b + 1] * r[b + 1] + r[b + 2] * r[b + 2]);
    if (!(kl > 1.0E-9))
    {
        r[d] = r[a];
        r[d + 1] = r[a + 1];
        r[d + 2] = r[a + 2];
        return;
    }
    const double kx = r[b] / kl;
    const double ky = r[b + 1] / kl;
    const double kz = r[b + 2] / kl;
    const double angle = r[angle_reg];
    const double c = table_cos(angle);
    const double s = table_sin(angle);
    const double vx = r[a];
    const double vy = r[a + 1];
    const double vz = r[a + 2];
    const double cx = ky * vz - kz * vy;
    const double cy = kz * vx - kx * vz;
    const double cz = kx * vy - ky * vx;
    const double dot = kx * vx + ky * vy + kz * vz;
    const double t = 1.0 - c;
    r[d] = vx * c + cx * s + kx * dot * t;
    r[d + 1] = vy * c + cy * s + ky * dot * t;
    r[d + 2] = vz * c + cz * s + kz * dot * t;
}

// ---------------------------------------------------------------- 主循环
/**
 * 跑 `[from, to)` 一颗粒子一步。
 *
 * @return >= 0：本段结束时的 rand 序号；< 0：错误编码 -(ip*8+kind)
 */
static inline int run_1lane(const Program& p, WmvmBatch& b, double* fv, const double* hv)
{
    const int32_t* code = p.code;
    const int n = p.ncode;
    const double* k = p.consts;
    double r[WMVM_MAX_REG];
    std::memset(r, 0, sizeof(r));

    int rand_seq = b.rand_seq;
    const int64_t seed = b.seed;
    const int step = b.age_step;
    const double time = b.time;

    for (int i = b.from; i < b.to; ++i)
    {
        int pc = 0;

        while (pc < n)
        {
            const int32_t w = code[pc++];
            const int op = w >> 24;
            const int d = (w >> DST_SHIFT) & FIELD;
            const int a = (w >> A_SHIFT) & FIELD;
            const int bb = w & FIELD;

            switch (op)
            {
                // ------------------------------------------------ 取数
                case OP_LDK:
                    r[d] = k[code[pc++]];
                    break;
                case OP_MOV:
                    r[d] = r[a];
                    break;
                case OP_MOV3:
                    r[d] = r[a];
                    r[d + 1] = r[a + 1];
                    r[d + 2] = r[a + 2];
                    break;
                case OP_LDF1:
                    r[d] = fv[a];
                    break;
                case OP_LDF3:
                    r[d] = fv[a];
                    r[d + 1] = fv[a + 1];
                    r[d + 2] = fv[a + 2];
                    break;
                case OP_LDH1:
                    r[d] = hv[a];
                    break;
                case OP_LDH3:
                    r[d] = hv[a];
                    r[d + 1] = hv[a + 1];
                    r[d + 2] = hv[a + 2];
                    break;

                // ------------------------------------------------ 粒子读
                case OP_LPOS:
                    r[d] = b.px[i];
                    r[d + 1] = b.py[i];
                    r[d + 2] = b.pz[i];
                    break;
                case OP_LPOSX:
                    r[d] = b.px[i];
                    break;
                case OP_LPOSY:
                    r[d] = b.py[i];
                    break;
                case OP_LPOSZ:
                    r[d] = b.pz[i];
                    break;
                case OP_LVEL:
                    r[d] = b.vx[i];
                    r[d + 1] = b.vy[i];
                    r[d + 2] = b.vz[i];
                    break;
                case OP_LVELX:
                    r[d] = b.vx[i];
                    break;
                case OP_LVELY:
                    r[d] = b.vy[i];
                    break;
                case OP_LVELZ:
                    r[d] = b.vz[i];
                    break;
                case OP_LENERGY:
                    r[d] = static_cast<double>(b.energy[i]);
                    break;
                case OP_LANCHOR:
                    r[d] = b.origin_x + b.anchor_dx[i];
                    r[d + 1] = b.origin_y + b.anchor_dy[i];
                    r[d + 2] = b.origin_z + b.anchor_dz[i];
                    break;
                case OP_LANCHORX:
                    r[d] = b.origin_x + b.anchor_dx[i];
                    break;
                case OP_LANCHORY:
                    r[d] = b.origin_y + b.anchor_dy[i];
                    break;
                case OP_LANCHORZ:
                    r[d] = b.origin_z + b.anchor_dz[i];
                    break;
                case OP_LLOCAL:
                    r[d] = b.px[i] - b.origin_x;
                    r[d + 1] = b.py[i] - b.origin_y;
                    r[d + 2] = b.pz[i] - b.origin_z;
                    break;
                case OP_LLOCALX:
                    r[d] = b.px[i] - b.origin_x;
                    break;
                case OP_LLOCALY:
                    r[d] = b.py[i] - b.origin_y;
                    break;
                case OP_LLOCALZ:
                    r[d] = b.pz[i] - b.origin_z;
                    break;
                case OP_LAXIS:
                {
                    double ax[3];
                    shape_axis(b, i, ax);
                    r[d] = ax[0];
                    r[d + 1] = ax[1];
                    r[d + 2] = ax[2];
                    break;
                }
                case OP_LAXISX:
                {
                    double ax[3];
                    shape_axis(b, i, ax);
                    r[d] = ax[0];
                    break;
                }
                case OP_LAXISY:
                {
                    double ax[3];
                    shape_axis(b, i, ax);
                    r[d] = ax[1];
                    break;
                }
                case OP_LAXISZ:
                {
                    double ax[3];
                    shape_axis(b, i, ax);
                    r[d] = ax[2];
                    break;
                }
                case OP_LTARGET:
                    r[d] = shape_target(b, i);
                    break;
                case OP_LR:
                    r[d] = shape_rnd(b, i);
                    break;
                case OP_LAGE:
                    r[d] = time;
                    break;

                // ------------------------------------------------ 粒子写
                case OP_SPOS:
                    b.px[i] = r[d];
                    b.py[i] = r[d + 1];
                    b.pz[i] = r[d + 2];
                    break;
                case OP_SPOSX:
                    b.px[i] = r[d];
                    break;
                case OP_SPOSY:
                    b.py[i] = r[d];
                    break;
                case OP_SPOSZ:
                    b.pz[i] = r[d];
                    break;
                case OP_SVEL:
                    b.vx[i] = r[d];
                    b.vy[i] = r[d + 1];
                    b.vz[i] = r[d + 2];
                    break;
                case OP_SVELX:
                    b.vx[i] = r[d];
                    break;
                case OP_SVELY:
                    b.vy[i] = r[d];
                    break;
                case OP_SVELZ:
                    b.vz[i] = r[d];
                    break;
                case OP_SENERGY:
                    b.energy[i] = static_cast<float>(r[d]);
                    break;

                // ------------------------------------------------ 标量算术
                case OP_ADD_N:
                    r[d] = r[a] + r[bb];
                    break;
                case OP_SUB_N:
                    r[d] = r[a] - r[bb];
                    break;
                case OP_MUL_N:
                    r[d] = r[a] * r[bb];
                    break;
                case OP_NEG_N:
                    r[d] = -r[a];
                    break;
                case OP_DIV_N:
                {
                    const double den = r[bb];
                    if (den == 0.0)
                    {
                        return fail(WMVM_ERR_DIV_ZERO, pc - 1, "除以零");
                    }
                    r[d] = r[a] / den;
                    break;
                }
                case OP_MOD_N:
                {
                    const double den = r[bb];
                    if (den == 0.0)
                    {
                        return fail(WMVM_ERR_MOD_ZERO, pc - 1, "取模的除数为零");
                    }
                    r[d] = std::fmod(r[a], den);
                    break;
                }

                // ------------------------------------------------ 向量算术
                case OP_ADD_V:
                    r[d] = r[a] + r[bb];
                    r[d + 1] = r[a + 1] + r[bb + 1];
                    r[d + 2] = r[a + 2] + r[bb + 2];
                    break;
                case OP_SUB_V:
                    r[d] = r[a] - r[bb];
                    r[d + 1] = r[a + 1] - r[bb + 1];
                    r[d + 2] = r[a + 2] - r[bb + 2];
                    break;
                case OP_SCALE:
                {
                    const double s = r[bb];
                    r[d] = r[a] * s;
                    r[d + 1] = r[a + 1] * s;
                    r[d + 2] = r[a + 2] * s;
                    break;
                }
                case OP_DIV_SCALE:
                {
                    const double den = r[bb];
                    if (den == 0.0)
                    {
                        return fail(WMVM_ERR_DIV_ZERO, pc - 1, "除以零");
                    }
                    r[d] = r[a] / den;
                    r[d + 1] = r[a + 1] / den;
                    r[d + 2] = r[a + 2] / den;
                    break;
                }
                case OP_NEG_V:
                    r[d] = -r[a];
                    r[d + 1] = -r[a + 1];
                    r[d + 2] = -r[a + 2];
                    break;

                // ------------------------------------------------ 比较与逻辑
                case OP_LT:
                    r[d] = r[a] < r[bb] ? 1.0 : 0.0;
                    break;
                case OP_LE:
                    r[d] = r[a] <= r[bb] ? 1.0 : 0.0;
                    break;
                case OP_GT:
                    r[d] = r[a] > r[bb] ? 1.0 : 0.0;
                    break;
                case OP_GE:
                    r[d] = r[a] >= r[bb] ? 1.0 : 0.0;
                    break;
                case OP_EQ_N:
                    r[d] = r[a] == r[bb] ? 1.0 : 0.0;
                    break;
                case OP_NE_N:
                    r[d] = r[a] != r[bb] ? 1.0 : 0.0;
                    break;
                case OP_AND:
                    r[d] = (r[a] != 0.0 && r[bb] != 0.0) ? 1.0 : 0.0;
                    break;
                case OP_OR:
                    r[d] = (r[a] != 0.0 || r[bb] != 0.0) ? 1.0 : 0.0;
                    break;
                case OP_NOT:
                    r[d] = r[a] == 0.0 ? 1.0 : 0.0;
                    break;
                case OP_SELECT:
                {
                    // word0: op|dst|cond|x，word1: y —— 两条路径都算完再挑（不是插值）
                    const int y = code[pc++];
                    r[d] = r[a] != 0.0 ? r[bb] : r[y];
                    break;
                }

                // ------------------------------------------------ 控制流
                case OP_JZ:
                {
                    const int t = code[pc++];
                    if (r[d] == 0.0)
                    {
                        pc = t;
                    }
                    break;
                }
                case OP_JNZ:
                {
                    const int t = code[pc++];
                    if (r[d] != 0.0)
                    {
                        pc = t;
                    }
                    break;
                }
                case OP_JMP:
                    pc = code[pc];
                    break;

                // ------------------------------------------------ 数学
                case OP_ABS:
                    r[d] = std::fabs(r[a]);
                    break;
                case OP_SQRT:
                    r[d] = std::sqrt(jmax(0.0, r[a]));
                    break;
                case OP_SIGN:
                    r[d] = r[a] > 0.0 ? 1.0 : (r[a] < 0.0 ? -1.0 : 0.0);
                    break;
                case OP_EXP:
                case OP_POW:
                case OP_ATAN2:
                    // 这三个要 fdlibm（StrictMath）逐位实现，下一版补；先明确报错，
                    // 不要偷偷用平台 libm —— 那会让双端/后续 native 对拍永久对不上。
                    return fail(WMVM_ERR_UNSUPPORTED, pc - 1, "exp/pow/atan2 还没实现");
                case OP_FLOOR:
                    r[d] = std::floor(r[a]);
                    break;
                case OP_ROUND:
                    r[d] = jround_double(r[a]);
                    break;
                case OP_SIN:
                    r[d] = table_sin(r[a]);
                    break;
                case OP_COS:
                    r[d] = table_cos(r[a]);
                    break;
                case OP_MIN:
                    r[d] = jmin(r[a], r[bb]);
                    break;
                case OP_MAX:
                    r[d] = jmax(r[a], r[bb]);
                    break;
                case OP_CLAMP:
                {
                    const int hi = code[pc++];
                    r[d] = jmax(r[bb], jmin(r[hi], r[a]));
                    break;
                }
                case OP_SMOOTHSTEP:
                {
                    const int x = code[pc++];
                    r[d] = smoothstep(r[a], r[bb], r[x]);
                    break;
                }
                case OP_LERP_N:
                {
                    const double t = r[code[pc++]];
                    const double a0 = r[a];
                    r[d] = a0 + (r[bb] - a0) * t;
                    break;
                }
                case OP_LERP_V:
                {
                    const double t = r[code[pc++]];
                    const double a0 = r[a];
                    const double a1 = r[a + 1];
                    const double a2 = r[a + 2];
                    r[d] = a0 + (r[bb] - a0) * t;
                    r[d + 1] = a1 + (r[bb + 1] - a1) * t;
                    r[d + 2] = a2 + (r[bb + 2] - a2) * t;
                    break;
                }

                // ------------------------------------------------ 向量函数
                case OP_DOT:
                    r[d] = r[a] * r[bb] + r[a + 1] * r[bb + 1] + r[a + 2] * r[bb + 2];
                    break;
                case OP_CROSS:
                {
                    const double ax0 = r[a], ay0 = r[a + 1], az0 = r[a + 2];
                    const double bx0 = r[bb], by0 = r[bb + 1], bz0 = r[bb + 2];
                    r[d] = ay0 * bz0 - az0 * by0;
                    r[d + 1] = az0 * bx0 - ax0 * bz0;
                    r[d + 2] = ax0 * by0 - ay0 * bx0;
                    break;
                }
                case OP_LEN:
                {
                    const double a0 = r[a], a1 = r[a + 1], a2 = r[a + 2];
                    r[d] = std::sqrt(a0 * a0 + a1 * a1 + a2 * a2);
                    break;
                }
                case OP_LEN_XZ:
                {
                    const double a0 = r[a], a2 = r[a + 2];
                    r[d] = std::sqrt(a0 * a0 + a2 * a2);
                    break;
                }
                case OP_NORMALIZE:
                {
                    const double vx = r[a], vy = r[a + 1], vz = r[a + 2];
                    const double l = std::sqrt(vx * vx + vy * vy + vz * vz);
                    if (l < 1.0E-9)
                    {
                        r[d] = r[d + 1] = r[d + 2] = 0.0;
                    }
                    else
                    {
                        r[d] = vx / l;
                        r[d + 1] = vy / l;
                        r[d + 2] = vz / l;
                    }
                    break;
                }
                case OP_DIST:
                {
                    const double dx = r[a] - r[bb];
                    const double dy = r[a + 1] - r[bb + 1];
                    const double dz = r[a + 2] - r[bb + 2];
                    r[d] = std::sqrt(dx * dx + dy * dy + dz * dz);
                    break;
                }
                case OP_TANGENT:
                case OP_RADIAL:
                    tangent_into(r, d, a, bb, -1, op == OP_TANGENT);
                    break;
                case OP_TANGENT_UP:
                case OP_RADIAL_UP:
                {
                    const int up = code[pc++];
                    const double ul = std::sqrt(r[up] * r[up] + r[up + 1] * r[up + 1]
                            + r[up + 2] * r[up + 2]);
                    if (!(ul > 1.0E-9))
                    {
                        return fail(WMVM_ERR_AXIS_ZERO, pc - 2,
                                "tangent / radial 的第三个参数（轴向）不能是零向量");
                    }
                    tangent_into(r, d, a, bb, up, op == OP_TANGENT_UP);
                    break;
                }
                case OP_ROTATE:
                {
                    const int angle = code[pc++];
                    rotate_into(r, d, a, bb, angle);
                    break;
                }
                case OP_PICK:
                    r[d] = r[a + bb];
                    break;
                case OP_NOISE:
                {
                    const int z = code[pc++];
                    const int stream = code[pc++];
                    r[d] = value3(seed, r[a], r[bb], r[z], jround_to_i(r[stream]));
                    break;
                }
                case OP_RAND:
                    r[d] = random_unit(seed, step, rand_seq++);
                    break;

                // ------------------------------------------------ 云级变量
                case OP_STF1:
                    fv[a] = r[bb];
                    break;
                case OP_STF3:
                    fv[a] = r[bb];
                    fv[a + 1] = r[bb + 1];
                    fv[a + 2] = r[bb + 2];
                    break;

                default:
                    return fail(WMVM_ERR_UNKNOWN_OP, pc - 1, "未知指令");
            }
        }
    }

    b.rand_seq = rand_seq;
    return rand_seq;
}

}  // namespace wmvm

#endif  // WMVM_VM_INL_H
