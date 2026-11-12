package com.wizardmagic.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.SpellShape;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellCloudManager;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellElements;
import com.wizardmagic.common.spell.McHost;
import com.wizardmagic.common.spell.SpellScripts;
import com.wizardmagic.core.spell.script.CastResult;
import com.wizardmagic.core.spell.script.EmitSpec;
import com.wizardmagic.core.spell.script.ScriptException;
import com.wizardmagic.core.spell.script.SpellScript;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * 法术调试命令（P0/P1 阶段）。
 *
 * <pre>
 * /spell debug spawn &lt;element&gt; &lt;amount&gt; [life] [seed]
 * /spell debug clear
 * /spell debug stats
 * /spell debug hash
 * </pre>
 *
 * <p>全部服务端执行；生成只发一个 spawn 包，轨迹由客户端用同一 seed 复算。</p>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class SpellCommand
{
    private static final SuggestionProvider<CommandSourceStack> ELEMENTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(SpellElements.ids(), builder);
    private static final SuggestionProvider<CommandSourceStack> SHAPE_IDS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(Shapes.ids(), builder);
    private static final SuggestionProvider<CommandSourceStack> SCRIPTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(SpellScripts.knownIds(), builder);

    private SpellCommand()
    {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event)
    {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("spell")
                .then(Commands.literal("debug")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("element", StringArgumentType.word())
                                        .suggests(ELEMENTS)
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(
                                                        SpellConstants.MIN_CLOUD_PARTICLES,
                                                        SpellConstants.MAX_CLOUD_PARTICLES))
                                                .executes(ctx -> spawn(ctx, SpellConstants.DEFAULT_LIFE_SEC, 0L, false))
                                                .then(Commands.argument("life", FloatArgumentType.floatArg(0.5F, 10.0F))
                                                        .executes(ctx -> spawn(ctx,
                                                                FloatArgumentType.getFloat(ctx, "life"), 0L, false))
                                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                                .executes(ctx -> spawn(ctx,
                                                                        FloatArgumentType.getFloat(ctx, "life"),
                                                                        LongArgumentType.getLong(ctx, "seed"), true)))))))
                        .then(Commands.literal("anchor")
                                .then(Commands.argument("element", StringArgumentType.word())
                                        .suggests(ELEMENTS)
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(
                                                        SpellConstants.MIN_CLOUD_PARTICLES,
                                                        SpellConstants.MAX_CLOUD_PARTICLES))
                                                .executes(SpellCommand::anchor))))
                        .then(Commands.literal("cast")
                                .then(Commands.argument("shape", StringArgumentType.word())
                                        .suggests(SHAPE_IDS)
                                        .then(Commands.argument("element", StringArgumentType.word())
                                                .suggests(ELEMENTS)
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(
                                                                SpellConstants.MIN_CLOUD_PARTICLES,
                                                                SpellConstants.MAX_CLOUD_PARTICLES))
                                                        .executes(SpellCommand::cast)))))
                        .then(Commands.literal("run")
                                .then(Commands.argument("script", StringArgumentType.word())
                                        .suggests(SCRIPTS)
                                        .executes(SpellCommand::runScript)))
                        .then(Commands.literal("scripts").executes(SpellCommand::listScripts))
                        .then(Commands.literal("cache").executes(SpellCommand::rebuildCache))
                        .then(Commands.literal("shapes").executes(SpellCommand::shapes))
                        .then(Commands.literal("clear").executes(SpellCommand::clear))
                        .then(Commands.literal("stats").executes(SpellCommand::stats))
                        .then(Commands.literal("hash").executes(SpellCommand::hash))));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, float life, long seed, boolean hasSeed)
    {
        String element = StringArgumentType.getString(ctx, "element");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        int elementId = SpellElements.indexOf(element);
        if (elementId < 0)
        {
            ctx.getSource().sendFailure(Component.literal("[spell] 未知元素: " + element));
            return 0;
        }

        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        long realSeed = hasSeed ? seed : level.getRandom().nextLong();
        Vec3 pos = source.getPosition();
        Entity entity = source.getEntity();
        if (entity != null)
        {
            pos = entity.getEyePosition().add(entity.getLookAngle().scale(6.0));
        }

        SpellCloud cloud = SpellCloudManager.spawn(level, elementId, amount, pos, realSeed, life);
        if (cloud == null)
        {
            source.sendFailure(Component.literal("[spell] 生成失败: " + SpellCloudManager.lastReject()));
            return 0;
        }
        final Vec3 at = pos;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[spell] 云 #%d 元素=%s 粒子=%d seed=%d life=%.1fs 位置=(%.1f, %.1f, %.1f)",
                cloud.id, element, cloud.count, realSeed, cloud.lifeSec, at.x, at.y, at.z)), false);
        return 1;
    }

    /**
     * 生成一朵**常驻云**（法阵 / 结界的占位形态）：不会自然死亡，附近没玩家也继续推进，
     * 玩家进入 64 格范围时补发状态快照。用 {@code /spell debug clear} 清除。
     */
    private static int anchor(CommandContext<CommandSourceStack> ctx)
    {
        String element = StringArgumentType.getString(ctx, "element");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        int elementId = SpellElements.indexOf(element);
        if (elementId < 0)
        {
            ctx.getSource().sendFailure(Component.literal("[spell] 未知元素: " + element));
            return 0;
        }

        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        Entity entity = source.getEntity();
        if (entity != null)
        {
            pos = entity.getEyePosition().add(entity.getLookAngle().scale(6.0));
        }
        long seed = level.getRandom().nextLong();

        // 常驻云：静止（Laws 对 persistent 直接返回），参数给 0 表示「不靠流场驱动」
        SpellCloud cloud = SpellCloudManager.spawn(level, elementId, amount, pos, seed, 60.0F,
                Shapes.POINT.id(), Shapes.POINT.defaults(),
                0.0F, SpellConstants.DEFAULT_ENERGY, 0.0F, true);
        if (cloud == null)
        {
            source.sendFailure(Component.literal("[spell] 生成失败: " + SpellCloudManager.lastReject()));
            return 0;
        }
        final Vec3 at = pos;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[spell] 常驻云 #%d 元素=%s 粒子=%d seed=%d 位置=(%.1f, %.1f, %.1f)",
                cloud.id, element, cloud.count, seed, at.x, at.y, at.z)), false);
        return 1;
    }

    /** `/spell debug cast <shape> <element> <amount>`：用形状的默认参数放一朵云 */
    private static int cast(CommandContext<CommandSourceStack> ctx)
    {
        String shapeId = StringArgumentType.getString(ctx, "shape");
        if (!Shapes.exists(shapeId))
        {
            ctx.getSource().sendFailure(Component.literal("[spell] 未知形状: " + shapeId
                    + "（可用: " + String.join(", ", Shapes.ids()) + "）"));
            return 0;
        }
        String element = StringArgumentType.getString(ctx, "element");
        int elementId = SpellElements.indexOf(element);
        if (elementId < 0)
        {
            ctx.getSource().sendFailure(Component.literal("[spell] 未知元素: " + element));
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        SpellShape shape = Shapes.byId(shapeId);
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 pos = aimPoint(source);
        long seed = level.getRandom().nextLong();

        SpellCloud cloud = SpellCloudManager.spawn(level, elementId, amount, pos, seed, 4.0F,
                shape.id(), shape.defaults(),
                0.06F, SpellConstants.DEFAULT_ENERGY, 0.02F, false);
        if (cloud == null)
        {
            source.sendFailure(Component.literal("[spell] 生成失败: " + SpellCloudManager.lastReject()));
            return 0;
        }
        final Vec3 at = pos;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[spell] 形状=%s 云 #%d 元素=%s 粒子=%d seed=%d 位置=(%.1f, %.1f, %.1f)",
                shape.id(), cloud.id, element, cloud.count, seed, at.x, at.y, at.z)), false);
        return 1;
    }

    /** 用行为脚本施法：cast 在服务端求值一次，emit 出几朵云就生成几朵 */
    private static int runScript(CommandContext<CommandSourceStack> ctx)
    {
        String id = StringArgumentType.getString(ctx, "script");
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 here = aimPoint(source);

        SpellScript script;
        try
        {
            script = SpellScripts.load(id);
        }
        catch (ScriptException e)
        {
            source.sendFailure(Component.literal("[spell] " + id + " 编译失败 " + e.display()));
            return 0;
        }

        long seed = level.getRandom().nextLong();
        CastResult result;
        try
        {
            result = script.cast(new McHost(level, here), seed);
        }
        catch (ScriptException e)
        {
            source.sendFailure(Component.literal("[spell] " + id + " 施法失败 " + e.display()));
            return 0;
        }

        int spawned = 0;
        for (int i = 0; i < result.emits().size(); i++)
        {
            EmitSpec spec = result.emits().get(i);
            if (SpellCloudManager.spawnScripted(level, script, spec, seed + i, result.vars()) != null)
            {
                spawned++;
            }
            else
            {
                source.sendFailure(Component.literal("[spell] " + id + " 生成被拒: "
                        + SpellCloudManager.lastReject()));
            }
        }
        final int cloudCount = spawned;
        final int nodes = script.nodes();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[spell] %s 节点=%d 云=%d seed=%d", id, nodes, cloudCount, seed)), false);
        return spawned;
    }

    private static int listScripts(CommandContext<CommandSourceStack> ctx)
    {
        StringBuilder sb = new StringBuilder("[spell] 脚本: ");
        boolean first = true;
        for (String id : SpellScripts.knownIds())
        {
            if (!first)
            {
                sb.append("；");
            }
            first = false;
            try
            {
                SpellScript s = SpellScripts.load(id);
                long bytes = SpellScripts.cacheSize(id);
                sb.append(id)
                        .append(SpellScripts.isPlayerSpell(id) ? "[存档]" : "[内置]")
                        .append(' ').append(s.nodes()).append(" 节点")
                        .append(s.fastPathActive() ? " 快速路径" : " 解释器")
                        .append(bytes >= 0 ? " 缓存" + bytes + "B" : " 无缓存");
            }
            catch (ScriptException e)
            {
                sb.append(id).append(" 失败 ").append(e.display());
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** 重新读源码、重编、覆盖存档里的编译产物 */
    private static int rebuildCache(CommandContext<CommandSourceStack> ctx)
    {
        SpellScripts.reload();
        String summary = SpellScripts.warmUp(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("[spell] 已重建编译缓存：" + summary), false);
        return 1;
    }

    private static int shapes(CommandContext<CommandSourceStack> ctx)
    {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[spell] 内置形状: " + String.join(", ", Shapes.ids())), false);
        return Shapes.count();
    }

    private static Vec3 aimPoint(CommandSourceStack source)
    {
        Vec3 pos = source.getPosition();
        Entity entity = source.getEntity();
        if (entity != null)
        {
            pos = entity.getEyePosition().add(entity.getLookAngle().scale(6.0));
        }
        return pos;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx)
    {
        ServerLevel level = ctx.getSource().getLevel();
        int before = SpellCloudManager.cloudCount(level);
        SpellCloudManager.clear(level, true);
        ctx.getSource().sendSuccess(() -> Component.literal("[spell] 已清空 " + before + " 朵云"), false);
        return before;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx)
    {
        ServerLevel level = ctx.getSource().getLevel();
        ctx.getSource().sendSuccess(() -> Component.literal("[spell] " + SpellCloudManager.statsLine(level)), false);
        List<SpellCloud> clouds = SpellCloudManager.clouds(level);
        int show = Math.min(clouds.size(), 12);
        for (int i = 0; i < show; i++)
        {
            SpellCloud cloud = clouds.get(i);
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "[spell] #%d %s 粒子=%d seed=%d 步=%d/%d",
                    cloud.id, SpellElements.id(cloud.elementId), cloud.count, cloud.castSeed,
                    cloud.ageSteps(), cloud.maxSteps)), false);
        }
        if (clouds.size() > show)
        {
            ctx.getSource().sendSuccess(() -> Component.literal("[spell] ... 其余 "
                    + (clouds.size() - show) + " 朵省略"), false);
        }
        return clouds.size();
    }

    private static int hash(CommandContext<CommandSourceStack> ctx)
    {
        ServerLevel level = ctx.getSource().getLevel();
        long aggregate = SpellCloudManager.aggregateHash(level);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[spell] 聚合 hash=%016x 云数=%d 粒子=%d",
                aggregate, SpellCloudManager.cloudCount(level), SpellCloudManager.particleCount(level))), false);
        for (SpellCloud cloud : SpellCloudManager.clouds(level))
        {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "[spell] #%d steps=%d hash=%016x",
                    cloud.id, cloud.ageSteps(), cloud.stateHash())), false);
        }
        return 1;
    }
}
