package com.wizardmagic.verify;

import org.junit.jupiter.api.Test;

/**
 * JUnit 门禁：把 {@link ScriptSpecCheck}（脚本语言正确性专项）包成一个用例。
 *
 * <p>和 {@link ScriptSelfCheckTest} 分开两个用例，是为了失败时一眼看出是"整体不能用"
 * 还是"某条语言语义跟约定不一致"。</p>
 */
final class ScriptSpecCheckTest
{
    @Test
    void scriptSemanticsChecks() throws Exception
    {
        Checks.quiet(ScriptSpecCheck::run);
    }
}
