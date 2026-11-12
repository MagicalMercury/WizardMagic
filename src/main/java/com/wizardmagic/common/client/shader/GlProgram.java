package com.wizardmagic.common.client.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自建 GL 管线用的 shader program。
 *
 * <p>把「加载 GLSL → 编译 → 绑定 attribute → 链接 → 定位 uniform → 失败不重试」
 * 集中在一处。此前 {@link SparkGlProgram} 与 {@code SparkHalfResTarget} 各写了一份，
 * 改一个细节要同步两处，且错误处理风格不一致（一个返回 0，一个抛异常）。</p>
 *
 * <p>用法：构造时给出两个源码路径，用 {@link #attribute(int, String)} 声明顶点属性下标，
 * 然后 {@link #ensure()} 惰性编译；失败后不再重试，调用方据此回退。</p>
 */
public final class GlProgram {

    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    private final String label;
    private final String vertexPath;
    private final String fragmentPath;

    /** attribute index -> 变量名，按声明顺序绑定。 */
    private final Map<Integer, String> attributes = new LinkedHashMap<>();

    /** uniform 名 -> location，首次查询后缓存。 */
    private final Map<String, Integer> uniformCache = new HashMap<>();

    private int program = -1;
    private boolean failed;

    /**
     * @param label        日志里用的名字
     * @param vertexPath   相对 {@code assets/wizardmagic/} 的路径，如 {@code shaders/core/xxx.vsh}
     * @param fragmentPath 同上
     */
    public GlProgram(String label, String vertexPath, String fragmentPath) {
        this.label = label;
        this.vertexPath = vertexPath;
        this.fragmentPath = fragmentPath;
    }

    /** 绑定顶点属性到固定下标；必须在 {@link #ensure()} 之前调用。 */
    public GlProgram attribute(int index, String name) {
        attributes.put(index, name);
        return this;
    }

    /** 编译并链接一次；失败后固定返回 false，不再重复尝试。 */
    public boolean ensure() {
        if (program != -1) {
            return true;
        }
        if (failed) {
            return false;
        }

        String vertexSource = ShaderSource.load(vertexPath);
        String fragmentSource = ShaderSource.load(fragmentPath);
        if (vertexSource == null || fragmentSource == null) {
            failed = true;
            return false;
        }

        int vertexShader = compile(GL20.GL_VERTEX_SHADER, vertexSource, "vertex");
        int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource, "fragment");
        if (vertexShader == 0 || fragmentShader == 0) {
            failed = true;
            return false;
        }

        int handle = GL20.glCreateProgram();
        GL20.glAttachShader(handle, vertexShader);
        GL20.glAttachShader(handle, fragmentShader);
        attributes.forEach((index, name) -> GL20.glBindAttribLocation(handle, index, name));
        GL20.glLinkProgram(handle);

        boolean linked = GL20.glGetProgrami(handle, GL20.GL_LINK_STATUS) != GL11.GL_FALSE;
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        if (!linked) {
            LOGGER.error("{} program link failed: {}", label, GL20.glGetProgramInfoLog(handle));
            GL20.glDeleteProgram(handle);
            failed = true;
            return false;
        }

        program = handle;
        LOGGER.info("{} program ready (id={})", label, handle);
        return true;
    }

    public int handle() {
        return program;
    }

    /** uniform 位置；program 未就绪时返回 -1。 */
    public int uniform(String name) {
        if (program == -1) {
            return -1;
        }
        return uniformCache.computeIfAbsent(name, n -> GL20.glGetUniformLocation(program, n));
    }

    public void use() {
        if (program != -1) {
            GL20.glUseProgram(program);
        }
    }

    /** 解绑当前 program。 */
    public static void stop() {
        GL20.glUseProgram(0);
    }

    public void destroy() {
        if (program != -1) {
            GL20.glDeleteProgram(program);
            program = -1;
        }
        uniformCache.clear();
        failed = false;
    }

    private int compile(int type, String source, String which) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("{} {} shader compile failed: {}", label, which,
                    GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
