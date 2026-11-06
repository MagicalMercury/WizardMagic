package com.wizardmagic.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.api.crucible.CrucibleBlockEntity;
import com.wizardmagic.api.crucible.CrucibleGeometry;
import com.wizardmagic.api.crucible.CrucibleRenderState;
import com.wizardmagic.api.crucible.CrucibleState;
import com.wizardmagic.api.crucible.FluidColorStrategy;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 通用坩埚渲染器：把 {@link CrucibleRenderState#surfaceHeight()} 画成一根液柱。
 *
 * <p>内容模组注册一次即可，所有继承 {@link CrucibleBlockEntity} 的坩埚共用：</p>
 * <pre>{@code
 * event.registerBlockEntityRenderer(
 *         ModBlockEntities.ALCHEMY_CRUCIBLE.get(), CrucibleRenderer::new);
 * }</pre>
 *
 * <p>为什么不走 blockstate 的静态液面模型：静态模型只有 8 档，液量连续变化时会跳档；
 * 颜色还要额外注册 {@code RegisterColorHandlersEvent.Block}；内容模组每加一口锅还得复制 8 个 JSON。</p>
 */
public class CrucibleRenderer<T extends CrucibleBlockEntity> implements BlockEntityRenderer<T> {

    /** 所有坩埚共用的液面贴图（白色波纹，颜色由顶点色决定）。 */
    public static final ResourceLocation FLUID_TEXTURE =
            new ResourceLocation(WizardMagic.MODID, "textures/block/crucible_fluid.png");

    /**
     * 直接取状态里的 ARGB。
     *
     * <p>不能给 {@code update(...)} 传 {@code null}：{@code FluidColorStrategies.orFallback}
     * 会把 null 换成固定的 {@code FALLBACK_ARGB}（中性紫），液体就永远是紫色，
     * 完全不读方块实体的颜色。</p>
     */
    private static final FluidColorStrategy STATE_COLOR = CrucibleState::fluidColorArgb;

    private static final float UNIT = 1.0F / 16.0F;

    public CrucibleRenderer(BlockEntityRendererProvider.Context context) {
        // 不需要渲染批次上下文，构造签名保持标准以便方法引用注册
    }

    @Override
    public void render(T crucible, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        // 每帧只取一次状态：缓存与颜色都从这里读，避免重复创建快照
        CrucibleState source = crucible.crucibleState();
        CrucibleRenderState state = crucible.renderCache().update(
                source,
                null,
                STATE_COLOR,
                crucible.cavityBottom(),
                crucible.cavityHeight());

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
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;

        // UV 必须用「模型单位」计算：x0/x1/y0/y1 是方块局部坐标（单位=格，0..1），
        // 而 FLUID_TEXTURE_SPAN 是模型单位。直接相除会把 UV 缩到 1/16，
        // 只采样到贴图的一小块，看起来就是纯色。
        float widthModel = CrucibleGeometry.FLUID_OUTER - CrucibleGeometry.FLUID_INSET;
        float heightModel = state.surfaceHeight() - crucible.cavityBottom();
        float span = CrucibleGeometry.FLUID_TEXTURE_SPAN;

        // 液面宽 11.94、贴图跨度也是 11.94 -> uWidth 恒为 1.0，一张贴图铺满液面
        float uWidth = widthModel / span;
        float uDepth = uWidth;
        float vHeight = heightModel / span;

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(FLUID_TEXTURE));
        Matrix4f matrix = pose.last().pose();

        // 侧面（液体侧面按高度平铺，不拉伸）
        quad(vc, matrix,
                new float[]{x0, y0, z0}, new float[]{x0, y1, z0},
                new float[]{x1, y1, z0}, new float[]{x1, y0, z0},
                new float[]{0, vHeight, 0, 0, uWidth, 0, uWidth, vHeight},
                0, 0, -1, red, green, blue, alpha, light, overlay);

        quad(vc, matrix,
                new float[]{x1, y0, z1}, new float[]{x1, y1, z1},
                new float[]{x0, y1, z1}, new float[]{x0, y0, z1},
                new float[]{0, vHeight, 0, 0, uWidth, 0, uWidth, vHeight},
                0, 0, 1, red, green, blue, alpha, light, overlay);

        quad(vc, matrix,
                new float[]{x0, y0, z1}, new float[]{x0, y1, z1},
                new float[]{x0, y1, z0}, new float[]{x0, y0, z0},
                new float[]{0, vHeight, 0, 0, uDepth, 0, uDepth, vHeight},
                -1, 0, 0, red, green, blue, alpha, light, overlay);

        quad(vc, matrix,
                new float[]{x1, y0, z0}, new float[]{x1, y1, z0},
                new float[]{x1, y1, z1}, new float[]{x1, y0, z1},
                new float[]{0, vHeight, 0, 0, uDepth, 0, uDepth, vHeight},
                1, 0, 0, red, green, blue, alpha, light, overlay);

        // 顶面
        quad(vc, matrix,
                new float[]{x0, y1, z1}, new float[]{x1, y1, z1},
                new float[]{x1, y1, z0}, new float[]{x0, y1, z0},
                new float[]{0, uDepth, uWidth, uDepth, uWidth, 0, 0, 0},
                0, 1, 0, red, green, blue, alpha, fullBright(light), overlay);
    }

    /** 顶面稍微提亮，避免内腔光照把液面压成黑块。 */
    private static int fullBright(int light) {
        int block = Math.max((light & 0xFFFF) >> 4, 12);
        int sky = (light >> 16) & 0xFFFF;
        return (sky << 16) | (block << 4);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void quad(VertexConsumer vc, Matrix4f matrix,
                             float[] p0, float[] p1, float[] p2, float[] p3,
                             float[] uv,
                             float nx, float ny, float nz,
                             int red, int green, int blue, int alpha,
                             int light, int overlay) {
        vertex(vc, matrix, p0, uv[0], uv[1], nx, ny, nz, red, green, blue, alpha, light, overlay);
        vertex(vc, matrix, p1, uv[2], uv[3], nx, ny, nz, red, green, blue, alpha, light, overlay);
        vertex(vc, matrix, p2, uv[4], uv[5], nx, ny, nz, red, green, blue, alpha, light, overlay);
        vertex(vc, matrix, p3, uv[6], uv[7], nx, ny, nz, red, green, blue, alpha, light, overlay);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void vertex(VertexConsumer vc, Matrix4f matrix, float[] pos, float u, float v,
                               float nx, float ny, float nz,
                               int red, int green, int blue, int alpha,
                               int light, int overlay) {
        vc.vertex(matrix, pos[0], pos[1], pos[2])
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(nx, ny, nz)
                .endVertex();
    }
}
