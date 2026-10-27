package com.wizardmagic.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * 独立 GL 管线的实例化渲染器：自管 VAO + 两个 VBO（静态 quad 模板 + 动态实例数据）。
 *
 * <pre>
 * 每实例 11 个 float (44B):
 *   [0..2]  世界坐标
 *   [3..6]  rgba
 *   [7..10] size, birthTick, life, phase
 * </pre>
 *
 * <p>顶点由 GPU 用 4 个顶点的 quad 模板 + divisor 展开，CPU 每 tick 只填数组、
 * 每帧最多上传一次（且只在 tick 改动过时上传）。</p>
 *
 * <p>用 {@code ARB_instanced_arrays}：MC 1.20.1 的 context 是 OpenGL 3.2 core，
 * 3.3 的 {@code glVertexAttribDivisor} 不保证可用，扩展版几乎所有驱动都有。</p>
 */
public final class SparkInstancedRenderer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    public static final int FLOATS_PER_INSTANCE = 11;
    public static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES; // 44

    private static int vao = -1;
    private static int quadVbo;
    private static int instVbo;
    private static FloatBuffer instanceBuffer;
    private static int capacity;

    private SparkInstancedRenderer() {
    }

    public static boolean ensure(int maxInstances) {
        if (vao != -1 && capacity >= maxInstances) {
            return true;
        }
        if (vao != -1) {
            return true; // 已有更小容量，超出部分会被截断（调用方保证不超过）
        }

        try {
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);

            // --- 静态 quad 模板（TRIANGLE_STRIP 顺序）
            quadVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            float[] quad = {
                    -1.0f, -1.0f, 0.0f, 1.0f,
                    1.0f, -1.0f, 1.0f, 1.0f,
                    -1.0f, 1.0f, 0.0f, 0.0f,
                    1.0f, 1.0f, 1.0f, 0.0f
            };
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L);

            // --- 动态实例数据
            instVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                    (long) maxInstances * BYTES_PER_INSTANCE, GL15.GL_DYNAMIC_DRAW);

            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, BYTES_PER_INSTANCE, 0L);
            ARBInstancedArrays.glVertexAttribDivisorARB(2, 1);

            GL20.glEnableVertexAttribArray(3);
            GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, BYTES_PER_INSTANCE, 12L);
            ARBInstancedArrays.glVertexAttribDivisorARB(3, 1);

            // 颜色压成 4 个归一化字节（1 float 的宽度），着色器侧仍是 vec4
            GL20.glEnableVertexAttribArray(4);
            GL20.glVertexAttribPointer(4, 4, GL11.GL_UNSIGNED_BYTE, true, BYTES_PER_INSTANCE, 24L);
            ARBInstancedArrays.glVertexAttribDivisorARB(4, 1);

            GL20.glEnableVertexAttribArray(5);
            GL20.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, BYTES_PER_INSTANCE, 28L);
            ARBInstancedArrays.glVertexAttribDivisorARB(5, 1);

            GL30.glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            instanceBuffer = MemoryUtil.memAllocFloat(maxInstances * FLOATS_PER_INSTANCE);
            capacity = maxInstances;
            LOGGER.info("spark instanced renderer ready: capacity={}, instanceVbo={}KB",
                    maxInstances, (long) maxInstances * BYTES_PER_INSTANCE / 1024);
            return true;
        } catch (Throwable t) {
            LOGGER.error("spark instanced renderer init failed", t);
            return false;
        }
    }

    /** 每批上传的实例数（64K × 56B ≈ 3.6 MB，避免单次大拷贝的驱动尖峰） */
    private static final int UPLOAD_CHUNK_INSTANCES = 65536;

    /** 上传实例数据（只在 tick 改过时调用）；分块上传，允许只传前 count 个（就近优先） */
    public static void upload(float[] source, int count) {
        if (instanceBuffer == null) {
            return;
        }
        int maxFloats = instanceBuffer.capacity();
        int floats = Math.min(count * FLOATS_PER_INSTANCE, maxFloats);
        int chunkFloats = UPLOAD_CHUNK_INSTANCES * FLOATS_PER_INSTANCE;

        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, instVbo);
        try {
            for (int offset = 0; offset < floats; offset += chunkFloats) {
                int n = Math.min(chunkFloats, floats - offset);
                long byteOffset = (long) offset * Float.BYTES;
                long byteLength = (long) n * Float.BYTES;
                // 首选映射直写：省掉 float[] → direct buffer 的一次拷贝
                boolean mapped = false;
                try {
                    java.nio.ByteBuffer target = GL30C.glMapBufferRange(GL15.GL_ARRAY_BUFFER,
                            byteOffset, byteLength,
                            GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_INVALIDATE_RANGE_BIT);
                    if (target != null) {
                        java.nio.FloatBuffer view = target.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                        view.put(source, offset, n);
                        GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
                        mapped = true;
                    }
                } catch (Throwable ignored) {
                    // 驱动不支持映射 → 回退到 glBufferSubData
                }
                if (!mapped) {
                    instanceBuffer.clear();
                    instanceBuffer.put(source, offset, n);
                    instanceBuffer.flip();
                    GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, byteOffset, instanceBuffer);
                }
            }
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    /** 一次 draw call 画完所有光点；targetW/targetH 是渲染目标像素尺寸（纹素吸附用），
     *  refScale 是分趟共用的半分辨率 screenScale，tinyPass：-1=不分趟回退 / 0=大趟 / 1=小趟 */
    public static void draw(int count, Matrix4f projection, float[] viewRotation,
                            float camX, float camY, float camZ, float time, float partial,
                            float maxDist, float fadeStart, float screenScale,
                            float targetW, float targetH, float refScale, int tinyPass) {
        if (vao == -1 || count <= 0) {
            return;
        }
        float[] proj = new float[16];
        projection.get(proj);

        GL20.glUseProgram(SparkGlProgram.program());
        GL20.glUniformMatrix4fv(SparkGlProgram.uProj(), false, proj);
        GL20.glUniformMatrix3fv(SparkGlProgram.uViewRot(), false, viewRotation);
        GL20.glUniform3f(SparkGlProgram.uCamPos(), camX, camY, camZ);
        GL20.glUniform1f(SparkGlProgram.uTime(), time);
        GL20.glUniform1f(SparkGlProgram.uPartial(), partial);
        GL20.glUniform1f(SparkGlProgram.uMaxDist(), maxDist);
        GL20.glUniform1f(SparkGlProgram.uFadeStart(), fadeStart);
        GL20.glUniform1f(SparkGlProgram.uScreenScale(), screenScale);
        GL20.glUniform2f(SparkGlProgram.uHalfRes(), targetW, targetH);
        GL20.glUniform1f(SparkGlProgram.uRefScale(), refScale);
        GL20.glUniform1f(SparkGlProgram.uTinyPass(), tinyPass);

        GlStateManager._glBindVertexArray(vao);
        GL31C.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, count);
        GlStateManager._glBindVertexArray(0);
        GL20.glUseProgram(0);
    }
}
