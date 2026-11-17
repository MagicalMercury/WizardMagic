package com.wizardmagic.core.spell;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.SpellShape;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.core.spell.script.EmitSpec;
import com.wizardmagic.core.spell.script.ScriptException;
import com.wizardmagic.core.spell.script.SpellScript;
import com.wizardmagic.core.spell.script.SpellWorld;
import com.wizardmagic.core.spell.script.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一朵法术云的粒子集合，SoA 布局（基本类型数组，禁止 {@code Object[]}）。
 *
 * <p>服务端持有真值；客户端收到 {@code SpellCloudSpawnPayload} 后用同样的
 * 构造参数 new 一份，靠 {@link SpellRandom} 与 {@link Laws} 的确定性复现同一轨迹。</p>
 *
 * <p>坐标单位：格（blocks）；速度单位：格/秒；{@link #ageSteps()} 是已推进的物理步数
 * （每 {@link SpellConstants#STEP_TICKS} tick 一步）。</p>
 */
public final class SpellCloud
{
    /** 全局自增 id，客户端靠它认云 */
    public final int id;
    /** 所有随机与噪声都由它派生 */
    public final long castSeed;
    /** 元素表下标（0..49），不是 {@code ElementTypes} */
    public final int elementId;
    /** 粒子数 8..8192（见 {@link SpellConstants#MAX_CLOUD_PARTICLES}） */
    public final int count;
    /** 云的寿命（秒） */
    public final float lifeSec;
    /** 总步数，到点后整朵云消失（常驻云为极值，不会自然死亡） */
    public final int maxSteps;
    /**
     * 常驻云（法阵 / 结界）：不会因寿命自然消失，附近没有玩家也继续推进。
     * 只能由显式命令或宿主方块销毁。
     *
     * <p><b>常驻云同时是静止的</b>：{@link #step()} 对 persistent 只累计年龄、不推进位置
     * （调试命令生成的常驻云不会自己飘走）。</p>
     */
    public final boolean persistent;

    /** 原点（P2 起是形状原点，例如龙卷的底面中心） */
    public final double originX;
    public final double originY;
    public final double originZ;
    /** 形状 id（脚本/命令引用；客户端按同一形状重建初始位置） */
    public final String shapeId;
    /** 形状参数 */
    public final ShapeParams shapeParams;
    /** 解析后的形状对象（避免每步查表） */
    public final SpellShape shape;
    /** 原点所在区块坐标（不 import Minecraft，保持本类可离机测试） */
    private final int chunkX;
    private final int chunkZ;

    // ---- 基础定律参数（Laws：元素浮力 + 线性阻尼 + 噪声扩散 + 能量） ----
    /** 线性阻尼（1/s） */
    public final float drag;

    // ---- energy ----
    public final float energyInit;
    public final float energyDecay;

    /**
     * 行为脚本；{@code null} = 走基础 {@link Laws}（只有浮力/阻尼/扩散/能量）。
     *
     * <p>有脚本时 {@code drag} 也不参与模拟，粒子每一步怎么动完全由脚本决定。</p>
     */
    public SpellScript script;
    /** 云级变量（{@code on cast} 顶层 let/param 的快照），tick 里可读可写 */
    public Map<String, Value> scriptVars = Map.of();
    /** 脚本求值抛错后置位：停止推进，由管理器当死亡移除 */
    public boolean broken;
    /** 出错原因（日志/命令显示用），正常时为 null */
    public String breakReason;
    /**
     * 服务端生成这朵云时的 {@code level.getGameTime()}。
     *
     * <p>客户端收到生成包时用它算出「服务端已经走了几步」并快进补齐：
     * 服务端在生成的同一 tick 末尾就会 step 一次，而客户端只能从下一个偶数 tick 开始，
     * 不补的话会永久落后 1–2 步（0.1–0.2 s），短法术的尾巴还会被移除包提前切掉。</p>
     */
    public long spawnGameTime;

    /**
     * 方块碰撞（可选）。{@code null} = 不碰撞——离机自检、穿方块的元素、以及没有世界
     * 输入的场景都走这条路。服务端与客户端各自塞一份，读各自的世界。
     */
    private ParticleCollider collider;

    private int ageSteps;

    /**
     * 最近一步的撞击事实（{@link ParticleCollider} 在步末填）。命中 {@code on hit} 用。
     *
     * <p>之所以是「最近一步」而不是「这一步」：碰撞是积分之后的后处理，所以第 N 步的
     * 撞击在脚本第 N 步的 {@code on tick} 里还看不到，第 N+1 步才看得到。一个事件钩子
     * 在步末触发，正好赶在同一条件生效的当口，不会偏移。</p>
     */
    private int hitCount;
    private double hitSumX;
    private double hitSumY;
    private double hitSumZ;
    private double hitSumVX;
    private double hitSumVY;
    private double hitSumVZ;
    private double hitSumNX;
    private double hitSumNY;
    private double hitSumNZ;
    /** 撞到的方块 id（本步第一个撞击格；{@code null} = 不知道/离线假世界） */
    private String hitBlock;

    /**
     * 一次撞击事件的事实。
     *
     * @param x,y,z   撞击点（本步撞击粒子的平均位置）
     * @param vx,vy,vz 撞前速度（平均）
     * @param nx,ny,nz 撞击面的法向（指向"被撞的那一面"，已归一化）
     * @param block   撞到的方块 id（{@code "minecraft:ice"}；不知道时为空串）
     * @param count   本步撞到方块的粒子数
     */
    public record HitFacts(int count, double x, double y, double z,
                           double vx, double vy, double vz,
                           double nx, double ny, double nz, String block)
    {
    }

    /**
     * 一朵云一辈子最多生几个子云（tick 里的 {@code emit}）。
     *
     * <p>防脚本把世界塞满：每步最多 {@link Interpreter} 的 {@code MAX_EMITS}、终身再卡一个
     * 硬上限；子云本身<b>不再生孙云</b>（见 {@link SpellCloudManager}）。</p>
     */
    public static final int MAX_EMITS_TOTAL = 32;

    /** 这一步攒下的待生成云（只有服务端会用；客户端照样求值但不取用） */
    private final List<EmitSpec> pendingEmits = new ArrayList<>();
    /** 已经接下的 emit 数（终身配额） */
    private int emittedTotal;
    /** 服务端才置 true：客户端不生成云，只等服务器下发 */
    private boolean emitsEnabled;
    /** 服务端才置 true：只有权威端会跑 {@code on expire} */
    private boolean serverSide;

    /**
     * 世界出口（服务端才有实现；客户端与离机自检为 {@code null} = 伤害 no-op、碰到生物也不判定）。
     *
     * <p>客户端也跑同一份脚本（粒子轨迹要两端一致），但它绝不能真的打人——所以脚本里的
     * {@code hurt(...)} 在客户端"执行了、什么也没发生"，这一点和 {@code emit} 完全同构。</p>
     */
    private SpellWorld world;
    /** 施法者 UUID（伤害/接触时跳过自己；{@code null} = 不知道/无主） */
    private UUID casterId;
    /**
     * 一步之内最多打几次伤害。
     *
     * <p>配额是**按步**的，不是终身的：一朵火焰风暴在生物堆里每步都要各打一下，
     * 终身配额（以前是 8）会在第 8 次之后把一朵还要活 12 秒的云提前掐死——实机现象就是
     * "打两下之后再也不掉血，粒子明明还糊在身上"。终身上限由云的寿命本身兜着，
     * 这里只需要挡住"一步之内刷一片"。</p>
     */
    public static final int MAX_HURT_CALLS_PER_STEP = 8;
    /** 本步已经打过的次数 */
    private int hurtCallsPerStep;
    /** 已经打过的总次数（终身，诊断用） */
    private int hurtCalls;
    /** 一步之内最多对几个实体触发 {@code on touch}（一堆生物叠在一起时挡一下） */
    public static final int MAX_TOUCHES_PER_STEP = 8;
    /** 已经触发过多少次 {@code on touch}（终身，诊断用；上限由云的寿命兜着） */
    private int touchCalls;
    /** 脚本自己结束这朵云（{@code end()}）：不跑 {@code on expire} */
    private boolean ended;

    // ---- SoA ----
    final double[] x;
    final double[] y;
    final double[] z;
    final double[] vx;
    final double[] vy;
    final double[] vz;
    final float[] energy;
    /**
     * 每粒的回位锚点（**相对原点的世界坐标偏移**，已按 {@link #faceX} 转过）。
     * 回位弹簧用；竖直朝上时它就是 (centerX, 局地高度, centerZ)。
     */
    final double[] anchorDX;
    final double[] anchorDY;
    final double[] anchorDZ;
    /** 每粒的旋转中心（相对原点的世界坐标偏移，已转过）：切向/径向力绕它旋转 */
    final double[] centerDX;
    final double[] centerDY;
    final double[] centerDZ;
    /** {@link #axisAt} 的复用缓冲（云只在单线程里推进） */
    private final double[] axisScratch = new double[3];
    /** 形状的每粒静态系数（`prepare` 生成一次，避免每步重算 pow） */
    private final double[] shapePrep;
    /** 粒子半径缓存（渲染与碰撞共用，见 {@link #particleRadius()}） */
    private double particleRadius = -1.0;

    /**
     * 每颗粒子的半径（世界单位）：既是最小 billboard 的半边长，也是碰撞用的球半径。
     *
     * <p>碰撞必须按这个半径算，否则球心会贴到方块表面——画面上的下半截就埋进地里了。
     * 渲染和碰撞共用同一个值，才能"看起来刚好落在表面上"。</p>
     *
     * <p>用 {@link StrictMath#cbrt} 并量化到 1/256：跨平台 {@code Math.cbrt} 不保证末位一致，
     * 量化之后服务端与客户端算出来的半径逐位相同，碰撞结果才不会分叉。</p>
     */
    public double particleRadius()
    {
        double r = particleRadius;
        if (r < 0.0)
        {
            r = Math.round((0.06 + 0.02 * StrictMath.cbrt(count)) * 256.0) / 256.0;
            particleRadius = r;
        }
        return r;
    }

    /**
     * 形状朝向：形状本地 +Y 轴指向这个方向（单位向量，默认 (0,1,0) 竖直向上）。
     *
     * <p>服务端与客户端必须逐位一致：值随生成包/快照包下发，两端各自按同一套算式建旋转
     * 矩阵（只用 {@code + - * /} 与 {@code sqrt}，不用 sin/cos）。</p>
     */
    public final double faceX;
    public final double faceY;
    public final double faceZ;
    /** true = 朝上是恒等旋转，采样时直接跳过旋转（保证旧行为的逐位不变） */
    private final boolean tilted;
    /** 把本地坐标转到世界的 3×3（行主序） */
    private final double m00, m01, m02, m10, m11, m12, m20, m21, m22;

    /** 最简：点云 + 默认阻尼/能量 */
    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ, float lifeSec)
    {
        this(id, castSeed, elementId, count, originX, originY, originZ,
                Shapes.POINT.id(), Shapes.POINT.defaults(), lifeSec,
                SpellConstants.DEFAULT_DRAG, SpellConstants.DEFAULT_ENERGY,
                SpellConstants.DEFAULT_ENERGY_DECAY, false, 0.0, 1.0, 0.0);
    }

    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ,
                      String shapeId, ShapeParams shapeParams,
                      float lifeSec, float drag, float energyInit, float energyDecay, boolean persistent)
    {
        this(id, castSeed, elementId, count, originX, originY, originZ, shapeId, shapeParams,
                lifeSec, drag, energyInit, energyDecay, persistent, 0.0, 1.0, 0.0);
    }

    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ,
                      String shapeId, ShapeParams shapeParams,
                      float lifeSec, float drag, float energyInit, float energyDecay, boolean persistent,
                      double faceX, double faceY, double faceZ)
    {
        this.id = id;
        this.castSeed = castSeed;
        this.elementId = elementId;
        this.count = Math.max(SpellConstants.MIN_CLOUD_PARTICLES,
                Math.min(SpellConstants.MAX_CLOUD_PARTICLES, count));
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.shapeId = shapeId == null ? Shapes.POINT.id() : shapeId;
        this.shapeParams = shapeParams == null ? ShapeParams.DEFAULT : shapeParams;
        this.shape = Shapes.byId(this.shapeId);
        this.chunkX = Math.floorDiv((int) Math.floor(originX), 16);
        this.chunkZ = Math.floorDiv((int) Math.floor(originZ), 16);
        // 寿命也夹一下：外部（命令 / 包 / 自检）可能传任意 float，非有限值和超大值都会让
        // maxSteps 溢出，云就永远不死了。
        this.lifeSec = (float) SpellConstants.clampLifeSec(lifeSec);
        this.persistent = persistent;
        long steps = (long) Math.ceil(this.lifeSec / SpellConstants.DT);
        this.maxSteps = persistent ? Integer.MAX_VALUE / 4
                : (int) Math.max(1L, Math.min(Integer.MAX_VALUE / 4L, steps));
        this.drag = Math.max(0.0F, drag);
        this.energyInit = energyInit;
        this.energyDecay = energyDecay;

        // 朝向归一化；退化（零向量 / 非有限）时退回竖直向上
        double fx = faceX;
        double fy = faceY;
        double fz = faceZ;
        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(len > 1.0E-9) || !Double.isFinite(len))
        {
            fx = 0.0;
            fy = 1.0;
            fz = 0.0;
            len = 1.0;
        }
        fx /= len;
        fy /= len;
        fz /= len;
        this.faceX = fx;
        this.faceY = fy;
        this.faceZ = fz;
        boolean identity = Math.abs(fx) < 1.0E-12 && Math.abs(fz) < 1.0E-12 && fy > 0.0;
        this.tilted = !identity;
        if (identity)
        {
            m00 = 1.0;
            m01 = 0.0;
            m02 = 0.0;
            m10 = 0.0;
            m11 = 1.0;
            m12 = 0.0;
            m20 = 0.0;
            m21 = 0.0;
            m22 = 1.0;
        }
        else
        {
            // 把本地 +Y 转到 (fx, fy, fz)：罗德里格斯公式，轴 = up × f，角 = acos(up·f)。
            // s = sqrt(1-c²) 而不是 sin()——跨 JVM 逐位一致，且不用三角函数。
            double kx = fz;
            double ky = 0.0;
            double kz = -fx;
            double kl = Math.sqrt(kx * kx + kz * kz);
            if (!(kl > 1.0E-9))
            {
                // 正好朝下：绕 X 轴转 180°
                m00 = 1.0;
                m01 = 0.0;
                m02 = 0.0;
                m10 = 0.0;
                m11 = -1.0;
                m12 = 0.0;
                m20 = 0.0;
                m21 = 0.0;
                m22 = -1.0;
            }
            else
            {
                kx /= kl;
                kz /= kl;
                double c = fy;
                double s = Math.sqrt(Math.max(0.0, 1.0 - c * c));
                double t = 1.0 - c;
                m00 = c + t * kx * kx;
                m01 = -s * kz;
                m02 = t * kx * kz;
                m10 = s * kz;
                m11 = c;
                m12 = -s * kx;
                m20 = t * kx * kz;
                m21 = s * kx;
                m22 = c + t * kz * kz;
            }
        }

        int n = this.count;
        x = new double[n];
        y = new double[n];
        z = new double[n];
        vx = new double[n];
        vy = new double[n];
        vz = new double[n];
        energy = new float[n];
        anchorDX = new double[n];
        anchorDY = new double[n];
        anchorDZ = new double[n];
        centerDX = new double[n];
        centerDY = new double[n];
        centerDZ = new double[n];
        Arrays.fill(energy, energyInit);
        shapePrep = new double[count * 2];
        shape.prepare(count, shapeParams, castSeed, shapePrep);

        initParticles();
    }

    /**
     * 脚本云：行为完全由 {@code script} 决定。
     *
     * <p>{@code spin/up/out/drag/hold/energy} 全部为 0——它们属于旧 {@link Laws}，
     * 不应悄悄掺进脚本云的物理里。</p>
     */
    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ,
                      float lifeSec, String shapeId, ShapeParams shapeParams,
                      SpellScript script, Map<String, Value> vars)
    {
        this(id, castSeed, elementId, count, originX, originY, originZ, lifeSec, shapeId, shapeParams,
                script, vars, 0.0, 1.0, 0.0);
    }

    /** 带朝向的脚本云（{@code emit { facing: … }}） */
    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ,
                      float lifeSec, String shapeId, ShapeParams shapeParams,
                      SpellScript script, Map<String, Value> vars,
                      double faceX, double faceY, double faceZ)
    {
        this(id, castSeed, elementId, count, originX, originY, originZ, shapeId, shapeParams,
                lifeSec, 0.0F, 100.0F, 0.0F, false, faceX, faceY, faceZ);
        this.script = script;
        this.scriptVars = vars == null ? Map.of() : new HashMap<>(vars);
    }

    /**
     * 确定性初始化：位置由 {@link SpellShape} 采样（形状 + 参数 + seed），
     * 速度是球面均匀的小扰动，让形状从第一帧就有细微不规则感。
     *
     * <p>采样结果是<b>形状本地坐标</b>：位置、回位锚点、旋转中心都过一遍
     * {@link #toWorld} 转到世界方向。朝上（默认）时 {@code tilted=false}，直接原样返回，
     * 保证旧行为的逐位不变。</p>
     */
    private void initParticles()
    {
        SpellShape shape = Shapes.byId(shapeId);
        double[] out = new double[3];
        double[] center = new double[3];
        double[] dir = new double[3];
        double[] w = new double[3];
        for (int i = 0; i < count; i++)
        {
            shape.sample(i, count, shapeParams, castSeed, out);
            shape.center(i, count, shapeParams, castSeed, center);
            toWorld(out[0], out[1], out[2], w);
            x[i] = originX + w[0];
            y[i] = originY + w[1];
            z[i] = originZ + w[2];
            // 回位锚点：形状的局部中心 X/Z + 这一粒自己的局地高度
            toWorld(center[0], out[1], center[2], w);
            anchorDX[i] = w[0];
            anchorDY[i] = w[1];
            anchorDZ[i] = w[2];
            // 旋转中心：形状的局部轴心
            toWorld(center[0], center[1], center[2], w);
            centerDX[i] = w[0];
            centerDY[i] = w[1];
            centerDZ[i] = w[2];

            SpellRandom.sphere(castSeed, i, 20, dir);
            double speed = 0.2 + 0.6 * SpellRandom.unit(castSeed, i, 21);
            vx[i] = dir[0] * speed;
            vy[i] = dir[1] * speed;
            vz[i] = dir[2] * speed;
        }
    }

    /** 形状本地偏移 → 世界偏移；朝上时原样返回（不多做一次乘法，保证逐位相同） */
    private void toWorld(double lx, double ly, double lz, double[] out)
    {
        if (!tilted)
        {
            out[0] = lx;
            out[1] = ly;
            out[2] = lz;
            return;
        }
        out[0] = m00 * lx + m01 * ly + m02 * lz;
        out[1] = m10 * lx + m11 * ly + m12 * lz;
        out[2] = m20 * lx + m21 * ly + m22 * lz;
    }

    /** 推进一步：常驻云静止；有脚本走脚本，否则走基础定律；步数自增 */
    /** 本步的调度形态：由 {@link #beginStep()} 决定，{@link #finishStep()} 消费 */
    private static final int KIND_NONE = 0;
    /** 常驻云：只涨年龄 */
    private static final int KIND_AGE_ONLY = 1;
    /** 解释器路径：beginStep 里已经整体跑完，只需收尾 */
    private static final int KIND_INTERPRETER = 2;
    /** 脚本快速路径：粒子区间可交给工作线程 */
    private static final int KIND_SCRIPT = 3;
    /** 通用定律：粒子区间可交给工作线程 */
    private static final int KIND_LAWS = 4;

    private int stepKind = KIND_NONE;
    /**
     * true = 强制走解释器（调试云用）。
     *
     * <p>正常施法永远编译执行：生成时就把寄存器机编好，一步都不会退回树遍历。
     * 这条口子是给 {@code /spell debug run} 和对照自检用的——它跑的是参考实现，
     * 慢，但可以拿来和编译结果逐位比对。</p>
     */
    private boolean interpreted;
    private SpellScript.Step pendingStep;

    /**
     * 阶段 1（**必须在调用线程**）：准备这一步。
     *
     * <p>标量段、外提量、自由变量搬运都在这里做完；解释器路径（没有快速路径）也在这里整体跑完。
     * 返回 {@code true} 表示这朵云还有 {@code [0, count)} 的粒子区间要推进，
     * 可以交给 {@link #stepRange(int, int)} —— 多个云的区间能拼成一条，一次并行调度。</p>
     */
    public boolean beginStep()
    {
        stepKind = KIND_NONE;
        pendingStep = null;
        // 伤害配额是"每步"的：一朵常在的生物云每步都要打人，终身配额会把它提前掐死
        hurtCallsPerStep = 0;
        if (broken)
        {
            return false;
        }
        if (persistent)
        {
            // 法阵 / 结界：位置不推进，只累计年龄（可见性快照与寿命判定用）
            stepKind = KIND_AGE_ONLY;
            return false;
        }
        if (script == null)
        {
            stepKind = KIND_LAWS;
            return count > 0;
        }
        if (interpreted)
        {
            // 调试云：解释器当参考实现，不进并行池
            try
            {
                script.tickInterpreted(this);
            }
            catch (RuntimeException e)
            {
                breakWith(e);
                return false;
            }
            stepKind = KIND_INTERPRETER;
            return false;
        }
        SpellScript.Step step = script.prepareStep(this);
        if (step == null)
        {
            try
            {
                script.tick(this);
            }
            catch (RuntimeException e)
            {
                breakWith(e);
                return false;
            }
            stepKind = KIND_INTERPRETER;
            return false;
        }
        pendingStep = step;
        stepKind = KIND_SCRIPT;
        if (!step.parallelSafe())
        {
            // 里面有 rand()：随机序列是按粒子顺序消耗的，切段就变了。
            // 整段在这里一次跑完，不进并行池（阶段 2 会跳过这朵云）。
            try
            {
                script.runStep(step, this, 0, count);
            }
            catch (RuntimeException e)
            {
                breakWith(e);
                return false;
            }
            return false;
        }
        return count > 0;
    }

    /**
     * 阶段 2（**可以并行**）：只推进 {@code [from, to)} 这一段粒子。
     *
     * <p>每颗粒子只碰自己的下标，所以切段与整段逐位相同；出错的云只标记自己 broken，
     * 不影响同一次调度里的其它云。</p>
     */
    public void stepRange(int from, int to)
    {
        if (to <= from || stepKind == KIND_NONE)
        {
            return;
        }
        try
        {
            if (stepKind == KIND_SCRIPT)
            {
                script.runStep(pendingStep, this, from, to);
            }
            else if (stepKind == KIND_LAWS)
            {
                Laws.step(this, from, to);
            }
        }
        catch (RuntimeException e)
        {
            breakWith(e);
        }
    }

    /**
     * 阶段 3（**必须在调用线程**）：写回云级变量、方块碰撞、累计年龄。
     *
     * <p>碰撞读世界（{@code getBlockState} 不是线程安全的），所以永远留在调用线程，
     * 在所有粒子区间 join 之后做。</p>
     */
    public void finishStep()
    {
        int kind = stepKind;
        stepKind = KIND_NONE;
        if (kind == KIND_NONE)
        {
            return;
        }
        if (kind == KIND_AGE_ONLY)
        {
            ageSteps++;
            return;
        }
        if (kind == KIND_SCRIPT)
        {
            try
            {
                script.completeStep(pendingStep, this);
            }
            catch (RuntimeException e)
            {
                breakWith(e);
                return;
            }
        }
        pendingStep = null;
        // 碰撞是「脚本/定律之后」的一次后处理：脚本永远不读世界，
        // 确定性由「两端拿到同一份方块状态」保证，而不是由脚本保证。
        if (!resolveCollisions())
        {
            return;
        }
        // 碰到生物：**只在服务端判定**（客户端两端的实体位置不可能一致），而且只在这份脚本
        // 有 on touch 时才去扫包围盒/查实体——没有钩子的法术一个字节都不该花。
        if (!resolveTouches())
        {
            return;
        }
        ageSteps++;
    }

    /**
     * 本步碰到的生物 → 每个实体触发一次 {@code on touch}。
     *
     * @return false = 脚本出错（云已标记 broken）
     */
    private boolean resolveTouches()
    {
        if (world == null || script == null || !script.hasTouch() || broken)
        {
            return true;
        }
        List<SpellWorld.Touch> touched;
        try
        {
            touched = world.touches(this);
        }
        catch (RuntimeException e)
        {
            breakWith(e);
            return false;
        }
        if (touched == null || touched.isEmpty())
        {
            return true;
        }
        int n = Math.min(touched.size(), MAX_TOUCHES_PER_STEP);
        for (int i = 0; i < n; i++)
        {
            if (!runTouchHook(touched.get(i)))
            {
                return false;
            }
            if (ended)
            {
                // 脚本自己喊停了：后面的实体不再触发
                break;
            }
        }
        return true;
    }

    /**
     * 碰撞 + 撞击事件；返回 false = 这一步出错（云已标记 broken）。
     *
     * <p>事件在碰撞之后<b>立即</b>触发：此刻粒子已经贴在表面上、速度已经按策略反弹，
     * 脚本看到的就是"刚撞上去"的那一帧，不需要自己推算撞击点。</p>
     */
    private boolean resolveCollisions()
    {
        if (collider == null)
        {
            clearHits();
            return true;
        }
        try
        {
            collider.resolve(this);
        }
        catch (RuntimeException e)
        {
            breakWith(e);
            return false;
        }
        if (hitCount > 0 && script != null && script.hasHit())
        {
            try
            {
                script.hit(this, hitFacts());
            }
            catch (RuntimeException e)
            {
                breakWith(e);
                return false;
            }
        }
        return true;
    }

    /** 这一步的撞击事实；{@code null} = 没有粒子撞上（或元素穿方块） */
    public HitFacts hitFacts()
    {
        if (hitCount <= 0)
        {
            return null;
        }
        int n = hitCount;
        double ax = hitSumNX;
        double ay = hitSumNY;
        double az = hitSumNZ;
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (!(len > 1.0E-9))
        {
            // 同时撞到相对两面（薄墙两侧）会互相抵消：退回"撞前速度的反方向"
            ax = -hitSumVX;
            ay = -hitSumVY;
            az = -hitSumVZ;
            len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (!(len > 1.0E-9))
            {
                ax = 0.0;
                ay = 1.0;
                az = 0.0;
                len = 1.0;
            }
        }
        return new HitFacts(n, hitSumX / n, hitSumY / n, hitSumZ / n,
                hitSumVX / n, hitSumVY / n, hitSumVZ / n,
                ax / len, ay / len, az / len,
                hitBlock == null ? "" : hitBlock);
    }

    public int hitCount()
    {
        return hitCount;
    }

    /**
     * 收下这一步的撞击累加值（{@link ParticleCollider} 调用；和而不是平均，
     * 免得每一步多做一次除法）。
     */
    public void recordHits(int count, double sumX, double sumY, double sumZ,
                           double sumVX, double sumVY, double sumVZ,
                           double sumNX, double sumNY, double sumNZ, String block)
    {
        this.hitCount = count;
        this.hitSumX = sumX;
        this.hitSumY = sumY;
        this.hitSumZ = sumZ;
        this.hitSumVX = sumVX;
        this.hitSumVY = sumVY;
        this.hitSumVZ = sumVZ;
        this.hitSumNX = sumNX;
        this.hitSumNY = sumNY;
        this.hitSumNZ = sumNZ;
        this.hitBlock = block;
    }

    private void clearHits()
    {
        hitCount = 0;
    }

    /** 单朵云跑一步的便捷入口（客户端镜像、自检用）；服务端走批量调度 */
    public void step()
    {
        if (beginStep())
        {
            SpellParallel.forEachRange(count, this::stepRange);
        }
        finishStep();
    }

    private void breakWith(RuntimeException e)
    {
        stepKind = KIND_NONE;
        broken = true;
        breakReason = describe(e);
    }

    /**
     * 把脚本异常转成日志里能用的文本。<b>带上行列</b>——只写「除以零」，作者没法定位，
     * 而运行期错误原本连行列都没有（寄存器机以前固定 {@code (1, 1)}）。
     */
    private static String describe(RuntimeException e)
    {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof ScriptException se)
        {
            return message + "（第 " + se.line + " 行第 " + se.col + " 列）";
        }
        return message;
    }

    /** true = 允许这朵云在 tick 里生成子云（只该由服务端打开） */
    public void setEmitsEnabled(boolean enabled)
    {
        this.emitsEnabled = enabled;
    }

    public boolean emitsEnabled()
    {
        return emitsEnabled;
    }

    /**
     * 收下这一步的 emit 请求。
     *
     * <p>客户端也会调（脚本两端跑同一份，求值保持 {@code rand()} 序列一致），
     * 但 {@code emitsEnabled=false} 时直接丢掉，不占配额。</p>
     */
    public void offerEmits(List<EmitSpec> specs)
    {
        if (!emitsEnabled || specs == null || specs.isEmpty())
        {
            return;
        }
        for (int i = 0; i < specs.size(); i++)
        {
            if (emittedTotal >= MAX_EMITS_TOTAL)
            {
                return;
            }
            pendingEmits.add(specs.get(i));
            emittedTotal++;
        }
    }

    /** 取走这一步攒下的 emit（并把队列清空）；没有就返回空列表 */
    public List<EmitSpec> drainEmits()
    {
        if (pendingEmits.isEmpty())
        {
            return List.of();
        }
        List<EmitSpec> out = new ArrayList<>(pendingEmits);
        pendingEmits.clear();
        return out;
    }

    /** true = 强制走解释器（调试云）；正常施法不要开 */
    public void setInterpreted(boolean interpreted)
    {
        this.interpreted = interpreted;
    }

    public boolean interpreted()
    {
        return interpreted;
    }

    /** 挂上/摘掉碰撞（服务端与客户端各自创建）；{@code null} = 不碰撞 */
    public void setCollider(ParticleCollider collider)
    {
        this.collider = collider;
    }

    public ParticleCollider collider()
    {
        return collider;
    }

    /**
     * 权威端（服务端）标记。
     *
     * <p>只影响 {@code on expire}：云的「死」在服务端是权威判定，客户端只是等移除包。
     * 两端都跑会让终局那一步多做一遍无用功（客户端本来也不生成子云），
     * 而 {@code on hit} 两端都要跑——它改的是粒子状态，不跑就两端画面对不上。</p>
     */
    public void setServerSide(boolean serverSide)
    {
        this.serverSide = serverSide;
    }

    public boolean serverSide()
    {
        return serverSide;
    }

    /** 挂上世界出口（服务端创建云时调用）；{@code null} = 打不出伤害、也不判定碰到生物 */
    public void setWorld(SpellWorld world)
    {
        this.world = world;
    }

    public SpellWorld world()
    {
        return world;
    }

    /** 施法者（伤害/接触时跳过自己） */
    public void setCasterId(UUID casterId)
    {
        this.casterId = casterId;
    }

    public UUID casterId()
    {
        return casterId;
    }

    /**
     * 打一次范围伤害（脚本 {@code hurt(radius, damage, [at])} 的落点）。
     *
     * @return false = 没有出口（客户端）或本步配额用完，这次调用被丢掉
     */
    public boolean hurt(double x, double y, double z, double radius, double damage)
    {
        if (!takeHurtQuota())
        {
            return false;
        }
        world.hurt(x, y, z, radius, damage);
        return true;
    }

    /**
     * 只打一个实体（脚本 {@code hurt(0, damage)}，半径 0 的语义）。
     *
     * @return false = 没有出口或本步配额用完
     */
    public boolean hurtEntity(long entityId, double damage)
    {
        if (!takeHurtQuota())
        {
            return false;
        }
        world.hurtEntity(entityId, damage);
        return true;
    }

    /** 本步的伤害配额；用完时静默丢弃（脚本读不到结果，也就无从刷） */
    private boolean takeHurtQuota()
    {
        if (world == null || hurtCallsPerStep >= MAX_HURT_CALLS_PER_STEP)
        {
            return false;
        }
        hurtCallsPerStep++;
        hurtCalls++;
        return true;
    }

    /** 本步已经打了几次（诊断用） */
    public int hurtCallsThisStep()
    {
        return hurtCallsPerStep;
    }

    /** 已经打过的总次数（终身，诊断用） */
    public int hurtCalls()
    {
        return hurtCalls;
    }

    /**
     * 脚本要求立刻结束这朵云（{@code end()}）。
     *
     * <p>已结束的云不再跑 {@code on expire}——它不是"寿终"，是作者主动喊停。</p>
     */
    public void kill()
    {
        this.ended = true;
    }

    public boolean ended()
    {
        return ended;
    }

    /** 已经触发过多少次 {@code on touch}（诊断用） */
    public int touchCalls()
    {
        return touchCalls;
    }

    /**
     * 跑一次 {@code on expire}（由管理器在移除这朵云之前调用）。
     *
     * <p>{@code end()} 结束的云不跑：那是作者主动喊停，不是寿终。</p>
     *
     * @return false = 脚本出错（云已标记 broken）
     */
    public boolean runExpireHook()
    {
        if (script == null || !script.hasExpire() || broken || ended)
        {
            return true;
        }
        try
        {
            script.expire(this);
            return true;
        }
        catch (RuntimeException e)
        {
            breakWith(e);
            return false;
        }
    }

    /**
     * 跑一次 {@code on touch}（本步碰到的第 {@code fact} 个实体）。
     *
     * <p>只有服务端会走到这里（客户端没有 {@link SpellWorld}，也就没有实体判定）。</p>
     *
     * @return false = 脚本出错（云已标记 broken）
     */
    public boolean runTouchHook(SpellWorld.Touch fact)
    {
        if (script == null || !script.hasTouch() || broken || world == null)
        {
            return true;
        }
        touchCalls++;
        try
        {
            script.touch(this, fact);
            return true;
        }
        catch (RuntimeException e)
        {
            breakWith(e);
            return false;
        }
    }

    public boolean isDead()
    {
        return broken || ended || (!persistent && ageSteps >= maxSteps);
    }

    /**
     * 用快照恢复状态（玩家进入可见范围时，服务端发送当前状态）。
     *
     * @param steps 已推进的步数
     * @param data  长度 = {@code count * 7}，依次为 x,y,z,vx,vy,vz,energy
     */
    public void restore(int steps, double[] data)
    {
        this.ageSteps = steps;
        int idx = 0;
        for (int i = 0; i < count; i++)
        {
            x[i] = data[idx++];
            y[i] = data[idx++];
            z[i] = data[idx++];
            vx[i] = data[idx++];
            vy[i] = data[idx++];
            vz[i] = data[idx++];
            energy[i] = (float) data[idx++];
        }
    }

    public int ageSteps()
    {
        return ageSteps;
    }

    /** 原点所在区块 X（区块生命周期判定用） */
    public int chunkX()
    {
        return chunkX;
    }

    /** 原点所在区块 Z */
    public int chunkZ()
    {
        return chunkZ;
    }

    public void setAgeSteps(int steps)
    {
        this.ageSteps = steps;
    }

    // ------------------------------------------------------------ 只读访问
    // 客户端渲染层在别的包里，只能经这些访问器读；Laws 同包直接用数组。

    public double x(int i)
    {
        return x[i];
    }

    public double y(int i)
    {
        return y[i];
    }

    public double z(int i)
    {
        return z[i];
    }

    public float energy(int i)
    {
        return energy[i];
    }

    // ------------------------------------------------ 脚本访问（逐粒子，读写）

    public double velX(int i)
    {
        return vx[i];
    }

    public double velY(int i)
    {
        return vy[i];
    }

    public double velZ(int i)
    {
        return vz[i];
    }

    public void setPos(int i, double px, double py, double pz)
    {
        x[i] = px;
        y[i] = py;
        z[i] = pz;
    }

    public void setVel(int i, double vX, double vY, double vZ)
    {
        vx[i] = vX;
        vy[i] = vY;
        vz[i] = vZ;
    }

    public void setEnergy(int i, double value)
    {
        energy[i] = (float) value;
    }

    // ------------------------------------------------------------ 原生内核（零拷贝）
    //
    // 原生内核直接读写这几条 SoA 数组的 [from, to) 区间。**不要换引用**，也不要缓存
    // 到数组之外再改：它是一个内存里的布局，不是副本。

    public double[] rawX()
    {
        return x;
    }

    public double[] rawY()
    {
        return y;
    }

    public double[] rawZ()
    {
        return z;
    }

    public double[] rawVx()
    {
        return vx;
    }

    public double[] rawVy()
    {
        return vy;
    }

    public double[] rawVz()
    {
        return vz;
    }

    public float[] rawEnergy()
    {
        return energy;
    }

    public double[] rawAnchorDx()
    {
        return anchorDX;
    }

    public double[] rawAnchorDy()
    {
        return anchorDY;
    }

    public double[] rawAnchorDz()
    {
        return anchorDZ;
    }

    /** 形状锚点（世界坐标）：竖直回位弹簧的"家" */
    public double anchorX(int i)
    {
        return originX + anchorDX[i];
    }

    public double anchorYWorld(int i)
    {
        return originY + anchorDY[i];
    }

    public double anchorZ(int i)
    {
        return originZ + anchorDZ[i];
    }

    /** 当前时刻的局部旋转中心（世界坐标），轴摆动的形状用 */
    public double[] axisAt(int i, double timeSeconds)
    {
        return axisAt(i, timeSeconds, axisScratch);
    }

    /** 同上，但写到调用方给的数组里——并行时段与段之间不能共用同一块 scratch */
    public double[] axisAt(int i, double timeSeconds, double[] out)
    {
        shape.centerAt(i, count, shapeParams, castSeed, timeSeconds, shapePrep, out);
        if (tilted)
        {
            double lx = out[0];
            double ly = out[1];
            double lz = out[2];
            out[0] = m00 * lx + m01 * ly + m02 * lz;
            out[1] = m10 * lx + m11 * ly + m12 * lz;
            out[2] = m20 * lx + m21 * ly + m22 * lz;
        }
        out[0] += originX;
        out[1] += originY;
        out[2] += originZ;
        return out;
    }

    /** 当前时刻的目标半径；≤0 表示形状不约束半径 */
    public double targetRadius(int i, double timeSeconds)
    {
        return shape.targetRadiusAt(i, count, shapeParams, castSeed, timeSeconds, shapePrep);
    }

    /** 每粒稳定的 [0,1) 随机数（由 castSeed + 下标派生，两端一致） */
    public double random01(int i)
    {
        return SpellRandom.unit(castSeed, i, 91);
    }

    // ---------------------------------------------------------------- 哈希

    /**
     * 当前粒子团的质心（世界坐标）。
     *
     * <p>用来回答"这朵云现在到底在哪"：{@code originX/Y/Z} 是<b>出生点</b>，一朵飞出去的云
     * 可以在离出生点很远的地方消散。{@code on expire} 里 {@code emit} 不写 {@code at}
     * 时就用它当默认位置。</p>
     */
    public double[] centroid()
    {
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (int i = 0; i < count; i++)
        {
            sx += x[i];
            sy += y[i];
            sz += z[i];
        }
        if (count == 0)
        {
            return new double[]{ originX, originY, originZ };
        }
        return new double[]{ sx / count, sy / count, sz / count };
    }

    /** 位置校验和（量化到 1/4096 格），用于服务端/客户端一致性比对 */
    public long stateHash()
    {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < count; i++)
        {
            h = SpellRandom.mix(h ^ quantize(x[i]));
            h = SpellRandom.mix(h ^ quantize(y[i]));
            h = SpellRandom.mix(h ^ quantize(z[i]));
        }
        return h;
    }

    private static long quantize(double v)
    {
        return Math.round(v * 4096.0);
    }

    /**
     * 导出状态快照，供玩家进入可见范围时补发。
     *
     * <p>用 {@code double} 而不是 {@code float}：float 只有约 7 位有效数字，
     * 坐标在 900 附近时量化误差会跨过 hash 的 1/4096 格，客户端恢复后与服务器
     * 状态不一致（日志里表现为同一朵静止云 hash 不同）。</p>
     *
     * @return 长度 = {@code count * 7}，依次为 x,y,z,vx,vy,vz,energy
     */
    public double[] snapshot()
    {
        double[] data = new double[count * 7];
        int idx = 0;
        for (int i = 0; i < count; i++)
        {
            data[idx++] = x[i];
            data[idx++] = y[i];
            data[idx++] = z[i];
            data[idx++] = vx[i];
            data[idx++] = vy[i];
            data[idx++] = vz[i];
            data[idx++] = energy[i];
        }
        return data;
    }
}
