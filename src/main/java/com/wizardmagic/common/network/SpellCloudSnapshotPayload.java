package com.wizardmagic.common.network;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.core.spell.script.Value;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：玩家进入 FULL 可见范围时，补发一朵**进行中**的云的完整状态。
 *
 * <p>不能只发生成包：云可能已经跑了很久（常驻法阵），客户端无法从 0 步快进。
 * 每个粒子 {@code x,y,z,vx,vy,vz,energy} 用 double 保精度（float 量化误差会跨过
 * 状态 hash 的 1/4096 格，导致双端不一致）。4096 粒约 213 KB，只发一次。</p>
 *
 * <p>协议 8：删掉 {@code spin/up/out/hold}。</p>
 */
public class SpellCloudSnapshotPayload
{
    public final int id;
    public final int elementId;
    public final int count;
    public final long seed;
    public final String shapeId;
    public final ShapeParams shapeParams;
    public final double originX;
    public final double originY;
    public final double originZ;
    /** 形状朝向（形状本地 +Y 轴指向它） */
    public final double faceX;
    public final double faceY;
    public final double faceZ;
    public final float lifeSec;
    public final float drag;
    public final float energyInit;
    public final float energyDecay;
    public final boolean persistent;
    public final int ageSteps;
    /** 长度 = count * 7：x,y,z,vx,vy,vz,energy */
    public final double[] state;
    /** 行为脚本 id；空串 = 基础定律路径 */
    public final String scriptId;
    /** 行为脚本源码 */
    public final String scriptSource;
    /** 云级变量 */
    public final Map<String, Value> scriptVars;

    public SpellCloudSnapshotPayload(int id, int elementId, int count, long seed,
                                     String shapeId, ShapeParams shapeParams,
                                     double originX, double originY, double originZ, float lifeSec,
                                     double faceX, double faceY, double faceZ,
                                     float drag, float energyInit, float energyDecay,
                                     boolean persistent, int ageSteps, double[] state,
                                     String scriptId, String scriptSource, Map<String, Value> scriptVars)
    {
        this.id = id;
        this.elementId = elementId;
        this.count = count;
        this.seed = seed;
        this.shapeId = shapeId;
        this.shapeParams = shapeParams;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.faceX = faceX;
        this.faceY = faceY;
        this.faceZ = faceZ;
        this.lifeSec = lifeSec;
        this.drag = drag;
        this.energyInit = energyInit;
        this.energyDecay = energyDecay;
        this.persistent = persistent;
        this.ageSteps = ageSteps;
        this.state = state;
        this.scriptId = scriptId == null ? "" : scriptId;
        this.scriptSource = scriptSource == null ? "" : scriptSource;
        this.scriptVars = scriptVars == null ? Map.of() : scriptVars;
    }

    public static void encode(SpellCloudSnapshotPayload payload, FriendlyByteBuf buf)
    {
        buf.writeVarInt(payload.id);
        buf.writeVarInt(payload.elementId);
        buf.writeVarInt(payload.count);
        buf.writeLong(payload.seed);
        SpellCloudSpawnPayload.encodeShape(buf, payload.shapeId, payload.shapeParams);
        buf.writeDouble(payload.originX);
        buf.writeDouble(payload.originY);
        buf.writeDouble(payload.originZ);
        buf.writeDouble(payload.faceX);
        buf.writeDouble(payload.faceY);
        buf.writeDouble(payload.faceZ);
        buf.writeFloat(payload.lifeSec);
        buf.writeFloat(payload.drag);
        buf.writeFloat(payload.energyInit);
        buf.writeFloat(payload.energyDecay);
        buf.writeBoolean(payload.persistent);
        buf.writeVarInt(payload.ageSteps);
        buf.writeVarInt(payload.state.length);
        for (double v : payload.state)
        {
            buf.writeDouble(v);
        }
        SpellScriptCodec.write(buf, payload.scriptId, payload.scriptSource, payload.scriptVars);
    }

    public static SpellCloudSnapshotPayload decode(FriendlyByteBuf buf)
    {
        int id = buf.readVarInt();
        int elementId = buf.readVarInt();
        int count = buf.readVarInt();
        long seed = buf.readLong();
        String shapeId = buf.readUtf(32);
        ShapeParams params = new ShapeParams(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
        double ox = buf.readDouble();
        double oy = buf.readDouble();
        double oz = buf.readDouble();
        double fx = buf.readDouble();
        double fy = buf.readDouble();
        double fz = buf.readDouble();
        float life = buf.readFloat();
        float drag = buf.readFloat();
        float energyInit = buf.readFloat();
        float energyDecay = buf.readFloat();
        boolean persistent = buf.readBoolean();
        int ageSteps = buf.readVarInt();
        int length = buf.readVarInt();
        double[] state = new double[length];
        for (int i = 0; i < length; i++)
        {
            state[i] = buf.readDouble();
        }
        return new SpellCloudSnapshotPayload(id, elementId, count, seed, shapeId, params,
                ox, oy, oz, life, fx, fy, fz, drag, energyInit, energyDecay, persistent, ageSteps, state,
                SpellScriptCodec.readId(buf), SpellScriptCodec.readSource(buf),
                SpellScriptCodec.readVars(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> context)
    {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT)
            {
                com.wizardmagic.common.client.spell.SpellCloudClient.INSTANCE.onSnapshot(this);
            }
        });
        ctx.setPacketHandled(true);
    }
}
