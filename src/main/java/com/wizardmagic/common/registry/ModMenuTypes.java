package com.wizardmagic.common.registry;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.common.blocks.research_desk.ResearchDeskMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 菜单类型。 */
public final class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, WizardMagic.MODID);

    public static final RegistryObject<MenuType<ResearchDeskMenu>> RESEARCH_DESK =
            MENUS.register("research_desk", () -> IForgeMenuType.create(ResearchDeskMenu::new));

    private ModMenuTypes() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
