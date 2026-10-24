package com.wizardmagic.elements;

import com.wizardmagic.elements.flow.ElementFlowScheduler;
import com.wizardmagic.elements.generation.ElementGeneration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 元素场的对外读写 API（跨 section 寻址）。
 *
 * <p>所有方法都不强制加载 chunk：未加载的坐标返回 0 / 写入失败。</p>
 */
public final class ElementAccess
{
    private ElementAccess() {
    }

    /** 取某个 chunk 的存储（未挂载时返回 null） */
    public static ElementFieldStorage storage(LevelChunk chunk) {
        return chunk.getCapability(ElementFieldStorage.CAPABILITY).orElse(null);
    }

    /** 取坐标所在 chunk 的存储（chunk 未加载返回 null） */
    public static ElementFieldStorage storage(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        return storage(level.getChunk(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** 坐标 -> section 下标（越界返回 -1） */
    public static int sectionIndex(Level level, int y) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return -1;
        }
        return (y - level.getMinBuildHeight()) >> 4;
    }

    /** 取坐标所在的元素场（chunk/section 不存在时返回 null；空气 section 会惰性生成） */
    public static ElementField field(Level level, BlockPos pos) {
        ElementFieldStorage storage = storage(level, pos);
        if (storage == null) {
            return null;
        }
        int sectionIndex = sectionIndex(level, pos.getY());
        if (sectionIndex < 0) {
            return null;
        }
        ElementField field = storage.get(sectionIndex);
        if (field == null) {
            // 空气 section 不预生成（省内存），第一次被访问时才按群系+噪声+高度补上。
            // 注意：这里不再要求 chunk 已经"预生成"——高空/远处的 section 就是靠这条路径
            // 才存在的，getChunk 会阻塞到方块数据就绪，按需补算是安全的。
            LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            ElementGeneration.generateSection(level, chunk, storage, sectionIndex);
            field = storage.get(sectionIndex);
        }
        return field;
    }

    /** 读一个格子的元素值；未加载/未生成返回 0 */
    public static int get(Level level, BlockPos pos, ElementTypes type) {
        ElementField field = field(level, pos);
        if (field == null) {
            return 0;
        }
        return field.get(type, ElementField.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
    }

    /** 写一个格子的元素值；成功返回 true */
    public static boolean set(Level level, BlockPos pos, ElementTypes type, int value) {
        ElementField field = field(level, pos);
        if (field == null) {
            return false;
        }
        field.set(type, ElementField.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15), value);
        markHot(level, pos);
        return true;
    }

    /** 增减一个格子的元素值；返回写入后的值（失败返回 -1） */
    public static int add(Level level, BlockPos pos, ElementTypes type, int delta) {
        ElementField field = field(level, pos);
        if (field == null) {
            return -1;
        }
        int value = field.add(type, ElementField.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15), delta);
        markHot(level, pos);
        return value;
    }

    /** 扰动发生后把 chunk 放进热队列，保证下一 tick 就参与扩散 */
    private static void markHot(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ElementFlowScheduler.INSTANCE.markHot(serverLevel, new ChunkPos(pos));
        }
    }

    /** 读一个格子的四元素（in-place 写入 out；失败返回 false） */
    public static boolean getAll(Level level, BlockPos pos, int[] out) {
        ElementField field = field(level, pos);
        if (field == null) {
            return false;
        }
        int index = ElementField.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        for (ElementTypes type : ElementTypes.values()) {
            out[type.index()] = field.get(type, index);
        }
        return true;
    }
}
