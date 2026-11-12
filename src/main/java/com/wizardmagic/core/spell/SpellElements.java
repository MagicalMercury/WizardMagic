package com.wizardmagic.core.spell;

import com.wizardmagic.core.fusion.ElementFusion;
import com.wizardmagic.core.fusion.FusionElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 元素索引与物理量的桥接层。
 *
 * <p>网络包里的 {@code elementId} 是**元素表下标**（{@code ElementFusion} 的 JSON 顺序），
 * 不是 {@code ElementTypes} 的 ordinal（那只有四个基础元素）。</p>
 *
 * <p>P1 的 {@code gravity}/{@code volatility} 先用内置表（四个基础元素 + spec §3.3 的
 * 覆盖值）；等元素表补上 {@code composition} 后改成从 JSON 派生，见 CHANGE.md 待定项。</p>
 */
public final class SpellElements
{
    private static final List<FusionElement> ELEMENTS = ElementFusion.get().elements();
    private static final String[] IDS = new String[ELEMENTS.size()];
    private static final Map<String, Integer> INDEX = new HashMap<>();
    private static final Map<String, Double> GRAVITY = new HashMap<>();
    private static final Map<String, Double> VOLATILITY = new HashMap<>();
    private static final Map<String, Integer> COLORS = new HashMap<>();

    /** 非基础元素的临时回退色（等 elements.json 补 color） */
    private static final int FALLBACK_COLOR = 0xFFC0C0FF;

    static
    {
        for (int i = 0; i < ELEMENTS.size(); i++)
        {
            String id = ELEMENTS.get(i).id();
            IDS[i] = id;
            INDEX.put(id, i);
        }

        // 四个基础元素（spec §3.3 的 baseGravity / baseVolatility）
        put("earth", 0.9, 0.15, 0x7A5230);
        put("water", 0.5, 0.50, 0x2E6FD8);
        put("air", -0.4, 1.00, 0xD8E6F0);
        put("fire", -0.9, 0.80, 0xFF6A2A);

        // spec §3.3 要求手写 overrides 的元素（只取 gravity / volatility 两列）
        put("light", -0.7, 0.90, 0);
        put("dark", 0.1, 0.40, 0);
        put("plant", 0.3, 0.30, 0);
        put("metal", 1.0, 0.10, 0);
        put("motion", 0.0, 0.20, 0);
        put("mist", -0.1, 0.90, 0);
        put("thunder", -0.5, 0.90, 0);
        put("ice", 0.2, 0.25, 0);
        put("magma", 0.7, 0.30, 0);
        put("void", -0.2, 0.50, 0);
        put("healing", -0.4, 0.60, 0);
        put("poison", 0.2, 0.50, 0);
        put("gravity", 1.5, 0.10, 0);
        put("magnet", 0.8, 0.20, 0);
    }

    private static void put(String id, double gravity, double volatility, int color)
    {
        GRAVITY.put(id, gravity);
        VOLATILITY.put(id, volatility);
        if (color != 0)
        {
            COLORS.put(id, color);
        }
    }

    private SpellElements()
    {
    }

    public static int count()
    {
        return IDS.length;
    }

    /** 元素表下标 → id；越界返回 null */
    public static String id(int index)
    {
        return index >= 0 && index < IDS.length ? IDS[index] : null;
    }

    /** 元素 id → 元素表下标；未知返回 -1 */
    public static int indexOf(String id)
    {
        Integer i = INDEX.get(id);
        return i == null ? -1 : i;
    }

    /** 所有元素 id（命令补全用） */
    public static String[] ids()
    {
        return IDS.clone();
    }

    /** 元素尚未提供物理量时返回 0（中性），volatility 返回 0.5 */
    public static double gravity(int index)
    {
        String id = id(index);
        return id == null ? 0.0 : GRAVITY.getOrDefault(id, 0.0);
    }

    public static double volatility(int index)
    {
        String id = id(index);
        return id == null ? 0.5 : VOLATILITY.getOrDefault(id, 0.5);
    }

    /** 元素颜色，{@code 0xAARRGGBB}；非基础元素暂时回退到紫白 */
    public static int colorArgb(int index)
    {
        String id = id(index);
        if (id == null)
        {
            return FALLBACK_COLOR;
        }
        return COLORS.getOrDefault(id, FALLBACK_COLOR);
    }

    /** 是否仍在用临时回退色（首次渲染时提示一次） */
    public static boolean hasOwnColor(int index)
    {
        String id = id(index);
        return id != null && COLORS.containsKey(id);
    }
}
