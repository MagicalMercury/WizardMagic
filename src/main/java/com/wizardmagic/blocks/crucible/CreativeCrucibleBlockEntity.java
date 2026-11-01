package com.wizardmagic.blocks.crucible;

import com.wizardmagic.api.crucible.CrucibleState;
import com.wizardmagic.api.crucible.CrucibleStateSource;
import com.wizardmagic.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造模式坩埚的方块实体：把方块属性翻译成 {@link CrucibleState}。
 *
 * <p>真正的数据只有颜色一个字段，液量与运行状态都从方块状态读，
 * 因此客户端不需要额外的同步包：方块状态本身会同步，方块实体跟着区块同步。</p>
 */
public class CreativeCrucibleBlockEntity extends BlockEntity implements CrucibleStateSource {

    /** 满液容量，单位与内容模组保持一致即可（这里用 mB）。 */
    public static final int CAPACITY = 8000;

    /** 默认液体颜色（青蓝），与贴图的液面色调一致。 */
    public static final int DEFAULT_COLOR = 0xFF3FD8F0;

    private int colorArgb = DEFAULT_COLOR;

    public CreativeCrucibleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CREATIVE_CRUCIBLE.get(), pos, state);
    }

    public int colorArgb() {
        return colorArgb;
    }

    public void setColorArgb(int argb) {
        this.colorArgb = argb;
        setChanged();
    }

    @Override
    public CrucibleState crucibleState() {
        BlockState state = getBlockState();
        int level = state.hasProperty(CreativeCrucibleBlock.LEVEL)
                ? state.getValue(CreativeCrucibleBlock.LEVEL)
                : 0;
        boolean running = state.hasProperty(CreativeCrucibleBlock.ACTIVE)
                && state.getValue(CreativeCrucibleBlock.ACTIVE);
        int amount = Math.round(CAPACITY * (level / (float) CreativeCrucibleBlock.MAX_LEVEL));

        Level world = getLevel();
        long time = world == null ? 0L : world.getGameTime();
        return new Snapshot(amount, CAPACITY, colorArgb, running, time);
    }

    /** 只读快照，供渲染器与调试命令消费。 */
    public record Snapshot(int fluidAmount, int fluidCapacity, int fluidColorArgb,
                           boolean running, long animationTime) implements CrucibleState {
    }
}
