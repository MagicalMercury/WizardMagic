package com.wizardmagic.elements.event;

import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementFieldStorage;
import com.wizardmagic.elements.generation.ElementGeneration;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * section 级重算队列。
 *
 * <ul>
 *   <li>{@link #enqueueRefresh}：方块变化时局部刷新 base（保留当前扰动）。</li>
 *   <li>{@link #enqueueRegenerate}：{@code /element regenerate} 强制整段重算。</li>
 * </ul>
 *
 * <p>所有重算都按 tick 预算消费，避免一次命令卡死服务器。</p>
 */
public final class ElementRefreshQueue
{
    /** reset=true 表示重置 current=base（regenerate）；false 表示保留扰动（方块刷新） */
    public record Task(ResourceKey<Level> dimension, ChunkPos pos, int sectionIndex, boolean reset)
    {
    }

    /** 上限保护：流水/任意方块更新可能刷爆队列 */
    private static final int MAX_PENDING = 8192;

    private static final Set<Task> PENDING = new LinkedHashSet<>();

    /** 诊断计数：入队 / 实际重算（供 /element stats 验证事件是否真的送达） */
    private static long totalEnqueued;
    private static long totalProcessed;

    private ElementRefreshQueue()
    {
    }

    public static long totalEnqueued() {
        return totalEnqueued;
    }

    public static long totalProcessed() {
        return totalProcessed;
    }

    /** 同一 section 两次刷新之间的最小间隔（tick） */
    private static final int REFRESH_COOLDOWN_TICKS = 20;
    /** section 级冷却表 */
    private static final Map<SectionKey, Long> LAST_REFRESH = new HashMap<>();

    private record SectionKey(ResourceKey<Level> dimension, long chunk, int section)
    {
    }

    /**
     * 方块变化：刷新所在 section，并按边界判断是否需要刷新相邻 section。
     *
     * <p>卷积影响半径约 8 格：变化点在下半（localY&lt;8）只会影响下方 section，
     * 上半只影响上方，没必要固定刷三个。</p>
     */
    public static void enqueueRefresh(ServerLevel level, BlockPos pos) {
        int sectionIndex = (pos.getY() - level.getMinBuildHeight()) >> 4;
        if (sectionIndex < 0 || sectionIndex >= level.getSectionsCount()) {
            return;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        long now = level.getGameTime();
        int localY = pos.getY() & 15;

        addWithCooldown(level, chunkPos, sectionIndex, now);
        if (localY < 8) {
            addWithCooldown(level, chunkPos, sectionIndex - 1, now);
        } else {
            addWithCooldown(level, chunkPos, sectionIndex + 1, now);
        }
    }

    private static void addWithCooldown(ServerLevel level, ChunkPos chunkPos, int sectionIndex, long now) {
        if (sectionIndex < 0 || sectionIndex >= level.getSectionsCount()) {
            return;
        }
        SectionKey key = new SectionKey(level.dimension(), chunkPos.toLong(), sectionIndex);
        synchronized (LAST_REFRESH) {
            Long last = LAST_REFRESH.get(key);
            if (last != null && now - last < REFRESH_COOLDOWN_TICKS) {
                return;
            }
            LAST_REFRESH.put(key, now);
            if (LAST_REFRESH.size() > 16384) {
                // 简单防膨胀：极端场景（大面积岩浆）下清空，宁可多算一次
                LAST_REFRESH.clear();
            }
        }
        add(new Task(level.dimension(), chunkPos, sectionIndex, false));
    }

    /** 强制重算：把半径内所有 chunk 的所有 section 入队（reset=true） */
    public static int enqueueRegenerate(ServerLevel level, ChunkPos center, int radiusChunks) {
        int queued = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                for (int sectionIndex = 0; sectionIndex < level.getSectionsCount(); sectionIndex++) {
                    if (add(new Task(level.dimension(), pos, sectionIndex, true))) {
                        queued++;
                    }
                }
            }
        }
        return queued;
    }

    /** 旧存档升级：把该 chunk 已有 section 全部入队重算（reset=true） */
    public static void enqueueUpgrade(ServerLevel level, LevelChunk chunk, ElementFieldStorage storage) {
        for (int sectionIndex : storage.sectionMap().keySet()) {
            add(new Task(level.dimension(), chunk.getPos(), sectionIndex, true));
        }
        storage.setBaseVersion(ElementFieldStorage.CURRENT_BASE_VERSION);
        chunk.setUnsaved(true);
    }

    /** 补算：稳态 section 不写盘，读档后把缺失的非空气 section 重新生成 */
    public static int enqueueMissing(ServerLevel level, LevelChunk chunk, ElementFieldStorage storage) {
        int queued = 0;
        for (int sectionIndex = 0; sectionIndex < level.getSectionsCount(); sectionIndex++) {
            net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            if (!storage.has(sectionIndex) && add(new Task(level.dimension(), chunk.getPos(), sectionIndex, true))) {
                queued++;
            }
        }
        return queued;
    }

    private static boolean add(Task task) {
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING) {
                return false;
            }
            boolean added = PENDING.add(task);
            if (added) {
                totalEnqueued++;
            }
            return added;
        }
    }

    /** 每个 tick 消费若干 section */
    public static void process(MinecraftServer server, int budget) {
        synchronized (PENDING) {
            if (PENDING.isEmpty() || budget <= 0) {
                return;
            }
            Iterator<Task> iterator = PENDING.iterator();
            int processed = 0;
            while (iterator.hasNext() && processed < budget) {
                Task task = iterator.next();
                iterator.remove();

                ServerLevel level = server.getLevel(task.dimension());
                if (level == null) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(task.pos().x, task.pos().z);
                if (chunk == null) {
                    continue;
                }
                ElementFieldStorage storage = ElementAccess.storage(chunk);
                if (storage == null || !storage.isGenerated()) {
                    continue;
                }

                if (task.reset()) {
                    ElementGeneration.generateSection(level, chunk, storage, task.sectionIndex());
                } else {
                    ElementField field = storage.get(task.sectionIndex());
                    if (field == null) {
                        continue;
                    }
                    ElementGeneration.refreshBase(chunk, task.sectionIndex(), field);
                }
                chunk.setUnsaved(true);
                processed++;
                totalProcessed++;
            }
        }
    }

    public static int pending() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    public static void clear() {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }
}
