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
}
