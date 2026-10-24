package com.wizardmagic.client.fx;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * 光点的半分辨率离屏目标 + 合成。
 *
 * <p>光点 pass 的片元数直接决定 overdraw：画到 1/2 分辨率纹理上片元数降到 1/4，
 * 再用线性采样叠加回主画面。粒子世界尺寸与屏幕投影都不变，只是光栅化精度减半
 * （光晕边缘反而更柔）。</p>
 *
 * <p>关键点：<b>深度也必须降到半分辨率</b>。共享主画面的全分辨率深度会让 FBO
 * 尺寸不匹配（不完整），所以每帧把主深度 blit 下采样一份，保证地形遮挡仍正确。</p>
 *
 * <p>合成使用自建着色器，不碰 MC 的 {@code position_tex} / RenderSystem 矩阵：
 * 顶点直接把 0..1 的屏幕坐标映射到 NDC，UV 与 NDC 同向（y 向上），
 * 避免原版管线里 GUI 坐标系（y 向下）与纹理坐标系（y 向上）之间的翻转陷阱。</p>
 */
public final class SparkHalfResTarget
{
    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    private static int fbo = -1;
    private static int colorTex = -1;
    private static int depthRbo = -1;
    private static int width;
    private static int height;
    private static boolean broken;

    // 合成管线（自建）
    private static int compositeProgram = -1;
    private static int compositeVao = -1;
    private static int compositeVbo = -1;
    private static int compositeTexLoc = -1;

    /**
     * aPos 是 0..1 的屏幕坐标，NDC 与 UV 同向：
     * NDC(-1,-1) 屏幕左下 ↔ UV(0,0) 纹理左下，不做任何翻转。
     */
    private SparkHalfResTarget() {
    }

    public static boolean isBroken() {
        return broken;
    }

    public static int fbo() {
        return fbo;
    }

    public static void ensure(int w, int h) {
        if (broken) {
            return;
        }
        if (fbo != -1 && width == w && height == h) {
            return;
        }
        destroy();
        width = Math.max(1, w);
        height = Math.max(1, h);

        try {
            colorTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            // RGBA16F：加色累积不截断，密集处不会提前饱和
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

            depthRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);

            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depthRbo);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("half-res FBO incomplete: status=0x{}", Integer.toHexString(status));
                broken = true;
                destroy();
            } else {
                LOGGER.info("half-res FBO ready: {}x{} (RGBA16F + D24)", width, height);
            }
        } catch (Throwable t) {
            LOGGER.error("half-res FBO init failed", t);
            broken = true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        }
    }

    /** 把主画面的深度下采样到半分辨率 FBO（GL_NEAREST，深度不能线性插值） */
    public static void blitDepth(int sourceFbo, int sourceW, int sourceH) {
        if (broken || fbo == -1) {
            return;
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL30.glBlitFramebuffer(0, 0, sourceW, sourceH, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
    }

    /** 把半分辨率结果加色叠加回主画面（调用前主 FBO 已绑定、viewport 已全屏） */
    public static void composite() {
        if (broken || colorTex == -1) {
            return;
        }
        if (!ensureComposite()) {
            return;
        }

        GL20.glUseProgram(compositeProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL20.glUniform1i(compositeTexLoc, 0);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);

        GL30.glBindVertexArray(compositeVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindVertexArray(0);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL20.glUseProgram(0);
    }

    private static boolean ensureComposite() {
        if (compositeProgram != -1) {
            return true;
        }
        try {
            String vertexSource = ShaderSource.load("shaders/core/spark_composite.vsh");
            String fragmentSource = ShaderSource.load("shaders/core/spark_composite.fsh");
            if (vertexSource == null || fragmentSource == null) {
                broken = true;
                return false;
            }
            int vs = compile(GL20.GL_VERTEX_SHADER, vertexSource);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glBindAttribLocation(program, 0, "aPos");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                LOGGER.error("composite link failed: {}", GL20.glGetProgramInfoLog(program));
                GL20.glDeleteProgram(program);
                broken = true;
                return false;
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            compositeProgram = program;
            compositeTexLoc = GL20.glGetUniformLocation(program, "uTex");

            // 0..1 的屏幕坐标，TRIANGLE_STRIP：(0,0) (1,0) (0,1) (1,1)
            float[] vertices = {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F,
                    1.0F, 1.0F, 0.0F
            };
            compositeVao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(compositeVao);
            compositeVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, compositeVbo);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(vertices.length);
            buffer.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
            MemoryUtil.memFree(buffer);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0L);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            LOGGER.info("composite program ready (id={})", program);
            return true;
        } catch (Throwable t) {
            LOGGER.error("composite init failed", t);
            broken = true;
            return false;
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("composite shader compile failed: "
                    + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public static void destroy() {
        if (fbo != -1) {
            GL30.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (colorTex != -1) {
            GL11.glDeleteTextures(colorTex);
            colorTex = -1;
        }
        if (depthRbo != -1) {
            GL30.glDeleteRenderbuffers(depthRbo);
            depthRbo = -1;
        }
        width = 0;
        height = 0;
    }
}
