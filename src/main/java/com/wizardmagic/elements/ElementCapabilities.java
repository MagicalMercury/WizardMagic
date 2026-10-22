package com.wizardmagic.elements;

import com.wizardmagic.WizardMagic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把 {@link ElementFieldStorage} 挂到每个服务端 chunk 上。
 *
 * <p>只在服务端挂载；客户端目前不需要这份数据（后续要做粒子/音效时再加同步）。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class ElementCapabilities
{
    private static final ResourceLocation ELEMENT_FIELD_ID =
            new ResourceLocation(WizardMagic.MODID, "element_field");

    private ElementCapabilities() {
    }

    @SubscribeEvent
    public static void onAttachChunkCapabilities(AttachCapabilitiesEvent<LevelChunk> event) {
        LevelChunk chunk = event.getObject();
        if (chunk.getLevel().isClientSide()) {
            return;
        }
        ICapabilityProvider provider = new ElementFieldStorage.Provider();
        event.addCapability(ELEMENT_FIELD_ID, provider);
    }
}
