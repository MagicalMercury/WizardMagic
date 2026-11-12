package com.wizardmagic.common.client.shader;

import com.wizardmagic.common.client.fx.SparkGlProgram;

/**
 * 坩埚液面的 shader program。
 *
 * <p>编译、链接与 uniform 定位交给 {@link GlProgram}，与 {@link SparkGlProgram} 共用同一套。</p>
 *
 * <p>顶点着色器只用「投影 × 视图旋转 × (世界坐标 − 相机位置)」，
 * <b>不做任何方向光照</b>：液面是水平面，方向光会让它随视角忽明忽暗。
 * 亮度由 lightmap（方块位置光照）决定，UV 走自定义 attribute 以支持 GL_REPEAT 平铺。</p>
 */
public final class CrucibleLiquidProgram {

    private static final GlProgram PROGRAM = new GlProgram(
            "crucible_liquid",
            "shaders/core/crucible_liquid.vsh",
            "shaders/core/crucible_liquid.fsh")
            .attribute(0, "aPos")
            .attribute(1, "aUv")
            .attribute(2, "aColor")
            .attribute(3, "aLight");

    private CrucibleLiquidProgram() {
    }

    public static GlProgram get() {
        return PROGRAM;
    }

    public static boolean ensure() {
        return PROGRAM.ensure();
    }

    public static int uProj() {
        return PROGRAM.uniform("uProj");
    }

    public static int uTex() {
        return PROGRAM.uniform("uTex");
    }

    public static int uLightmap() {
        return PROGRAM.uniform("uLightmap");
    }
}
