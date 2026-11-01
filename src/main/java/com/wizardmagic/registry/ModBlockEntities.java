package com.wizardmagic.registry;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.blocks.crucible.CreativeCrucibleBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 方块实体类型。 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, WizardMagic.MODID);

    public static final RegistryObject<BlockEntityType<CreativeCrucibleBlockEntity>> CREATIVE_CRUCIBLE =
            BLOCK_ENTITIES.register("creative_crucible", () -> BlockEntityType.Builder
                    .of(CreativeCrucibleBlockEntity::new, ModBlocks.CREATIVE_CRUCIBLE.get())
                    .build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
