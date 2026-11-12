package com.wizardmagic.api.spell;

/**
 * 形状参数：所有内置形状共用同一组字段，按形状各取所需。
 *
 * <p>这样网络包与脚本只需要一套固定 schema，加新形状不用改协议。</p>
 *
 * <table border="1">
 *   <caption>字段用途</caption>
 *   <tr><th>字段</th><th>含义</th><th>用到的形状</th></tr>
 *   <tr><td>{@code radius}</td><td>主半径（helix 为顶部半径 R_t）</td><td>point/disc/ring/polygon/sphere/shell/cone/helix</td></tr>
 *   <tr><td>{@code thickness}</td><td>带宽/管径/抖动幅度</td><td>ring/shell/helix/line/sine/cosine</td></tr>
 *   <tr><td>{@code height}</td><td>竖直高度</td><td>cone/helix</td></tr>
 *   <tr><td>{@code turns}</td><td>helix 的轴角速度 ω（rad/s）</td><td>helix</td></tr>
 *   <tr><td>{@code sides}</td><td>多边形边数（≥3）</td><td>polygon</td></tr>
 *   <tr><td>{@code length}</td><td>水平长度 / helix 的顶部轴半径 A_t</td><td>line/sine/cosine/helix</td></tr>
 *   <tr><td>{@code amplitude}</td><td>波幅 / helix 的半径幂指数 p</td><td>sine/cosine/helix</td></tr>
 *   <tr><td>{@code frequency}</td><td>波数 / helix 的轴波数 k_y</td><td>sine/cosine/helix</td></tr>
 *   <tr><td>{@code radiusPulse}</td><td>半径脉动幅度 ε_r（0 = 不脉动）</td><td>helix</td></tr>
 *   <tr><td>{@code radiusWave}</td><td>半径脉动沿高度的波数 k_r</td><td>helix</td></tr>
 *   <tr><td>{@code radiusSpeed}</td><td>半径脉动角速度 ω_r（rad/s）</td><td>helix</td></tr>
 * </table>
 *
 * <p>helix 的半径公式：`R(y,t) = R0(y)·[1 + ε_r·sin(k_r·y − ω_r·t + φ_r)]`，
 * `R0(y) = R_b + (R_t − R_b)(y/H)^p`。</p>
 */
public record ShapeParams(double radius, double thickness, double height, double turns,
                          int sides, double length, double amplitude, double frequency,
                          double radiusPulse, double radiusWave, double radiusSpeed)
{
    /** 通用缺省值（未提供时使用） */
    public static final ShapeParams DEFAULT = new ShapeParams(
            2.0, 0.5, 4.0, 1.0, 6, 4.0, 1.0, 1.0, 0.0, 0.0, 0.0);

    public ShapeParams
    {
        radius = Math.max(0.0, radius);
        thickness = Math.max(0.0, thickness);
        height = Math.max(0.0, height);
        sides = Math.max(3, Math.min(64, sides));
        length = Math.max(0.0, length);
        frequency = Math.max(0.0, frequency);
        turns = Math.max(0.0, turns);
        radiusPulse = Math.max(0.0, Math.min(1.0, radiusPulse));
        radiusWave = Math.max(0.0, radiusWave);
        radiusSpeed = Math.max(0.0, radiusSpeed);
    }

    /** 只改半径（命令里最常用的快捷参数） */
    public ShapeParams withRadius(double value)
    {
        return new ShapeParams(value, thickness, height, turns, sides, length, amplitude,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withThickness(double value)
    {
        return new ShapeParams(radius, value, height, turns, sides, length, amplitude,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withHeight(double value)
    {
        return new ShapeParams(radius, thickness, value, turns, sides, length, amplitude,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withTurns(double value)
    {
        return new ShapeParams(radius, thickness, height, value, sides, length, amplitude,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withSides(int value)
    {
        return new ShapeParams(radius, thickness, height, turns, value, length, amplitude,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withLength(double value)
    {
        return new ShapeParams(radius, thickness, height, turns, sides, value, amplitude,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withAmplitude(double value)
    {
        return new ShapeParams(radius, thickness, height, turns, sides, length, value,
                frequency, radiusPulse, radiusWave, radiusSpeed);
    }

    public ShapeParams withFrequency(double value)
    {
        return new ShapeParams(radius, thickness, height, turns, sides, length, amplitude,
                value, radiusPulse, radiusWave, radiusSpeed);
    }
}
