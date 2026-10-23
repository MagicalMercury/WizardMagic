package com.wizardmagic.client.fx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.wizardmagic.WizardMagic;

import java.io.IOException;

/**
 * 自定义 shader 注册：assets/wizardmagic/shaders/core/magic_spark.{json,vsh,fsh}。
 *
 * <p>着色器在资源重载时会被重建，所以每次都重新赋值。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SparkShaders
{
    private static ShaderInstance spark;

    private SparkShaders()
    {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), "wizardmagic:magic_spark",
                        DefaultVertexFormat.POSITION_COLOR_TEX),
                shader -> spark = shader);
    }

    public static ShaderInstance spark() {
        return spark;
    }
}
