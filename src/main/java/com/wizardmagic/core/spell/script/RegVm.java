package com.wizardmagic.core.spell.script;

import com.wizardmagic.api.spell.SpellMath;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellNoise;

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

    // ---------------------------------------------------------------- 热循环

    /**
     * 逐粒子跑一遍循环体。
     *
     * @param code   指令流
     * @param k      常量表
     * @param r      寄存器帧（每次调用新建，逐粒子复用）
     * @param fv     cast 变量快照
     * @param hv     外提常量
     * @param count  粒子数
     * @param time   当前时刻（秒），{@code p.age}/{@code p.axis}/{@code p.targetR} 用
     */
    static void execute(int[] code, double[] k, double[] r, double[] fv, double[] hv,
            SpellCloud cloud, int count, double time)
    {
        final int n = code.length;
        for (int i = 0; i < count; i++)
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
                        r[(w >>> DST_SHIFT) & FIELD] = fv[(w >>> A_SHIFT) & FIELD];
                        break;
                    case OP_LDF3:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int s = (w >>> A_SHIFT) & FIELD;
                        r[d] = fv[s];
                        r[d + 1] = fv[s + 1];
                        r[d + 2] = fv[s + 2];
                        break;
                    }
                    case OP_LDH1:
                        r[(w >>> DST_SHIFT) & FIELD] = hv[(w >>> A_SHIFT) & FIELD];
                        break;
                    case OP_LDH3:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int s = (w >>> A_SHIFT) & FIELD;
                        r[d] = hv[s];
                        r[d + 1] = hv[s + 1];
                        r[d + 2] = hv[s + 2];
                        break;
                    }

                    // ------------------------------------------------ 粒子读
                    case OP_LPOS:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = cloud.x(i);
                        r[d + 1] = cloud.y(i);
                        r[d + 2] = cloud.z(i);
                        break;
                    }
                    case OP_LPOSX:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.x(i);
                        break;
                    case OP_LPOSY:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.y(i);
                        break;
                    case OP_LPOSZ:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.z(i);
                        break;
                    case OP_LVEL:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = cloud.velX(i);
                        r[d + 1] = cloud.velY(i);
                        r[d + 2] = cloud.velZ(i);
                        break;
                    }
                    case OP_LVELX:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.velX(i);
                        break;
                    case OP_LVELY:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.velY(i);
                        break;
                    case OP_LVELZ:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.velZ(i);
                        break;
                    case OP_LENERGY:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.energy(i);
                        break;
                    case OP_LANCHOR:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = cloud.anchorX(i);
                        r[d + 1] = cloud.anchorYWorld(i);
                        r[d + 2] = cloud.anchorZ(i);
                        break;
                    }
                    case OP_LANCHORX:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.anchorX(i);
                        break;
                    case OP_LANCHORY:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.anchorYWorld(i);
                        break;
                    case OP_LANCHORZ:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.anchorZ(i);
                        break;
                    case OP_LLOCAL:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = cloud.x(i) - cloud.originX;
                        r[d + 1] = cloud.y(i) - cloud.originY;
                        r[d + 2] = cloud.z(i) - cloud.originZ;
                        break;
                    }
                    case OP_LLOCALX:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.x(i) - cloud.originX;
                        break;
                    case OP_LLOCALY:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.y(i) - cloud.originY;
                        break;
                    case OP_LLOCALZ:
                        r[(w >>> DST_SHIFT) & FIELD] = cloud.z(i) - cloud.originZ;
                        break;
                    case OP_LAXIS:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        if (!axisReady)
                        {
                            double[] a = cloud.axisAt(i, time);
                            ax = a[0];
                            ay = a[1];
                            az = a[2];
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
                            double[] a = cloud.axisAt(i, time);
                            ax = a[0];
                            ay = a[1];
                            az = a[2];
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
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setPos(i, r[s], r[s + 1], r[s + 2]);
                        break;
                    }
                    case OP_SPOSX:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setPos(i, r[s], cloud.y(i), cloud.z(i));
                        break;
                    }
                    case OP_SPOSY:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setPos(i, cloud.x(i), r[s], cloud.z(i));
                        break;
                    }
                    case OP_SPOSZ:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setPos(i, cloud.x(i), cloud.y(i), r[s]);
                        break;
                    }
                    case OP_SVEL:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setVel(i, r[s], r[s + 1], r[s + 2]);
                        break;
                    }
                    case OP_SVELX:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setVel(i, r[s], cloud.velY(i), cloud.velZ(i));
                        break;
                    }
                    case OP_SVELY:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setVel(i, cloud.velX(i), r[s], cloud.velZ(i));
                        break;
                    }
                    case OP_SVELZ:
                    {
                        int s = (w >>> DST_SHIFT) & FIELD;
                        cloud.setVel(i, cloud.velX(i), cloud.velY(i), r[s]);
                        break;
                    }
                    case OP_SENERGY:
                        cloud.setEnergy(i, r[(w >>> DST_SHIFT) & FIELD]);
                        break;

                    // ------------------------------------------------ 标量算术
                    case OP_ADD_N:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] + r[w & FIELD];
                        break;
                    case OP_SUB_N:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] - r[w & FIELD];
                        break;
                    case OP_MUL_N:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] * r[w & FIELD];
                        break;
                    case OP_DIV_N:
                    {
                        double den = r[w & FIELD];
                        if (den == 0.0)
                        {
                            throw divZero();
                        }
                        r[(w >>> DST_SHIFT) & FIELD] = r[(w >>> A_SHIFT) & FIELD] / den;
                        break;
                    }
                    case OP_NEG_N:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = -r[(w >>> A_SHIFT) & FIELD];
                        break;
                    }

                    // ------------------------------------------------ 向量算术
                    case OP_ADD_V:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
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
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
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
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        double s = r[w & FIELD];
                        r[d] = r[a] * s;
                        r[d + 1] = r[a + 1] * s;
                        r[d + 2] = r[a + 2] * s;
                        break;
                    }
                    case OP_DIV_SCALE:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        double s = r[w & FIELD];
                        if (s == 0.0)
                        {
                            throw divZero();
                        }
                        r[d] = r[a] / s;
                        r[d + 1] = r[a + 1] / s;
                        r[d + 2] = r[a + 2] / s;
                        break;
                    }
                    case OP_NEG_V:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        r[d] = -r[a];
                        r[d + 1] = -r[a + 1];
                        r[d + 2] = -r[a + 2];
                        break;
                    }

                    // ------------------------------------------------ 比较与逻辑
                    case OP_LT:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] < r[w & FIELD] ? 1.0 : 0.0;
                        break;
                    case OP_LE:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] <= r[w & FIELD] ? 1.0 : 0.0;
                        break;
                    case OP_GT:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] > r[w & FIELD] ? 1.0 : 0.0;
                        break;
                    case OP_GE:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] >= r[w & FIELD] ? 1.0 : 0.0;
                        break;
                    case OP_EQ_N:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] == r[w & FIELD] ? 1.0 : 0.0;
                        break;
                    case OP_NE_N:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[(w >>> A_SHIFT) & FIELD] != r[w & FIELD] ? 1.0 : 0.0;
                        break;
                    case OP_AND:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                (r[(w >>> A_SHIFT) & FIELD] != 0.0 && r[w & FIELD] != 0.0) ? 1.0 : 0.0;
                        break;
                    case OP_OR:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                (r[(w >>> A_SHIFT) & FIELD] != 0.0 || r[w & FIELD] != 0.0) ? 1.0 : 0.0;
                        break;
                    case OP_NOT:
                    {
                        int d = (w >>> DST_SHIFT) & FIELD;
                        r[d] = r[(w >>> A_SHIFT) & FIELD] == 0.0 ? 1.0 : 0.0;
                        break;
                    }

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

                    // ------------------------------------------------ 数学
                    case OP_ABS:
                        r[(w >>> DST_SHIFT) & FIELD] = Math.abs(r[(w >>> A_SHIFT) & FIELD]);
                        break;
                    case OP_SQRT:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                Math.sqrt(Math.max(0.0, r[(w >>> A_SHIFT) & FIELD]));
                        break;
                    case OP_SIN:
                        r[(w >>> DST_SHIFT) & FIELD] = SpellMath.sin(r[(w >>> A_SHIFT) & FIELD]);
                        break;
                    case OP_COS:
                        r[(w >>> DST_SHIFT) & FIELD] = SpellMath.cos(r[(w >>> A_SHIFT) & FIELD]);
                        break;
                    case OP_EXP:
                        r[(w >>> DST_SHIFT) & FIELD] = Math.exp(r[(w >>> A_SHIFT) & FIELD]);
                        break;
                    case OP_FLOOR:
                        r[(w >>> DST_SHIFT) & FIELD] = Math.floor(r[(w >>> A_SHIFT) & FIELD]);
                        break;
                    case OP_ROUND:
                        r[(w >>> DST_SHIFT) & FIELD] = Math.round(r[(w >>> A_SHIFT) & FIELD]);
                        break;
                    case OP_MIN:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                Math.min(r[(w >>> A_SHIFT) & FIELD], r[w & FIELD]);
                        break;
                    case OP_MAX:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                Math.max(r[(w >>> A_SHIFT) & FIELD], r[w & FIELD]);
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
                    {
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[a] * r[b] + r[a + 1] * r[b + 1] + r[a + 2] * r[b + 2];
                        break;
                    }
                    case OP_CROSS:
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
                        break;
                    }
                    case OP_LEN:
                    {
                        int a = (w >>> A_SHIFT) & FIELD;
                        double a0 = r[a];
                        double a1 = r[a + 1];
                        double a2 = r[a + 2];
                        r[(w >>> DST_SHIFT) & FIELD] = Math.sqrt(a0 * a0 + a1 * a1 + a2 * a2);
                        break;
                    }
                    case OP_LEN_XZ:
                    {
                        int a = (w >>> A_SHIFT) & FIELD;
                        double a0 = r[a];
                        double a2 = r[a + 2];
                        r[(w >>> DST_SHIFT) & FIELD] = Math.sqrt(a0 * a0 + a2 * a2);
                        break;
                    }
                    case OP_NORMALIZE:
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
                        break;
                    }
                    case OP_DIST:
                    {
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        double dx = r[a] - r[b];
                        double dy = r[a + 1] - r[b + 1];
                        double dz = r[a + 2] - r[b + 2];
                        r[(w >>> DST_SHIFT) & FIELD] = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        break;
                    }
                    case OP_TANGENT:
                    case OP_RADIAL:
                    {
                        int op = w >>> 24;
                        int d = (w >>> DST_SHIFT) & FIELD;
                        int a = (w >>> A_SHIFT) & FIELD;
                        int b = w & FIELD;
                        double rx = r[b] - r[a];
                        double rz = r[b + 2] - r[a + 2];
                        double len = Math.sqrt(rx * rx + rz * rz);
                        if (len < 1.0E-9)
                        {
                            r[d] = 0.0;
                            r[d + 1] = 0.0;
                            r[d + 2] = 0.0;
                        }
                        else if (op == OP_TANGENT)
                        {
                            r[d] = -rz / len;
                            r[d + 1] = 0.0;
                            r[d + 2] = rx / len;
                        }
                        else
                        {
                            r[d] = rx / len;
                            r[d + 1] = 0.0;
                            r[d + 2] = rz / len;
                        }
                        break;
                    }
                    case OP_PICK:
                        r[(w >>> DST_SHIFT) & FIELD] =
                                r[((w >>> A_SHIFT) & FIELD) + (w & FIELD)];
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
                    default:
                        throw new ScriptException("未知指令 " + (w >>> 24), 1, 1);
                }
            }
        }
    }

    /** 冷路径：除数为 0。放在方法外，热循环里只留一条分支。 */
    private static RuntimeException divZero()
    {
        return new ScriptException("除以零", 1, 1);
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
