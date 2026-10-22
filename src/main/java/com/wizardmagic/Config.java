package com.wizardmagic;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;

/**
 * 模组配置。元素系统的可调参数都在这里。
 */
@Mod.EventBusSubscriber(modid = WizardMagic.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_FLOW = BUILDER
            .comment("是否启用元素扩散与回流")
            .define("elements.enableFlow", true);

    public static final ForgeConfigSpec.DoubleValue FLOW_ALPHA = BUILDER
            .comment("扩散系数。6 邻域的稳定上限是 1/6≈0.1667；0.12 已接近上限，扩散明显")
            .defineInRange("elements.alpha", 0.12, 0.0, 0.16);

    public static final ForgeConfigSpec.DoubleValue FLOW_BETA = BUILDER
            .comment("回流系数，决定被消耗后恢复速度（β=0.02 时半衰期约 35 tick）")
            .defineInRange("elements.beta", 0.02, 0.0, 1.0);

    public static final ForgeConfigSpec.IntValue FLOW_BUDGET = BUILDER
            .comment("每 tick 最多处理多少个活跃 chunk（按到玩家距离从近到远）")
            .defineInRange("elements.flowBudget", 32, 0, 1024);

    public static final ForgeConfigSpec.IntValue FLOW_INTERVAL = BUILDER
            .comment("每隔多少 tick 扫描一次普通活跃 chunk（被扰动的 chunk 每 tick 都扫描）")
            .defineInRange("elements.flowInterval", 1, 1, 40);

    public static final ForgeConfigSpec.IntValue FLOW_COOLDOWN = BUILDER
            .comment("同一 section 两次扩散之间的最小间隔（tick）。20 = 每 1 秒才抹平一步，避免瞬间被抹平")
            .defineInRange("elements.flowCooldown", 30, 1, 200);

    public static final ForgeConfigSpec.BooleanValue FLUX_MODE = BUILDER
            .comment("true=通量式扩散（像水一样有前沿，推荐）；false=Laplacian 平滑（会瞬间抹平）")
            .define("elements.fluxMode", true);

    public static final ForgeConfigSpec.DoubleValue FLUX_RATE = BUILDER
            .comment("通量式扩散的单次转移比例（0..1，越大越急）")
            .defineInRange("elements.fluxRate", 0.35, 0.0, 1.0);

    public static final ForgeConfigSpec.IntValue FLUX_LIMIT = BUILDER
            .comment("每方向每 tick 最多转移多少点（配合 flowCooldown，3 ≈ 一步抹平中心约 18%）")
            .defineInRange("elements.fluxLimit", 4, 1, 32);

    public static final ForgeConfigSpec.BooleanValue REFRESH_ON_BLOCK_CHANGE = BUILDER
            .comment("方块变化（放置/破坏/熔炉点燃/水流）时局部重算 base，保留玩家扰动")
            .define("elements.refreshOnBlockChange", true);

    public static final ForgeConfigSpec.IntValue REFRESH_BUDGET = BUILDER
            .comment("每 tick 最多重算多少个 section（方块刷新与 /element regenerate 共用；旧存档升级期间可临时调大）")
            .defineInRange("elements.refreshBudget", 4, 1, 64);

    public static final ForgeConfigSpec.IntValue GENERATION_SECTION_BUDGET = BUILDER
            .comment("每 tick 最多生成多少个 section 的元素场（硬上限，防止单 tick 失控）")
            .defineInRange("elements.generationSectionBudget", 16, 0, 64);

    public static final ForgeConfigSpec.DoubleValue GENERATION_TIME_BUDGET_MS = BUILDER
            .comment("每 tick 用于元素场生成的时间预算（毫秒）。方块刷新队列为空时自动 ×2")
            .defineInRange("elements.generationTimeBudgetMs", 2.0, 0.25, 10.0);

    public static final ForgeConfigSpec.IntValue GENERATION_RADIUS = BUILDER
            .comment("预生成玩家周围多少 chunk 的元素场（-1 = 所有加载 chunk 都预生成；更远处首次访问时惰性生成）")
            .defineInRange("elements.generationRadius", 5, -1, 32);

    public static final ForgeConfigSpec.IntValue ACTIVE_RADIUS = BUILDER
            .comment("玩家周围保持活跃的 chunk 半径（0 表示只算玩家所在 chunk）")
            .defineInRange("elements.activeRadius", 2, 0, 16);

    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
