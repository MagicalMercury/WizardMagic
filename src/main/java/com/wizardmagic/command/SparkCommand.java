package com.wizardmagic.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.fx.SparkFx;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 特效调试命令：
 * <pre>
 * /spark burst &lt;count&gt; &lt;color&gt; [spread]
 * /spark single &lt;color&gt; &lt;size&gt;      在视线前方 6 格放一个静止的大光点，用来观察质感
 * </pre>
 *
 * <p>颜色用自定义参数类型，支持 {@code #66CCFF}、{@code 0x66CCFF}、六位十六进制、十进制。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class SparkCommand
{
    private SparkCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("spark")
                .then(Commands.literal("burst")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                .then(Commands.argument("color", ColorArgument.color())
                                        .executes(ctx -> burst(ctx, 0.8f))
                                        .then(Commands.argument("spread", FloatArgumentType.floatArg(0.0f, 16.0f))
                                                .executes(ctx -> burst(ctx, FloatArgumentType.getFloat(ctx, "spread")))))))
                .then(Commands.literal("single")
                        .then(Commands.argument("color", ColorArgument.color())
                                .then(Commands.argument("size", FloatArgumentType.floatArg(0.1f, 16.0f))
                                        .executes(SparkCommand::single)))));
    }

    private static int burst(CommandContext<CommandSourceStack> ctx, float spread) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int color = ColorArgument.get(ctx, "color");

        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        Entity entity = source.getEntity();
        if (entity != null) {
            // 与 /spark single 一致：从眼睛出发、沿视线前方 6 格爆发
            pos = entity.getEyePosition().add(entity.getLookAngle().scale(6.0));
        }
        SparkFx.burst(level, pos, count, color, spread, 0.35f, 0.35f, 50);
        source.sendSuccess(() -> Component.literal(String.format(
                "已广播 %d 个光点（颜色 #%06X，散布 %.1f，视线前方 6 格）", count, color, spread)), false);
        return 1;
    }

    /** 单个静止大光点：速度 0、寿命 60 秒，方便近距离观察形状与边缘衰减 */
    private static int single(CommandContext<CommandSourceStack> ctx) {
        int color = ColorArgument.get(ctx, "color");
        float size = FloatArgumentType.getFloat(ctx, "size");

        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        Entity entity = source.getEntity();
        if (entity != null) {
            // 必须从眼睛出发：getPosition() 是脚部，直接 + look*6 会比重心低 1.62 格，
            // 表现就是"光点在准星下方"。
            pos = entity.getEyePosition().add(entity.getLookAngle().scale(6.0));
        }

        SparkFx.burst(level, pos, 1, color, 0.0f, 0.0f, size, 1200);
        source.sendSuccess(() -> Component.literal(String.format(
                "已生成 1 个静止光点（颜色 #%06X，直径 %.2f 格，寿命 60s，视线前方 6 格）", color, size)), false);
        return 1;
    }
}
