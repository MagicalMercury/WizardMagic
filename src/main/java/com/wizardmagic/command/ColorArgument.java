package com.wizardmagic.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Collection;
import java.util.List;

/**
 * 颜色参数：接受 {@code #RRGGBB}、{@code 0xRRGGBB}、六位十六进制、十进制。
 *
 * <p>为什么不用 {@code StringArgumentType.word()}：Brigadier 的 word() 只允许
 * {@code [0-9A-Za-z_.+-]}，读到 {@code #} 就停，接着报"尾随数据"。
 * 自定义参数类型可以一路读到空白，再交给颜色解析。</p>
 */
public class ColorArgument implements ArgumentType<Integer>
{
    private static final Collection<String> EXAMPLES = List.of("0x66CCFF", "#66CCFF", "66CCFF", "6699000");

    /** 命令树同步到客户端必须注册；由静态初始化完成。 */
    public static final Info INFO = ArgumentTypeInfos.registerByClass(ColorArgument.class, new Info());

    public static ColorArgument color() {
        return new ColorArgument();
    }

    public static int get(CommandContext<?> context, String name) {
        return context.getArgument(name, Integer.class);
    }

    @Override
    public Integer parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        String raw = reader.getString().substring(start, reader.getCursor());
        int color = parse(raw);
        if (color < 0) {
            reader.setCursor(start);
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        return color;
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }

    /** @return 0xRRGGBB；无法解析时返回 -1 */
    public static int parse(String input) {
        String s = input.trim();
        if (s.isEmpty()) {
            return -1;
        }
        try {
            if (s.startsWith("#")) {
                return (int) (Long.parseLong(s.substring(1), 16) & 0xFFFFFFL);
            }
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) (Long.parseLong(s.substring(2), 16) & 0xFFFFFFL);
            }
            if (s.length() == 6 && s.matches("[0-9A-Fa-f]{6}")) {
                return (int) (Long.parseLong(s, 16) & 0xFFFFFFL);
            }
            return (int) (Long.parseLong(s, 10) & 0xFFFFFFL);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 参数类型的网络/JSON 序列化器（1.20.1 通过 ArgumentTypeInfo 注册）。 */
    public static final class Info implements ArgumentTypeInfo<ColorArgument, Info.Template>
    {
        @Override
        public void serializeToNetwork(Template template, FriendlyByteBuf buffer) {
        }

        @Override
        public Template deserializeFromNetwork(FriendlyByteBuf buffer) {
            return new Template();
        }

        @Override
        public void serializeToJson(Template template, JsonObject json) {
        }

        @Override
        public Template unpack(ColorArgument argument) {
            return new Template();
        }

        public static final class Template implements ArgumentTypeInfo.Template<ColorArgument>
        {
            @Override
            public ColorArgument instantiate(CommandBuildContext context) {
                return new ColorArgument();
            }

            @Override
            public ArgumentTypeInfo<ColorArgument, ?> type() {
                return INFO;
            }
        }
    }
}
