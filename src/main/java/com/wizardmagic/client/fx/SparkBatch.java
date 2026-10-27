package com.wizardmagic.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 客户端光点池（数据侧）。
 *
 * <p>渲染走独立 GL 管线 + GPU instancing（见 {@link SparkInstancedRenderer}）：
 * CPU 每 tick 只把活跃光点写成一张 11 float/实例 的表，每帧最多上传一次，
 * 顶点展开由 GPU 用 divisor 完成。</p>
 *
 * <h2>坐标约定（路线 A）</h2>
 * <pre>
 *   view = R * (p - C)
 *   顶点 = view + (aPos * size, 0)
 * </pre>
 * R 由 shader 的 uViewRot（mat3，列主序）表示，C 是相机位置。
 */
public final class SparkBatch
{
    /** burst 区容量：法术爆发光点，按寿命消失，池满时循环覆盖最老的 */
    private static final int BURST_CAPACITY = 262144;
    /** 元素光点专用区容量：不会被 burst 挤占，只能由 ElementGlowField 增删 */
    private static final int PERSIST_CAPACITY = 65535;
    /** 同时存在的最大光点数（burst 区 + 元素区） */
    public static final int CAPACITY = BURST_CAPACITY + PERSIST_CAPACITY;

    private static final SparkBatch INSTANCE = new SparkBatch();

    private final Spark[] pool = new Spark[CAPACITY];
    private int burstCount;
    private int persistCount;
    private int replaceCursor;
    private int persistCursor;

    /** 实例表：每光点 11 float，见 SparkInstancedRenderer 的布局说明 */
    private final float[] instanceData = new float[CAPACITY * SparkInstancedRenderer.FLOATS_PER_INSTANCE];
    private boolean instanceDirty = true;

    private final RandomSource random = RandomSource.create();

    /** 相机位置（render 时更新），用于「就近优先」排序 */
    private double camX;
    private double camY;
    private double camZ;
    private boolean camValid;
    /** 每帧最多绘制多少个实例：法术特效优先，超限时元素光点整组让位 */
    private static final int MAX_DRAW = 262144;
    /** 排序间隔（tick）：0 表示已失效 */
    private int sortTimer;
    private final int[] sortOrder = new int[CAPACITY];
    private int sortCount;
    /** 本 tick 实际写入实例表的数量（≤ MAX_DRAW） */
    private int instanceCount;

    private SparkBatch() {
        for (int i = 0; i < CAPACITY; i++) {
            pool[i] = new Spark();
        }
    }

    public static SparkBatch get() {
        return INSTANCE;
    }

    public int activeCount() {
        return burstCount + persistCount;
    }

    /** 与 shader 的 uTime 同一时间基准（tick 单位），用于计算光点寿命。
     *  用启动时刻做基准：原来的 `millis % 3600000` 每小时回绕，会让所有光点的
     *  t 归零、整点闪一下。 */
    private static final long START_MILLIS = System.currentTimeMillis();

    private static float now() {
        return (float) ((System.currentTimeMillis() - START_MILLIS) / 50.0);
    }

    public void spawnBurst(double x, double y, double z, int amount, int color,
                           float spread, float speed, float size, int life) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float birth = now();

        for (int i = 0; i < amount; i++) {
            Spark spark;
            if (burstCount < BURST_CAPACITY) {
                spark = pool[burstCount++];
            } else {
                spark = pool[replaceCursor];
                if (++replaceCursor >= BURST_CAPACITY) {
                    replaceCursor = 0;
                }
            }

            double theta = random.nextDouble() * Math.PI * 2.0;
            double cosPhi = random.nextDouble() * 2.0 - 1.0;
            double sinPhi = Math.sqrt(Math.max(0.0, 1.0 - cosPhi * cosPhi));

            double dirX = sinPhi * Math.cos(theta);
            double dirY = cosPhi;
            double dirZ = sinPhi * Math.sin(theta);

            double radius = spread * Math.cbrt(random.nextDouble());
            double velocity = speed * (0.35 + random.nextDouble() * 0.65);

            spark.x = x + dirX * radius;
            spark.y = y + dirY * radius;
            spark.z = z + dirZ * radius;
            // 出生瞬间 prev == 当前，避免第一帧从原点插值
            spark.prevX = spark.x;
            spark.prevY = spark.y;
            spark.prevZ = spark.z;
            spark.vx = dirX * velocity;
            spark.vy = dirY * velocity;
            spark.vz = dirZ * velocity;
            spark.age = 0;
            spark.life = Math.max(4, (int) (life * (0.7 + random.nextDouble() * 0.6)));
            spark.size = size * (0.75f + random.nextFloat() * 0.5f);
            spark.r = r;
            spark.g = g;
            spark.b = b;
            spark.phase = random.nextFloat() * 6.2831f;
            spark.birth = birth;
            spark.drift = speed > 0.01f;
            spark.alphaMul = 1.0f;
            spark.dead = false;
            spark.lifecycle = null;   // null = 按 life 定时消失（默认行为）
        }
        instanceDirty = true;
    }

    /** 生成一个"持久"光点：不按寿命死亡，alpha 由外部每 tick 控制（元素场发光用）。
     *  分配在池的尾部专用区，永远不会被 burst 覆盖。 */
    public Spark spawnPersistent(double x, double y, double z, int color, float size) {
        Spark spark;
        if (persistCount < PERSIST_CAPACITY) {
            spark = pool[BURST_CAPACITY + persistCount++];
        } else {
            spark = pool[BURST_CAPACITY + persistCursor];
            if (++persistCursor >= PERSIST_CAPACITY) {
                persistCursor = 0;
            }
        }
        spark.x = x;
        spark.y = y;
        spark.z = z;
        spark.prevX = x;
        spark.prevY = y;
        spark.prevZ = z;
        spark.vx = 0;
        spark.vy = 0;
        spark.vz = 0;
        spark.age = 0;
        spark.life = 1_000_000;
        spark.size = size;
        spark.r = ((color >> 16) & 0xFF) / 255.0f;
        spark.g = ((color >> 8) & 0xFF) / 255.0f;
        spark.b = (color & 0xFF) / 255.0f;
        spark.phase = random.nextFloat() * 6.2831f;
        spark.birth = now();
        spark.drift = false;
        spark.alphaMul = 1.0f;
        spark.dead = false;
        spark.lifecycle = SparkLife.persistent();
        instanceDirty = true;
        return spark;
    }

    /** 标记持久光点在本 tick 之后移除（元素消失时用） */
    public void kill(Spark spark) {
        spark.dead = true;
        instanceDirty = true;
    }

    public void markDirty() {
        instanceDirty = true;
    }

    /** 每 tick 推进位置与生命周期，并重建实例表 */
    public void tick() {
        boolean changed = false;

        // ---- burst 区：位置自己积分，生命周期可被外部钩子接管
        for (int i = burstCount - 1; i >= 0; i--) {
            Spark spark = pool[i];
            int age = ++spark.age;
            boolean alive = spark.dead
                    ? false
                    : (spark.lifecycle != null ? spark.lifecycle.tick(spark, age) : age < spark.life);
            if (!alive) {
                removeBurst(i);
                changed = true;
                continue;
            }

            spark.prevX = spark.x;
            spark.prevY = spark.y;
            spark.prevZ = spark.z;

            spark.x += spark.vx;
            spark.y += spark.vy;
            spark.z += spark.vz;

            spark.vx *= 0.965;
            spark.vz *= 0.965;
            spark.vy = spark.vy * 0.965 + (spark.drift ? 0.0025 : 0.0);
            changed = true;
        }

        // ---- 元素光点区：位置由 ElementGlowField 驱动，这里只推进寿命与钩子
        for (int j = persistCount - 1; j >= 0; j--) {
            Spark spark = pool[BURST_CAPACITY + j];
            int age = ++spark.age;
            boolean alive = spark.dead
                    ? false
                    : (spark.lifecycle != null ? spark.lifecycle.tick(spark, age) : age < spark.life);
            if (!alive) {
                removePersistent(j);
                changed = true;
            }
        }

        // markDirty() 只置脏标志，render() 只负责上传，实例表要在这里重新序列化
        if (changed || instanceDirty) {
            rebuildInstances();
        }
    }

    private void rebuildInstances() {
        int total = burstCount + persistCount;
        if (total == 0) {
            sortCount = 0;
            instanceDirty = true;
            return;
        }

        // 每 20 tick 重排一次「就近优先」顺序（相机位置由 render 更新）
        if (--sortTimer <= 0) {
            sortTimer = 20;
            sortByDistance(total);
        }

        int idx = 0;
        int written = 0;
        int limit = MAX_DRAW;

        // 1) 法术特效（burst）优先，按距离从近到远填满名额
        for (int k = 0; k < sortCount && written < limit; k++) {
            int slot = sortOrder[k];
            if (slot >= BURST_CAPACITY) {
                continue; // 元素光点在下面统一处理
            }
            idx = writeInstance(pool[slot], idx);
            written++;
        }

        // 2) 元素光点是背景：剩余名额放得下就整组画，放不下就整组跳过。
        //    刻意不做逐个裁剪——逐个进出会造成成片闪烁。
        if (written + persistCount <= limit) {
            for (int j = 0; j < persistCount; j++) {
                idx = writeInstance(pool[BURST_CAPACITY + j], idx);
                written++;
            }
        }

        instanceCount = written;
        instanceDirty = true;
    }

    /** 把活跃光点按到相机的距离升序排进 sortOrder（计数排序，O(n)） */
    private void sortByDistance(int total) {
        if (!camValid) {
            for (int k = 0; k < total; k++) {
                sortOrder[k] = k < burstCount ? k : BURST_CAPACITY + (k - burstCount);
            }
            sortCount = total;
            return;
        }

        // 距离平方分桶：每桶 4 格，64 桶覆盖 256 格；更远的集中进最后一桶
        final int buckets = 64;
        final double bucketSize = 4.0 * 4.0;
        final int[] counts = new int[buckets + 1];

        for (int k = 0; k < total; k++) {
            int slot = k < burstCount ? k : BURST_CAPACITY + (k - burstCount);
            counts[bucketOf(pool[slot], buckets, bucketSize)]++;
        }

        int[] starts = new int[buckets + 1];
        int acc = 0;
        for (int b = 0; b <= buckets; b++) {
            starts[b] = acc;
            acc += counts[b];
        }

        int[] cursor = starts.clone();
        for (int k = 0; k < total; k++) {
            int slot = k < burstCount ? k : BURST_CAPACITY + (k - burstCount);
            int bucket = bucketOf(pool[slot], buckets, bucketSize);
            sortOrder[cursor[bucket]++] = slot;
        }
        sortCount = total;
    }

    private int bucketOf(Spark spark, int buckets, double bucketSize) {
        double dx = spark.x - camX;
        double dy = spark.y - camY;
        double dz = spark.z - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        int bucket = (int) (distSq / bucketSize);
        return bucket < 0 ? 0 : Math.min(bucket, buckets);
    }

    private int writeInstance(Spark spark, int idx) {
        // 布局必须与 SparkInstancedRenderer / spark 着色器一致（11 float / 44 字节）
        instanceData[idx++] = (float) spark.x;
        instanceData[idx++] = (float) spark.y;
        instanceData[idx++] = (float) spark.z;
        instanceData[idx++] = (float) spark.prevX;
        instanceData[idx++] = (float) spark.prevY;
        instanceData[idx++] = (float) spark.prevZ;
        // 颜色 4 通道压成 4 个字节（小端序 → 内存里就是 r,g,b,a），shader 侧仍是 vec4
        int cr = (int) (spark.r * 255.0f + 0.5f);
        int cg = (int) (spark.g * 255.0f + 0.5f);
        int cb = (int) (spark.b * 255.0f + 0.5f);
        int ca = (int) (spark.alphaMul * 255.0f + 0.5f);
        instanceData[idx++] = Float.intBitsToFloat(cr | (cg << 8) | (cb << 16) | (ca << 24));
        instanceData[idx++] = spark.size;
        instanceData[idx++] = spark.birth;
        instanceData[idx++] = spark.life;
        instanceData[idx++] = spark.phase;
        return idx;
    }

    private void removeBurst(int index) {
        Spark last = pool[--burstCount];
        pool[burstCount] = pool[index];
        pool[index] = last;
    }

    private void removePersistent(int index) {
        Spark last = pool[BURST_CAPACITY + --persistCount];
        pool[BURST_CAPACITY + persistCount] = pool[BURST_CAPACITY + index];
        pool[BURST_CAPACITY + index] = last;
    }

    /** 相机基向量（世界空间），每帧计算一次 */
    public record Basis(float rX, float rY, float rZ,
                        float uX, float uY, float uZ,
                        float fX, float fY, float fZ)
    {
        public static Basis of(Camera camera) {
            Vector3f left = camera.getLeftVector();
            Vector3f up = camera.getUpVector();
            Vector3f look = camera.getLookVector();
            return new Basis(-left.x(), -left.y(), -left.z(),
                    up.x(), up.y(), up.z(),
                    look.x(), look.y(), look.z());
        }

        /**
         * GLSL mat3 是列主序，R 的行是 (r, u, -f)：
         * 列 0 = (r.x, u.x, -f.x) ...
         */
        public float[] toColumnMajor() {
            return new float[]{
                    rX, uX, -fX,
                    rY, uY, -fY,
                    rZ, uZ, -fZ
            };
        }
    }

    /** 渲染所有光点（RenderLevelStageEvent.AFTER_WEATHER） */
    public void render(Camera camera, float partialTick) {
        if (burstCount + persistCount == 0) {
            return;
        }
        if (!SparkGlProgram.ensure()) {
            return;
        }
        if (!SparkInstancedRenderer.ensure(CAPACITY)) {
            return;
        }

        // 记录相机位置：下一次 rebuildInstances 用它做「就近优先」排序
        Vec3 cam = camera.getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        camValid = true;

        if (instanceDirty) {
            SparkInstancedRenderer.upload(instanceData, instanceCount);
            instanceDirty = false;
        }
        Basis basis = Basis.of(camera);
        float[] viewRotation = basis.toColumnMajor();
        float time = now() + partialTick;

        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        int screenW = window.getWidth();
        int screenH = window.getHeight();
        if (screenW <= 0 || screenH <= 0) {
            return;
        }

        // 大粒子走半分辨率 FBO（片元数降到 1/4），小粒子直接全分辨率画主画面（运动平滑）
        int halfW = Math.max(1, screenW / 2);
        int halfH = Math.max(1, screenH / 2);
        SparkHalfResTarget.ensure(halfW, halfH);

        if (SparkHalfResTarget.isBroken() || SparkHalfResTarget.fbo() == -1) {
            drawSparks(viewRotation, time, partialTick, cam, screenW, screenH, -1);
            return;
        }

        int mainFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        // 1) 主深度下采样到半分辨率：FBO 尺寸必须一致，且地形遮挡要正确
        SparkHalfResTarget.blitDepth(mainFbo, screenW, screenH);

        // 2) 大粒子画到半分辨率 FBO（不做 clamp/吸附，观感与原全分辨率管线一致）
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SparkHalfResTarget.fbo());
        GL11.glViewport(0, 0, halfW, halfH);
        GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        drawSparks(viewRotation, time, partialTick, cam, halfW, halfH, 0);

        // 3) 小粒子直接画到主画面（全分辨率）：半分辨率网格里纹素吸附的步进是
        //    2 个全分辨率像素，小粒子运动会「一卡一卡」；全分辨率网格下同样的
        //    吸附只步进 1px，运动平滑。小粒子片元数少，全分辨率开销可忽略，
        //    深度还直接用主画面全分辨率深度，遮挡更准。
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL11.glViewport(0, 0, screenW, screenH);
        drawSparks(viewRotation, time, partialTick, cam, screenW, screenH, 1);

        // 4) 半分辨率结果加色叠加回主画面
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL11.glViewport(0, 0, screenW, screenH);
        SparkHalfResTarget.composite();
    }

    /** targetW/targetH：实际渲染目标的像素尺寸（半分辨率 FBO 或全屏），供
     *  screenScale 与纹素吸附共同使用。tinyPass：-1=不分趟（全屏回退），0=半分辨率
     *  大粒子趟，1=全分辨率小粒子趟（分趟与交叉淡化阈值见顶点着色器）。 */
    private void drawSparks(float[] viewRotation, float time, float partialTick,
                            Vec3 cam, int targetW, int targetH, int tinyPass) {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        try {
            float maxDist = com.wizardmagic.Config.SPARK_MAX_DISTANCE.get().floatValue();
            float fadeStart = maxDist * 0.7f;
            double fov = net.minecraft.client.Minecraft.getInstance().options.fov().get();
            float screenScale = (float) (targetH / (2.0 * Math.tan(Math.toRadians(fov) / 2.0)));
            // 分流尺子固定用半分辨率 screenScale：小趟的 screenScale（全分辨率）是它的一半
            float refScale = tinyPass == 1 ? screenScale * 0.5f : screenScale;

            SparkInstancedRenderer.draw(instanceCount, RenderSystem.getProjectionMatrix(), viewRotation,
                    (float) cam.x, (float) cam.y, (float) cam.z, time, partialTick,
                    maxDist, fadeStart, screenScale, targetW, targetH, refScale, tinyPass);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    static final class Spark
    {
        double x;
        double y;
        double z;
        double prevX;
        double prevY;
        double prevZ;
        double vx;
        double vy;
        double vz;
        int age;
        int life;
        float size;
        float r;
        float g;
        float b;
        float phase;
        float birth;
        float alphaMul = 1.0f;
        boolean drift;
        /** 外部生命周期钩子；null = 按 {@link #life} 定时消失 */
        SparkLife lifecycle;
        /** 由 {@link #kill} 置位，下一 tick 无条件移除 */
        boolean dead;
    }
}
