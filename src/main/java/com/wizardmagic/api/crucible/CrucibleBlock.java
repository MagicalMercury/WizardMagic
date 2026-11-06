package com.wizardmagic.api.crucible;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 通用坩埚方块基类。
 *
 * <p>内容模组继承本类即可拿到统一的方块属性、发光规则与默认交互：</p>
 * <pre>{@code
 * public class AlchemyCrucibleBlock extends CrucibleBlock {
 *     public AlchemyCrucibleBlock() {
 *         super(Properties.of()
 *                 .mapColor(MapColor.COLOR_PURPLE)
 *                 .strength(2.5F, 6.0F)
 *                 .sound(SoundType.STONE));
 *     }
 *
 *     @Override
 *     public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
 *         return new AlchemyCrucibleBlockEntity(pos, state);
 *     }
 * }
 * }</pre>
 *
 * <p>状态属性：</p>
 * <ul>
 *   <li>{@link #LEVEL} {@code 0..8}，0 = 空锅；渲染器按 {@code fillFraction} 换算真实液面；
 *       内容模组也可以完全不用它，直接在方块实体里覆写 {@code fluidAmount()}</li>
 *   <li>{@link #ACTIVE} 是否运行，驱动法阵呼吸贴图与方块光</li>
 * </ul>
 *
 * <p>默认交互（只在服务端执行，内容模组可覆写 {@link #use} 替换）：</p>
 * <ul>
 *   <li>右键：{@code LEVEL} +1，满档回到 0，归零时自动停止运行</li>
 *   <li>潜行 + 右键：切换 {@code ACTIVE}</li>
 * </ul>
 */
public abstract class CrucibleBlock extends Block implements EntityBlock {

    /** 液面档位上限，对应 {@code level} 属性的最大值。 */
    public static final int MAX_LEVEL = 8;

    /** 液面档位，0 = 空锅。 */
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, MAX_LEVEL);

    /** 是否处于炼制状态，驱动法阵呼吸与方块光。 */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    /** 运行时的默认方块光等级。 */
    public static final int DEFAULT_ACTIVE_LIGHT = 9;

    /**
     * @param properties 方块属性（mapColor / strength / sound 等由内容模组决定）
     */
    protected CrucibleBlock(Properties properties) {
        this(properties, DEFAULT_ACTIVE_LIGHT);
    }

    /**
     * @param properties  方块属性
     * @param activeLight 运行时的方块光等级，0 表示不发光
     */
    protected CrucibleBlock(Properties properties, int activeLight) {
        super(properties
                .noOcclusion()
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false)
                .lightLevel(state -> state.getValue(ACTIVE) ? activeLight : 0));
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
            int current = state.getValue(LEVEL);
            int target = (current + 1) % (MAX_LEVEL + 1);
            next = state.setValue(LEVEL, target);
            if (target == 0) {
                next = next.setValue(ACTIVE, false);
            }
        }

        level.setBlock(pos, next, Block.UPDATE_ALL);
        return InteractionResult.CONSUME;
    }

    /** 内容模组返回自己的方块实体实例。 */
    @Override
    public abstract BlockEntity newBlockEntity(BlockPos pos, BlockState state);
}
