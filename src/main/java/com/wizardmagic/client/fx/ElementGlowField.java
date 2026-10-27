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
 *   <li>光点在方块内游走：水按 Gerstner 波速度场随涌浪起伏，其余元素随机漂移，
 *       越出格子后淡出并补位；</li>
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
            case 2 -> 4.5f;   // Wind: 9 格边长
            case 3 -> 1.0f;   // Fire: 2 格边长
            default -> 1.5f;  // Earth: 3 格边长
        };
    }

    /** 超过这个距离的聚类立即淡出，不等下一轮服务端刷新 */
    private static final double UNLOAD_DISTANCE_SQ = 96.0 * 96.0;

    /** Gerstner 波分量：{dirX, dirZ, 波长, 振幅}，三个不同方向/尺度叠加。
     *  波长远大于水聚类尺寸（7 格），簇内才是一段平缓的涌浪而不是高频抖动；
     *  振幅 0.22/0.12/0.06 合计约 0.4 格，是肉眼可见又不脱离方块范围的起伏。 */
    private static final double[][] OCEAN_WAVES = {
            {0.96, 0.28, 32.0, 0.30},
            {0.55, 0.83, 19.0, 0.16},
            {0.90, -0.44, 11.0, 0.08}
    };

    /** Gerstner 水平位移的陡度系数 Q：0 是纯正弦，1 时波峰自交 */
    private static final double WAVE_STEEPNESS = 0.6;
    /** 深水色散 ω=√(gk) 在 MC 尺度上偏快（λ=32 时 ω≈1.39 rad/s，周期 4.5 秒），
     *  略微放慢到 0.8 得到约 5.7 秒的涌浪周期——既能看出浪形，又不至于原地呼吸。 */
    private static final double WAVE_TIME_SCALE = 0.8;
    /** 逻辑 tick 时长（秒）：速度是格/秒，除以它换成「格/tick」 */
    private static final double SECONDS_PER_TICK = 0.05;
    /** 水的越界余量：波形位移叠加在静止位置上，而静止位置本身可以贴近 range，
     *  不留余量会让刚生成的光点立刻被判出界 */
    private static final float WAVE_MARGIN = 0.5f;

    private static final Map<Long, Glow> GLOWS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    /** 水域网格边长（用于判断岸在哪个方向），与水聚类的 CELL_SIZE 一致 */
    private static final int SHORE_CELL = 7;
    /** 朝岸涌浪的水平速度（格/tick） */
    private static final float SHORE_SPEED = 0.030F;
    private static int shoreTick;
    private static final net.minecraft.core.BlockPos.MutableBlockPos PROBE =
            new net.minecraft.core.BlockPos.MutableBlockPos();
    private static final long START_MILLIS = System.currentTimeMillis();

    /** 秒级时间，用于给趋势场做缓慢变化（风向摆动、水波起伏） */
    private static double now() {
        return (System.currentTimeMillis() - START_MILLIS) / 1000.0;
    }

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
            float flowX = entry.length > 5 ? entry[3] / 60.0f : 0.0f;
            float flowY = entry.length > 5 ? entry[4] / 60.0f : 0.0f;
            float flowZ = entry.length > 5 ? entry[5] / 60.0f : 0.0f;
            Glow glow = GLOWS.get(key);
            if (glow == null || glow.type != type) {
                if (glow != null) {
                    glow.dispose();
                }
                Glow created = new Glow(key, posKey, type, count);
                created.flowX = flowX;
                created.flowY = flowY;
                created.flowZ = flowZ;
                GLOWS.put(key, created);
            } else {
                // 之前淡出过就恢复，并把亮度拉回来（fade 之前可能已经降到一半）
                glow.fading = false;
                glow.fade = 1.0f;
                // 流场低通：服务端每 10 tick 推一次新值，直接覆盖会让光点逐帧跳变，
                // 用 25% 混合让方向连续过渡。
                glow.flowX += (flowX - glow.flowX) * 0.25F;
                glow.flowY += (flowY - glow.flowY) * 0.25F;
                glow.flowZ += (flowZ - glow.flowZ) * 0.25F;
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

    /**
     * 重建「岸」的方向：把水聚类量化到 SHORE_CELL 网格上，
     * 某个方向的邻居网格里没有水 → 那边就是水域的边界，浪往那边涌。
     */
    private static void updateShores() {
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        for (Glow glow : GLOWS.values()) {
            if (glow.type != 1) {
                continue;
            }
            occupied.add(gridKey(Math.round(glow.baseX / SHORE_CELL),
                    Math.round(glow.baseZ / SHORE_CELL)));
        }
        if (occupied.isEmpty()) {
            return;
        }
        for (Glow glow : GLOWS.values()) {
            if (glow.type != 1) {
                continue;
            }
            int gx = Math.round(glow.baseX / SHORE_CELL);
            int gz = Math.round(glow.baseZ / SHORE_CELL);
            float dx = 0.0F;
            float dz = 0.0F;
            if (!occupied.contains(gridKey(gx + 1, gz))) {
                dx += 1.0F;
            }
            if (!occupied.contains(gridKey(gx - 1, gz))) {
                dx -= 1.0F;
            }
            if (!occupied.contains(gridKey(gx, gz + 1))) {
                dz += 1.0F;
            }
            if (!occupied.contains(gridKey(gx, gz - 1))) {
                dz -= 1.0F;
            }
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01F) {
                glow.shoreX = dx / len;
                glow.shoreZ = dz / len;
            } else {
                // 四面都有水：区域内部，没有岸的方向
                glow.shoreX = 0.0F;
                glow.shoreZ = 0.0F;
            }
        }
    }

    private static long gridKey(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
    }

    /** 每 tick 推进：随机游走、出格淡出重生、淡出组移除 */
    public static void tick() {
        if (GLOWS.isEmpty()) {
            return;
        }
        if (++shoreTick % 20 == 0) {
            updateShores();
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
        /** 静止位置（相对聚类中心）：水的波形采样基准，生成后不再变 */
        final float[] restX;
        final float[] restY;
        final float[] restZ;
        final float[] velX;
        final float[] velY;
        final float[] velZ;
        final float[] alpha;
        final float[] phase;
        final float range;
        /** 光点是否存活：漂出 cell 后置 false，冷却结束时补一个新点 */
        final boolean[] alive;
        /** 补位冷却（tick） */
        final int[] respawnCooldown;
        /** 地元素：在空气格中累计的 tick 数 */
        final int[] airTicks;
        /** 朝最近「岸」的水平方向（单位向量），0,0 表示四周都是水 */
        float shoreX;
        float shoreZ;
        /** 流场速度（格/秒，服务端广播来的），作为光点运动的基础 */
        float flowX;
        float flowY;
        float flowZ;

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
            restX = new float[count];
            restY = new float[count];
            restZ = new float[count];
            velX = new float[count];
            velY = new float[count];
            velZ = new float[count];
            alpha = new float[count];
            phase = new float[count];
            alive = new boolean[count];
            respawnCooldown = new int[count];
            airTicks = new int[count];
            for (int i = 0; i < count; i++) {
                phase[i] = RANDOM.nextFloat() * 6.2831f;
            }

            for (int i = 0; i < count; i++) {
                spawnOne(i, true);
            }
        }

        /** 在 cell 内随机位置生成一个光点；initial=true 时直接满亮。
         *  复用已持有的 Spark（不重新分配池槽位），只有首次才真正申请。 */
        private void spawnOne(int i, boolean initial) {
            respawn(i);
            alpha[i] = initial ? 1.0f : 0.0f;
            alive[i] = true;
            airTicks[i] = 0;
            respawnCooldown[i] = 0;
            if (sparks[i] == null) {
                sparks[i] = SparkBatch.get().spawnPersistent(
                        baseX + offsetX[i], baseY + offsetY[i], baseZ + offsetZ[i],
                        COLORS[type], 0.20f);
            }
        }

        /** 漂出 cell（或地元素滞留空气超时）后隐藏，等冷却结束再复用。
         *  不 kill：保留池槽位，仅把 alpha 压到 0——顶点着色器会直接剔除它。 */
        private void removeOne(int i) {
            alive[i] = false;
            respawnCooldown[i] = 5 + RANDOM.nextInt(10);
            if (sparks[i] != null) {
                sparks[i].alphaMul = 0.0f;
            }
        }

        private void respawn(int i) {
            // 静止位置：整个聚类立方内的随机点（风 9 格、火 2 格、地 3 格、水 7 格）
            restX[i] = (RANDOM.nextFloat() - 0.5f) * 2.0f * range;
            restY[i] = (RANDOM.nextFloat() - 0.5f) * 2.0f * range;
            restZ[i] = (RANDOM.nextFloat() - 0.5f) * 2.0f * range;
            // 水的起伏由波形驱动，随机抖动只当气泡噪声，压小以免盖过波浪
            float jitter = type == 1 ? 0.008f : 0.05f;
            velX[i] = (RANDOM.nextFloat() - 0.5f) * jitter;
            velY[i] = (RANDOM.nextFloat() - 0.5f) * jitter;
            velZ[i] = (RANDOM.nextFloat() - 0.5f) * jitter;
            offsetX[i] = restX[i];
            offsetY[i] = restY[i];
            offsetZ[i] = restZ[i];
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

            double time = now();
            var level = net.minecraft.client.Minecraft.getInstance().level;
            for (int i = 0; i < count; i++) {
                // 光点已漂出 cell：等冷却结束后在 cell 内补一个新点
                if (!alive[i]) {
                    if (--respawnCooldown[i] <= 0) {
                        spawnOne(i, false);
                    }
                    continue;
                }

                // 流场只作为扰动（格/秒 → 格/tick，系数 0.02 而非 0.05）：
                // curl-noise 是涡旋场，相邻格子方向天然不同，耦合太强会把各元素的主导趋势冲散。
                float trendX = flowX * 0.02F;
                float trendY = flowY * 0.02F;
                float trendZ = flowZ * 0.02F;
                switch (type) {
                    case 2 -> {
                        // 风：主导方向由大尺度背景风给出（+X，略偏 -Z），流场扰动只做细节
                        trendX += 0.030F;
                        trendZ -= 0.008F;
                        float gustX = 0.010F * (float) Math.sin(time * 1.7 + phase[i]);
                        float gustZ = 0.010F * (float) Math.cos(time * 1.5 + phase[i] * 1.3F);
                        float heightFactor = (float) Math.min(1.0,
                                Math.max(0.35, 0.35 + (baseY - 63.0) / 200.0));
                        trendX += gustX * heightFactor;
                        trendZ += gustZ * heightFactor;
                    }
                    case 3 -> {
                        // 火：总体趋势就是向上，不再叠加流场的 Y 分量
                        // （flowY 每次 solve 都会变，直接吃进来会出现「某一帧突然直直向上」的突兀运动）。
                        trendY = 0.018F + (RANDOM.nextFloat() - 0.5F) * 0.012F;
                        // 横向仍受流场影响：火柱被风吹歪，但不会被吹倒
                        trendX *= 0.5F;
                        trendZ *= 0.5F;
                    }
                    case 1 -> {
                        // 垂直：Gerstner 波速度场（波形起伏）
                        double vy = 0.0;
                        for (double[] cfg : OCEAN_WAVES) {
                            double k = 2.0 * Math.PI / cfg[2];
                            double omega = Math.sqrt(9.8 * k) * WAVE_TIME_SCALE;
                            double theta = k * ((baseX + restX[i]) * cfg[0]
                                    + (baseZ + restZ[i]) * cfg[1]) - omega * time;
                            vy -= omega * cfg[3] * Math.cos(theta);
                        }
                        trendY = (float) (vy * SECONDS_PER_TICK);

                        // 水平：朝最近的「岸」涌。岸 = 水域网格里没有水的邻居方向，
                        // 由 updateShores() 每 20 tick 重建一次。
                        if (shoreX != 0.0F || shoreZ != 0.0F) {
                            trendX = shoreX * SHORE_SPEED;
                            trendZ = shoreZ * SHORE_SPEED;
                        } else {
                            // 四面都是水（区域内部）：只保留一点点整体漂移
                            trendX = 0.004F;
                        }
                    }
                    case 0 ->
                        // 地：缓慢下沉（+= 保留流场横向扰动）
                        trendY -= 0.018F;
                    default -> {
                    }
                }

                offsetX[i] += velX[i] + trendX;
                offsetY[i] += velY[i] + trendY;
                offsetZ[i] += velZ[i] + trendZ;

                // 地元素例外：漂进洞穴空气后开始计时，满 10 tick 就消失，
                // 即使之后又回到岩石也不会恢复。
                if (type == 0 && level != null) {
                    PROBE.set((int) Math.floor(baseX + offsetX[i]),
                            (int) Math.floor(baseY + offsetY[i]),
                            (int) Math.floor(baseZ + offsetZ[i]));
                    if (level.getBlockState(PROBE).isAir()) {
                        if (++airTicks[i] >= 10) {
                            removeOne(i);
                            continue;
                        }
                    } else {
                        airTicks[i] = 0;
                    }
                }

                // 风：按「到生成点的球面距离」判断，半径 1.5 × CELL_SIZE
                // （range = CELL_SIZE / 2，所以 1.5 × CELL_SIZE = 3 × range）
                boolean out;
                if (type == 2) {
                    float windRange = 3.0F * range;
                    float distSq = offsetX[i] * offsetX[i]
                            + offsetY[i] * offsetY[i]
                            + offsetZ[i] * offsetZ[i];
                    out = distSq > windRange * windRange;
                } else {
                    // 水的波形位移叠加在静止位置上，静止位置本身可以贴近 range，
                    // 要留出波形振幅的余量，否则刚生成的光点会立刻被判出界
                    float limit = type == 1 ? range + WAVE_MARGIN : range;
                    out = Math.abs(offsetX[i]) > limit
                            || Math.abs(offsetY[i]) > limit
                            || Math.abs(offsetZ[i]) > limit;
                }

                if (out) {
                    // 漂出 cell：淡出后真消失，等冷却再补位（不再原地重置）
                    alpha[i] -= 0.08f;
                    if (alpha[i] <= 0.0f) {
                        removeOne(i);
                        continue;
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
