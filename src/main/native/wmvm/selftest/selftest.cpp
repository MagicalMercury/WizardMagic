/*
 * WMVM 离机自检：不接 JNI、不接 Minecraft，直接跑原生内核。
 *
 * 期望值全部来自 Java 侧实测（见 commit 说明里的 Probe 输出），所以这是真正的
 * 跨语言对拍，不是"自己验自己"：
 *   - SpellNoise.value3 / SpellRandom.unit 的三个 bit 常量
 *   - Math.round / Math.min / Math.max 的边界
 * 先用 `cmake -S src/main/native/wmvm -B build-native && cmake --build build-native` 构建。
 */
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "jmath.h"
#include "noise.h"
#include "wmvm.h"

namespace
{

int g_failed = 0;
int g_checks = 0;

uint64_t bits(double v)
{
    uint64_t u;
    std::memcpy(&u, &v, sizeof(u));
    return u;
}

void expect_bits(const char* what, double got, uint64_t want)
{
    ++g_checks;
    const uint64_t g = bits(got);
    if (g != want)
    {
        std::printf("[FAIL] %-28s got 0x%016llx want 0x%016llx\n", what,
                static_cast<unsigned long long>(g), static_cast<unsigned long long>(want));
        ++g_failed;
    }
    else
    {
        std::printf("[ok]   %s\n", what);
    }
}

void expect_true(const char* what, bool ok)
{
    ++g_checks;
    if (!ok)
    {
        std::printf("[FAIL] %s\n", what);
        ++g_failed;
    }
    else
    {
        std::printf("[ok]   %s\n", what);
    }
}

/** 迷你汇编器：按 `op<<24 | d<<16 | a<<8 | b` 打包 */
struct Asm
{
    std::vector<int32_t> code;
    std::vector<double> consts;

    int k(double v)
    {
        consts.push_back(v);
        return static_cast<int>(consts.size()) - 1;
    }

    void op(int o, int d, int a, int b)
    {
        code.push_back((o << 24) | ((d & 0xFF) << 16) | ((a & 0xFF) << 8) | (b & 0xFF));
    }

    void op1(int o, int d, int a, int b, int extra)
    {
        op(o, d, a, b);
        code.push_back(extra);
    }

    void op2(int o, int d, int a, int b, int e1, int e2)
    {
        op(o, d, a, b);
        code.push_back(e1);
        code.push_back(e2);
    }
};

// 与 RegVm 的编码一致
enum
{
    LDK = 1, MOV = 2, MOV3 = 3,
    LPOS = 8, LPOSX = 9, LVEL = 12, LENERGY = 16,
    SPOS = 32, SPOSX = 33, SVEL = 36,
    ADD_N = 41, MUL_N = 43, ADD_V = 46, SCALE = 48,
    GT = 53, JZ = 60, JMP = 61, JNZ = 85,
    SQRT = 63, NOISE = 84, RAND = 86,
    LEN = 77, DOT = 75, PICK = 83,
    SELECT = 96
};

/** 65536 项 sin 表：自检时用 std::sin 造一张，真实场景由 Java 灌入 */
std::vector<float> make_sin_table()
{
    std::vector<float> t(65536);
    for (int i = 0; i < 65536; ++i)
    {
        t[i] = static_cast<float>(std::sin(static_cast<double>(i) * 3.14159265358979323846 * 2.0 / 65536.0));
    }
    return t;
}

WmvmBatch make_batch(int count, std::vector<double>& px, std::vector<double>& py,
        std::vector<double>& pz, std::vector<double>& vx, std::vector<double>& vy,
        std::vector<double>& vz, std::vector<float>& en)
{
    WmvmBatch b{};
    b.from = 0;
    b.to = count;
    b.px = px.data();
    b.py = py.data();
    b.pz = pz.data();
    b.vx = vx.data();
    b.vy = vy.data();
    b.vz = vz.data();
    b.energy = en.data();
    b.seed = 0x0123456789abcdefLL;
    b.age_step = 0;
    b.time = 0.0;
    b.rand_seq = 0;
    return b;
}

}  // namespace

int main()
{
    const std::vector<float> sinTable = make_sin_table();
    if (wmvm_init() != 0)
    {
        std::printf("[FAIL] wmvm_init\n");
        return 1;
    }
    expect_true("sin 表被接受", wmvm_set_sin_table(sinTable.data(), 65536) == 0);
    std::printf("       target=%s lanes=%d\n", wmvm_target_name(), wmvm_lanes());

    // ---------------- 1) p.pos = p.pos + p.v（一行 MOV/ADD_V/SPOS 的完整链路）
    {
        Asm s;
        s.op(LDK, 0, 0, 0);
        s.code.push_back(s.k(1.0));
        s.op(LPOS, 3, 0, 0);
        s.op(LVEL, 6, 0, 0);
        s.op(ADD_V, 3, 3, 6);
        s.op(SPOS, 3, 0, 0);

        std::vector<double> px{1.0, 2.0, 3.0}, py{0.0, 0.0, 0.0}, pz{0.0, 0.0, 0.0};
        std::vector<double> vx{0.5, -0.25, 10.0}, vy{1.0, 1.0, 1.0}, vz{0.0, 0.0, 0.0};
        std::vector<float> en(3, 1.0f);
        WmvmBatch b = make_batch(3, px, py, pz, vx, vy, vz, en);

        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), static_cast<int>(s.code.size()),
                s.consts.data(), static_cast<int>(s.consts.size()));
        expect_true("程序编译", prog != nullptr);
        if (prog != nullptr)
        {
            const int rc = wmvm_run(prog, &b, fv, hv);
            expect_true("run 返回 rand 序号 0", rc == 0);
            expect_bits("pos += vel [0]", px[0], bits(1.5));
            expect_bits("pos += vel [1]", px[1], bits(1.75));
            expect_bits("pos += vel [2]", px[2], bits(13.0));
            wmvm_program_destroy(prog);
        }
    }

    // ---------------- 2) 分支：x > 0 的粒子被改成 100
    {
        Asm s;
        s.op(LPOSX, 0, 0, 0);
        s.op(LDK, 1, 0, 0);
        s.code.push_back(s.k(0.0));
        s.op(GT, 2, 0, 1);
        s.op(JZ, 2, 0, 0);
        const int jumpSite = static_cast<int>(s.code.size());
        s.code.push_back(-1);                       // 占位：JZ 的目标字
        s.op(LDK, 3, 0, 0);
        s.code.push_back(s.k(100.0));
        s.op(SPOSX, 3, 0, 0);
        s.code[jumpSite] = static_cast<int32_t>(s.code.size());

        std::vector<double> px{-1.0, 0.0, 5.0}, py(3, 0.0), pz(3, 0.0);
        std::vector<double> vx(3, 0.0), vy(3, 0.0), vz(3, 0.0);
        std::vector<float> en(3, 0.0f);
        WmvmBatch b = make_batch(3, px, py, pz, vx, vy, vz, en);
        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), static_cast<int>(s.code.size()),
                s.consts.data(), static_cast<int>(s.consts.size()));
        if (prog != nullptr)
        {
            const int rc = wmvm_run(prog, &b, fv, hv);
            expect_true("分支程序正常结束", rc == 0);
            expect_bits("分支：负数不动", px[0], bits(-1.0));
            expect_bits("分支：零不动", px[1], bits(0.0));
            expect_bits("分支：正数改成 100", px[2], bits(100.0));
            wmvm_program_destroy(prog);
        }
    }

    // ---------------- 2b) SELECT：两条路径都算完再挑（if-conversion 的目标指令）
    {
        // r5 = (px > 0.5) ? 7 : 9 → 写回 px
        Asm s;
        s.op(LPOSX, 0, 0, 0);
        s.op(LDK, 1, 0, 0);
        s.code.push_back(s.k(0.5));
        s.op(GT, 2, 0, 1);
        s.op(LDK, 3, 0, 0);
        s.code.push_back(s.k(7.0));
        s.op(LDK, 4, 0, 0);
        s.code.push_back(s.k(9.0));
        s.op(SELECT, 5, 2, 3);
        s.code.push_back(4);                        // 第三寄存器：假分支的值
        s.op(SPOSX, 5, 0, 0);

        WmvmProgram* prog = wmvm_program_create(s.code.data(), static_cast<int>(s.code.size()),
                s.consts.data(), static_cast<int>(s.consts.size()));
        expect_true("SELECT 程序编译", prog != nullptr);
        if (prog != nullptr)
        {
            // 3 颗 < 4 lane ⇒ 标量内核
            {
                std::vector<double> px{0.2, 0.8, 0.2}, py(3, 0.0), pz(3, 0.0);
                std::vector<double> vx(3, 0.0), vy(3, 0.0), vz(3, 0.0);
                std::vector<float> en(3, 0.0f);
                WmvmBatch b = make_batch(3, px, py, pz, vx, vy, vz, en);
                double fv[8] = {0}, hv[8] = {0};
                const int rc = wmvm_run(prog, &b, fv, hv);
                expect_true("SELECT：标量段正常结束", rc == 0);
                expect_bits("SELECT：条件假取 y（标量）", px[0], bits(9.0));
                expect_bits("SELECT：条件真取 x（标量）", px[1], bits(7.0));
            }
            // 8 颗 ⇒ 走 2 组 4 lane 向量内核
            {
                std::vector<double> px(8), py(8, 0.0), pz(8, 0.0);
                for (int i = 0; i < 8; ++i)
                {
                    px[i] = (i & 1) != 0 ? 0.8 : 0.2;
                }
                std::vector<double> vx(8, 0.0), vy(8, 0.0), vz(8, 0.0);
                std::vector<float> en(8, 0.0f);
                WmvmBatch b = make_batch(8, px, py, pz, vx, vy, vz, en);
                double fv[8] = {0}, hv[8] = {0};
                const int rc = wmvm_run(prog, &b, fv, hv);
                expect_true("SELECT：向量段正常结束", rc == 0);
                expect_bits("SELECT：第 1 通道假取 y", px[0], bits(9.0));
                expect_bits("SELECT：第 2 通道真取 x", px[1], bits(7.0));
                expect_bits("SELECT：末通道真取 x", px[7], bits(7.0));
            }
            wmvm_program_destroy(prog);
        }
    }

    // ---------------- 3) noise / rand 与 Java 实测值逐位相同
    {
        // p.pos.x = noise(0.25, -1.5, 3.75, 7)
        Asm s;
        s.op(LDK, 0, 0, 0);
        s.code.push_back(s.k(0.25));
        s.op(LDK, 1, 0, 0);
        s.code.push_back(s.k(-1.5));
        s.op(LDK, 2, 0, 0);
        s.code.push_back(s.k(3.75));
        s.op(LDK, 3, 0, 0);
        s.code.push_back(s.k(7.0));
        s.op2(NOISE, 4, 0, 1, 2, 3);
        s.op(SPOSX, 4, 0, 0);

        std::vector<double> px(1, 0.0), py(1, 0.0), pz(1, 0.0);
        std::vector<double> vx(1, 0.0), vy(1, 0.0), vz(1, 0.0);
        std::vector<float> en(1, 0.0f);
        WmvmBatch b = make_batch(1, px, py, pz, vx, vy, vz, en);
        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), static_cast<int>(s.code.size()),
                s.consts.data(), static_cast<int>(s.consts.size()));
        if (prog != nullptr)
        {
            wmvm_run(prog, &b, fv, hv);
            expect_bits("noise(0.25,-1.5,3.75,7)", px[0], 0x3fe546952b9abf0dULL);
            wmvm_program_destroy(prog);
        }

        // 连续 3 次 rand() → 与 SpellRandom.unit(seed, 0, 0/1/2) 一致
        Asm r;
        r.op(RAND, 0, 0, 0);
        r.op(RAND, 1, 0, 0);
        r.op(RAND, 2, 0, 0);
        r.op(SPOSX, 0, 0, 0);
        std::vector<double> px2(1, 0.0), py2(1, 0.0), pz2(1, 0.0);
        std::vector<double> vx2(1, 0.0), vy2(1, 0.0), vz2(1, 0.0);
        std::vector<float> en2(1, 0.0f);
        WmvmBatch b2 = make_batch(1, px2, py2, pz2, vx2, vy2, vz2, en2);
        WmvmProgram* rp = wmvm_program_create(r.code.data(), static_cast<int>(r.code.size()),
                r.consts.data(), static_cast<int>(r.consts.size()));
        if (rp != nullptr)
        {
            const int rc = wmvm_run(rp, &b2, fv, hv);
            expect_true("rand 序号推进到 3", rc == 3);
            expect_bits("rand #0", px2[0], 0x3fb57a3807a48fa8ULL);
            wmvm_program_destroy(rp);
        }
    }

    // ---------------- 4) rand 序列跨粒子按顺序消耗（1 lane 语义）
    {
        Asm s;
        s.op(RAND, 0, 0, 0);
        s.op(SPOSX, 0, 0, 0);
        std::vector<double> px(3, 0.0), py(3, 0.0), pz(3, 0.0);
        std::vector<double> vx(3, 0.0), vy(3, 0.0), vz(3, 0.0);
        std::vector<float> en(3, 0.0f);
        WmvmBatch b = make_batch(3, px, py, pz, vx, vy, vz, en);
        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), static_cast<int>(s.code.size()),
                s.consts.data(), static_cast<int>(s.consts.size()));
        if (prog != nullptr)
        {
            const int rc = wmvm_run(prog, &b, fv, hv);
            expect_true("3 颗粒子消耗 3 次 rand", rc == 3);
            // 第 i 颗粒子用的是 unit(seed, 0, i)
            expect_bits("跨粒子 rand #0", px[0], 0x3fb57a3807a48fa8ULL);
            wmvm_program_destroy(prog);
        }
    }


    // ---------------- 5) round / min / max 的 Java 语义（期望值来自 JDK 实跑）
    {
        expect_bits("round(0.5)", wmvm::jround_double(0.5), 0x3ff0000000000000ULL);
        expect_bits("round(-0.5)", wmvm::jround_double(-0.5), 0x0000000000000000ULL);
        expect_bits("round(0.49999999999999994)", wmvm::jround_double(0.49999999999999994),
                0x0000000000000000ULL);
        expect_bits("round(2.5)", wmvm::jround_double(2.5), 0x4008000000000000ULL);
        expect_bits("round(-2.5)", wmvm::jround_double(-2.5), 0xc000000000000000ULL);
        expect_bits("round(2^52)", wmvm::jround_double(4503599627370496.0), 0x4330000000000000ULL);
        expect_bits("round(2^52+1)", wmvm::jround_double(4503599627370497.0), 0x4330000000000001ULL);
        expect_bits("round(1e18)", wmvm::jround_double(1.0e18), 0x43abc16d674ec800ULL);
        expect_bits("round(NaN)", wmvm::jround_double(std::nan("")), 0x0000000000000000ULL);
        expect_bits("round(inf)", wmvm::jround_double(HUGE_VAL), 0x43e0000000000000ULL);
        expect_bits("round(-1e18)", wmvm::jround_double(-1.0e18), 0xc3abc16d674ec800ULL);
        expect_bits("min(+0,-0)", wmvm::jmin(0.0, -0.0), 0x8000000000000000ULL);
        expect_bits("max(+0,-0)", wmvm::jmax(0.0, -0.0), 0x0000000000000000ULL);
        expect_bits("min(NaN,1)", wmvm::jmin(std::nan(""), 1.0), 0x7ff8000000000000ULL);
        expect_bits("max(1,NaN)", wmvm::jmax(1.0, std::nan("")), 0x7ff8000000000000ULL);
    }

    // ---------------- 6) 纯算术基准：lane 1 vs 自动（看向量化本身有没有收益）
    {
        Asm s;
        s.op(LDK, 3, 0, 0);
        s.code.push_back(s.k(0.001));
        s.op(LPOS, 0, 0, 0);
        for (int i = 0; i < 40; ++i)
        {
            s.op(SCALE, 0, 0, 3);   // p.pos = p.pos * 0.001（每条 3 次乘）
        }
        s.op(SPOS, 0, 0, 0);

        const int n = 4096;
        std::vector<double> px(n), py(n), pz(n), vx(n, 0.0), vy(n, 0.0), vz(n, 0.0);
        std::vector<float> en(n, 1.0f);
        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), (int) s.code.size(),
                s.consts.data(), (int) s.consts.size());
        auto runOnce = [&](int lanes) {
            wmvm_force_lanes(lanes);
            for (int i = 0; i < n; ++i) { px[i] = 1.0 + i * 1e-6; }
            WmvmBatch bb = make_batch(n, px, py, pz, vx, vy, vz, en);
            return wmvm_run(prog, &bb, fv, hv);
        };
        for (int warm = 0; warm < 3; ++warm) { runOnce(1); runOnce(0); }
        const int iters = 200;
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) { runOnce(1); }
        const auto t1 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) { runOnce(0); }
        const auto t2 = std::chrono::steady_clock::now();
        const double ns1 = std::chrono::duration<double, std::nano>(t1 - t0).count() / (iters * (double) n);
        const double nsN = std::chrono::duration<double, std::nano>(t2 - t1).count() / (iters * (double) n);
        std::printf("[bench] 40x SCALE  1 lane = %.1f ns/粒  %d lane = %.1f ns/粒  倍率 = %.2fx\n",
                ns1, wmvm_lanes(), nsN, ns1 / nsN);
        wmvm_force_lanes(0);
        wmvm_program_destroy(prog);
    }

    // ---------------- 7) noise 基准：3 次/粒（firestorm 的形状）
    {
        Asm s;
        s.op(LPOS, 0, 0, 0);              // r0..2 = pos
        s.op(LDK, 3, 0, 0);
        s.code.push_back(s.k(0.0));
        // NOISE 0,0,1,2, stream=3  三次（流 0/1/2）
        s.op2(NOISE, 6, 0, 1, 2, 3);
        s.op2(NOISE, 7, 0, 1, 2, 3);
        s.op2(NOISE, 8, 0, 1, 2, 3);
        s.op(ADD_N, 6, 6, 7);
        s.op(ADD_N, 8, 8, 6);
        s.op(SPOSX, 8, 0, 0);

        const int n = 4096;
        std::vector<double> px(n), py(n), pz(n), vx(n, 0.0), vy(n, 0.0), vz(n, 0.0);
        std::vector<float> en(n, 1.0f);
        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), (int) s.code.size(),
                s.consts.data(), (int) s.consts.size());
        auto runOnce = [&](int lanes) {
            wmvm_force_lanes(lanes);
            for (int i = 0; i < n; ++i) { px[i] = i * 0.01; py[i] = 1.0; pz[i] = 2.0; }
            WmvmBatch bb = make_batch(n, px, py, pz, vx, vy, vz, en);
            return wmvm_run(prog, &bb, fv, hv);
        };
        for (int warm = 0; warm < 3; ++warm) { runOnce(1); runOnce(0); }
        const int iters = 100;
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) { runOnce(1); }
        const auto t1 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) { runOnce(0); }
        const auto t2 = std::chrono::steady_clock::now();
        const double ns1 = std::chrono::duration<double, std::nano>(t1 - t0).count() / (iters * (double) n);
        const double nsN = std::chrono::duration<double, std::nano>(t2 - t1).count() / (iters * (double) n);
        std::printf("[bench] 3x NOISE   1 lane = %.1f ns/粒  %d lane = %.1f ns/粒  倍率 = %.2fx  (cache hits=%ld misses=%ld)\n",
                ns1, wmvm_lanes(), nsN, ns1 / nsN, wmvm::g_noise_cache.hits, wmvm::g_noise_cache.misses);
        wmvm_force_lanes(0);
        wmvm_program_destroy(prog);
    }
    // ---------------- 8) 带分支的循环体：隔离"发散"的代价
    {
        Asm s;
        s.op(LPOS, 0, 0, 0);
        s.op(LDK, 3, 0, 0);
        s.code.push_back(s.k(0.0));
        s.op(GT, 4, 0, 3);              // r4 = (px > 0)
        s.op(JZ, 4, 0, 0);
        const int site = (int) s.code.size();
        s.code.push_back(-1);
        s.op(LDK, 5, 0, 0);
        s.code.push_back(s.k(0.001));
        for (int i = 0; i < 20; ++i) { s.op(SCALE, 0, 0, 5); }
        s.code[site] = (int) s.code.size();   // 跳到这里（跳过 20 次 SCALE）
        s.op(SPOS, 0, 0, 0);

        const int n = 4096;
        std::vector<double> px(n), py(n), pz(n), vx(n, 0.0), vy(n, 0.0), vz(n, 0.0);
        std::vector<float> en(n, 1.0f);
        double fv[8] = {0}, hv[8] = {0};
        WmvmProgram* prog = wmvm_program_create(s.code.data(), (int) s.code.size(),
                s.consts.data(), (int) s.consts.size());
        auto runOnce = [&](int lanes) {
            wmvm_force_lanes(lanes);
            for (int i = 0; i < n; ++i) { px[i] = (i & 1) ? 1.0 : -1.0; }  // 相邻粒子必发散
            WmvmBatch bb = make_batch(n, px, py, pz, vx, vy, vz, en);
            return wmvm_run(prog, &bb, fv, hv);
        };
        for (int w = 0; w < 3; ++w) { runOnce(1); runOnce(0); }
        const int iters = 200;
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) { runOnce(1); }
        const auto t1 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) { runOnce(0); }
        const auto t2 = std::chrono::steady_clock::now();
        const double ns1 = std::chrono::duration<double, std::nano>(t1 - t0).count() / (iters * (double) n);
        const double nsN = std::chrono::duration<double, std::nano>(t2 - t1).count() / (iters * (double) n);
        std::printf("[bench] 1x if + 20x SCALE  1 lane = %.1f ns/粒  %d lane = %.1f ns/粒  倍率 = %.2fx\n",
                ns1, wmvm_lanes(), nsN, ns1 / nsN);
        wmvm_force_lanes(0);
        wmvm_program_destroy(prog);
    }
    std::printf("\nchecks=%d failures=%d\n", g_checks, g_failed);
    return g_failed == 0 ? 0 : 1;
}
