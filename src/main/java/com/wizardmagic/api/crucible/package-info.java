/**
 * 坩埚 API：核心模组提供给内容模组的只读契约、可复用基类与通用渲染器。
 *
 * <h2>快速接入（内容模组加一口新坩埚）</h2>
 *
 * <p><b>1. 继承方块基类</b></p>
 * <pre>{@code
 * public class AlchemyCrucibleBlock extends CrucibleBlock {
 *     public AlchemyCrucibleBlock() {
 *         super(Properties.of()
 *                 .mapColor(MapColor.COLOR_PURPLE)
 *                 .strength(2.5F, 6.0F)
 *                 .sound(SoundType.STONE));
 *     }
 *
 *     @Override
 *     public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
 *         return new AlchemyCrucibleBlockEntity(pos, state);
 *     }
 * }
 * }</pre>
 *
 * <p><b>2. 继承方块实体基类</b>（有真实储罐就覆写液量来源）</p>
 * <pre>{@code
 * public class AlchemyCrucibleBlockEntity extends CrucibleBlockEntity {
 *     public AlchemyCrucibleBlockEntity(BlockPos pos, BlockState state) {
 *         super(ModBlockEntities.ALCHEMY_CRUCIBLE.get(), pos, state, 16000);
 *     }
 *
 *     @Override
 *     public int fluidAmount() { return tank.getFluidAmount(); }
 * }
 * }</pre>
 *
 * <p><b>3. 注册通用渲染器</b></p>
 * <pre>{@code
 * event.registerBlockEntityRenderer(
 *         ModBlockEntities.ALCHEMY_CRUCIBLE.get(), CrucibleRenderer::new);
 * }</pre>
 *
 * <p><b>4. 资源三件套</b>：blockstate（active 两态即可，液面不需要模型）、
 * 模型 JSON（{@code "parent": "wizardmagic:block/crucible/crucible"} + 覆盖贴图）、
 * 一张 128×128 图集。液面贴图全模组共用 {@code wizardmagic:block/crucible_fluid}。</p>
 *
 * <h2>职责边界</h2>
 *
 * <p><b>核心模组（本包）负责</b></p>
 * <ul>
 *   <li>只读数据契约 {@link com.wizardmagic.api.crucible.CrucibleState}
 *       与能力接口 {@link com.wizardmagic.api.crucible.CrucibleStateSource}；</li>
 *   <li>可复用基类 {@link com.wizardmagic.api.crucible.CrucibleBlock}
 *       与 {@link com.wizardmagic.api.crucible.CrucibleBlockEntity}
 *       （属性、发光、颜色持久化、渲染缓存都在这里）；</li>
 *   <li>内腔几何常量 {@link com.wizardmagic.api.crucible.CrucibleGeometry}；</li>
 *   <li>默认液面高度策略 {@link com.wizardmagic.api.crucible.FluidSurfaceStrategies}
 *       与颜色回退策略 {@link com.wizardmagic.api.crucible.FluidColorStrategies}；</li>
 *   <li>状态 → 渲染参数的解析与缓存
 *       （{@link com.wizardmagic.api.crucible.CrucibleRenderResolver}、
 *        {@link com.wizardmagic.api.crucible.CrucibleRenderCache}）；</li>
 *   <li>时间 → 相位的换算 {@link com.wizardmagic.api.crucible.CrucibleAnimation}；</li>
 *   <li>通用渲染器 {@code com.wizardmagic.common.client.CrucibleRenderer}，
 *       按 {@code surfaceHeight} 实时绘制液柱。</li>
 * </ul>
 *
 * <p><b>内容模组负责</b></p>
 * <ul>
 *   <li>方块与方块实体的注册（继承上面两个基类即可）；</li>
 *   <li>液体的真实来源（覆写 {@code fluidAmount()} / {@code fluidCapacity()}，
 *       或提供 {@code FluidColorStrategy}）；</li>
 *   <li>可选的液面策略覆写（例如横截面积随高度变化的容器）；</li>
 *   <li>模型 JSON、纹理、{@code blockstate} 变体；换了内腔尺寸就覆写
 *       {@code cavityBottom()} / {@code cavityHeight()}。</li>
 * </ul>
 *
 * <h2>渲染管线</h2>
 *
 * <pre>
 * 每帧：renderCache.update(state, surface, color, cavityBottom, cavityHeight)
 *         ↓ 签名未变则直接返回上次结果，不调用任何策略
 *       CrucibleRenderState{empty, fillFraction, surfaceHeight, colorArgb, running}
 *         ↓
 *       empty → 跳过液面；否则按 surfaceHeight 画液柱，按 colorArgb 着色
 * 呼吸动画：CrucibleAnimation.phase(gameTime, partialTick, 32.0F)，曲线由渲染器自己定
 * </pre>
 *
 * <h2>调用频率</h2>
 *
 * <p>{@code CrucibleStateSource#crucibleState()}：每帧最多一次。
 * {@code FluidSurfaceStrategy} 与 {@code FluidColorStrategy}：仅在缓存签名变化时调用。
 * {@code CrucibleAnimation.phase(...)}：每帧一次，纯算术，无分配。</p>
 *
 * <h2>失效</h2>
 *
 * <p>缓存默认按「液量 / 容量 / 颜色 / 运行状态」四个签名自动判定；
 * 内容模组在写入这些数据时调用 {@code CrucibleRenderCache#invalidate()} 可以强制重算。</p>
 */
package com.wizardmagic.api.crucible;
