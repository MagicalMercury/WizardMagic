/**
 * 坩埚状态 API：核心模组提供给内容模组与客户端渲染器的只读契约。
 *
 * <h2>职责边界</h2>
 *
 * <p><b>核心模组（本包）负责</b></p>
 * <ul>
 *   <li>只读数据契约 {@link com.wizardmagic.api.crucible.CrucibleState}
 *       与能力接口 {@link com.wizardmagic.api.crucible.CrucibleStateSource}；</li>
 *   <li>默认液面高度策略 {@link com.wizardmagic.api.crucible.FluidSurfaceStrategies}
 *       与颜色回退策略 {@link com.wizardmagic.api.crucible.FluidColorStrategies}；</li>
 *   <li>状态 → 渲染参数的解析与缓存
 *       （{@link com.wizardmagic.api.crucible.CrucibleRenderResolver}、
 *        {@link com.wizardmagic.api.crucible.CrucibleRenderCache}）；</li>
 *   <li>时间 → 相位的换算 {@link com.wizardmagic.api.crucible.CrucibleAnimation}。</li>
 * </ul>
 *
 * <p><b>内容模组负责</b></p>
 * <ul>
 *   <li>方块、方块实体、方块实体渲染器的注册；</li>
 *   <li>让方块实体实现 {@code CrucibleStateSource}，按自己的数据回答状态；</li>
 *   <li>液体颜色的真实来源（{@code FluidColorStrategy} 实现）；</li>
 *   <li>可选的液面策略覆写（例如横截面积随高度变化的容器）；</li>
 *   <li>模型 JSON、纹理、{@code blockstate} 变体，以及内腔几何
 *       （{@code cavityBottom} / {@code cavityHeight}）的实际数值。</li>
 * </ul>
 *
 * <p><b>渲染器负责</b></p>
 * <ul>
 *   <li>每帧一次 {@code cache.update(...)}，拿 {@code CrucibleRenderState}；</li>
 *   <li>按 {@code empty} 跳过液面、按 {@code surfaceHeight} 缩放液柱、按
 *       {@code colorArgb} 着色；</li>
 *   <li>按 {@code running} 开关呼吸动画，曲线与帧率节奏自己定，
 *       时间由 {@code CrucibleAnimation.phase(...)} 换算。</li>
 * </ul>
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
