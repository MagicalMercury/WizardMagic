package com.wizardmagic.core.spell.event;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.core.spell.SpellCloudManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 法术云的服务端接线：每 tick 推进、维度卸载时清理。
 *
 * <p>与元素场（{@code ElementTickHandler}）互不依赖，法术云不读写元素场。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class SpellServerEvents
{
    private SpellServerEvents()
    {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels())
        {
            SpellCloudManager.tick(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event)
    {
        if (event.getLevel() instanceof ServerLevel level)
        {
            SpellCloudManager.clear(level, false);
        }
    }

    /**
     * 区块卸载：一次性云丢弃，常驻云冻结保留。
     *
     * <p>注意 tick 里还有一道 {@code hasChunk} 兜底：即使这个事件因故没到，
     * 未加载区块的云也不会被推进。</p>
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event)
    {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk))
        {
            return;
        }
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        // 区块事件有可能不在主线程触发；云列表只在主线程访问，必要时转交
        if (!level.getServer().isSameThread())
        {
            level.getServer().execute(() -> SpellCloudManager.onChunkUnload(level, chunkX, chunkZ));
            return;
        }
        SpellCloudManager.onChunkUnload(level, chunkX, chunkZ);
    }
}
