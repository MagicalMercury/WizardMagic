package com.wizardmagic.network;

import com.wizardmagic.WizardMagic;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端的光点生成包。纯视觉，不写存档。
 *
 * <p>一次包生成一整簇光点（burst），避免万级光点产生上万个小包。</p>
 */
public class SparkPayload
{
    private final double x;
    private final double y;
    private final double z;
    private final float spread;
    private final float speed;
    private final int color;
    private final float size;
    private final int life;
    private final int count;

    public SparkPayload(double x, double y, double z, float spread, float speed,
                        int color, float size, int life, int count) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.spread = spread;
        this.speed = speed;
        this.color = color;
        this.size = size;
        this.life = life;
        this.count = count;
    }

    public static void encode(SparkPayload payload, FriendlyByteBuf buf) {
        buf.writeDouble(payload.x);
        buf.writeDouble(payload.y);
        buf.writeDouble(payload.z);
        buf.writeFloat(payload.spread);
        buf.writeFloat(payload.speed);
        buf.writeInt(payload.color);
        buf.writeFloat(payload.size);
        buf.writeVarInt(payload.life);
        buf.writeVarInt(payload.count);
    }

    public static SparkPayload decode(FriendlyByteBuf buf) {
        return new SparkPayload(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                buf.readInt(), buf.readFloat(),
                buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            // 服务端也会加载本类，必须显式判断；客户端类只在这个分支里被引用
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                com.wizardmagic.client.fx.SparkBatch.get()
                        .spawnBurst(x, y, z, count, color, spread, speed, size, life);
            }
        });
        ctx.setPacketHandled(true);
    }
}
