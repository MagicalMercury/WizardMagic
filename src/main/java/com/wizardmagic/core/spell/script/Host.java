package com.wizardmagic.core.spell.script;

/**
 * 世界查询接口。只允许在 {@code on cast} 里调用。
 *
 * <p>纯接口是为了让语言层不 import Minecraft：离线自检给一个全零实现，
 * 游戏里由 {@code common.spell.McHost} 接到真实的 {@code ServerLevel}。</p>
 */
public interface Host
{
    /** 某坐标所在 section 的元素浓度（0..1）；非基础元素暂返回 0 */
    double field(int elementId, double x, double y, double z);

    /** 光照 0..1 */
    double light(double x, double y, double z);

    /** 0 晴 / 1 雨 / 2 雷 */
    int weather();

    /** 当天时间 0..23999 */
    long dayTime();

    /** 施法原点（{@code here()}） */
    double hereX();

    double hereY();

    double hereZ();

    /**
     * 施法朝向（{@code look()}，已归一化）。
     *
     * <p>没有它就只能写"在某个点炸开"的法术——投射物（火球飞出去再炸）需要一个方向。
     * 和 {@code here()} 一样只在 {@code on cast} 可用：{@code tick} 里读它没有意义
     * （两端朝向未必一致），所以编译期就拒。</p>
     */
    double lookX();

    double lookY();

    double lookZ();
}
