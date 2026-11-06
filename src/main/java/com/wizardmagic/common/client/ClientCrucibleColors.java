package com.wizardmagic.common.client;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.common.blocks.crucible.CreativeCrucibleBlockEntity;
import com.wizardmagic.common.registry.ModBlocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 坩埚液体着色。
 *
 * <p>液面模型所有面都是 {@code tintindex: 0}，颜色由方块实体的 {@code colorArgb} 决定；
 * 没有方块实体时（物品栏、预览）回退到默认青蓝。这就是「液体颜色可自定义」的运行时入口。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientCrucibleColors {

    private ClientCrucibleColors() {
    }

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(
                (state, level, pos, tintIndex) -> {
                    if (level != null && pos != null
                            && level.getBlockEntity(pos) instanceof CreativeCrucibleBlockEntity crucible) {
                        return crucible.colorArgb();
                    }
                    return CreativeCrucibleBlockEntity.DEFAULT_COLOR;
                },
                ModBlocks.CREATIVE_CRUCIBLE.get());
    }
}
