package com.wizardmagic.common.client.spell;

import com.wizardmagic.WizardMagic;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端接线：每 tick 推进法术云镜像；断线时清空。
 *
 * <p>渲染不在这里提交——{@code SparkBatch} 每帧重建实例表时会优先调用
 * {@link SpellCloudClient#fill}，与光点共用同一条自建 GL 管线。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID, value = Dist.CLIENT)
public final class SpellClientEvents
{
    private SpellClientEvents()
    {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            SpellCloudClient.INSTANCE.tick();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        SpellCloudClient.INSTANCE.clear();
    }

    /** 客户端区块卸载：丢掉原点在该区块的镜像云（防御性，正常由可见性同步负责移除） */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event)
    {
        if (event.getLevel() instanceof ClientLevel && event.getChunk() instanceof LevelChunk chunk)
        {
            SpellCloudClient.INSTANCE.onChunkUnload(chunk.getPos().x, chunk.getPos().z);
        }
    }
}
