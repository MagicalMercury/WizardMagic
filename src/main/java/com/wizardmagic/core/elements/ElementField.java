package com.wizardmagic.core.elements;

import com.wizardmagic.api.elements.ElementTypes;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.Arrays;

/**
 * 单个 16×16×16 ChunkSection 的四元素场。
 *
 * <p>存储：每格一个 {@code int}，四个元素各占 8 bit（低 7 位有效，值域 0..127）。
 * 相比 4 个独立的 {@code byte[4096]}，四个元素共享同一条缓存行，扩散和生成时
 * 访存次数降到 1/4，也为将来做 SIMD 留出布局。</p>
 *
 * <figure>
 * <pre>
 *   bits  31..24 | 23..16 | 15..8 | 7..0
 *          Fire  |  Wind  | Water | Earth
 * </pre>
 * </figure>
 *
 * <p>索引约定：{@code index = (y << 8) | (z << 4) | x}，x/y/z 均为 0..15。</p>
 *
 * <p>序列化格式与旧版完全一致（{@code byte[COUNT * SIZE]}，按元素分段），
 * 旧存档可以直接读。</p>
 */
public final class ElementField implements INBTSerializable<CompoundTag>
{
    public static final int SIZE = 16 * 16 * 16; // 4096
    public static final int EDGE = 16;
    private static final int MASK = 0x7F;
    private static final int SHIFT = 8;
    private static final int COUNT = ElementTypes.COUNT;
    /** 四个元素同时为 0 的快速判断掩码 */
    private static final int ALL_MASK = 0x7F7F7F7F;

    /** 当前值：每格一个 int，四元素打包 */
    private final int[] current = new int[SIZE];
    /** 回流目标：同样的打包格式 */
    private final int[] base = new int[SIZE];
    /** 扩散用的复用缓冲，避免每 tick 分配 16KB */
    private final int[] scratch = new int[SIZE];

    /** 魔力屏障位图：完全阻断（通透度 0）的格子，1 位一格 */
    private final long[] blocked = new long[SIZE >> 6];
    /** 部分通透格子的系数（0..127），懒分配；null 表示没有部分通透的格子 */
    private byte[] permeability;
    /** 本 section 是否含有任何魔力屏障（没有时所有屏障判定都能直接短路） */
    private boolean hasBarrier;

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
    public int[] scratchWords() {
        return scratch;
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
        return (current[index] >>> (type.index() * SHIFT)) & MASK;
    }

    public int getBase(ElementTypes type, int index) {
        return (base[index] >>> (type.index() * SHIFT)) & MASK;
    }

    public void set(ElementTypes type, int index, int value) {
        int shift = type.index() * SHIFT;
        current[index] = (current[index] & ~(MASK << shift))
                | ((ElementTypes.clamp(value) & MASK) << shift);
        dirty = true;
        // 刚被扰动：清掉冷却，下一次扫描立刻允许扩散一次
        lastStepTick = 0L;
    }

    public void setBase(ElementTypes type, int index, int value) {
        int shift = type.index() * SHIFT;
        base[index] = (base[index] & ~(MASK << shift))
                | ((ElementTypes.clamp(value) & MASK) << shift);
    }

    /** 加到基础场（生成/局部刷新用；不影响 dirty 与冷却） */
    public void addBase(ElementTypes type, int index, int delta) {
        int shift = type.index() * SHIFT;
        int value = ElementTypes.clamp(getBase(type, index) + delta);
        base[index] = (base[index] & ~(MASK << shift)) | ((value & MASK) << shift);
    }

    /** 只写当前值，不设 dirty（局部刷新时用来恢复扰动偏差） */
    public void setCurrent(ElementTypes type, int index, int value) {
        int shift = type.index() * SHIFT;
        current[index] = (current[index] & ~(MASK << shift))
                | ((ElementTypes.clamp(value) & MASK) << shift);
    }

    /** 返回实际写入后的值（clamp 之后） */
    public int add(ElementTypes type, int index, int delta) {
        int value = ElementTypes.clamp(get(type, index) + delta);
        set(type, index, value);
        return value;
    }

    /** 直接把所有元素置零（调试/测试用） */
    public void clear() {
        Arrays.fill(current, 0);
        dirty = true;
    }

    /** 把当前值复制成基础场 */
    public void snapshotBase() {
        System.arraycopy(current, 0, base, 0, SIZE);
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
        return Arrays.equals(current, base);
    }

    /** 把基础场复制回当前值（回流测试用） */
    public void resetToBase() {
        System.arraycopy(base, 0, current, 0, SIZE);
        dirty = false;
    }

    /** 元素之间互相填充：把一组值写入当前值 */
    public void setAll(ElementTypes type, int value) {
        int shift = type.index() * SHIFT;
        int packed = (ElementTypes.clamp(value) & MASK) << shift;
        int clearMask = ~(MASK << shift);
        for (int i = 0; i < SIZE; i++) {
            current[i] = (current[i] & clearMask) | packed;
        }
    }

    // ---------------------------------------------------- 魔力屏障标记

    public boolean hasBarrier() {
        return hasBarrier;
    }

    /**
     * 原生平流内核直接读这张位图（64 个 long，1 bit = 完全阻断）。
     *
     * <p><b>只读</b>：外面不要改它，否则和 {@link #setPermeability} 维护的状态不一致。</p>
     */
    public long[] blockedBits() {
        return blocked;
    }

    /**
     * 原生平流内核用的逐格通透度数组；可能是 {@code null}（等价于处处 127）。
     *
     * <p><b>只读</b>，理由同上。</p>
     */
    public byte[] permeabilityBytes() {
        return permeability;
    }

    /** 该格是否被完全阻断（通透度 0） */
    public boolean isBlocked(int index) {
        return hasBarrier && (blocked[index >> 6] & (1L << (index & 63))) != 0;
    }

    /**
     * 该格的魔力通透度，0..127（127 = 普通方块）。
     * 无屏障的 section 直接返回 {@link ManaBarrier#OPEN}，热路径零额外成本。
     */
    public int permeability(int index) {
        if (!hasBarrier) {
            return ManaBarrier.OPEN;
        }
        if ((blocked[index >> 6] & (1L << (index & 63))) != 0) {
            return ManaBarrier.BLOCKED;
        }
        return permeability == null ? ManaBarrier.OPEN : (permeability[index] & 0xFF);
    }

    /**
     * 设置一格的通透度。
     * <ul>
     *   <li>0：记为完全阻断，并把该格元素清零；</li>
     *   <li>1..126：记入系数数组（懒分配），元素可在内部存在；</li>
     *   <li>127：完全通透，不记录。</li>
     * </ul>
     */
    public void setPermeability(int index, int perm) {
        if (perm >= ManaBarrier.OPEN) {
            return;
        }
        hasBarrier = true;
        if (perm <= ManaBarrier.BLOCKED) {
            blocked[index >> 6] |= 1L << (index & 63);
            zeroCell(index);
            return;
        }
        if (permeability == null) {
            permeability = new byte[SIZE];
            Arrays.fill(permeability, (byte) ManaBarrier.OPEN);
        }
        permeability[index] = (byte) perm;
    }

    /** 清空所有屏障标记（重新扫描方块前调用） */
    public void clearBarriers() {
        Arrays.fill(blocked, 0L);
        permeability = null;
        hasBarrier = false;
    }

    /** 把一格的四元素当前值与基础值全部清零（完全阻断时用） */
    public void zeroCell(int index) {
        current[index] = 0;
        base[index] = 0;
    }

    // ------------------------------------------------------- 扩散用数组访问

    /** 当前值（每格一个打包 int）——扩散直接在此之上做位运算 */
    public int[] currentWords() {
        return current;
    }

    /** 基础场（每格一个打包 int），只读 */
    public int[] baseWords() {
        return base;
    }

    /** 在打包 int 里替换某个元素的 8 bit 字段 */
    public static int setElem(int word, int shift, int value) {
        int clamped = ElementTypes.clamp(value) & MASK;
        return (word & ~(MASK << shift)) | (clamped << shift);
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

    /** 打包 int[] -> byte[COUNT * SIZE]（旧格式：每个元素一段连续字节） */
    private static byte[] pack(int[] words) {
        byte[] out = new byte[COUNT * SIZE];
        for (int i = 0; i < SIZE; i++) {
            int word = words[i];
            for (int t = 0; t < COUNT; t++) {
                out[t * SIZE + i] = (byte) ((word >>> (t * SHIFT)) & MASK);
            }
        }
        return out;
    }

    private static void unpack(byte[] in, int[] words) {
        if (in.length != COUNT * SIZE) {
            return;
        }
        Arrays.fill(words, 0);
        for (int i = 0; i < SIZE; i++) {
            int word = 0;
            for (int t = 0; t < COUNT; t++) {
                word |= (in[t * SIZE + i] & MASK) << (t * SHIFT);
            }
            words[i] = word;
        }
    }
}
