package com.wizardmagic.api.spell;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置形状 + 注册表。
 * <p>所有实现都是纯函数：同一 {@code (seed, index, count, params)} 逐位复现。
 * 采样允许用 {@code SpellMath.sin/cos}（只在生成时调用一次，误差 ~1e-5，远小于
 * 状态 hash 的 1/4096 格量化）；六定律本身仍然只做四则运算与开方。</p>
 *
 * <p>新增形状：实现 {@link SpellShape} 并调用 {@link #register(SpellShape)}；
 * 脚本与命令按 id 引用，不需要改协议。</p>
 */
public final class Shapes
{
    private static final Map<String, SpellShape> BY_ID = new LinkedHashMap<>();

    /** 均匀球内的点云（默认形状） */
    public static final SpellShape POINT = register(new PointShape());
    /** 线段（沿 X） */
    public static final SpellShape LINE = register(new LineShape());
    /** 实心圆盘（XZ 平面） */
    public static final SpellShape DISC = register(new DiscShape());
    /** 圆环带（XZ 平面，可给厚度） */
    public static final SpellShape RING = register(new RingShape());
    /** 正多边形轮廓 */
    public static final SpellShape POLYGON = register(new PolygonShape());
    /** 实心球 */
    public static final SpellShape SPHERE = register(new SphereShape());
    /** 球壳（可给厚度） */
    public static final SpellShape SHELL = register(new ShellShape());
    /** 圆锥（底面在 y=0，顶点在 y=height） */
    public static final SpellShape CONE = register(new ConeShape());
    /** 螺旋带（龙卷风：空心壳 + 上升） */
    public static final SpellShape HELIX = register(new HelixShape());
    /** 正弦曲线（沿 X，y 方向波动） */
    public static final SpellShape SINE = register(new SineShape());
    /** 余弦曲线（沿 X，y 方向波动，相位差 π/2） */
    public static final SpellShape COSINE = register(new CosineShape());

    private Shapes()
    {
    }

    public static SpellShape register(SpellShape shape)
    {
        BY_ID.put(shape.id(), shape);
        return shape;
    }

    /** 按 id 查形状；未知返回 {@link #POINT}（不抛异常，避免网络包把客户端打崩） */
    public static SpellShape byId(String id)
    {
        return id == null ? POINT : BY_ID.getOrDefault(id, POINT);
    }

    public static boolean exists(String id)
    {
        return id != null && BY_ID.containsKey(id);
    }

    public static String[] ids()
    {
        return BY_ID.keySet().toArray(new String[0]);
    }

    public static int count()
    {
        return BY_ID.size();
    }

    // ------------------------------------------------------------ 采样工具

    /** 与 {@code core.spell.SpellRandom} 相同的 splitmix64，保证两侧抖动一致 */
    private static double unit(long seed, int index, int stream)
    {
        long h = seed;
        h ^= (long) index * 0x9E3779B97F4A7C15L;
        h ^= (long) stream * 0xC2B2AE3D27D4EB4FL;
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 均匀球面方向（拒绝采样，无三角函数） */
    private static void sphereDir(long seed, int index, int stream, double[] out)
    {
        for (int attempt = 0; attempt < 8; attempt++)
        {
            double a = 2.0 * unit(seed, index, stream + attempt * 3) - 1.0;
            double b = 2.0 * unit(seed, index, stream + attempt * 3 + 1) - 1.0;
            double s = a * a + b * b;
            if (s >= 1.0 || s < 1.0E-9)
            {
                continue;
            }
            double f = 2.0 * Math.sqrt(1.0 - s);
            out[0] = a * f;
            out[1] = b * f;
            out[2] = 1.0 - 2.0 * s;
            return;
        }
        out[0] = 0.0;
        out[1] = 1.0;
        out[2] = 0.0;
    }

    /** 圆盘内均匀取点（拒绝采样），写入 out[0..1] */
    private static void discPoint(long seed, int index, int stream, double[] out)
    {
        for (int attempt = 0; attempt < 8; attempt++)
        {
            double a = 2.0 * unit(seed, index, stream + attempt * 2) - 1.0;
            double b = 2.0 * unit(seed, index, stream + attempt * 2 + 1) - 1.0;
            if (a * a + b * b <= 1.0)
            {
                out[0] = a;
                out[1] = b;
                return;
            }
        }
        out[0] = 0.0;
        out[1] = 0.0;
    }

    private static double golden(int index)
    {
        double g = index * 0.6180339887498949;
        return g - Math.floor(g);
    }

    // ------------------------------------------------------------ 内置形状

    private static final class PointShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "point";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(0.6, 0.0, 0.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            sphereDir(seed, index, 0, out);
            double r = p.radius() * Math.sqrt(unit(seed, index, 10));
            out[0] *= r;
            out[1] *= r;
            out[2] *= r;
        }
    }

    private static final class LineShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "line";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(0.0, 0.1, 0.0, 0.0, 6, 4.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            double t = (index + 0.5) / count;
            out[0] = (t - 0.5) * p.length();
            out[1] = (unit(seed, index, 2) - 0.5) * p.thickness();
            out[2] = (unit(seed, index, 3) - 0.5) * p.thickness();
        }
    }

    private static final class DiscShape implements SpellShape
    {
        private final double[] tmp = new double[3];

        @Override
        public String id()
        {
            return "disc";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.0, 0.2, 0.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            discPoint(seed, index, 0, tmp);
            out[0] = tmp[0] * p.radius();
            out[1] = (unit(seed, index, 5) - 0.5) * p.thickness();
            out[2] = tmp[1] * p.radius();
        }
    }

    private static final class RingShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "ring";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.0, 0.4, 0.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            double angle = (golden(index) + 0.02 * (unit(seed, index, 1) - 0.5)) * Math.PI * 2.0;
            double r = p.radius() + (unit(seed, index, 2) - 0.5) * p.thickness();
            out[0] = SpellMath.cos(angle) * r;
            out[1] = (unit(seed, index, 3) - 0.5) * p.thickness() * 0.5;
            out[2] = SpellMath.sin(angle) * r;
        }
    }

    private static final class PolygonShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "polygon";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.0, 0.1, 0.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            int sides = p.sides();
            double s = (index + 0.5) / count * sides;
            int k = (int) Math.floor(s);
            double f = s - k;
            double a0 = Math.PI * 2.0 * k / sides;
            double a1 = Math.PI * 2.0 * (k + 1) / sides;
            double x0 = SpellMath.cos(a0);
            double z0 = SpellMath.sin(a0);
            double x1 = SpellMath.cos(a1);
            double z1 = SpellMath.sin(a1);
            double x = x0 + (x1 - x0) * f;
            double z = z0 + (z1 - z0) * f;
            double scale = p.radius() * (1.0 + (unit(seed, index, 1) - 0.5) * p.thickness() / Math.max(0.01, p.radius()));
            out[0] = x * scale;
            out[1] = (unit(seed, index, 2) - 0.5) * p.thickness();
            out[2] = z * scale;
        }
    }

    private static final class SphereShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "sphere";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.0, 0.0, 0.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            sphereDir(seed, index, 0, out);
            double r = p.radius() * StrictMath.cbrt(unit(seed, index, 10));
            out[0] *= r;
            out[1] *= r;
            out[2] *= r;
        }
    }

    private static final class ShellShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "shell";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.0, 0.3, 0.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            sphereDir(seed, index, 0, out);
            double r = p.radius() + (unit(seed, index, 10) - 0.5) * p.thickness();
            out[0] *= r;
            out[1] *= r;
            out[2] *= r;
        }
    }

    private static final class ConeShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "cone";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.0, 0.0, 4.0, 0.0, 6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            // 底面密、顶点稀（h = sqrt(u)），角度用黄金比均匀散开
            double h = Math.sqrt(unit(seed, index, 1));
            double angle = (golden(index) + 0.02 * (unit(seed, index, 2) - 0.5)) * Math.PI * 2.0;
            double r = p.radius() * (1.0 - h) * Math.sqrt(unit(seed, index, 3));
            out[0] = SpellMath.cos(angle) * r;
            out[1] = h * p.height();
            out[2] = SpellMath.sin(angle) * r;
        }
    }

    private static final class HelixShape implements SpellShape
    {
        /** 轴底部半径占比固定为 0：轴在原点处不摆（火源是锚点） */
        private static final double AXIS_BASE = 0.0;
        /** 轴半径随高度的幂指数 q */
        private static final double AXIS_POW = 1.6;
        /** 底部半径占顶部半径比例（R_b = 0.02·R_t：底部尽量小） */
        private static final double RADIUS_BASE_RATIO = 0.02;

        @Override
        public String id()
        {
            return "helix";
        }

        /**
         * 参数映射（对应参数化方程）：
         * radius=R_t、thickness=环带宽度、height=H、turns=ω（轴角速度 rad/s）
         * length=A_t（顶部轴半径）、amplitude=p（半径幂指数）、frequency=k_y（全高波数）
         * radiusPulse=ε_r、radiusWave=k_r、radiusSpeed=ω_r（半径脉动）
         */
        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(2.6, 0.35, 8.0, 1.2, 6, 1.2, 2.2, 1.0, 0.18, 2.0, 2.5);
        }

        @Override
        public boolean animated()
        {
            return true;
        }

        @Override
        public double targetRadius(int index, int count, ShapeParams p, long seed)
        {
            return targetRadiusAt(index, count, p, seed, 0.0);
        }

        /**
         * R(y,t) = R0(y)·[1 + ε_r·sin(k_r·y − ω_r·t + φ_r)]，其中
         * R0(y) = R_b + (R_t−R_b)(y/H)^p。
         *
         * <p>脉动是"目标半径"随时间变化，六定律的径向弹簧去追它——不是直接把粒子摆过去。</p>
         */
        @Override
        public void prepare(int count, ShapeParams p, long seed, double[] out)
        {
            double amp = p.amplitude();
            for (int i = 0; i < count; i++)
            {
                double t = (i + 0.5) / count;
                out[i * 2] = StrictMath.pow(t, amp);
                out[i * 2 + 1] = StrictMath.pow(t, AXIS_POW);
            }
        }

        @Override
        public double targetRadiusAt(int index, int count, ShapeParams p, long seed, double timeSeconds)
        {
            double t = (index + 0.5) / count;
            double r0 = p.radius() * (RADIUS_BASE_RATIO
                    + (1.0 - RADIUS_BASE_RATIO) * StrictMath.pow(t, p.amplitude()));
            if (p.radiusPulse() <= 0.0)
            {
                return r0;
            }
            double y = t * p.height();
            double kr = Math.PI * 2.0 * p.radiusWave() / Math.max(0.01, p.height());
            double phase = kr * y - p.radiusSpeed() * timeSeconds + Math.PI * 2.0 * unit(seed, 0, 9);
            double r = r0 * (1.0 + p.radiusPulse() * SpellMath.sin(phase));
            return Math.max(0.02, r);
        }

        @Override
        public double targetRadiusAt(int index, int count, ShapeParams p, long seed,
                                     double timeSeconds, double[] prep)
        {
            double t = (index + 0.5) / count;
            double r0 = p.radius() * (RADIUS_BASE_RATIO
                    + (1.0 - RADIUS_BASE_RATIO) * prep[index * 2]);
            if (p.radiusPulse() <= 0.0)
            {
                return r0;
            }
            double y = t * p.height();
            double kr = Math.PI * 2.0 * p.radiusWave() / Math.max(0.01, p.height());
            double phase = kr * y - p.radiusSpeed() * timeSeconds + Math.PI * 2.0 * unit(seed, 0, 9);
            double r = r0 * (1.0 + p.radiusPulse() * SpellMath.sin(phase));
            return Math.max(0.02, r);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            double t = (index + 0.5) / count;
            centerAt(index, count, p, seed, 0.0, out);
            double r0 = targetRadiusAt(index, count, p, seed, 0.0);
            double r = r0 + (unit(seed, index, 2) - 0.5) * p.thickness();
            if (r < 0.02)
            {
                r = 0.02;
            }
            // 每个高度整圈均匀（黄金比），turns 现在是时间参数，不再做静态扭转
            double theta = golden(index) * Math.PI * 2.0;
            out[0] += SpellMath.cos(theta) * r;
            out[1] = t * p.height();
            out[2] += SpellMath.sin(theta) * r;
        }

        @Override
        public void center(int index, int count, ShapeParams p, long seed, double[] out)
        {
            centerAt(index, count, p, seed, 0.0, out);
        }

        /**
         * 时刻 t 的轴心：A(y)·(cos(k_y·y − ω t + φ0), sin(...))。
         *
         * <p>只让"轴"随时间转，粒子仍由六定律驱动，不推翻速度积分。</p>
         */
        @Override
        public void centerAt(int index, int count, ShapeParams p, long seed,
                             double timeSeconds, double[] out)
        {
            double t = (index + 0.5) / count;
            double y = t * p.height();
            // A(y) = A_b + (A_t - A_b)·(y/H)^q
            double axisRadius = AXIS_BASE + (p.length() - AXIS_BASE) * StrictMath.pow(t, AXIS_POW);
            // k_y 按"全高多少个波"给定，φ0 由 seed 决定
            double ky = Math.PI * 2.0 * p.frequency() / Math.max(0.01, p.height());
            double phase = ky * y - p.turns() * timeSeconds + Math.PI * 2.0 * unit(seed, 0, 7);
            out[0] = axisRadius * SpellMath.cos(phase);
            out[1] = y;
            out[2] = axisRadius * SpellMath.sin(phase);
        }

        @Override
        public void centerAt(int index, int count, ShapeParams p, long seed,
                             double timeSeconds, double[] prep, double[] out)
        {
            double t = (index + 0.5) / count;
            double y = t * p.height();
            double axisRadius = AXIS_BASE + (p.length() - AXIS_BASE) * prep[index * 2 + 1];
            double ky = Math.PI * 2.0 * p.frequency() / Math.max(0.01, p.height());
            double phase = ky * y - p.turns() * timeSeconds + Math.PI * 2.0 * unit(seed, 0, 7);
            out[0] = axisRadius * SpellMath.cos(phase);
            out[1] = y;
            out[2] = axisRadius * SpellMath.sin(phase);
        }
    }

    private static final class SineShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "sine";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(0.0, 0.2, 0.0, 0.0, 6, 6.0, 1.5, 1.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            double t = (index + 0.5) / count;
            out[0] = (t - 0.5) * p.length();
            out[1] = p.amplitude() * SpellMath.sin(t * p.frequency() * Math.PI * 2.0)
                    + (unit(seed, index, 1) - 0.5) * p.thickness();
            out[2] = (unit(seed, index, 2) - 0.5) * p.thickness();
        }
    }

    private static final class CosineShape implements SpellShape
    {
        @Override
        public String id()
        {
            return "cosine";
        }

        @Override
        public ShapeParams defaults()
        {
            return new ShapeParams(0.0, 0.2, 0.0, 0.0, 6, 6.0, 1.5, 1.0, 0.0, 0.0, 0.0);
        }

        @Override
        public void sample(int index, int count, ShapeParams p, long seed, double[] out)
        {
            double t = (index + 0.5) / count;
            out[0] = (t - 0.5) * p.length();
            out[1] = p.amplitude() * SpellMath.cos(t * p.frequency() * Math.PI * 2.0)
                    + (unit(seed, index, 1) - 0.5) * p.thickness();
            out[2] = (unit(seed, index, 2) - 0.5) * p.thickness();
        }
    }
}
