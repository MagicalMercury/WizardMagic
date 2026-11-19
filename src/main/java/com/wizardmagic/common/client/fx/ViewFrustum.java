package com.wizardmagic.common.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * 云级剔除：**距离剔除 + 背后剔除 + 视锥锥体角度剔除**，不碰投影矩阵。
 *
 * <p>为什么不用完整视锥：要从 {@code 投影 × 视图} 里抽 6 个平面，而
 * {@code AFTER_WEATHER} 阶段能拿到的 modelview 未必是"世界→相机"（实测直接导致
 * 整朵云被判成不可见、什么都画不出来）。这里用相机位置 + 朝向 + FOV 做判定，
 * 数学上不可能把"正前方的东西"误判成不可见：</p>
 *
 * <pre>
 *   dist − 半径 &gt; 192                           → 超出粒子裁距
 *   proj + 支撑函数 &lt; 0                          → 整个 AABB 在相机平面背后
 *   acos(cos夹角) − asin(半径/距离) &gt; 对角半角    → 整个 AABB 在视锥外侧
 * </pre>
 *
 * <p><b>为什么必须有第三条</b>：前两条只判"背后"。一个 8×13×8 格的火焰风暴，
 * 包围球半径 ≈ 12.6 格；当 8–10 朵围在玩家十几格范围内时，"中心在背后 12 格内"
 * 的云全都从"背后判据"里漏过去。而侧后方恰恰是这种摆法里数量最多的那一批——
 * 锥体判据就是补这个盲区的，它按 FOV 的对角半角切，带 {@code asin(半径/距离)}
 * 容差，所以只会多画、不会漏画。</p>
 */
public final class ViewFrustum {

    /** AABB 外扩（格）：抵消"相机数据落后一帧"和插值半格 */
    private static final double MARGIN = 4.0;
    /** 与 SparkBatch 的裁距一致 */
    private static final double MAX_DIST = 192.0;
    /** 锥体判据的额外余量：4°，抵掉旋转延迟与 AABB 外的拖尾火花 */
    private static final double CONE_MARGIN = Math.toRadians(4.0);
    /** 粒子级剔除的边界余量：3°，抵掉相机/插值的一帧延迟 */
    private static final double POINT_MARGIN = Math.toRadians(3.0);

    private static volatile double camX;
    private static volatile double camY;
    private static volatile double camZ;
    private static volatile double dirX;
    private static volatile double dirY = -1.0;
    private static volatile double dirZ;
    /** 视锥对角半角（弧度），在 capture 里按 FOV 与窗口宽高比刷新 */
    private static volatile double halfDiag = Math.toRadians(58.0);
    private static volatile boolean ready;

    /**
     * 相机角速度（弧度/秒，EMA 平滑）。
     *
     * <p><b>为什么需要它</b>：实例表是 20 Hz 重建的（每个 client tick 一次），所以一份
     * 剔除结果要被画 6～7 帧、最长约 50 ms。快速转视角时视野会在这 50 ms 里移动几十度，
     * 新进入视野的粒子当时没被写进实例表 ⇒ 屏幕边缘出现一块黑的，要等下一次重建才补上。
     * 所以剔除余量必须**跟着角速度放大**。</p>
     */
    private static volatile double angVel;
    /** 上一帧的视线方向（算角速度用） */
    private static volatile double prevX = 1.0;
    private static volatile double prevY;
    private static volatile double prevZ;
    private static volatile long lastCaptureNanos;
    /** 余量要覆盖的时间窗：实例重建间隔（50 ms）+ 一帧（≈10 ms） */
    private static final double CULL_HORIZON = 0.065;
    /** 动态余量上限，别把视锥撑成半球 */
    private static final double MAX_EXTRA = Math.toRadians(40.0);
    /** 本帧的动态余量（弧度），云级锥体判据也要加它 */
    private static volatile double coneExtra;

    /**
     * 粒子级剔除用的**快照**（每帧在 capture 里刷一次）：
     * {@code [0..2]=相机 [3..5]=视线 [6..8]=右 [9..11]=上 [12]=tan(水平半角) [13]=tan(竖直半角)}。
     *
     * <p>为什么要快照：{@code fill()} 里每帧要判 20–40 万颗粒子，如果每颗都去读
     * {@code volatile} 字段，光是内存序就够呛。所以每帧取一次引用，循环里只读数组。</p>
     */
    private static final double[] SNAP = new double[14];

    /**
     * 最近一次 {@link #isVisible} 的中间量，诊断用（渲染线程单线程访问，不分配）：
     * {@code [0]=proj(格) [1]=dist(格) [2]=夹角(度)}；判死时 {@code [2]} 是负的原因码。
     */
    private static final double[] LAST = new double[3];

    /** 关掉剔除做 A/B：{@code -Dwizardmagic.mirror.cull=0} */
    private static final boolean ENABLED =
            !"0".equals(System.getProperty("wizardmagic.mirror.cull"));

    private ViewFrustum() {
    }

    /** 渲染阶段调用：记下相机位置、朝向与视锥半角（一帧一次）。 */
    public static void capture(RenderLevelStageEvent event) {
        var camera = event.getCamera();
        if (camera == null) {
            return;
        }
        Vec3 pos = camera.getPosition();
        double yaw = Math.toRadians(camera.getYRot());
        double pitch = Math.toRadians(camera.getXRot());
        double cp = Math.cos(pitch);
        // 先用旧方向把角速度算出来，再覆盖
        double nx = -Math.sin(yaw) * cp;
        double ny = -Math.sin(pitch);
        double nz = Math.cos(yaw) * cp;
        long now = System.nanoTime();
        updateAngVel(prevX, prevY, prevZ, nx, ny, nz, now);
        prevX = nx;
        prevY = ny;
        prevZ = nz;
        lastCaptureNanos = now;
        // MC 约定：yaw=0 看 +Z；pitch>0 看下方
        dirX = nx;
        dirY = ny;
        dirZ = nz;
        camX = pos.x;
        camY = pos.y;
        camZ = pos.z;
        halfDiag = computeHalfDiag();
        refreshSnapshot();
        ready = true;
    }

    /** 方向变化量 / 帧间隔 → 角速度（EMA 平滑，抗单帧抖动） */
    private static void updateAngVel(double ox, double oy, double oz,
                                     double nx, double ny, double nz, long nowNanos) {
        long last = lastCaptureNanos;
        if (last == 0L || nowNanos <= last) {
            return;
        }
        double dt = (nowNanos - last) / 1.0e9;
        if (dt > 0.25) {
            return;   // 长时间没渲染（暂停/切窗口），不当作旋转
        }
        double dot = ox * nx + oy * ny + oz * nz;
        if (dot > 1.0) {
            dot = 1.0;
        } else if (dot < -1.0) {
            dot = -1.0;
        }
        double w = Math.acos(dot) / dt;
        // 平滑：单帧的抖动不该立刻把视锥撑开
        angVel = angVel * 0.7 + w * 0.3;
    }

    /**
     * 刷新粒子级剔除快照：正交基（右/上）+ 两个方向的 tan(半角)。
     *
     * <p>右向量取 {@code cross(视线, 世界上方)}，正上/正下看时退化，此时固定成 +X。</p>
     */
    private static void refreshSnapshot() {
        double fx = dirX;
        double fy = dirY;
        double fz = dirZ;
        double rx = -fz;
        double rz = fx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1.0E-6) {
            rx = 1.0;
            rz = 0.0;
            rl = 1.0;
        }
        rx /= rl;
        rz /= rl;
        // up = cross(right, fwd)，其中 right 的 y 分量恒为 0
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        double tanY;
        double tanX;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var window = mc.getWindow();
            double aspect = Math.max(0.5, (double) window.getWidth() / Math.max(1, window.getHeight()));
            // 动态余量：快速转视角时把视锥按角速度撑开，覆盖"实例表重建间隔"内的视野位移。
            // 不撑开的话，新进入视野的粒子没被写进实例表 ⇒ 屏幕边缘一块黑的。
            double extra = Math.min(MAX_EXTRA, angVel * CULL_HORIZON);
            coneExtra = extra;
            double halfY = Math.toRadians(mc.options.fov().get()) * 0.5 + POINT_MARGIN + extra;
            double halfX = Math.atan(Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5) * aspect)
                    + POINT_MARGIN + extra;
            double cap = Math.toRadians(89.0);
            tanY = Math.tan(Math.min(cap, halfY));
            tanX = Math.tan(Math.min(cap, halfX));
        } catch (Throwable ignored) {
            tanY = 0.75;
            tanX = 1.25;
        }

        SNAP[0] = camX;
        SNAP[1] = camY;
        SNAP[2] = camZ;
        SNAP[3] = fx;
        SNAP[4] = fy;
        SNAP[5] = fz;
        SNAP[6] = rx;
        SNAP[7] = 0.0;
        SNAP[8] = rz;
        SNAP[9] = ux;
        SNAP[10] = uy;
        SNAP[11] = uz;
        SNAP[12] = tanX;
        SNAP[13] = tanY;
    }

    /**
     * 取本帧快照；还没抓到相机时返回 {@code null}（调用方据此整段跳过剔除）。
     */
    public static double[] snapshot() {
        return ready && ENABLED ? SNAP : null;
    }

    /**
     * 单颗粒子级剔除：球体近似（半径 {@code r}），**保守**——只会多画，不会漏画。
     *
     * <p>三个判据全是点积 + 比较，没有 {@code sqrt}/{@code acos}：</p>
     * <pre>
     *   fz + r &lt; 0                        → 整颗在相机平面背后
     *   |fx| − r &gt; (fz + r)·tanHalfX      → 整颗在左右边界外
     *   |fy| − r &gt; (fz + r)·tanHalfY      → 整颗在上下边界外
     * </pre>
     *
     * <p>{@code (max(fz,0) + r)} 而不是 {@code fz}：把锥体沿轴向前推一个半径，
     * 贴脸的大粒子不会被近平面误判；用 {@code max(fz,0)} 是为了在“粒子已经越过
     * 近平面一点点”时不再收紧判据（否则会误剔掉就在镜头边上的粒子）。</p>
     */
    public static boolean pointVisible(double[] m, double x, double y, double z, double r) {
        double dx = x - m[0];
        double dy = y - m[1];
        double dz = z - m[2];
        double fz = dx * m[3] + dy * m[4] + dz * m[5];
        if (fz + r < 0.0) {
            return false;
        }
        double depth = (fz > 0.0 ? fz : 0.0) + r;
        double fx = dx * m[6] + dy * m[7] + dz * m[8];
        double ax = fx < 0.0 ? -fx : fx;
        if (ax - r > depth * m[12]) {
            return false;
        }
        double fy = dx * m[9] + dy * m[10] + dz * m[11];
        double ay = fy < 0.0 ? -fy : fy;
        return ay - r <= depth * m[13];
    }

    /**
     * 视锥对角半角：竖直半角由 FOV 给，水平半角再乘宽高比，最后取对角。
     *
     * <p>取对角（而不是水平/竖直半角）是为了**保守**：一条判据同时盖住两个方向。</p>
     */
    private static double computeHalfDiag() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            double fovY = Math.toRadians(mc.options.fov().get());
            var window = mc.getWindow();
            double aspect = Math.max(0.5, (double) window.getWidth() / Math.max(1, window.getHeight()));
            double tanY = Math.tan(fovY * 0.5);
            double tanX = tanY * aspect;
            return Math.atan(Math.sqrt(tanX * tanX + tanY * tanY)) + CONE_MARGIN;
        } catch (Throwable ignored) {
            return Math.toRadians(54.0) + CONE_MARGIN;
        }
    }

    /** 还没抓到相机（启动第一帧）时返回 true，宁可多画也不漏画。 */
    public static boolean isVisible(double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ) {
        if (!ENABLED || !ready) {
            LAST[0] = 0.0;
            LAST[1] = 0.0;
            LAST[2] = 90.0;
            return true;
        }
        double cx = (minX + maxX) * 0.5;
        double cy = (minY + maxY) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double rx = (maxX - minX) * 0.5;
        double ry = (maxY - minY) * 0.5;
        double rz = (maxZ - minZ) * 0.5;
        double radius = Math.sqrt(rx * rx + ry * ry + rz * rz) + MARGIN;

        double dx = cx - camX;
        double dy = cy - camY;
        double dz = cz - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double proj = dx * dirX + dy * dirY + dz * dirZ;
        LAST[0] = proj;
        LAST[1] = dist;

        // 1) 距离剔除
        if (dist - radius > MAX_DIST) {
            LAST[2] = -1.0;
            return false;
        }
        // 2) 背后剔除：AABB 沿视线方向的**精确支撑函数**（|rx·dirX|+|ry·dirY|+|rz·dirZ|），
        //    比包围球紧，对细长的火焰风暴能多剔一些
        double support = Math.abs(rx * dirX) + Math.abs(ry * dirY) + Math.abs(rz * dirZ) + MARGIN;
        if (proj + support < 0.0) {
            LAST[2] = -2.0;
            return false;
        }
        // 3) 锥体剔除：相机落在 AABB 内时无条件可见
        if (dist <= radius) {
            LAST[2] = 0.0;
            return true;
        }
        double cos = proj / dist;
        if (cos > 1.0) {
            cos = 1.0;
        } else if (cos < -1.0) {
            cos = -1.0;
        }
        double angle = Math.acos(cos);
        // 半径带来的角度容差（clamp 到 90° 以内，避免巨大 AABB 把判据废掉）
        double tol = Math.asin(clampUnit(radius / dist));
        LAST[2] = Math.toDegrees(angle);
        // 云级判据同样要吃动态余量：整朵云被剔掉的代价比"一条黑边"更大
        return angle - tol <= halfDiag + coneExtra;
    }

    private static double clampUnit(double v) {
        if (v > 0.9995) {
            return 0.9995;
        }
        if (v < 0.0) {
            return 0.0;
        }
        return v;
    }

    /** 最近一次判定：{@code [proj, dist, 夹角°]}；判死时第 3 项是原因码（-1 距离 / -2 背后 / -3 锥体外）。 */
    public static double[] last() {
        return LAST;
    }

    /** 视锥对角半角（度），诊断用 */
    public static double halfDiagDegrees() {
        return Math.toDegrees(halfDiag);
    }

    public static double camX() {
        return camX;
    }

    public static double camY() {
        return camY;
    }

    public static double camZ() {
        return camZ;
    }

    /** 诊断串（日志用） */
    public static String describe() {
        return String.format(java.util.Locale.ROOT,
                "cam=(%.1f,%.1f,%.1f) dir=(%.2f,%.2f,%.2f) halfDiag=%.1f°+%.1f°(ω=%.0f°/s)%s",
                camX, camY, camZ, dirX, dirY, dirZ,
                halfDiagDegrees(), Math.toDegrees(coneExtra), Math.toDegrees(angVel),
                ready ? "" : "（未就绪）");
    }
}
