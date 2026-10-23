package com.wizardmagic.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 客户端光点池 + 渲染。
 *
 * <h2>billboard 的坐标系约定（路线 A）</h2>
 *
 * <p>顶点直接给<b>视图空间</b>坐标，并把 modelview 显式置为 identity：</p>
 *
 * <pre>
 * 设相机位置 C、相机基 (r = 右, u = 上, f = 前)，光点世界坐标 p：
 *
 *   d   = p - C
 *   vx  = r · d
 *   vy  = u · d
 *   vz  = -(f · d)          // 视图空间里相机看向 -Z
 *   四角 = (vx ± h, vy ± h, vz)
 * </pre>
 *
 * <p>这样 R 由我们乘，管线只负责 projection，不再依赖 {@code AFTER_PARTICLES}
 * 阶段 modelview 里到底有没有相机旋转——之前「光点绕圈/变薄片」就是
 * 世界空间偏移与视图空间坐标混用造成的。</p>
 */
public final class SparkBatch
{
    /** 同时存在的最大光点数（5~10 万需求按 131072 预留，约 10 MB） */
    public static final int CAPACITY = 131072;

    private static final SparkBatch INSTANCE = new SparkBatch();

    private final Spark[] pool = new Spark[CAPACITY];
    private int count;
    /** 池满后覆盖最老光点的游标 */
    private int replaceCursor;

    private final RandomSource random = RandomSource.create();

    /** 自管大缓冲：一次性装下全部光点（13 万 × 144B ≈ 19MB），避免每帧十几次上传/draw call */
    private static BufferBuilder vertexBuffer;

    private SparkBatch() {
        for (int i = 0; i < CAPACITY; i++) {
            pool[i] = new Spark();
        }
    }

    private static BufferBuilder vertexBuffer() {
        if (vertexBuffer == null) {
            // 24MB 预分配，够 131072 个光点（每个 4 顶点 × 36B = 144B）
            vertexBuffer = new BufferBuilder(24 * 1024 * 1024);
        }
        return vertexBuffer;
    }

    public static SparkBatch get() {
        return INSTANCE;
    }

    public int activeCount() {
        return count;
    }

    /** 在 (x,y,z) 周围生成 count 个光点 */
    public void spawnBurst(double x, double y, double z, int amount, int color,
                           float spread, float speed, float size, int life) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        for (int i = 0; i < amount; i++) {
            Spark spark;
            if (count < CAPACITY) {
                spark = pool[count++];
            } else {
                // 池满：覆盖游标位置，保证大量特效不会互相"吃掉"生成请求
                spark = pool[replaceCursor];
                if (++replaceCursor >= CAPACITY) {
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
            // 速度 0 的"静置"光点（调试用）不要上浮，否则会慢慢飘走
            spark.drift = speed > 0.01f;
        }
    }

    /** 每 tick 推进位置与生命周期 */
    public void tick() {
        for (int i = count - 1; i >= 0; i--) {
            Spark spark = pool[i];
            if (++spark.age >= spark.life) {
                remove(i);
                continue;
            }
            spark.x += spark.vx;
            spark.y += spark.vy;
            spark.z += spark.vz;

            spark.vx *= 0.965;
            spark.vz *= 0.965;
            spark.vy = spark.vy * 0.965 + (spark.drift ? 0.0025 : 0.0);
        }
    }

    private void remove(int index) {
        Spark last = pool[--count];
        pool[count] = pool[index];
        pool[index] = last;
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
            // 右 = -左；视线 f 指向相机前方，视图空间里前方是 -Z
            return new Basis(-left.x(), -left.y(), -left.z(),
                    up.x(), up.y(), up.z(),
                    look.x(), look.y(), look.z());
        }

        /** 世界坐标 -> 视图空间（只旋转，不平移） */
        public float viewX(float dx, float dy, float dz) {
            return rX * dx + rY * dy + rZ * dz;
        }

        public float viewY(float dx, float dy, float dz) {
            return uX * dx + uY * dy + uZ * dz;
        }

        public float viewZ(float dx, float dy, float dz) {
            return -(fX * dx + fY * dy + fZ * dz);
        }
    }

    /** 渲染所有光点（在 RenderLevelStageEvent.AFTER_PARTICLES 调用） */
    public void render(Camera camera, float partialTick) {
        if (count == 0) {
            return;
        }
        ShaderInstance shader = SparkShaders.spark();
        if (shader == null) {
            return;
        }

        Vec3 cam = camera.getPosition();
        Basis basis = Basis.of(camera);

        var timeUniform = shader.getUniform("Time");
        if (timeUniform != null) {
            timeUniform.set((float) (net.minecraft.Util.getMillis() % 1_000_000L) * 0.006f);
        }

        // 顶点已经是视图空间坐标，所以 modelview 必须是 identity（渲染后恢复）
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.last().pose().identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> shader);

        BufferBuilder buffer = vertexBuffer();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        try {
            // 只有 1 个光点时走单点路径（/spark single 的调试路径），其余走批量版
            boolean single = count == 1;
            for (int i = 0; i < count; i++) {
                Spark spark = pool[i];
                if (single) {
                    renderSingle(buffer, spark, basis, cam, partialTick);
                } else {
                    renderBatch(buffer, spark, basis, cam, partialTick);
                }
            }
        } finally {
            // BufferBuilder 是全局复用的：任何路径都必须复位
            flush(buffer);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * 批量版：标量展开 + 每帧只算一次基向量。
     *
     * <p>优化点：</p>
     * <ul>
     *   <li>相机基只算一次，循环内每个光点只做 3 次点积（9 乘 6 加）；</li>
     *   <li>四角用 {@code vx ± h}, {@code vy ± h} 直接得到，不再做第二遍向量加法
     *       ——这是路线 A 相比世界空间写法最大的收益；</li>
     *   <li>整个循环零对象分配（Basis 每帧一个，光点全是池内字段）。</li>
     * </ul>
     *
     * @return 是否写入了顶点
     */
    private static boolean renderBatch(BufferBuilder buffer, Spark spark, Basis basis,
                                       Vec3 cam, float partialTick) {
        float t = (spark.age + partialTick) / spark.life;
        float alpha = (1.0f - t) * (1.0f - t);
        if (alpha <= 0.002f) {
            return false;
        }

        float dx = (float) (spark.x - cam.x);
        float dy = (float) (spark.y - cam.y);
        float dz = (float) (spark.z - cam.z);

        float vx = basis.viewX(dx, dy, dz);
        float vy = basis.viewY(dx, dy, dz);
        float vz = basis.viewZ(dx, dy, dz);

        float h = spark.size * 0.5f * (1.0f - 0.55f * t);
        float r = spark.r;
        float g = spark.g;
        float b = spark.b;

        // 左上 / 右上 / 右下 / 左下（视图空间 Y 向上）
        buffer.vertex(vx - h, vy + h, vz).color(r, g, b, alpha).uv(0.0f, 0.0f).endVertex();
        buffer.vertex(vx + h, vy + h, vz).color(r, g, b, alpha).uv(1.0f, 0.0f).endVertex();
        buffer.vertex(vx + h, vy - h, vz).color(r, g, b, alpha).uv(1.0f, 1.0f).endVertex();
        buffer.vertex(vx - h, vy - h, vz).color(r, g, b, alpha).uv(0.0f, 1.0f).endVertex();
        return true;
    }

    /**
     * 单点版：与 {@link #renderBatch} 数学等价，但每步显式展开，
     * 用于调试单光点（/spark single）或与批量版做结果对照。
     */
    private static boolean renderSingle(BufferBuilder buffer, Spark spark, Basis basis, Vec3 cam, float partialTick) {
        double ddx = spark.x - cam.x;
        double ddy = spark.y - cam.y;
        double ddz = spark.z - cam.z;

        // v_view = R * (p - C)
        double viewX = basis.rX() * ddx + basis.rY() * ddy + basis.rZ() * ddz;
        double viewY = basis.uX() * ddx + basis.uY() * ddy + basis.uZ() * ddz;
        double viewZ = -(basis.fX() * ddx + basis.fY() * ddy + basis.fZ() * ddz);

        float t = (spark.age + partialTick) / spark.life;
        float alpha = (1.0f - t) * (1.0f - t);
        if (alpha <= 0.002f) {
            return false;
        }
        float h = spark.size * 0.5f * (1.0f - 0.55f * t);

        float x = (float) viewX;
        float y = (float) viewY;
        float z = (float) viewZ;

        buffer.vertex(x - h, y + h, z).color(spark.r, spark.g, spark.b, alpha).uv(0.0f, 0.0f).endVertex();
        buffer.vertex(x + h, y + h, z).color(spark.r, spark.g, spark.b, alpha).uv(1.0f, 0.0f).endVertex();
        buffer.vertex(x + h, y - h, z).color(spark.r, spark.g, spark.b, alpha).uv(1.0f, 1.0f).endVertex();
        buffer.vertex(x - h, y - h, z).color(spark.r, spark.g, spark.b, alpha).uv(0.0f, 1.0f).endVertex();
        return true;
    }

    private static void flush(BufferBuilder buffer) {
        if (!buffer.building()) {
            return;
        }
        var rendered = buffer.endOrDiscardIfEmpty();
        if (rendered != null) {
            BufferUploader.drawWithShader(rendered);
        }
    }

    private static final class Spark
    {
        double x;
        double y;
        double z;
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
        boolean drift;
    }
}
