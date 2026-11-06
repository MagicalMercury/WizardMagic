package com.wizardmagic.core.fusion;

import java.util.List;

/**
 * 一个元素定义（纯数据，不含显示名）。
 *
 * <p>显示名走语言文件：{@link #translationKey()} → {@code element.wizardmagic.&lt;id&gt;}。
 * 这样数据表只有 id / 阶数 / 配方三件事，加语言不需要动 JSON。</p>
 *
 * @param id     稳定标识（配方用这个引用，不要用显示名）
 * @param tier   阶数：0 = 基础元素，其余 = max(输入两元素阶数) + 1
 * @param recipe 配方（两个输入元素的 id）；0 阶为 {@code null}
 */
public record FusionElement(String id, int tier, List<String> recipe) {

    public boolean isBase() {
        return recipe == null || recipe.isEmpty();
    }

    /** 语言文件键，例如 {@code element.wizardmagic.light}。 */
    public String translationKey() {
        return "element.wizardmagic." + id;
    }

    /** 配方是无序的，这里给出规范化的配对键。 */
    public static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
