package com.wizardmagic.verify;

import org.junit.jupiter.api.Test;

/**
 * JUnit 门禁：把 {@link SpellSelfCheck} 的自检包成一个用例。
 *
 * <p>覆盖元素表、噪声、确定性、形状采样与单步成本；通过时不刷屏，
 * 失败时断言消息里带清单末尾。完整清单跑 {@code gradlew spellLawsCheck}。</p>
 */
final class SpellSelfCheckTest
{
    @Test
    void particleLayerChecks() throws Exception
    {
        Checks.quiet(SpellSelfCheck::run);
    }
}
