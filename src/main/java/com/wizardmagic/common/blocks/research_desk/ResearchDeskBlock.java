package com.wizardmagic.common.blocks.research_desk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 研究台：两格宽的桌子，桌上摊开一张可自定义的纸。
 *
 * <p>两格的实现沿用原版床的结构：{@link #PART} 由 {@code FOOT}/{@code HEAD} 组成，
 * {@link #FACING} 指向从 FOOT 到 HEAD 的方向。放置时自动生成 HEAD 半块，
 * 任意一半被破坏时另一半通过 {@link #updateShape} 触发 {@link #tearDown} 一并移除，
 * 因此不存在“半张桌子”状态。</p>
 *
 * <p>纸张数据只存在 HEAD 半块的 {@link ResearchDeskBlockEntity} 上；
 * 点击 FOOT 时转发到 HEAD。</p>
 */
public class ResearchDeskBlock extends Block implements EntityBlock {

    public static final EnumProperty<BedPart> PART = BlockStateProperties.BED_PART;
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** 碰撞到桌面高度（毯子顶面），可以站上去。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 11.4, 16);

    public ResearchDeskBlock() {
        super(Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F, 3.0F)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false));
        registerDefaultState(stateDefinition.any()
                .setValue(PART, BedPart.FOOT)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, FACING);
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 放置时让桌子“面对”玩家：FOOT→HEAD 取玩家朝向的顺时针方向，长边便横在玩家面前。
        // 玩家面朝北时 FACING=EAST（blockstate y=0），模型 +Z 正是南向，正好对着玩家；
        // 其余三个朝向由 y=90/180/270 补齐，正面始终朝向玩家。
        Direction facing = context.getHorizontalDirection().getClockWise();
        BlockPos headPos = context.getClickedPos().relative(facing);
        Level level = context.getLevel();
        if (!level.getBlockState(headPos).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, BedPart.FOOT);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide) {
            return;
        }
        BlockPos headPos = pos.relative(state.getValue(FACING));
        level.setBlock(headPos, state.setValue(PART, BedPart.HEAD), Block.UPDATE_ALL);
        level.blockUpdated(pos, Blocks.AIR);
        state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == getNeighbourDirection(state.getValue(PART), state.getValue(FACING))) {
            boolean stillWhole = neighbour.is(this) && neighbour.getValue(PART) != state.getValue(PART);
            return stillWhole ? state : tearDown(level, pos);
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /**
     * 任意一半消失时调用：掉出桌上的纸，并返回 AIR 让这一半也消失。
     */
    private BlockState tearDown(LevelAccessor level, BlockPos pos) {
        if (level instanceof Level realLevel && !realLevel.isClientSide
                && realLevel.getBlockEntity(pos) instanceof ResearchDeskBlockEntity desk) {
            desk.dropPaper(realLevel, pos);
        }
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        super.playerWillDestroy(level, pos, state, player);
        if (!level.isClientSide && !player.isCreative()
                && level.getBlockEntity(pos) instanceof ResearchDeskBlockEntity desk) {
            desk.dropPaper(level, pos);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (state.getValue(PART) != BedPart.HEAD) {
            blockEntity = level.getBlockEntity(pos.relative(getConnectedDirection(state)));
        }
        if (!(blockEntity instanceof ResearchDeskBlockEntity desk)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // 必须传方块实体自己的位置（HEAD）。传点击位置的话，点 FOOT 打开 GUI 时
        // 客户端会用 FOOT 坐标 getBlockEntity -> 拿不到实体 -> 退化成 SimpleContainer，
        // 客户端预测全部落到空容器上，与桌面渲染脱节。
        NetworkHooks.openScreen((ServerPlayer) player, desk, desk.getBlockPos());
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == BedPart.HEAD ? new ResearchDeskBlockEntity(pos, state) : null;
    }

    /** 从这一半指向另一半的方向。 */
    public static Direction getNeighbourDirection(BedPart part, Direction facing) {
        return part == BedPart.FOOT ? facing : facing.getOpposite();
    }

    /** 从这一半指向“桌子主体（HEAD）”的方向。 */
    public static Direction getConnectedDirection(BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(PART) == BedPart.HEAD ? facing.getOpposite() : facing;
    }

    /** 纸张渲染用的中心点相对 FOOT 方块原点的偏移。 */
    public static double[] paperAnchor(BlockState state) {
        Direction facing = state.getValue(FACING);
        return new double[]{
                0.5 + facing.getStepX() * 0.5,
                0.0 + facing.getStepZ() * 0.5
        };
    }
}
