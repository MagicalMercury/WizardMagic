package com.wizardmagic.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.wizardmagic.WizardMagic;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 独立 GL 管线用的 shader program（不经过 MC 的 ShaderInstance / shader json）。
 *
 * <p>为什么自己编译：实例化渲染需要自定义 attribute layout（divisor），
 * MC 的 {@code VertexFormat} 不支持 divisor，{@code ShaderInstance} 也只能按 json 声明属性。</p>
 *
 * <p>顶点着色器里做四件事：把世界坐标转到视图空间（路线 A 的 R）、用实例参数算 alpha/size、
 * 用单位 quad 展开 billboard、按投影大小分趟（小粒子全分辨率渲染保证运动平滑与亮度稳定，
 * 大粒子走半分辨率趟省片元）。片元着色器沿用原来的径向衰减 + 核心过曝 + 闪烁。</p>
 */
public final class SparkGlProgram
{
    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    private static int program = -1;
    private static int uProj = -1;
    private static int uViewRot = -1;
    private static int uCamPos = -1;
    private static int uTime = -1;
    private static int uPartial = -1;
    private static int uMaxDist = -1;
    private static int uFadeStart = -1;
    private static int uScreenScale = -1;
    private static int uHalfRes = -1;
    private static int uRefScale = -1;
    private static int uTinyPass = -1;
    private static boolean failed;

    private SparkGlProgram() {
    }

    public static int program() {
        return program;
    }

    public static int uProj() {
        return uProj;
    }

    public static int uViewRot() {
        return uViewRot;
    }

    public static int uCamPos() {
        return uCamPos;
    }

    public static int uTime() {
        return uTime;
    }

    public static int uPartial() {
        return uPartial;
    }

    public static int uMaxDist() {
        return uMaxDist;
    }

    public static int uFadeStart() {
        return uFadeStart;
    }

    public static int uScreenScale() {
        return uScreenScale;
    }

    public static int uHalfRes() {
        return uHalfRes;
    }

    public static int uRefScale() {
        return uRefScale;
    }

    public static int uTinyPass() {
        return uTinyPass;
    }

    /** 编译一次；失败后不再重试（返回 false，调用方回退）。 */
    public static boolean ensure() {
        if (program != -1) {
            return true;
        }
        if (failed) {
            return false;
        }

        String vertexSource = ShaderSource.load("shaders/core/spark_instanced.vsh");
        String fragmentSource = ShaderSource.load("shaders/core/spark_instanced.fsh");
        if (vertexSource == null || fragmentSource == null) {
            failed = true;
            return false;
        }

        int vs = compile(GL20.GL_VERTEX_SHADER, vertexSource, "vertex");
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource, "fragment");
        if (vs == 0 || fs == 0) {
            failed = true;
            return false;
        }

        int handle = GL20.glCreateProgram();
        GL20.glAttachShader(handle, vs);
        GL20.glAttachShader(handle, fs);
        GL20.glBindAttribLocation(handle, 0, "aPos");
        GL20.glBindAttribLocation(handle, 1, "aUv");
        GL20.glBindAttribLocation(handle, 2, "iPos");
        GL20.glBindAttribLocation(handle, 3, "iPrev");
        GL20.glBindAttribLocation(handle, 4, "iColor");
        GL20.glBindAttribLocation(handle, 5, "iMeta");
        GL20.glLinkProgram(handle);

        if (GL20.glGetProgrami(handle, GL20.GL_LINK_STATUS) == 0) {
            LOGGER.error("spark program link failed: {}", GL20.glGetProgramInfoLog(handle));
            GL20.glDeleteProgram(handle);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            failed = true;
            return false;
        }

        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        program = handle;
        uProj = GL20.glGetUniformLocation(handle, "uProj");
        uViewRot = GL20.glGetUniformLocation(handle, "uViewRot");
        uCamPos = GL20.glGetUniformLocation(handle, "uCamPos");
        uTime = GL20.glGetUniformLocation(handle, "uTime");
        uPartial = GL20.glGetUniformLocation(handle, "uPartial");
        uMaxDist = GL20.glGetUniformLocation(handle, "uMaxDist");
        uFadeStart = GL20.glGetUniformLocation(handle, "uFadeStart");
        uScreenScale = GL20.glGetUniformLocation(handle, "uScreenScale");
        uHalfRes = GL20.glGetUniformLocation(handle, "uHalfRes");
        uRefScale = GL20.glGetUniformLocation(handle, "uRefScale");
        uTinyPass = GL20.glGetUniformLocation(handle, "uTinyPass");

        LOGGER.info("spark instanced program ready (id={})", handle);
        return true;
    }

    private static int compile(int type, String source, String label) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("spark {} shader compile failed: {}", label, GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
