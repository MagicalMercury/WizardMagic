package com.wizardmagic.common.client.shader;

import com.wizardmagic.WizardMagic;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 液面批次的接线：帧首清空，帧末统一绘制。
 *
 * <p>用 {@code AFTER_SKY} 清空而不是画完清空——方块实体渲染器在本帧稍后提交，
 * 提前清掉才不会让这一帧的数据被上一帧的绘制吃掉。</p>
 *
 * <p>{@code AFTER_WEATHER} 与光点一致：必须晚于云（云不写深度，会整片盖住半透明）。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID, value = Dist.CLIENT)
public final class CrucibleLiquidRenderer {

    private CrucibleLiquidRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            CrucibleLiquidBatch.draw(event.getCamera());
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // 帧末清空：本帧 BER 提交的数据在本帧 AFTER_WEATHER 画完才丢弃。
            // 用 AFTER_SKY 清空有风险——如果那个 stage 晚于方块实体提交，本帧数据会被立刻抹掉。
            CrucibleLiquidBatch.begin();
        }
    }
}
