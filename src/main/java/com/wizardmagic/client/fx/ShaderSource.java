package com.wizardmagic.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 自建 GL 管线用的 GLSL 加载器。
 *
 * <p>不走 MC 的 {@code ShaderInstance} / {@code RegisterShadersEvent}：
 * 光点程序是自己 {@code glCreateProgram} 编译的，所以这里只负责从
 * {@code assets/wizardmagic/shaders/core/} 读源文件。</p>
 *
 * <p>shader 编译是惰性的（首次渲染时），那时资源管理器已经可用；
 * 加载失败返回 {@code null}，由调用方回退（全分辨率 / 不渲染）。</p>
 */
public final class ShaderSource
{
    private static final Logger LOGGER = LoggerFactory.getLogger("wizardmagic-fx");

    private ShaderSource() {
    }

    /** @return GLSL 源码；null 表示加载失败 */
    public static String load(String path) {
        ResourceLocation location = new ResourceLocation("wizardmagic", path);
        try (InputStream in = Minecraft.getInstance().getResourceManager()
                .getResource(location).orElseThrow().open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("failed to load shader {}", location, e);
            return null;
        }
    }
}
