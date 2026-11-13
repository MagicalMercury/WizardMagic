package com.wizardmagic.core.spell;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.core.spell.SpellRandom;
import com.wizardmagic.core.spell.script.EmitSpec;
import com.wizardmagic.core.spell.script.SpellScript;
import com.wizardmagic.core.spell.script.Value;
import com.wizardmagic.common.spell.LevelSolidOracle;
import com.wizardmagic.common.spell.McSpellWorld;
import com.wizardmagic.common.network.ModNetwork;
import com.wizardmagic.common.network.SpellCloudRemovePayload;
import com.wizardmagic.common.network.SpellCloudSnapshotPayload;
import com.wizardmagic.common.network.SpellCloudSpawnPayload;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 法术云管理器：每维度一份云列表，负责准入、推进、销毁与**按玩家可见性**同步。
 *
 * <p>两个池是分开的：</p>
 * <ul>
 *   <li><b>服务端模拟池</b>（{@link SpellConstants#MAX_SIM_PARTICLES_PER_LEVEL}）：
 *       决定一个维度能同时模拟多少粒子，常驻云（法阵/结界）即使没人看也继续推进；</li>
 *   <li><b>客户端渲染池</b>（{@code SparkBatch.MAX_DRAW}）：只决定看得见的部分画多少，
 *       不参与服务端准入。</li>
 * </ul>
 *
 * <p>同步规则：每 {@link SpellConstants#VISIBILITY_SYNC_TICKS} tick 核对一次
 * 「玩家 ↔ 云」的可见关系——进入范围时补发（新云发生成包、进行中的云发状态快照），
 * 离开范围时发移除包。这样走远再回来的玩家也能看到仍在运行的常驻效果。</p>
 */
public final class SpellCloudManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WizardMagic.MODID + "-spell");

    /** 可见性状态：半可见（只发生成包，客户端按距离淡出） */
    public static final int VIS_SEMI = 1;
    /** 可见性状态：完整可见（进入时发状态快照） */
    public static final int VIS_FULL = 2;

    /** 维度 → 云列表（只在服务端主线程访问） */
    private static final Map<ResourceKey<Level>, List<SpellCloud>> CLOUDS = new HashMap<>();
    /** 维度 → 玩家 UUID → 云 id → 可见性状态（{@link #VIS_SEMI} / {@link #VIS_FULL}） */
    private static final Map<ResourceKey<Level>, Map<UUID, Map<Integer, Integer>>> VISIBLE = new HashMap<>();

    private static int nextCloudId = 1;

    /**
     * 整批一步超过这个时间就打一条 WARN。
     *
     * <p>10 Hz 的一步是 **100 ms**，所以 8 ms = 8% 的预算。以前这里是 3 ms、注释还写成
     * "30% 的预算"（按 10 ms 窗口算才会是 30%），于是 28 万粒时每步都打一条、一条日志占了
     * 全部日志的 75%。现在阈值 8 ms + 最长每秒一条。</p>
     */
    private static final long SLOW_STEP_NANOS = 8_000_000L;
    /** slow step 日志的节流间隔（毫秒）：再慢也只每秒一条 */
    private static final long SLOW_LOG_INTERVAL_MS = 1000L;
    private static long lastSlowLogMs;

    /** 最近一次准入被拒的原因（单线程，命令直接读） */
    private static String lastReject = "";

    // ---- 统计：最近 256 步的耗时（ns），环形采样 ----
    private static final int SAMPLE_SIZE = 256;
    private static final long[] STEP_SAMPLES = new long[SAMPLE_SIZE];
    private static int sampleCount;
    private static int sampleCursor;
    private static long lastStepNanos;

    private SpellCloudManager()
    {
    }

    // ------------------------------------------------------------------ 生成

    /** 用默认阻尼/能量生成一朵一次性点云；失败返回 null，原因见 {@link #lastReject()}。 */
    public static SpellCloud spawn(ServerLevel level, int elementId, int amount, Vec3 origin, long seed, float lifeSec)
    {
        return spawn(level, elementId, amount, origin, seed, lifeSec,
                Shapes.POINT.id(), Shapes.POINT.defaults(),
                SpellConstants.DEFAULT_DRAG, SpellConstants.DEFAULT_ENERGY,
                SpellConstants.DEFAULT_ENERGY_DECAY, false);
    }

    /**
     * 完整生成入口：形状 + 基础定律参数 + 是否常驻。
     *
     * @param shapeId     形状 id（见 {@link Shapes}）
     * @param shapeParams 形状参数
     * @param drag        线性阻尼（1/s）
     * @param persistent  常驻云（法阵 / 结界）：静止、不自然死亡
     */
    public static SpellCloud spawn(ServerLevel level, int elementId, int amount, Vec3 origin, long seed, float lifeSec,
                                   String shapeId, ShapeParams shapeParams,
                                   float drag, float energyInit, float energyDecay, boolean persistent)
    {
        return spawn(level, elementId, amount, origin, seed, lifeSec, shapeId, shapeParams,
                drag, energyInit, energyDecay, persistent, 0.0, 1.0, 0.0);
    }

    /** 同上，再带形状朝向（{@code emit} 造"不继承脚本"的子云时用） */
    public static SpellCloud spawn(ServerLevel level, int elementId, int amount, Vec3 origin, long seed, float lifeSec,
                                   String shapeId, ShapeParams shapeParams,
                                   float drag, float energyInit, float energyDecay, boolean persistent,
                                   double faceX, double faceY, double faceZ)
    {
        lastReject = "";
        if (elementId < 0 || elementId >= SpellElements.count())
        {
            lastReject = "未知元素下标 " + elementId;
            return null;
        }
        if (amount < SpellConstants.MIN_CLOUD_PARTICLES || amount > SpellConstants.MAX_CLOUD_PARTICLES)
        {
            lastReject = "粒子数必须在 " + SpellConstants.MIN_CLOUD_PARTICLES
                    + ".." + SpellConstants.MAX_CLOUD_PARTICLES + " 之间";
            return null;
        }
        List<SpellCloud> list = list(level);
        if (list.size() >= SpellConstants.MAX_CLOUDS_PER_LEVEL)
        {
            lastReject = "本维度云数已达上限 " + SpellConstants.MAX_CLOUDS_PER_LEVEL;
            return null;
        }
        if (persistent && anchoredCount(level) >= SpellConstants.MAX_ANCHORED_CLOUDS_PER_LEVEL)
        {
            lastReject = "本维度常驻云数已达上限 " + SpellConstants.MAX_ANCHORED_CLOUDS_PER_LEVEL;
            return null;
        }
        int total = particleCount(level);
        if (total + amount > SpellConstants.MAX_SIM_PARTICLES_PER_LEVEL)
        {
            lastReject = "本维度模拟粒子将超过 " + SpellConstants.MAX_SIM_PARTICLES_PER_LEVEL
                    + "（当前 " + total + "，请求 " + amount + "）";
            return null;
        }

        SpellCloud cloud = new SpellCloud(nextCloudId++, seed, elementId, amount,
                origin.x, origin.y, origin.z, shapeId, shapeParams,
                lifeSec, drag, energyInit, energyDecay, persistent, faceX, faceY, faceZ);
        ParticleCollider collider = LevelSolidOracle.collideFor(level, elementId);
        cloud.setCollider(collider);
        cloud.setServerSide(true);
        cloud.spawnGameTime = level.getGameTime();
        list.add(cloud);
        // 立即把新云推给范围内的玩家，不等到下一个同步周期
        syncVisibility(level);
        LOGGER.info("[wizardmagic] spell spawn cloud={} element={} particles={} seed={} life={}s persistent={} collision={} at {} {} {}",
                cloud.id, SpellElements.id(elementId), cloud.count, seed, cloud.lifeSec, persistent,
                collisionPath(collider),
                String.format("%.2f", origin.x), String.format("%.2f", origin.y), String.format("%.2f", origin.z));
        return cloud;
    }

    /** 日志用：这朵云走哪条碰撞填充路径（{@code box} 只在调试开关下出现） */
    private static String collisionPath(ParticleCollider collider)
    {
        if (collider == null)
        {
            return "none";
        }
        return collider.fill() == ParticleCollider.Fill.BOX ? "box" : "lazy";
    }

    /**
     * 脚本云：行为由 {@code script} 的 {@code on tick} 决定，{@code emit} 的参数已经
     * 求值成 {@link EmitSpec}。准入规则与旧路径完全一致（元素、粒子数、云数、模拟池）。
     *
     * @param caster 施法者（伤害时跳过自己；{@code null} = 无主，比如命令台或者子云丢了施法者）
     */
    public static SpellCloud spawnScripted(ServerLevel level, SpellScript script, EmitSpec spec,
                                           long seed, Map<String, Value> vars, UUID caster)
    {
        lastReject = "";
        if (spec.elementId() < 0 || spec.elementId() >= SpellElements.count())
        {
            lastReject = "未知元素下标 " + spec.elementId();
            return null;
        }
        if (spec.count() < SpellConstants.MIN_CLOUD_PARTICLES || spec.count() > SpellConstants.MAX_CLOUD_PARTICLES)
        {
            lastReject = "粒子数必须在 " + SpellConstants.MIN_CLOUD_PARTICLES
                    + ".." + SpellConstants.MAX_CLOUD_PARTICLES + " 之间";
            return null;
        }
        List<SpellCloud> list = list(level);
        if (list.size() >= SpellConstants.MAX_CLOUDS_PER_LEVEL)
        {
            lastReject = "本维度云数已达上限 " + SpellConstants.MAX_CLOUDS_PER_LEVEL;
            return null;
        }
        int total = particleCount(level);
        if (total + spec.count() > SpellConstants.MAX_SIM_PARTICLES_PER_LEVEL)
        {
            lastReject = "本维度模拟粒子将超过 " + SpellConstants.MAX_SIM_PARTICLES_PER_LEVEL
                    + "（当前 " + total + "，请求 " + spec.count() + "）";
            return null;
        }

        // 正常施法：生成时就把寄存器机编好，之后一步都不退回解释器。
        // 编不出来 = 脚本用了快速路径不支持的写法（顶层 if、tick 内 emit、rand …），
        // 那朵云只能走解释器——实战里应当避免，所以这里告警而不是静默。
        if (!script.prepareFastPath(vars))
        {
            LOGGER.warn("[wizardmagic] 法术 {} 编不出寄存器机，这朵云会退回解释器：{}",
                    script.id, script.fastPathReason());
        }

        SpellCloud cloud = new SpellCloud(nextCloudId++, seed, spec.elementId(), spec.count(),
                spec.x(), spec.y(), spec.z(), spec.lifeSec(), spec.shapeId(), spec.params(),
                script, vars, spec.faceX(), spec.faceY(), spec.faceZ());
        ParticleCollider collider = LevelSolidOracle.collideFor(level, spec.elementId());
        cloud.setCollider(collider);
        // 伤害出口只在服务端挂：客户端跑同一份脚本，但 hurt() 什么也不做
        cloud.setWorld(new McSpellWorld(level, caster));
        cloud.setCasterId(caster);
        cloud.spawnGameTime = level.getGameTime();
        // 服务端才允许在 tick 里生成子云（客户端等服务器下发）
        cloud.setEmitsEnabled(true);
        cloud.setServerSide(true);
        list.add(cloud);
        syncVisibility(level);
        LOGGER.info("[wizardmagic] spell script={} cloud={} element={} particles={} seed={} life={}s srcLen={} scriptSet={} collision={} at {} {} {}",
                script.id, cloud.id, SpellElements.id(spec.elementId()), cloud.count, seed, cloud.lifeSec,
                script.source.length(), cloud.script != null, collisionPath(collider),
                String.format("%.2f", spec.x()), String.format("%.2f", spec.y()),
                String.format("%.2f", spec.z()));
        return cloud;
    }

    // ------------------------------------------------------------------ 推进

    /**
     * 每 {@link SpellConstants#STEP_TICKS} tick 推进一步（由 {@code SpellServerEvents} 调用）。
     *
     * <p>这里只做「区块还在不在」「谁死了」这类串行杂务；真正的推进交给 {@link SpellBatch}
     * ——它把所有云的粒子区间拼成一条，一次并行调度（不是每朵云各 fork/join 一次）。</p>
     */
    public static void tick(ServerLevel level)
    {
        if (level.getGameTime() % SpellConstants.STEP_TICKS != 0)
        {
            return;
        }
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        if (list != null && !list.isEmpty())
        {
            long start = System.nanoTime();
            // 区块未加载 → 不模拟。一次性云直接丢弃；常驻云冻结保留，等区块重新加载后继续。
            Iterator<SpellCloud> it = list.iterator();
            while (it.hasNext())
            {
                SpellCloud cloud = it.next();
                if (!level.hasChunk(cloud.chunkX(), cloud.chunkZ()) && !cloud.persistent)
                {
                    it.remove();
                    removeFromVisible(level, cloud);
                    LOGGER.info("[wizardmagic] spell cloud={} dropped: chunk ({}, {}) not loaded",
                            cloud.id, cloud.chunkX(), cloud.chunkZ());
                }
            }
            if (!list.isEmpty())
            {
                SpellBatch.step(list);
                // 步进之后处理 tick / on hit 里的 emit：这一步攒下的请求现在才生成子云
                // （必须等所有云的粒子区间 join 完，否则边跑边加会撕裂列表）
                spawnPendingEmits(level, list);
            }

            int particles = 0;
            List<SpellCloud> expired = new ArrayList<>();
            Iterator<SpellCloud> after = list.iterator();
            while (after.hasNext())
            {
                SpellCloud cloud = after.next();
                particles += cloud.count;
                if (cloud.broken)
                {
                    LOGGER.warn("[wizardmagic] spell cloud={} script error: {}", cloud.id, cloud.breakReason);
                }
                if (cloud.isDead())
                {
                    // on expire：终局脚本，跑在移除之前，产出的子云等全批 join 完再生成
                    if (cloud.serverSide())
                    {
                        cloud.runExpireHook();
                    }
                    after.remove();
                    removeFromVisible(level, cloud);
                    expired.add(cloud);
                    LOGGER.info("[wizardmagic] spell hash cloud={} steps={} hash={} side=SERVER",
                            cloud.id, cloud.ageSteps(), String.format("%016x", cloud.stateHash()));
                }
            }
            // on expire 生出的子云：此时迭代已经结束，加进列表不会打乱它
            if (!expired.isEmpty())
            {
                spawnPendingEmits(level, expired);
            }

            lastStepNanos = System.nanoTime() - start;
            if (lastStepNanos > SLOW_STEP_NANOS)
            {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastSlowLogMs >= SLOW_LOG_INTERVAL_MS)
                {
                    lastSlowLogMs = nowMs;
                    LOGGER.warn("[wizardmagic] slow spell step clouds={} particles={} cost={}ms",
                            list.size(), particles, String.format(java.util.Locale.ROOT,
                                    "%.2f", lastStepNanos / 1.0E6));
                }
            }
            recordSample(lastStepNanos);
        }

        // 可见性核对：进入范围补发、离开范围移除
        if (level.getGameTime() % SpellConstants.VISIBILITY_SYNC_TICKS == 0)
        {
            syncVisibility(level);
        }
    }

    // ------------------------------------------------------------------ 清理

    /**
     * 区块卸载：一次性云随区块丢弃（短命法术不该在未加载区继续存在）；
     * 常驻云冻结保留——状态留在内存里，区块重新加载后从原状态继续。
     *
     * <p>跨重启的持久化由宿主方块（法阵方块实体）负责：方块实体在
     * {@code onLoad} 时重新 spawn 常驻云。调试命令创建的常驻云只在本次会话有效。</p>
     */
    public static void onChunkUnload(ServerLevel level, int chunkX, int chunkZ)
    {
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        if (list == null || list.isEmpty())
        {
            return;
        }
        Iterator<SpellCloud> it = list.iterator();
        while (it.hasNext())
        {
            SpellCloud cloud = it.next();
            if (cloud.chunkX() != chunkX || cloud.chunkZ() != chunkZ)
            {
                continue;
            }
            if (cloud.persistent)
            {
                continue; // 冻结，等区块重新加载
            }
            it.remove();
            removeFromVisible(level, cloud);
            LOGGER.info("[wizardmagic] spell cloud={} dropped with chunk ({}, {})",
                    cloud.id, chunkX, chunkZ);
        }
    }

    /** 清空某维度的所有云；broadcast=true 时通知已同步过的玩家一起清 */
    public static void clear(ServerLevel level, boolean broadcast)
    {
        List<SpellCloud> list = CLOUDS.remove(level.dimension());
        Map<UUID, Map<Integer, Integer>> vis = VISIBLE.remove(level.dimension());
        if (list == null || list.isEmpty())
        {
            return;
        }
        if (broadcast && vis != null)
        {
            for (ServerPlayer player : level.players())
            {
                Map<Integer, Integer> synced = vis.get(player.getUUID());
                if (synced == null)
                {
                    continue;
                }
                for (SpellCloud cloud : list)
                {
                    if (synced.containsKey(cloud.id))
                    {
                        sendRemove(player, cloud);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- 查询 / 统计

    public static int cloudCount(ServerLevel level)
    {
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        return list == null ? 0 : list.size();
    }

    /** 常驻云（法阵 / 结界）数量 */
    public static int anchoredCount(ServerLevel level)
    {
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        if (list == null)
        {
            return 0;
        }
        int n = 0;
        for (SpellCloud cloud : list)
        {
            if (cloud.persistent)
            {
                n++;
            }
        }
        return n;
    }

    public static int particleCount(ServerLevel level)
    {
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        if (list == null || list.isEmpty())
        {
            return 0;
        }
        int total = 0;
        for (SpellCloud cloud : list)
        {
            total += cloud.count;
        }
        return total;
    }

    public static List<SpellCloud> clouds(ServerLevel level)
    {
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        return list == null ? List.of() : List.copyOf(list);
    }

    public static String lastReject()
    {
        return lastReject;
    }

    /** 当前维度所有云状态的聚合哈希 */
    public static long aggregateHash(ServerLevel level)
    {
        long h = 0x9E3779B97F4A7C15L;
        for (SpellCloud cloud : clouds(level))
        {
            h = SpellRandom.mix(h ^ cloud.castSeed);
            h = SpellRandom.mix(h ^ cloud.stateHash());
        }
        return h;
    }

    public static String statsLine(ServerLevel level)
    {
        long[] sorted = samplesCopy();
        double p50 = percentile(sorted, 0.50) / 1.0E6;
        double p99 = percentile(sorted, 0.99) / 1.0E6;
        return String.format(java.util.Locale.ROOT,
                "云数=%d（常驻=%d）粒子=%d/%d 最后一步=%.3fms p50=%.3fms p99=%.3fms",
                cloudCount(level), anchoredCount(level), particleCount(level),
                SpellConstants.MAX_SIM_PARTICLES_PER_LEVEL,
                lastStepNanos / 1.0E6, p50, p99);
    }

    // ------------------------------------------------------------ 可见性同步

    /**
     * 核对每个玩家与每朵云的三态可见关系：
     * <ul>
     *   <li>{@code FULL}（≤64 格）：进入时发完整数据（新云生成包 / 进行中云状态快照）</li>
     *   <li>{@code SEMI}（64–160 格）：只发生成包，客户端按距离淡出（不传大快照）</li>
     *   <li>{@code HIDDEN}（&gt;176 格）：发移除包</li>
     * </ul>
     * 半可见 → 完整时补一份快照校正状态；完整 → 半可见不发包，只是淡出。
     */
    public static void syncVisibility(ServerLevel level)
    {
        List<SpellCloud> list = CLOUDS.get(level.dimension());
        if (list == null || list.isEmpty())
        {
            VISIBLE.remove(level.dimension());
            return;
        }
        Map<UUID, Map<Integer, Integer>> vis = VISIBLE.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        double fullSq = SpellConstants.VIS_FULL_RANGE * SpellConstants.VIS_FULL_RANGE;
        double fullExitSq = SpellConstants.VIS_FULL_EXIT * SpellConstants.VIS_FULL_EXIT;
        double semiSq = SpellConstants.VIS_SEMI_RANGE * SpellConstants.VIS_SEMI_RANGE;
        double semiExitSq = SpellConstants.VIS_SEMI_EXIT * SpellConstants.VIS_SEMI_EXIT;
        List<ServerPlayer> players = level.players();

        Map<UUID, ServerPlayer> byId = new HashMap<>();
        for (ServerPlayer player : players)
        {
            byId.put(player.getUUID(), player);
        }

        for (SpellCloud cloud : list)
        {
            // 区块未加载的云（常驻云冻结中）不发给任何玩家
            if (!level.hasChunk(cloud.chunkX(), cloud.chunkZ()))
            {
                for (Map.Entry<UUID, Map<Integer, Integer>> entry : vis.entrySet())
                {
                    if (entry.getValue().remove(cloud.id) != null)
                    {
                        ServerPlayer player = byId.get(entry.getKey());
                        if (player != null)
                        {
                            sendRemove(player, cloud);
                        }
                    }
                }
                continue;
            }

            for (ServerPlayer player : players)
            {
                Map<Integer, Integer> synced = vis.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
                Integer state = synced.get(cloud.id);
                double distSq = player.distanceToSqr(cloud.originX, cloud.originY, cloud.originZ);

                if (state == null)
                {
                    if (distSq <= fullSq)
                    {
                        synced.put(cloud.id, VIS_FULL);
                        if (cloud.ageSteps() == 0)
                        {
                            sendSpawn(player, cloud);
                        }
                        else
                        {
                            sendSnapshot(player, cloud);
                        }
                    }
                    else if (distSq <= semiSq)
                    {
                        // 半可见：只发生成包（常驻/静止云就是精确状态；运动中的云在远处允许近似）
                        synced.put(cloud.id, VIS_SEMI);
                        sendSpawn(player, cloud);
                    }
                }
                else if (state == VIS_SEMI)
                {
                    if (distSq <= fullSq)
                    {
                        synced.put(cloud.id, VIS_FULL);
                        sendSnapshot(player, cloud);
                    }
                    else if (distSq > semiExitSq)
                    {
                        synced.remove(cloud.id);
                        sendRemove(player, cloud);
                    }
                }
                else // VIS_FULL
                {
                    if (distSq > semiExitSq)
                    {
                        synced.remove(cloud.id);
                        sendRemove(player, cloud);
                    }
                    else if (distSq > fullExitSq)
                    {
                        synced.put(cloud.id, VIS_SEMI);
                    }
                }
            }
        }

        // 离线玩家与已销毁云的 id 清掉，避免集合无限增长
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : players)
        {
            online.add(player.getUUID());
        }
        vis.keySet().retainAll(online);
        Set<Integer> alive = new HashSet<>();
        for (SpellCloud cloud : list)
        {
            alive.add(cloud.id);
        }
        for (Map<Integer, Integer> synced : vis.values())
        {
            synced.keySet().retainAll(alive);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static List<SpellCloud> list(ServerLevel level)
    {
        return CLOUDS.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
    }

    private static void removeFromVisible(ServerLevel level, SpellCloud cloud)
    {
        Map<UUID, Map<Integer, Integer>> vis = VISIBLE.get(level.dimension());
        if (vis == null)
        {
            return;
        }
        for (ServerPlayer player : level.players())
        {
            Map<Integer, Integer> synced = vis.get(player.getUUID());
            if (synced != null && synced.remove(cloud.id) != null)
            {
                sendRemove(player, cloud);
            }
        }
    }

    private static void sendSpawn(ServerPlayer player, SpellCloud cloud)
    {
        SpellCloudSpawnPayload payload = new SpellCloudSpawnPayload(cloud.id, cloud.elementId, cloud.count,
                cloud.castSeed, cloud.shapeId, cloud.shapeParams,
                cloud.originX, cloud.originY, cloud.originZ, cloud.lifeSec,
                cloud.faceX, cloud.faceY, cloud.faceZ,
                cloud.drag, cloud.energyInit, cloud.energyDecay,
                cloud.persistent, cloud.stateHash(),
                cloud.script == null ? "" : cloud.script.id,
                cloud.script == null ? "" : cloud.script.source,
                cloud.scriptVars, cloud.spawnGameTime);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static void sendSnapshot(ServerPlayer player, SpellCloud cloud)
    {
        SpellCloudSnapshotPayload payload = new SpellCloudSnapshotPayload(cloud.id, cloud.elementId, cloud.count,
                cloud.castSeed, cloud.shapeId, cloud.shapeParams,
                cloud.originX, cloud.originY, cloud.originZ, cloud.lifeSec,
                cloud.faceX, cloud.faceY, cloud.faceZ,
                cloud.drag, cloud.energyInit, cloud.energyDecay,
                cloud.persistent, cloud.ageSteps(), cloud.snapshot(),
                cloud.script == null ? "" : cloud.script.id,
                cloud.script == null ? "" : cloud.script.source,
                cloud.scriptVars);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static void sendRemove(ServerPlayer player, SpellCloud cloud)
    {
        SpellCloudRemovePayload payload = new SpellCloudRemovePayload(cloud.id, cloud.ageSteps(), cloud.stateHash());
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    /**
     * 把这一步各朵云攒下的 {@code emit} 变成真正的子云。
     *
     * <p>规矩：</p>
     * <ul>
     *   <li>只有服务端会走到这里（客户端云的 {@code emitsEnabled=false}，脚本照样求值、
     *       只是没人取用，这样两端 {@code rand()} 序列一致）；</li>
     *   <li>子云<b>不再生孙云</b>：一条会 emit 的 tick 如果允许递归，指数增长挡不住；</li>
     *   <li>种子由「父云种子 + 父云步号 + 序号」混合，确定且每次施法不同；</li>
     *   <li>子云继承父云当时的 cast 变量（状态可以往下传，但子云不会重跑 on cast）。</li>
     * </ul>
     */
    private static void spawnPendingEmits(ServerLevel level, List<SpellCloud> list)
    {
        List<SpellCloud> snapshot = List.copyOf(list);
        for (SpellCloud cloud : snapshot)
        {
            List<EmitSpec> emits = cloud.drainEmits();
            if (emits.isEmpty() || cloud.script == null)
            {
                continue;
            }
            for (int i = 0; i < emits.size(); i++)
            {
                EmitSpec spec = emits.get(i);
                long seed = SpellRandom.mix(cloud.castSeed
                        ^ (cloud.ageSteps() * 0x9E3779B97F4A7C15L) ^ ((long) i << 32));
                SpellCloud child = spec.inherit()
                        ? spawnScripted(level, cloud.script, spec, seed,
                                new HashMap<>(cloud.scriptVars), cloud.casterId())
                        : spawnLaw(level, spec, seed);
                if (child == null)
                {
                    LOGGER.debug("[wizardmagic] spell cloud={} emit 被拒：{}", cloud.id, lastReject);
                    break;
                }
                child.setEmitsEnabled(false);
                LOGGER.debug("[wizardmagic] spell cloud={} emitted cloud={} step={} inherit={}",
                        cloud.id, child.id, cloud.ageSteps(), spec.inherit());
            }
        }
    }

    /**
     * {@code emit inherit: false} 的子云：**不带脚本**，只按元素定律（浮力/阻尼/噪声扩散）飘。
     *
     * <p>用处是"烟、灰尘、碎片"这类不该跟着父云走的余波：脚本能摆好它们的位置和初速，
     * 之后交给元素本身。元素决定上升还是下沉（雾 ≈ −0.1、火 ≈ −0.9 会往上飘，土会落）。</p>
     */
    private static SpellCloud spawnLaw(ServerLevel level, EmitSpec spec, long seed)
    {
        return spawn(level, spec.elementId(), spec.count(),
                new Vec3(spec.x(), spec.y(), spec.z()), seed, spec.lifeSec(),
                spec.shapeId(), spec.params(),
                SpellConstants.DEFAULT_DRAG, SpellConstants.DEFAULT_ENERGY,
                SpellConstants.DEFAULT_ENERGY_DECAY, false,
                spec.faceX(), spec.faceY(), spec.faceZ());
    }

    private static void recordSample(long nanos)
    {
        STEP_SAMPLES[sampleCursor] = nanos;
        sampleCursor = (sampleCursor + 1) % SAMPLE_SIZE;
        if (sampleCount < SAMPLE_SIZE)
        {
            sampleCount++;
        }
    }

    private static long[] samplesCopy()
    {
        long[] out = new long[sampleCount];
        System.arraycopy(STEP_SAMPLES, 0, out, 0, sampleCount);
        java.util.Arrays.sort(out);
        return out;
    }

    private static double percentile(long[] sorted, double q)
    {
        if (sorted.length == 0)
        {
            return 0.0;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.max(0, Math.round(q * (sorted.length - 1))));
        return sorted[idx];
    }
}
