package com.wizardmagic.elements.event;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementFieldStorage;
import com.wizardmagic.elements.ElementTypes;
import com.wizardmagic.elements.generation.ElementGeneration;
import com.wizardmagic.network.ElementGlowPayload;
import com.wizardmagic.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端 → 客户端：把玩家周围「元素值够高」的区域发过去，客户端渲染漂浮光点。
 *
 * <p>每种元素有独立的聚类尺度与门槛（数组下标与 {@link ElementTypes} 的 ordinal 对齐）：
 * <pre>
 *   元素   cell   阈值   低值光点   高值光点(&gt;100)
 *   地      3     80      1          3
 *   水      3     80      1          3
 *   风      7     80      3         10
 *   火      2     60      1          3
 * </pre>
 *
 * <p>加速手段：</p>
 * <ul>
 *   <li>chunk 按玩家视线方向排序、section 按与玩家高度差排序，预算优先花在看得见的地方；</li>
 *   <li>元素场是平滑场，按聚类尺度做步进采样（风/地/水 step=2，火 step=1），遍历量降 3~8 倍；</li>
 *   <li>缺 section 时就地补算，每轮 {@link #SECTION_BUDGET} 个，避免一次性几百个造成卡顿。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class ElementGlowBroadcaster
{
    /** 刷新间隔（tick） */
    private static final int INTERVAL = 10;
    /** 扫描半径（chunk）：要盖住渲染淡出距离 64 格 */
    private static final int CHUNK_RADIUS = 3;
    /** 垂直扫描半高（格） */
    private static final int VERTICAL_RANGE = 64;
    /** 单玩家最多发多少个聚类（按到玩家的距离取最近的） */
    private static final int MAX_CELLS = 4096;
    /** 每轮扫描最多惰性补算多少个 section */
    private static final int SECTION_BUDGET = 32;

    //                                   Earth Water Wind Fire
    private static final int[] CELL_SIZE = {3, 7, 7, 2};
    private static final int[] THRESHOLD = {80, 90, 80, 60};
    private static final int[] LOW_COUNT = {1, 3, 3, 1};
    private static final int[] HIGH_COUNT = {3, 10, 10, 3};
    /** 采样步长：元素场平滑，cell 越大越可以跳着采样 */
    private static final int[] SAMPLE_STEP = {2, 2, 2, 1};
    private static final int HIGH_THRESHOLD = 100;
    /** 球形扫描半径（格）：和渲染淡出距离一致，避免方形边界出现尖角 */
    private static final double SCAN_RADIUS = 64.0;
    private static final double SCAN_RADIUS_SQ = SCAN_RADIUS * SCAN_RADIUS;

    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    private static int tickCounter;

    private ElementGlowBroadcaster() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % INTERVAL != 0) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                List<long[]> entries = scan(level, player);
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new ElementGlowPayload(entries));
                LOGGER.debug("element glow scan: player={} cells={}", player.getScoreboardName(), entries.size());
            }
        }
    }

    private static List<long[]> scan(ServerLevel level, ServerPlayer player) {
        BlockPos center = player.blockPosition();
        Vec3 look = player.getLookAngle();
        ElementTypes[] types = ElementTypes.values();
        int typeCount = types.length;

        @SuppressWarnings("unchecked")
        Map<Long, Cell>[] maps = new Map[typeCount];
        for (int t = 0; t < typeCount; t++) {
            maps[t] = new HashMap<>();
        }

        ChunkPos centerChunk = new ChunkPos(center);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        int yLow = Math.max(minY, center.getY() - VERTICAL_RANGE);
        int yHigh = Math.min(maxY - 1, center.getY() + VERTICAL_RANGE);
        int siMin = (yLow - minY) >> 4;
        int siMax = (yHigh - minY) >> 4;
        int playerSi = (center.getY() - minY) >> 4;

        double px = center.getX() + 0.5;
        double pz = center.getZ() + 0.5;
        double py = center.getY() + 0.5;

        // 1) chunk 按视线方向优先（dot 越大越先扫）
        List<ChunkPos> chunkOrder = new ArrayList<>();
        for (int cx = -CHUNK_RADIUS; cx <= CHUNK_RADIUS; cx++) {
            for (int cz = -CHUNK_RADIUS; cz <= CHUNK_RADIUS; cz++) {
                chunkOrder.add(new ChunkPos(centerChunk.x + cx, centerChunk.z + cz));
            }
        }
        chunkOrder.sort(Comparator.comparingDouble(cp -> {
            double dx = cp.getMinBlockX() + 8 - px;
            double dz = cp.getMinBlockZ() + 8 - pz;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-3) {
                return -1.0E9; // 脚下最优先
            }
            return -(dx * look.x + dz * look.z) / len;
        }));

        // 2) section 按离玩家高度近的先扫
        List<Integer> sectionOrder = new ArrayList<>();
        for (int si = siMin; si <= siMax; si++) {
            sectionOrder.add(si);
        }
        sectionOrder.sort(Comparator.comparingInt(si -> Math.abs(si - playerSi)));

        int budget = SECTION_BUDGET;

        for (ChunkPos cp : chunkOrder) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) {
                continue;
            }
            ElementFieldStorage storage = ElementAccess.storage(chunk);
            if (storage == null) {
                continue;
            }

            int baseX = chunk.getPos().getMinBlockX();
            int baseZ = chunk.getPos().getMinBlockZ();

            for (int si : sectionOrder) {
                ElementField field = storage.get(si);
                if (field == null) {
                    if (budget <= 0) {
                        continue;
                    }
                    budget--;
                    ElementGeneration.generateSection(level, chunk, storage, si);
                    field = storage.get(si);
                    if (field == null) {
                        continue;
                    }
                }

                int baseY = minY + (si << 4);

                for (int t = 0; t < typeCount; t++) {
                    int step = SAMPLE_STEP[t];
                    int cellSize = CELL_SIZE[t];
                    Map<Long, Cell> cells = maps[t];

                    for (int y = 0; y < 16; y += step) {
                        for (int z = 0; z < 16; z += step) {
                            for (int x = 0; x < 16; x += step) {
                                int index = ElementField.index(x, y, z);
                                int value = field.get(types[t], index);
                                if (value < THRESHOLD[t]) {
                                    continue;
                                }
                                int wx = baseX + x;
                                int wy = baseY + y;
                                int wz = baseZ + z;
                                // 球形范围：方形边界会从远处看出尖角
                                double ddx = wx - px;
                                double ddy = wy - py;
                                double ddz = wz - pz;
                                if (ddx * ddx + ddy * ddy + ddz * ddz > SCAN_RADIUS_SQ) {
                                    continue;
                                }
                                long key = cellKey(wx, wy, wz, cellSize);
                                Cell cell = cells.get(key);
                                if (cell == null) {
                                    cell = new Cell(wx, wy, wz, cellSize);
                                    cells.put(key, cell);
                                }
                                cell.add(wx, wy, wz, value);
                            }
                        }
                    }
                }
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int t = 0; t < typeCount; t++) {
            for (Cell cell : maps[t].values()) {
                int max = cell.maxValue();
                int count = max > HIGH_THRESHOLD ? HIGH_COUNT[t] : LOW_COUNT[t];
                candidates.add(new Candidate(
                        BlockPos.asLong(cell.centerX(), cell.centerY(), cell.centerZ()),
                        t, count, cell.distSq(px, py, pz)));
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::distSq));
        int limit = Math.min(MAX_CELLS, candidates.size());
        List<long[]> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Candidate candidate = candidates.get(i);
            out.add(new long[]{candidate.pos(), candidate.type(), candidate.count()});
        }
        return out;
    }

    private static long cellKey(int x, int y, int z, int cellSize) {
        long cx = Math.floorDiv(x, cellSize) & 0x1FFFFFL;
        long cy = Math.floorDiv(y, cellSize) & 0x1FFFFFL;
        long cz = Math.floorDiv(z, cellSize) & 0x1FFFFFL;
        return (cx << 42) | (cy << 21) | cz;
    }

    private record Candidate(long pos, int type, int count, double distSq) {
    }

    /** 一个聚类：平均位置 + 组内最大值 */
    private static final class Cell
    {
        final int gridX;
        final int gridY;
        final int gridZ;
        final int cellSize;
        long sumX;
        long sumY;
        long sumZ;
        int samples;
        int max;

        Cell(int x, int y, int z, int cellSize) {
            this.gridX = Math.floorDiv(x, cellSize);
            this.gridY = Math.floorDiv(y, cellSize);
            this.gridZ = Math.floorDiv(z, cellSize);
            this.cellSize = cellSize;
        }

        void add(int x, int y, int z, int value) {
            sumX += x;
            sumY += y;
            sumZ += z;
            samples++;
            if (value > max) {
                max = value;
            }
        }

        int centerX() {
            return (int) Math.round((double) sumX / samples);
        }

        int centerY() {
            return (int) Math.round((double) sumY / samples);
        }

        int centerZ() {
            return (int) Math.round((double) sumZ / samples);
        }

        int maxValue() {
            return max;
        }

        double distSq(double px, double py, double pz) {
            double dx = (gridX * cellSize + cellSize * 0.5) - px;
            double dy = (gridY * cellSize + cellSize * 0.5) - py;
            double dz = (gridZ * cellSize + cellSize * 0.5) - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
