package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.SpellMath;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellNoise;
import com.wizardmagic.core.spell.SpellRandom;

/**
 * 寄存器机后端：执行 {@link FastProgram} 编出的寄存器指令。
 *
 * <p><b>为什么不再是栈机</b>：栈机每算一个中间结果都要 {@code PUSH}/{@code POP} 两条指令，
 * 一条 {@code a*b + c} 要 5 次分派；寄存器机把每个中间结果钉在一个槽位上，同一条式子
 * 只要 2 次分派。变量的读写也不再需要 {@code LOAD}/{@code STORE} —— 变量本身就是寄存器，
 * {@code let}/{@code =} 只是"把值算进那个槽位"。</p>
 *
 * <p><b>打包</b>：一个寄存器只有 0–255 号，所以「操作码 + 目标 + 两个源」正好塞进一个
 * {@code int}：{@code op<<24 | dst<<16 | a<<8 | b}。这一条比什么都重要 —— 解释器的成本
 * 几乎全在"每条指令要读几个 int、每个 int 都要一次边界检查"上。打包后绝大部分指令
 * 只读 1 个 int，剩下的靠移位取字段（纯 ALU，不进访存端口）。</p>
 *
 * <p>装不进一个 int 的少数指令自己带后续 int：{@code LDK}（常量表下标）、
 * {@code JZ}/{@code JMP}（指令流下标，可能超过 255）、三元数学 {@code clamp/smoothstep/lerp}
 * （第三个源）、{@code noise}（第三、第四个源）。</p>
 *
 * <p><b>别名</b>：目标可以和源重合（{@code x = x + 1} 编成 {@code ADD x, x, t}），
 * 所以每条向量指令都先把源读进局部变量再写目标。</p>
 *
 * <p>{@link #execute} 是热路径：只收基本类型数组 + 云 + 两个标量，冷的东西（自由变量
 * 搬运、外提求值、失败回退）都在 {@link FastProgram} 里。这样 C2 编译的图小、
 * uncommon trap 少，不会被"一条几乎不走的错误分支"拖出主循环。</p>
 */
final class RegVm
{
    private RegVm() {}

    /** 寄存器编号的位宽上限：所有指令都把寄存器编号压进 8 位 */
    static final int MAX_REG = 256;

    private static final int DST_SHIFT = 16;
    private static final int A_SHIFT = 8;
    private static final int FIELD = 0xFF;

    /** 打包一条指令：op | 目标 | 源1 | 源2；用不到的字段填 0 */
    static int word(int op, int dst, int a, int b)
    {
        return (op << 24) | ((dst & FIELD) << DST_SHIFT) | ((a & FIELD) << A_SHIFT) | (b & FIELD);
    }

    /**
     * 这条指令占几个 int。
     *
     * <p>多字指令自己带后续词：{@code LDK}（常量下标）、{@code JZ/JMP/JNZ}（目标）、
     * 三元数学、{@code TANGENT_UP/RADIAL_UP/ROTATE}（第三参），{@code NOISE} 带两个字。
     * 扫描指令流（原生内核要预计算什么、能不能走 4 通道）必须用同一份。</p>
     */
    static int length(int op)
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

    // ---------------------------------------------------------------- 取数

    /** [LDK|d][k] d ← k[k] */
    static final int OP_LDK = 1;
    /** d ← r[a] */
    static final int OP_MOV = 2;
    /** d..d+2 ← r[a..a+2] */
    static final int OP_MOV3 = 3;
    /** d ← fv[a]（cast 变量，每步搬一次） */
    static final int OP_LDF1 = 4;
    /** d..d+2 ← fv[a..a+2] */
    static final int OP_LDF3 = 5;
    /** d ← hv[a]（循环体外提，每步算一次） */
    static final int OP_LDH1 = 6;
    /** d..d+2 ← hv[a..a+2] */
    static final int OP_LDH3 = 7;

    // 粒子读：只有一个目标寄存器
    static final int OP_LPOS = 8;
    static final int OP_LPOSX = 9;
    static final int OP_LPOSY = 10;
    static final int OP_LPOSZ = 11;
    static final int OP_LVEL = 12;
    static final int OP_LVELX = 13;
    static final int OP_LVELY = 14;
    static final int OP_LVELZ = 15;
    static final int OP_LENERGY = 16;
    static final int OP_LANCHOR = 17;
    static final int OP_LANCHORX = 18;
    static final int OP_LANCHORY = 19;
    static final int OP_LANCHORZ = 20;
    static final int OP_LLOCAL = 21;
    static final int OP_LLOCALX = 22;
    static final int OP_LLOCALY = 23;
    static final int OP_LLOCALZ = 24;
    /** 形状轴心，每粒每步最多算一次（里面有 sin/cos） */
    static final int OP_LAXIS = 25;
    static final int OP_LAXISX = 26;
    static final int OP_LAXISY = 27;
    static final int OP_LAXISZ = 28;
    /** 形状目标半径，同样惰性 */
    static final int OP_LTARGET = 29;
    static final int OP_LR = 30;
    static final int OP_LAGE = 31;

    // 粒子写：只有一个源寄存器
    static final int OP_SPOS = 32;
    static final int OP_SPOSX = 33;
    static final int OP_SPOSY = 34;
    static final int OP_SPOSZ = 35;
    static final int OP_SVEL = 36;
    static final int OP_SVELX = 37;
    static final int OP_SVELY = 38;
    static final int OP_SVELZ = 39;
    static final int OP_SENERGY = 40;

    // 标量算术：d, a, b
    static final int OP_ADD_N = 41;
    static final int OP_SUB_N = 42;
    static final int OP_MUL_N = 43;
    /** 除数为 0 时抛错（与解释器同一语义） */
    static final int OP_DIV_N = 44;
    /** d, a */
    static final int OP_NEG_N = 45;

    // 向量算术
    /** d, a, b（都是 vec3） */
    static final int OP_ADD_V = 46;
    static final int OP_SUB_V = 47;
    /** d(vec3), a(vec3), b(num) */
    static final int OP_SCALE = 48;
    /** d(vec3), a(vec3), b(num)，除数为 0 时抛错 */
    static final int OP_DIV_SCALE = 49;
    /** d, a（vec3） */
    static final int OP_NEG_V = 50;

    // 比较与逻辑：d, a, b
    static final int OP_LT = 51;
    static final int OP_LE = 52;
    static final int OP_GT = 53;
    static final int OP_GE = 54;
    static final int OP_EQ_N = 55;
    static final int OP_NE_N = 56;
    static final int OP_AND = 57;
    static final int OP_OR = 58;
    /** d, a */
    static final int OP_NOT = 59;

    // 控制流
    /** [JZ|a][目标下标] r[a] == 0 时跳 */
    static final int OP_JZ = 60;
    /** [JMP][目标下标] */
    static final int OP_JMP = 61;
    /** [JNZ|a][目标下标] r[a] != 0 时跳（{@code or} 的短路用） */
    static final int OP_JNZ = 85;

    // 一元数学：d, a
    static final int OP_ABS = 62;
    static final int OP_SQRT = 63;
    static final int OP_SIN = 64;
    static final int OP_COS = 65;
    static final int OP_EXP = 66;
    static final int OP_FLOOR = 67;
    static final int OP_ROUND = 68;

    // 二元数学：d, a, b
    static final int OP_MIN = 69;
    static final int OP_MAX = 70;

    // 三元数学：[op|d|a|b][c]
    /** d = clamp(x, lo, hi) */
    static final int OP_CLAMP = 71;
    static final int OP_SMOOTHSTEP = 72;
    static final int OP_LERP_N = 73;
    /** d(vec3), a(vec3), b(vec3), t(num) */
    static final int OP_LERP_V = 74;

    // 向量函数
    /** d(num), a(vec3), b(vec3) */
    static final int OP_DOT = 75;
    /** d(vec3), a(vec3), b(vec3) */
    static final int OP_CROSS = 76;
    /** d(num), a(vec3) */
    static final int OP_LEN = 77;
    static final int OP_LEN_XZ = 78;
    /** d(vec3), a(vec3) */
    static final int OP_NORMALIZE = 79;
    /** d(num), a(vec3), b(vec3) */
    static final int OP_DIST = 80;
    /** d(vec3), a(vec3=轴心), b(vec3=位置) */
    static final int OP_TANGENT = 81;
    static final int OP_RADIAL = 82;
    /** d, a(vec3), k：取分量 k（0/1/2） */
    static final int OP_PICK = 83;
    /** [NOISE|d|x|y][z][stream] */
    static final int OP_NOISE = 84;
    /**
     * d ← 本步第 seq 次 {@code rand()}，{@code seq} 从这次 {@link #execute} 的 0 开始。
     *
     * <p>序列是按<b>粒子顺序</b>消耗的（和第 0 粒的第 1 次、第 2 次…）：所以带 {@code rand}
     * 的循环体不能被切段并行——{@code FastProgram#parallelSafe()} 会因为这条指令返回 false，
     * 整朵云退回"一次跑完"。</p>
     */
    static final int OP_RAND = 86;
    /** [STF1|0|a|b] fv[a] ← r[b]：写回 cast 变量（整段跑完后由 FastProgram 搬回 Env） */
    static final int OP_STF1 = 87;
    /** [STF3|0|a|b] fv[a..a+2] ← r[b..b+2] */
    static final int OP_STF3 = 88;
    /** [TANGENT_UP|d|a|b][up] 绕任意轴的水平切向（第三参是轴向寄存器） */
    static final int OP_TANGENT_UP = 89;
    /** [RADIAL_UP|d|a|b][up] 绕任意轴的径向 */
    static final int OP_RADIAL_UP = 90;
    /** [ROTATE|d|a|b][angle] 绕轴 b 旋转 a 角度 r[angle] */
    static final int OP_ROTATE = 91;
    /** d = a % b（除数为 0 时抛错，与解释器同一语义） */
    static final int OP_MOD_N = 92;
    /** d = a 的符号：-1 / 0 / 1 */
    static final int OP_SIGN = 93;
    /** d = pow(a, b) */
    static final int OP_POW = 94;
    /** d = atan2(a, b) */
    static final int OP_ATAN2 = 95;
    // ---------------------------------------------------------------- 热循环

    /**
     * 逐粒子跑一遍循环体。
     *
     * @param code   指令流
     * @param pos    与 {@code code} 一一对应的源码位置（{@code line<<12|col}），只在报错时读
     * @param k      常量表
     * @param r      寄存器帧（每次调用新建，逐粒子复用）
     * @param fv     cast 变量快照
     * @param hv     外提常量
     * @param from   段起点（含）
     * @param to     段终点（不含）
     * @param time   当前时刻（秒），{@code p.age}/{@code p.axis}/{@code p.targetR} 用
     * @param randSeq 这一步已经消耗了多少次 {@code rand()}（同一朵云可能有多个循环，
     *                序列必须接着上一条循环往下数，否则和解释器对不上）
     * @return 跑完之后消耗到的序号
     */
    static int execute(int[] code, int[] pos, double[] k, double[] r, double[] fv, double[] hv,
            SpellCloud cloud, int from, int to, double time, int randSeq)
    {
        final int n = code.length;
        final double[] axis = new double[3];
        // rand() 的序列：每步从调用方给的序号开始，按粒子顺序与程序顺序消耗（与解释器同一条式子）
        final long seed = cloud.castSeed;
        final int step = cloud.ageSteps();
        for (int i = from; i < to; i++)
        {
            // p.axis / p.targetR 每粒每步只算一次：形状采样里有 sin/cos 和几次 pow
            boolean axisReady = false;
            boolean targetReady = false;
            double ax = 0.0;
            double ay = 0.0;
            double az = 0.0;
            double target = 0.0;

            int pc = 0;
            while (pc < n)
            {
                int w = code[pc++];
                switch (w >>> 24)
                {
                    // ------------------------------------------------ 取数
                    case OP_LDK:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = k[code[pc++]];
                        break;
                    }
                    case OP_MOV:
                        r[(w >>> DST_SHIFT) & FIELD] = r[(w >>> A_SHIFT) & FIELD];
                        break;
                    case OP_MOV3:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int s = (w >>> A_SHIFT) & FIELD;
                        r[d] = r[s];
                        r[d + 1] = r[s + 1];
                        r[d + 2] = r[s + 2];
                        break;
                    }
                    case OP_LDF1:
                    case OP_LDF3:
                    case OP_LDH1:
                    case OP_LDH3:
                        loadConst(w, r, fv, hv);
                        break;

                    // ------------------------------------------------ 粒子读
                    case OP_LPOS:
                    case OP_LVEL:
                    case OP_LANCHOR:
                    case OP_LLOCAL:
                        loadVec(w, r, cloud, i);
                        break;
                    case OP_LPOSX:
                    case OP_LPOSY:
                    case OP_LPOSZ:
                        loadPosComp(w, r, cloud, i);
                        break;
                    case OP_LVELX:
                    case OP_LVELY:
                    case OP_LVELZ:
                        loadVelComp(w, r, cloud, i);
                        break;
                    case OP_LANCHORX:
                    case OP_LANCHORY:
                    case OP_LANCHORZ:
                        loadAnchorComp(w, r, cloud, i);
                        break;
                    case OP_LLOCALX:
                    case OP_LLOCALY:
                    case OP_LLOCALZ:
                        loadLocalComp(w, r, cloud, i);
                        break;
                    case OP_LENERGY:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.energy(i);
                        break;
                    case OP_LAXIS:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        if (!axisReady)
                        {
                            cloud.axisAt(i, time, axis);
                            ax = axis[0];
                            ay = axis[1];
                            az = axis[2];
                            axisReady = true;
                        }
                        r[d] = ax;
                        r[d + 1] = ay;
                        r[d + 2] = az;
                        break;
                    }
                    case OP_LAXISX:
                    case OP_LAXISY:
                    case OP_LAXISZ:
                    {
                        int op = w >>> 24;
                        if (!axisReady)
                        {
                            cloud.axisAt(i, time, axis);
                            ax = axis[0];
                            ay = axis[1];
                            az = axis[2];
                            axisReady = true;
                        }
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = op == OP_LAXISX ? ax : op == OP_LAXISY ? ay : az;
                        break;
                    }
                    case OP_LTARGET:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        if (!targetReady)
                        {
                            target = cloud.targetRadius(i, time);
                            targetReady = true;
                        }
                        r[d] = target;
                        break;
                    }
                    case OP_LR:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.random01(i);
                        break;
                    case OP_LAGE:
                        r[(w >>> DST_SHIFT) & FIELD] = time;
                        break;

                    // ------------------------------------------------ 粒子写
                    case OP_SPOS:
                    case OP_SPOSX:
                    case OP_SPOSY:
                    case OP_SPOSZ:
                        storePos(w, r, cloud, i);
                        break;
                    case OP_SVEL:
                    case OP_SVELX:
                    case OP_SVELY:
                    case OP_SVELZ:
                        storeVel(w, r, cloud, i);
                        break;
                    case OP_SENERGY:
                        cloud.setEnergy(i, r[(w >>> DST_SHIFT) & FIELD]);
                        break;

                    // ------------------------------------------------ 标量算术
                    case OP_ADD_N:
                    case OP_SUB_N:
                    case OP_MUL_N:
                    case OP_NEG_N:
                        arithN(w, r);
                        break;
                    case OP_DIV_N:
                    case OP_DIV_SCALE:
                        // 除零要用当前指令的位置，所以把 ip 传进去（冷路径才用）
                        arithDiv(w, r, pos, pc - 1);
                        break;
                    case OP_MOD_N:
                        arithMod(w, r, pos, pc - 1);
                        break;

                    // ------------------------------------------------ 向量算术
                    case OP_ADD_V:
                    case OP_SUB_V:
                    case OP_SCALE:
                    case OP_NEG_V:
                        arithV(w, r);
                        break;

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
                        compare(w, r);
                        break;

                    // ------------------------------------------------ 控制流
                    case OP_JZ:
                    {
                        int c = (w >>> DST_SHIFT) & FIELD;
                        int t = code[pc++];
                        if (r[c] == 0.0)
                        {
                            pc = t;
                        }
                        break;
                    }
                    case OP_JMP:
                        pc = code[pc];
                        break;
                    case OP_JNZ:
                    {
                        int c = (w >>> DST_SHIFT) & FIELD;
                        int t = code[pc++];
                        if (r[c] != 0.0)
                        {
                            pc = t;
                        }
                        break;
                    }

                    // ------------------------------------------------ 数学
                    case OP_ABS:
                    case OP_SQRT:
                    case OP_SIGN:
                    case OP_EXP:
                    case OP_FLOOR:
                    case OP_ROUND:
                        mathBasic(w, r);
                        break;
                    case OP_SIN:
                    case OP_COS:
                        mathTrig(w, r);
                        break;
                    case OP_MIN:
                    case OP_MAX:
                        mathMinMax(w, r);
                        break;
                    case OP_POW:
                    case OP_ATAN2:
                        mathPowAtan2(w, r);
                        break;
                    case OP_CLAMP:
                    {
                        int x = (w >>> A_SHIFT) & FIELD;
                        int lo = w & FIELD;
                        int hi = code[pc++];
                        r[(w >>> DST_SHIFT) & FIELD] = Math.max(r[lo], Math.min(r[hi], r[x]));
                        break;
                    }
                    case OP_SMOOTHSTEP:
                    {
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        int x = code[pc++];
                        r[(w >>> DST_SHIFT) & FIELD] = smoothstep(r[a], r[b], r[x]);
                        break;
                    }
                    case OP_LERP_N:
                    {
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        double t = r[code[pc++]];
                        double a0 = r[a];
                        r[(w >>> DST_SHIFT) & FIELD] = a0 + (r[b] - a0) * t;
                        break;
                    }
                    case OP_LERP_V:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        double t = r[code[pc++]];
                        double a0 = r[a];
                        double a1 = r[a + 1];
                        double a2 = r[a + 2];
                        r[d] = a0 + (r[b] - a0) * t;
                        r[d + 1] = a1 + (r[b + 1] - a1) * t;
                        r[d + 2] = a2 + (r[b + 2] - a2) * t;
                        break;
                    }
                    case OP_DOT:
                        dot(w, r);
                        break;
                    case OP_CROSS:
                        crossV(w, r);
                        break;
                    case OP_LEN:
                    case OP_LEN_XZ:
                        lenV(w, r);
                        break;
                    case OP_NORMALIZE:
                        normalizeV(w, r);
                        break;
                    case OP_DIST:
                        distV(w, r);
                        break;
                    case OP_TANGENT:
                    case OP_RADIAL:
                        tangentV(w, r);
                        break;
                    case OP_PICK:
                        pickV(w, r);
                        break;
                    case OP_NOISE:
                    {
                        int x = (w >>> A_SHIFT) & FIELD;
                        int y = w & FIELD;
                        int z = code[pc++];
                        int stream = code[pc++];
                        r[(w >>> DST_SHIFT) & FIELD] = SpellNoise.value3(cloud.castSeed,
                                r[x], r[y], r[z], (int) Math.round(r[stream]));
                        break;
                    }
                    case OP_RAND:
                        r[(w >>> DST_SHIFT) & FIELD] = SpellRandom.unit(seed, step, randSeq++);
                        break;
                    case OP_STF1:
                    case OP_STF3:
                        storeFree(w, r, fv);
                        break;
                    case OP_TANGENT_UP:
                    case OP_RADIAL_UP:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        int up = code[pc++];
                        double ul = Math.sqrt(r[up] * r[up] + r[up + 1] * r[up + 1]
                                + r[up + 2] * r[up + 2]);
                        if (!(ul > 1.0E-9))
                        {
                            // 与解释器一致：轴向零向量是用法错误，不是"静默给零"
                            throw error(pos, pc - 2, "tangent / radial 的第三个参数（轴向）不能是零向量");
                        }
                        tangentUp(r, d, a, b, up, (w >>> 24) == OP_TANGENT_UP);
                        break;
                    }
                    case OP_ROTATE:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        int angle = code[pc++];
                        rotateInto(r, d, a, b, angle);
                        break;
                    }
                    default:
                        throw error(pos, pc - 1, "未知指令 " + (w >>> 24));
                }
            }
        }
        return randSeq;
    }

    // ---------------------------------------------------------------- 指令组
    //
    // 拆成这些小方法，是为了让 {@link #execute} 的控制流小到 IDE 的分析器能处理
    // （以前 95 个 case 挤在一个方法里，IDE 直接放弃数据流分析）。
    //
    // 两条规矩：
    //  1) 每个方法都很小（几十字节），JIT 在热调用点上会内联，机器码和拆之前一样；
    //  2) 凡是<b>要改循环局部变量</b>的指令——pc（JZ/JMP/JNZ/LDK/多字指令）、
    //     轴点/目标半径的惰性缓存（LAXIS*/LTARGET）、随机序号（RAND）——一律留在
    //     execute 里内联。这些局部变量能被提升到寄存器，是热循环的关键。

    /** LDF1 / LDF3 / LDH1 / LDH3：从 cast 变量快照或外提量取数 */
    private static void loadConst(int w, double[] r, double[] fv, double[] hv)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int s = (w >>> A_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_LDF1:
                r[d] = fv[s];
                break;
            case OP_LDF3:
                r[d] = fv[s];
                r[d + 1] = fv[s + 1];
                r[d + 2] = fv[s + 2];
                break;
            case OP_LDH1:
                r[d] = hv[s];
                break;
            default:
                r[d] = hv[s];
                r[d + 1] = hv[s + 1];
                r[d + 2] = hv[s + 2];
                break;
        }
    }

    /** LPOS / LVEL / LANCHOR / LLOCAL：整向量 */
    private static void loadVec(int w, double[] r, SpellCloud cloud, int i)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_LPOS:
                r[d] = cloud.x(i);
                r[d + 1] = cloud.y(i);
                r[d + 2] = cloud.z(i);
                break;
            case OP_LVEL:
                r[d] = cloud.velX(i);
                r[d + 1] = cloud.velY(i);
                r[d + 2] = cloud.velZ(i);
                break;
            case OP_LANCHOR:
                r[d] = cloud.anchorX(i);
                r[d + 1] = cloud.anchorYWorld(i);
                r[d + 2] = cloud.anchorZ(i);
                break;
            default:
                r[d] = cloud.x(i) - cloud.originX;
                r[d + 1] = cloud.y(i) - cloud.originY;
                r[d + 2] = cloud.z(i) - cloud.originZ;
                break;
        }
    }

    /** LPOSX/Y/Z 单个分量直取（每个 x/y/z 各自成组，方法小到一定被内联） */
    private static void loadPosComp(int w, double[] r, SpellCloud cloud, int i)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_LPOSX: r[d] = cloud.x(i); break;
            case OP_LPOSY: r[d] = cloud.y(i); break;
            default: r[d] = cloud.z(i); break;
        }
    }

    private static void loadVelComp(int w, double[] r, SpellCloud cloud, int i)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_LVELX: r[d] = cloud.velX(i); break;
            case OP_LVELY: r[d] = cloud.velY(i); break;
            default: r[d] = cloud.velZ(i); break;
        }
    }

    private static void loadAnchorComp(int w, double[] r, SpellCloud cloud, int i)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_LANCHORX: r[d] = cloud.anchorX(i); break;
            case OP_LANCHORY: r[d] = cloud.anchorYWorld(i); break;
            default: r[d] = cloud.anchorZ(i); break;
        }
    }

    private static void loadLocalComp(int w, double[] r, SpellCloud cloud, int i)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_LLOCALX: r[d] = cloud.x(i) - cloud.originX; break;
            case OP_LLOCALY: r[d] = cloud.y(i) - cloud.originY; break;
            default: r[d] = cloud.z(i) - cloud.originZ; break;
        }
    }

    /** SPOS / SPOSX/Y/Z */
    private static void storePos(int w, double[] r, SpellCloud cloud, int i)
    {
        int s = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_SPOS: cloud.setPos(i, r[s], r[s + 1], r[s + 2]); break;
            case OP_SPOSX: cloud.setPos(i, r[s], cloud.y(i), cloud.z(i)); break;
            case OP_SPOSY: cloud.setPos(i, cloud.x(i), r[s], cloud.z(i)); break;
            default: cloud.setPos(i, cloud.x(i), cloud.y(i), r[s]); break;
        }
    }

    /** SVEL / SVELX/Y/Z */
    private static void storeVel(int w, double[] r, SpellCloud cloud, int i)
    {
        int s = (w >>> DST_SHIFT) & FIELD;
        switch (w >>> 24)
        {
            case OP_SVEL: cloud.setVel(i, r[s], r[s + 1], r[s + 2]); break;
            case OP_SVELX: cloud.setVel(i, r[s], cloud.velY(i), cloud.velZ(i)); break;
            case OP_SVELY: cloud.setVel(i, cloud.velX(i), r[s], cloud.velZ(i)); break;
            default: cloud.setVel(i, cloud.velX(i), cloud.velY(i), r[s]); break;
        }
    }

    /** ADD_N / SUB_N / MUL_N / NEG_N */
    private static void arithN(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        int b = w & FIELD;
        switch (w >>> 24)
        {
            case OP_ADD_N: r[d] = r[a] + r[b]; break;
            case OP_SUB_N: r[d] = r[a] - r[b]; break;
            case OP_MUL_N: r[d] = r[a] * r[b]; break;
            default: r[d] = -r[a]; break;
        }
    }

    /** DIV_N / DIV_SCALE：除数为 0 时按源码位置抛错 */
    private static void arithDiv(int w, double[] r, int[] pos, int ip)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        double den = r[w & FIELD];
        if (den == 0.0)
        {
            throw divZero(pos, ip);
        }
        if ((w >>> 24) == OP_DIV_N)
        {
            r[d] = r[a] / den;
            return;
        }
        r[d] = r[a] / den;
        r[d + 1] = r[a + 1] / den;
        r[d + 2] = r[a + 2] / den;
    }

    /** ADD_V / SUB_V / SCALE / NEG_V */
    private static void arithV(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        int b = w & FIELD;
        switch (w >>> 24)
        {
            case OP_ADD_V:
            {
                double a0 = r[a];
                double a1 = r[a + 1];
                double a2 = r[a + 2];
                r[d] = a0 + r[b];
                r[d + 1] = a1 + r[b + 1];
                r[d + 2] = a2 + r[b + 2];
                break;
            }
            case OP_SUB_V:
            {
                double a0 = r[a];
                double a1 = r[a + 1];
                double a2 = r[a + 2];
                r[d] = a0 - r[b];
                r[d + 1] = a1 - r[b + 1];
                r[d + 2] = a2 - r[b + 2];
                break;
            }
            case OP_SCALE:
            {
                double s = r[b];
                r[d] = r[a] * s;
                r[d + 1] = r[a + 1] * s;
                r[d + 2] = r[a + 2] * s;
                break;
            }
            default:
                r[d] = -r[a];
                r[d + 1] = -r[a + 1];
                r[d + 2] = -r[a + 2];
                break;
        }
    }

    /** 比较与逻辑：结果一律写 0.0 / 1.0 */
    private static void compare(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        int b = w & FIELD;
        switch (w >>> 24)
        {
            case OP_LT: r[d] = r[a] < r[b] ? 1.0 : 0.0; break;
            case OP_LE: r[d] = r[a] <= r[b] ? 1.0 : 0.0; break;
            case OP_GT: r[d] = r[a] > r[b] ? 1.0 : 0.0; break;
            case OP_GE: r[d] = r[a] >= r[b] ? 1.0 : 0.0; break;
            case OP_EQ_N: r[d] = r[a] == r[b] ? 1.0 : 0.0; break;
            case OP_NE_N: r[d] = r[a] != r[b] ? 1.0 : 0.0; break;
            case OP_AND: r[d] = (r[a] != 0.0 && r[b] != 0.0) ? 1.0 : 0.0; break;
            case OP_OR: r[d] = (r[a] != 0.0 || r[b] != 0.0) ? 1.0 : 0.0; break;
            default: r[d] = r[a] == 0.0 ? 1.0 : 0.0; break;
        }
    }

    /**
     * ABS / SQRT / SIGN / EXP / FLOOR / ROUND（热路径）。
     *
     * <p>原来把 sin/cos 也塞在同一个方法里（194 字节），C2 判定 "too big" 拒绝内联；
     * 拆成两组之后这个只有 ~90 字节，能稳定内联。sin/cos 单独一组，只有真用到的脚本
     * 才付那次调用。</p>
     */
    private static void mathBasic(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        double a = r[(w >>> A_SHIFT) & FIELD];
        switch (w >>> 24)
        {
            case OP_ABS: r[d] = Math.abs(a); break;
            case OP_SQRT: r[d] = Math.sqrt(Math.max(0.0, a)); break;
            // sign 用比较写出来，避免 Math.signum 的 ±0.0 细节在两端有任何差异
            case OP_SIGN: r[d] = a > 0.0 ? 1.0 : a < 0.0 ? -1.0 : 0.0; break;
            // StrictMath：Math.exp 是平台 intrinsic（实测 50 万样本 48,502 个 bit 与 fdlibm 不同），
            // 跨 JVM/跨平台/与将来的 C++ 内核都不可能一致。整数契约从这里开始就是 fdlibm。
            case OP_EXP: r[d] = StrictMath.exp(a); break;
            case OP_FLOOR: r[d] = Math.floor(a); break;
            default: r[d] = Math.round(a); break;
        }
    }

    /** SIN / COS（查表，冷一些） */
    private static void mathTrig(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        double a = r[(w >>> A_SHIFT) & FIELD];
        r[d] = (w >>> 24) == OP_SIN ? SpellMath.sin(a) : SpellMath.cos(a);
    }

    /** MIN / MAX（热路径，单独拆出来以便内联） */
    private static void mathMinMax(int w, double[] r)
    {
        int a = (w >>> A_SHIFT) & FIELD;
        double x = r[a];
        double y = r[w & FIELD];
        r[(w >>> DST_SHIFT) & FIELD] = (w >>> 24) == OP_MIN ? Math.min(x, y) : Math.max(x, y);
    }

    /** POW / ATAN2（StrictMath，冷路径） */
    private static void mathPowAtan2(int w, double[] r)
    {
        int a = (w >>> A_SHIFT) & FIELD;
        double x = r[a];
        double y = r[w & FIELD];
        // StrictMath：Math.pow / Math.atan2 允许 ±1 ulp 的平台差异，两端是不同 JVM，
        // 用 StrictMath 才能保证服务端和客户端逐位一致（解释器那边也是 StrictMath）。
        r[(w >>> DST_SHIFT) & FIELD] = (w >>> 24) == OP_POW
                ? StrictMath.pow(x, y) : StrictMath.atan2(x, y);
    }

    /** MOD：除数为 0 与除法同语义（抛错，而不是返回 NaN） */
    private static void arithMod(int w, double[] r, int[] pos, int ip)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        double den = r[w & FIELD];
        if (den == 0.0)
        {
            // 措辞和解释器一致：脚本作者看到的应该是"取模"，不是笼统的"除以零"
            throw error(pos, ip, "取模的除数为零");
        }
        r[d] = r[a] % den;
    }

    /** DOT（一行式子，内联成本为零） */
    private static void dot(int w, double[] r)
    {
        int a = (w >>> A_SHIFT) & FIELD;
        int b = w & FIELD;
        r[(w >>> DST_SHIFT) & FIELD] = r[a] * r[b] + r[a + 1] * r[b + 1] + r[a + 2] * r[b + 2];
    }

    private static void crossV(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        int b = w & FIELD;
        double ax0 = r[a];
        double ay0 = r[a + 1];
        double az0 = r[a + 2];
        double bx0 = r[b];
        double by0 = r[b + 1];
        double bz0 = r[b + 2];
        r[d] = ay0 * bz0 - az0 * by0;
        r[d + 1] = az0 * bx0 - ax0 * bz0;
        r[d + 2] = ax0 * by0 - ay0 * bx0;
    }

    private static void lenV(int w, double[] r)
    {
        int a = (w >>> A_SHIFT) & FIELD;
        double a0 = r[a];
        double a1 = r[a + 1];
        double a2 = r[a + 2];
        double sq = (w >>> 24) == OP_LEN ? a0 * a0 + a1 * a1 + a2 * a2 : a0 * a0 + a2 * a2;
        r[(w >>> DST_SHIFT) & FIELD] = Math.sqrt(sq);
    }

    private static void normalizeV(int w, double[] r)
    {
        int d = (w >>> DST_SHIFT) & FIELD;
        int a = (w >>> A_SHIFT) & FIELD;
        double vx = r[a];
        double vy = r[a + 1];
        double vz = r[a + 2];
        double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (l < 1.0E-9)
        {
            r[d] = 0.0;
            r[d + 1] = 0.0;
            r[d + 2] = 0.0;
        }
        else
        {
            r[d] = vx / l;
            r[d + 1] = vy / l;
            r[d + 2] = vz / l;
        }
    }

    private static void distV(int w, double[] r)
    {
        int a = (w >>> A_SHIFT) & FIELD;
        int b = w & FIELD;
        double dx = r[a] - r[b];
        double dy = r[a + 1] - r[b + 1];
        double dz = r[a + 2] - r[b + 2];
        r[(w >>> DST_SHIFT) & FIELD] = Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** TANGENT / RADIAL：绕世界 Y 轴（旧行为） */
    private static void tangentV(int w, double[] r)
    {
        tangentUp(r, (w >>> DST_SHIFT) & FIELD, (w >>> A_SHIFT) & FIELD, w & FIELD,
                -1, (w >>> 24) == OP_TANGENT);
    }

    /**
     * 切向 / 径向，{@code upReg < 0} 表示世界 Y 轴。
     *
     * <p>与解释器同一个式子：先把相对向量里的轴向分量投影掉，再取径向 / 切向。
     * 两参版本（up = Y）投影后正好是 {@code (rx, 0, rz)}，与旧实现逐位一致。</p>
     */
    private static void tangentUp(double[] r, int d, int a, int b, int upReg, boolean tangent)
    {
        double ux = 0.0;
        double uy = 1.0;
        double uz = 0.0;
        if (upReg >= 0)
        {
            double ul = Math.sqrt(r[upReg] * r[upReg] + r[upReg + 1] * r[upReg + 1]
                    + r[upReg + 2] * r[upReg + 2]);
            if (!(ul > 1.0E-9))
            {
                r[d] = 0.0;
                r[d + 1] = 0.0;
                r[d + 2] = 0.0;
                return;
            }
            ux = r[upReg] / ul;
            uy = r[upReg + 1] / ul;
            uz = r[upReg + 2] / ul;
        }
        double rx = r[b] - r[a];
        double ry = r[b + 1] - r[a + 1];
        double rz = r[b + 2] - r[a + 2];
        double axial = rx * ux + ry * uy + rz * uz;
        double px = rx - ux * axial;
        double py = ry - uy * axial;
        double pz = rz - uz * axial;
        double l = Math.sqrt(px * px + py * py + pz * pz);
        if (l < 1.0E-9)
        {
            r[d] = 0.0;
            r[d + 1] = 0.0;
            r[d + 2] = 0.0;
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

    /** 绕任意轴旋转（右手定则），与解释器同一式子；轴退化时原样返回 */
    private static void rotateInto(double[] r, int d, int a, int b, int angleReg)
    {
        int k = b;
        double kl = Math.sqrt(r[k] * r[k] + r[k + 1] * r[k + 1] + r[k + 2] * r[k + 2]);
        if (!(kl > 1.0E-9))
        {
            r[d] = r[a];
            r[d + 1] = r[a + 1];
            r[d + 2] = r[a + 2];
            return;
        }
        double kx = r[k] / kl;
        double ky = r[k + 1] / kl;
        double kz = r[k + 2] / kl;
        double angle = r[angleReg];
        double c = SpellMath.cos(angle);
        double s = SpellMath.sin(angle);
        double vx = r[a];
        double vy = r[a + 1];
        double vz = r[a + 2];
        double cx = ky * vz - kz * vy;
        double cy = kz * vx - kx * vz;
        double cz = kx * vy - ky * vx;
        double dot = kx * vx + ky * vy + kz * vz;
        double t = 1.0 - c;
        r[d] = vx * c + cx * s + kx * dot * t;
        r[d + 1] = vy * c + cy * s + ky * dot * t;
        r[d + 2] = vz * c + cz * s + kz * dot * t;
    }

    private static void pickV(int w, double[] r)
    {
        r[(w >>> DST_SHIFT) & FIELD] = r[((w >>> A_SHIFT) & FIELD) + (w & FIELD)];
    }

    /** STF1 / STF3：写回 cast 变量 */
    private static void storeFree(int w, double[] r, double[] fv)
    {
        int off = (w >>> A_SHIFT) & FIELD;
        int s = w & FIELD;
        fv[off] = r[s];
        if ((w >>> 24) == OP_STF3)
        {
            fv[off + 1] = r[s + 1];
            fv[off + 2] = r[s + 2];
        }
    }


    /**
     * 按出错指令的源码位置造异常。
     *
     * <p>以前这里固定写 {@code (1, 1)}——tick 里除零只能看到"第 1 行第 1 列"，等于没有。
     * 位置表由编译器按语句填（见 {@code FastProgram.IntList#at}），热循环不读它。</p>
     */
    /** 冷路径：除数为 0。放在方法外，热循环里只留一条分支。 */
    private static RuntimeException divZero(int[] pos, int ip)
    {
        return error(pos, ip, "除以零");
    }

    private static ScriptException error(int[] pos, int ip, String message)
    {
        int at = pos != null && ip >= 0 && ip < pos.length ? pos[ip] : 0;
        return new ScriptException(message, at >>> 12, at & 0xFFF);
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
}
