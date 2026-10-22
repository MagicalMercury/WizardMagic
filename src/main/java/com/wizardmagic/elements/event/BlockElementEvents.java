package com.wizardmagic.elements.event;

import com.wizardmagic.Config;
import com.wizardmagic.WizardMagic;
import com.wizardmagic.elements.ElementAccess;
import com.wizardmagic.elements.ElementFieldStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 方块变化 → 局部重算 base。
 *
 * <p>监听 {@link BlockEvent.NeighborNotifyEvent}：放置、破坏、熔炉点燃/熄灭、
 * 水/岩浆流动、红石都会触发。**不削减事件范围**（后续大量玩法要依赖方块更新），
 * 只过滤掉没有意义的工作：</p>
 * <ul>
 *   <li>元素场还没生成的 chunk（世界生成阶段的海量 setBlock）直接丢弃；</li>
 *   <li>超出玩家视野的 chunk 不实时刷新（等玩家靠近或 regenerate）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID)
public final class BlockElementEvents
{
    /** 只刷新玩家周围的 chunk（单位：chunk），超过就交给 /element regenerate 兜底 */
    private static final int REFRESH_RADIUS_CHUNKS = 8;

    private BlockElementEvents() {
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!Config.REFRESH_ON_BLOCK_CHANGE.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || level.isClientSide) {
            return;
        }

        BlockPos pos = event.getPos();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        ElementFieldStorage storage = ElementAccess.storage(chunk);
        if (storage == null || !storage.isGenerated()) {
            // 世界生成阶段：元素场随后会整体生成，这些更新没有可刷新的对象
            return;
        }
        if (!isNearPlayer(level, pos)) {
            return;
        }
        ElementRefreshQueue.enqueueRefresh(level, pos);
    }

    private static boolean isNearPlayer(ServerLevel level, BlockPos pos) {
        if (level.players().isEmpty()) {
            return false;
        }
        double limit = (double) REFRESH_RADIUS_CHUNKS * 16;
        double limitSq = limit * limit;
        for (var player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= limitSq) {
                return true;
            }
        }
        return false;
    }
}
