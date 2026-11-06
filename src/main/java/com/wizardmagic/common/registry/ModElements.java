package com.wizardmagic.common.registry;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.core.fusion.ElementFusion;
import com.wizardmagic.core.fusion.FusionElement;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.NewRegistryEvent;
import net.minecraftforge.registries.RegistryBuilder;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 要素注册表。
 *
 * <p>{@code wizardmagic:element} 是一个<b>自定义注册表</b>（不是物品、不是方块），
 * 由 Forge 的 {@link DeferredRegister#create(ResourceKey, String)} 自动通过
 * {@code NewRegistryEvent} 创建。其它模组可以按 id 查表，也可以往同一个注册表里加自己的要素。</p>
 *
 * <p>数据来源是 {@code data/wizardmagic/fusion/elements.json}：注册时逐个登记，
 * 加一个要素只需要改 JSON，不需要写 Java 代码。</p>
 */
public final class ModElements {

    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-elements");

    public static final ResourceKey<Registry<Element>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(new ResourceLocation(WizardMagic.MODID, "element"));

    public static final DeferredRegister<Element> ELEMENTS =
            DeferredRegister.create(REGISTRY_KEY, WizardMagic.MODID);

    /** 要素 id（path）→ 注册对象，按元素表顺序。 */
    private static final Map<String, RegistryObject<Element>> BY_ID = new LinkedHashMap<>();

    static {
        for (FusionElement data : ElementFusion.get().elements()) {
            String path = data.id();
            ResourceLocation id = new ResourceLocation(WizardMagic.MODID, path);
            BY_ID.put(path, ELEMENTS.register(path, () -> Element.from(data, id)));
        }
        LOGGER.info("queued {} elements for registry {}", BY_ID.size(), REGISTRY_KEY.location());
    }

    private ModElements() {
    }

    public static RegistryObject<Element> get(String path) {
        return BY_ID.get(path);
    }

    public static Map<String, RegistryObject<Element>> all() {
        return Collections.unmodifiableMap(BY_ID);
    }

    public static void register(IEventBus modBus) {
        // 1.20.1 的 DeferredRegister.create(ResourceKey, modid) 只负责登记条目，
        // 不会创建自定义注册表；缺这一步，RegisterEvent 阶段会静默跳过，
        // 随后 ObjectHolder 阶段报 "Unable to find registry with key wizardmagic:element"。
        modBus.addListener(ModElements::onNewRegistry);
        ELEMENTS.register(modBus);
    }

    private static void onNewRegistry(NewRegistryEvent event) {
        event.create(new RegistryBuilder<Element>().setName(REGISTRY_KEY.location()));
    }
}
