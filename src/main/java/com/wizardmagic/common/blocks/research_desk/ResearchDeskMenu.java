package com.wizardmagic.common.blocks.research_desk;

import com.wizardmagic.common.registry.ModMenuTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 研究台的 GUI：上面一格放纸，下面三行是玩家背包。
 *
 * <p>槽位通过 {@link ResearchDeskBlockEntity#isPaper(ItemStack)} 限制为
 * {@code #wizardmagic:research_papers}，与渲染层解耦——放得进去的纸，
 * 渲染器就按该物品自己的模型画在桌面上。</p>
 */
public class ResearchDeskMenu extends AbstractContainerMenu {

    private final Container desk;

    /** 客户端构造：从网络数据恢复方块实体。 */
    public ResearchDeskMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, resolve(inventory, buffer));
    }

    private static Container resolve(Inventory inventory, FriendlyByteBuf buffer) {
        var blockEntity = inventory.player.level().getBlockEntity(buffer.readBlockPos());
        return blockEntity instanceof Container container ? container : new SimpleContainer(1);
    }

    /** 服务端构造。 */
    public ResearchDeskMenu(int id, Inventory inventory, Container desk) {
        super(ModMenuTypes.RESEARCH_DESK.get(), id);
        this.desk = desk;

        addSlot(new Slot(desk, 0, 80, 35) {
            @Override
            public boolean mayPlace(@NotNull ItemStack stack) {
                return ResearchDeskBlockEntity.isPaper(stack);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return desk.stillValid(player);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, 37, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        // 桌面槽的数量变化走 Slot#setChanged，不经过 setItem/removeItem，
        // 渲染层需要显式同步，否则取走部分纸时桌上仍按旧数量画。
        if (index == 0 && desk instanceof ResearchDeskBlockEntity blockEntity) {
            blockEntity.markUpdated();
        }
        return result;
    }
}
