package com.wizardmagic.verify;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * JUnit 门禁：原生内核与 Java 后端的整段逐位对拍。
 *
 * <p>没有原生库（CI 的 Linux runner）就跳过；有库但结果不一致就是真失败。</p>
 */
final class NativeVmParityTest
{
    @Test
    void nativeParity() throws Exception
    {
        Assumptions.assumeTrue(System.getProperty("wizardmagic.native.library") != null,
                "没有 -Dwizardmagic.native.library，跳过原生分支");
        Checks.quiet(NativeVmParity::run);
    }
}
