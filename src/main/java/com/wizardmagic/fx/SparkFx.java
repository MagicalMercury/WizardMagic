package com.wizardmagic.fx;

import com.wizardmagic.network.ModNetwork;
import com.wizardmagic.network.SparkPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * 特效公共 API（服务端调用，自动广播给附近玩家）。
 *
 * <pre>
 * // 一簇爆发
 * SparkFx.burst(level, pos, 120, 0x66CCFF, 0.7f, 0.35f, 0.35f, 50);
 *
 * // 拖尾：每 tick 在实体位置撒几个
 * SparkFx.trail(level, entity.position(), 3, 0xFFAA33, 0.25f, 0.1f);
 * </pre>
 *
 * <p>参数说明：count 光点数、color 0xRRGGBB、spread 初始散布半径（格）、
 * speed 初速度（格/tick）、size 光点直径（格）、life 寿命（tick）。</p>
 */
public final class SparkFx
{
    /** 超过这个距离的玩家不接收特效包 */
    private static final double VISIBLE_RANGE = 64.0;

    private SparkFx() {
    }

    /** 单次爆发：在 pos 周围 spread 半径内生成 count 个光点 */
    public static void burst(ServerLevel level, Vec3 pos, int count, int color,
                             float spread, float speed, float size, int life) {
        if (count <= 0) {
            return;
        }
        broadcast(level, pos, count, color, spread, speed, size, life);
    }

    /** 拖尾：调用方每 tick 自己调用，count 建议 1~4 */
    public static void trail(ServerLevel level, Vec3 pos, int count, int color,
                             float spread, float speed) {
        burst(level, pos, count, color, spread, speed, 0.22f, 26);
    }

    /** 法术命中：一簇火花 + 一个短促的亮核 */
    public static void impact(ServerLevel level, Vec3 pos, int color) {
        burst(level, pos, 80, color, 0.35f, 0.45f, 0.30f, 34);
        burst(level, pos, 12, 0xFFFFFF, 0.08f, 0.12f, 0.55f, 18);
    }

    private static void broadcast(ServerLevel level, Vec3 pos, int count, int color,
                                  float spread, float speed, float size, int life) {
        SparkPayload payload = new SparkPayload(pos.x, pos.y, pos.z, spread, speed, color, size, life, count);
        double rangeSq = VISIBLE_RANGE * VISIBLE_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= rangeSq) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
            }
        }
    }
}
