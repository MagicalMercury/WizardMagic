package com.wizardmagic.elements.generation;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块 -> 四元素贡献权重（相对值，卷积时会平均）。
 *
 * <p>顺序固定 {Earth, Water, Wind, Fire}。</p>
 */
public final class BlockElementWeights
{
    private static final int[] NONE = {0, 0, 0, 0};

    private BlockElementWeights() {
    }

    public static int[] contribution(BlockState state) {
        int earth = 0;
        int water = 0;
        int wind = 0;
        int fire = 0;

        if (state.isAir()) {
            wind += 4;
        } else if (state.is(Blocks.WATER)) {
            water += 30;
            earth -= 4;
        } else if (state.is(Blocks.LAVA)) {
            fire += 30;
            earth -= 4;
        } else if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            fire += 28;
            wind += 2;
        } else if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            fire += (state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT)) ? 18 : 6;
        } else if (state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT)) {
            // 正在使用的熔炉 / 高炉 / 烟熏炉
            fire += 20;
        } else if (state.is(Blocks.MAGMA_BLOCK)) {
            fire += 18;
            earth += 4;
        } else if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) {
            water += 10;
            earth += 2;
        } else if (state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)) {
            earth += 10;
        } else if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            earth += 4;
            wind += 3;
        } else if (state.isSolid()) {
            earth += 5;
        }

        if (earth == 0 && water == 0 && wind == 0 && fire == 0) {
            return NONE;
        }
        return new int[]{earth, water, wind, fire};
    }
}
