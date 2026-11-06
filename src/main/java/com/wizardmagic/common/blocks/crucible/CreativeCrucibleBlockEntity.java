package com.wizardmagic.common.blocks.crucible;

import com.wizardmagic.api.crucible.CrucibleBlockEntity;
import com.wizardmagic.common.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造模式坩埚的方块实体。
 *
 * <p>液量、运行状态、颜色、渲染缓存全部由 {@link CrucibleBlockEntity} 提供；
 * 这里只绑定自己的方块实体类型与容量。</p>
 */
public class CreativeCrucibleBlockEntity extends CrucibleBlockEntity {

    /** 满液容量（mB），与 {@code LEVEL} 档位换算。 */
    public static final int CAPACITY = DEFAULT_CAPACITY;

    /** 默认液体颜色（青蓝），转发基类常量以保留旧调用点。 */
    public static final int DEFAULT_COLOR = CrucibleBlockEntity.DEFAULT_COLOR;

    public CreativeCrucibleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CREATIVE_CRUCIBLE.get(), pos, state, CAPACITY);
    }
}
