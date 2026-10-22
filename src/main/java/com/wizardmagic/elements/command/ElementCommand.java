package com.wizardmagic.elements.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementField;
import com.wizardmagic.elements.ElementFieldStorage;
import com.wizardmagic.elements.ElementTypes;
import com.wizardmagic.elements.event.ChunkElementEvents;
import com.wizardmagic.elements.event.ElementRefreshQueue;
import com.wizardmagic.elements.flow.ElementFlowScheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * 调试命令：
 * <pre>
 * /element get   &lt;pos&gt;
 * /element set   &lt;pos&gt; &lt;earth|water|wind|fire&gt; &lt;0-127&gt;
 * /element stats
 * </pre>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class ElementCommand
{
    private ElementCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("element")
                .then(Commands.literal("get")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ElementCommand::get)))
                .then(Commands.literal("set")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, ElementTypes.MAX_VALUE))
                                                .executes(ElementCommand::set)))))
                .then(Commands.literal("stats")
                        .executes(ElementCommand::stats))
                .then(Commands.literal("disturb")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 16))
                                        .then(Commands.argument("delta", IntegerArgumentType.integer(-127, 127))
                                                .executes(ElementCommand::disturb)))))
                .then(Commands.literal("regenerate")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 16))
                                .executes(ElementCommand::regenerate))));
    }

    private static int get(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        ServerLevel level = context.getSource().getLevel();

        ElementFieldStorage storage = ElementAccess.storage(level, pos);
        if (storage == null) {
            context.getSource().sendFailure(Component.literal("该位置未加载或未挂载元素场"));
            return 0;
        }

        int[] values = new int[ElementTypes.COUNT];
        if (!ElementAccess.getAll(level, pos, values)) {
            context.getSource().sendFailure(Component.literal("该位置没有元素场数据（section 未生成）"));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "%s -> Earth=%d Water=%d Wind=%d Fire=%d",
                pos.toShortString(), values[0], values[1], values[2], values[3])), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        ServerLevel level = context.getSource().getLevel();
        String typeName = StringArgumentType.getString(context, "type");
        int value = IntegerArgumentType.getInteger(context, "value");

        ElementTypes type = parseType(typeName);
        if (type == null) {
            context.getSource().sendFailure(Component.literal("未知元素：" + typeName + "（可用 earth/water/wind/fire）"));
            return 0;
        }

        if (!ElementAccess.set(level, pos, type, value)) {
            context.getSource().sendFailure(Component.literal("写入失败：该位置没有元素场数据"));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "%s 的 %s 已设为 %d", pos.toShortString(), type.name(), value)), false);
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        int active = ElementFlowScheduler.INSTANCE.activeCount();
        int queued = ChunkElementEvents.queuedChunks();
        int pendingRefresh = ElementRefreshQueue.pending();
        long enqueuedTotal = ElementRefreshQueue.totalEnqueued();
        long processedTotal = ElementRefreshQueue.totalProcessed();
        int hotCount = ElementFlowScheduler.INSTANCE.hotCount();
        double[] timing = com.wizardmagic.elements.generation.ElementGeneration.averageTiming();
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "元素场：活跃=%d(hot %d) 待生成=%d 待重算=%d（入队 %d / 重算 %d）| 分段 ms: noise=%.3f conv=%.3f interp=%.3f hot=%.3f",
                active, hotCount, queued, pendingRefresh, enqueuedTotal, processedTotal,
                timing[0], timing[1], timing[2], timing[3])), false);
        return 1;
    }

    /** 强制重算玩家周围 radius 个 chunk 的所有 section（重置 current = base） */
    private static int regenerate(CommandContext<CommandSourceStack> context) {
        int radius = IntegerArgumentType.getInteger(context, "radius");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        int queued = ElementRefreshQueue.enqueueRegenerate(level, new ChunkPos(center), radius);
        int finalQueued = queued;
        source.sendSuccess(() -> Component.literal(String.format(
                "已排队重算 %d 个 section（半径 %d chunk），按 tick 预算逐步执行",
                finalQueued, radius)), false);
        return 1;
    }

    /**
     * 对一片球形区域施加元素扰动（四元素同加 delta），用于观察扩散/回流。
     * 静止态（current == base）本身不会产生任何变化，必须扰动才会动。
     */
    private static int disturb(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos center = BlockPosArgument.getLoadedBlockPos(context, "pos");
        int radius = IntegerArgumentType.getInteger(context, "radius");
        int delta = IntegerArgumentType.getInteger(context, "delta");
        ServerLevel level = context.getSource().getLevel();

        int touched = 0;
        int radiusSq = radius * radius;
        Set<ChunkPos> touchedChunks = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    ElementField field = ElementAccess.field(level, pos);
                    if (field == null) {
                        continue;
                    }
                    int index = ElementField.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    for (ElementTypes type : ElementTypes.values()) {
                        field.add(type, index, delta);
                    }
                    touchedChunks.add(new ChunkPos(pos));
                    touched++;
                }
            }
        }

        ElementFlowScheduler.INSTANCE.addAround(level, new ChunkPos(center), Math.max(1, (radius >> 4) + 1));
        for (ChunkPos chunkPos : touchedChunks) {
            ElementFlowScheduler.INSTANCE.markHot(level, chunkPos);
        }

        int finalTouched = touched;
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "已对 %d 个方块施加 %+d 扰动（四元素同加），区域已加入活跃集合", finalTouched, delta)), false);
        return 1;
    }

    private static ElementTypes parseType(String name) {
        for (ElementTypes type : ElementTypes.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
