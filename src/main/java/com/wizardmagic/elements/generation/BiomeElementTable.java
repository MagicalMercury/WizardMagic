package com.wizardmagic.elements.generation;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.HashMap;
import java.util.Map;

/**
 * 群系 -> 四元素基础值（0..127）。
 *
 * <p>顺序固定为 {Earth, Water, Wind, Fire}，与 {@link com.wizardmagic.elements.ElementTypes#ordinal()} 一致。</p>
 */
public final class BiomeElementTable
{
    private static final int[] DEFAULT = {60, 40, 50, 30};
    private static final Map<ResourceLocation, int[]> TABLE = new HashMap<>();

    static {
        put(Biomes.PLAINS, 60, 40, 50, 10);
        put(Biomes.SUNFLOWER_PLAINS, 60, 40, 50, 12);
        put(Biomes.FOREST, 70, 40, 45, 10);
        put(Biomes.FLOWER_FOREST, 65, 45, 50, 10);
        put(Biomes.BIRCH_FOREST, 68, 42, 46, 10);
        put(Biomes.DARK_FOREST, 72, 45, 40, 8);
        put(Biomes.TAIGA, 75, 35, 40, 8);
        put(Biomes.SNOWY_TAIGA, 70, 40, 42, 5);
        put(Biomes.SNOWY_PLAINS, 50, 30, 45, 5);
        put(Biomes.ICE_SPIKES, 46, 34, 48, 4);
        put(Biomes.DESERT, 90, 10, 30, 30);
        put(Biomes.BADLANDS, 95, 5, 25, 30);
        put(Biomes.SAVANNA, 80, 20, 40, 25);
        put(Biomes.JUNGLE, 70, 70, 40, 15);
        put(Biomes.SWAMP, 50, 80, 30, 5);
        put(Biomes.MANGROVE_SWAMP, 52, 82, 30, 5);
        put(Biomes.OCEAN, 20, 110, 40, 5);
        put(Biomes.DEEP_OCEAN, 24, 112, 38, 5);
        put(Biomes.WARM_OCEAN, 26, 108, 40, 8);
        put(Biomes.COLD_OCEAN, 22, 110, 42, 4);
        put(Biomes.RIVER, 30, 100, 40, 5);
        put(Biomes.BEACH, 55, 70, 40, 10);
        put(Biomes.MUSHROOM_FIELDS, 60, 60, 50, 10);
        put(Biomes.MEADOW, 65, 45, 55, 10);
        put(Biomes.GROVE, 72, 38, 44, 6);
        put(Biomes.NETHER_WASTES, 40, 5, 20, 120);
        put(Biomes.SOUL_SAND_VALLEY, 30, 5, 15, 90);
        put(Biomes.CRIMSON_FOREST, 45, 10, 20, 100);
        put(Biomes.WARPED_FOREST, 45, 15, 25, 85);
        put(Biomes.BASALT_DELTAS, 60, 5, 25, 95);
        put(Biomes.THE_END, 30, 10, 60, 20);
        put(Biomes.END_HIGHLANDS, 35, 10, 65, 22);
        put(Biomes.END_MIDLANDS, 32, 10, 62, 20);
        put(Biomes.SMALL_END_ISLANDS, 30, 12, 60, 18);
    }

    private BiomeElementTable() {
    }

    private static void put(ResourceKey<Biome> key, int earth, int water, int wind, int fire) {
        TABLE.put(key.location(), new int[]{earth, water, wind, fire});
    }

    /** 取群系的基础四元素；未收录的群系使用默认值 */
    public static int[] base(Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(key -> TABLE.get(key.location()))
                .orElse(DEFAULT);
    }

    /** 默认基础值（只读，别改） */
    public static int[] defaults() {
        return DEFAULT;
    }
}
