package com.wizardmagic.verify;

import org.junit.jupiter.api.Test;

/**
 * JUnit 门禁：把 {@link ScriptSelfCheck} 的自检包成一个用例。
 *
 * <p>自检本身仍是普通程序（{@code gradlew spellScriptCheck} / 右键 Run 都能跑，输出逐条
 * {@code [ok]}）。这里只是让 {@code gradlew test} 与 IDE 的测试按钮也能触发它：
 * 通过时不刷屏，失败时断言消息里带清单末尾。</p>
 */
final class ScriptSelfCheckTest
{
    @Test
    void scriptLanguageChecks() throws Exception
    {
        // false = 不测性能（性能走 gradlew spellPerf，门禁里不测时间）
        Checks.quiet(() -> ScriptSelfCheck.run(false));
    }
}
