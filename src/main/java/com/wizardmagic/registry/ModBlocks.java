package com.wizardmagic.registry;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.blocks.crucible.CreativeCrucibleBlock;
import com.wizardmagic.blocks.mana_refuse.ManaRefuseBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 方块、方块物品与创造模式标签页。 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, WizardMagic.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, WizardMagic.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WizardMagic.MODID);

    public static final RegistryObject<Block> MANA_REFUSE_BLOCK =
            BLOCKS.register("mana_refuse_block", ManaRefuseBlock::new);

    public static final RegistryObject<Item> MANA_REFUSE_ITEM =
            ITEMS.register("mana_refuse_block",
                    () -> new BlockItem(MANA_REFUSE_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<Block> CREATIVE_CRUCIBLE =
            BLOCKS.register("creative_crucible", CreativeCrucibleBlock::new);

    public static final RegistryObject<Item> CREATIVE_CRUCIBLE_ITEM =
            ITEMS.register("creative_crucible",
                    () -> new BlockItem(CREATIVE_CRUCIBLE.get(), new Item.Properties()));

    public static final RegistryObject<CreativeModeTab> TAB =
            CREATIVE_TABS.register("wizardmagic", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wizardmagic"))
                    .icon(() -> new ItemStack(CREATIVE_CRUCIBLE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(MANA_REFUSE_BLOCK.get());
                        output.accept(CREATIVE_CRUCIBLE.get());
                    })
                    .build());

    private ModBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
    }
}
