package com.wizardmagic.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：销毁一朵法术云。
 *
 * <p>附带服务端在最后一步算出的 {@code hash} 与步数，客户端可以拿自己做逐位比对，
 * 用于验证「同 seed 双端轨迹一致」。</p>
 */
public class SpellCloudRemovePayload
{
    public final int id;
    public final int steps;
    public final long hash;

    public SpellCloudRemovePayload(int id, int steps, long hash)
    {
        this.id = id;
        this.steps = steps;
        this.hash = hash;
    }

    public static void encode(SpellCloudRemovePayload payload, FriendlyByteBuf buf)
    {
        buf.writeVarInt(payload.id);
        buf.writeVarInt(payload.steps);
        buf.writeLong(payload.hash);
    }

    public static SpellCloudRemovePayload decode(FriendlyByteBuf buf)
    {
        return new SpellCloudRemovePayload(buf.readVarInt(), buf.readVarInt(), buf.readLong());
    }

    public void handle(Supplier<NetworkEvent.Context> context)
    {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT)
            {
                com.wizardmagic.common.client.spell.SpellCloudClient.INSTANCE.onRemove(this);
            }
        });
        ctx.setPacketHandled(true);
    }
}
