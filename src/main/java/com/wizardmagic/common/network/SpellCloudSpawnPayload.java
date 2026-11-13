package com.wizardmagic.common.network;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.core.spell.script.Value;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：生成一朵法术云。
 *
 * <p>只发一次（不是逐 tick 同步）；客户端用同样的元素、形状、seed 与（脚本云的）
 * 源码复算。玩家进入范围时若有进行中的云，改发 {@link SpellCloudSnapshotPayload}。</p>
 *
 * <p>协议 8：删掉 {@code spin/up/out/hold}——旋涡、上升气流、回位弹簧已经移进
 * {@code .spell}，不再占用网络字段。</p>
 */
public class SpellCloudSpawnPayload
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
    /** 基础定律参数（非脚本云） */
    public final float drag;
    public final float energyInit;
    public final float energyDecay;
    /** 常驻云（法阵 / 结界）：客户端不会因为寿命到点而删除它 */
    public final boolean persistent;
    /** 服务端在 ageSteps = 0 时的状态哈希，客户端可当场验证初始化是否逐位一致 */
    public final long initHash;
    /** 行为脚本 id；空串 = 基础定律路径 */
    public final String scriptId;
    /** 行为脚本源码（P1 随包下发，客户端解析后跑同一解释器） */
    public final String scriptSource;
    /** 云级变量（cast 作用域快照） */
    public final Map<String, Value> scriptVars;
    /** 服务端生成时的 gameTime（客户端据此快进补步） */
    public final long spawnGameTime;

    public SpellCloudSpawnPayload(int id, int elementId, int count, long seed,
                                  String shapeId, ShapeParams shapeParams,
                                  double originX, double originY, double originZ, float lifeSec,
                                  double faceX, double faceY, double faceZ,
                                  float drag, float energyInit, float energyDecay,
                                  boolean persistent, long initHash,
                                  String scriptId, String scriptSource, Map<String, Value> scriptVars,
                                  long spawnGameTime)
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
        this.initHash = initHash;
        this.scriptId = scriptId == null ? "" : scriptId;
        this.scriptSource = scriptSource == null ? "" : scriptSource;
        this.scriptVars = scriptVars == null ? Map.of() : scriptVars;
        this.spawnGameTime = spawnGameTime;
    }

    public static void encode(SpellCloudSpawnPayload payload, FriendlyByteBuf buf)
    {
        buf.writeVarInt(payload.id);
        buf.writeVarInt(payload.elementId);
        buf.writeVarInt(payload.count);
        buf.writeLong(payload.seed);
        encodeShape(buf, payload.shapeId, payload.shapeParams);
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
        buf.writeLong(payload.initHash);
        buf.writeLong(payload.spawnGameTime);
        SpellScriptCodec.write(buf, payload.scriptId, payload.scriptSource, payload.scriptVars);
    }

    public static SpellCloudSpawnPayload decode(FriendlyByteBuf buf)
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
        // 顺序必须与 encode 完全一致：initHash → spawnGameTime → 脚本三件套
        long initHash = buf.readLong();
        long spawnGameTime = buf.readLong();
        String scriptId = SpellScriptCodec.readId(buf);
        String scriptSource = SpellScriptCodec.readSource(buf);
        Map<String, Value> scriptVars = SpellScriptCodec.readVars(buf);
        return new SpellCloudSpawnPayload(id, elementId, count, seed, shapeId, params,
                ox, oy, oz, life, fx, fy, fz, drag, energyInit, energyDecay, persistent, initHash,
                scriptId, scriptSource, scriptVars, spawnGameTime);
    }

    /** 形状编码：id + 参数。参数用 double——用 float 会在末位漂移，3000 粒的初始
     *  位置量化到 1/4096 后仍会翻位，服务端/客户端 initHash 偶发不一致。 */
    static void encodeShape(FriendlyByteBuf buf, String shapeId, ShapeParams p)
    {
        buf.writeUtf(shapeId == null ? "point" : shapeId, 32);
        buf.writeDouble(p.radius());
        buf.writeDouble(p.thickness());
        buf.writeDouble(p.height());
        buf.writeDouble(p.turns());
        buf.writeVarInt(p.sides());
        buf.writeDouble(p.length());
        buf.writeDouble(p.amplitude());
        buf.writeDouble(p.frequency());
        buf.writeDouble(p.radiusPulse());
        buf.writeDouble(p.radiusWave());
        buf.writeDouble(p.radiusSpeed());
    }

    public void handle(Supplier<NetworkEvent.Context> context)
    {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT)
            {
                com.wizardmagic.common.client.spell.SpellCloudClient.INSTANCE.onSpawn(this);
            }
        });
        ctx.setPacketHandled(true);
    }
}
