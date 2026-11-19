package com.wizardmagic.common.client.spell;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.common.client.fx.SparkBatch;
import com.wizardmagic.common.client.fx.SpellInstanceSource;
import com.wizardmagic.common.client.fx.ViewFrustum;
import com.wizardmagic.common.network.SpellCloudRemovePayload;
import com.wizardmagic.common.network.SpellCloudSnapshotPayload;
import com.wizardmagic.common.network.SpellCloudSpawnPayload;
import com.wizardmagic.common.spell.LevelSolidOracle;
import com.wizardmagic.core.spell.SpellBatch;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellCloudManager;
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
import java.util.Arrays;
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
     * 同进程直通开关（单人游戏）：{@code -Dwizardmagic.spell.link=0} 关掉。
     *
     * <p>关掉就退回"客户端镜像自己跑 VM"，用来做 A/B 或出问题时的逃生门。</p>
     */
    private static final boolean LINK_ENABLED =
            !"0".equals(System.getProperty("wizardmagic.spell.link"));

    /**
     * 抽稀阈值的量化档数：1/32（3.1% 一步）。
     *
     * <p>阈值连续取值时，密度每步漂一点点就会让某些粒子跨过阈值 ⇒ 掩码每步都变
     * （观感是细闪）。量化之后只有密度变化超过约 6% 才会真正改判。</p>
     */
    private static final int THR_LEVELS = 32;
    private static final int THR_STEP = 65536 / THR_LEVELS;

    /**
     * 诊断（不参与任何逻辑）：客户端时钟与渲染相位。
     *
     * <p>每秒汇总一行：这一秒里客户端 tick 数、步进数、相邻步进的间隔、以及 {@code fill}
     * 实际采样到的相位变化序列。用来判定「步进/相位是否真的每 tick 前进一格」。</p>
     */
    private long diagNanos;
    private int diagTicks;
    private int diagSteps;
    /** LINK_ALL 每秒翻脸的次数：>0 就说明同一帧里"端点烘焙语义"和"uPartial 来源"会来回切 */
    private int linkAllFlips;
    private boolean lastLinkAll;
    /** 当前"可见但没连上权威云"的云数（它是 LINK_ALL 变 false 的唯一原因） */
    private int unlinkedVisible;
    /** 本帧实例表里前多少个属于"播放时钟组"（直通云）；渲染方据此拆两次 draw */
    private int diagLinkedInstances;
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
        // 同进程直通（单人游戏）：能拿到权威云就不用本地快进、也不用 hash 对拍——
        // 状态下一步就会被服务端数组覆盖。这样连"生成时的 catchUp"也一并省掉。
        boolean linkedAtSpawn = LINK_ENABLED && SpellCloudManager.authoritative(p.id) != null;
        if (linkedAtSpawn)
        {
            LOGGER.info("[wizardmagic] spell spawn path cloud={} link=ON（跳过本地快进）", p.id);
        }
        else
        {
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
        boolean linked = local != null && local.link != null;
        if (steps >= 0)
        {
            // 一次性云跑满寿命 = 正常结束；其余都是可见性退出（走远 / 区块卸载）
            boolean expired = !persistent && maxSteps > 0 && steps >= maxSteps;
            String reason = expired ? "final" : "exit";
            // 客户端收到生成包才开始推进，且不补步；步数不同时 hash 必然不同，
            // 那是延迟不是状态分歧。只有步数相同才能判定一致性。
            // 直通模式（单人游戏）下客户端状态就是服务端数组的拷贝，不可能分歧，
            // 而且我们只拷 x/y/z/energy、不拷速度 —— hash 对不上不代表有问题，直接跳过。
            String verdict;
            boolean warn;
            if (linked)
            {
                verdict = "LINKED";
                warn = false;
            }
            else if (steps == p.steps)
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
            int linkedCount = 0;
            for (ClientCloud c : clouds.values())
            {
                // 同进程直通：单人游戏里服务端和客户端同 JVM，物理不用跑两遍。
                // 连上权威云就只把它这一步的状态**抓到待用槽**（不直接换正在画的那份）。
                if (LINK_ENABLED && c.tryLink())
                {
                    c.captureNext();
                    linkedCount++;
                }
                else
                {
                    c.copyPrev();
                    CLIENT_BATCH.add(c.cloud);
                }
            }
            long t0 = System.nanoTime();
            if (!CLIENT_BATCH.isEmpty())
            {
                SpellBatch.step(CLIENT_BATCH);
            }
            long cost = System.nanoTime() - t0;
            diagLinked = linkedCount;
            if (linkedCount > 0 && !loggedLink)
            {
                loggedLink = true;
                LOGGER.info("[wizardmagic] mirror link ON: 直通 {}/{} 朵（同进程读服务端状态，客户端不再跑 VM）",
                        linkedCount, clouds.size());
            }
            // 步进之后重算渲染预算（体素密度 + 视锥）；预算够时这条几乎不花钱
            planMirrorBudget();
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
                // 并行诊断：caller% 接近 100 = 池子没帮忙；cpu/墙钟 = 有效并行度；
                // ns/粒(CPU) = 每颗粒子的真实计算成本（和 spellMicro 的 80 ns 对比就该看出问题）
                long cpuNanos = com.wizardmagic.core.spell.SpellParallel.lastCpuNanos();
                long particles = 0L;
                for (ClientCloud c : clouds.values())
                {
                    particles += c.cloud.count;
                }
                LOGGER.warn("[wizardmagic] slow client spell step clouds={} particles={} cost={}ms"
                                + " | pool threads={} chunks={} caller={} ({}%) cpu={}ms par={} ns/粒={}",
                        CLIENT_BATCH.size(), particles, String.format(Locale.ROOT, "%.2f", cost / 1.0E6),
                        com.wizardmagic.core.spell.SpellParallel.threads(),
                        com.wizardmagic.core.spell.SpellParallel.lastChunks(),
                        com.wizardmagic.core.spell.SpellParallel.lastCallerChunks(),
                        String.format(Locale.ROOT, "%.0f",
                                com.wizardmagic.core.spell.SpellParallel.lastCallerShare() * 100.0),
                        String.format(Locale.ROOT, "%.1f", cpuNanos / 1.0E6),
                        String.format(Locale.ROOT, "%.2f", (double) cpuNanos / Math.max(1L, cost)),
                        String.format(Locale.ROOT, "%.0f",
                                particles <= 0 ? 0.0 : (double) cpuNanos / particles));
            }
            CLIENT_BATCH.clear();
            // 全部直通时不在 tick 里标脏：实例表由播放时钟在**段边界**重建（约 10 Hz），
            // 段内每帧只换 uPartial 一个 uniform。只要有一朵没连上，就还得每 tick 重建。
            changed = linkedCount < clouds.size();
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
        // 镜像模式的插值窗口按 gameTime 的 tick 相位；全直通时窗口恒为整段（0→1），
        // 相位由播放时钟算出的 uPartial 提供（见 frameTick）。

        // 本帧的可见性 + 预算：先把"剔除后真要画多少"加出来，超了由 gate 兜底
        // （正常情况 planMirrorBudget 已经压到预算内，gate 恒为 65536）
        long want = 0;
        for (ClientCloud c : clouds.values())
        {
            c.visible = ViewFrustum.isVisible(c.bounds[0], c.bounds[1], c.bounds[2],
                    c.bounds[3], c.bounds[4], c.bounds[5]);
            if (c.visible)
            {
                // 真正会被写的上限 = 抽稀后 ∧ 剔除后
                want += Math.min(c.keepCount, c.visibleCount);
            }
        }
        // 兜底闸门：正常情况下 planMirrorBudget 已经把 want 压到预算内，这里恒为 65536。
        // 万一（掩码还没重算的那几步）真的超了，用**逐粒子哈希门**按比例削，
        // 而不是整数步长——整数步长最多浪费一半预算，哈希门是精确填满。
        int gate = 65536;
        if (want > maxInstances)
        {
            gate = (int) Math.max(1L, Math.min(65536L, (65536L * maxInstances) / want));
        }

        int idx = 0;
        int written = 0;
        int culled = 0;
        float nowTicks = SparkBatch.nowTicks();

        // 两趟写：先"播放时钟组"（直通云，端点=整段两端），再"tick 组"（镜像云，端点=半步窗口）。
        // 分组是必须的——同一个 uPartial 只能服务一种烘焙语义，渲染方按 diagLinkedInstances
        // 把法术区间拆成两次 draw。以前用全局 LINK_ALL 决定烘焙方式，一朵云没连上就会把
        // 所有直通云切到 tick 相位（端点却还是播放时钟的），那是逐帧级抖动的直接来源。
        diagLinkedInstances = 0;
        writePass:
        for (int pass = 0; pass < 2; pass++)
        {
            for (ClientCloud c : clouds.values())
            {
                boolean playClock = c.link != null && ClientCloud.LINK_READY;
                if (playClock != (pass == 0))
                {
                    continue;
                }
                if (!c.visible)
                {
                    continue;
                }
            SpellCloud cloud = c.cloud;
            // 与碰撞共用同一个半径：球心停在「表面 ± 半径」，所以画面上刚好贴着表面
            double size = cloud.particleRadius() * c.sizeScale;
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

            final int[] keep = c.keepIdx;
            final int n = c.keepCount;
            final long gateSeed = seed ^ 0x2545F4914F6CDD1DL;
            // 粒子级剔除快照（每帧一份；null = 还没抓到相机 → 不剔）
            final double[] frustum = ViewFrustum.snapshot();
            // 本朵云的插值窗口：**按本云自己的 link 状态**决定，不再看全局 LINK_ALL。
            // 直通云 → 段两端（0→1），相位由播放时钟给；
            // 镜像云 → 50 ms 窗口（两个 tick 档），相位由 partialTick 扫。
            // 两者必须由渲染方分成两次 draw：同一个 uPartial 只能服务同一种烘焙语义。
            final double cf0;
            final double cf1;
            if (c.link != null && ClientCloud.LINK_READY)
            {
                cf0 = 0.0;
                cf1 = 1.0;
            }
            else
            {
                cf0 = f0;
                cf1 = f1;
            }
            final double[] curX = c.curX;
            final double[] curY = c.curY;
            final double[] curZ = c.curZ;
            for (int k = 0; k < n; k++)
            {
                int i = keep == null ? k : keep[k];
                double px = c.prevX[i];
                double py = c.prevY[i];
                double pz = c.prevZ[i];
                double cx = curX[i];
                double cy = curY[i];
                double cz = curZ[i];

                // 1) 粒子级剔除：整颗在视野外就整颗跳过（连插值和实例写入都省）。
                //    判据是三条点积 + 比较，取不到相机快照时整段不生效。
                if (frustum != null && !ViewFrustum.pointVisible(frustum, cx, cy, cz, size))
                {
                    culled++;
                    continue;
                }
                // 2) 限流闸门：只在"剔除后仍然超预算"时介入（正常恒为 65536）
                if (gate < 65536 && hash16(i, gateSeed) >= gate)
                {
                    continue;
                }

                double ax = px + (cx - px) * cf0;
                double ay = py + (cy - py) * cf0;
                double az = pz + (cz - pz) * cf0;
                double bx = px + (cx - px) * cf1;
                double by = py + (cy - py) * cf1;
                double bz = pz + (cz - pz) * cf1;

                if (written == 0)
                {
                    probeX = (float) bx;
                    probeY = (float) by;
                    probeZ = (float) bz;
                    probeSize = (float) size;
                    probeBirth = birth;
                    probeLife = lifeTicks;
                    probePhase = (float) SpellRandom.unit(seed, i, 99);
                    probeArgb = 0;   // 颜色在下面算，先占位
                }
                float e = (c.link != null ? c.curE[i] : cloud.energy(i)) / 100.0F;
                if (e < 0.0F) e = 0.0F;
                if (e > 1.0F) e = 1.0F;
                int r = (int) (cr * e);
                int g = (int) (cg * e);
                int b = (int) (cb * e);
                int a = (int) (255 * e * 0.9F * semi);
                if (probeArgb == 0)
                {
                    probeArgb = r | (g << 8) | (b << 16) | (a << 24);
                }

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
                break writePass;
            }
            }
            if (pass == 0)
            {
                // 前 diagLinkedInstances 个实例属于"播放时钟组"，渲染方据此拆两次 draw
                diagLinkedInstances = written;
            }
        }
        diagWritten = written;
        diagCulled = culled;
        // 播放时钟诊断（1/s）：延迟、段长、相位、断供次数
        diagDry = (int) ClientCloud.LINK_STARVED;
        diagSegMs = ClientCloud.SEG / 1.0E6;
        // delay 字段语义已改：不再是"R=now-DELAY"的延迟估计，而是"播放头落后最新快照的
        // 毫秒数"（即水位 ×100ms/步）；直通尚未开始（gPlay 未初始化）时打 0。
        diagDelayMs = ClientCloud.gPlay == Double.NEGATIVE_INFINITY ? 0.0
                : (ClientCloud.gFront - ClientCloud.gPlay) * 100.0;
        diagU = ClientCloud.LINK_U;
        // 临时探针：每秒一行，把"写进去的第一个实例"原样打出来。
        // 用来回答"到底是没写、写飞了、还是写了但画不出来"。
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastProbeLog > 1000L && !clouds.isEmpty())
        {
            lastProbeLog = nowMs;
            // H1 取证（只读不改行为）：linkMisses 是每朵云的实例字段，probe 是全局视角，这里求和。
            int miss = 0;
            for (ClientCloud c : clouds.values())
            {
                miss += c.linkMisses;
            }
            // 每云口径（最多 6 朵）：off = 本云最新标号比全局前沿落后几步；
            // hold = 播放头走到本云最新快照之外的帧数；skip = 本云因一帧跨两步丢掉的步数。
            // 这三样是"多云里只有某朵云抖"唯一可能被量到的量（全局计数器对它全瞎）。
            StringBuilder per = new StringBuilder();
            int shown = 0;
            for (ClientCloud c : clouds.values())
            {
                if (c.link == null || c.myStep < 0L)
                {
                    continue;
                }
                if (shown >= 6)
                {
                    per.append(" …");
                    break;
                }
                shown++;
                long label = c.baseGlobal + c.myStep;
                per.append(String.format(Locale.ROOT, " c%d[off=%d hold=%d skip=%d]",
                        c.cloud.id, ClientCloud.gFront - label, c.holdFrames, c.epochSkips));
            }
            LOGGER.info("[wizardmagic] mirror probe: clouds={} linked={} seg={}ms delay={}ms u={} starved={} written={} culled={} kept={} visible={} |"
                            + " inst0 pos=({},{},{}) size={} birth={} life={} phase={} argb={}"
                            + " | cam=({},{},{})"
                            + " | miss={} discont={} ahead={} skip={} iv={}ms torn={} | percloud{}"
                            + " | dt={}ms jump={} pjump={} all={} flips={} unlink={}",
                    clouds.size(), diagLinked,
                    String.format(Locale.ROOT, "%.0f", diagSegMs),
                    String.format(Locale.ROOT, "%.0f", diagDelayMs),
                    String.format(Locale.ROOT, "%.3f", diagU), diagDry,
                    written, culled, diagKept, diagVisible,
                    String.format(Locale.ROOT, "%.2f", probeX),
                    String.format(Locale.ROOT, "%.2f", probeY),
                    String.format(Locale.ROOT, "%.2f", probeZ),
                    String.format(Locale.ROOT, "%.4f", probeSize),
                    String.format(Locale.ROOT, "%.1f", probeBirth),
                    String.format(Locale.ROOT, "%.1f", probeLife),
                    String.format(Locale.ROOT, "%.3f", probePhase),
                    Integer.toHexString(probeArgb),
                    String.format(Locale.ROOT, "%.1f", ViewFrustum.camX()),
                    String.format(Locale.ROOT, "%.1f", ViewFrustum.camY()),
                    String.format(Locale.ROOT, "%.1f", ViewFrustum.camZ()),
                    miss, ClientCloud.LINK_DISCONT, ClientCloud.LINK_AHEAD, ClientCloud.LINK_EPOCH_SKIP,
                    ClientCloud.iv16StatsMs(), ClientCloud.LINK_TORN, per,
                    String.format(Locale.ROOT, "%.1f", ClientCloud.DBG_DT_MAX_MS),
                    String.format(Locale.ROOT, "%.3f", ClientCloud.DBG_STEP_JUMP_MAX),
                    ClientCloud.DBG_PAIR_JUMP_MAX,
                    ClientCloud.LINK_ALL, linkAllFlips, unlinkedVisible);
            // 帧级诊断口径清零，下一秒重新统计
            ClientCloud.DBG_DT_MAX_MS = 0.0;
            ClientCloud.DBG_STEP_JUMP_MAX = 0.0;
            ClientCloud.DBG_PAIR_JUMP_MAX = 0L;
            linkAllFlips = 0;
        }
        // 有可见云却一个实例都没写：一定是剔除/预算出了问题，留一条可诊断的日志。
        // （visible==0 是"全部被视锥剔掉"，那是正常情况，不算问题）
        if (written == 0 && !clouds.isEmpty() && diagVisible > 0)
        {
            long now = System.currentTimeMillis();
            if (now - lastZeroLog > 1000L)
            {
                lastZeroLog = now;
                LOGGER.warn("[wizardmagic] mirror wrote 0 instances: clouds={} visible={} kept={} {}",
                        clouds.size(), diagVisible, diagKept, ViewFrustum.describe());
            }
        }
        return written;
    }

    /**
     * 每帧推进播放时钟（渲染前调用）。
     *
     * <p><b>适用范围</b>：只有"同进程直通"才有意义——单人游戏，或局域网里**当主机的那台机器**。
     * 判定条件就是 {@link ClientCloud#tryLink()} 拿到权威云，而权威云只存在于跑服务端的同一个 JVM 里；
     * 加入别人房间的客户端、连专用服的客户端都拿不到，会继续走原来的镜像路径（按 tick 相位插值）。</p>
     *
     * <p>全直通时：改用匀速播放时钟+水位伺服（{@link ClientCloud#advancePlayback}）——
     * 一段恒等于 100ms 仿真，按 rate×dt 匀速推进；不再按快照的实际到达间隔插值
     * （到达间隔在 34~180ms 间抖动，那种插值让每段速度在 0.55×~2.9× 间跳变）。
     * 段号变了才返回 true —— 那一刻实例表才需要重建，段内只换 uniform。</p>
     */
    @Override
    public boolean frameTick()
    {
        boolean anyLinked = false;
        boolean allLinked = true;
        for (ClientCloud c : clouds.values())
        {
            if (c.link != null)
            {
                anyLinked = true;
            }
            else if (c.visible)
            {
                allLinked = false;
            }
        }
        ClientCloud.LINK_ALL = anyLinked && allLinked;
        // 诊断：LINK_ALL 一旦翻脸，uPartial 就会在"播放时钟相位"和 MC 的 partialTick 之间切换，
        // 同时 fill() 烘焙的端点也会从"整段 0→1"切成"[u-0.5, u] 半窗"——那是两条完全不同的
        // 插值语义混用，会直接产生 20 Hz（partialTick 周期）的位置来回扫。所以必须计数。
        if (ClientCloud.LINK_ALL != lastLinkAll)
        {
            lastLinkAll = ClientCloud.LINK_ALL;
            linkAllFlips++;
        }
        unlinkedVisible = 0;
        for (ClientCloud c : clouds.values())
        {
            if (c.link == null && c.visible)
            {
                unlinkedVisible++;
            }
        }
        if (!anyLinked)
        {
            return false;
        }
        // 抓拍挪到每帧：对每朵已连接的云检查一次 epoch（一次 volatile 读）。
        // 60fps 网格约 16ms 一格，服务端一步 ~100ms，漏抓在结构上不可能；
        // 云在服务端一步完成后的下一帧就能进环（比 10Hz 的 tick 网格快）。
        // tick() 里原有的 captureNext 调用保留不动：渲染路径被跳过时兜底，
        // epoch 未变时它只是一次 volatile 读，无成本。
        // 帧开始前沿：本帧若有云首次抓拍（myStep 尚未锚定），baseGlobal 以"它 + 1"锚定，
        // 所以这份前沿必须在 captureNext 循环之前刷新。
        ClientCloud.gFront = computeFront();
        for (ClientCloud c : clouds.values())
        {
            if (c.link != null)
            {
                c.captureNext();
            }
        }
        // 抓拍后的前沿：伺服用这一份——按"实际抓到的步数"推进，一帧连抓两步（追帧爆发）也如实 +2，
        // 不再受 feedTimeline 按时间去重（吞 gStep++）的调制。
        ClientCloud.gFront = computeFront();
        final double[] phase = linkPhase;
        // 匀速播放时钟 + 水位伺服：段内段间速度恒为 rate（≈1×），到达抖动全被水位缓冲吸收
        long seg = ClientCloud.advancePlayback(System.nanoTime(), phase);
        if (seg < 0L)
        {
            // 时间轴还没铺开（不足两步快照）：这一帧退回 tick 相位
            ClientCloud.LINK_ALL = false;
            ClientCloud.LINK_READY = false;
            return false;
        }
        ClientCloud.LINK_READY = true;
        ClientCloud.LINK_U = phase[0];
        boolean rebuild = false;
        for (ClientCloud c : clouds.values())
        {
            if (c.link == null)
            {
                continue;
            }
            c.phase = phase[0];
            if (c.applyPair(seg))
            {
                rebuild = true;
            }
        }
        // 相位必须**相对实际被画的那一对快照**量，而不是相对播放头段号 k。
        // 数据没到时 applyPair 只能把配对钉在最新一段（pairStep < k），此时用 k 算出的相位
        // 会在钉住的那一段上反复扫 0→1（约 10 次/秒 = "方向来回"）；改成相对 pairStep 量，
        // 相位就会随播放头一起超过 1，被 spellPartial 夹在 1.4 —— 表现为"停在段末等数据"，
        // 这才是断供时应有的样子。
        long pairBase = Long.MAX_VALUE;
        for (ClientCloud c : clouds.values())
        {
            if (c.link != null && c.pairStep >= 0L && c.pairStep < pairBase)
            {
                pairBase = c.pairStep;
            }
        }
        if (pairBase != Long.MAX_VALUE)
        {
            ClientCloud.LINK_U = ClientCloud.gPlay - pairBase;
        }
        if (ClientCloud.fdLeft > 0)
        {
            frameDump(seg, phase[0]);
        }
        return rebuild;
    }

    /**
     * 逐帧 dump（诊断，默认关）：{@code -Dwizardmagic.spell.framedump=<行数>}。
     *
     * <p>现有 probe 是**每秒一行**，对"高频（逐帧级）抖动"完全失明——采到的是什么相位纯看运气。
     * 这里每帧打一行，直接看：</p>
     * <ul>
     *   <li>{@code u} 是否平滑单调（若是，相位没抖）；</li>
     *   <li>{@code pair}/{@code pair0} 是否逐帧跳跃（配对在换）；</li>
     *   <li>{@code prev0}/{@code cur0}（固定第 0 颗粒子的两个端点坐标）是否逐帧跳变
     *       —— 内容跳了就是环槽被覆盖 / 选错槽；</li>
     *   <li>{@code all}（LINK_ALL）是否在翻脸——它一翻，uPartial 会在"播放时钟相位"和
     *       MC 的 partialTick 之间来回切，那是**逐帧级**的错相位来源。</li>
     * </ul>
     */
    private void frameDump(long seg, double u)
    {
        ClientCloud c = null;
        for (ClientCloud x : clouds.values())
        {
            if (x.link != null)
            {
                c = x;
                break;
            }
        }
        ClientCloud.fdLeft--;
        if (c == null || c.prevX == null || c.curX == null)
        {
            LOGGER.info("[wizardmagic] frame dump: 无直通云");
            return;
        }
        LOGGER.info("[wizardmagic] frame dump: u={} k={} lk={} pair={} w={} all={} front={} "                        + "prev0=({},{},{}) cur0=({},{},{})",
                String.format(Locale.ROOT, "%.3f", u), seg, seg - c.baseGlobal, c.pairStep,
                String.format(Locale.ROOT, "%.2f", ClientCloud.gFront - ClientCloud.gPlay),
                ClientCloud.LINK_ALL, ClientCloud.gFront,
                String.format(Locale.ROOT, "%.3f", c.prevX[0]),
                String.format(Locale.ROOT, "%.3f", c.prevY[0]),
                String.format(Locale.ROOT, "%.3f", c.prevZ[0]),
                String.format(Locale.ROOT, "%.3f", c.curX[0]),
                String.format(Locale.ROOT, "%.3f", c.curY[0]),
                String.format(Locale.ROOT, "%.3f", c.curZ[0]));
    }

    /**
     * 派生前沿：所有已抓拍云的 {@code baseGlobal + myStep} 的最大值（全局坐标系由它定义）。
     *
     * <p>没抓拍过的云（{@code myStep = -1}）不参与；一朵都没有返回 -1。
     * ClientCloud 是静态嵌套类、够不到外层实例的 {@code clouds}，
     * 所以这个计算落在外层类里（实例方法，直接遍历自己的 map）。</p>
     */
    private long computeFront()
    {
        long front = -1L;
        for (ClientCloud c : clouds.values())
        {
            if (c.link != null && c.myStep >= 0L)
            {
                long v = c.baseGlobal + c.myStep;
                if (v > front)
                {
                    front = v;
                }
            }
        }
        return front;
    }

    @Override
    public boolean wantsDraw()
    {
        return !clouds.isEmpty();
    }

    /**
     * 实例表前多少个属于"播放时钟组"（端点整段 0→1 烘焙）。
     *
     * <p>渲染方用它把法术区间拆成两次 draw：前段用播放时钟相位，后段用 {@code partialTick}。
     * 这样一朵云没连上、或者时间轴还没铺开，都不会污染别的云的相位。</p>
     */
    @Override
    public int linkedInstanceCount()
    {
        return ClientCloud.LINK_READY ? diagLinkedInstances : 0;
    }

    /**
     * "播放时钟组"实例的 {@code uPartial}：播放时钟的段内相位。
     *
     * <p><b>判据是 {@link ClientCloud#LINK_READY}，不是 LINK_ALL</b>：端点按整段 0→1 烘焙的
     * 那批实例（见 {@link #linkedInstanceCount()}）必须配播放时钟相位；同一帧里如果混进了
     * 镜像云，它们走另一次 draw、用 {@code partialTick}。以前这里用全局 LINK_ALL，
     * 一朵云没连上就会让**所有**直通云改用 tick 相位，而端点仍是播放时钟烘焙的整段
     * ——两个时钟错配，画面在同一段内 20 Hz 来回扫。</p>
     */
    @Override
    public float spellPartial(float partialTick)
    {
        if (!ClientCloud.LINK_READY)
        {
            return partialTick;
        }
        double u = ClientCloud.LINK_U;
        // 外推上限 40 ms：再晚就按住（等下一段），否则高速粒子会顺着旧方向飞歪。
        // 这里只夹不外推的计数（它每帧会被 2~3 次 draw 各问一遍，计数放在 frameTick 里）。
        if (u > ClientCloud.U_MAX)
        {
            u = ClientCloud.U_MAX;
        }
        else if (u < 0.0)
        {
            u = 0.0;
        }
        return (float) u;
    }

    // ------------------------------------------------------------- 渲染预算

    /** 体素密度每 4 个物理步才重算一次（密度变化远慢于粒子运动） */
    private int planCooldown;
    private long lastBudgetLog;
    private long lastZeroLog;
    private int diagClouds;
    private int diagVisible;
    private int diagKept;
    private int diagWritten;
    /** 上一帧被粒子级剔除跳过的粒子数（诊断：验证剔除到底砍掉多少） */
    private int diagCulled;
    /** 上一步直通（跳过本地 VM）的云数 */
    private int diagLinked;
    private boolean loggedLink;
    /** 播放时钟诊断：段长、水位（播放头落后毫秒数）、段内相位、断供帧数（正常段长≈100、水位≈160、断供=0） */
    private final double[] linkPhase = new double[1];
    private double diagSegMs;
    private double diagDelayMs;
    private double diagU;
    private int diagDry;
    private long lastProbeLog;
    private float probeX, probeY, probeZ, probeSize, probeBirth, probeLife, probePhase;
    private int probeArgb;
    private int dbgDRef, dbgCells, dbgVoxSum, dbgHistSum, dbgOcc0, dbgKeep0, dbgCount0, dbgSolve;
    private long dbgEst8;
    private long dbgPlanned;
    /** 视锥剔除与分档统计（每秒随 budget 行打一次） */
    private int dbgRawVisible;
    private int dbgCullDist, dbgCullBehind, dbgCullCone;
    private int dbgTierFull, dbgTierSemi, dbgTierHidden;
    private double dbgProjLo, dbgProjHi, dbgDistLo, dbgDistHi;
    /** 上一次求根实际评估了几次（诊断用） */
    private int solveEvals;

    /**
     * 每个物理步调一次：给每朵云算"渲染预算"。
     *
     * <p><b>原则</b>：只在**密集处**抽稀。世界空间体素密度（0.5 格起）里，
     * 一格密度 {@code v}、这朵云的典型密度 {@code dRef}（非空格中位数）时，
     * 保留比例 {@code ratio = min(1, g·√(dRef/v))}：
     * 稀疏格（v &lt; dRef）常常 100% 全留，稠密格抽得多——**密度梯度保留**，
     * 不会像"每格封顶 T"那样把所有密集处压平成同一个密度。</p>
     *
     * <p>{@code g} 是全局增益，由 {@link #solveGain(int)} 逐格精确求根到
     * "期望保留总数 ≤ 实例表预算"，所以预算基本是**填满**的，不是砍一半。</p>
     *
     * <p>抽稀用"粒子下标哈希"，不是随机数：同一颗粒子每步的去留完全一致 ⇒ 不闪。
     * 掩码只在"总粒数超预算"时才需要算，平时（云少）整条路径是 O(云数)。</p>
     */
    private void planMirrorBudget()
    {
        final int budget = SparkBatch.maxDraw();
        // 顺序：**先剔除，再判预算**。
        // 云级（AABB）→ 粒子级（三平面）两趟都做完，得到"这一帧真的会被画的粒子数"，
        // 然后才拿它去和实例表预算比。不能反过来（先按视锥内总数算预算、再乘一个
        // 剔除比例去修），那是拿上一帧的结果猜这一帧。
        final double[] frustum = ViewFrustum.snapshot();
        long rawVisible = 0;      // 云级剔除后、粒子级剔除前的粒子数
        long visibleTotal = 0;    // 粒子级剔除后的粒子数 = 真正要画的量
        int cullDist = 0;
        int cullBehind = 0;
        int cullCone = 0;
        int tierFull = 0;
        int tierSemi = 0;
        int tierHidden = 0;
        double projLo = Double.MAX_VALUE;
        double projHi = -Double.MAX_VALUE;
        double distLo = Double.MAX_VALUE;
        double distHi = 0.0;
        for (ClientCloud c : clouds.values())
        {
            c.visible = ViewFrustum.isVisible(c.bounds[0], c.bounds[1], c.bounds[2],
                    c.bounds[3], c.bounds[4], c.bounds[5]);
            double[] probe = ViewFrustum.last();
            double proj = probe[0];
            double dist = probe[1];
            if (proj < projLo) projLo = proj;
            if (proj > projHi) projHi = proj;
            if (dist < distLo) distLo = dist;
            if (dist > distHi) distHi = dist;
            if (!c.visible)
            {
                double why = probe[2];
                if (why == -1.0) cullDist++;
                else if (why == -2.0) cullBehind++;
                else cullCone++;
                c.visibleCount = 0;
                continue;
            }
            rawVisible += c.cloud.count;
            // 粒子级剔除：这一朵还剩多少颗真的要画
            c.visibleCount = countVisible(c, frustum);
            visibleTotal += c.visibleCount;
            // 分档按半可见系数算（与 fill 的 alpha 用同一套判据），
            // 这样"能省多少朵"就是一个可直接读的数
            float semi = semiFade(c.cloud);
            if (semi >= 0.999F) tierFull++;
            else if (semi > 0.002F) tierSemi++;
            else tierHidden++;
        }
        dbgRawVisible = (int) rawVisible;
        dbgCullDist = cullDist;
        dbgCullBehind = cullBehind;
        dbgCullCone = cullCone;
        dbgTierFull = tierFull;
        dbgTierSemi = tierSemi;
        dbgTierHidden = tierHidden;
        dbgProjLo = projLo;
        dbgProjHi = projHi;
        dbgDistLo = distLo;
        dbgDistHi = distHi;
        diagClouds = clouds.size();

        // 剔除后已经够：一个都不抽（也不该重置 sizeScale 以外的任何东西）
        if (visibleTotal <= budget)
        {
            for (ClientCloud c : clouds.values())
            {
                c.keepIdx = null;
                c.keepCount = c.cloud.count;
                c.sizeScale = 1f;
            }
            diagKept = (int) visibleTotal;
            diagVisible = (int) visibleTotal;
            logBudget(1.0);
            return;
        }

        if (planCooldown > 0)
        {
            planCooldown--;
            return;
        }
        planCooldown = 3;   // 之后每 4 步重算一次

        // 1) 体素计数 + 直方图（**只数剔除后可见的粒子**，密度梯度才对得上画出来的东西）
        for (ClientCloud c : clouds.values())
        {
            if (c.visible)
            {
                voxelize(c, frustum);
            }
        }

        // 2) 解全局增益 g：**逐格精确求根**，不用直方图近似。
        //
        //    每格 v 颗、基线 dRef、增益 g 时，这一格保留
        //        v ≤ u²  →  v（全留）
        //        v > u²  →  v·min(1, u/√v) = u·√v        其中 u = g·√dRef
        //    所以 kept(g) 关于 g 单调递增、分段线性，可以直接二分（对数域）。
        //    老实现用 hist[b]·2^b 估算，2^b 是桶的**下限**，靠近桶上限的格子被
        //    低估近一半 ⇒ 解出来的 g 偏大 ⇒ 实际保留超预算，再由整数步长硬砍一半。
        double gain = solveGain(budget);
        dbgSolve = solveEvals;
        dbgPlanned = totalExpected(gain);

        // 诊断：记录第一朵可见云的体素统计，便于核对"期望 / 实际"是否自洽
        for (ClientCloud c : clouds.values())
        {
            if (!c.visible) continue;
            int cells = c.gx * c.gy * c.gz;
            int voxSum = 0;
            int occ = 0;
            for (int k = 0; k < cells; k++)
            {
                int v = c.vox[k];
                if (v > 0)
                {
                    voxSum += v;
                    occ++;
                }
            }
            int histSum = 0;
            for (int b = 0; b < 14; b++) histSum += c.hist[b];
            dbgDRef = c.dRef;
            dbgCells = cells;
            dbgVoxSum = voxSum;
            dbgHistSum = histSum;
            dbgOcc0 = occ;
            dbgCount0 = c.cloud.count;
            dbgEst8 = expectedKept(c, 8.0);
            break;
        }

        // 3) 生成保留下标表
        long kept = 0;
        dbgKeep0 = 0;   // 每次 plan 都要重采，别沿用上一步的旧值
        for (ClientCloud c : clouds.values())
        {
            if (!c.visible)
            {
                c.keepIdx = null;
                c.keepCount = c.cloud.count;
                c.sizeScale = 1f;
                continue;
            }
            buildKeep(c, gain);
            if (dbgKeep0 <= 0)
            {
                dbgKeep0 = c.keepCount;
            }
            kept += c.keepCount;
        }
        diagKept = (int) kept;
        diagVisible = (int) visibleTotal;
        logBudget(gain);
    }

    /** 一秒最多打一行，用来验证"到底抽了多少、抽的是不是密集处" */
    private void logBudget(double gain)
    {
        long now = System.currentTimeMillis();
        if (now - lastBudgetLog < 1000L)
        {
            return;
        }
        lastBudgetLog = now;
        if (diagClouds == 0 || diagVisible == 0)
        {
            return;
        }
        if (diagVisible <= SparkBatch.maxDraw() && !DIAG)
        {
            return;   // 没超预算就别刷日志
        }
        LOGGER.info("[wizardmagic] mirror budget clouds={} raw={} visible={} planned={} kept={} written={} gain={}"
                        + " eval={} | cull dist={} behind={} cone={} | tier full={} semi={} hidden={}"
                        + " | dist=[{},{}] proj=[{},{}] {} | cloud0 n={} dRef={} cells={} occ={} voxSum={} histSum={} keep0={} est8={}",
                diagClouds, dbgRawVisible, diagVisible, dbgPlanned, diagKept, diagWritten,
                String.format(Locale.ROOT, "%.3f", gain), dbgSolve,
                dbgCullDist, dbgCullBehind, dbgCullCone,
                dbgTierFull, dbgTierSemi, dbgTierHidden,
                String.format(Locale.ROOT, "%.1f", dbgDistLo), String.format(Locale.ROOT, "%.1f", dbgDistHi),
                String.format(Locale.ROOT, "%.1f", dbgProjLo), String.format(Locale.ROOT, "%.1f", dbgProjHi),
                ViewFrustum.describe(),
                dbgCount0, dbgDRef, dbgCells, dbgOcc0, dbgVoxSum, dbgHistSum, dbgKeep0, dbgEst8);
    }

    /**
     * 粒子级剔除计数：这一朵云剔除后还剩多少颗真要画。
     *
     * <p>成本约 3 次点积/颗粒子（40 万颗 ≈ 0.5 ms），但它换来的是**正确的预算前提**：
     * 后面的密度直方图、增益求解、限流闸门全部以这个数为基准。</p>
     */
    private static int countVisible(ClientCloud c, double[] frustum)
    {
        SpellCloud cloud = c.cloud;
        int n = cloud.count;
        if (frustum == null)
        {
            return n;
        }
        double r = cloud.particleRadius() * c.sizeScale;
        int vis = 0;
        for (int i = 0; i < n; i++)
        {
            if (ViewFrustum.pointVisible(frustum, c.curX[i], c.curY[i], c.curZ[i], r))
            {
                vis++;
            }
        }
        return vis;
    }

    /** 体素边长：量化到 2 的幂（≥0.5 格），并把网格原点吸附到世界格点上。
     *
     * <p>两者都是为了**掩码稳定**：体素尺寸/原点每步都变的话，同一颗粒子会在
     * "这格密度高/那格密度低"之间反复横跳，观感上就是闪烁。量化之后只有跨过
     * 2 的幂那一档才会重排。</p> */
    private static void voxelize(ClientCloud c, double[] frustum)
    {
        double ex = Math.max(1.0E-3, c.bounds[3] - c.bounds[0]);
        double ey = Math.max(1.0E-3, c.bounds[4] - c.bounds[1]);
        double ez = Math.max(1.0E-3, c.bounds[5] - c.bounds[2]);
        double longest = Math.max(ex, Math.max(ey, ez));
        double want = Math.max(0.5, longest / 16.0);
        // 向上取到 2 的幂
        double vs = 0.5;
        while (vs < want && vs < 64.0)
        {
            vs *= 2.0;
        }
        c.voxSize = vs;
        c.voxOx = Math.floor(c.bounds[0] / vs) * vs;
        c.voxOy = Math.floor(c.bounds[1] / vs) * vs;
        c.voxOz = Math.floor(c.bounds[2] / vs) * vs;
        c.gx = (int) Math.max(1, Math.min(16, Math.ceil((c.bounds[3] - c.voxOx) / vs)));
        c.gy = (int) Math.max(1, Math.min(16, Math.ceil((c.bounds[4] - c.voxOy) / vs)));
        c.gz = (int) Math.max(1, Math.min(16, Math.ceil((c.bounds[5] - c.voxOz) / vs)));
        int cells = c.gx * c.gy * c.gz;
        if (c.vox == null || c.vox.length < cells)
        {
            c.vox = new int[cells];
            c.voxSqrt = new float[cells];
        }
        Arrays.fill(c.vox, 0, cells, 0);
        SpellCloud cloud = c.cloud;
        int n = cloud.count;
        if (c.cellOf == null || c.cellOf.length < n)
        {
            c.cellOf = new int[n];
        }
        int[] cellOf = c.cellOf;
        int[] vox = c.vox;
        double r = cloud.particleRadius() * c.sizeScale;
        for (int i = 0; i < n; i++)
        {
            double x = c.curX[i];
            double y = c.curY[i];
            double z = c.curZ[i];
            // 剔除掉的粒子：格号记 -1，既不进密度统计，也不进保留下标表
            // （保留下标表 = 可见 ∩ 通过密度阈值，keepCount 就是真正要画的数）
            if (frustum != null && !ViewFrustum.pointVisible(frustum, x, y, z, r))
            {
                cellOf[i] = -1;
                continue;
            }
            int k = cellIndex(c, x, y, z);
            cellOf[i] = k;
            vox[k]++;
        }
        // 每格的 √v 缓存（求根时 kept = Σ u·√v，避免反复开方）
        float[] sq = c.voxSqrt;
        Arrays.fill(c.hist, 0);
        int nonEmpty = 0;
        for (int k = 0; k < cells; k++)
        {
            int v = vox[k];
            if (v > 0)
            {
                sq[k] = (float) Math.sqrt(v);
                c.hist[Math.min(13, 31 - Integer.numberOfLeadingZeros(v))]++;
                nonEmpty++;
            }
            else
            {
                sq[k] = 0f;
            }
        }
        int half = Math.max(1, (nonEmpty + 1) / 2);
        int cum = 0;
        int medianBucket = 0;
        for (int b = 0; b < 14; b++)
        {
            cum += c.hist[b];
            if (cum >= half)
            {
                medianBucket = b;
                break;
            }
        }
        c.dRef = Math.max(1, 1 << medianBucket);
    }

    /**
     * 这朵云在增益 g 下的**精确**期望保留数（逐格求和，不是直方图近似）。
     *
     * <p>{@code u = g·√dRef}，则格子密度 v 的保留数是
     * {@code v ≤ u² ? v : v·(u/√v) = u·√v}。整段只用乘加 + 缓存的 √v，
     * 一盏 1 万格的云的评估成本 ≈ 1 万次乘加。</p>
     */
    private static long expectedKept(ClientCloud c, double gain)
    {
        return expectedKeptU(c, gain * Math.sqrt(c.dRef));
    }

    /** {@link #expectedKept} 的直接版本：调用方已经把 {@code u = g·√dRef} 算好 */
    private static long expectedKeptU(ClientCloud c, double u)
    {
        int cells = c.gx * c.gy * c.gz;
        double tsat = u * u;
        int[] vox = c.vox;
        float[] sq = c.voxSqrt;
        long sum = 0;
        for (int k = 0; k < cells; k++)
        {
            int v = vox[k];
            if (v <= 0)
            {
                continue;
            }
            if (v <= tsat)
            {
                sum += v;
            }
            else
            {
                sum += (long) (u * sq[k] + 0.5);
            }
        }
        return sum;
    }

    /** 所有可见云在增益 g 下的期望保留总数 */
    private long totalExpected(double gain)
    {
        long sum = 0;
        for (ClientCloud c : clouds.values())
        {
            if (c.visible)
            {
                sum += expectedKept(c, gain);
            }
        }
        solveEvals++;
        return sum;
    }

    /**
     * 解全局增益：最大的 g，使"期望保留总数 ≤ 预算"。
     *
     * <p>kept(g) 关于 g 单调递增、分段线性 ⇒ 二分。但**区间必须先验证**：
     * g=1 在实际场景里几乎总是"不可行"的一侧（保留超预算），直接拿
     * [2^-8, 2^8] 二分会在不可行区里一路往上收，最后返回一个"什么都不抽"的
     * 增益——那就是老 bug 的原样复现。所以改成：从 g=1 出发先等比缩小
     * 找到**可行点**，再在 (可行, 不可行] 之间二分；若 g=1 已经可行，
     * 反过来往上抬到仍可行的最大值（也就是"能不抽就不抽"）。</p>
     */
    private double solveGain(int budget)
    {
        solveEvals = 0;
        final long target = budget - (budget / 32L);   // 97%：量化步长 1/32，留一档余量
        double g = 1.0;
        long kept = totalExpected(g);
        if (kept > target)
        {
            // 向下找可行点
            double infeasible = g;
            for (int i = 0; i < 40 && kept > target; i++)
            {
                infeasible = g;
                g *= 0.5;
                kept = totalExpected(g);
            }
            double feasible = g;
            for (int i = 0; i < 12 && infeasible / feasible > 1.01; i++)
            {
                double mid = Math.sqrt(feasible * infeasible);
                if (totalExpected(mid) > target)
                {
                    infeasible = mid;
                }
                else
                {
                    feasible = mid;
                }
            }
            return feasible;
        }
        // g=1 已经可行：往上抬，找到仍可行的最大值（少抽）
        double feasible = g;
        for (int i = 0; i < 40 && totalExpected(feasible * 2.0) <= target; i++)
        {
            feasible *= 2.0;
        }
        double infeasible = feasible * 2.0;
        for (int i = 0; i < 12 && infeasible / feasible > 1.01; i++)
        {
            double mid = Math.sqrt(feasible * infeasible);
            if (totalExpected(mid) > target)
            {
                infeasible = mid;
            }
            else
            {
                feasible = mid;
            }
        }
        return feasible;
    }

    /**
     * 生成保留下标表。
     *
     * <p><b>梯度模型</b>（不是"每格封顶"）：{@code ratio = min(1, g·√(dRef/d))}。
     * 稀疏格（d < dRef）保留比例更高、常常 100%；稠密格抽得多——**密度梯度是保住的**，
     * 不会像 {@code min(d, T)} 那样把所有密集处压平成同一个密度。</p>
     *
     * <p>阈值量化到 1/{@link #THR_LEVELS}（3.1% 步长）：密度小幅漂移时同一颗粒子的
     * 去留不变，掩码不会每步跳一次。<b>每个粒子只用一次哈希</b>（下标哈希，不带随机），
     * 所以"第 i 颗去不去"只取决于 (i, castSeed, 本格阈值)，跨帧稳定。</p>
     */
    private static void buildKeep(ClientCloud c, double gain)
    {
        final int cells = c.gx * c.gy * c.gz;
        final double u = gain * Math.sqrt(c.dRef);
        final double tsat = u * u;
        final int[] vox = c.vox;
        final float[] sq = c.voxSqrt;
        // 计数原地换成阈值（16 位定点，65536 = 全留）
        for (int k = 0; k < cells; k++)
        {
            int v = vox[k];
            if (v <= 0)
            {
                vox[k] = 65536;
                continue;
            }
            if (v <= tsat)
            {
                vox[k] = 65536;
                continue;
            }
            int lvl = (int) (u / sq[k] * THR_LEVELS + 0.5);   // = ratio · LEVELS
            if (lvl < 1)
            {
                lvl = 1;
            }
            else if (lvl > THR_LEVELS)
            {
                lvl = THR_LEVELS;
            }
            vox[k] = lvl * THR_STEP;
        }
        SpellCloud cloud = c.cloud;
        int n = cloud.count;
        if (c.keepIdx == null || c.keepIdx.length < n)
        {
            c.keepIdx = new int[n];
        }
        int[] keep = c.keepIdx;
        int[] cellOf = c.cellOf;
        int kept = 0;
        for (int i = 0; i < n; i++)
        {
            int cell = cellOf[i];
            if (cell < 0)
            {
                continue;   // 被粒子级剔除，本来就不画
            }
            int thr = vox[cell];
            if (thr >= 65536 || hash16(i, cloud.castSeed) < thr)
            {
                keep[kept++] = i;
            }
        }
        c.keepCount = kept;
        // 尺寸补偿按"可见粒子"算：被剔除的那部分不需要补偿
        int denom = Math.max(1, c.visibleCount);
        float ratio = kept == 0 ? 1f : (float) denom / kept;
        c.sizeScale = Math.min(1.8f, (float) Math.sqrt(ratio));
    }

    private static int cellIndex(ClientCloud c, double x, double y, double z)
    {
        int ix = (int) Math.floor((x - c.voxOx) / c.voxSize);
        int iy = (int) Math.floor((y - c.voxOy) / c.voxSize);
        int iz = (int) Math.floor((z - c.voxOz) / c.voxSize);
        if (ix < 0) ix = 0; else if (ix >= c.gx) ix = c.gx - 1;
        if (iy < 0) iy = 0; else if (iy >= c.gy) iy = c.gy - 1;
        if (iz < 0) iz = 0; else if (iz >= c.gz) iz = c.gz - 1;
        return (iz * c.gy + iy) * c.gx + ix;
    }

    /** 粒子下标的稳定哈希（0..65535）：同一颗粒子每步都得到同一个值 ⇒ 去留不闪 */
    private static int hash16(int i, long seed)
    {
        int h = i * 0x9E3779B1 + (int) seed;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0xFFFF;
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
        /**
         * 三个位置缓冲轮转：{@code prev}（正在离开的段起点）/ {@code cur}（段终点，正在画）/
         * {@code next}（等当前段播完才换上的最新快照）。
         *
         * <p>为什么要第三个槽：权威快照的到达时刻相对渲染帧是抖的。没有待用槽就只能二选一——
         * 要么当场换（位置跳一格 = 加速），要么不换还先把 prev 覆盖掉（冻住 = 刹车）。
         * 有了待用槽，**每一段都以恒定的 100 ms 播完**，抖动被吸收在延迟里。</p>
         *
         * <p>镜像模式（多人游戏）下 {@code cur} 直接**指到 cloud 的数组**，不额外拷贝；
         * 直通模式下三个槽都是自己的缓冲（不能指别人的数组，否则会读到撕裂数据）。</p>
         */
        double[] prevX;
        double[] prevY;
        double[] prevZ;
        double[] curX;
        double[] curY;
        double[] curZ;
        float[] prevE;
        float[] curE;
        /** 段内相位（0 = 段起点，1 = 段终点）；略超 [0,1] = 沿段方向线性外推 */
        double phase;

        // ------------------------------------------------ 直通快照环（同进程）
        /**
         * 快照环：{@code RING} 槽，最新槽 = {@code ringHead}，往回数 = 更老。
         *
         * <p><b>为什么是 4 槽而不是 2 槽</b>：播放延迟必须大于"最大到达间隔"（实测 130 ms），
         * 否则播放时刻会越过最新快照（末端断供）。但延迟一大，播放时刻就会落在**上一段
         * 甚至上上段**里，两槽根本取不到那对数据（起点饿死）。只有环能同时满足
         * "延迟够大"和"数据取得到"——这是之前 4 版插值全失败的机械原因。</p>
         */
        static final int RING = 4;
        final double[][] ringX = new double[RING][];
        final double[][] ringY = new double[RING][];
        final double[][] ringZ = new double[RING][];
        final float[][] ringE = new float[RING][];
        /** 每槽对应的全局步号（和播放时间轴同一个计数器） */
        final long[] ringStep = new long[RING];
        int ringHead = -1;
        int ringSize;
        /** 本帧选中的段起点步号（-1 = 还没选过） */
        long pairStep = -1;
        // ------------------------------------------- 播放时间轴（所有直通云共享）
        /**
         * 段长预测（到达间隔 EMA）。每段代表**恒定 100 ms 的仿真时间**，抖的是墙钟到达间隔。
         *
         * <p>所以时间轴按"段"走、不按到达时刻走：同一段不论早到 20 ms 还是晚到 30 ms，
         * 都以同一个预测段长播完——运动速度恒定，抖动全被延迟吸收。</p>
         */
        static double SEG = 1.0e8;
        static final double SEG_MIN = 6.0e7;
        static final double SEG_MAX = 2.5e8;
        /**
         * 播放延迟 = 最近窗口内最大到达间隔 + 余量（升快降慢）。
         *
         * <p>不变式：只要 {@code DELAY > 最大到达间隔}，播放时刻就**永远**落在最新快照之前，
         * 也就永远不会断供。所以上限按段长的倍数给，而不是写死 190 ms——
         * 实测间隔会到 160 ms，写死 190 时被顶死，导致每秒抽一次。</p>
         */
        static double DELAY = 1.6e8;
        static final double DELAY_MIN = 1.2e8;
        static final double DELAY_MARGIN = 4.0e7;
        /** 延迟上限 = 2.5 个段长：环深 4 槽能回溯 3 段，比例 < 3 就取得到那一对 */
        static final double DELAY_RATIO_MAX = 2.5;
        private static final int IV_WIN = 8;
        private static final long[] IV = new long[IV_WIN];
        private static int ivIdx;
        private static int ivFill;
        /**
         * 诊断（不参与逻辑）：最近 16 个**真实**到达间隔的环形样本（纳秒）。
         *
         * <p>窗口比 {@code IV} 大一倍，专供 probe 行算 min/avg/max——
         * 一旦出现"漏抓一整步"的双倍间隔，max 会立刻顶到 200 ms 一档。</p>
         */
        private static final int IV16_WIN = 16;
        private static final long[] IV16 = new long[IV16_WIN];
        private static int iv16Idx;
        private static int iv16Fill;
        static long gStep = -1L;
        /** 已喂过时间轴的最大全局步号（去重判据） */
        static long gSeq = Long.MIN_VALUE;
        /** 派生前沿 = 所有已抓拍云的 baseGlobal + myStep 的最大值（myStep=-1 的云不参与；-1 = 还没有任何云抓拍过）。
         *  伺服前沿改用它：按"实际抓到的步数"推进，一朵云一帧连抓两步（追帧爆发）也如实 +2；
         *  gStep 只剩诊断用途——feedTimeline 的按时间去重会吞步，不再适合当播放前沿。 */
        static long gFront = -1L;
        /** 同一 stepNanos 去重用 */
        static long gArrive;
        // ------------------------------------------------ 匀速播放时钟（水位伺服）
        // 设计：每帧按 rate×dt 匀速推进播放头，一段恒等于 100ms 仿真 ⇒ 段内段间速度恒为
        // rate（≈1×）；rate 由水位伺服驱动，长期严格 1:1（服务端 10 步/s 平均 = 墙钟 1:1，
        // 无累计漂移）。这是 jitter buffer 的标准做法，取代"按实际到达间隔插值"——那种做法
        // 在到达间隔 34~180ms 抖动下，每段速度 = 100/实际间隔 在 0.55×~2.9× 之间跳变，
        // 用户看到的就是"每段末减速、突然变到下一段"。
        /** 播放头位置，单位=步（1 步=100ms 仿真）；哨兵 NEGATIVE_INFINITY 表示未初始化 */
        static double gPlay = Double.NEGATIVE_INFINITY;
        // ------------------------------------------------ 帧级诊断（1 秒窗口内的最大值）
        //
        // 现有计数器都是"秒级采样"，而"位置某一帧被推了一下"完全可能被 1 秒的采样漏掉。
        // 这三个量专治这件事：帧间隔、单帧播放头推进、单云配对跳变。
        /** 本秒内最大的帧间隔（毫秒）。60fps ≈ 16.7；出现 100+ 说明有一帧真的卡住了 */
        static double DBG_DT_MAX_MS;
        /** 本秒内单帧播放头推进的最大值（步）。正常 = rate×dt/100 ≈ 0.17；≈1 就是一帧跳了一整段 */
        static double DBG_STEP_JUMP_MAX;
        /** 本秒内单云配对标号的非 +1 跳变最大值（段）。>1 = 跳过了整整一段数据 */
        static long DBG_PAIR_JUMP_MAX;
        /**
         * 逐帧 dump 剩余行数。三种触发方式：
         * <ul>
         *   <li>手动：{@code -Dwizardmagic.spell.framedump=<行数>}（进游戏就开始打）；</li>
         *   <li><b>自动</b>：某朵云出现"供不上播放头"（{@code lk > 本云最新标号}）时自动武装 180 行
         *       —— 也就是抖动发生的当下，不需要任何开关；</li>
         *   <li>0 = 关。</li>
         * </ul>
         */
        static int fdLeft = Integer.getInteger("wizardmagic.spell.framedump", 0);
        /** 上一帧的 nanoTime（算帧间 dt 用） */
        static long gLastNow;
        /** 目标水位（步）：播放头常驻落后最新快照 1.6 步 ≈ 160ms，到达抖动全靠这段缓冲吸收 */
        static final double W_TARGET = 1.6;
        /** 伺服速率下限：缺水（快照晚到、水位走低）时最多降到 0.85×，宁可慢也不冒进 */
        static final double RATE_MIN = 0.85;
        /** 伺服速率上限：丰水（快照早到攒了余量）时最多升到 1.05×，肉眼不可见 */
        static final double RATE_MAX = 1.05;
        /** 伺服增益：水位偏差（步）映射到速率修正的比例 */
        static final double GAIN = 0.3;
        /** 播放头落后硬锚阈值（步）：落后超过 3.2 步即掉出 4 槽环覆盖，直接锚回目标水位 */
        static final double W_MAX = 3.2;
        // 本帧全部可见云都走直通（决定 uPartial 用播放时钟还是 partialTick）
        static boolean LINK_ALL;
        /** 播放时钟本帧可用（时间轴已铺开）。它才是"端点烘焙 + 相位来源"的唯一判据：
         *  端点按整段 0→1 烘焙的云必须配播放时钟相位，不能用 partialTick。 */
        static boolean LINK_READY;
        /** 本帧 shader 用的 uPartial（段内相位） */
        static double LINK_U;
        /** 外推上限（相位 > 1 = 沿段方向线性外推；0.4 段 ≈ 40 ms） */
        static final double U_MAX = 1.4;
        /** 播放时刻越过最新快照的帧数（诊断：正常应恒为 0） */
        static long LINK_STARVED;        /** applyPair 扫描中撞上环内相邻槽步号断档的次数（诊断：中间那步被漏抓/撕裂的频率，正常应恒为 0） */
        static long LINK_DISCONT;
        /** applyPair 兜底选中的段超前于播放头的次数（诊断：画面瞬间前跳再回跳的坏情况，正常应恒为 0） */
        static long LINK_AHEAD;
        /** captureNext 单次抓拍跨过的服务端步数-1 的累计（诊断：真正的漏抓计数，正常应恒为 0。
         *  ringStep 是时间轴计数、构造上永远连续，discont 量不到漏抓，只有 epoch 差能证明） */
        static long LINK_EPOCH_SKIP;
        /** 撕裂探针计数（照收不拒绝）：epoch 一致但 writeSeq 前后不一致或为奇数 ⇒ 拷贝
         *  跨越了服务端写窗口，疑似撕裂。“撕裂导致抖动”假设已被 2026-11-19 实测否决
         * （torn=0 时抖动仍复现），字段留作诊断仪器。 */
        static long LINK_TORN;
        final float birthTicks;
        /** 是否已经打印过"走字节码还是解释器" */
        boolean loggedPath;

        // ---------------------------------------------------- 渲染预算（每步重建）
        /** 粒子包围盒 minX/minY/minZ/maxX/maxY/maxZ，在 copyPrev 里顺带算（零额外遍历） */
        final double[] bounds = new double[6];
        /** 上一物理步是否落在视锥内 */
        boolean visible = true;
        /**
         * 保留粒子下标（紧凑数组）；{@code null} = 一个都不抽（预算够 / 稀疏散布）。
         *
         * <p>用"紧凑下标表"而不是布尔掩码，是因为写入循环可以直接
         * {@code for (k = 0; k < keepCount; k++)}，成本只和**保留数**成正比。</p>
         */
        int[] keepIdx;
        int keepCount;
        /** 抽稀后的尺寸补偿（面积×数量守恒） */
        float sizeScale = 1f;
        /** 上一轮计划时粒子级剔除后剩余的粒子数（fill 用它算限流闸门） */
        int visibleCount;

        // ------------------------------------------------------ 同进程直通（单人游戏）
        /** 能直接读到权威云时非 null：物理由服务端跑，本地只拷数组 */
        SpellCloud link;
        /** 已经尝试过并且失败的解链（避免每步都去查一遍） */
        boolean linkFailed;
        /** 上一次从权威云拷过来的 epoch（判"有没有新的一步"） */
        int linkEpoch = -1;
        /** 连续多少次 seqlock 校验失败（诊断：应该是 0） */
        int linkMisses;
        /** 本云的抓拍计数（每成功抓拍 +1；-1 = 还没抓过）。
         *  环槽标号 ringStep 用它而不用共享 gStep：构造上每次抓拍必然 +1、与 feedTimeline
         *  的按时间去重彻底解耦——去重吞掉 gStep++ 时环里也不再出现两个同号槽（段边界一帧瞬移的根因）。 */
        long myStep = -1L;
        /** 本云首个快照对应的全局步号（全局 = 派生前沿 gFront 的坐标系）；
         *  环槽局部号 + baseGlobal = 全局号，applyPair 靠它把全局播放段号换算回本云局部号 */
        long baseGlobal = 0L;
        /** 本云累计因"一帧跨两步"而丢掉的步数（全局 skip 只是求和，这里分云——多云随机抖动的第一嫌疑） */
        long epochSkips;
        /** 播放头已经走到本云**最新快照之外**的帧数：本云供不上，旧对会被冻住而 u 继续绕圈
         *  ⇒ 位置在同一段内来回扫 = 视觉上的"方向瞬间倒退"。这是现有计数器唯一的盲区。 */
        long holdFrames;

        // 体素密度网格（复用，避免每步分配）
        int[] vox;
        /** 每格 √v（求根时 kept = Σ u·√v，避免反复开方）；空格的值为 0 */
        float[] voxSqrt;
        /** 每颗粒子所属的格号（体素化时算一次，建表时直接查，不再重算坐标） */
        int[] cellOf;
        final int[] hist = new int[14];
        /** 这朵云的"典型密度"（非空体素计数的中位数，2 的幂）—— 梯度模型的分母基准 */
        int dRef = 1;
        int gx;
        int gy;
        int gz;
        double voxOx;
        double voxOy;
        double voxOz;
        double voxSize;

        ClientCloud(SpellCloud cloud)
        {
            this.cloud = cloud;
            int n = cloud.count;
            prevX = new double[n];
            prevY = new double[n];
            prevZ = new double[n];
            prevE = new float[n];
            // 一开始 cur 直接指到云自己的数组（镜像模式就地生效），
            // 一旦直通就换成自有三缓冲（见 switchToLinked）
            curX = cloud.rawX();
            curY = cloud.rawY();
            curZ = cloud.rawZ();
            curE = cloud.rawEnergy();
            keepCount = n;              // 第一次 plan 之前先按"不抽稀"渲染，别让新云空一帧
            visibleCount = n;           // 同上：剔除统计也就位之前按"全可见"算，保守
            copyPrev();
            this.birthTicks = SparkBatch.nowTicks();
        }

        void copyPrev()
        {
            double loX = Double.MAX_VALUE;
            double loY = Double.MAX_VALUE;
            double loZ = Double.MAX_VALUE;
            double hiX = -Double.MAX_VALUE;
            double hiY = -Double.MAX_VALUE;
            double hiZ = -Double.MAX_VALUE;
            for (int i = 0; i < cloud.count; i++)
            {
                double x = curX[i];
                double y = curY[i];
                double z = curZ[i];
                prevX[i] = x;
                prevY[i] = y;
                prevZ[i] = z;
                if (x < loX) loX = x;
                if (x > hiX) hiX = x;
                if (y < loY) loY = y;
                if (y > hiY) hiY = y;
                if (z < loZ) loZ = z;
                if (z > hiZ) hiZ = z;
            }
            for (int i = 0; i < cloud.count; i++)
            {
                prevE[i] = curE[i];
            }
            recomputeBounds(loX, loY, loZ, hiX, hiY, hiZ);
        }

        private void recomputeBounds(double loX, double loY, double loZ,
                                     double hiX, double hiY, double hiZ)
        {
            if (loX <= hiX)
            {
                bounds[0] = loX;
                bounds[1] = loY;
                bounds[2] = loZ;
                bounds[3] = hiX;
                bounds[4] = hiY;
                bounds[5] = hiZ;
            }
            else
            {
                bounds[0] = bounds[3] = cloud.originX;
                bounds[1] = bounds[4] = cloud.originY;
                bounds[2] = bounds[5] = cloud.originZ;
            }
        }

        /**
         * 尝试连上服务端那朵权威云。
         *
         * <p>单人游戏里服务端和客户端同 JVM，{@link SpellCloudManager#authoritative(int)} 直接
         * 返回服务端对象——不序列化、不发包。校验 seed/count/元素，任何一个不匹配就放弃
         * （宁可退回自己跑 VM，也不画错东西）。失败后不再重试。</p>
         *
         * @return true = 本步之后由权威方提供状态，不要再跑本地 VM
         */
        boolean tryLink()
        {
            if (link != null)
            {
                // 权威云被销毁（id 不再出现）→ 解链，退回镜像
                if (SpellCloudManager.authoritative(cloud.id) == null)
                {
                    link = null;
                    return false;
                }
                return true;
            }
            if (linkFailed)
            {
                return false;
            }
            SpellCloud src = SpellCloudManager.authoritative(cloud.id);
            if (src == null || src == cloud)
            {
                return false;
            }
            if (src.count != cloud.count || src.castSeed != cloud.castSeed
                    || src.elementId != cloud.elementId)
            {
                linkFailed = true;
                LOGGER.warn("[wizardmagic] mirror link rejected cloud={} count={}/{} seed={}/{} el={}/{}",
                        cloud.id, cloud.count, src.count, cloud.castSeed, src.castSeed,
                        cloud.elementId, src.elementId);
                return false;
            }
            link = src;
            switchToLinked();
            return true;
        }

        /**
         * 从权威云抓一步进快照环（**不换正在画的那份**）。
         *
         * <p>seqlock：读前读后各取一次 stepEpoch，相等才算拿到一致状态（拷贝期间服务端
         * 没有步进），否则重试（最多 3 次），都撞上就等下一 tick。另有 writeSeq 探针
         * 计数（照收不拒绝）：epoch 相等但 writeSeq 前后不一致或为奇数 ⇒ 疑似撕裂，
         * 只累计 LINK_TORN，快照照收。2026-11-19 实测否决了“撕裂导致抖动”假设
         * （torn=0 时抖动仍复现），拒绝重试版已回退为本探针版。</p>
         */
        void captureNext()
        {
            SpellCloud src = link;
            if (src == null)
            {
                return;
            }
            if (src.stepEpoch() == linkEpoch)
            {
                return;                  // 这一步还没跑，环里不用加槽
            }
            final int n = cloud.count;
            final int slot = (ringHead + 1) % RING;
            long arrive = 0L;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                // 拷贝开始前先读一次写序列号（奇数 = 服务端正在写入）和步进纪元。
                int w1 = src.writeSeq();
                int e1 = src.stepEpoch();
                System.arraycopy(src.rawX(), 0, ringX[slot], 0, n);
                System.arraycopy(src.rawY(), 0, ringY[slot], 0, n);
                System.arraycopy(src.rawZ(), 0, ringZ[slot], 0, n);
                System.arraycopy(src.rawEnergy(), 0, ringE[slot], 0, n);
                int e2 = src.stepEpoch();
                if (e1 == e2)
                {
                    int w2 = src.writeSeq();
                    if (w1 != w2 || (w1 & 1) != 0)
                    {
                        LINK_TORN++;   // 探针：拷贝跨越了写窗口 ⇒ 疑似撕裂。假设已被实测否决（撕裂时抖动不存在），
                                       // 只计数照收，供未来诊断参考。
                    }
                    // epoch 差 = 这一次抓拍跨过的服务端步数（必须在 linkEpoch 被覆盖之前算）。
                    // ringStep 是时间轴计数、构造上永远连续，discont 量不到漏抓，
                    // 只有 epoch 差能证明漏抓，所以用它累计真正的漏抓计数。
                    long delta = e2 - linkEpoch;
                    if (delta > 1L)
                    {
                        // 这一次抓拍跨过了 delta-1 步 = 之前的抓拍网格漏掉了这么多步
                        LINK_EPOCH_SKIP += delta - 1L;
                        // 分云口径：多云时"哪朵云在丢步"才是随机的那个量
                        epochSkips += delta - 1L;
                    }
                    linkEpoch = e2;
                    // stepNanos 在 epoch 之前写；e1==e2 才能保证它属于这一份数据
                    arrive = src.stepNanos();
                    long seq = src.stepSeq();
                    ringHead = slot;
                    if (ringSize < RING)
                    {
                        ringSize++;
                    }
                    feedTimeline(arrive, seq);
                    // 环槽标号 = **服务端全局步号**（同一步所有云同号）。
                    //
                    // 以前用"本云被抓拍了几次"（myStep）+ 首抓锚一个 baseGlobal = gFront+1：
                    // 后放的云锚得比老云高 1 步，于是 N 朵云同场时每朵云渲染的真实步号相差
                    // 1..N-1 步（实测 4 朵云：off = 3/2/1/0）。一旦播放头领先某朵云的数据，
                    // applyPair 只能把配对钉在它最新的一段上，而相位继续按 100ms 扫 →
                    // 那朵云的画面就"向前冲一段、再向后弹回"，约 10 次/秒。
                    // 用全局步号后 baseGlobal 恒 0、off 恒 0，这类分叉在构造上不存在。
                    myStep = seq;
                    baseGlobal = 0L;
                    ringStep[slot] = seq;
                    // 包围盒跟着新快照重算（渲染预算/剔除用）
                    recomputeBoundsOf(ringX[slot], ringY[slot], ringZ[slot]);
                    // 本地只负责"到点消失"：年龄跟着走，isDead() 才能和权威方同时生效。
                    // 漏抓步时云的年龄也要跟上服务端（按跨过步数补齐），否则本地云活得比权威方久。
                    // 补的次数钳到 [1,4]：下限保证至少推进一次；上限防首抓（linkEpoch 初值为 -1，
                    // delta 异常大）或极端情况一次补太多。
                    int bumps = (int) Math.max(1L, Math.min(4L, delta));
                    for (int i = 0; i < bumps; i++)
                    {
                        cloud.bumpAge();
                    }
                    return;
                }
                // e1 != e2：拷贝期间 epoch 变了（原 seqlock 语义），落到这里重试。
            }
            linkMisses++;
        }

        /** 切到直通模式：分配快照环。还没攒到 2 槽之前，fill 继续用旧的 prev/cur（云的数组） */
        void switchToLinked()
        {
            int n = cloud.count;
            for (int i = 0; i < RING; i++)
            {
                ringX[i] = new double[n];
                ringY[i] = new double[n];
                ringZ[i] = new double[n];
                ringE[i] = new float[n];
            }
            ringHead = -1;
            ringSize = 0;
            pairStep = -1;
            phase = 0.0;
            // 抓拍计数一并复位（-1 = 待首抓）；baseGlobal 会在下次首抓时重新锚定
            myStep = -1L;
        }

        /**
         * 把全局段号 {@code k} 映射到本朵云的环槽，并把 prev/cur 指向那一对。
         *
         * <p>只在段号真的变了才换引用——所以实例表只会在**段边界**重建，
         * 段内每帧只更新 {@code uPartial} 一个 uniform。</p>
         *
         * @return true = 段号变了（实例表需要重建）
         */
        boolean applyPair(long k)
        {
            if (ringSize < 2 || ringHead < 0)
            {
                pairStep = -1;
                return false;
            }
            // k 是全局播放段号（gFront 坐标系），环槽标号 ringStep 是本云局部号（myStep 域），
            // 差值就是 baseGlobal——先换算成本云局部号，之后的扫描/比较全在局部坐标系里。
            long lk = k - baseGlobal;
            // 诊断：播放头已经走到本云**最新快照之外**。这一刻本云供不上，选中的旧对会被冻住，
            // 而 u 仍按墙钟 0→1 绕圈 ⇒ 位置在同一段内来回扫（视觉 = 方向瞬间倒退）。
            // 这条路径在 starved/ahead/discont 里都是静默的，所以必须单独计数。
            if (lk > ringStep[ringHead])
            {
                holdFrames++;
                // 零配置取证：抖动发生的当下自动开逐帧 dump（3 秒），下次复现就能拿到逐帧数据
                if (fdLeft <= 0)
                {
                    fdLeft = 180;
                }
            }
            // 从最新往回找一对**步号连续**的槽，取包含 k 的那一段；找不到就退到最老的一段。
            // （步号不连续 = 中间那一步被 seqlock 丢了，这种不能当段用，否则位置会跳一格）
            int bestA = -1;
            int bestB = -1;
            for (int i = 0; i + 1 < ringSize; i++)
            {
                int b = (int) (((long) ringHead - i + 2L * RING) % RING);
                int a = (int) (((long) ringHead - i - 1L + 2L * RING) % RING);
                if (ringStep[b] != ringStep[a] + 1L)
                {
                    // H1 取证（只计数不改行为）：这一对相邻槽步号断档 = 中间那步被漏抓/撕裂。
                    LINK_DISCONT++;
                    continue;
                }
                bestA = a;
                bestB = b;
                if (ringStep[a] <= lk)
                {
                    break;
                }
            }
            if (bestA < 0)
            {
                pairStep = -1;
                return false;
            }
            // 兜底禁止超前段：环里没有能覆盖播放头的连续对时，宁可短暂静止也不画超前的位置——
            // 超前段会在下个正确快照到达时造成向后跳变，静止的观感代价小得多。
            // 直接 return false：维持当前 prevX/curX 对不变，不切换指针、不触发重建。
            if (ringStep[bestA] > lk)
            {
                LINK_AHEAD++;
                return false;
            }
            long start = ringStep[bestA];
            if (start == pairStep)
            {
                return false;
            }
            // 帧级诊断：配对一次前进超过 1 段 = 中间有段数据被跳过了（画面会瞬间冲一下）
            if (pairStep >= 0L && start - pairStep > DBG_PAIR_JUMP_MAX)
            {
                DBG_PAIR_JUMP_MAX = start - pairStep;
            }
            prevX = ringX[bestA];
            prevY = ringY[bestA];
            prevZ = ringZ[bestA];
            prevE = ringE[bestA];
            curX = ringX[bestB];
            curY = ringY[bestB];
            curZ = ringZ[bestB];
            curE = ringE[bestB];
            pairStep = start;
            return true;
        }

        /** 扫一份快照算包围盒（每步一次，和旧实现的成本一样） */
        private void recomputeBoundsOf(double[] xs, double[] ys, double[] zs)
        {
            double loX = Double.MAX_VALUE;
            double loY = Double.MAX_VALUE;
            double loZ = Double.MAX_VALUE;
            double hiX = -Double.MAX_VALUE;
            double hiY = -Double.MAX_VALUE;
            double hiZ = -Double.MAX_VALUE;
            for (int i = 0, n = cloud.count; i < n; i++)
            {
                double x = xs[i];
                double y = ys[i];
                double z = zs[i];
                if (x < loX) loX = x;
                if (x > hiX) hiX = x;
                if (y < loY) loY = y;
                if (y > hiY) hiY = y;
                if (z < loZ) loZ = z;
                if (z > hiZ) hiZ = z;
            }
            recomputeBounds(loX, loY, loZ, hiX, hiY, hiZ);
        }

        /**
         * 收到一步新的权威状态：记下它的**真实到达时刻**，并更新段长/间隔统计（纯诊断）。
         *
         * <p>去重从"时间戳容差（0.25×SEG≈25ms）"改成**全局步号**：同一步里 N 朵云各自
         * {@code stepNanos} 相差可达毫秒级，容差判据既会漏（雨后追帧时真步被吞，gStep 少走）
         * 也会重（跨云偏差超过容差时同一步喂两次）。步号是精确的整数判据，两头都不会错。</p>
         */
        private static void feedTimeline(long arrive, long seq)
        {
            if (seq <= gSeq)
            {
                return;                  // 同一步（或更旧）已经喂过
            }
            gSeq = seq;
            if (gStep < 0L)
            {
                gStep = 0L;
                gArrive = arrive;
                return;
            }
            long interval = arrive - gArrive;
            if (interval <= 0L)
            {
                return;
            }
            double sample = Math.max(SEG_MIN, Math.min(SEG_MAX, interval));
            SEG += (sample - SEG) * 0.25;
            IV[ivIdx] = interval;
            ivIdx = (ivIdx + 1) % IV_WIN;
            if (ivFill < IV_WIN)
            {
                ivFill++;
            }
            // H1 取证（不参与逻辑）：同步记一份更大窗口的样本给 probe 行算 min/avg/max。
            // 走到这里的一定是去重后通过的新间隔（interval <= 0 的 return 路径不会进来）。
            IV16[iv16Idx] = interval;
            iv16Idx = (iv16Idx + 1) % IV16_WIN;
            if (iv16Fill < IV16_WIN)
            {
                iv16Fill++;
            }
            long max = 0L;
            for (int i = 0; i < ivFill; i++)
            {
                max = Math.max(max, IV[i]);
            }
            // 延迟必须 > 最大间隔（这是"永不断供"的充要条件），同时按段长成比例，
            // 这样 SEG 估小时也不会让播放速率快于数据到达。
            double want = Math.max(max + DELAY_MARGIN, SEG * 1.7);
            want = Math.max(DELAY_MIN, Math.min(DELAY_RATIO_MAX * SEG, want));
            // 延迟只允许缓慢变化：跳变会让 R=now-DELAY 前后跳，画面会顿一下或倒退。
            DELAY += (want - DELAY) * (want > DELAY ? 0.3 : 0.02);
            gStep++;
            // 注：原 gT（每步真实到达时刻环）已随 segmentOf 一并删除，播放不再依赖到达时刻
            gArrive = arrive;
        }

        /**
         * 诊断（不参与逻辑）：最近 16 个到达间隔的 min/avg/max（毫秒，斜杠三段式）。
         *
         * <p>正常应稳定在约 100/100/100；出现双倍间隔（漏抓一整步）时 max 会顶到
         * 200 ms 一档、avg 被拉高——这就是 H1 的直接证据。</p>
         */
        private static String iv16StatsMs()
        {
            if (iv16Fill == 0)
            {
                return "-/-/-";
            }
            long min = Long.MAX_VALUE;
            long max = 0L;
            long sum = 0L;
            for (int i = 0; i < iv16Fill; i++)
            {
                long v = IV16[i];
                min = Math.min(min, v);
                max = Math.max(max, v);
                sum += v;
            }
            long avgMs = sum / iv16Fill / 1_000_000L;
            return (min / 1_000_000L) + "/" + avgMs + "/" + (max / 1_000_000L);
        }

        /**
         * 匀速播放时钟推进一帧：返回播放头所在的段号，段内相位写进 {@code outPhase[0]}。
         *
         * <p>取代旧的"按真实到达间隔插值"：播放头 {@code gPlay} 以 rate×dt 匀速推进，
         * 一段恒等于 100ms 仿真 ⇒ 段内段间速度恒为 rate（≈1×）；rate 由水位伺服驱动
         * （{@code w = gFront - gPlay} 对 W_TARGET 的偏差，钳在 [RATE_MIN, RATE_MAX]），
         * 长期严格 1:1 无累计漂移——jitter buffer 的标准做法。</p>
         *
         * <p>{@code u} 可能超过 1（外推区，最大 1.4）：断供夹把播放头按住在 gFront+0.4，
         * 与 U_MAX=1.4 对齐（pair (gFront-1,gFront) 的 u 到 1.4）；spellPartial 里已有钳制，
         * 这里不重复夹。</p>
         */
        static long advancePlayback(long nowNanos, double[] outPhase)
        {
            if (gFront < 2L)
            {
                // 时间轴还没铺开（不足两步快照）：沿用原"退回 tick 相位"语义
                outPhase[0] = 0.0;
                return -1L;
            }
            if (gPlay == Double.NEGATIVE_INFINITY)
            {
                // 首次初始化：跳到目标水位才开始播放（前几帧本来就没画，不存在跳变）
                gPlay = gFront - W_TARGET;
                gLastNow = nowNanos;
            }
            // 帧间 dt（毫秒）；首次初始化后 dt=0，本帧不推进
            double dtMs = (nowNanos - gLastNow) / 1.0e6;
            gLastNow = nowNanos;
            if (dtMs > DBG_DT_MAX_MS)
            {
                DBG_DT_MAX_MS = dtMs;
            }
            // 水位伺服：水位偏离 W_TARGET 时按比例修正速率（钳在 [RATE_MIN, RATE_MAX]）；
            // 前沿用派生 gFront（实际抓到的步数），与 feedTimeline 去重是否吞步无关
            double w = gFront - gPlay;
            double rate = 1.0 + Math.max(RATE_MIN - 1.0, Math.min(RATE_MAX - 1.0, (w - W_TARGET) * GAIN));
            double advance = rate * dtMs / 100.0;
            if (advance > DBG_STEP_JUMP_MAX)
            {
                DBG_STEP_JUMP_MAX = advance;   // 单帧推进超过 ~0.3 步 = 那一帧位置被推了一下
            }
            gPlay += advance;
            // 断供夹：播放头越过最新快照 0.4 步就按住（0.4 与 U_MAX=1.4 对齐：pair (gFront-1,gFront) 的 u 到 1.4）
            if (gPlay > gFront + 0.4)
            {
                gPlay = gFront + 0.4;
                LINK_STARVED++;
            }
            // 落后硬锚：极罕见（暂停/长时间掉帧后播放头掉出环覆盖），硬锚一次比逐帧 ahead+冻结好
            if (gPlay < gFront - W_MAX)
            {
                gPlay = gFront - W_TARGET;
            }
            long k = (long) Math.floor(gPlay);
            if (k > gFront - 1L)
            {
                // 外推区落在最新一段上
                k = gFront - 1L;
            }
            outPhase[0] = gPlay - k;
            return k;
        }
    }
}
