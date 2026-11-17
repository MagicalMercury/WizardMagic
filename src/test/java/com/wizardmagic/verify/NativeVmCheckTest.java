package com.wizardmagic.verify;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * JUnit 门禁：原生内核的 JNI 冒烟测试。
 *
 * <p>没有设置 {@code -Dwizardmagic.native.library}（CI 的 Linux runner、没装原生库的机器）
 * 就整个跳过 —— 不能因为"平台没有原生库"让 Java 侧的门禁变红。</p>
 */
final class NativeVmCheckTest
{
    @Test
    void nativeSmoke() throws Exception
    {
        Assumptions.assumeTrue(System.getProperty("wizardmagic.native.library") != null,
                "没有 -Dwizardmagic.native.library，跳过原生分支");
        Checks.quiet(NativeVmCheck::run);
    }
}
