package com.wizardmagic.common.client.shader;

import net.minecraft.client.Camera;
import org.joml.Vector3f;

/**
 * 相机基向量（世界空间），每帧算一次。
 *
 * <p>自建 GL 管线不在 MC 的 {@code RenderSystem} 矩阵栈里渲染，
 * 需要自己拼「投影 × 视图旋转 × (世界坐标 − 相机位置)」。
 * 这里给出 shader 需要的 mat3（列主序）。</p>
 *
 * <p>原先定义在 {@code SparkBatch} 内部，液面也要用，所以提到独立文件。</p>
 */
public record CameraBasis(float rX, float rY, float rZ,
                          float uX, float uY, float uZ,
                          float fX, float fY, float fZ) {

    /** 从当前相机取右/上/前三个基向量。 */
    public static CameraBasis of(Camera camera) {
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vector3f look = camera.getLookVector();
        return new CameraBasis(-left.x(), -left.y(), -left.z(),
                up.x(), up.y(), up.z(),
                look.x(), look.y(), look.z());
    }

    /**
     * GLSL mat3 是列主序，R 的行是 (r, u, -f)：
     * 列 0 = (r.x, u.x, -f.x)，其余同理。
     */
    public float[] toColumnMajor() {
        return new float[]{
                rX, uX, -fX,
                rY, uY, -fY,
                rZ, uZ, -fZ
        };
    }
}
