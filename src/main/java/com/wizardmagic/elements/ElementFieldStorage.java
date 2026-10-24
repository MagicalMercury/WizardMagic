package com.wizardmagic.elements;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 挂在 {@link net.minecraft.world.level.chunk.LevelChunk} 上的四元素场存储。
 *
 * <p>1.20.1 没有 attachment API，用 Forge Capability 实现（chunk capability 会被
 * {@code ChunkSerializer} 自动写进 {@code ForgeCaps}，存档/读档无需手写）。</p>
 *
 * <p>数据按 section 懒分配：只有「生成过」的 section 才占内存，
 * 一个 section = 4 × 4096 字节（另加一份 base 快照）。</p>
 */
@AutoRegisterCapability
public final class ElementFieldStorage
{
    public static final Capability<ElementFieldStorage> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {});

    /** section 下标 -> 元素场（下标 = (worldY - minBuildHeight) >> 4） */
    private final Map<Integer, ElementField> sections = new HashMap<>();

    /** 整块 chunk 是否已完成生成 */
    private boolean generated;

    /** base 生成公式版本：低于当前版本说明是旧存档数据，需要重算
     *  v3 = 强源半径按源类型；v4 = 稳态 section 不写盘；
     *  v5 = 合并插值/分离式插值；v6 = 群系+噪声归一化到 48、风三次方到 127、水海平面高斯峰；
     *  v7 = 岩浆火元素强度 26 → 80 */
    public static final int CURRENT_BASE_VERSION = 7;

    private int baseVersion;

    public int getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(int baseVersion) {
        this.baseVersion = baseVersion;
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    public ElementField get(int sectionIndex) {
        return sections.get(sectionIndex);
    }

    public ElementField getOrCreate(int sectionIndex) {
        return sections.computeIfAbsent(sectionIndex, i -> new ElementField());
    }

    public boolean has(int sectionIndex) {
        return sections.containsKey(sectionIndex);
    }

    public Collection<ElementField> fields() {
        return sections.values();
    }

    public Map<Integer, ElementField> sectionMap() {
        return sections;
    }

    public int sectionCount() {
        return sections.size();
    }

    public void clear() {
        sections.clear();
        generated = false;
    }

    // ------------------------------------------------------------- 序列化

    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        int skipped = 0;
        for (Map.Entry<Integer, ElementField> entry : sections.entrySet()) {
            // 稳态 section 不写盘：读档时按 baseVersion 重算，能省掉 99% 的体积
            if (entry.getValue().isPristine()) {
                skipped++;
                continue;
            }
            CompoundTag sectionTag = entry.getValue().serializeNBT();
            sectionTag.putInt("Section", entry.getKey());
            list.add(sectionTag);
        }
        root.put("Sections", list);
        root.putBoolean("Generated", generated);
        root.putInt("BaseVersion", baseVersion);
        return root;
    }

    public void deserializeNBT(CompoundTag root) {
        sections.clear();
        ListTag list = root.getList("Sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag sectionTag = list.getCompound(i);
            int index = sectionTag.getInt("Section");
            ElementField field = new ElementField();
            field.deserializeNBT(sectionTag);
            sections.put(index, field);
        }
        generated = root.getBoolean("Generated");
        baseVersion = root.getInt("BaseVersion");
    }

    // --------------------------------------------------------------- 提供者

    /** chunk capability 的挂载体 */
    public static final class Provider implements ICapabilitySerializable<CompoundTag>
    {
        private final ElementFieldStorage storage = new ElementFieldStorage();
        private final LazyOptional<ElementFieldStorage> optional = LazyOptional.of(() -> storage);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            return CAPABILITY.orEmpty(capability, optional);
        }

        @Override
        public CompoundTag serializeNBT() {
            return storage.serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            storage.deserializeNBT(nbt);
        }

        public void invalidate() {
            optional.invalidate();
        }
    }
}
