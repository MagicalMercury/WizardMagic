package com.wizardmagic.common.blocks.mana_refuse;

import com.wizardmagic.api.elements.ElementTypes;
import com.wizardmagic.core.elements.ManaBarrier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 魔力拒绝方块：阻断魔力场流动的「绝缘体」。
 *
 * <p>行为由 {@code ElementRefuse} 在元素场层实现（不是方块本身）：</p>
 * <ul>
 *   <li>该方块占据的格子，四种元素<b>恒为 0</b>（生成时清零，之后不被扩散/平流写入）；</li>
 *   <li>扩散：不发出通量，也不接收邻居的通量 —— 直通的元素流被完全掐断；</li>
 *   <li>平流：平流结果不写回这些格子，回溯穿过它时取到 0。</li>
 * </ul>
 *
 * <p>模型是 8 个角块 + 1 个内芯的镂空结构，所以必须 {@code noOcclusion()}，
 * 否则相邻面会被剔除导致外观破损。</p>
 */
public class ManaRefuseBlock extends Block implements ManaBarrier {

    public ManaRefuseBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(3.0F, 1200.0F)
                .sound(SoundType.STONE)
                .noOcclusion()
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false));
    }

    /** 完全阻断：内部不允许存在任何元素，也不传递通量。 */
    @Override
    public int permeability(BlockState state, ElementTypes type) {
        return ManaBarrier.BLOCKED;
    }
}
