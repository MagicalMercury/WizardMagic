/*
 * WMVM 的 C ABI 实现：程序缓存、sin 表、lane 选择、错误归位。
 *
 * 三个执行器共用同一份 opcode / Program / sin 表：
 *   1 lane   —— 标量内核（vm-inl.h）：含 rand / 写云级变量的段只能走它，
 *               也是向量内核处理不了的尾巴；
 *   4 lane   —— AVX2 目标下的向量内核（ScalableTag<double> 宽度 = 4）；
 *   8 lane   —— AVX-512 目标下的同一份向量内核（宽度 = 8）。
 * 向量内核由 Highway 运行期分派：同一份源码按目标各编一遍。
 */
#include "wmvm.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "vm-inl.h"   // Program / 标量内核 / sin 表（只编一次：它自己有 include guard）

#undef HWY_TARGET_INCLUDE
#define HWY_TARGET_INCLUDE "vm.cpp"
#include "hwy/foreach_target.h"
#include "hwy/highway.h"

HWY_BEFORE_NAMESPACE();
namespace hwy
{
namespace HWY_NAMESPACE
{

#include "vm-vec-inl.h"

/** 向量内核入口（每个目标一份） */
int VecRun(const ::wmvm::Program& p, WmvmBatch& b, double* fv, const double* hv)
{
    return wmvm_vec::Run(ScalableTag<double>(), p, b, fv, hv);
}

/** 本目标的 double lane 数（AVX2 = 4，AVX-512 = 8，SSE2 = 2） */
int VecLanes()
{
    return static_cast<int>(Lanes(ScalableTag<double>()));
}

const char* VecTargetName()
{
    return TargetName(HWY_TARGET);
}

}  // namespace HWY_NAMESPACE

#if HWY_ONCE
HWY_EXPORT(VecRun);
HWY_EXPORT(VecLanes);
HWY_EXPORT(VecTargetName);
#endif

}  // namespace hwy
HWY_AFTER_NAMESPACE();

#if HWY_ONCE

using namespace hwy;   // HWY_DYNAMIC_DISPATCH 展开出来的分派表在 hwy:: 里

namespace wmvm
{
const float* g_sin_table = nullptr;
int g_sin_count = 0;
thread_local char g_error[256] = {0};

/** 0 = 自动（按 CPU 选 4/8）；1 = 强制标量 */
int g_forced_lanes = 0;
}  // namespace wmvm

struct WmvmProgram
{
    wmvm::Program prog;
    int32_t* code = nullptr;
    double* consts = nullptr;
};

extern "C"
{

int wmvm_init(void)
{
    // 触发一次运行期分派，顺带把"选中的目标"初始化好
    (void) HWY_DYNAMIC_DISPATCH(VecLanes)();
    return 0;
}

const char* wmvm_target_name(void)
{
    return HWY_DYNAMIC_DISPATCH(VecTargetName)();
}

int wmvm_lanes(void)
{
    return HWY_DYNAMIC_DISPATCH(VecLanes)();
}

int wmvm_force_lanes(int lanes)
{
    if (lanes == 0 || lanes == 1)
    {
        wmvm::g_forced_lanes = lanes;
        return 1;
    }
    if (lanes == wmvm_lanes())
    {
        wmvm::g_forced_lanes = lanes;
        return lanes;
    }
    return 0;   // 这个目标没有这么多 lane
}

int wmvm_set_sin_table(const float* table, int count)
{
    if (table == nullptr || count != 65536)
    {
        std::snprintf(wmvm::g_error, sizeof(wmvm::g_error),
                "sin 表必须是 65536 项（收到 %d）", count);
        return -1;
    }
    wmvm::g_sin_table = table;
    wmvm::g_sin_count = count;
    return 0;
}

WmvmProgram* wmvm_program_create(const int32_t* code, int ncode,
                                 const double* consts, int nconsts)
{
    if (code == nullptr || ncode <= 0)
    {
        std::snprintf(wmvm::g_error, sizeof(wmvm::g_error), "指令流为空");
        return nullptr;
    }
    auto* p = new WmvmProgram();
    p->code = static_cast<int32_t*>(std::malloc(sizeof(int32_t) * ncode));
    std::memcpy(p->code, code, sizeof(int32_t) * ncode);
    if (consts != nullptr && nconsts > 0)
    {
        p->consts = static_cast<double*>(std::malloc(sizeof(double) * nconsts));
        std::memcpy(p->consts, consts, sizeof(double) * nconsts);
    }
    p->prog.code = p->code;
    p->prog.ncode = ncode;
    p->prog.consts = p->consts;
    p->prog.nconsts = nconsts;

    // rand / 写云级变量：顺序语义绑死粒子顺序，只能 1 lane
    for (int pc = 0; pc < ncode;)
    {
        const int op = p->code[pc] >> 24;
        if (op == wmvm::OP_RAND || op == wmvm::OP_STF1 || op == wmvm::OP_STF3)
        {
            p->prog.lane1_only = true;
        }
        if (op == wmvm::OP_NOISE || op == wmvm::OP_JZ || op == wmvm::OP_JMP || op == wmvm::OP_JNZ)
        {
            p->prog.vec_ok = false;
        }
        pc += wmvm::op_length(op);
    }
    return p;
}

void wmvm_program_destroy(WmvmProgram* p)
{
    if (p == nullptr)
    {
        return;
    }
    std::free(p->code);
    std::free(p->consts);
    delete p;
}

int wmvm_run(const WmvmProgram* p, WmvmBatch* b, double* fv, const double* hv)
{
    if (p == nullptr || b == nullptr || fv == nullptr || hv == nullptr)
    {
        std::snprintf(wmvm::g_error, sizeof(wmvm::g_error), "参数为空");
        return -(0 * 8 + WMVM_ERR_UNKNOWN_OP);
    }
    const int total = b->to - b->from;
    if (total <= 0)
    {
        return b->rand_seq;
    }

    int lanes = wmvm::g_forced_lanes;
    if (lanes == 0)
    {
        lanes = HWY_DYNAMIC_DISPATCH(VecLanes)();
    }
    if (p->prog.lane1_only || !p->prog.vec_ok || lanes <= 1 || total < lanes)
    {
        return wmvm::run_1lane(p->prog, *b, fv, hv);
    }

    // 向量部分：[from, from + total/lanes*lanes)；剩下的尾巴交给标量内核
    const int vectorTo = b->from + (total / lanes) * lanes;
    WmvmBatch head = *b;
    head.to = vectorTo;
    int rc = HWY_DYNAMIC_DISPATCH(VecRun)(p->prog, head, fv, hv);
    if (rc < 0)
    {
        return rc;
    }
    b->rand_seq = head.rand_seq;
    if (vectorTo < b->to)
    {
        WmvmBatch tail = *b;
        tail.from = vectorTo;
        tail.to = b->to;
        rc = wmvm::run_1lane(p->prog, tail, fv, hv);
        b->rand_seq = tail.rand_seq;
        if (rc < 0)
        {
            return rc;
        }
    }
    return b->rand_seq;
}

const char* wmvm_last_error(void)
{
    return wmvm::g_error;
}

}  // extern "C"

#endif  // HWY_ONCE
