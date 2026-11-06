package com.wizardmagic.common.blocks.research_desk;

import com.wizardmagic.common.registry.ModBlockEntities;
import com.wizardmagic.common.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 研究台的方块实体：只保存桌面上的那一张纸。
 *
 * <p>纸张槽接受 {@code #wizardmagic:research_papers} 里的任意物品，所以内容模组
 * 新增纸张时只需要往这个 tag 里加一条 id，不需要改这里的任何代码。</p>
 *
 * <p>方块实体只存在于 HEAD 半块；点击 FOOT 时由方块转发过来。</p>
 */
public class ResearchDeskBlockEntity extends BlockEntity implements Container, net.minecraft.world.MenuProvider {

    private static final String PAPER_KEY = "Paper";

    private ItemStack paper = ItemStack.EMPTY;

    public ResearchDeskBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESEARCH_DESK.get(), pos, state);
    }

    /** 当前桌面上的纸；渲染器每帧读取。 */
    public ItemStack paper() {
        return paper;
    }

    public static boolean isPaper(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModTags.RESEARCH_PAPERS);
    }

    // --- Container ----------------------------------------------------------

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return paper.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return paper;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack result = paper.copyWithCount(Math.min(count, paper.getCount()));
        paper.shrink(result.getCount());
        if (paper.isEmpty()) {
            paper = ItemStack.EMPTY;
        }
        markUpdated();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = paper;
        paper = ItemStack.EMPTY;
        // Container 的 "NoUpdate" 只表示不标脏存档；桌面渲染直接读 paper，
        // 少了同步会让客户端继续画已经不在桌上的纸。
        markUpdated();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.paper = stack.copy();
        if (this.paper.getCount() > getMaxStackSize()) {
            this.paper.setCount(getMaxStackSize());
        }
        markUpdated();
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        paper = ItemStack.EMPTY;
        markUpdated();
    }

    /**
     * 数据变更后打脏存档并同步客户端。
     *
     * <p>菜单的 {@code quickMoveStack} 只调用 {@code Slot#setChanged()}，
     * 不会经过 {@code setItem}/{@code removeItem}，需要显式调用本方法。</p>
     */
    public void markUpdated() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // --- MenuProvider -------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.wizardmagic.research_desk");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ResearchDeskMenu(id, inventory, this);
    }

    // --- persistence / sync -------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!paper.isEmpty()) {
            tag.put(PAPER_KEY, paper.save(new CompoundTag()));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        paper = tag.contains(PAPER_KEY) ? ItemStack.of(tag.getCompound(PAPER_KEY)) : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet) {
        load(packet.getTag());
    }

    /** 掉出桌面上的纸（拆桌子或半块被破坏时）。 */
    public void dropPaper(Level level, BlockPos pos) {
        if (!paper.isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, paper);
            paper = ItemStack.EMPTY;
            markUpdated();
        }
    }
}
