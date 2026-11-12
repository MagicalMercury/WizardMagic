package com.wizardmagic.core.spell;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.SpellShape;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.core.spell.script.SpellScript;
import com.wizardmagic.core.spell.script.Value;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
    /** 粒子数 8..4096 */
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

    private int ageSteps;

    // ---- SoA ----
    final double[] x;
    final double[] y;
    final double[] z;
    final double[] vx;
    final double[] vy;
    final double[] vz;
    final float[] energy;
    /** 每粒在形状里的局地高度（世界锚点 = originY + anchorY[i]），回位弹簧用 */
    final double[] anchorY;
    /** 每粒的局部旋转中心（X/Z）：切向与径向力绕它旋转（轴摆动的形状用） */
    final double[] centerX;
    final double[] centerZ;
    /** {@link #axisAt} 的复用缓冲（云只在单线程里推进） */
    private final double[] axisScratch = new double[3];
    /** 形状的每粒静态系数（`prepare` 生成一次，避免每步重算 pow） */
    private final double[] shapePrep;

    /** 最简：点云 + 默认阻尼/能量 */
    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ, float lifeSec)
    {
        this(id, castSeed, elementId, count, originX, originY, originZ,
                Shapes.POINT.id(), Shapes.POINT.defaults(), lifeSec,
                SpellConstants.DEFAULT_DRAG, SpellConstants.DEFAULT_ENERGY,
                SpellConstants.DEFAULT_ENERGY_DECAY, false);
    }

    public SpellCloud(int id, long castSeed, int elementId, int count,
                      double originX, double originY, double originZ,
                      String shapeId, ShapeParams shapeParams,
                      float lifeSec, float drag, float energyInit, float energyDecay, boolean persistent)
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
        this.lifeSec = Math.max(0.1F, lifeSec);
        this.persistent = persistent;
        this.maxSteps = persistent ? Integer.MAX_VALUE / 4
                : Math.max(1, (int) Math.ceil(this.lifeSec / SpellConstants.DT));
        this.drag = Math.max(0.0F, drag);
        this.energyInit = energyInit;
        this.energyDecay = energyDecay;

        int n = this.count;
        x = new double[n];
        y = new double[n];
        z = new double[n];
        vx = new double[n];
        vy = new double[n];
        vz = new double[n];
        energy = new float[n];
        anchorY = new double[n];
        centerX = new double[n];
        centerZ = new double[n];
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
        this(id, castSeed, elementId, count, originX, originY, originZ, shapeId, shapeParams,
                lifeSec, 0.0F, 100.0F, 0.0F, false);
        this.script = script;
        this.scriptVars = vars == null ? Map.of() : new HashMap<>(vars);
    }

    /**
     * 确定性初始化：位置由 {@link SpellShape} 采样（形状 + 参数 + seed），
     * 速度是球面均匀的小扰动，让形状从第一帧就有细微不规则感。
     */
    private void initParticles()
    {
        SpellShape shape = Shapes.byId(shapeId);
        double[] out = new double[3];
        double[] center = new double[3];
        double[] dir = new double[3];
        for (int i = 0; i < count; i++)
        {
            shape.sample(i, count, shapeParams, castSeed, out);
            shape.center(i, count, shapeParams, castSeed, center);
            x[i] = originX + out[0];
            y[i] = originY + out[1];
            z[i] = originZ + out[2];
            anchorY[i] = out[1];
            centerX[i] = center[0];
            centerZ[i] = center[2];

            SpellRandom.sphere(castSeed, i, 20, dir);
            double speed = 0.2 + 0.6 * SpellRandom.unit(castSeed, i, 21);
            vx[i] = dir[0] * speed;
            vy[i] = dir[1] * speed;
            vz[i] = dir[2] * speed;
        }
    }

    /** 推进一步：常驻云静止；有脚本走脚本，否则走基础定律；步数自增 */
    public void step()
    {
        if (broken)
        {
            return;
        }
        if (persistent)
        {
            // 法阵 / 结界：位置不推进，只累计年龄（可见性快照与寿命判定用）
            ageSteps++;
            return;
        }
        if (script != null)
        {
            try
            {
                script.tick(this);
            }
            catch (RuntimeException e)
            {
                broken = true;
                breakReason = e.getMessage() == null ? e.toString() : e.getMessage();
                return;
            }
        }
        else
        {
            Laws.step(this);
        }
        ageSteps++;
    }

    public boolean isDead()
    {
        return broken || (!persistent && ageSteps >= maxSteps);
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

    /** 形状锚点（世界坐标）：竖直回位弹簧的"家" */
    public double anchorX(int i)
    {
        return originX + centerX[i];
    }

    public double anchorYWorld(int i)
    {
        return originY + anchorY[i];
    }

    public double anchorZ(int i)
    {
        return originZ + centerZ[i];
    }

    /** 当前时刻的局部旋转中心（世界坐标），轴摆动的形状用 */
    public double[] axisAt(int i, double timeSeconds)
    {
        shape.centerAt(i, count, shapeParams, castSeed, timeSeconds, shapePrep, axisScratch);
        axisScratch[0] += originX;
        axisScratch[1] += originY;
        axisScratch[2] += originZ;
        return axisScratch;
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
