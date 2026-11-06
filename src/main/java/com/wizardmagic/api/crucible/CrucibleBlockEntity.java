package com.wizardmagic.api.crucible;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 通用坩埚方块实体基类。
 *
 * <p>已经把「契约 → 渲染参数」这条链打通：实现 {@link CrucibleStateSource}，
 * 持有每帧复用的 {@link CrucibleRenderCache}，并把颜色写进 NBT。
 * 内容模组继承后只需关心自己的液量从哪来。</p>
 *
 * <pre>{@code
 * public class AlchemyCrucibleBlockEntity extends CrucibleBlockEntity {
 *     public AlchemyCrucibleBlockEntity(BlockPos pos, BlockState state) {
 *         super(ModBlockEntities.ALCHEMY_CRUCIBLE.get(), pos, state, 16000);
 *     }
 *
 *     // 有真实储罐时覆写这两个方法，LEVEL 属性就不再参与计算
 *     @Override
 *     public int fluidAmount() { return tank.getFluidAmount(); }
 *
 *     @Override
 *     public int fluidCapacity() { return tank.getCapacity(); }
 * }
 * }</pre>
 *
 * <p>液面几何默认取 {@link CrucibleGeometry}；换了内腔尺寸就覆写
 * {@link #cavityBottom()} / {@link #cavityHeight()}，通用渲染器会跟着走。</p>
 */
public abstract class CrucibleBlockEntity extends BlockEntity implements CrucibleStateSource {

    /** 默认容量，单位与内容模组保持一致即可（这里用 mB）。 */
    public static final int DEFAULT_CAPACITY = 8000;

    /** 默认液体颜色（青蓝）。 */
    public static final int DEFAULT_COLOR = 0xFF3FD8F0;

    private static final String TAG_COLOR = "ColorArgb";

    private final CrucibleRenderCache renderCache = new CrucibleRenderCache();
    private final int capacity;
    private int colorArgb = DEFAULT_COLOR;

    protected CrucibleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        this(type, pos, state, DEFAULT_CAPACITY);
    }

    /**
     * @param capacity 满液容量，必须 {@code > 0}
     */
    protected CrucibleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int capacity) {
        super(type, pos, state);
        this.capacity = Math.max(1, capacity);
    }

    // ---------------------------------------------------------------- 颜色

    /** 液体颜色，{@code 0xAARRGGBB}。 */
    public int colorArgb() {
        return colorArgb;
    }

    /** 设置液体颜色；变化时让渲染缓存失效并同步客户端。 */
    public void setColorArgb(int argb) {
        if (this.colorArgb == argb) {
            return;
        }
        this.colorArgb = argb;
        renderCache.invalidate();
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    // ---------------------------------------------------------------- 渲染

    /** 每帧复用的渲染状态缓存；一个方块实体一个实例，不要跨线程共享。 */
    public final CrucibleRenderCache renderCache() {
        return renderCache;
    }

    /** 内腔底面 y（模型单位）。 */
    public float cavityBottom() {
        return CrucibleGeometry.CAVITY_BOTTOM;
    }

    /** 内腔深度（模型单位）。 */
    public float cavityHeight() {
        return CrucibleGeometry.CAVITY_HEIGHT;
    }

    // ------------------------------------------------------------ 状态契约

    /** 满液容量。 */
    public int fluidCapacity() {
        return capacity;
    }

    /**
     * 当前液量。默认按 {@link CrucibleBlock#LEVEL} 换算；
     * 有真实储罐的内容模组直接覆写本方法。
     */
    public int fluidAmount() {
        BlockState state = getBlockState();
        int level = state.hasProperty(CrucibleBlock.LEVEL)
                ? state.getValue(CrucibleBlock.LEVEL)
                : 0;
        return Math.round(capacity * (level / (float) CrucibleBlock.MAX_LEVEL));
    }

    /** 是否处于炼制状态。默认读 {@link CrucibleBlock#ACTIVE}。 */
    public boolean running() {
        BlockState state = getBlockState();
        return state.hasProperty(CrucibleBlock.ACTIVE) && state.getValue(CrucibleBlock.ACTIVE);
    }

    @Override
    public CrucibleState crucibleState() {
        Level world = getLevel();
        long time = world == null ? 0L : world.getGameTime();
        return new Snapshot(fluidAmount(), capacity, colorArgb, running(), time);
    }

    // ---------------------------------------------------------------- NBT

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(TAG_COLOR, colorArgb);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(TAG_COLOR)) {
            colorArgb = tag.getInt(TAG_COLOR);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putInt(TAG_COLOR, colorArgb);
        return tag;
    }

    /**
     * 必须覆写：{@link BlockEntity#getUpdatePacket()} 默认返回 {@code null}，
     * 而 {@code ChunkHolder.broadcastBlockEntity} 遇到 null 直接跳过，
     * 结果是颜色变更永远发不到客户端。
     */
    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        load(packet.getTag());
    }

    /** 只读快照，供渲染器与调试命令消费。 */
    public record Snapshot(int fluidAmount, int fluidCapacity, int fluidColorArgb,
                           boolean running, long animationTime) implements CrucibleState {
    }
}
