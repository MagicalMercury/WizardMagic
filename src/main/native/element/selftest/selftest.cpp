/*
 * 元素平流原生实现的自检：语义边界 + 成本基准。
 *
 * 与 Java 的**逐位对拍**在 Java 侧做（`verify/ElementAdvectCheck`，走 JNI 调同一份代码），
 * 这里只守两件东西：
 *   1. 几条明确的语义（无流 → 只抬到 base；全零格不许被抬；屏障格不动）；
 *   2. 每格成本（用来判断值不值得继续优化）。
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

    void fillFlow(float u, float v, float w)
    {
        for (int c = 0; c < wmvm::element::FLUID_NC; ++c)
        {
            fu[c] = u;
            fv[c] = v;
            fw[c] = w;
        }
    }
};

}  // namespace

int main()
{
    using namespace wmvm::element;

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
        expect_true("确定性：同输入两次逐字节相同",
                std::memcmp(a.current.data(), b.current.data(), sizeof(int) * SIZE) == 0);
    }

    // ---------------- 基准
    {
        Fixture f(false, true, false);
        const int iters = 400;

        f.fillFlow(0.0f, 0.0f, 0.0f);
        auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i)
        {
            advect(f.in);
        }
        auto t1 = std::chrono::steady_clock::now();
        const double nsNoFlow = std::chrono::duration<double, std::nano>(t1 - t0).count()
                / (double(iters) * SIZE);

        f.fillFlow(0.5f, 0.25f, -0.4f);
        auto t2 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i)
        {
            advect(f.in);
        }
        auto t3 = std::chrono::steady_clock::now();
        const double nsFlow = std::chrono::duration<double, std::nano>(t3 - t2).count()
                / (double(iters) * SIZE);

        std::printf("[bench] 平流 %.2f ns/格：有流=%.2f 无流（短路）=%.2f\n",
                nsFlow, nsFlow, nsNoFlow);
        expect_true("基准数字合理（均有流路径 > 0）", nsFlow > 0.0);
    }

    std::printf("\nchecks=%d failures=%d\n", g_checks, g_failed);
    return g_failed == 0 ? 0 : 1;
}
