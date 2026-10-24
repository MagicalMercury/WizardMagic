package com.wizardmagic.network;

import com.wizardmagic.client.fx.ElementGlowField;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：玩家周围「元素值 >= 80」的方块列表。
 *
 * <p>每条是 (BlockPos.asLong, 元素类型 ordinal, 光点数量)，一次包批量发送。</p>
 */
public class ElementGlowPayload
{
    private final List<long[]> entries;

    public ElementGlowPayload(List<long[]> entries) {
        this.entries = entries;
    }

    public static void encode(ElementGlowPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.size());
        for (long[] entry : payload.entries) {
            buf.writeLong(entry[0]);
            buf.writeByte((int) entry[1]);
            buf.writeByte((int) entry[2]);
        }
    }

    public static ElementGlowPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<long[]> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new long[]{buf.readLong(), buf.readByte(), buf.readByte()});
        }
        return new ElementGlowPayload(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ElementGlowField.apply(entries);
            }
        });
        ctx.setPacketHandled(true);
    }
}
