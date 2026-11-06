package com.wizardmagic.common.blocks.crucible;

import com.wizardmagic.api.crucible.CrucibleBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;

/**
 * 创造模式坩埚：坩埚 API 的参考实现，也是内容模组继承 {@link CrucibleBlock} 的样板。
 *
 * <p>状态属性与交互全部来自基类：{@code LEVEL 0..8} 驱动液面，
 * {@code ACTIVE} 驱动法阵呼吸贴图与方块光。液面由通用
 * {@code com.wizardmagic.common.client.CrucibleRenderer} 按真实比例绘制，
 * 不再依赖 8 个静态模型。</p>
 */
public class CreativeCrucibleBlock extends CrucibleBlock {

    /** 转发基类常量，保留旧调用点。 */
    public static final int MAX_LEVEL = CrucibleBlock.MAX_LEVEL;
    public static final IntegerProperty LEVEL = CrucibleBlock.LEVEL;
    public static final BooleanProperty ACTIVE = CrucibleBlock.ACTIVE;

    public CreativeCrucibleBlock() {
        super(Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(2.5F, 6.0F)
                .sound(SoundType.STONE));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeCrucibleBlockEntity(pos, state);
    }
}
