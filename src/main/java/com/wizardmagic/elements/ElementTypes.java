package com.wizardmagic.elements;

/**
 * 四大元素。ordinal 即数组下标（0=Earth, 1=Water, 2=Wind, 3=Fire）。
 * 数值范围固定 0..127，用 byte 存储（读时 & 0x7F）。
 */
public enum ElementTypes {
    Earth,
    Water,
    Wind,
    Fire;

    /** 元素种类数量 */
    public static final int COUNT = values().length;

    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 127;

    public static int clamp(int value) {
        if (value < MIN_VALUE) {
            return MIN_VALUE;
        }
        return Math.min(value, MAX_VALUE);
    }

    /** 安全下标：ordinal 一定在 0..3 */
    public int index() {
        return ordinal();
    }
}
