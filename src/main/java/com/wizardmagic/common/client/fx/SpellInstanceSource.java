package com.wizardmagic.common.client.fx;

/**
 * 法术粒子的实例源：{@link SparkBatch} 每帧重建实例表时**最先**调用它，
 * 因此法术粒子优先占用 {@code MAX_DRAW} 名额，burst 与元素光点排在其后。
 *
 * <p>实例布局必须与 {@code writeInstance} 完全一致（11 float / 44 字节）：
 * {@code x, y, z, prevX, prevY, prevZ, 颜色(4 字节打包), size, birth, life, phase}。</p>
 */
public interface SpellInstanceSource
{
    /**
     * 填充实例数据，从 {@code data[0]} 开始写。
     *
     * @param data 目标数组（长度 = 池容量 × 11）
     * @param maxInstances 本次最多写入多少实例
     * @return 实际写入的实例数，必须 ≤ {@code maxInstances}
     */
    int fill(float[] data, int maxInstances);

    /**
     * 是否还有法术实例。
     *
     * <p>{@link SparkBatch#render} 在"一个光点都没有"时会整体跳过；可法术粒子有可能
     * **这一帧才第一次产出**实例（{@code spellCount} 还是 0），所以不能只看计数。</p>
     */
    default boolean wantsDraw()
    {
        return false;
    }

    /**
     * 每帧调用一次（在 {@link SparkBatch#render} 重建判定之前）：推进"播放时间轴"。
     *
     * <p>只有同进程直通（单人 / 局域网主机的这台机器）会用到：那时快照的到达时刻是抖的
     * （实测 80–130 ms），但每一段代表**恒定 100 ms 的仿真时间**。播放时钟按服务端时间轴
     * 均匀走，抖动全部吸收在播放延迟里。</p>
     *
     * @return true = 选中的快照段变了，实例表需要立即重建（只在段边界发生，约 10 Hz）
     */
    default boolean frameTick()
    {
        return false;
    }

    /**
     * 法术区间本帧的 {@code uPartial}。
     *
     * <p>默认原样返回 {@code partialTick}（镜像模式：相位按 tick 走）；同进程直通时
     * 由 {@link com.wizardmagic.common.client.spell.SpellCloudClient} 覆盖成"播放时钟的段内相位"。</p>
     */
    default float spellPartial(float partialTick)
    {
        return partialTick;
    }
}
