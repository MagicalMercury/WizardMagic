/*
 * WMVM 向量内核（4 通道 = AVX2，8 通道 = AVX-512）。
 *
 * 被 `vm.cpp` 在 `namespace hwy { namespace HWY_NAMESPACE {` 里 include，所以这里的
 * `Add/Mul/...` 都是当前 Highway 目标的算子。lane 数由目标决定（`Lanes(d)`）。
 *
 * <b>语义</b>：与标量内核逐条一致——每 lane 有自己的 pc，按 pc 分组执行，组外的 lane
 * 不写寄存器（`IfThenElse` 掩码写）。所以"怎么分组"不影响任何一个 lane 的结果，
 * 与标量版逐位相同（和 Java 的 `RegVm4` 是同一个论证）。
 *
 * <b>为什么能快</b>：同一批粒子是连续下标，粒子 SoA 数组可以整段 `LoadU/StoreU`，
 * 一次指令分派处理 L 颗粒子；分支一致时掩码全真，几乎没有额外开销。
 */

#include <climits>
#include <cmath>
#include <cstring>

#include "jmath.h"
#include "noise.h"
#include "wmvm.h"

namespace wmvm_vec
{

/** 一条 lane 一个 pc；double 最多 8 条（AVX-512 512/64） */
constexpr int MAXL = 8;

inline bool bit_of(uint32_t bits, int l)
{
    return ((bits >> l) & 1u) != 0;
}

template <class D>
int Run(D d, const ::wmvm::Program& p, WmvmBatch& b, double* fv, const double* hv)
{
    using V = Vec<D>;
    using M = Mask<D>;
    const int L = static_cast<int>(Lanes(d));
    const int32_t* code = p.code;
    const int n = p.ncode;
    const double* k = p.consts;
    const int64_t seed = b.seed;
    const int step = b.age_step;
    const double time = b.time;

    const V zero = Zero(d);
    const V one = Set(d, 1.0);
    const V minusOne = Set(d, -1.0);
    const V negZero = Set(d, -0.0);

    // 帧：每个寄存器一个向量，lane 之间互不重叠；调用内复用（先写后读）
    V r[WMVM_MAX_REG];
    for (int i = 0; i < WMVM_MAX_REG; ++i)
    {
        r[i] = zero;
    }

    // Java 语义的 min/max（NaN 传播、两个 0 时取 -0.0 / 取非负）
    auto vmin = [&](V x, V y) -> V
    {
        V res = IfThenElse(Le(x, y), x, y);
        res = IfThenElse(IsNaN(x), x, res);
        const M bothZero = And(Eq(x, zero), Eq(y, zero));
        const M negAny = Or(Lt(CopySign(one, x), zero), Lt(CopySign(one, y), zero));
        return IfThenElse(bothZero, IfThenElse(negAny, negZero, zero), res);
    };
    auto vmax = [&](V x, V y) -> V
    {
        V res = IfThenElse(Ge(x, y), x, y);
        res = IfThenElse(IsNaN(x), x, res);
        const M bothZero = And(Eq(x, zero), Eq(y, zero));
        const M negX = Lt(CopySign(one, x), zero);
        return IfThenElse(And(bothZero, negX), y, res);
    };

    const M mAll = FirstN(d, static_cast<size_t>(L));

    int rand_seq = b.rand_seq;
    int pcs[MAXL];
    double tmp[MAXL];

    // 逐组跑 [from, to)；尾部不足 L 的粒子交给标量内核（由调用方处理）
    for (int base = b.from; base + L <= b.to; base += L)
    {
        // 粒子 SoA 数组是整条数组（绝对下标）；axis/target/rnd 是"本块"预算的（块内下标）
        const int lbase = base - b.from;
        uint32_t live = (1u << L) - 1u;
        for (int l = 0; l < L; ++l)
        {
            pcs[l] = 0;
        }
        // 同步模式：所有 lane 都在同一个 pc（分歧极罕见）。这时不扫描、不建掩码，
        // 直接按"整组活跃"执行——这是向量化真正能拿到收益的前提。
        bool diverged = false;

        while (live != 0u)
        {
            int ip;
            uint32_t group;
            bool full;
            M m;
            if (!diverged)
            {
                ip = pcs[0];
                if (ip >= n)
                {
                    break;
                }
                group = live;
                full = true;
                m = mAll;
            }
            else
            {
                ip = INT_MAX;
                for (int l = 0; l < L; ++l)
                {
                    if (bit_of(live, l) && pcs[l] < ip)
                    {
                        ip = pcs[l];
                    }
                }
                if (ip == INT_MAX)
                {
                    break;
                }
                group = 0u;
                for (int l = 0; l < L; ++l)
                {
                    if (bit_of(live, l) && pcs[l] == ip)
                    {
                        group |= 1u << l;
                    }
                }
                full = (group == live);
                const uint8_t mb = static_cast<uint8_t>(group);
                m = full ? mAll : LoadMaskBits(d, &mb);
            }

            const int32_t w = code[ip];
            const int op = w >> 24;
            const int dst = (w >> 16) & 0xFF;
            const int a = (w >> 8) & 0xFF;
            const int bb = w & 0xFF;
            const int next = ip + ::wmvm::op_length(op);

            auto set = [&](int idx, V v)
            {
                r[idx] = full ? v : IfThenElse(m, v, r[idx]);
            };
            auto set3 = [&](int idx, V x, V y, V z)
            {
                set(idx, x);
                set(idx + 1, y);
                set(idx + 2, z);
            };

            switch (op)
            {
                // ------------------------------------------------ 取数
                case ::wmvm::OP_LDK:
                    set(dst, Set(d, k[code[ip + 1]]));
                    break;
                case ::wmvm::OP_MOV:
                    set(dst, r[a]);
                    break;
                case ::wmvm::OP_MOV3:
                    set3(dst, r[a], r[a + 1], r[a + 2]);
                    break;
                case ::wmvm::OP_LDF1:
                    set(dst, Set(d, fv[a]));
                    break;
                case ::wmvm::OP_LDF3:
                    set3(dst, Set(d, fv[a]), Set(d, fv[a + 1]), Set(d, fv[a + 2]));
                    break;
                case ::wmvm::OP_LDH1:
                    set(dst, Set(d, hv[a]));
                    break;
                case ::wmvm::OP_LDH3:
                    set3(dst, Set(d, hv[a]), Set(d, hv[a + 1]), Set(d, hv[a + 2]));
                    break;

                // ------------------------------------------------ 粒子读
                case ::wmvm::OP_LPOS:
                    set3(dst, LoadU(d, b.px + base), LoadU(d, b.py + base), LoadU(d, b.pz + base));
                    break;
                case ::wmvm::OP_LPOSX:
                    set(dst, LoadU(d, b.px + base));
                    break;
                case ::wmvm::OP_LPOSY:
                    set(dst, LoadU(d, b.py + base));
                    break;
                case ::wmvm::OP_LPOSZ:
                    set(dst, LoadU(d, b.pz + base));
                    break;
                case ::wmvm::OP_LVEL:
                    set3(dst, LoadU(d, b.vx + base), LoadU(d, b.vy + base), LoadU(d, b.vz + base));
                    break;
                case ::wmvm::OP_LVELX:
                    set(dst, LoadU(d, b.vx + base));
                    break;
                case ::wmvm::OP_LVELY:
                    set(dst, LoadU(d, b.vy + base));
                    break;
                case ::wmvm::OP_LVELZ:
                    set(dst, LoadU(d, b.vz + base));
                    break;
                case ::wmvm::OP_LENERGY:
                {
                    const Rebind<float, D> df;
                    set(dst, PromoteTo(d, LoadU(df, b.energy + base)));
                    break;
                }
                case ::wmvm::OP_LANCHOR:
                    set3(dst,
                            Add(Set(d, b.origin_x), LoadU(d, b.anchor_dx + base)),
                            Add(Set(d, b.origin_y), LoadU(d, b.anchor_dy + base)),
                            Add(Set(d, b.origin_z), LoadU(d, b.anchor_dz + base)));
                    break;
                case ::wmvm::OP_LANCHORX:
                    set(dst, Add(Set(d, b.origin_x), LoadU(d, b.anchor_dx + base)));
                    break;
                case ::wmvm::OP_LANCHORY:
                    set(dst, Add(Set(d, b.origin_y), LoadU(d, b.anchor_dy + base)));
                    break;
                case ::wmvm::OP_LANCHORZ:
                    set(dst, Add(Set(d, b.origin_z), LoadU(d, b.anchor_dz + base)));
                    break;
                case ::wmvm::OP_LLOCAL:
                    set3(dst,
                            Sub(LoadU(d, b.px + base), Set(d, b.origin_x)),
                            Sub(LoadU(d, b.py + base), Set(d, b.origin_y)),
                            Sub(LoadU(d, b.pz + base), Set(d, b.origin_z)));
                    break;
                case ::wmvm::OP_LLOCALX:
                    set(dst, Sub(LoadU(d, b.px + base), Set(d, b.origin_x)));
                    break;
                case ::wmvm::OP_LLOCALY:
                    set(dst, Sub(LoadU(d, b.py + base), Set(d, b.origin_y)));
                    break;
                case ::wmvm::OP_LLOCALZ:
                    set(dst, Sub(LoadU(d, b.pz + base), Set(d, b.origin_z)));
                    break;
                case ::wmvm::OP_LAXIS:
                    set3(dst, LoadU(d, b.axis_x + lbase), LoadU(d, b.axis_y + lbase),
                            LoadU(d, b.axis_z + lbase));
                    break;
                case ::wmvm::OP_LAXISX:
                    set(dst, LoadU(d, b.axis_x + lbase));
                    break;
                case ::wmvm::OP_LAXISY:
                    set(dst, LoadU(d, b.axis_y + lbase));
                    break;
                case ::wmvm::OP_LAXISZ:
                    set(dst, LoadU(d, b.axis_z + lbase));
                    break;
                case ::wmvm::OP_LTARGET:
                    set(dst, LoadU(d, b.target + lbase));
                    break;
                case ::wmvm::OP_LR:
                    set(dst, LoadU(d, b.rnd + lbase));
                    break;
                case ::wmvm::OP_LAGE:
                    set(dst, Set(d, time));
                    break;

                // ------------------------------------------------ 粒子写（掩码写）
                case ::wmvm::OP_SPOS:
                    BlendedStore(r[dst], m, d, b.px + base);
                    BlendedStore(r[dst + 1], m, d, b.py + base);
                    BlendedStore(r[dst + 2], m, d, b.pz + base);
                    break;
                case ::wmvm::OP_SPOSX:
                    BlendedStore(r[dst], m, d, b.px + base);
                    break;
                case ::wmvm::OP_SPOSY:
                    BlendedStore(r[dst], m, d, b.py + base);
                    break;
                case ::wmvm::OP_SPOSZ:
                    BlendedStore(r[dst], m, d, b.pz + base);
                    break;
                case ::wmvm::OP_SVEL:
                    BlendedStore(r[dst], m, d, b.vx + base);
                    BlendedStore(r[dst + 1], m, d, b.vy + base);
                    BlendedStore(r[dst + 2], m, d, b.vz + base);
                    break;
                case ::wmvm::OP_SVELX:
                    BlendedStore(r[dst], m, d, b.vx + base);
                    break;
                case ::wmvm::OP_SVELY:
                    BlendedStore(r[dst], m, d, b.vy + base);
                    break;
                case ::wmvm::OP_SVELZ:
                    BlendedStore(r[dst], m, d, b.vz + base);
                    break;
                case ::wmvm::OP_SENERGY:
                {
                    Store(r[dst], d, tmp);
                    for (int l = 0; l < L; ++l)
                    {
                        if (bit_of(group, l))
                        {
                            b.energy[base + l] = static_cast<float>(tmp[l]);
                        }
                    }
                    break;
                }

                // ------------------------------------------------ 标量算术
                case ::wmvm::OP_ADD_N:
                    set(dst, Add(r[a], r[bb]));
                    break;
                case ::wmvm::OP_SUB_N:
                    set(dst, Sub(r[a], r[bb]));
                    break;
                case ::wmvm::OP_MUL_N:
                    set(dst, Mul(r[a], r[bb]));
                    break;
                case ::wmvm::OP_NEG_N:
                    set(dst, Sub(zero, r[a]));
                    break;
                case ::wmvm::OP_DIV_N:
                case ::wmvm::OP_DIV_SCALE:
                {
                    const M zeroDen = And(m, Eq(r[bb], zero));
                    if (!AllFalse(d, zeroDen))
                    {
                        return ::wmvm::fail(WMVM_ERR_DIV_ZERO, ip, "除以零");
                    }
                    if (op == ::wmvm::OP_DIV_N)
                    {
                        set(dst, Div(r[a], r[bb]));
                    }
                    else
                    {
                        set3(dst, Div(r[a], r[bb]), Div(r[a + 1], r[bb]), Div(r[a + 2], r[bb]));
                    }
                    break;
                }
                case ::wmvm::OP_MOD_N:
                {
                    const M zeroDen = And(m, Eq(r[bb], zero));
                    if (!AllFalse(d, zeroDen))
                    {
                        return ::wmvm::fail(WMVM_ERR_MOD_ZERO, ip, "取模的除数为零");
                    }
                    // 很冷，逐 lane 算（没有向量 fmod）
                    double lhs[MAXL];
                    double rhs[MAXL];
                    double out[MAXL];
                    Store(r[a], d, lhs);
                    Store(r[bb], d, rhs);
                    for (int l = 0; l < L; ++l)
                    {
                        out[l] = std::fmod(lhs[l], rhs[l]);
                    }
                    set(dst, LoadU(d, out));
                    break;
                }

                // ------------------------------------------------ 向量算术
                case ::wmvm::OP_ADD_V:
                    set3(dst, Add(r[a], r[bb]), Add(r[a + 1], r[bb + 1]), Add(r[a + 2], r[bb + 2]));
                    break;
                case ::wmvm::OP_SUB_V:
                    set3(dst, Sub(r[a], r[bb]), Sub(r[a + 1], r[bb + 1]), Sub(r[a + 2], r[bb + 2]));
                    break;
                case ::wmvm::OP_SCALE:
                {
                    const V s = r[bb];
                    set3(dst, Mul(r[a], s), Mul(r[a + 1], s), Mul(r[a + 2], s));
                    break;
                }
                case ::wmvm::OP_NEG_V:
                    set3(dst, Sub(zero, r[a]), Sub(zero, r[a + 1]), Sub(zero, r[a + 2]));
                    break;

                // ------------------------------------------------ 比较与逻辑
                case ::wmvm::OP_LT:
                    set(dst, IfThenElse(Lt(r[a], r[bb]), one, zero));
                    break;
                case ::wmvm::OP_LE:
                    set(dst, IfThenElse(Le(r[a], r[bb]), one, zero));
                    break;
                case ::wmvm::OP_GT:
                    set(dst, IfThenElse(Gt(r[a], r[bb]), one, zero));
                    break;
                case ::wmvm::OP_GE:
                    set(dst, IfThenElse(Ge(r[a], r[bb]), one, zero));
                    break;
                case ::wmvm::OP_EQ_N:
                    set(dst, IfThenElse(Eq(r[a], r[bb]), one, zero));
                    break;
                case ::wmvm::OP_NE_N:
                    set(dst, IfThenElse(Ne(r[a], r[bb]), one, zero));
                    break;
                case ::wmvm::OP_AND:
                    set(dst, IfThenElse(And(Ne(r[a], zero), Ne(r[bb], zero)), one, zero));
                    break;
                case ::wmvm::OP_OR:
                    set(dst, IfThenElse(Or(Ne(r[a], zero), Ne(r[bb], zero)), one, zero));
                    break;
                case ::wmvm::OP_NOT:
                    set(dst, IfThenElse(Eq(r[a], zero), one, zero));
                    break;

                // ------------------------------------------------ 控制流
                case ::wmvm::OP_JZ:
                case ::wmvm::OP_JNZ:
                case ::wmvm::OP_JMP:
                {
                    const int target = code[ip + 1];
                    if (op == ::wmvm::OP_JMP)
                    {
                        for (int l = 0; l < L; ++l)
                        {
                            if (bit_of(group, l))
                            {
                                pcs[l] = target;
                            }
                        }
                    }
                    else
                    {
                        Store(r[dst], d, tmp);
                        for (int l = 0; l < L; ++l)
                        {
                            if (!bit_of(group, l))
                            {
                                continue;
                            }
                            const bool take = (op == ::wmvm::OP_JZ) ? (tmp[l] == 0.0) : (tmp[l] != 0.0);
                            pcs[l] = take ? target : next;
                        }
                    }
                    break;
                }

                // ------------------------------------------------ 数学
                case ::wmvm::OP_ABS:
                    set(dst, Abs(r[a]));
                    break;
                case ::wmvm::OP_SQRT:
                    set(dst, Sqrt(vmax(zero, r[a])));
                    break;
                case ::wmvm::OP_SIGN:
                    set(dst, IfThenElse(Gt(r[a], zero), one,
                            IfThenElse(Lt(r[a], zero), minusOne, zero)));
                    break;
                case ::wmvm::OP_FLOOR:
                    set(dst, Floor(r[a]));
                    break;
                case ::wmvm::OP_ROUND:
                {
                    // 逐 lane：jround 是位级算法，没有向量版本
                    Store(r[a], d, tmp);
                    double out[MAXL];
                    for (int l = 0; l < L; ++l)
                    {
                        out[l] = ::wmvm::jround_double(tmp[l]);
                    }
                    set(dst, LoadU(d, out));
                    break;
                }
                case ::wmvm::OP_SIN:
                case ::wmvm::OP_COS:
                {
                    // 查同一张 65536 项表；表索引是整数运算，逐 lane 做（gather 更慢）
                    Store(r[a], d, tmp);
                    double out[MAXL];
                    for (int l = 0; l < L; ++l)
                    {
                        out[l] = (op == ::wmvm::OP_SIN) ? ::wmvm::table_sin(tmp[l])
                                                        : ::wmvm::table_cos(tmp[l]);
                    }
                    set(dst, LoadU(d, out));
                    break;
                }
                case ::wmvm::OP_MIN:
                    set(dst, vmin(r[a], r[bb]));
                    break;
                case ::wmvm::OP_MAX:
                    set(dst, vmax(r[a], r[bb]));
                    break;
                case ::wmvm::OP_CLAMP:
                {
                    const int hi = code[ip + 1];
                    set(dst, vmax(r[bb], vmin(r[hi], r[a])));
                    break;
                }
                case ::wmvm::OP_SMOOTHSTEP:
                {
                    const int x = code[ip + 1];
                    const V lo = r[a];
                    const V hi = r[bb];
                    const V xx = r[x];
                    // b == a 时给 0（与标量版一致）
                    const M same = Eq(hi, lo);
                    const V t = vmax(zero, vmin(one, Div(Sub(xx, lo), Sub(hi, lo))));
                    const V ss = Mul(Mul(t, t), Sub(Set(d, 3.0), Mul(Set(d, 2.0), t)));
                    set(dst, IfThenElse(same, zero, ss));
                    break;
                }
                case ::wmvm::OP_LERP_N:
                {
                    const V t = r[code[ip + 1]];
                    const V a0 = r[a];
                    set(dst, Add(a0, Mul(Sub(r[bb], a0), t)));
                    break;
                }
                case ::wmvm::OP_LERP_V:
                {
                    const V t = r[code[ip + 1]];
                    const V a0 = r[a];
                    const V a1 = r[a + 1];
                    const V a2 = r[a + 2];
                    set3(dst,
                            Add(a0, Mul(Sub(r[bb], a0), t)),
                            Add(a1, Mul(Sub(r[bb + 1], a1), t)),
                            Add(a2, Mul(Sub(r[bb + 2], a2), t)));
                    break;
                }

                // ------------------------------------------------ 向量函数
                case ::wmvm::OP_DOT:
                    set(dst, Add(Add(Mul(r[a], r[bb]), Mul(r[a + 1], r[bb + 1])),
                            Mul(r[a + 2], r[bb + 2])));
                    break;
                case ::wmvm::OP_CROSS:
                {
                    const V ax0 = r[a];
                    const V ay0 = r[a + 1];
                    const V az0 = r[a + 2];
                    const V bx0 = r[bb];
                    const V by0 = r[bb + 1];
                    const V bz0 = r[bb + 2];
                    set3(dst, Sub(Mul(ay0, bz0), Mul(az0, by0)),
                            Sub(Mul(az0, bx0), Mul(ax0, bz0)),
                            Sub(Mul(ax0, by0), Mul(ay0, bx0)));
                    break;
                }
                case ::wmvm::OP_LEN:
                    set(dst, Sqrt(Add(Add(Mul(r[a], r[a]), Mul(r[a + 1], r[a + 1])),
                            Mul(r[a + 2], r[a + 2]))));
                    break;
                case ::wmvm::OP_LEN_XZ:
                    set(dst, Sqrt(Add(Mul(r[a], r[a]), Mul(r[a + 2], r[a + 2]))));
                    break;
                case ::wmvm::OP_NORMALIZE:
                {
                    const V vx = r[a];
                    const V vy = r[a + 1];
                    const V vz = r[a + 2];
                    const V len = Sqrt(Add(Add(Mul(vx, vx), Mul(vy, vy)), Mul(vz, vz)));
                    const M tiny = Lt(len, Set(d, 1.0E-9));
                    const V safe = IfThenElse(tiny, one, len);
                    set3(dst,
                            IfThenElse(tiny, zero, Div(vx, safe)),
                            IfThenElse(tiny, zero, Div(vy, safe)),
                            IfThenElse(tiny, zero, Div(vz, safe)));
                    break;
                }
                case ::wmvm::OP_DIST:
                {
                    const V dx = Sub(r[a], r[bb]);
                    const V dy = Sub(r[a + 1], r[bb + 1]);
                    const V dz = Sub(r[a + 2], r[bb + 2]);
                    set(dst, Sqrt(Add(Add(Mul(dx, dx), Mul(dy, dy)), Mul(dz, dz))));
                    break;
                }
                case ::wmvm::OP_TANGENT:
                case ::wmvm::OP_RADIAL:
                case ::wmvm::OP_TANGENT_UP:
                case ::wmvm::OP_RADIAL_UP:
                {
                    const bool tangent = (op == ::wmvm::OP_TANGENT || op == ::wmvm::OP_TANGENT_UP);
                    const int up = (op == ::wmvm::OP_TANGENT_UP || op == ::wmvm::OP_RADIAL_UP)
                            ? code[ip + 1] : -1;
                    V ux = zero;
                    V uy = one;
                    V uz = zero;
                    if (up >= 0)
                    {
                        const V ul = Sqrt(Add(Add(Mul(r[up], r[up]), Mul(r[up + 1], r[up + 1])),
                                Mul(r[up + 2], r[up + 2])));
                        const M tiny = Not(Gt(ul, Set(d, 1.0E-9)));
                        if (!AllFalse(d, And(m, tiny)))
                        {
                            return ::wmvm::fail(WMVM_ERR_AXIS_ZERO, ip,
                                    "tangent / radial 的第三个参数（轴向）不能是零向量");
                        }
                        const V safe = IfThenElse(tiny, one, ul);
                        ux = Div(r[up], safe);
                        uy = Div(r[up + 1], safe);
                        uz = Div(r[up + 2], safe);
                    }
                    const V rx = Sub(r[bb], r[a]);
                    const V ry = Sub(r[bb + 1], r[a + 1]);
                    const V rz = Sub(r[bb + 2], r[a + 2]);
                    const V axial = Add(Add(Mul(rx, ux), Mul(ry, uy)), Mul(rz, uz));
                    const V px = Sub(rx, Mul(ux, axial));
                    const V py = Sub(ry, Mul(uy, axial));
                    const V pz = Sub(rz, Mul(uz, axial));
                    const V l = Sqrt(Add(Add(Mul(px, px), Mul(py, py)), Mul(pz, pz)));
                    const M tinyL = Lt(l, Set(d, 1.0E-9));
                    const V safeL = IfThenElse(tinyL, one, l);
                    if (tangent)
                    {
                        set3(dst,
                                IfThenElse(tinyL, zero, Div(Sub(Mul(py, uz), Mul(pz, uy)), safeL)),
                                IfThenElse(tinyL, zero, Div(Sub(Mul(pz, ux), Mul(px, uz)), safeL)),
                                IfThenElse(tinyL, zero, Div(Sub(Mul(px, uy), Mul(py, ux)), safeL)));
                    }
                    else
                    {
                        set3(dst,
                                IfThenElse(tinyL, zero, Div(px, safeL)),
                                IfThenElse(tinyL, zero, Div(py, safeL)),
                                IfThenElse(tinyL, zero, Div(pz, safeL)));
                    }
                    break;
                }
                case ::wmvm::OP_ROTATE:
                {
                    const int angleReg = code[ip + 1];
                    const V kx0 = r[bb];
                    const V ky0 = r[bb + 1];
                    const V kz0 = r[bb + 2];
                    const V kl = Sqrt(Add(Add(Mul(kx0, kx0), Mul(ky0, ky0)), Mul(kz0, kz0)));
                    const M tiny = Not(Gt(kl, Set(d, 1.0E-9)));
                    const V safe = IfThenElse(tiny, one, kl);
                    const V kx = IfThenElse(tiny, zero, Div(kx0, safe));
                    const V ky = IfThenElse(tiny, zero, Div(ky0, safe));
                    const V kz = IfThenElse(tiny, zero, Div(kz0, safe));
                    // cos/sin 查表：逐 lane
                    Store(r[angleReg], d, tmp);
                    double cs[MAXL];
                    double sn[MAXL];
                    for (int l = 0; l < L; ++l)
                    {
                        cs[l] = ::wmvm::table_cos(tmp[l]);
                        sn[l] = ::wmvm::table_sin(tmp[l]);
                    }
                    const V c = LoadU(d, cs);
                    const V s = LoadU(d, sn);
                    const V vx = r[a];
                    const V vy = r[a + 1];
                    const V vz = r[a + 2];
                    const V cx = Sub(Mul(ky, vz), Mul(kz, vy));
                    const V cy = Sub(Mul(kz, vx), Mul(kx, vz));
                    const V cz = Sub(Mul(kx, vy), Mul(ky, vx));
                    const V dot = Add(Add(Mul(kx, vx), Mul(ky, vy)), Mul(kz, vz));
                    const V t = Sub(one, c);
                    // 轴退化时原样返回
                    const V ox = IfThenElse(tiny, vx, Add(Add(Mul(vx, c), Mul(cx, s)), Mul(Mul(kx, dot), t)));
                    const V oy = IfThenElse(tiny, vy, Add(Add(Mul(vy, c), Mul(cy, s)), Mul(Mul(ky, dot), t)));
                    const V oz = IfThenElse(tiny, vz, Add(Add(Mul(vz, c), Mul(cz, s)), Mul(Mul(kz, dot), t)));
                    set3(dst, ox, oy, oz);
                    break;
                }
                case ::wmvm::OP_PICK:
                    set(dst, r[a + bb]);
                    break;
                case ::wmvm::OP_NOISE:
                {
                    const int z = code[ip + 1];
                    const int stream = code[ip + 2];
                    double xs[MAXL];
                    double ys[MAXL];
                    double zs[MAXL];
                    double ss[MAXL];
                    double out[MAXL];
                    Store(r[a], d, xs);
                    Store(r[bb], d, ys);
                    Store(r[z], d, zs);
                    Store(r[stream], d, ss);
                    for (int l = 0; l < L; ++l)
                    {
                        out[l] = ::wmvm::value3(seed, xs[l], ys[l], zs[l],
                                ::wmvm::jround_to_i(ss[l]));
                    }
                    set(dst, LoadU(d, out));
                    break;
                }

                // 这三条在 Java 侧就被拦下了（nativeUnsafe），走不到这里
                case ::wmvm::OP_EXP:
                case ::wmvm::OP_POW:
                case ::wmvm::OP_ATAN2:
                case ::wmvm::OP_RAND:
                case ::wmvm::OP_STF1:
                case ::wmvm::OP_STF3:
                    return ::wmvm::fail(WMVM_ERR_UNSUPPORTED, ip, "这条指令不该进向量内核");

                default:
                    return ::wmvm::fail(WMVM_ERR_UNKNOWN_OP, ip, "未知指令");
            }

            // 推进（分支已经改过 pcs；其余按指令长度）
            for (int l = 0; l < L; ++l)
            {
                if (!bit_of(group, l))
                {
                    continue;
                }
                if (pcs[l] == ip)
                {
                    pcs[l] = next;
                }
                if (pcs[l] >= n)
                {
                    live &= ~(1u << l);
                }
            }

            // 分歧/重同步：所有 live lane 的 pc 一样就回到快路径
            bool same = true;
            for (int l = 1; l < L; ++l)
            {
                if (pcs[l] != pcs[0])
                {
                    same = false;
                    break;
                }
            }
            diverged = !same;
        }
    }

    b.rand_seq = rand_seq;
    return rand_seq;
}

}  // namespace wmvm_vec

