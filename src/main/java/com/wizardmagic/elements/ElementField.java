package com.wizardmagic.elements;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * 单个 16×16×16 ChunkSection 的四元素场。
 *
 * <p>存储：4 组 4096 字节（每种元素一组），值域 0..127。
 * 另外保存一份 {@code base}（基础场快照），作为扩散回流的目标值。</p>
 *
 * <p>索引约定：{@code index = (y << 8) | (z << 4) | x}，x/y/z 均为 0..15。</p>
 */
public final class ElementField implements INBTSerializable<CompoundTag>
{
    public static final int SIZE = 16 * 16 * 16; // 4096
    public static final int EDGE = 16;
    private static final int MASK = 0x7F;

    /** 当前值：current[element.ordinal()][index] */
    private final byte[][] current = new byte[ElementTypes.COUNT][SIZE];
    /** 回流目标：base[element.ordinal()][index] */
    private final byte[][] base = new byte[ElementTypes.COUNT][SIZE];
    /** 扩散用的复用缓冲，避免每 tick 分配 16KB */
    private final byte[][] scratch = new byte[ElementTypes.COUNT][SIZE];

    /** 是否已经生成过（区分「未生成」与「全 0」） */
    private boolean generated;
    /** 是否与 base 有偏差（有扰动才需要扩散） */
    private boolean dirty;
    /** 上一次扩散的 tick（用于每格 20 tick 冷却，避免瞬间抹平） */
    private long lastStepTick;

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int x(int index) {
        return index & 15;
    }

    public static int z(int index) {
        return (index >> 4) & 15;
    }

    public static int y(int index) {
        return (index >> 8) & 15;
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    /** 有扰动（与 base 不同）时才需要参与扩散 */
    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** 扩散用的复用缓冲（内容每次由扩散逻辑覆盖） */
    public byte[] scratchArray(ElementTypes type) {
        return scratch[type.index()];
    }

    /** 上一次扩散发生的 tick */
    public long getLastStepTick() {
        return lastStepTick;
    }

    public void setLastStepTick(long lastStepTick) {
        this.lastStepTick = lastStepTick;
    }

    // ------------------------------------------------------------------ 读写

    public int get(ElementTypes type, int index) {
        return current[type.index()][index] & MASK;
    }

    public int getBase(ElementTypes type, int index) {
        return base[type.index()][index] & MASK;
    }

    public void set(ElementTypes type, int index, int value) {
        current[type.index()][index] = (byte) ElementTypes.clamp(value);
        dirty = true;
        // 刚被扰动：清掉冷却，下一次扫描立刻允许扩散一次
        lastStepTick = 0L;
    }

    public void setBase(ElementTypes type, int index, int value) {
        base[type.index()][index] = (byte) ElementTypes.clamp(value);
    }

    /** 加到基础场（生成/局部刷新用；不影响 dirty 与冷却） */
    public void addBase(ElementTypes type, int index, int delta) {
        int value = ElementTypes.clamp(getBase(type, index) + delta);
        base[type.index()][index] = (byte) value;
    }

    /** 只写当前值，不设 dirty（局部刷新时用来恢复扰动偏差） */
    public void setCurrent(ElementTypes type, int index, int value) {
        current[type.index()][index] = (byte) ElementTypes.clamp(value);
    }

    /** 返回实际写入后的值（clamp 之后） */
    public int add(ElementTypes type, int index, int delta) {
        int value = ElementTypes.clamp(get(type, index) + delta);
        set(type, index, value);
        return value;
    }

    /** 直接把所有元素置零（调试/测试用） */
    public void clear() {
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            java.util.Arrays.fill(current[t], (byte) 0);
        }
        dirty = true;
    }

    /** 把当前值复制成基础场 */
    public void snapshotBase() {
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            System.arraycopy(current[t], 0, base[t], 0, SIZE);
        }
        generated = true;
        // 生成后 current == base，处于稳态，不需要扩散
        dirty = false;
    }

    /**
     * 稳态：没有扰动，current 与 base 完全一致。
     *
     * <p>这类 section 不需要写盘——base 是确定性函数（种子 + 群系 + 方块），
     * 读档时按 baseVersion 重算即可；只有被扰动过的 section 才必须保存。</p>
     */
    public boolean isPristine() {
        // 注意：不能看 dirty。局部刷新会把 current==base 的 section 标脏，
        // 但只要两值一致，读档重算的结果就完全相同，没必要存。
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            if (!java.util.Arrays.equals(current[t], base[t])) {
                return false;
            }
        }
        return true;
    }

    /** 把基础场复制回当前值（回流测试用） */
    public void resetToBase() {
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            System.arraycopy(base[t], 0, current[t], 0, SIZE);
        }
        dirty = false;
    }

    /** 元素之间互相填充：把一组值写入当前值 */
    public void setAll(ElementTypes type, int value) {
        byte v = (byte) ElementTypes.clamp(value);
        java.util.Arrays.fill(current[type.index()], v);
    }

    // ------------------------------------------------------- 扩散用数组访问

    /** 当前值数组（只读约定：扩散时先 clone 再写） */
    public byte[] currentArray(ElementTypes type) {
        return current[type.index()];
    }

    /** 基础场数组（只读） */
    public byte[] baseArray(ElementTypes type) {
        return base[type.index()];
    }

    // ------------------------------------------------------------- 序列化

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Current", pack(current));
        tag.putByteArray("Base", pack(base));
        tag.putBoolean("Generated", generated);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains("Current", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            unpack(tag.getByteArray("Current"), current);
        }
        if (tag.contains("Base", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            unpack(tag.getByteArray("Base"), base);
        }
        generated = tag.getBoolean("Generated");
    }

    /** 把 4 组数组拼成一个 byte[COUNT * SIZE] */
    private static byte[] pack(byte[][] arrays) {
        byte[] out = new byte[ElementTypes.COUNT * SIZE];
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            System.arraycopy(arrays[t], 0, out, t * SIZE, SIZE);
        }
        return out;
    }

    private static void unpack(byte[] in, byte[][] arrays) {
        if (in.length != ElementTypes.COUNT * SIZE) {
            return;
        }
        for (int t = 0; t < ElementTypes.COUNT; t++) {
            System.arraycopy(in, t * SIZE, arrays[t], 0, SIZE);
        }
    }
}
