package com.wizardmagic.verify;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 自检的 JUnit 包装：跑的时候把逐条 {@code [ok]} 收进缓冲。
 *
 * <p><b>通过时不吱声</b>（否则一次一百多条会把控制台淹掉，而且 IDEA 会把同一份 stdout
 * 再流一遍，每条出现两次、两个套件互相穿插）；**失败时把清单的最后一段塞进断言消息**，
 * 这样 {@code gradlew test} 的控制台和 IDE 的失败详情里都能直接看到是哪几条挂了。</p>
 *
 * <p>要看完整清单：{@code gradlew spellScriptCheck} / {@code gradlew spellLawsCheck}。</p>
 */
final class Checks
{
    /** 失败时随消息带出来的清单长度上限（字符） */
    private static final int TAIL = 6000;

    private Checks()
    {
    }

    @FunctionalInterface
    interface Body
    {
        /** @return 失败条数 */
        int run() throws Exception;
    }

    /** 跑完；有失败就抛 {@link AssertionError}（消息里带清单尾部） */
    static void quiet(Body body) throws Exception
    {
        PrintStream real = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        int failures;
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8))
        {
            System.setOut(capture);
            try
            {
                failures = body.run();
            }
            finally
            {
                System.setOut(real);
            }
        }
        if (failures != 0)
        {
            String text = buffer.toString(StandardCharsets.UTF_8);
            String tail = text.length() <= TAIL ? text : "…（前略）\n" + text.substring(text.length() - TAIL);
            throw new AssertionError(failures + " 项失败，清单末尾：\n" + tail);
        }
    }
}
