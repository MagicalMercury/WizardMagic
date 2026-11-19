package com.wizardmagic.core.spell;

/**
 * 同进程权威状态直通：把「服务端那朵云」的粒子状态直接暴露给同 JVM 的读取方。
 *
 * <p><b>为什么需要它</b>：客户端镜像本来要**自己再跑一遍 VM** 才能画。单人游戏里服务端和
 * 客户端在同一个 JVM，45 万粒子每步十几 MB —— 发包不可能（带宽 + 序列化都不现实），
 * 但也没必要：数组就在同一块堆里。所以只开一个读口子，客户端把物理整段省掉。</p>
 *
 * <p><b>读约定（seqlock）</b>：{@link #stepEpoch()} 每完成一步才 +1（volatile 写，带
 * release 语义，所以「看到新 epoch」蕴含「看到这一步的全部粒子写入」）。读取方必须这么用：</p>
 *
 * <pre>
 *   int e1 = src.stepEpoch();
 *   ... 读 rawX() / rawY() / rawZ() / rawEnergy() ...
 *   int e2 = src.stepEpoch();
 *   e1 == e2 → 这一份自始至终一致，可以用
 *   e1 != e2 → 期间权威方又走了一步（数组被改写过），这份要丢掉重读
 * </pre>
 *
 * <p><b>不要跨线程长期持有</b>返回的数组引用：只在 {@code e1 == e2} 那段窗口内保证一致。
 * 也不要写它们——那会破坏权威状态。</p>
 *
 * <p>多人游戏里没有权威云的本地实例，{@link SpellCloudManager#authoritative(int)} 返回
 * {@code null}，客户端自动退回「自己跑 VM」的镜像模式。</p>
 */
public interface SpellStateSource
{
    /** 权威方每完成一步 +1；只在步末写，读方据此做 seqlock 校验 */
    int stepEpoch();

    /**
     * 这一步**完成时刻**（{@link System#nanoTime()}）。
     *
     * <p>为什么不能只给 epoch：读方是低频率去"看"的（客户端 10 Hz 看一眼），看到 epoch 变了
     * 不等于知道这一步是什么时候完成的——观测时刻带着最多一个 tick 的抖动。有了这个时间戳，
     * 读方能拿到**精确的步时刻**，从而用它预测插值相位，而不是用"我什么时候读到"，
     * 后者会把自己的读数节奏混进运动里（那是"越修越抖"的根源）。</p>
     *
     * <p>与 {@link #stepEpoch()} 的先后：先写时间戳，再 `stepEpoch++`（volatile 写），
     * 所以"读到新 epoch"一定读得到这一步的时间戳。</p>
     */
    long stepNanos();

    /** 粒子数（= 各个 raw 数组的有效长度） */
    int count();

    double[] rawX();

    double[] rawY();

    double[] rawZ();

    /** 粒子能量（渲染用；0..100 的 float） */
    float[] rawEnergy();
}
