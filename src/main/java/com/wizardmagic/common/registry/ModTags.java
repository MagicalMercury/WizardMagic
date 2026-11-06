package com.wizardmagic.common.registry;

import com.wizardmagic.WizardMagic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 模组用到的物品标签。
 *
 * <p>{@link #RESEARCH_PAPERS} 是研究台纸张槽的准入名单。内容模组要新增一种纸张，
 * 只需要在自己的数据包里往 {@code wizardmagic:research_papers} 加一个 id，
 * 核心模组不需要改代码。</p>
 */
public final class ModTags {

    public static final TagKey<Item> RESEARCH_PAPERS =
            ItemTags.create(new ResourceLocation(WizardMagic.MODID, "research_papers"));

    private ModTags() {
    }
}
