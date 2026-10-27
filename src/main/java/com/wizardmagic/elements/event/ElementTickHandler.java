package com.wizardmagic.elements.event;

import com.wizardmagic.Config;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.elements.flow.ElementFlowScheduler;
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

        ChunkElementEvents.processQueue(event.getServer());
        ElementRefreshQueue.process(event.getServer(), Config.REFRESH_BUDGET.get());

        if (!Config.ENABLE_FLOW.get()) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            elementFlow(level);
            com.wizardmagic.elements.fluid.FluidManager.tick(level);
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
            ElementFlowScheduler.INSTANCE.addAround(serverLevel, player.chunkPosition(), Config.ACTIVE_RADIUS.get());
            int generationRadius = Config.GENERATION_RADIUS.get();
            if (generationRadius >= 0) {
                ChunkElementEvents.requestAround(serverLevel, player.chunkPosition(), generationRadius);
            }
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
