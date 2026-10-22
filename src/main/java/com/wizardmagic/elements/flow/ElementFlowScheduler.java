package com.wizardmagic.elements.flow;

import com.wizardmagic.Config;
import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementFieldStorage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活跃 chunk 调度器：只对玩家附近 / 近期被扰动的 chunk 跑扩散。
 *
 * <p>调度优先级（CHANGE.md 扩散调整）：</p>
 * <ol>
 *   <li><b>hot</b>：被 set/add/disturb 动过的 chunk，每 tick 优先处理；</li>
 *   <li><b>active</b>：玩家周围 {@code activeRadius} 圈内的 chunk，按到玩家的距离排序后
 *       从近到远消费；每 20 tick 重新排序。</li>
 * </ol>
 *
 * <p>远离玩家的 chunk 会在 {@link #addAround} 时被清理（keepRadius = 4×半径），
 * 避免活跃集合随探索无限膨胀；chunk 卸载时通过 {@link #remove} 立即移出。</p>
 */
public final class ElementFlowScheduler
{
    public static final ElementFlowScheduler INSTANCE = new ElementFlowScheduler();

    /** 维度 -> 活跃 chunk */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> active = new HashMap<>();
    /** 维度 -> 被扰动过的 chunk（每 tick 优先处理） */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> hot = new HashMap<>();
    /** 维度 -> 按距离排序的 active 快照（每 20 tick 重建） */
    private final Map<ResourceKey<Level>, List<ChunkPos>> ordered = new HashMap<>();

    private long tickCounter;
    private long lastSortTick = Long.MIN_VALUE;

    private ElementFlowScheduler()
    {
    }

    public void add(ServerLevel level, ChunkPos pos) {
        active.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos);
    }

    /**
     * 标记"刚被扰动"的 chunk：它必须立刻参与扩散，
     * 不能和几百个普通活跃 chunk 排在同一个队列里。
     */
    public void markHot(ServerLevel level, ChunkPos pos) {
        hot.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos);
    }

    public void addAround(ServerLevel level, ChunkPos center, int radius) {
        Set<ChunkPos> set = active.computeIfAbsent(level.dimension(), key -> new HashSet<>());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                set.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        // 玩家飞行时清理旧区域，避免活跃集合无限增长
        prune(level, center, radius * 2 + 2);
    }

    /** 移除离玩家超过 keepRadius 的活跃 chunk（玩家回来时 addAround 会重新加入） */
    public void prune(ServerLevel level, ChunkPos center, int keepRadius) {
        Set<ChunkPos> set = active.get(level.dimension());
        if (set == null) {
            return;
        }
        long r2 = (long) keepRadius * keepRadius;
        set.removeIf(pos -> {
            long dx = pos.x - center.x;
            long dz = pos.z - center.z;
            return dx * dx + dz * dz > r2;
        });
    }

    /** chunk 卸载：立即移出活跃与 hot，不等扫描 */
    public void remove(ServerLevel level, ChunkPos pos) {
        Set<ChunkPos> set = active.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
        Set<ChunkPos> hotSet = hot.get(level.dimension());
        if (hotSet != null) {
            hotSet.remove(pos);
        }
    }

    public void clear(ResourceKey<Level> dimension) {
        active.remove(dimension);
        hot.remove(dimension);
        ordered.remove(dimension);
    }

    public void clearAll() {
        active.clear();
        hot.clear();
        ordered.clear();
    }

    public int activeCount() {
        int total = 0;
        for (Set<ChunkPos> set : active.values()) {
            total += set.size();
        }
        return total;
    }

    public int hotCount() {
        int total = 0;
        for (Set<ChunkPos> set : hot.values()) {
            total += set.size();
        }
        return total;
    }

    public void tick(ServerLevel level) {
        tickCounter++;
        long now = level.getGameTime();

        double alpha = Config.FLOW_ALPHA.get();
        double beta = Config.FLOW_BETA.get();
        int budget = Config.FLOW_BUDGET.get();
        int cooldown = Math.max(1, Config.FLOW_COOLDOWN.get());
        if (budget <= 0) {
            return;
        }

        ChunkPos center = playerCenter(level);

        // 1) 被扰动过的 chunk：每 tick 优先，按距离从近到远
        Set<ChunkPos> hotSet = hot.get(level.dimension());
        if (hotSet != null && !hotSet.isEmpty()) {
            List<ChunkPos> hotOrder = new ArrayList<>(hotSet);
            sortByDistance(hotOrder, center);
            int hotBudget = Math.max(1, budget / 2);
            for (int i = 0; i < hotOrder.size() && i < hotBudget; i++) {
                ChunkPos pos = hotOrder.get(i);
                int state = processChunk(level, pos, alpha, beta, now, cooldown);
                if (state == STATE_CLEAN) {
                    hotSet.remove(pos);
                } else if (state == STATE_GONE) {
                    hotSet.remove(pos);
                }
            }
        }

        // 2) 普通活跃 chunk：按「到玩家距离」从近到远消费
        int interval = Math.max(1, Config.FLOW_INTERVAL.get());
        if (tickCounter % interval == 0) {
            Set<ChunkPos> set = active.get(level.dimension());
            if (set == null || set.isEmpty()) {
                return;
            }
            List<ChunkPos> order = ordered.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
            if (now - lastSortTick >= 20) {
                order.clear();
                order.addAll(set);
                sortByDistance(order, center);
                lastSortTick = now;
            }
            int processed = 0;
            for (int i = 0; i < order.size() && processed < budget; i++) {
                ChunkPos pos = order.get(i);
                int state = processChunk(level, pos, alpha, beta, now, cooldown);
                if (state == STATE_GONE) {
                    set.remove(pos);
                }
                processed++;
            }
        }
    }

    private static final int STATE_OK = 0;
    private static final int STATE_CLEAN = 1;
    private static final int STATE_GONE = 2;

    /**
     * 处理单个 chunk：只对 dirty 且过了冷却的 section 跑一步扩散。
     *
     * @return {@link #STATE_OK} 仍有脏 section；{@link #STATE_CLEAN} 全部收敛；
     *         {@link #STATE_GONE} chunk 已卸载/未生成
     */
    private int processChunk(ServerLevel level, ChunkPos pos, double alpha, double beta,
                             long now, int cooldown) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            return STATE_GONE;
        }
        ElementFieldStorage storage = ElementAccess.storage(chunk);
        if (storage == null || !storage.isGenerated()) {
            return STATE_GONE;
        }

        boolean changed = false;
        boolean anyDirty = false;
        boolean fluxMode = Config.FLUX_MODE.get();
        double fluxRate = Config.FLUX_RATE.get();
        int fluxLimit = Config.FLUX_LIMIT.get();
        for (ElementField field : storage.fields()) {
            // 稳态 section（current == base）直接跳过，零开销
            if (!field.isDirty()) {
                continue;
            }
            anyDirty = true;

            // 每个 section 自带冷却：扩散一次后必须等 flowCooldown tick 才能再动
            if (now - field.getLastStepTick() < cooldown) {
                continue;
            }
            field.setLastStepTick(now);

            boolean fieldChanged = fluxMode
                    ? ElementDiffusion.stepFlux(field, fluxRate, fluxLimit, beta)
                    : ElementDiffusion.step(field, alpha, beta);
            if (fieldChanged) {
                changed = true;
            } else {
                field.setDirty(false);
            }
        }

        if (changed) {
            chunk.setUnsaved(true);
        }
        return anyDirty ? STATE_OK : STATE_CLEAN;
    }

    private static ChunkPos playerCenter(ServerLevel level) {
        if (level.players().isEmpty()) {
            return null;
        }
        var player = level.players().get(0);
        return player.chunkPosition();
    }

    private static void sortByDistance(List<ChunkPos> list, ChunkPos center) {
        if (center == null) {
            return;
        }
        final long cx = center.x;
        final long cz = center.z;
        list.sort(Comparator.comparingLong(pos -> {
            long dx = pos.x - cx;
            long dz = pos.z - cz;
            return dx * dx + dz * dz;
        }));
    }
}
