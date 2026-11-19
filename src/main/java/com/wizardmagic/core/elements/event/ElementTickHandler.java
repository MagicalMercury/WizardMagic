package com.wizardmagic.core.elements.event;

import com.wizardmagic.Config;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.core.elements.flow.ElementFlowScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 服务端 tick 接线：
 * <ul>
 *   <li>每 tick 推进 chunk 元素场生成（有预算，避免卡顿）</li>
 *   <li>每 tick 对活跃 chunk 跑一步扩散</li>
 *   <li>每 20 tick 把玩家周围 chunk 加入活跃集合</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class ElementTickHandler
{
    private ElementTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        long probe0 = System.nanoTime();
        ChunkElementEvents.processQueue(event.getServer());
        com.wizardmagic.core.diag.ServerTickProbe.note("chunkQueue", System.nanoTime() - probe0);
        long probe1 = System.nanoTime();
        ElementRefreshQueue.process(event.getServer(), Config.REFRESH_BUDGET.get());
        com.wizardmagic.core.diag.ServerTickProbe.note("refreshQ", System.nanoTime() - probe1);

        if (!Config.ENABLE_FLOW.get()) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            long probe2 = System.nanoTime();
            elementFlow(level);
            // 元素场平流/扩散：**每 tick 每维度**都在跑，是"tick 内重活"的头号嫌疑
            com.wizardmagic.core.diag.ServerTickProbe.note("elemFlow", System.nanoTime() - probe2);
            com.wizardmagic.core.elements.fluid.FluidManager.tick(level);
        }
    }

    private static void elementFlow(ServerLevel level) {
        ElementFlowScheduler.INSTANCE.tick(level);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 20 != 0) {
            return;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            long probe0 = System.nanoTime();
            ElementFlowScheduler.INSTANCE.addAround(serverLevel, player.chunkPosition(), Config.ACTIVE_RADIUS.get());
            int generationRadius = Config.GENERATION_RADIUS.get();
            if (generationRadius >= 0) {
                ChunkElementEvents.requestAround(serverLevel, player.chunkPosition(), generationRadius);
            }
            // 1 Hz 的"玩家周围登记/生成入队"：轻则几毫秒，重则几十毫秒，单独计
            com.wizardmagic.core.diag.ServerTickProbe.note("elem20", System.nanoTime() - probe0);
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // 维度加载时清掉旧状态，等 chunk / 玩家重新登记
            ElementFlowScheduler.INSTANCE.clear(serverLevel.dimension());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getChunk() instanceof LevelChunk chunk
                && chunk.getLevel() instanceof ServerLevel serverLevel) {
            // 立即移出活跃集合，不等扫描发现 getChunkNow == null
            ElementFlowScheduler.INSTANCE.remove(serverLevel, chunk.getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ElementFlowScheduler.INSTANCE.clear(serverLevel.dimension());
        }
    }
}
