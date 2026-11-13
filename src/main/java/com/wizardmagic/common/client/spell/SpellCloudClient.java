package com.wizardmagic.common.client.spell;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.common.client.fx.SparkBatch;
import com.wizardmagic.common.client.fx.SpellInstanceSource;
import com.wizardmagic.common.network.SpellCloudRemovePayload;
import com.wizardmagic.common.network.SpellCloudSnapshotPayload;
import com.wizardmagic.common.network.SpellCloudSpawnPayload;
import com.wizardmagic.common.spell.LevelSolidOracle;
import com.wizardmagic.core.spell.SpellBatch;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellElements;
import com.wizardmagic.core.spell.SpellRandom;
import com.wizardmagic.core.spell.script.ScriptException;
import com.wizardmagic.core.spell.script.SpellScript;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端的法术云镜像 + 渲染实例源。
 *
 * <p>服务端只发「生成 / 销毁」两个包；客户端用与服端完全相同的
 * {@link SpellCloud} / {@link com.wizardmagic.core.spell.Laws} 复算，
 * 因此没有逐 tick 同步，且同一 seed 的轨迹逐位一致。</p>
 *
 * <p>插值：物理 10 Hz（每 2 tick 一步），实例表每 tick 写一次快照
 * （{@code iPrev}/{@code iPos} 取两步之间的 0.0/1.0 与 0.5/1.0），
 * 着色器再按 {@code uPartial} 做 tick 内插值，运动连续。</p>
 */
public final class SpellCloudClient implements SpellInstanceSource
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WizardMagic.MODID + "-spell");

    public static final SpellCloudClient INSTANCE = new SpellCloudClient();

    static
    {
        SparkBatch.get().setSpellSource(INSTANCE);
    }

    /** id → 客户端镜像 */
    private final Map<Integer, ClientCloud> clouds = new HashMap<>();
    /** id → {steps, hash, gameTime}，云已消失但服务端销毁包可能后到，用于比对 */
    private final Map<Integer, long[]> tombstones = new HashMap<>();
    /** 每步批量推进用的缓冲（客户端主线程串行进入） */
    private static final List<SpellCloud> CLIENT_BATCH = new ArrayList<>();

    private Level lastLevel;
    private boolean warnedFallbackColor;
    /** 慢步日志节流（毫秒时间戳） */
    private long lastSlowLog;

    /**
     * 诊断开关：只有 {@code -Dwizardmagic.script.debug=1} 才打。
     *
     * <p>正常游玩不该每秒刷一行相位/时钟诊断；调「画面抽一下」这类问题时再打开，
     * 一次就能看出是时钟被撬、还是步进/相位错格。</p>
     */
    private static final boolean DIAG = System.getProperty("wizardmagic.script.debug") != null;

    /**
     * 诊断（不参与任何逻辑）：客户端时钟与渲染相位。
     *
     * <p>每秒汇总一行：这一秒里客户端 tick 数、步进数、相邻步进的间隔、以及 {@code fill}
     * 实际采样到的相位变化序列。用来判定「步进/相位是否真的每 tick 前进一格」。</p>
     */
    private long diagNanos;
    private int diagTicks;
    private int diagSteps;
    private long diagLastStepNanos;
    private double diagGapMin = Double.MAX_VALUE;
    private double diagGapMax;
    private int diagLastPhase = -1;
    private final StringBuilder diagPhases = new StringBuilder();

    /** 诊断：上一次看到的 gameTime 与跳变统计（只记日志，不参与逻辑） */
    private long lastGameTime = Long.MIN_VALUE;
    private long lastClockLog;
    private int clockJumps;
    private long clockDrift;

    private SpellCloudClient()
    {
    }

    // ------------------------------------------------------------------ 网络

    public void onSpawn(SpellCloudSpawnPayload p)
    {
        if (p.elementId < 0 || p.elementId >= SpellElements.count())
        {
            LOGGER.warn("[wizardmagic] spell spawn rejected: 未知元素下标 {}", p.elementId);
            return;
        }
        int count = Math.max(SpellConstants.MIN_CLOUD_PARTICLES,
                Math.min(SpellConstants.MAX_CLOUD_PARTICLES, p.count));
        SpellScript script = compileScript(p.scriptId, p.scriptSource);
        if (!p.scriptId.isEmpty() && script == null)
        {
            return;
        }
        if (script != null)
        {
            // 客户端镜像同样在生成时编译，保证第一步就是编译执行（不在飞行途中换后端）
            script.prepareFastPath(p.scriptVars);
        }
        SpellCloud cloud = script == null
                ? new SpellCloud(p.id, p.seed, p.elementId, count,
                        p.originX, p.originY, p.originZ, p.shapeId, p.shapeParams,
                        p.lifeSec, p.drag, p.energyInit, p.energyDecay, p.persistent,
                        p.faceX, p.faceY, p.faceZ)
                : new SpellCloud(p.id, p.seed, p.elementId, count,
                        p.originX, p.originY, p.originZ, p.lifeSec, p.shapeId, p.shapeParams,
                        script, p.scriptVars, p.faceX, p.faceY, p.faceZ);
        // 必须在 catchUp 之前：快进那几步也要碰撞，否则和服务端状态对不上
        cloud.setCollider(LevelSolidOracle.collideFor(Minecraft.getInstance().level, p.elementId));
        LOGGER.info("[wizardmagic] spell spawn path cloud={} scriptId='{}' srcLen={} nodes={} tickStmts={}",
                p.id, p.scriptId, p.scriptSource.length(),
                script == null ? -1 : script.nodes(),
                script == null ? -1 : script.program.tick().size());
        long initHash = cloud.stateHash();
        int caught = catchUp(cloud, p.spawnGameTime);
        if (initHash != p.initHash)
        {
            LOGGER.warn(String.format(Locale.ROOT,
                    "[wizardmagic] spell DESYNC-INIT cloud=%d client=%016x server=%016x seed=%d count=%d steps=%d",
                    p.id, initHash, p.initHash, p.seed, count, cloud.ageSteps()));
        }
        else
        {
            LOGGER.info(String.format(Locale.ROOT,
                    "[wizardmagic] spell init cloud=%d hash=%016x compare=OK steps=%d catchup=%d",
                    p.id, initHash, cloud.ageSteps(), caught));
        }
        if (!cloud.isDead())
        {
            clouds.put(p.id, new ClientCloud(cloud));
        }
        if (!warnedFallbackColor && !SpellElements.hasOwnColor(p.elementId))
        {
            warnedFallbackColor = true;
            LOGGER.info("[wizardmagic] spell: 元素 {} 尚无配色，使用临时回退色", SpellElements.id(p.elementId));
        }
        SparkBatch.get().markDirty();
    }

    /** 生成包里的服务端 gameTime → 已经过去的物理步数，快进补齐（上限 10 步） */
    private static final int MAX_CATCHUP_STEPS = 10;

    /**
     * 服务端在「客户端当前时刻」已经走了几步（偶数 gameTime 为步进 tick）。
     *
     * <p>生成发生在 tick {@code T} 途中，{@link #expectedAge} 要算的是 {@code [T, now]} 里的偶数 tick 数：</p>
     * <ul>
     *   <li>{@code T} 偶：{@code now=T} 那一 tick 末尾就会走一步，{@code age = d/2 + 1}</li>
     *   <li>{@code T} 奇：第一步在 {@code T+1}，{@code age = (d+1)/2}</li>
     * </ul>
     * <p>少算这一项，客户端就会永远落后生成 tick 的那一步。</p>
     */
    private static long expectedAge(long spawnGameTime, long now)
    {
        long d = Math.max(0L, now - spawnGameTime);
        return (spawnGameTime % 2L == 0L) ? (d / SpellConstants.STEP_TICKS + 1L)
                : ((d + 1L) / SpellConstants.STEP_TICKS);
    }

    private int catchUp(SpellCloud cloud, long spawnGameTime)
    {
        Level level = Minecraft.getInstance().level;
        if (level == null || spawnGameTime <= 0)
        {
            return 0;
        }
        long age = expectedAge(spawnGameTime, level.getGameTime());
        int target = (int) Math.min(MAX_CATCHUP_STEPS, age);
        int done = 0;
        for (; done < target && !cloud.isDead(); done++)
        {
            cloud.step();
        }
        return done;
    }

    /** 生成包带源码；同一份源码只编一次，反复施法不必每次重新解析 */
    private static final Map<String, SpellScript> COMPILED = new HashMap<>();
    private static final int MAX_COMPILED = 64;

    /** 生成包带源码；P1 客户端自己编译同一份脚本，编译失败 = 这朵云不渲染。 */
    private static SpellScript compileScript(String id, String source)
    {
        if (id == null || id.isEmpty() || source == null || source.isEmpty())
        {
            return null;
        }
        SpellScript hit = COMPILED.get(source);
        if (hit != null)
        {
            return hit;
        }
        try
        {
            SpellScript script = SpellScript.compile(id, source);
            if (COMPILED.size() >= MAX_COMPILED)
            {
                COMPILED.clear();
            }
            COMPILED.put(source, script);
            return script;
        }
        catch (ScriptException e)
        {
            LOGGER.warn("[wizardmagic] spell script {} 编译失败 {}", id, e.display());
            return null;
        }
    }

    /**
     * 玩家进入可见范围时收到进行中的云：直接用服务端快照覆盖本地状态，
     * 之后继续用六定律复算（不需要从 0 步快进）。
     */
    public void onSnapshot(SpellCloudSnapshotPayload p)
    {
        if (p.elementId < 0 || p.elementId >= SpellElements.count())
        {
            LOGGER.warn("[wizardmagic] spell snapshot rejected: 未知元素下标 {}", p.elementId);
            return;
        }
        int count = Math.max(SpellConstants.MIN_CLOUD_PARTICLES,
                Math.min(SpellConstants.MAX_CLOUD_PARTICLES, p.count));
        SpellScript script = compileScript(p.scriptId, p.scriptSource);
        if (!p.scriptId.isEmpty() && script == null)
        {
            return;
        }
        if (script != null)
        {
            // 客户端镜像同样在生成时编译，保证第一步就是编译执行（不在飞行途中换后端）
            script.prepareFastPath(p.scriptVars);
        }
        SpellCloud cloud = script == null
                ? new SpellCloud(p.id, p.seed, p.elementId, count,
                        p.originX, p.originY, p.originZ, p.shapeId, p.shapeParams,
                        p.lifeSec, p.drag, p.energyInit, p.energyDecay, p.persistent,
                        p.faceX, p.faceY, p.faceZ)
                : new SpellCloud(p.id, p.seed, p.elementId, count,
                        p.originX, p.originY, p.originZ, p.lifeSec, p.shapeId, p.shapeParams,
                        script, p.scriptVars, p.faceX, p.faceY, p.faceZ);
        cloud.setCollider(LevelSolidOracle.collideFor(Minecraft.getInstance().level, p.elementId));
        if (p.state.length == count * 7)
        {
            cloud.restore(p.ageSteps, p.state);
        }
        else
        {
            LOGGER.warn("[wizardmagic] spell snapshot state length mismatch cloud={} expect={} actual={}",
                    p.id, count * 7, p.state.length);
        }
        clouds.put(p.id, new ClientCloud(cloud));
        LOGGER.info(String.format(Locale.ROOT,
                "[wizardmagic] spell snapshot cloud=%d steps=%d particles=%d persistent=%b",
                p.id, p.ageSteps, count, p.persistent));
        SparkBatch.get().markDirty();
    }

    public void onRemove(SpellCloudRemovePayload p)
    {
        ClientCloud local = clouds.remove(p.id);
        int steps = -1;
        int maxSteps = -1;
        long hash = 0L;
        boolean persistent = false;
        if (local != null)
        {
            steps = local.cloud.ageSteps();
            maxSteps = local.cloud.maxSteps;
            persistent = local.cloud.persistent;
            hash = local.cloud.stateHash();
        }
        else
        {
            long[] tomb = tombstones.remove(p.id);
            if (tomb != null)
            {
                steps = (int) tomb[0];
                hash = tomb[1];
            }
        }
        if (steps >= 0)
        {
            // 一次性云跑满寿命 = 正常结束；其余都是可见性退出（走远 / 区块卸载）
            boolean expired = !persistent && maxSteps > 0 && steps >= maxSteps;
            String reason = expired ? "final" : "exit";
            // 客户端收到生成包才开始推进，且不补步；步数不同时 hash 必然不同，
            // 那是延迟不是状态分歧。只有步数相同才能判定一致性。
            String verdict;
            boolean warn;
            if (steps == p.steps)
            {
                verdict = hash == p.hash ? "OK" : "MISMATCH";
                warn = hash != p.hash;
            }
            else if (hash == p.hash)
            {
                verdict = "STEPS_DIFF_HASH_OK";
                warn = false;
            }
            else
            {
                int lag = p.steps - steps; // >0 = 客户端落后
                verdict = "LAG(" + lag + ")";
                warn = lag > 5;
            }
            String line = String.format(Locale.ROOT,
                    "[wizardmagic] spell %s cloud=%d clientSteps=%d clientHash=%016x serverSteps=%d serverHash=%016x compare=%s",
                    reason, p.id, steps, hash, p.steps, p.hash, verdict);
            if (warn)
            {
                LOGGER.warn(line);
            }
            else
            {
                LOGGER.info(line);
            }
        }
        SparkBatch.get().markDirty();
    }

    public void clear()
    {
        clouds.clear();
        tombstones.clear();
        lastLevel = null;
        SparkBatch.get().markDirty();
    }

    /** 客户端区块卸载：丢掉原点在该区块的镜像云 */
    public void onChunkUnload(int chunkX, int chunkZ)
    {
        int before = clouds.size();
        boolean removed = clouds.values().removeIf(c ->
                c.cloud.chunkX() == chunkX && c.cloud.chunkZ() == chunkZ);
        if (removed)
        {
            LOGGER.info("[wizardmagic] spell unload (chunk) chunk=({}, {}) removed={} clouds={}",
                    chunkX, chunkZ, before - clouds.size(), clouds.size());
            SparkBatch.get().markDirty();
        }
    }

    // ---------------------------------------------------------------- 每 tick

    public void tick()
    {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
        {
            if (!clouds.isEmpty())
            {
                clear();
            }
            return;
        }
        // 单人暂停时服务端停止推进；客户端也不能继续攒步，否则恢复后会跑在服务端前面
        if (mc.isPaused())
        {
            return;
        }
        if (level != lastLevel)
        {
            clouds.clear();
            tombstones.clear();
            lastLevel = level;
            lastGameTime = Long.MIN_VALUE;
        }

        // 诊断（默认关）：客户端 gameTime 是否连续。正常情况下每个 tick 只 +1；
        // 出现其它增量说明时间包把整值覆盖过来了——那时步进/插值的奇偶相位会跟着抖。
        if (DIAG)
        {
            long gameTime = level.getGameTime();
            if (lastGameTime != Long.MIN_VALUE && gameTime - lastGameTime != 1L)
            {
                long delta = gameTime - lastGameTime;
                clockJumps++;
                clockDrift += delta - 1L;
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastClockLog > 500L)
                {
                    lastClockLog = nowMs;
                    LOGGER.info("[wizardmagic] client clock delta={} gt={} jumps={} drift={}",
                            delta, gameTime, clockJumps, clockDrift);
                }
            }
            lastGameTime = gameTime;
        }

        boolean changed = false;
        // 步进与插值必须用同一个时钟来源：都用 level.getGameTime()，否则时间包跳变时
        // 两者相位错开，画面会在两个插值区间之间来回跳（看起来就是抖动）。
        if (DIAG)
        {
            diagTicks++;
        }
        if (level.getGameTime() % SpellConstants.STEP_TICKS == 0)
        {
            if (DIAG)
            {
                diagSteps++;
                long stepNanos = System.nanoTime();
                if (diagLastStepNanos != 0L)
                {
                    double gap = (stepNanos - diagLastStepNanos) / 1.0E6;
                    diagGapMin = Math.min(diagGapMin, gap);
                    diagGapMax = Math.max(diagGapMax, gap);
                }
                diagLastStepNanos = stepNanos;
            }
            // 先把所有云的 prev 拍下来，再整批推进一步：步进本身是一次并行调度，
            // 而不是每朵云各 fork/join 一次（云多时差 2–4 倍）。
            CLIENT_BATCH.clear();
            for (ClientCloud c : clouds.values())
            {
                c.copyPrev();
                CLIENT_BATCH.add(c.cloud);
            }
            long t0 = System.nanoTime();
            if (!CLIENT_BATCH.isEmpty())
            {
                SpellBatch.step(CLIENT_BATCH);
            }
            long cost = System.nanoTime() - t0;
            for (ClientCloud c : clouds.values())
            {
                if (!c.loggedPath && c.cloud.script != null)
                {
                    c.loggedPath = true;
                    LOGGER.info("[wizardmagic] client script cloud={} id={} fast={} firstStep={}ms",
                            c.cloud.id, c.cloud.script.id, c.cloud.script.fastPathActive(),
                            String.format(Locale.ROOT, "%.2f", cost / 1.0E6));
                }
            }
            if (cost > 3_000_000L && System.currentTimeMillis() - lastSlowLog > 1000L)
            {
                lastSlowLog = System.currentTimeMillis();
                LOGGER.warn("[wizardmagic] slow client spell step clouds={} cost={}ms",
                        CLIENT_BATCH.size(), String.format(Locale.ROOT, "%.2f", cost / 1.0E6));
            }
            CLIENT_BATCH.clear();
            changed = true;
        }

        Iterator<Map.Entry<Integer, ClientCloud>> it = clouds.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<Integer, ClientCloud> entry = it.next();
            ClientCloud c = entry.getValue();
            if (c.cloud.isDead())
            {
                it.remove();
                tombstones.put(entry.getKey(), new long[]{c.cloud.ageSteps(), c.cloud.stateHash(), level.getGameTime()});
                changed = true;
            }
        }

        // 墓碑 10 秒后过期
        long now = level.getGameTime();
        tombstones.entrySet().removeIf(e -> now - e.getValue()[2] > 200);

        if (changed)
        {
            SparkBatch.get().markDirty();
        }

        // 每秒汇总一次时钟/相位诊断（仅调试开关打开时）
        if (DIAG)
        {
            long diagNow = System.nanoTime();
            if (diagNanos == 0L)
            {
                diagNanos = diagNow;
            }
            else if (diagNow - diagNanos >= 1_000_000_000L)
            {
                LOGGER.info("[wizardmagic] render diag: ticks={} steps={} gap={}..{}ms phase={}",
                        diagTicks, diagSteps,
                        diagGapMin == Double.MAX_VALUE ? "-" : String.format(Locale.ROOT, "%.1f", diagGapMin),
                        diagGapMax == 0.0 ? "-" : String.format(Locale.ROOT, "%.1f", diagGapMax),
                        diagPhases.length() == 0 ? "-" : diagPhases.toString());
                diagNanos = diagNow;
                diagTicks = 0;
                diagSteps = 0;
                diagGapMin = Double.MAX_VALUE;
                diagGapMax = 0.0;
                diagPhases.setLength(0);
            }
        }
    }

    // ------------------------------------------------------------- 实例填充

    @Override
    public int fill(float[] data, int maxInstances)
    {
        if (clouds.isEmpty())
        {
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
        {
            return 0;
        }
        // 物理每 2 tick 一步：本 tick 在两步之间的位置系数（与步进用同一个 clock 来源）
        int m = (int) (level.getGameTime() % SpellConstants.STEP_TICKS);
        if (DIAG && m != diagLastPhase)
        {
            diagLastPhase = m;
            if (diagPhases.length() < 80)
            {
                diagPhases.append((char) ('0' + m));
            }
        }
        double f0 = m * 0.5;
        double f1 = f0 + 0.5;

        int idx = 0;
        int written = 0;
        float nowTicks = SparkBatch.nowTicks();

        for (ClientCloud c : clouds.values())
        {
            SpellCloud cloud = c.cloud;
            int n = cloud.count;
            // 与碰撞共用同一个半径：球心停在「表面 ± 半径」，所以画面上刚好贴着表面
            double size = cloud.particleRadius();
            int color = SpellElements.colorArgb(cloud.elementId);
            int cr = (color >> 16) & 0xFF;
            int cg = (color >> 8) & 0xFF;
            int cb = color & 0xFF;
            float birth = c.birthTicks;
            float lifeTicks = cloud.maxSteps * SpellConstants.STEP_TICKS;
            long seed = cloud.castSeed;

            // 半可见：64 格内全亮，64–160 格按距离平滑淡出（服务端在同一区间只发生成包）
            float semi = semiFade(cloud);
            if (semi <= 0.002F)
            {
                continue;
            }

            for (int i = 0; i < n && written < maxInstances; i++)
            {
                double px = c.prevX[i];
                double py = c.prevY[i];
                double pz = c.prevZ[i];
                double cx = cloud.x(i);
                double cy = cloud.y(i);
                double cz = cloud.z(i);

                double ax = px + (cx - px) * f0;
                double ay = py + (cy - py) * f0;
                double az = pz + (cz - pz) * f0;
                double bx = px + (cx - px) * f1;
                double by = py + (cy - py) * f1;
                double bz = pz + (cz - pz) * f1;

                float e = cloud.energy(i) / 100.0F;
                if (e < 0.0F) e = 0.0F;
                if (e > 1.0F) e = 1.0F;
                int r = (int) (cr * e);
                int g = (int) (cg * e);
                int b = (int) (cb * e);
                int a = (int) (255 * e * 0.9F * semi);

                data[idx++] = (float) bx;
                data[idx++] = (float) by;
                data[idx++] = (float) bz;
                data[idx++] = (float) ax;
                data[idx++] = (float) ay;
                data[idx++] = (float) az;
                data[idx++] = Float.intBitsToFloat(r | (g << 8) | (b << 16) | (a << 24));
                data[idx++] = (float) size;
                data[idx++] = birth;
                data[idx++] = lifeTicks;
                data[idx++] = (float) SpellRandom.unit(seed, i, 99);
                written++;
            }
            if (written >= maxInstances)
            {
                break;
            }
        }
        return written;
    }

    // ---------------------------------------------------------------- 半可见

    /**
     * 半可见系数：玩家到云原点的距离 64 格内为 1，64→160 格平滑降到 0。
     *
     * <p>与 shader 无关——法术粒子在 SparkBatch 里是独立 draw 区间，裁距 192 格，
     * 距离淡出完全由这里写进实例 alpha 的系数完成。</p>
     */
    private static float semiFade(SpellCloud cloud)
    {
        var player = Minecraft.getInstance().player;
        if (player == null)
        {
            return 1.0F;
        }
        double distSq = player.distanceToSqr(cloud.originX, cloud.originY, cloud.originZ);
        double full = SpellConstants.VIS_FULL_RANGE;
        double semi = SpellConstants.VIS_SEMI_RANGE;
        if (distSq <= full * full)
        {
            return 1.0F;
        }
        double t = (Math.sqrt(distSq) - full) / (semi - full);
        if (t >= 1.0)
        {
            return 0.0F;
        }
        if (t < 0.0)
        {
            t = 0.0;
        }
        double smooth = t * t * (3.0 - 2.0 * t);
        return (float) (1.0 - smooth);
    }

    // ---------------------------------------------------------------- 内部

    private static final class ClientCloud
    {
        final SpellCloud cloud;
        final double[] prevX;
        final double[] prevY;
        final double[] prevZ;
        final float birthTicks;
        /** 是否已经打印过"走字节码还是解释器" */
        boolean loggedPath;

        ClientCloud(SpellCloud cloud)
        {
            this.cloud = cloud;
            int n = cloud.count;
            prevX = new double[n];
            prevY = new double[n];
            prevZ = new double[n];
            copyPrev();
            this.birthTicks = SparkBatch.nowTicks();
        }

        void copyPrev()
        {
            for (int i = 0; i < cloud.count; i++)
            {
                prevX[i] = cloud.x(i);
                prevY[i] = cloud.y(i);
                prevZ[i] = cloud.z(i);
            }
        }
    }
}
