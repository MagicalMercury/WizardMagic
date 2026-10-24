package com.wizardmagic.client.fx;

import com.wizardmagic.elements.ElementTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 元素场光点：把「元素值 >= 80」的方块渲染成漂浮光点。
 *
 * <ul>
 *   <li>值 80..100 → 1 个光点；&gt; 100 → 3 个；</li>
 *   <li>颜色：地 b57b23 / 水 00c3ff / 风 00c994 / 火 e42720（与 {@link ElementTypes} ordinal 对齐）；</li>
 *   <li>光点在方块内随机游走，越出格子后淡出并在格内重生；</li>
 *   <li>方块元素值降到阈值以下 → 整组光点淡出移除。</li>
 * </ul>
 *
 * <p>光点本身复用 {@link SparkBatch} 的池（persistent 模式），因此和法术光点共用
 * 同一次 instanced draw。</p>
 */
public final class ElementGlowField
{
    /** 颜色顺序必须与 ElementTypes 的 ordinal 一致：Earth, Water, Wind, Fire */
    private static final int[] COLORS = {0xb57b23, 0x00c3ff, 0x00c994, 0xe42720};

    private static final int THRESHOLD = 80;
    private static final int HIGH_THRESHOLD = 100;

    /**
     * 每种元素的游走半径（聚类边长的一半），下标与 ElementTypes.ordinal 对齐。
     * 风是 5×5×5、火是 2×2×2、地水是 3×3×3。
     */
    private static float rangeOf(int type) {
        return switch (type) {
            case 1 -> 3.5f;   // Water: 7 格边长
            case 2 -> 3.5f;   // Wind: 7 格边长
            case 3 -> 1.0f;   // Fire: 2 格边长
            default -> 1.5f;  // Earth: 3 格边长
        };
    }

    /** 超过这个距离的聚类立即淡出，不等下一轮服务端刷新 */
    private static final double UNLOAD_DISTANCE_SQ = 96.0 * 96.0;

    private static final Map<Long, Glow> GLOWS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private ElementGlowField() {
    }

    /** 收到服务端全量列表：新增/更新，未出现的淡出 */
    public static void apply(List<long[]> entries) {
        Set<Long> seen = new HashSet<>(Math.max(16, entries.size() * 2));
        for (long[] entry : entries) {
            long posKey = entry[0];
            int type = (int) entry[1];
            int count = (int) entry[2];
            if (type < 0 || type >= COLORS.length) {
                continue;
            }
            // key 必须带元素类型：地/水的聚类中心可能重合，只按 pos 会每秒互相 dispose 重建
            long key = glowKey(posKey, type);
            seen.add(key);
            Glow glow = GLOWS.get(key);
            if (glow == null || glow.type != type) {
                if (glow != null) {
                    glow.dispose();
                }
                GLOWS.put(key, new Glow(key, posKey, type, count));
            } else {
                // 之前淡出过就恢复，并把亮度拉回来（fade 之前可能已经降到一半）
                glow.fading = false;
                glow.fade = 1.0f;
            }
        }
        for (Glow glow : GLOWS.values()) {
            if (!seen.contains(glow.mapKey)) {
                glow.fading = true;
            }
        }
        SparkBatch.get().markDirty();
        org.slf4j.LoggerFactory.getLogger("wizardmagic-fx")
                .debug("glow apply: entries={} active={}", entries.size(), GLOWS.size());
    }

    /** 每 tick 推进：随机游走、出格淡出重生、淡出组移除 */
    public static void tick() {
        if (GLOWS.isEmpty()) {
            return;
        }
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        double px = player == null ? 0 : player.getX();
        double py = player == null ? 0 : player.getY();
        double pz = player == null ? 0 : player.getZ();

        boolean changed = false;
        Iterator<Glow> iterator = GLOWS.values().iterator();
        while (iterator.hasNext()) {
            Glow glow = iterator.next();
            // 远离玩家的聚类立刻开始淡出，不用等服务端下一轮刷新
            if (player != null && glow.distanceToSqr(px, py, pz) > UNLOAD_DISTANCE_SQ) {
                glow.fading = true;
            }
            if (glow.tick()) {
                changed = true;
            }
            if (glow.removed) {
                iterator.remove();
            }
        }
        if (changed || !GLOWS.isEmpty()) {
            SparkBatch.get().markDirty();
        }
    }

    public static int activeCount() {
        return GLOWS.size();
    }

    public static void clear() {
        for (Glow glow : GLOWS.values()) {
            glow.dispose();
        }
        GLOWS.clear();
    }

    /** 聚类 key：位置 + 元素类型 */
    private static long glowKey(long pos, int type) {
        return pos * 4L + type;
    }

    private static final class Glow
    {
        final long mapKey;
        final int type;
        final int count;
        final BlockPos base;
        final float baseX;
        final float baseY;
        final float baseZ;

        final SparkBatch.Spark[] sparks;
        final float[] offsetX;
        final float[] offsetY;
        final float[] offsetZ;
        final float[] velX;
        final float[] velY;
        final float[] velZ;
        final float[] alpha;
        final float range;

        float fade = 0.0f;
        boolean fading;
        boolean removed;

        Glow(long mapKey, long posKey, int type, int count) {
            this.mapKey = mapKey;
            this.type = type;
            this.count = count;
            this.base = BlockPos.of(posKey);
            this.baseX = base.getX() + 0.5f;
            this.baseY = base.getY() + 0.5f;
            this.baseZ = base.getZ() + 0.5f;
            this.range = rangeOf(type);

            int color = COLORS[type];
            sparks = new SparkBatch.Spark[count];
            offsetX = new float[count];
            offsetY = new float[count];
            offsetZ = new float[count];
            velX = new float[count];
            velY = new float[count];
            velZ = new float[count];
            alpha = new float[count];

            for (int i = 0; i < count; i++) {
                respawn(i);
                alpha[i] = 1.0f;
                sparks[i] = SparkBatch.get().spawnPersistent(
                        baseX + offsetX[i], baseY + offsetY[i], baseZ + offsetZ[i], color, 0.20f);
            }
        }

        private void respawn(int i) {
            // 光点在整个聚类立方内游走（风 5 格、火 2 格、地水 3 格）
            offsetX[i] = (RANDOM.nextFloat() - 0.5f) * 2.0f * range;
            offsetY[i] = (RANDOM.nextFloat() - 0.5f) * 2.0f * range;
            offsetZ[i] = (RANDOM.nextFloat() - 0.5f) * 2.0f * range;
            velX[i] = (RANDOM.nextFloat() - 0.5f) * 0.05f;
            velY[i] = (RANDOM.nextFloat() - 0.5f) * 0.05f;
            velZ[i] = (RANDOM.nextFloat() - 0.5f) * 0.05f;
        }

        /** @return 是否有变化 */
        boolean tick() {
            if (fading) {
                fade -= 0.06f;
                if (fade <= 0.0f) {
                    dispose();
                    removed = true;
                    return true;
                }
            } else if (fade < 1.0f) {
                fade = Math.min(1.0f, fade + 0.12f);
            }

            for (int i = 0; i < count; i++) {
                offsetX[i] += velX[i];
                offsetY[i] += velY[i];
                offsetZ[i] += velZ[i];

                boolean out = Math.abs(offsetX[i]) > range
                        || Math.abs(offsetY[i]) > range
                        || Math.abs(offsetZ[i]) > range;

                if (out) {
                    alpha[i] -= 0.08f;
                    if (alpha[i] <= 0.0f) {
                        respawn(i);
                        alpha[i] = 0.0f; // 下一 tick 起逐渐回亮
                    }
                } else if (alpha[i] < 1.0f) {
                    alpha[i] = Math.min(1.0f, alpha[i] + 0.08f);
                }

                SparkBatch.Spark spark = sparks[i];
                if (spark == null) {
                    continue;
                }
                spark.prevX = spark.x;
                spark.prevY = spark.y;
                spark.prevZ = spark.z;
                spark.x = baseX + offsetX[i];
                spark.y = baseY + offsetY[i];
                spark.z = baseZ + offsetZ[i];
                spark.alphaMul = Math.max(0.0f, alpha[i]) * fade;
            }
            return true;
        }

        double distanceToSqr(double px, double py, double pz) {
            double dx = baseX - px;
            double dy = baseY - py;
            double dz = baseZ - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        void dispose() {
            for (SparkBatch.Spark spark : sparks) {
                if (spark != null) {
                    SparkBatch.get().kill(spark);
                }
            }
        }
    }
}
