package com.wizardmagic.elements.fluid;

import com.wizardmagic.elements.flow.ElementBudget;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 流体域的生命周期管理：**按维度各持一个域**、按位移阈值重建、1 Hz 求解。
 *
 * <p>三点修正（相对旧版）：
 * <ul>
 *   <li>旧版 `field` 是单例，主世界/下界都有玩家时每 tick 交替重建；
 *       现在用 {@code Map<ResourceKey, FluidField>}，每个维度独立演化。</li>
 *   <li>旧版 `wantX = ((px-48)/4)*4`，玩家走 4 格就重建（注释却写 16 格）；
 *       现在按 {@link #REBUILD_DISTANCE} 对齐，必须移动 32 格才重建。</li>
 *   <li>重建时清空而不是重新分配，避免每 32 格一次 0.8 MB 垃圾。</li>
 * </ul>
 */
public final class FluidManager {

    /** 玩家移动超过这个距离才重建域（必须能被 CELL 整除）。 */
    private static final int REBUILD_DISTANCE = 32;

    private static final Map<ResourceKey<Level>, FluidField> FIELDS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> TICKS = new HashMap<>();
    private static ServerLevel lastLevel;

    private FluidManager() {
    }

    public static void tick(ServerLevel serverLevel) {
        // 无玩家的维度不参与模拟
        if (serverLevel.players().isEmpty()) {
            return;
        }
        ServerPlayer player = serverLevel.players().get(0);
        int px = player.blockPosition().getX();
        int py = player.blockPosition().getY();
        int pz = player.blockPosition().getZ();

        int half = FluidField.DOMAIN / 2;
        int wantX = ((px - half) / REBUILD_DISTANCE) * REBUILD_DISTANCE;
        int wantY = ((py - half) / REBUILD_DISTANCE) * REBUILD_DISTANCE;
        int wantZ = ((pz - half) / REBUILD_DISTANCE) * REBUILD_DISTANCE;

        ResourceKey<Level> dim = serverLevel.dimension();
        FluidField field = FIELDS.get(dim);
        if (field == null || field.originX != wantX || field.originY != wantY || field.originZ != wantZ) {
            if (field == null) {
                field = new FluidField();
                FIELDS.put(dim, field);
            } else {
                field.clear();   // 复用对象，不重新分配
            }
            field.originX = wantX;
            field.originY = wantY;
            field.originZ = wantZ;
            FluidMask.invalidate();
        }
        lastLevel = serverLevel;

        int t = TICKS.merge(dim, 1, Integer::sum);
        if (t % 20 != 0) {
            return;
        }

        // 1) 背景风：curl-noise，离散无散，直接覆写基础速度场
        FluidBackground.apply(field, t * 0.05);
        // 2) 地形：固体格的六个面通量清零（缓存，域不变不重扫）
        FluidMask.update(serverLevel, field);
        FluidMask.apply(field);
        // 3) 元素驱动的扰动叠加在背景风之上
        FluidForces.apply(serverLevel, field);
        // 4) 投影（只作用在扰动上；背景风已在零空间里）
        FluidSolver.solve(field);
    }

    /** 取指定维度的域（元素平流、光晕广播按各自的 level 调用）。 */
    public static FluidField field(ServerLevel level) {
        return level == null ? null : FIELDS.get(level.dimension());
    }

    /** 兼容旧调用：返回最近一次 tick 的维度。 */
    public static FluidField field() {
        return lastLevel == null ? null : FIELDS.get(lastLevel.dimension());
    }

    public static String stats() {
        FluidField field = field();
        if (field == null) {
            return "流体：未初始化（需要至少一个玩家）";
        }
        String sampleLine = "域内采样峰值 fire=" + FluidForces.lastMaxFire
                + " water=" + FluidForces.lastMaxWater + " wind=" + FluidForces.lastMaxWind;
        if (lastLevel != null && !lastLevel.players().isEmpty()) {
            net.minecraft.core.BlockPos p = lastLevel.players().get(0).blockPosition();
            sampleLine += String.format(" | 当前格 Earth=%d Water=%d Wind=%d Fire=%d",
                    com.wizardmagic.elements.ElementAccess.get(lastLevel, p, com.wizardmagic.elements.ElementTypes.Earth),
                    com.wizardmagic.elements.ElementAccess.get(lastLevel, p, com.wizardmagic.elements.ElementTypes.Water),
                    com.wizardmagic.elements.ElementAccess.get(lastLevel, p, com.wizardmagic.elements.ElementTypes.Wind),
                    com.wizardmagic.elements.ElementAccess.get(lastLevel, p, com.wizardmagic.elements.ElementTypes.Fire));
        }
        float maxV = field.maxAbsVelocity();
        return String.format(
                "流体域 origin=(%d,%d,%d) %d³ 格距=%d | maxDiv=%.3e 动能=%.4f |u|max=%.4f | %s | forces %s | total %s",
                field.originX, field.originY, field.originZ,
                FluidField.DOMAIN, FluidField.CELL,
                field.maxDivergence(), field.meanKinetic(), maxV,
                sampleLine,
                ElementBudget.summary("fluid.forces"),
                ElementBudget.summary("fluid.total"));
    }

    /** 域内中心 1/3 体积注入速度脉冲（诊断用）。 */
    public static boolean pulse() {
        FluidField field = field();
        if (field == null) {
            return false;
        }
        int lo = FluidField.N / 3;
        int hi = 2 * FluidField.N / 3;
        for (int i = lo; i < hi; i++)
            for (int j = lo; j < hi; j++)
                for (int k = lo; k < hi; k++) {
                    field.u[FluidField.uf(i, j, k)] += 2.0f;
                    field.v[FluidField.vf(i, j, k)] += 1.0f;
                    field.w[FluidField.wf(i, j, k)] += 0.5f;
                }
        FluidSolver.faceToCell(field);
        return true;
    }

    public static boolean solveNow() {
        FluidField field = field();
        if (field == null) {
            return false;
        }
        FluidSolver.solve(field);
        return true;
    }

    public static void clear() {
        for (FluidField f : FIELDS.values()) {
            f.clear();
        }
    }

    /** 可视化：每隔 2 格采样一次速度，非零处生成一个粒子（返回粒子数）。 */
    public static int visualize(ServerLevel serverLevel) {
        FluidField field = field(serverLevel);
        if (field == null) {
            return 0;
        }
        if (field.maxAbsVelocity() < 1e-6f) {
            pulse();
        }
        FluidSolver.faceToCell(field);
        float[] out = new float[3];
        int count = 0;
        for (int i = 0; i < FluidField.N; i += 2)
            for (int j = 0; j < FluidField.N; j += 2)
                for (int k = 0; k < FluidField.N; k += 2) {
                    double wx = field.originX + (i + 0.5) * FluidField.CELL;
                    double wy = field.originY + (j + 0.5) * FluidField.CELL;
                    double wz = field.originZ + (k + 0.5) * FluidField.CELL;
                    field.sample(wx, wy, wz, out);
                    double speed = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
                    if (speed < 0.05) {
                        continue;
                    }
                    serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                            wx, wy, wz, 1, out[0] * 0.02, out[1] * 0.02, out[2] * 0.02, 0.0);
                    count++;
                }
        return count;
    }
}
