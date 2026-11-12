package com.wizardmagic;

import com.mojang.logging.LogUtils;
import com.wizardmagic.common.blocks.research_desk.ResearchDeskRenderer;
import com.wizardmagic.common.blocks.research_desk.ResearchDeskScreen;
import com.wizardmagic.common.registry.ModRegistration;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(WizardMagic.MODID)
public class WizardMagic
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "wizardmagic";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    public WizardMagic()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 所有注册表（方块、物品、创造标签页、元素）统一在 registry 包里管理
        ModRegistration.register(modEventBus);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        event.enqueueWork(com.wizardmagic.common.network.ModNetwork::register);
        LOGGER.info("Wizard Magic common setup");
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        LOGGER.info("Wizard Magic: server starting");
        // 存档里的法术目录 + 编译缓存：启动时全编一遍，缓存没命中就重编并写回
        try
        {
            com.wizardmagic.common.spell.SpellScripts.warmUp(event.getServer());
        }
        catch (RuntimeException e)
        {
            // 法术目录坏了不能连累开服
            LOGGER.warn("Wizard Magic: 法术缓存预热失败", e);
        }
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                    com.wizardmagic.common.registry.ModMenuTypes.RESEARCH_DESK.get(),
                    ResearchDeskScreen::new));
            LOGGER.info("Wizard Magic client setup");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }

        @SubscribeEvent
        public static void onRegisterRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerBlockEntityRenderer(
                    com.wizardmagic.common.registry.ModBlockEntities.RESEARCH_DESK.get(),
                    ResearchDeskRenderer::new);
            // 通用坩埚渲染器：所有继承 CrucibleBlockEntity 的坩埚共用同一实现
            event.registerBlockEntityRenderer(
                    com.wizardmagic.common.registry.ModBlockEntities.CREATIVE_CRUCIBLE.get(),
                    com.wizardmagic.common.client.CrucibleRenderer::new);
        }
    }
}
