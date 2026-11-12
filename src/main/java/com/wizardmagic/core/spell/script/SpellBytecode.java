package com.wizardmagic.core.spell.script;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 编译产物的信封：{@code .spellc} 的读写。
 *
 * <p>里面装的是 {@link FastProgram} 编出来的寄存器机指令流（{@code int[]} + 常量表 +
 * 自由变量表 + 外提量表）。<b>它不是能脱离源码发布的东西</b>：顶层标量语句和外提表达式
 * 仍是语法树节点，读回时要用同一份源码重新解析、再按编号挂回去。所以这是一个
 * 「缓存」，不是独立的字节码格式。</p>
 *
 * <p>信封负责四件事，任何一条对不上就当缓存无效（返回 null，调用方重新编译并覆盖）：</p>
 * <ul>
 *   <li>{@link #MAGIC} + {@link #FORMAT}：文件类型与信封版本；</li>
 *   <li>{@code FastProgram.IR_VERSION}：指令集/分配规则/外提编号顺序变了就整批作废；</li>
 *   <li><b>源码哈希</b>：源码改了 → 重编；</li>
 *   <li><b>变量签名</b>：{@code cast} 变量决定自由变量的类型推断，签名不同 → 不认（运行时再按云的变量核对一次）。</li>
 * </ul>
 *
 * <p>末尾还有一个 32 位校验和，用来挡住截断/手改的文件——这类坏文件必须安静地重编，
 * 而不是让服务器读到一半的指令流。</p>
 */
public final class SpellBytecode
{
    /** "WMSP" */
    private static final int MAGIC = 0x574D5350;
    /** 信封格式版本；只改信封布局时 +1 */
    private static final int FORMAT = 1;
    /** 存档里的后缀（文件名是 {@code <id>.spellc}） */
    public static final String EXTENSION = "spellc";

    private static final int MAX_BLOB = 8 * 1024 * 1024;

    /**
     * 解包结果。
     *
     * @param program 编好的快速路径
     * @param varsSig 这份代码是按哪一套 {@code cast} 变量编的；空串 = 没有云级变量
     */
    public record Unpacked(FastProgram program, String varsSig)
    {
    }

    private SpellBytecode()
    {
    }

    // ---------------------------------------------------------------- 哈希与签名

    /** 源码哈希（FNV-1a 64）。只用于"判断是不是同一份源码"，不做安全用途 */
    public static long sourceHash(String source)
    {
        long h = 0xCBF29CE484222325L;
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes)
        {
            h ^= (b & 0xFF);
            h *= 0x100000001B3L;
        }
        return h;
    }

    /**
     * 云级变量的类型签名：{@code "名字:类型,名字:类型"}，按名字排序。
     *
     * <p>{@link FastProgram} 的自由变量是按这堆变量的<b>类型</b>推断的，所以：
     * 变量集合或任何一个类型变了，编出来的代码就不能再用。</p>
     */
    public static String varsSignature(Map<String, Value> vars)
    {
        if (vars == null || vars.isEmpty())
        {
            return "";
        }
        List<String> parts = new ArrayList<>(vars.size());
        for (Map.Entry<String, Value> e : vars.entrySet())
        {
            parts.add(e.getKey() + ":" + typeChar(e.getValue()));
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private static char typeChar(Value v)
    {
        if (v instanceof Value.Num)
        {
            return 'n';
        }
        if (v instanceof Value.Bool)
        {
            return 'b';
        }
        if (v instanceof Value.Vec3)
        {
            return 'v';
        }
        if (v instanceof Value.Elem)
        {
            return 'e';
        }
        return '-';
    }

    // ---------------------------------------------------------------- 打包

    /**
     * @return null = 这份程序落不了盘（例如某个外提量没有编号），调用方不写缓存即可
     */
    static byte[] pack(String source, String varsSig, FastProgram program)
    {
        try
        {
            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream(2048);
            try (DataOutputStream body = new DataOutputStream(bodyBuf))
            {
                program.writeTo(body);
            }

            ByteArrayOutputStream all = new ByteArrayOutputStream(bodyBuf.size() + 64);
            DataOutputStream out = new DataOutputStream(all);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(FastProgram.IR_VERSION);
            out.writeLong(sourceHash(source));
            out.writeUTF(varsSig == null ? "" : varsSig);
            byte[] body = bodyBuf.toByteArray();
            out.writeInt(body.length);
            out.write(body);
            out.flush();
            out.writeInt(checksum(all.toByteArray(), all.size()));
            out.flush();
            return all.toByteArray();
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * 解包并校验。
     *
     * @return null = 文件不是本格式、版本不符、源码没对上、变量签名格式坏了，或者内容被截断
     */
    static Unpacked unpack(SpellScript script, byte[] blob)
    {
        if (blob == null || blob.length < 32 || blob.length > MAX_BLOB)
        {
            return null;
        }
        int bodyEnd = blob.length - 4;
        try
        {
            int stored = readInt(blob, bodyEnd);
            if (checksum(blob, bodyEnd) != stored)
            {
                return null;
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob, 0, bodyEnd));
            if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readInt() != FastProgram.IR_VERSION)
            {
                return null;
            }
            if (in.readLong() != sourceHash(script.source))
            {
                return null;
            }
            String varsSig = in.readUTF();
            int bodyLen = in.readInt();
            if (bodyLen < 0 || bodyLen > bodyEnd)
            {
                return null;
            }
            byte[] body = in.readNBytes(bodyLen);
            if (body.length != bodyLen)
            {
                return null;
            }
            FastProgram program = FastProgram.decode(script.program,
                    new DataInputStream(new ByteArrayInputStream(body)));
            return program == null ? null : new Unpacked(program, varsSig);
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static int readInt(byte[] b, int off)
    {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int checksum(byte[] b, int len)
    {
        int h = 0x811C9DC5;
        for (int i = 0; i < len; i++)
        {
            h ^= (b[i] & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }
}
