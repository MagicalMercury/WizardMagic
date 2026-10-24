package com.wizardmagic.client.fx;

import com.wizardmagic.WizardMagic;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端接线：tick 推进光点生命周期，AFTER_PARTICLES 阶段提交渲染。
 *
 * <p>选 AFTER_PARTICLES 是为了让光点叠在粒子之上、天气之下，
 * 和原版粒子的视觉层次一致。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID, value = Dist.CLIENT)
public final class SparkRenderer
{
    private SparkRenderer() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // 先跑元素光点（会直接改 Spark 的位置/alpha），再让池统一重建实例表
            ElementGlowField.tick();
            SparkBatch.get().tick();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 必须用 AFTER_WEATHER：MC 在 AFTER_PARTICLES 之后才渲染云（LevelRenderer:1393），
        // 而云不写深度、只做混合，会把先前画好的光点整片盖住。
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            SparkBatch.get().render(event.getCamera(), event.getPartialTick());
        }
    }
}
