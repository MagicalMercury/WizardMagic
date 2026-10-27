package com.wizardmagic.network;

import com.wizardmagic.client.fx.ElementGlowField;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：玩家周围满足阈值的方块列表（每条 = 一个光点聚类）。
 *
 * <p><b>线格式（v2）</b>：包内只发一次原点（3 个 varint），之后每条 10 字节：
 * <pre>
 *   short dx, dy, dz      相对原点的偏移（±32767，广播半径 96 格远够用）
 *   byte  type | count<<2 低 2 位元素类型、高位光点数量（1 或 3）
 *   byte  velX, velY, velZ 流场速度，int8 量化（1/60 格每秒）
 * </pre>
 * 旧格式每条 13 字节（long 位置 + 2 个 byte + 3 个 byte）。4096 条时 53 KB → 41 KB。
 */
public class ElementGlowPayload
{
    private final List<long[]> entries;

    public ElementGlowPayload(List<long[]> entries) {
        this.entries = entries;
    }

    public static void encode(ElementGlowPayload payload, FriendlyByteBuf buf) {
        if (payload.entries.isEmpty()) {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            return;
        }
        long first = payload.entries.get(0)[0];
        int ox = BlockPos.getX(first);
        int oy = BlockPos.getY(first);
        int oz = BlockPos.getZ(first);
        buf.writeVarInt(ox);
        buf.writeVarInt(oy);
        buf.writeVarInt(oz);
        buf.writeVarInt(payload.entries.size());
        for (long[] entry : payload.entries) {
            long pos = entry[0];
            int dx = BlockPos.getX(pos) - ox;
            int dy = BlockPos.getY(pos) - oy;
            int dz = BlockPos.getZ(pos) - oz;
            buf.writeShort(dx);
            buf.writeShort(dy);
            buf.writeShort(dz);
            buf.writeByte(((int) entry[1] & 0x03) | (((int) entry[2] & 0x3F) << 2));
            buf.writeByte((int) entry[3]);
            buf.writeByte((int) entry[4]);
            buf.writeByte((int) entry[5]);
        }
    }

    public static ElementGlowPayload decode(FriendlyByteBuf buf) {
        int ox = buf.readVarInt();
        int oy = buf.readVarInt();
        int oz = buf.readVarInt();
        int size = buf.readVarInt();
        List<long[]> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = ox + buf.readShort();
            int y = oy + buf.readShort();
            int z = oz + buf.readShort();
            int packed = buf.readByte() & 0xFF;
            entries.add(new long[]{
                    BlockPos.asLong(x, y, z),
                    packed & 0x03,
                    (packed >> 2) & 0x3F,
                    buf.readByte(), buf.readByte(), buf.readByte()});
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
