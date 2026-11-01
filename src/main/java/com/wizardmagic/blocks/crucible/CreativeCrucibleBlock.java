package com.wizardmagic.blocks.crucible;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 创造模式坩埚：给核心模组的坩埚 API 提供可放置、可手动改状态的样板方块。
 *
 * <p>状态由两个方块属性驱动，渲染层不需要方块实体：</p>
 * <ul>
 *   <li>{@link #LEVEL} 0–8，对应 8 档液面模型（0 = 空锅，可见锅底）；</li>
 *   <li>{@link #ACTIVE} 是否运行，对应法阵呼吸贴图的 active 变体。</li>
 * </ul>
 *
 * <p>测试用交互（只在服务端执行）：</p>
 * <ul>
 *   <li>右键 / 使用键：液面档位 +1，满档回到空锅；回到空锅时自动停止运行。</li>
 *   <li>潜行 + 右键：切换运行状态。</li>
 * </ul>
 */
public class CreativeCrucibleBlock extends Block implements EntityBlock {

    public static final int MAX_LEVEL = 8;
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, MAX_LEVEL);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public CreativeCrucibleBlock() {
        super(Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(2.5F, 6.0F)
                .sound(SoundType.STONE)
                .noOcclusion()
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false)
                .lightLevel(state -> state.getValue(ACTIVE) ? 9 : 0));
        registerDefaultState(stateDefinition.any().setValue(LEVEL, 0).setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LEVEL, ACTIVE);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockState next;
        if (player.isShiftKeyDown()) {
            next = state.setValue(ACTIVE, !state.getValue(ACTIVE));
        } else {
            int currentLevel = state.getValue(LEVEL);
            int nextLevel = (currentLevel + 1) % (MAX_LEVEL + 1);
            next = state.setValue(LEVEL, nextLevel);
            if (nextLevel == 0) {
                next = next.setValue(ACTIVE, false);
            }
        }

        level.setBlock(pos, next, Block.UPDATE_ALL);
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeCrucibleBlockEntity(pos, state);
    }
}
