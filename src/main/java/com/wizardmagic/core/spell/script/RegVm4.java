package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.SpellMath;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellNoise;
import com.wizardmagic.core.spell.SpellRandom;

import static com.wizardmagic.core.spell.script.RegVm.*;

/**
 * 4 粒通道版寄存器机（P2）。
 *
 * <p><b>为什么要它</b>：标量 {@link RegVm#execute} 每颗粒子都要把 100 多条指令逐条分派一次，
 * 单粒约 20% 的时间花在"取指 + switch"上。这里把 4 颗粒子放进 4 条 lane，共享同一条指令流：
 * 一条指令只取一次、只分派一次，然后对 4 条 lane 各做一遍。</p>
 *
 * <p><b>语义</b>：每条 lane 有独立的 {@code pc} 和独立的寄存器段
 * （{@code r[lane * frameSize + reg]}）。分支在 lane 之间不一致时，按 {@code pc} 分组，
 * 每组各执行一次——所以每条 lane 走哪条路径、执行顺序如何，都与标量版完全相同，
 * 结果逐位一致。粒子之间没有数据依赖，唯一需要"按粒子顺序"的 {@code rand()} 与云级变量
 * 写回不允许进入本执行器（{@link #supported} 会拒绝），由调用方退回标量。</p>
 *
 * <p><b>线程</b>：与标量版一样，每段 [from,to) 只碰自己的下标；帧数组和其它临时数组都是
 * 每次调用新建，多个工作线程并行跑不同区间互不影响。</p>
 */
final class RegVm4
{
    static final int LANES = 4;

    /**
     * 被真正调用过多少次（诊断用）。
     *
     * <p>为什么要这个计数器：`ENABLED` 是静态开关，光看"测试全绿"没法区分
     * "4 通道跑过且正确" 和 "4 通道压根没被启用"。自检末尾会把它打出来。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong EXECUTED =
            new java.util.concurrent.atomic.AtomicLong();

    static long executedCount()
    {
        return EXECUTED.get();
    }

    /**
     * 总开关：默认关闭。
     *
     * <p>实测（2 核沙箱，火焰风暴）：单线程 865 vs 标量 767 ns/粒，2 线程 640 vs 594。
     * 分派确实被摊薄了，但每条 lane 的掩码判断、4 倍寄存器足迹、以及按族拆分的辅助方法
     * 调用，把收益全部吃掉。说明热循环的成本主要不在"指令分派"，而在每条指令内部的数据
     * 搬运与边界检查。想在其它 CPU 上复测时用 {@code -Dwizardmagic.spell.lanes=1} 打开。</p>
     */
    private static final boolean ENABLED =
            "1".equals(System.getProperty("wizardmagic.spell.lanes"));

    private RegVm4()
    {
    }

    static boolean enabled()
    {
        return ENABLED;
    }

    /** 这段指令流能不能按 4 粒通道跑（含 rand / 写云级变量 / 未知指令就退回标量） */
    static boolean supported(int[] code)
    {
        if (!ENABLED)
        {
            return false;
        }
        int pc = 0;
        while (pc < code.length)
        {
            int op = code[pc] >>> 24;
            if (op == OP_RAND || op == OP_STF1 || op == OP_STF3)
            {
                return false;
            }
            if (!known(op))
            {
                return false;
            }
            pc += length(op);
        }
        return true;
    }

    private static boolean known(int op)
    {
        return (op >= OP_LDK && op <= OP_ATAN2) && op != OP_RAND && op != OP_STF1 && op != OP_STF3;
    }

    private static int length(int op)
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
                return 2;
            case OP_NOISE:
                return 3;
            default:
                return 1;
        }
    }

    /**
     * 逐 4 粒一组执行 {@code [from, to)}。
     *
     * @param r  长度 = {@code frameSize * LANES}，lane 间互不重叠
     */
    static void execute(int[] code, int[] pos, double[] k, double[] r, double[] fv, double[] hv,
                        SpellCloud cloud, int from, int to, double time)
    {
        EXECUTED.incrementAndGet();
        final int frame = r.length / LANES;
        final int n = code.length;
        final long seed = cloud.castSeed;
        final int step = cloud.ageSteps();
        final int[] pcs = new int[LANES];
        final int[] idx = new int[LANES];
        final int[] live = new int[LANES];
        final double[] ax = new double[LANES];
        final double[] ay = new double[LANES];
        final double[] az = new double[LANES];
        final double[] tgt = new double[LANES];
        final int[] ready = new int[2];
        final double[] axisTmp = new double[3];

        for (int base = from; base < to; base += LANES)
        {
            int lanes = Math.min(LANES, to - base);
            for (int l = 0; l < LANES; l++)
            {
                idx[l] = base + l;
                pcs[l] = 0;
                live[l] = l < lanes ? 1 : 0;
            }
            ready[0] = 0;
            ready[1] = 0;

            while (true)
            {
                int ip = -1;
                int group = 0;
                for (int l = 0; l < LANES; l++)
                {
                    if (live[l] == 0)
                    {
                        continue;
                    }
                    if (ip < 0)
                    {
                        ip = pcs[l];
                    }
                    if (pcs[l] == ip)
                    {
                        group |= 1 << l;
                    }
                }
                if (ip < 0)
                {
                    break;
                }
                step(code, pos, k, r, fv, hv, cloud, idx, group, ip, time, frame, pcs, ready,
                        ax, ay, az, tgt, axisTmp, seed, step);
                for (int l = 0; l < LANES; l++)
                {
                    if ((group & (1 << l)) != 0 && pcs[l] >= n)
                    {
                        live[l] = 0;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 单条指令

    private static void step(int[] code, int[] pos, double[] k, double[] r, double[] fv, double[] hv,
                             SpellCloud cloud, int[] idx, int m, int ip, double time, int F,
                             int[] pcs, int[] ready,
                             double[] ax, double[] ay, double[] az, double[] tgt,
                             double[] axisTmp, long seed, int step)
    {
        int ins = code[ip];
        int op = ins >>> 24;
        int d = (ins >>> 16) & 0xFF;
        int a = (ins >>> 8) & 0xFF;
        int b = ins & 0xFF;
        switch (op)
        {
            case OP_LDK:
            {
                double v = k[code[ip + 1]];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        r[l * F + d] = v;
                    }
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_MOV:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = r[o + a];
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_MOV3:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = r[o + a];
                        r[o + d + 1] = r[o + a + 1];
                        r[o + d + 2] = r[o + a + 2];
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LDF1:
            case OP_LDF3:
            case OP_LDH1:
            case OP_LDH3:
            {
                double[] src = (op == OP_LDF1 || op == OP_LDF3) ? fv : hv;
                boolean vec = op == OP_LDF3 || op == OP_LDH3;
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = src[a];
                        if (vec)
                        {
                            r[o + d + 1] = src[a + 1];
                            r[o + d + 2] = src[a + 2];
                        }
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            }

            // ------------------------------------------------ 粒子读
            case OP_LPOS:
            case OP_LVEL:
            case OP_LANCHOR:
            case OP_LLOCAL:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        int i = idx[l];
                        switch (op)
                        {
                            case OP_LPOS:
                                r[o + d] = cloud.x(i);
                                r[o + d + 1] = cloud.y(i);
                                r[o + d + 2] = cloud.z(i);
                                break;
                            case OP_LVEL:
                                r[o + d] = cloud.velX(i);
                                r[o + d + 1] = cloud.velY(i);
                                r[o + d + 2] = cloud.velZ(i);
                                break;
                            case OP_LANCHOR:
                                r[o + d] = cloud.anchorX(i);
                                r[o + d + 1] = cloud.anchorYWorld(i);
                                r[o + d + 2] = cloud.anchorZ(i);
                                break;
                            default:
                                r[o + d] = cloud.x(i) - cloud.originX;
                                r[o + d + 1] = cloud.y(i) - cloud.originY;
                                r[o + d + 2] = cloud.z(i) - cloud.originZ;
                                break;
                        }
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LPOSX:
            case OP_LPOSY:
            case OP_LPOSZ:
            case OP_LVELX:
            case OP_LVELY:
            case OP_LVELZ:
            case OP_LANCHORX:
            case OP_LANCHORY:
            case OP_LANCHORZ:
            case OP_LLOCALX:
            case OP_LLOCALY:
            case OP_LLOCALZ:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int i = idx[l];
                        r[l * F + d] = switch (op)
                        {
                            case OP_LPOSX -> cloud.x(i);
                            case OP_LPOSY -> cloud.y(i);
                            case OP_LPOSZ -> cloud.z(i);
                            case OP_LVELX -> cloud.velX(i);
                            case OP_LVELY -> cloud.velY(i);
                            case OP_LVELZ -> cloud.velZ(i);
                            case OP_LANCHORX -> cloud.anchorX(i);
                            case OP_LANCHORY -> cloud.anchorYWorld(i);
                            case OP_LANCHORZ -> cloud.anchorZ(i);
                            case OP_LLOCALX -> cloud.x(i) - cloud.originX;
                            case OP_LLOCALY -> cloud.y(i) - cloud.originY;
                            default -> cloud.z(i) - cloud.originZ;
                        };
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LENERGY:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        r[l * F + d] = cloud.energy(idx[l]);
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LAXIS:
            case OP_LAXISX:
            case OP_LAXISY:
            case OP_LAXISZ:
                for (int l = 0; l < LANES; l++)
                {
                    int bit = 1 << l;
                    if ((m & bit) == 0)
                    {
                        continue;
                    }
                    if ((ready[0] & bit) == 0)
                    {
                        cloud.axisAt(idx[l], time, axisTmp);
                        ax[l] = axisTmp[0];
                        ay[l] = axisTmp[1];
                        az[l] = axisTmp[2];
                        ready[0] |= bit;
                    }
                    int o = l * F;
                    if (op == OP_LAXIS)
                    {
                        r[o + d] = ax[l];
                        r[o + d + 1] = ay[l];
                        r[o + d + 2] = az[l];
                    }
                    else
                    {
                        r[o + d] = op == OP_LAXISX ? ax[l] : op == OP_LAXISY ? ay[l] : az[l];
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LTARGET:
                for (int l = 0; l < LANES; l++)
                {
                    int bit = 1 << l;
                    if ((m & bit) == 0)
                    {
                        continue;
                    }
                    if ((ready[1] & bit) == 0)
                    {
                        tgt[l] = cloud.targetRadius(idx[l], time);
                        ready[1] |= bit;
                    }
                    r[l * F + d] = tgt[l];
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LR:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        r[l * F + d] = cloud.random01(idx[l]);
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LAGE:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        r[l * F + d] = time;
                    }
                }
                advance(pcs, m, ip + 1);
                return;

            // ------------------------------------------------ 粒子写
            case OP_SPOS:
            case OP_SPOSX:
            case OP_SPOSY:
            case OP_SPOSZ:
            case OP_SVEL:
            case OP_SVELX:
            case OP_SVELY:
            case OP_SVELZ:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        int i = idx[l];
                        switch (op)
                        {
                            case OP_SPOS:
                                cloud.setPos(i, r[o + d], r[o + d + 1], r[o + d + 2]);
                                break;
                            case OP_SPOSX:
                                cloud.setPos(i, r[o + d], cloud.y(i), cloud.z(i));
                                break;
                            case OP_SPOSY:
                                cloud.setPos(i, cloud.x(i), r[o + d], cloud.z(i));
                                break;
                            case OP_SPOSZ:
                                cloud.setPos(i, cloud.x(i), cloud.y(i), r[o + d]);
                                break;
                            case OP_SVEL:
                                cloud.setVel(i, r[o + d], r[o + d + 1], r[o + d + 2]);
                                break;
                            case OP_SVELX:
                                cloud.setVel(i, r[o + d], cloud.velY(i), cloud.velZ(i));
                                break;
                            case OP_SVELY:
                                cloud.setVel(i, cloud.velX(i), r[o + d], cloud.velZ(i));
                                break;
                            default:
                                cloud.setVel(i, cloud.velX(i), cloud.velY(i), r[o + d]);
                                break;
                        }
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_SENERGY:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        cloud.setEnergy(idx[l], r[l * F + d]);
                    }
                }
                advance(pcs, m, ip + 1);
                return;

            // ------------------------------------------------ 标量算术
            case OP_ADD_N:
            case OP_SUB_N:
            case OP_MUL_N:
            case OP_NEG_N:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        switch (op)
                        {
                            case OP_ADD_N: r[o + d] = r[o + a] + r[o + b]; break;
                            case OP_SUB_N: r[o + d] = r[o + a] - r[o + b]; break;
                            case OP_MUL_N: r[o + d] = r[o + a] * r[o + b]; break;
                            default: r[o + d] = -r[o + a]; break;
                        }
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_DIV_N:
            case OP_DIV_SCALE:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double den = r[o + b];
                    if (den == 0.0)
                    {
                        throw error(pos, ip, "除以零");
                    }
                    if (op == OP_DIV_N)
                    {
                        r[o + d] = r[o + a] / den;
                    }
                    else
                    {
                        r[o + d] = r[o + a] / den;
                        r[o + d + 1] = r[o + a + 1] / den;
                        r[o + d + 2] = r[o + a + 2] / den;
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_MOD_N:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double den = r[o + b];
                    if (den == 0.0)
                    {
                        throw error(pos, ip, "取模的除数为零");
                    }
                    r[o + d] = r[o + a] % den;
                }
                advance(pcs, m, ip + 1);
                return;

            // ------------------------------------------------ 向量算术
            case OP_ADD_V:
            case OP_SUB_V:
            case OP_SCALE:
            case OP_NEG_V:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    switch (op)
                    {
                        case OP_ADD_V:
                        {
                            double a0 = r[o + a];
                            double a1 = r[o + a + 1];
                            double a2 = r[o + a + 2];
                            r[o + d] = a0 + r[o + b];
                            r[o + d + 1] = a1 + r[o + b + 1];
                            r[o + d + 2] = a2 + r[o + b + 2];
                            break;
                        }
                        case OP_SUB_V:
                        {
                            double a0 = r[o + a];
                            double a1 = r[o + a + 1];
                            double a2 = r[o + a + 2];
                            r[o + d] = a0 - r[o + b];
                            r[o + d + 1] = a1 - r[o + b + 1];
                            r[o + d + 2] = a2 - r[o + b + 2];
                            break;
                        }
                        case OP_SCALE:
                        {
                            double s = r[o + b];
                            r[o + d] = r[o + a] * s;
                            r[o + d + 1] = r[o + a + 1] * s;
                            r[o + d + 2] = r[o + a + 2] * s;
                            break;
                        }
                        default:
                            r[o + d] = -r[o + a];
                            r[o + d + 1] = -r[o + a + 1];
                            r[o + d + 2] = -r[o + a + 2];
                            break;
                    }
                }
                advance(pcs, m, ip + 1);
                return;

            // ------------------------------------------------ 比较与逻辑
            case OP_LT:
            case OP_LE:
            case OP_GT:
            case OP_GE:
            case OP_EQ_N:
            case OP_NE_N:
            case OP_AND:
            case OP_OR:
            case OP_NOT:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        switch (op)
                        {
                            case OP_LT: r[o + d] = r[o + a] < r[o + b] ? 1.0 : 0.0; break;
                            case OP_LE: r[o + d] = r[o + a] <= r[o + b] ? 1.0 : 0.0; break;
                            case OP_GT: r[o + d] = r[o + a] > r[o + b] ? 1.0 : 0.0; break;
                            case OP_GE: r[o + d] = r[o + a] >= r[o + b] ? 1.0 : 0.0; break;
                            case OP_EQ_N: r[o + d] = r[o + a] == r[o + b] ? 1.0 : 0.0; break;
                            case OP_NE_N: r[o + d] = r[o + a] != r[o + b] ? 1.0 : 0.0; break;
                            case OP_AND:
                                r[o + d] = (r[o + a] != 0.0 && r[o + b] != 0.0) ? 1.0 : 0.0;
                                break;
                            case OP_OR:
                                r[o + d] = (r[o + a] != 0.0 || r[o + b] != 0.0) ? 1.0 : 0.0;
                                break;
                            default: r[o + d] = r[o + a] == 0.0 ? 1.0 : 0.0; break;
                        }
                    }
                }
                advance(pcs, m, ip + 1);
                return;

            // ------------------------------------------------ 控制流
            case OP_JZ:
            case OP_JNZ:
            {
                int target = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    boolean zero = r[l * F + d] == 0.0;
                    boolean jump = op == OP_JZ ? zero : !zero;
                    pcs[l] = jump ? target : ip + 2;
                }
                return;
            }
            case OP_JMP:
            {
                int target = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        pcs[l] = target;
                    }
                }
                return;
            }

            // ------------------------------------------------ 数学
            case OP_ABS:
            case OP_SQRT:
            case OP_SIGN:
            case OP_SIN:
            case OP_COS:
            case OP_EXP:
            case OP_FLOOR:
            case OP_ROUND:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double x = r[o + a];
                    r[o + d] = switch (op)
                    {
                        case OP_ABS -> Math.abs(x);
                        case OP_SQRT -> Math.sqrt(Math.max(0.0, x));
                        case OP_SIGN -> x > 0.0 ? 1.0 : x < 0.0 ? -1.0 : 0.0;
                        case OP_SIN -> SpellMath.sin(x);
                        case OP_COS -> SpellMath.cos(x);
                        case OP_EXP -> StrictMath.exp(x);
                        case OP_FLOOR -> Math.floor(x);
                        default -> Math.round(x);
                    };
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_MIN:
            case OP_MAX:
            case OP_POW:
            case OP_ATAN2:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        double x = r[o + a];
                        double y = r[o + b];
                        r[o + d] = switch (op)
                        {
                            case OP_MIN -> Math.min(x, y);
                            case OP_MAX -> Math.max(x, y);
                            case OP_POW -> StrictMath.pow(x, y);
                            default -> StrictMath.atan2(x, y);
                        };
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_CLAMP:
            {
                int hi = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = Math.max(r[o + b], Math.min(r[o + hi], r[o + a]));
                    }
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_SMOOTHSTEP:
            {
                int x = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = smoothstep(r[o + a], r[o + b], r[o + x]);
                    }
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_LERP_N:
            {
                int x = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        double a0 = r[o + a];
                        r[o + d] = a0 + (r[o + b] - a0) * r[o + x];
                    }
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_LERP_V:
            {
                int x = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double t = r[o + x];
                    double a0 = r[o + a];
                    double a1 = r[o + a + 1];
                    double a2 = r[o + a + 2];
                    r[o + d] = a0 + (r[o + b] - a0) * t;
                    r[o + d + 1] = a1 + (r[o + b + 1] - a1) * t;
                    r[o + d + 2] = a2 + (r[o + b + 2] - a2) * t;
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_DOT:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = r[o + a] * r[o + b] + r[o + a + 1] * r[o + b + 1]
                                + r[o + a + 2] * r[o + b + 2];
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_CROSS:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double ax0 = r[o + a];
                    double ay0 = r[o + a + 1];
                    double az0 = r[o + a + 2];
                    double bx0 = r[o + b];
                    double by0 = r[o + b + 1];
                    double bz0 = r[o + b + 2];
                    r[o + d] = ay0 * bz0 - az0 * by0;
                    r[o + d + 1] = az0 * bx0 - ax0 * bz0;
                    r[o + d + 2] = ax0 * by0 - ay0 * bx0;
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_LEN:
            case OP_LEN_XZ:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        double a0 = r[o + a];
                        double a1 = r[o + a + 1];
                        double a2 = r[o + a + 2];
                        double sq = op == OP_LEN ? a0 * a0 + a1 * a1 + a2 * a2 : a0 * a0 + a2 * a2;
                        r[o + d] = Math.sqrt(sq);
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_NORMALIZE:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double vx = r[o + a];
                    double vy = r[o + a + 1];
                    double vz = r[o + a + 2];
                    double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    if (len < 1.0E-9)
                    {
                        r[o + d] = 0.0;
                        r[o + d + 1] = 0.0;
                        r[o + d + 2] = 0.0;
                    }
                    else
                    {
                        r[o + d] = vx / len;
                        r[o + d + 1] = vy / len;
                        r[o + d + 2] = vz / len;
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_DIST:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        double dx = r[o + a] - r[o + b];
                        double dy = r[o + a + 1] - r[o + b + 1];
                        double dz = r[o + a + 2] - r[o + b + 2];
                        r[o + d] = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_TANGENT:
            case OP_RADIAL:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        tangent4(r, l * F, d, a, b, -1, 0, 0, 0, op == OP_TANGENT);
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_TANGENT_UP:
            case OP_RADIAL_UP:
            {
                int up = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) == 0)
                    {
                        continue;
                    }
                    int o = l * F;
                    double ul = Math.sqrt(r[o + up] * r[o + up]
                            + r[o + up + 1] * r[o + up + 1]
                            + r[o + up + 2] * r[o + up + 2]);
                    if (!(ul > 1.0E-9))
                    {
                        throw error(pos, ip, "tangent / radial 的第三个参数（轴向）不能是零向量");
                    }
                    tangent4(r, o, d, a, b, up, r[o + up] / ul, r[o + up + 1] / ul,
                            r[o + up + 2] / ul, op == OP_TANGENT_UP);
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_ROTATE:
            {
                int angle = code[ip + 1];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        rotate4(r, l * F, d, a, b, angle);
                    }
                }
                advance(pcs, m, ip + 2);
                return;
            }
            case OP_PICK:
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = r[o + a + b];
                    }
                }
                advance(pcs, m, ip + 1);
                return;
            case OP_NOISE:
            {
                int z = code[ip + 1];
                int stream = code[ip + 2];
                for (int l = 0; l < LANES; l++)
                {
                    if ((m & (1 << l)) != 0)
                    {
                        int o = l * F;
                        r[o + d] = SpellNoise.value3(seed, r[o + a], r[o + b],
                                r[o + z], (int) Math.round(r[o + stream]));
                    }
                }
                advance(pcs, m, ip + 3);
                return;
            }
            default:
                throw error(pos, ip, "未知指令 " + op);
        }
    }

    private static void advance(int[] pcs, int m, int next)
    {
        for (int l = 0; l < LANES; l++)
        {
            if ((m & (1 << l)) != 0)
            {
                pcs[l] = next;
            }
        }
    }

    /** 与标量版 {@code tangentUp} 同一式子；upReg&lt;0 表示世界 Y 轴 */
    private static void tangent4(double[] r, int o, int d, int a, int b,
                                 int upReg, double ux, double uy, double uz, boolean tangent)
    {
        if (upReg < 0)
        {
            ux = 0.0;
            uy = 1.0;
            uz = 0.0;
        }
        double rx = r[o + b] - r[o + a];
        double ry = r[o + b + 1] - r[o + a + 1];
        double rz = r[o + b + 2] - r[o + a + 2];
        double axial = rx * ux + ry * uy + rz * uz;
        double px = rx - ux * axial;
        double py = ry - uy * axial;
        double pz = rz - uz * axial;
        double len = Math.sqrt(px * px + py * py + pz * pz);
        if (len < 1.0E-9)
        {
            r[o + d] = 0.0;
            r[o + d + 1] = 0.0;
            r[o + d + 2] = 0.0;
            return;
        }
        if (!tangent)
        {
            r[o + d] = px / len;
            r[o + d + 1] = py / len;
            r[o + d + 2] = pz / len;
            return;
        }
        r[o + d] = (py * uz - pz * uy) / len;
        r[o + d + 1] = (pz * ux - px * uz) / len;
        r[o + d + 2] = (px * uy - py * ux) / len;
    }

    private static void rotate4(double[] r, int o, int d, int a, int b, int angleReg)
    {
        double kl = Math.sqrt(r[o + b] * r[o + b] + r[o + b + 1] * r[o + b + 1]
                + r[o + b + 2] * r[o + b + 2]);
        if (!(kl > 1.0E-9))
        {
            r[o + d] = r[o + a];
            r[o + d + 1] = r[o + a + 1];
            r[o + d + 2] = r[o + a + 2];
            return;
        }
        double kx = r[o + b] / kl;
        double ky = r[o + b + 1] / kl;
        double kz = r[o + b + 2] / kl;
        double angle = r[o + angleReg];
        double c = SpellMath.cos(angle);
        double s = SpellMath.sin(angle);
        double vx = r[o + a];
        double vy = r[o + a + 1];
        double vz = r[o + a + 2];
        double cx = ky * vz - kz * vy;
        double cy = kz * vx - kx * vz;
        double cz = kx * vy - ky * vx;
        double dot = kx * vx + ky * vy + kz * vz;
        double t = 1.0 - c;
        r[o + d] = vx * c + cx * s + kx * dot * t;
        r[o + d + 1] = vy * c + cy * s + ky * dot * t;
        r[o + d + 2] = vz * c + cz * s + kz * dot * t;
    }

    private static double smoothstep(double a, double b, double x)
    {
        if (b == a)
        {
            return 0.0;
        }
        double t = Math.max(0.0, Math.min(1.0, (x - a) / (b - a)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static RuntimeException error(int[] pos, int ip, String message)
    {
        int at = pos != null && ip >= 0 && ip < pos.length ? pos[ip] : 0;
        return new ScriptException(message, at >>> 12, at & 0xFFF);
    }
}
