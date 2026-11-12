package com.wizardmagic.common.client.fx;

import com.wizardmagic.common.client.shader.GlProgram;

/**
 * 光点实例化渲染的 program。
 *
 * <p>编译、链接与 uniform 定位交给 {@link GlProgram}；本类只声明 attribute 下标
 * 和 spark 自己的 uniform 名，保持原来的静态调用方式（{@code SparkInstancedRenderer}
 * 与 {@code SparkBatch} 无需改动）。</p>
 */
public final class SparkGlProgram {

    private static final GlProgram PROGRAM = new GlProgram(
            "spark_instanced",
            "shaders/core/spark_instanced.vsh",
            "shaders/core/spark_instanced.fsh")
            .attribute(0, "aPos")
            .attribute(1, "aUv")
            .attribute(2, "iPos")
            .attribute(3, "iPrev")
            .attribute(4, "iColor")
            .attribute(5, "iMeta");

    private SparkGlProgram() {
    }

    /** 编译一次；失败后不再重试，调用方回退。 */
    public static boolean ensure() {
        return PROGRAM.ensure();
    }

    public static int program() {
        return PROGRAM.handle();
    }

    public static int uProj() {
        return PROGRAM.uniform("uProj");
    }

    public static int uViewRot() {
        return PROGRAM.uniform("uViewRot");
    }

    public static int uCamPos() {
        return PROGRAM.uniform("uCamPos");
    }

    public static int uTime() {
        return PROGRAM.uniform("uTime");
    }

    public static int uPartial() {
        return PROGRAM.uniform("uPartial");
    }

    public static int uMaxDist() {
        return PROGRAM.uniform("uMaxDist");
    }

    public static int uFadeStart() {
        return PROGRAM.uniform("uFadeStart");
    }

    public static int uScreenScale() {
        return PROGRAM.uniform("uScreenScale");
    }

    public static int uHalfRes() {
        return PROGRAM.uniform("uHalfRes");
    }

    public static int uRefScale() {
        return PROGRAM.uniform("uRefScale");
    }

    public static int uTinyPass() {
        return PROGRAM.uniform("uTinyPass");
    }

    /** 0 = 火花按 (1-t)²；1 = 法术粒子平台期 + 尾段快速淡出 */
    public static int uLifeFade() {
        return PROGRAM.uniform("uLifeFade");
    }
}
