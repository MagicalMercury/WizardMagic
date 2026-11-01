package com.wizardmagic.api.crucible;

/**
 * 能力接口：能够提供坩埚状态的对象。
 *
 * <p>内容模组让方块实体（或任何持有状态的对象）实现本接口；渲染器用
 * {@code instanceof CrucibleStateSource} 探测，因此核心代码与渲染器都不需要知道
 * 具体方块实体类型。</p>
 *
 * <p><b>调用频率</b>：每帧最多一次。渲染方应把返回值交给
 * {@link CrucibleRenderCache}，由缓存决定是否真的重新计算液面与颜色。</p>
 */
public interface CrucibleStateSource {

    /**
     * 当前状态快照。
     *
     * <p>实现方可以返回复用的不可变实例以减少分配；调用方不得修改返回对象。</p>
     */
    CrucibleState crucibleState();
}
