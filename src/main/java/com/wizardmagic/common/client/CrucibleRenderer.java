package com.wizardmagic.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wizardmagic.api.crucible.CrucibleBlockEntity;
import com.wizardmagic.api.crucible.CrucibleGeometry;
import com.wizardmagic.api.crucible.CrucibleRenderState;
import com.wizardmagic.api.crucible.CrucibleState;
import com.wizardmagic.api.crucible.FluidColorStrategy;
import com.wizardmagic.common.client.shader.CrucibleLiquidBatch;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 通用坩埚渲染器：把 {@link CrucibleRenderState#surfaceHeight()} 变成液面顶点。
 *
 * <p>本类只负责「算几何 + 提交」，实际绘制在
 * {@link com.wizardmagic.common.client.shader.CrucibleLiquidRenderer} 的
 * {@code AFTER_WEATHER} 阶段统一完成——自建 GL program 不能混进 MC 的
 * {@code MultiBufferSource} 批次。</p>
 *
 * <p>内容模组注册一次即可，所有继承 {@link CrucibleBlockEntity} 的坩埚共用：</p>
 * <pre>{@code
 * event.registerBlockEntityRenderer(
 *         ModBlockEntities.ALCHEMY_CRUCIBLE.get(), CrucibleRenderer::new);
 * }</pre>
 *
 * <p>液面表现：UV 2×2 平铺（GL_REPEAT）、正弦摆动、三层渐变边缘压暗。</p>
 */
public class CrucibleRenderer<T extends CrucibleBlockEntity> implements BlockEntityRenderer<T> {

    /**
     * 直接取状态里的 ARGB。
     *
     * <p>不能给 {@code update(...)} 传 {@code null}：{@code FluidColorStrategies.orFallback}
     * 会把 null 换成固定的 {@code FALLBACK_ARGB}（中性紫），液体就永远是紫色。</p>
     */
    private static final FluidColorStrategy STATE_COLOR = CrucibleState::fluidColorArgb;

    private static final float UNIT = 1.0F / 16.0F;

    /** 液面 UV 放大倍数，配合 GL_REPEAT 使用。 */
    private static final float TILING = 2.0F;

    /**
     * UV 摆动幅度（相对贴图比例）。
     *
     * <p>用正弦来回摆动而不是线性平移：线性平移每个 tick 加同一个量，
     * 图案就是刚性单向滑动，看起来像传送带。</p>
     */
    private static final float SWAY_U = 0.05F;
    private static final float SWAY_V = 0.04F;

    /** 摆动角速度（弧度 / tick）：一个来回约 10 秒。 */
    private static final float SWAY_SPEED = 0.015F;

    /** 边缘压暗带宽度（模型单位）。 */
    private static final float RIM_WIDTH = 1.2F;

    /** 压暗带保留的亮度比例。 */
    private static final float RIM_DARKEN = 0.72F;

    public CrucibleRenderer(BlockEntityRendererProvider.Context context) {
        // 构造签名保持标准，便于用方法引用注册
    }

    @Override
    public void render(T crucible, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        CrucibleState source = crucible.crucibleState();
        CrucibleRenderState state = crucible.renderCache().update(
                source, null, STATE_COLOR, crucible.cavityBottom(), crucible.cavityHeight());
        if (state.empty()) {
            return;
        }

        float x0 = CrucibleGeometry.FLUID_INSET * UNIT;
        float x1 = CrucibleGeometry.FLUID_OUTER * UNIT;
        float z0 = x0;
        float z1 = x1;
        float y0 = crucible.cavityBottom() * UNIT;
        float y1 = state.surfaceHeight() * UNIT;
        if (y1 - y0 < 1.0E-4F) {
            return;
        }

        int argb = state.colorArgb();
        if ((argb >>> 24) == 0) {
            argb |= 0xFF000000;
        }

        // UV 用模型单位换算：x0/x1 是方块局部坐标（格，0..1），FLUID_TEXTURE_SPAN 是模型单位。
        float span = CrucibleGeometry.FLUID_TEXTURE_SPAN;
        float uSpan = (CrucibleGeometry.FLUID_OUTER - CrucibleGeometry.FLUID_INSET) / span * TILING;
        float vSpan = (state.surfaceHeight() - crucible.cavityBottom()) / span * TILING;

        float phase = ((float) source.animationTime() + partialTick) * SWAY_SPEED;
        float u0 = SWAY_U * (float) Math.sin(phase);
        float v0 = SWAY_V * (float) Math.sin(phase * 0.73F + 1.1F);

        Matrix4f matrix = pose.last().pose();

        int blockLight = (light >> 4) & 0xF;
        int skyLight = (light >> 20) & 0xF;
        float sideU = (blockLight + 0.5F) / 16.0F;
        float sideV = (skyLight + 0.5F) / 16.0F;

        // 顶面至少 12 级方块光，避免内腔把液面压成黑块
        int topBlock = Math.max(blockLight, 12);
        float topU = (topBlock + 0.5F) / 16.0F;

        float[] uvSide = sideUv(u0, v0, uSpan, vSpan);

        // ---- 四个侧面 ----
        submit(matrix, new float[]{x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0},
                uvSide, argb, sideU, sideV);
        submit(matrix, new float[]{x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1},
                uvSide, argb, sideU, sideV);
        submit(matrix, new float[]{x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0},
                uvSide, argb, sideU, sideV);
        submit(matrix, new float[]{x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1},
                uvSide, argb, sideU, sideV);

        // ---- 顶面三层：共面（y 全等），靠绘制顺序覆盖出边缘压暗 ----
        // 层间不能有 y 差：哪怕 0.00125 格，低角度斜视也会被投影放大成"一片一片"。
        // 共面必然重叠，所以整批液面不写深度（读深度仍在，内壁照样遮挡）。
        float rim = RIM_WIDTH * UNIT;
        float ratio = rim / (x1 - x0);
        float[] uvTop = topUv(u0, v0, uSpan, uSpan);

        submit(matrix, topPoints(x0, x1, z0, z1, y1), uvTop,
                scaleRgb(argb, RIM_DARKEN), topU, sideV);
        submit(matrix, topPoints(x0 + rim / 3.0F, x1 - rim / 3.0F,
                        z0 + rim / 3.0F, z1 - rim / 3.0F, y1),
                insetUv(u0, v0, uSpan, ratio / 3.0F),
                scaleRgb(argb, (RIM_DARKEN + 1.0F) * 0.5F), topU, sideV);
        submit(matrix, topPoints(x0 + rim * 2.0F / 3.0F, x1 - rim * 2.0F / 3.0F,
                        z0 + rim * 2.0F / 3.0F, z1 - rim * 2.0F / 3.0F, y1),
                insetUv(u0, v0, uSpan, ratio * 2.0F / 3.0F),
                argb, topU, sideV);
    }

    /** 把局部坐标的四边形变换到世界坐标后提交。 */
    private static void submit(Matrix4f matrix, float[] local, float[] uv,
                               int argb, float lightU, float lightV) {
        float[] world = new float[12];
        Vector3f v = new Vector3f();
        for (int i = 0; i < 4; i++) {
            v.set(local[i * 3], local[i * 3 + 1], local[i * 3 + 2]).mulPosition(matrix);
            world[i * 3] = v.x;
            world[i * 3 + 1] = v.y;
            world[i * 3 + 2] = v.z;
        }
        CrucibleLiquidBatch.submit(world, uv, argb, lightU, lightV);
    }

    /** 顶面四个角，从 +Y 往下看逆时针：(x0,z1) → (x1,z1) → (x1,z0) → (x0,z0)。 */
    private static float[] topPoints(float x0, float x1, float z0, float z1, float y) {
        return new float[]{
                x0, y, z1,
                x1, y, z1,
                x1, y, z0,
                x0, y, z0
        };
    }

    /** 顶面 UV：左下、右下、右上、左上。 */
    private static float[] topUv(float u0, float v0, float uSpan, float vSpan) {
        return new float[]{
                u0, v0 + vSpan,
                u0 + uSpan, v0 + vSpan,
                u0 + uSpan, v0,
                u0, v0
        };
    }

    /** 侧面 UV：左下、左上、右上、右下。 */
    private static float[] sideUv(float u0, float v0, float uSpan, float vSpan) {
        return new float[]{
                u0, v0 + vSpan,
                u0, v0,
                u0 + uSpan, v0,
                u0 + uSpan, v0 + vSpan
        };
    }

    /** 顶面内缩一圈后的 UV：只取原范围中间那部分，保证图案与外层连续。 */
    private static float[] insetUv(float u0, float v0, float uSpan, float ratio) {
        float off = uSpan * ratio;
        float size = uSpan * (1.0F - 2.0F * ratio);
        return topUv(u0 + off, v0 + off, size, size);
    }

    /** 按比例压暗 RGB，保留 alpha。 */
    private static int scaleRgb(int argb, float factor) {
        int a = argb & 0xFF000000;
        int r = (int) (((argb >>> 16) & 0xFF) * factor);
        int g = (int) (((argb >>> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return a | (r << 16) | (g << 8) | b;
    }
}
