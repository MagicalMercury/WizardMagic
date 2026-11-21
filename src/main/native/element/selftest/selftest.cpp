/*
 * 元素平流原生实现的自检：语义边界 + 标量/向量逐位对拍 + 成本基准。
 *
 * 与 Java 的**逐位对拍**在 Java 侧做（`verify/ElementAdvectCheck`，走 JNI 调同一份代码），
 * 这里守三件东西：
 *   1. 几条明确的语义（无流 → 只抬到 base；全零格不许被抬；屏障格不动）；
 *   2. 标量内核与向量内核在多种输入上**逐字节相同**（含"一边活一边死"的混合批）；
 *   3. 每格成本（用来判断向量化值不值，决策门 1.3×）。
 */
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../src/element-advect.h"

namespace
{

int g_checks = 0;
int g_failed = 0;

void expect_true(const char* what, bool ok)
{
    ++g_checks;
    if (!ok)
    {
        ++g_failed;
        std::printf("[FAIL] %s\n", what);
    }
    else
    {
        std::printf("[ok]   %s\n", what);
    }
}

void expect_int(const char* what, int got, int want)
{
    ++g_checks;
    if (got != want)
    {
        ++g_failed;
        std::printf("[FAIL] %s（得 %d，期望 %d）\n", what, got, want);
    }
    else
    {
        std::printf("[ok]   %s\n", what);
    }
}

/** 四元素打包：每通道 7 位 */
int pack(int a, int b, int c, int d)
{
    return ((a & 0x7F)) | ((b & 0x7F) << 8) | ((c & 0x7F) << 16) | ((d & 0x7F) << 24);
}

int unpack(int word, int channel)
{
    return (static_cast<unsigned>(word) >> (channel * 8)) & 0x7F;
}

/** 一份测试用的输入：section 内每个格与 base 由简单规则填，速度场可零可非零 */
struct Fixture
{
    std::vector<int> current;
    std::vector<int> scratch;
    std::vector<int> base;
    std::vector<uint64_t> blocked;
    std::vector<uint8_t> permeability;
    std::vector<float> fu;
    std::vector<float> fv;
    std::vector<float> fw;
    wmvm::element::Input in;

    Fixture(bool withBarrier, bool withBase, bool zeroCurrent)
            : current(wmvm::element::SIZE, 0),
              scratch(wmvm::element::SIZE, 0),
              base(wmvm::element::SIZE, 0),
              blocked((wmvm::element::SIZE >> 6), 0),
              permeability(wmvm::element::SIZE, static_cast<uint8_t>(wmvm::element::BARRIER_OPEN)),
              fu(wmvm::element::FLUID_NC, 0.0f),
              fv(wmvm::element::FLUID_NC, 0.0f),
              fw(wmvm::element::FLUID_NC, 0.0f)
    {
        for (int idx = 0; idx < wmvm::element::SIZE; ++idx)
        {
            int cur = zeroCurrent ? 0 : ((idx * 37 + 11) % 128);
            current[idx] = pack(cur, (cur / 2) % 128, (cur / 3) % 128, (cur / 5) % 128);
            if (withBase)
            {
                // 一半格子 base 比当前高（会被抬），一半更低（不动）
                int b = (idx % 2 == 0) ? std::min(127, cur + 9) : (cur / 4);
                base[idx] = pack(b, b / 2, b / 3, b / 4);
            }
        }
        if (withBarrier)
        {
            // 第 0..63 格全部阻断，第 64..127 格半通透
            for (int idx = 0; idx < 64; ++idx)
            {
                blocked[idx >> 6] |= (static_cast<uint64_t>(1) << (idx & 63));
            }
            for (int idx = 64; idx < 128; ++idx)
            {
                permeability[idx] = 60;
            }
        }
        in.current = current.data();
        in.scratch = scratch.data();
        in.base = base.data();
        in.blocked = blocked.data();
        in.permeability = permeability.data();
        in.has_barrier = withBarrier ? 1 : 0;
        in.fluid_u = fu.data();
        in.fluid_v = fv.data();
        in.fluid_w = fw.data();
        in.fluid_origin_x = 0;
        in.fluid_origin_y = 0;
        in.fluid_origin_z = 0;
        in.section_x = 0;
        in.section_y = 0;
        in.section_z = 0;
        in.dt = 1.0;
    }

    /** 拷贝之后必须重绑指针（`in` 里的指针指向原来那份 vector） */
    void rebind()
    {
        in.current = current.data();
        in.scratch = scratch.data();
        in.base = base.data();
        in.blocked = blocked.data();
        in.permeability = permeability.data();
        in.fluid_u = fu.data();
        in.fluid_v = fv.data();
        in.fluid_w = fw.data();
    }

    void fillFlow(float u, float v, float w)
    {
        for (int c = 0; c < wmvm::element::FLUID_NC; ++c)
        {
            fu[c] = u;
            fv[c] = v;
            fw[c] = w;
        }
    }

    /** 非均匀速度场：让反推坐标真的落在场内的不同位置上（夹紧路径之外的分支） */
    void fillWave(float phase)
    {
        for (int k = 0; k < wmvm::element::FLUID_N; ++k)
        {
            for (int j = 0; j < wmvm::element::FLUID_N; ++j)
            {
                for (int i = 0; i < wmvm::element::FLUID_N; ++i)
                {
                    const int c = i + wmvm::element::FLUID_N
                            * (j + wmvm::element::FLUID_N * k);
                    const float s = std::sin(0.31f * static_cast<float>(i) + 0.17f * static_cast<float>(j)
                            + 0.23f * static_cast<float>(k) + phase);
                    const float t = std::cos(0.11f * static_cast<float>(i) - 0.29f * static_cast<float>(j)
                            + 0.19f * static_cast<float>(k) + phase);
                    fu[c] = 0.9f * s;
                    fv[c] = -0.7f * t;
                    fw[c] = 0.5f * (s + t);
                }
            }
        }
    }

    /** 隔一个格阻断一个：制造"一批里一半活一半死"的混合批（8 lane 时才有意义） */
    void fillAlternatingBarrier()
    {
        blocked.assign(blocked.size(), 0);
        for (int idx = 0; idx < wmvm::element::SIZE; ++idx)
        {
            if ((idx & 1) != 0)
            {
                blocked[idx >> 6] |= (static_cast<uint64_t>(1) << (idx & 63));
            }
        }
        in.has_barrier = 1;
    }
};

/** 两组同样的输入各跑一次标量与向量内核，逐字节+返回码比对 */
void checkParity(const char* what, Fixture& f, double dt, int sectionX)
{
    f.in.dt = dt;
    f.in.section_x = sectionX;

    Fixture a = f;
    Fixture b = f;
    a.rebind();
    b.rebind();
    const int rcScalar = wmvm::element::advect_scalar(a.in);
    const int rcSimd = wmvm::element::advect_simd(b.in);
    bool same = rcScalar == rcSimd
            && std::memcmp(a.current.data(), b.current.data(),
                       sizeof(int) * wmvm::element::SIZE) == 0;
    if (!same)
    {
        // 找出第一处分叉，方便定位
        for (int idx = 0; idx < wmvm::element::SIZE; ++idx)
        {
            if (a.current[idx] != b.current[idx])
            {
                std::printf("      首个分叉 idx=%d 标量=0x%08X 向量=0x%08X\n", idx, a.current[idx],
                        b.current[idx]);
                break;
            }
        }
    }
    expect_true(what, same);
}

}  // namespace

int main()
{
    using namespace wmvm::element;

    std::printf("向量内核：target=%s lanes=%d\n", advect_simd_target(), advect_simd_lanes());

    // ---------------- 1) 无流 + base：应当只剩"抬到 base"
    {
        Fixture f(false, true, false);
        std::vector<int> before = f.current;
        const int rc = advect(f.in);
        // 返回码：bit0 = 有改动，bit1 = 走的是无流短路
        expect_int("无流 + base：走短路且有改动", rc, 3);
        bool allRaised = true;
        bool noOver = true;
        for (int idx = 0; idx < SIZE; ++idx)
        {
            for (int t = 0; t < 4; ++t)
            {
                const int cur = unpack(before[idx], t);
                const int b = unpack(f.base[idx], t);
                const int now = unpack(f.current[idx], t);
                const int want = cur > b ? cur : b;
                if (now != want)
                {
                    allRaised = false;
                }
                if (now > 127)
                {
                    noOver = false;
                }
            }
        }
        expect_true("无流 + base：逐通道取 max(current, base)", allRaised);
        expect_true("无流 + base：结果仍在 0..127", noOver);
    }

    // ---------------- 2) 无流 + 全零元素 + base：全零格不许被抬
    {
        Fixture f(false, true, true);
        const int rc = advect(f.in);
        expect_int("无流 + 全零格 + base：走短路但无改动", rc, 2);
    }

    // ---------------- 3) 有流：值域与有限性
    {
        Fixture f(false, true, false);
        f.fillFlow(0.5f, 0.25f, -0.4f);
        const int rc = advect(f.in);
        expect_int("有流：走完整路径且有改动", rc, 1);
        bool inRange = true;
        for (int idx = 0; idx < SIZE; ++idx)
        {
            for (int t = 0; t < 4; ++t)
            {
                const int v = unpack(f.current[idx], t);
                if (v < 0 || v > 127)
                {
                    inRange = false;
                }
            }
        }
        expect_true("有流：结果仍在 0..127", inRange);
    }

    // ---------------- 4) 屏障：完全阻断的格不动
    {
        Fixture f(true, true, false);
        std::vector<int> before = f.current;
        advect(f.in);
        bool blockedUntouched = true;
        for (int idx = 0; idx < 64; ++idx)
        {
            if (f.current[idx] != before[idx])
            {
                blockedUntouched = false;
            }
        }
        expect_true("屏障：完全阻断的格一个字节都不动", blockedUntouched);
    }

    // ---------------- 5) 确定性：同输入两次结果相同
    {
        Fixture a(false, true, false);
        Fixture b(false, true, false);
        a.fillFlow(0.5f, 0.25f, -0.4f);
        b.fillFlow(0.5f, 0.25f, -0.4f);
        advect(a.in);
        advect(b.in);
        // advect() 默认走 dispatch（向量内核），这里等价于"向量内核两次同结果"
        expect_true("确定性：同输入两次逐字节相同",
                std::memcmp(a.current.data(), b.current.data(), sizeof(int) * SIZE) == 0);
    }

    // ---------------- 6) 标量 vs 向量 逐位对拍
    if (advect_simd_lanes() == 0)
    {
        std::printf("[skip] 本机没有 AVX2/AVX-512，向量内核回落到标量，对拍无意义\n");
    }
    else
    {
        {   // 均匀流 + base，dm=1.0：反推坐标几乎都夹到边界
            Fixture f(false, true, false);
            f.fillFlow(0.5f, 0.25f, -0.4f);
            checkParity("对拍：均匀流 + base（dt=1，坐标夹紧）", f, 1.0, 0);
        }
        {   // 均匀流 + 屏障 + 半通透
            Fixture f(true, true, false);
            f.fillFlow(0.5f, 0.25f, -0.4f);
            checkParity("对拍：屏障 + 半通透（dt=1）", f, 1.0, 0);
        }
        {   // 非均匀流，dt=0.2：反推坐标落在场内
            Fixture f(false, true, false);
            f.fillWave(0.0f);
            checkParity("对拍：非均匀流（dt=0.2）", f, 0.2, 0);
        }
        {   // 非均匀流 + 屏障，dt=0.5，section 原点非零
            Fixture f(true, false, false);
            f.fillWave(1.7f);
            checkParity("对拍：非均匀流 + 屏障 + 非零 section 原点（dt=0.5）", f, 0.5, 64);
        }
        {   // 隔格阻断：混合批（AVX-512 的"一格活一格死"分支）
            Fixture f(false, false, false);
            f.fillWave(3.1f);
            f.fillAlternatingBarrier();
            checkParity("对拍：隔格阻断的混合批（dt=0.7）", f, 0.7, 0);
        }
        {   // 极端：dt 很大 + 全 127 值
            Fixture f(false, true, false);
            for (int idx = 0; idx < SIZE; ++idx)
            {
                f.current[idx] = pack(127, 127, 127, 127);
            }
            f.in.current = f.current.data();
            f.fillWave(5.0f);
            checkParity("对拍：全 127 值 + 大位移（dt=3）", f, 3.0, 0);
        }
        {   // 无流短路路径也要一致
            Fixture f(false, true, false);
            checkParity("对拍：无流短路（dt=1）", f, 1.0, 0);
        }
    }

    // ---------------- 基准
    {
        const int iters = 400;
        Fixture f(false, true, false);

        f.fillFlow(0.0f, 0.0f, 0.0f);
        auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i)
        {
            advect(f.in);
        }
        auto t1 = std::chrono::steady_clock::now();
        const double nsNoFlow = std::chrono::duration<double, std::nano>(t1 - t0).count()
                / (double(iters) * SIZE);

        // 两条路各跑一遍（同一个起点、同样的迭代次数），两种流场都测
        const char* pattern[2] = {"均匀流", "非均匀流"};
        double nsScalar[2] = {0.0, 0.0};
        double nsSimd[2] = {0.0, 0.0};
        for (int p = 0; p < 2; ++p)
        {
            Fixture fs(false, true, false);
            Fixture fv(false, true, false);
            if (p == 0)
            {
                fs.fillFlow(0.5f, 0.25f, -0.4f);
                fv.fillFlow(0.5f, 0.25f, -0.4f);
            }
            else
            {
                fs.fillWave(0.0f);
                fv.fillWave(0.0f);
            }

            auto t2 = std::chrono::steady_clock::now();
            for (int i = 0; i < iters; ++i)
            {
                advect_scalar(fs.in);
            }
            auto t3 = std::chrono::steady_clock::now();
            nsScalar[p] = std::chrono::duration<double, std::nano>(t3 - t2).count()
                    / (double(iters) * SIZE);

            auto t4 = std::chrono::steady_clock::now();
            for (int i = 0; i < iters; ++i)
            {
                advect_simd(fv.in);
            }
            auto t5 = std::chrono::steady_clock::now();
            nsSimd[p] = std::chrono::duration<double, std::nano>(t5 - t4).count()
                    / (double(iters) * SIZE);

            expect_true("标量与向量跑完的终态一致",
                    std::memcmp(fs.current.data(), fv.current.data(), sizeof(int) * SIZE) == 0);
        }

        std::printf("[bench] 平流 ns/格（%d lane）：\n", advect_simd_lanes());
        for (int p = 0; p < 2; ++p)
        {
            std::printf("        有流-%s：标量 %.2f / 向量 %.2f = %.2fx\n", pattern[p], nsScalar[p],
                    nsSimd[p], nsScalar[p] / nsSimd[p]);
        }
        std::printf("        无流（短路）：%.2f\n", nsNoFlow);
        for (int p = 0; p < 2; ++p)
        {
            expect_true("基准数字合理（两条路都 > 0）", nsScalar[p] > 0.0 && nsSimd[p] > 0.0);
        }
    }

    std::printf("\nchecks=%d failures=%d\n", g_checks, g_failed);
    return g_failed == 0 ? 0 : 1;
}
