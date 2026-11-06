package com.wizardmagic.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.core.fusion.ElementFusion;
import com.wizardmagic.core.fusion.FusionElement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * 元素合成的调试命令。
 *
 * <pre>
 *   /fusion check            显示元素总数、各阶数量与校验问题
 *   /fusion &lt;a&gt; &lt;b&gt;       查询两个元素的合成产物（无序）
 * </pre>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class FusionCommand {

    private FusionCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("fusion")
                .then(Commands.literal("check").executes(FusionCommand::check))
                .then(Commands.argument("a", StringArgumentType.word())
                        .then(Commands.argument("b", StringArgumentType.word())
                                .executes(FusionCommand::combine))));
    }

    private static int check(CommandContext<CommandSourceStack> ctx) {
        ElementFusion fusion = ElementFusion.get();
        StringBuilder tiers = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : fusion.tierCounts().entrySet()) {
            if (tiers.length() > 0) {
                tiers.append(' ');
            }
            tiers.append(e.getKey()).append("阶=").append(e.getValue());
        }
        String tierText = tiers.toString();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "fusion.check", fusion.size(), tierText,
                fusion.isValid() ? Component.translatable("fusion.ok")
                        : Component.literal(fusion.issues().size() + " issues")), false);
        for (String issue : fusion.issues()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  · " + issue), false);
        }
        return fusion.size();
    }

    private static int combine(CommandContext<CommandSourceStack> ctx) {
        String a = StringArgumentType.getString(ctx, "a");
        String b = StringArgumentType.getString(ctx, "b");
        ElementFusion fusion = ElementFusion.get();
        var result = fusion.combine(a, b);
        if (result.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("fusion.none", a, b), false);
            return 0;
        }
        FusionElement e = result.get();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "fusion.combine", a, b, Component.translatable(e.translationKey()), e.tier()), false);
        return 1;
    }
}
