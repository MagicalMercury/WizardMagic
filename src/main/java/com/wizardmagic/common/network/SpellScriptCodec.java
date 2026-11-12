package com.wizardmagic.common.network;

import com.wizardmagic.core.spell.script.Value;

import net.minecraft.network.FriendlyByteBuf;

import java.util.HashMap;
import java.util.Map;

/**
 * 生成包里的「脚本三件套」：id、源码、云级变量。
 *
 * <p>P1 直接把源码发给客户端，客户端解析后与服务端跑同一套解释器，保证逐位一致。
 * 这样玩家脚本不需要客户端也有同名资源。P2 换成序列化 AST/字节码后可以去掉源码传输
 * （见 `plan/spell-script-language.md` §10 的待办）。</p>
 */
final class SpellScriptCodec
{
    static final int MAX_SOURCE = 32 * 1024;

    private SpellScriptCodec()
    {
    }

    static void write(FriendlyByteBuf buf, String id, String source, Map<String, Value> vars)
    {
        buf.writeUtf(id == null ? "" : id, 64);
        buf.writeUtf(source == null ? "" : source, MAX_SOURCE);

        Map<String, Value> map = vars == null ? Map.of() : vars;
        buf.writeVarInt(map.size());
        for (Map.Entry<String, Value> e : map.entrySet())
        {
            buf.writeUtf(e.getKey(), 64);
            Value v = e.getValue();
            if (v instanceof Value.Num n)
            {
                buf.writeByte(0);
                buf.writeDouble(n.v());
            }
            else if (v instanceof Value.Bool b)
            {
                buf.writeByte(1);
                buf.writeBoolean(b.v());
            }
            else if (v instanceof Value.Vec3 x)
            {
                buf.writeByte(2);
                buf.writeDouble(x.x());
                buf.writeDouble(x.y());
                buf.writeDouble(x.z());
            }
            else if (v instanceof Value.Elem el)
            {
                buf.writeByte(3);
                buf.writeVarInt(el.id());
                buf.writeUtf(el.name(), 64);
            }
            else
            {
                buf.writeByte(4);
            }
        }
    }

    static String readId(FriendlyByteBuf buf)
    {
        return buf.readUtf(64);
    }

    static String readSource(FriendlyByteBuf buf)
    {
        return buf.readUtf(MAX_SOURCE);
    }

    static Map<String, Value> readVars(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        Map<String, Value> out = new HashMap<>();
        for (int i = 0; i < n; i++)
        {
            String name = buf.readUtf(64);
            int type = buf.readByte();
            switch (type)
            {
                case 0:
                    out.put(name, new Value.Num(buf.readDouble()));
                    break;
                case 1:
                    out.put(name, new Value.Bool(buf.readBoolean()));
                    break;
                case 2:
                    out.put(name, new Value.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
                    break;
                case 3:
                    int id = buf.readVarInt();
                    out.put(name, new Value.Elem(id, buf.readUtf(64)));
                    break;
                default:
                    break;
            }
        }
        return out;
    }
}
