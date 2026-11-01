package com.wizardmagic.elements;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 在元素场里标记实现 {@link ManaBarrier} 的方块。
 *
 * <p>屏障的效果完全由元素场层保证：</p>
 * <ul>
 *   <li><b>生成 / 方块变化</b>：{@link #scan} 把每格通透度写进 {@link ElementField}；
 *       通透度为 0 的格子连同 {@code current}/{@code base} 一起清零；</li>
 *   <li><b>扩散</b>：{@code ElementDiffusion.stepFlux} 按通透度给通量打折，0 则整格跳过；</li>
 *   <li><b>平流</b>：{@code ElementAdvection} 按通透度缩小平流结果。</li>
 * </ul>
 *
 * <p>没有屏障方块的 section（绝大多数）在 {@code maybeHas} 处直接返回，
 * 热路径上所有判定都被 {@code hasBarrier} 短路。</p>
 */
public final class ElementBarriers {

    private ElementBarriers() {
    }

    /**
     * 重新扫描一个 section 的屏障标记。
     *
     * @return 该 section 是否含有屏障方块
     */
    public static boolean scan(LevelChunkSection section, ElementField field) {
        field.clearBarriers();
        if (section == null || section.hasOnlyAir()) {
            return false;
        }
        if (!section.maybeHas(state -> state.getBlock() instanceof ManaBarrier)) {
            return false;
        }
        boolean any = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.getBlock() instanceof ManaBarrier barrier) {
                        int index = ElementField.index(x, y, z);
                        field.setPermeability(index, barrier.permeability(state));
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
