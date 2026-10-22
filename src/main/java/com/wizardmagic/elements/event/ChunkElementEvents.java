package com.wizardmagic.elements.event;

import com.mojang.logging.LogUtils;
import com.wizardmagic.Config;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementFieldStorage;
import com.wizardmagic.elements.flow.ElementFlowScheduler;
import com.wizardmagic.elements.generation.ElementGeneration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * chunk 加载后触发生成。
 *
 * <p>{@code ChunkEvent.Load} 可能发生在 worker 线程，且新 chunk 的方块未必就绪，
 * 所以这里只入队，真正的生成放到 {@code ServerTickEvent}（主线程）推进。</p>
 *
 * <p>调度策略（CHANGE.md P1-2 / P1-3）：</p>
 * <ul>
 *   <li>时间预算制（毫秒）而不是固定 section 数，避免单 tick 尖峰；</li>
 *   <li>方块刷新队列为空时预算 ×2，把闲置算力让给生成；</li>
 *   <li>每 20 tick 按「到最近玩家的距离」重排队列，脚下的 chunk 优先；</li>
 *   <li>只预生成玩家周围 {@code generationRadius} 内的 chunk，更远处走惰性生成。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class ChunkElementEvents
{
    private static final List<Pending> QUEUE = new ArrayList<>();
    /** 已入队或已生成的 chunk，避免重复排队 */
    private static final Map<Long, Boolean> TRACKED = new HashMap<>();
    private static final Logger LOGGER = LogUtils.getLogger();

    private ChunkElementEvents()
    {
    }

    private static final class Pending
    {
        final LevelChunk chunk;
        int nextSection;
        double distanceSq;

        Pending(LevelChunk chunk) {
            this.chunk = chunk;
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (chunk.getLevel().isClientSide()) {
            return;
        }

        ElementFieldStorage storage = ElementAccess.storage(chunk);
        if (storage == null) {
            return;
        }

        if (storage.isGenerated()) {
            if (chunk.getLevel() instanceof ServerLevel serverLevel) {
                if (storage.getBaseVersion() < ElementFieldStorage.CURRENT_BASE_VERSION) {
                    // 旧存档：base 是旧公式算的（没有邻近方块/指数强源），排队重算
                    ElementRefreshQueue.enqueueUpgrade(serverLevel, chunk, storage);
                } else {
                    // 稳态 section 不写盘，读档后补算缺失的非空气 section
                    ElementRefreshQueue.enqueueMissing(serverLevel, chunk, storage);
                    // 活跃集合只由 addAround（玩家周围）与 markHot（扰动）维护，
                    // 不再把每个加载的 chunk 都塞进去，避免集合无限膨胀
                    // 旧版本把稳态 section 也写进了存档：标脏让它在保存时被剔除
                    if (hasPristineSections(storage)) {
                        chunk.setUnsaved(true);
                    }
                }
            }
            return;
        }

        synchronized (QUEUE) {
            long key = chunk.getPos().toLong();
            if (TRACKED.containsKey(key)) {
                return;
            }
            TRACKED.put(key, Boolean.TRUE);
            QUEUE.add(new Pending(chunk));
        }
    }

    /** 玩家移动时补入队：只关心玩家附近、已加载但还没生成元素场的 chunk */
    public static void requestAround(ServerLevel level, ChunkPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                if (chunk == null) {
                    continue;
                }
                ElementFieldStorage storage = ElementAccess.storage(chunk);
                if (storage == null || storage.isGenerated()) {
                    continue;
                }
                synchronized (QUEUE) {
                    long key = chunk.getPos().toLong();
                    if (TRACKED.containsKey(key)) {
                        continue;
                    }
                    TRACKED.put(key, Boolean.TRUE);
                    QUEUE.add(new Pending(chunk));
                }
            }
        }
    }

    /** 主线程每 tick 调用：按毫秒预算推进生成 */
    public static void processQueue(MinecraftServer server) {
        int maxSections = Config.GENERATION_SECTION_BUDGET.get();
        if (maxSections <= 0) {
            return;
        }

        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) {
                return;
            }
            if (server.getTickCount() % 20 == 0) {
                sortByPlayerDistance(server);
            }
        }

        boolean idle = ElementRefreshQueue.pending() == 0;
        double budgetMs = Config.GENERATION_TIME_BUDGET_MS.get() * (idle ? 2.0 : 1.0);
        long deadline = System.nanoTime() + (long) (budgetMs * 1_000_000L);
        int generated = 0;
        int radius = Config.GENERATION_RADIUS.get();

        while (generated < maxSections && System.nanoTime() < deadline) {
            Pending pending;
            synchronized (QUEUE) {
                pending = QUEUE.isEmpty() ? null : QUEUE.get(0);
            }
            if (pending == null) {
                return;
            }

            LevelChunk chunk = pending.chunk;
            // 超出预生成半径：丢弃，等玩家靠近时 requestAround 或惰性访问
            if (radius >= 0 && pending.distanceSq > (double) radius * radius) {
                synchronized (QUEUE) {
                    QUEUE.remove(0);
                }
                dropTracked(chunk);
                continue;
            }

            ElementFieldStorage storage = ElementAccess.storage(chunk);
            if (storage == null) {
                dropTracked(chunk);
                continue;
            }

            LevelChunkSection[] sections = chunk.getSections();
            while (pending.nextSection < sections.length
                    && generated < maxSections
                    && System.nanoTime() < deadline) {
                int sectionIndex = pending.nextSection++;
                LevelChunkSection section = sections[sectionIndex];
                // 空气 section 不消耗预算：1.20.1 每个 chunk 有 24 个 section，
                // 其中十几格是空气，若按空气扣预算，生成速度会慢 2-3 倍。
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                ElementGeneration.generateSection(chunk.getLevel(), chunk, storage, sectionIndex);
                generated++;
            }

            if (pending.nextSection >= sections.length) {
                storage.setGenerated(true);
                storage.setBaseVersion(ElementFieldStorage.CURRENT_BASE_VERSION);
                // 关键：capability 数据变更不会自动把 chunk 标记为脏，
                // 不标记就不会落盘，下次进世界会认为"未生成"而重算一遍。
                chunk.setUnsaved(true);
                if (chunk.getLevel() instanceof ServerLevel serverLevel) {
                    ElementFlowScheduler.INSTANCE.addAround(serverLevel, chunk.getPos(), 1);
                }
                LOGGER.debug("元素场生成完成 chunk={} sections={}",
                        chunk.getPos(), storage.sectionCount());
                synchronized (QUEUE) {
                    QUEUE.remove(pending);
                }
                dropTracked(chunk);
            }
        }
    }

    private static void dropTracked(LevelChunk chunk) {
        synchronized (QUEUE) {
            TRACKED.remove(chunk.getPos().toLong());
        }
    }

    private static void sortByPlayerDistance(MinecraftServer server) {
        Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, double[]> playerChunks = new HashMap<>();
        double[] fallback = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            double[] pos = {player.getX(), player.getZ()};
            playerChunks.put(player.level().dimension(), pos);
            if (fallback == null) {
                fallback = pos;
            }
        }
        if (fallback == null) {
            return;
        }
        for (Pending pending : QUEUE) {
            double[] pos = playerChunks.getOrDefault(pending.chunk.getLevel().dimension(), fallback);
            double cx = pending.chunk.getPos().getMiddleBlockX();
            double cz = pending.chunk.getPos().getMiddleBlockZ();
            double dx = cx - pos[0];
            double dz = cz - pos[1];
            pending.distanceSq = dx * dx + dz * dz;
        }
        QUEUE.sort((a, b) -> Double.compare(a.distanceSq, b.distanceSq));
    }

    public static int queuedChunks() {
        synchronized (QUEUE) {
            return QUEUE.size();
        }
    }

    /** 存档里还留着「存了但其实是稳态」的 section，需要让 chunk 重写一次把它们剔除 */
    private static boolean hasPristineSections(ElementFieldStorage storage) {
        for (ElementField field : storage.fields()) {
            if (field.isPristine()) {
                return true;
            }
        }
        return false;
    }

    public static void clearQueue() {
        synchronized (QUEUE) {
            QUEUE.clear();
            TRACKED.clear();
        }
    }
}
