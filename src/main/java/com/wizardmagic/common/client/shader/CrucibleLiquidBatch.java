package com.wizardmagic.common.client.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.wizardmagic.WizardMagic;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;

/**
 * 坩埚液面的顶点批次。
 *
 * <p>方块实体渲染器每帧只做「提交四边形」，真正的绘制集中在
 * {@code RenderLevelStageEvent.AFTER_WEATHER} 一次画完——自建 GL 管线的 program
 * 不能混进 MC 的 {@code MultiBufferSource} 批次，否则状态会被打乱。</p>
 *
 * <p>顶点在 <b>世界坐标</b>下提交（BERS 的 pose 已变换好），
 * shader 用「投影 × 视图旋转 × (世界坐标 − 相机位置)」还原视图空间。</p>
 */
public final class CrucibleLiquidBatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    /** 液面贴图：独立于 block atlas，靠 GL_REPEAT 支持 UV 平铺。 */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation(WizardMagic.MODID, "textures/block/crucible_fluid.png");

    /** 顶点布局：x,y,z, u,v, r,g,b,a, lightU,lightV */
    private static final int FLOATS_PER_VERTEX = 11;
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES;

    private static final int MAX_VERTICES = 8192;

    /** 四边形拆成两个三角形（GL_QUADS 在 core profile 不可用）。 */
    private static final int[] CORNERS = {0, 1, 2, 0, 2, 3};

    private static final float[] DATA = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private static int vertexCount;

    private static int vao = -1;
    private static int vbo = -1;
    private static FloatBuffer upload;
    private static boolean broken;

    /** lightmap 纹理 id：{@code LightTexture.lightTexture} 是 private，反射取一次后缓存。 */
    private static int lightmapTextureId = -1;

    private CrucibleLiquidBatch() {
    }

    /** 每帧开始清空。 */
    public static void begin() {
        vertexCount = 0;
    }

    /**
     * 提交一个四边形。
     *
     * @param points 12 个 float，四个顶点的世界坐标 xyz
     * @param uv     8 个 float，与 points 顺序对应的 uv
     * @param argb   顶点色（乘到贴图上）
     * @param lightU lightmap 的 u（0..1）
     * @param lightV lightmap 的 v（0..1）
     */
    public static void submit(float[] points, float[] uv, int argb, float lightU, float lightV) {
        if (broken || vertexCount + 6 > MAX_VERTICES) {
            return;
        }

        float a = ((argb >>> 24) & 0xFF) / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        int o = vertexCount * FLOATS_PER_VERTEX;
        for (int corner : CORNERS) {
            DATA[o++] = points[corner * 3];
            DATA[o++] = points[corner * 3 + 1];
            DATA[o++] = points[corner * 3 + 2];
            DATA[o++] = uv[corner * 2];
            DATA[o++] = uv[corner * 2 + 1];
            DATA[o++] = r;
            DATA[o++] = g;
            DATA[o++] = b;
            DATA[o++] = a;
            DATA[o++] = lightU;
            DATA[o++] = lightV;
        }
        vertexCount += CORNERS.length;
    }

    /** 一次画完本帧提交的所有液面。 */
    public static void draw(Camera camera) {
        if (vertexCount == 0 || broken) {
            return;
        }
        if (!CrucibleLiquidProgram.ensure() || !ensureBuffers()) {
            return;
        }

        upload.clear();
        upload.put(DATA, 0, vertexCount * FLOATS_PER_VERTEX);
        upload.flip();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, upload);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // 顶点进 shader 时已经是视图空间坐标（LevelRenderer 的视图旋转 +
        // BlockEntityRenderDispatcher 的 pos-cameraPos 平移），只差投影。
        float[] projection = new float[16];
        RenderSystem.getProjectionMatrix().get(projection);

        Minecraft minecraft = Minecraft.getInstance();
        int texture = minecraft.getTextureManager().getTexture(TEXTURE).getId();
        int lightmap = lightmapTextureId();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        // 顶面三层共面，写深度会 z-fighting；读深度保留（内壁照样能挡住液面）。
        RenderSystem.depthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);

        CrucibleLiquidProgram.get().use();
        GL20.glUniformMatrix4fv(CrucibleLiquidProgram.uProj(), false, projection);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(CrucibleLiquidProgram.uTex(), 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmap);
        GL20.glUniform1i(CrucibleLiquidProgram.uLightmap(), 1);

        GlStateManager._glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        GlStateManager._glBindVertexArray(0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        CrucibleLiquidProgram.get().stop();
        GL11.glEnable(GL11.GL_CULL_FACE);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /**
     * lightmap 纹理 id。
     *
     * <p>MC 把 {@code LightTexture.lightTexture} 设为 private，没有公开 getter；
     * 这里用 Forge 的反射助手取一次并缓存（映射由它处理）。
     * 取不到时返回 0，液面会缺少光照调制但仍然可见。</p>
     */

    private static int lightmapTextureId() {
        if (lightmapTextureId != -1) {
            return lightmapTextureId;
        }
        try {
            Field field = ObfuscationReflectionHelper.findField(LightTexture.class, "lightTexture");
            AbstractTexture texture = (AbstractTexture) field.get(
                    Minecraft.getInstance().gameRenderer.lightTexture());
            lightmapTextureId = texture.getId();
        } catch (Throwable t) {
            LOGGER.warn("无法读取 lightmap 纹理，液面将缺少光照调制", t);
            lightmapTextureId = 0;
        }
        return lightmapTextureId;
    }

    private static boolean ensureBuffers() {
        if (vao != -1) {
            return true;
        }
        try {
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);

            vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                    (long) MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES,
                    GL15.GL_DYNAMIC_DRAW);

            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, 12L);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, STRIDE, 20L);
            GL20.glEnableVertexAttribArray(3);
            GL20.glVertexAttribPointer(3, 2, GL11.GL_FLOAT, false, STRIDE, 36L);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            upload = MemoryUtil.memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
            return true;
        } catch (Throwable t) {
            LOGGER.error("crucible liquid buffer init failed", t);
            broken = true;
            return false;
        }
    }
}
