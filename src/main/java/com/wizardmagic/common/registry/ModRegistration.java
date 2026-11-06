package com.wizardmagic.common.registry;

import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 所有注册表的唯一入口。
 *
 * <p>主模组类只调 {@link #register(IEventBus)} 一次；新增内容类别时在这里加一行，
 * 具体的 {@code DeferredRegister} 全部收在自己的类里。</p>
 */
public final class ModRegistration {

    private ModRegistration() {
    }

    public static void register(IEventBus modBus) {
        ModBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        ModMenuTypes.register(modBus);
        ModElements.register(modBus);
    }
}
