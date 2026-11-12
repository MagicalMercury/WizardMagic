package com.wizardmagic.common.network;

import com.wizardmagic.WizardMagic;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 模组网络频道（特效包 + 法术云包）。 */
public final class ModNetwork
{
    /** 协议版本：删掉 spin/up/out/hold（已移交脚本）后从 "7" 升到 "8" */
    private static final String VERSION = "8";

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
        CHANNEL.registerMessage(id++, SpellCloudSpawnPayload.class,
                SpellCloudSpawnPayload::encode, SpellCloudSpawnPayload::decode, SpellCloudSpawnPayload::handle);
        CHANNEL.registerMessage(id++, SpellCloudSnapshotPayload.class,
                SpellCloudSnapshotPayload::encode, SpellCloudSnapshotPayload::decode,
                SpellCloudSnapshotPayload::handle);
        CHANNEL.registerMessage(id++, SpellCloudRemovePayload.class,
                SpellCloudRemovePayload::encode, SpellCloudRemovePayload::decode, SpellCloudRemovePayload::handle);
    }
}
