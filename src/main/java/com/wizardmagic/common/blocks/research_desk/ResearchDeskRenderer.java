package com.wizardmagic.common.blocks.research_desk;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * 把桌上的纸渲染成物品模型，平铺在桌面。
 *
 * <p><b>为什么不走 {@code ItemRenderer.renderStatic}</b>：那条路会追加
 * {@code ItemDisplayContext} 对应的 display 变换（{@code item/generated} 的
 * {@code ground} 是 {@code translate(0,2/16,0)·scale(0.5)}，{@code fixed} 是绕原点的
 * {@code Ry180}），这些变换会和我们自己的 pose 叠加，导致纸缩小、悬空或掉到桌面以下，
 * 且很难从外部推导清楚。</p>
 *
 * <p>这里只借用 {@link ItemRenderer} 取 BakedModel，然后用
 * {@code ModelBlockRenderer.renderModel} 直接画 quad —— 没有任何 display 变换，
 * pose 完全由本类决定，几何是确定的：</p>
 *
 * <pre>
 * 模型盒 [0,1] x [0,1] x [0.468,0.531]
 *   T(-0.5) 居中 -> S(0.62) -> R_x(-90°) 放平 -> R_y 对齐朝向 -> T(c_t)
 * 结果：纸面在 XZ 平面，厚度约 0.039 格，底面比毯面高 0.005 格
 * </pre>
 */
public class ResearchDeskRenderer implements BlockEntityRenderer<ResearchDeskBlockEntity> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final java.util.Set<Long> LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 桌面（含桌毯）上表面高度，单位：格。 */
    private static final float DESK_TOP = 11.45F / 16.0F;
    /** 纸的边长（格）。 */
    private static final float PAPER_SIZE = 0.62F;
    /** 抬高量：纸厚一半 + 余量，既不沉进毯子也不 z-fighting。 */
    private static final float PAPER_LIFT = 0.025F;

    private final ItemRenderer itemRenderer;

    public ResearchDeskRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ResearchDeskBlockEntity desk, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        ItemStack paper = desk.paper();
        if (paper.isEmpty()) {
            return;
        }
        Direction facing = desk.getBlockState().getValue(ResearchDeskBlock.FACING);

        BakedModel model = itemRenderer.getModel(paper, desk.getLevel(), null, 0);

        // 目标中心：HEAD 方块原点退半格到两格交界，再抬到桌面上方
        float cx = 0.5F - facing.getStepX() * 0.5F;
        float cz = 0.5F - facing.getStepZ() * 0.5F;
        float cy = DESK_TOP + PAPER_LIFT;

        pose.pushPose();
        pose.translate(cx, cy, cz);                              // T(c_t)
        // 朝向对齐 + 让纸在桌面平面内转 90°。
        // 放在 R_x 之前（外层）= 绕世界 Y 轴，即绕纸中心在桌面里打转；
        // 纸是正方形，所以只改变纹理朝向，不改变占地。
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot() + 90.0F));
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));           // R_x(-90°) 放平
        pose.scale(PAPER_SIZE, PAPER_SIZE, PAPER_SIZE);          // S(s)
        pose.translate(-0.5F, -0.5F, -0.5F);                     // T(-c_m) 居中

        if (LOGGED.add(desk.getBlockPos().asLong())) {
            Matrix4f m = pose.last().pose();
            LOGGER.info("[research_desk] paper target=({}, {}, {}) deskTop={} poseTranslate=({}, {}, {})",
                    cx, cy, cz, DESK_TOP, m.m30(), m.m31(), m.m32());
        }

        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                pose.last(),
                buffers.getBuffer(RenderType.cutout()),
                Blocks.AIR.defaultBlockState(),
                model,
                1.0F, 1.0F, 1.0F,
                light, overlay);

        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(ResearchDeskBlockEntity desk) {
        return false;
    }
}
