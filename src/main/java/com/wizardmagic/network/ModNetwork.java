package com.wizardmagic.network;

import com.wizardmagic.WizardMagic;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 模组网络频道（目前只有特效包）。 */
public final class ModNetwork
{
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(WizardMagic.MODID, "fx"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals)
            .simpleChannel();

    private ModNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SparkPayload.class,
                SparkPayload::encode, SparkPayload::decode, SparkPayload::handle);
        CHANNEL.registerMessage(id++, ElementGlowPayload.class,
                ElementGlowPayload::encode, ElementGlowPayload::decode, ElementGlowPayload::handle);
    }
}
