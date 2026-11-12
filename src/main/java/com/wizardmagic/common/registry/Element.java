package com.wizardmagic.common.registry;

import com.wizardmagic.core.fusion.FusionElement;
import com.wizardmagic.core.fusion.ElementFusion;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个「元素」——不是物品，也不是方块，而是注册表里的独立条目。
 *
 * <p>设计参考神秘时代的 {@code Aspect}：元素是一等数据对象，带稳定 id、阶数与配方；
 * 图标只是 GUI 贴图（{@code textures/gui/element/<id>.png}），物品与方块通过
 * 容器接口暴露自己含有的元素。</p>
 */
public final class Element {

    private final ResourceLocation id;
    private final int tier;
    private final String recipeA;
    private final String recipeB;

    public Element(ResourceLocation id, int tier, String recipeA, String recipeB) {
        this.id = id;
        this.tier = tier;
        this.recipeA = recipeA;
        this.recipeB = recipeB;
    }

    public static Element from(FusionElement data, ResourceLocation id) {
        String a = null;
        String b = null;
        if (data.recipe() != null && data.recipe().size() == 2) {
            a = data.recipe().get(0);
            b = data.recipe().get(1);
        }
        return new Element(id, data.tier(), a, b);
    }

    public ResourceLocation id() {
        return id;
    }

    public int tier() {
        return tier;
    }

    public boolean isBase() {
        return recipeA == null || recipeB == null;
    }

    public String recipeA() {
        return recipeA;
    }

    public String recipeB() {
        return recipeB;
    }

    /** 语言键，例如 {@code element.wizardmagic.light}。 */
    public String translationKey() {
        return "element.wizardmagic." + id.getPath();
    }

    /** GUI 图标路径，例如 {@code wizardmagic:textures/gui/element/light.png}。 */
    public ResourceLocation icon() {
        return new ResourceLocation(id.getNamespace(), "textures/gui/element/" + id.getPath() + ".png");
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
