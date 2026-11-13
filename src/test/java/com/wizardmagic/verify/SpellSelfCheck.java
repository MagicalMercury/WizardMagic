package com.wizardmagic.verify;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.SpellShape;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.core.spell.Laws;
import com.wizardmagic.core.spell.SpellCloud;
import com.wizardmagic.core.spell.SpellConstants;
import com.wizardmagic.core.spell.SpellElements;
import com.wizardmagic.core.spell.SpellNoise;
import com.wizardmagic.core.spell.SpellRandom;

/**
 * 离机自检：不启动 Minecraft，验证法术粒子层的确定性与物理方向。
 *
 * <p>运行方式见 CHANGE.md：javac + java，classpath 带上 gson 与
 * {@code src/main/resources}（元素表 JSON）。</p>
 */
public final class SpellSelfCheck
{
    private static int checks;
    private static int failures;

    public static void main(String[] args)
    {
        elementTable();
        noise();
        random();
        determinism();
        physicsDirection();
        energyAndDeath();
        persistentStatic();
        shapes();
        cost(4096);
        costHelix();

        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures != 0)
        {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- 元素表

    private static void elementTable()
    {
        check("元素表 50 个", SpellElements.count() == 50);
        int fire = SpellElements.indexOf("fire");
        int earth = SpellElements.indexOf("earth");
        int air = SpellElements.indexOf("air");
        int water = SpellElements.indexOf("water");
        check("fire/earth/air/water 都能查到", fire >= 0 && earth >= 0 && air >= 0 && water >= 0);
        check("fire 上升（gravity<0）", SpellElements.gravity(fire) < 0.0);
        check("earth 下沉（gravity>0）", SpellElements.gravity(earth) > 0.0);
        check("air 扩散最快（volatility=1）", Math.abs(SpellElements.volatility(air) - 1.0) < 1.0E-9);
        check("未知元素返回 -1", SpellElements.indexOf("not_an_element") == -1);
        check("元素下标往返", SpellElements.indexOf(SpellElements.id(7)) == 7);
    }

    // --------------------------------------------------------------- 噪声

    private static void noise()
    {
        double a = SpellNoise.value3(42L, 1.5, 2.5, 3.5, 7);
        double b = SpellNoise.value3(42L, 1.5, 2.5, 3.5, 7);
        check("噪声可复现", a == b);
        check("噪声在 [-1,1]", a >= -1.0 && a <= 1.0);
        check("不同 step 不同值", SpellNoise.value3(42L, 1.5, 2.5, 3.5, 8) != a);
        check("不同 seed 不同值", SpellNoise.value3(43L, 1.5, 2.5, 3.5, 7) != a);
        check("噪声连续（近点差值小）",
                Math.abs(SpellNoise.value3(42L, 1.5, 2.5, 3.5, 7)
                        - SpellNoise.value3(42L, 1.5001, 2.5, 3.5, 7)) < 0.01);
    }

    // --------------------------------------------------------------- 随机

    private static void random()
    {
        double[] out = new double[3];
        SpellRandom.sphere(1234L, 5, 0, out);
        double len = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        check("球面方向是单位向量", Math.abs(len - 1.0) < 1.0E-9);
        check("unit 在 [0,1)", SpellRandom.unit(1L, 2, 3) >= 0.0 && SpellRandom.unit(1L, 2, 3) < 1.0);
        check("unit 可复现", SpellRandom.unit(9L, 9, 9) == SpellRandom.unit(9L, 9, 9));
    }

    // ------------------------------------------------------------ 确定性

    private static void determinism()
    {
        SpellCloud a = cloud(1, "fire", 256, 12345L);
        SpellCloud b = cloud(2, "fire", 256, 12345L);
        long h0a = a.stateHash();
        long h0b = b.stateHash();
        check("同 seed 初始状态一致", h0a == h0b);

        step(a, 30);
        step(b, 30);
        check("同 seed 30 步后仍一致", a.stateHash() == b.stateHash());
        check("30 步后状态已变化", a.stateHash() != h0a);

        SpellCloud c = cloud(3, "fire", 256, 999L);
        step(c, 30);
        check("不同 seed 结果不同", c.stateHash() != a.stateHash());

        boolean equal = true;
        for (int i = 0; i < a.count; i++)
        {
            if (a.x(i) != c.x(i) || a.y(i) != c.y(i) || a.z(i) != c.z(i))
            {
                equal = false;
                break;
            }
        }
        check("不同 seed 逐点不同", !equal);
    }

    // ---------------------------------------------------------- 物理方向

    private static void physicsDirection()
    {
        double fireY0 = meanY(cloud(10, "fire", 512, 7L), 0);
        SpellCloud fire = cloud(11, "fire", 512, 7L);
        step(fire, 20);
        double fireY1 = meanY(fire, 0);
        check("火元素上升", fireY1 > fireY0);

        SpellCloud water = cloud(12, "water", 512, 8L);
        double waterY0 = meanY(water, 0);
        step(water, 20);
        check("水元素下沉", meanY(water, 0) < waterY0);

        SpellCloud earth = cloud(13, "earth", 512, 9L);
        double earthY0 = meanY(earth, 0);
        step(earth, 20);
        check("地元素下沉", meanY(earth, 0) < earthY0);

        SpellCloud air = cloud(14, "air", 512, 10L);
        double spread0 = spread(air);
        step(air, 20);
        check("风元素扩散变大", spread(air) > spread0);

        boolean finite = true;
        SpellCloud fire2 = cloud(15, "fire", 256, 11L);
        step(fire2, 40);
        for (int i = 0; i < fire2.count; i++)
        {
            if (!isFinite(fire2.x(i)) || !isFinite(fire2.y(i)) || !isFinite(fire2.z(i)))
            {
                finite = false;
                break;
            }
        }
        check("40 步后坐标仍是有限值", finite);
    }

    private static void energyAndDeath()
    {
        SpellCloud cloud = cloud(20, "fire", 64, 21L);
        float e0 = cloud.energy(0);
        step(cloud, 10);
        check("能量衰减", cloud.energy(0) < e0);
        check("能量非负", cloud.energy(0) >= 0.0F);

        int steps = cloud.maxSteps;
        step(cloud, steps);
        check("寿命到点后 isDead", cloud.isDead());
    }

    private static void persistentStatic()
    {
        SpellCloud cloud = new SpellCloud(40, 42L, SpellElements.indexOf("fire"), 128,
                0.0, 64.0, 0.0, Shapes.POINT.id(), Shapes.POINT.defaults(),
                3.0F, 0.0F, 60.0F, 0.03F, true);
        double x0 = cloud.x(0);
        double y0 = cloud.y(0);
        double z0 = cloud.z(0);
        float e0 = cloud.energy(0);
        step(cloud, 20);
        check("常驻云不移动（静止）", cloud.x(0) == x0 && cloud.y(0) == y0 && cloud.z(0) == z0);
        check("常驻云步数仍在增长", cloud.ageSteps() == 20);
        check("常驻云能量不衰减", cloud.energy(0) == e0);
        check("常驻云不按寿命死亡", !cloud.isDead());
    }

    // --------------------------------------------------------------- 形状

    private static void shapes()
    {
        check("内置形状 ≥ 11 种", Shapes.count() >= 11);
        String[] required = {"point", "line", "disc", "ring", "polygon", "sphere",
                "shell", "cone", "helix", "sine", "cosine"};
        boolean all = true;
        for (String id : required)
        {
            all &= Shapes.exists(id);
        }
        check("11 种必需形状齐全", all);

        int fire = SpellElements.indexOf("fire");
        for (String id : required)
        {
            SpellShape shape = Shapes.byId(id);
            SpellCloud a = shaped(100, fire, 256, id, shape.defaults(), 1L);
            SpellCloud b = shaped(101, fire, 256, id, shape.defaults(), 1L);
            check("形状 " + id + " 可复现", a.stateHash() == b.stateHash());
            check("形状 " + id + " 坐标有限且不外溢", withinBounds(a, shape, shape.defaults()));
        }

        // 龙卷：竖向随机轴 + 每高度一圈 + R0(y) 幂展开（轴随时间转动）
        ShapeParams helixParams = new ShapeParams(2.6, 0.35, 8.0, 1.2, 6, 1.2, 2.2, 1.0, 0.18, 2.0, 2.5);
        SpellCloud storm = shaped(102, fire, 2048, "helix", helixParams, 7L);

        final int bands = 16;
        double[] sumX = new double[bands];
        double[] sumZ = new double[bands];
        int[] bandCount = new int[bands];
        boolean heightOk = true;
        for (int i = 0; i < storm.count; i++)
        {
            double localY = storm.y(i) - 64.0;
            heightOk &= localY >= -0.01 && localY <= 8.01;
            int band = Math.max(0, Math.min(bands - 1, (int) (localY / 8.0 * bands)));
            sumX[band] += storm.x(i);
            sumZ[band] += storm.z(i);
            bandCount[band]++;
        }
        double[] cx = new double[bands];
        double[] cz = new double[bands];
        for (int b = 0; b < bands; b++)
        {
            if (bandCount[b] > 0)
            {
                cx[b] = sumX[b] / bandCount[b];
                cz[b] = sumZ[b] / bandCount[b];
            }
        }

        int[] sectors = new int[12];
        double[] sumR = new double[bands];
        double[] bandMinR = new double[bands];
        for (int b = 0; b < bands; b++)
        {
            bandMinR[b] = Double.MAX_VALUE;
        }
        double minR = Double.MAX_VALUE;
        double maxR = 0.0;
        for (int i = 0; i < storm.count; i++)
        {
            double localY = storm.y(i) - 64.0;
            int band = Math.max(0, Math.min(bands - 1, (int) (localY / 8.0 * bands)));
            double dx = storm.x(i) - cx[band];
            double dz = storm.z(i) - cz[band];
            double r = Math.sqrt(dx * dx + dz * dz);
            sumR[band] += r;
            bandMinR[band] = Math.min(bandMinR[band], r);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            int sector = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI * 2.0) * 12.0);
            sectors[Math.max(0, Math.min(11, sector))]++;
        }

        double bottomR = 0.0;
        double topR = 0.0;
        int bottomN = 0;
        int topN = 0;
        for (int b = 0; b < 3; b++)
        {
            bottomR += sumR[b];
            bottomN += bandCount[b];
        }
        for (int b = bands - 3; b < bands; b++)
        {
            topR += sumR[b];
            topN += bandCount[b];
        }
        bottomR /= Math.max(1, bottomN);
        topR /= Math.max(1, topN);

        double wobble = 0.0;
        for (int b = 0; b < bands; b++)
        {
            wobble = Math.max(wobble, Math.abs(cx[b]));
        }

        boolean uniform = true;
        for (int s : sectors)
        {
            uniform &= s >= storm.count / 36;
        }
        check("火焰风暴高度 0–8", heightOk);
        check("火焰风暴全周均匀（不是螺旋线）", uniform);
        check("火焰风暴半径按幂律展开（顶部/底部 > 5）", bottomR > 0.0 && topR / bottomR > 5.0);
        check("火焰风暴轴是摆动的随机曲线", wobble > 0.3);
        check("火焰风暴底部很窄（< 0.6 格）", bottomR < 0.6);
        check("火焰风暴顶部环带半径 > 1.8", topR > 1.8);
        check("火焰风暴最高处半径接近 2.6", maxR > 2.4 && maxR < 3.5);
        double topBandMin = Math.min(Math.min(bandMinR[bands - 1], bandMinR[bands - 2]), bandMinR[bands - 3]);
        check("火焰风暴顶部空心（顶部最小半径 > 1.2）", topBandMin > 1.2);

        // 轴随时间转动（只让中轴线带时间参数，六定律不变）
        double[] c0 = new double[3];
        double[] c1 = new double[3];
        double[] c2 = new double[3];
        int lowIdx = 20;
        int highIdx = storm.count - 20;
        Shapes.HELIX.centerAt(lowIdx, storm.count, helixParams, 7L, 0.0, c0);
        Shapes.HELIX.centerAt(lowIdx, storm.count, helixParams, 7L, 0.0, c1);
        Shapes.HELIX.centerAt(lowIdx, storm.count, helixParams, 7L, 2.0, c2);
        check("轴点可复现", c0[0] == c1[0] && c0[2] == c1[2]);
        check("轴点随时间转动", Math.abs(c0[0] - c2[0]) + Math.abs(c0[2] - c2[2]) > 1.0E-3);
        Shapes.HELIX.centerAt(highIdx, storm.count, helixParams, 7L, 0.0, c2);
        double lowA = Math.sqrt(c0[0] * c0[0] + c0[2] * c0[2]);
        double highA = Math.sqrt(c2[0] * c2[0] + c2[2] * c2[2]);
        check("轴半径随高度增大", highA > lowA * 3.0);

        check("不同形状生成不同云", shaped(103, fire, 256, "sphere", Shapes.SPHERE.defaults(), 5L).stateHash()
                != shaped(104, fire, 256, "helix", helixParams, 5L).stateHash());
    }

    private static SpellCloud shaped(int id, int elementId, int particles, String shapeId,
                                     ShapeParams params, long seed)
    {
        return new SpellCloud(id, seed, elementId, particles, 0.0, 64.0, 0.0,
                shapeId, params, 3.0F, 0.0F, 60.0F, 0.0F, false);
    }

    private static boolean withinBounds(SpellCloud cloud, SpellShape shape, ShapeParams p)
    {
        double limit = p.radius() + p.length() + p.height() + 3.0;
        for (int i = 0; i < cloud.count; i++)
        {
            double x = cloud.x(i);
            double y = cloud.y(i) - 64.0;
            double z = cloud.z(i);
            if (!isFinite(x) || !isFinite(y) || !isFinite(z))
            {
                return false;
            }
            if (Math.abs(x) > limit || Math.abs(z) > limit || Math.abs(y) > limit)
            {
                return false;
            }
        }
        return true;
    }

    private static void cost(int particles)
    {
        SpellCloud cloud = cloud(30, "fire", particles, 31L);
        // 预热
        step(cloud, 5);
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++)
        {
            long t0 = System.nanoTime();
            step(cloud, 10);
            long dt = System.nanoTime() - t0;
            if (dt < best)
            {
                best = dt;
            }
        }
        double perStepMs = best / 10.0 / 1.0E6;
        System.out.printf(java.util.Locale.ROOT,
                "[cost] %d 粒 单步 p_best=%.3f ms（本机标定）%n", particles, perStepMs);
        check("4096 粒单步 < 5 ms（宽松上限，实机应 < 0.5）", perStepMs < 5.0);
    }

    /** 龙卷（animated 形状）每步要按时间重算轴点，单独量一下成本 */
    private static void costHelix()
    {
        int fire = SpellElements.indexOf("fire");
        ShapeParams p = new ShapeParams(2.6, 0.35, 8.0, 1.2, 6, 1.2, 2.2, 1.0, 0.18, 2.0, 2.5);
        SpellCloud cloud = new SpellCloud(300, 31L, fire, 3000, 0.0, 64.0, 0.0,
                "helix", p, 4.5F, 0.05F, 70.0F, 0.03F, false);
        step(cloud, 5);
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++)
        {
            long t0 = System.nanoTime();
            step(cloud, 10);
            best = Math.min(best, System.nanoTime() - t0);
        }
        double perStepMs = best / 10.0 / 1.0E6;
        System.out.printf(java.util.Locale.ROOT,
                "[cost] helix 3000 粒 单步 p_best=%.3f ms（含每粒轴点重算）%n", perStepMs);
        check("龙卷 3000 粒单步 < 2 ms", perStepMs < 2.0);
    }

    // --------------------------------------------------------------- 工具

    private static SpellCloud cloud(int id, String element, int particles, long seed)
    {
        int elementId = SpellElements.indexOf(element);
        if (elementId < 0)
        {
            throw new IllegalStateException("未知元素 " + element);
        }
        // 基础定律：默认阻尼 + 能量衰减
        return new SpellCloud(id, seed, elementId, particles, 0.0, 64.0, 0.0, 3.0F);
    }

    private static void step(SpellCloud cloud, int steps)
    {
        for (int i = 0; i < steps; i++)
        {
            cloud.step();
        }
    }

    private static double meanY(SpellCloud cloud, int unused)
    {
        double sum = 0.0;
        for (int i = 0; i < cloud.count; i++)
        {
            sum += cloud.y(i);
        }
        return sum / cloud.count;
    }

    private static double spread(SpellCloud cloud)
    {
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        for (int i = 0; i < cloud.count; i++)
        {
            mx += cloud.x(i);
            my += cloud.y(i);
            mz += cloud.z(i);
        }
        mx /= cloud.count;
        my /= cloud.count;
        mz /= cloud.count;
        double sum = 0.0;
        for (int i = 0; i < cloud.count; i++)
        {
            double dx = cloud.x(i) - mx;
            double dy = cloud.y(i) - my;
            double dz = cloud.z(i) - mz;
            sum += dx * dx + dy * dy + dz * dz;
        }
        return Math.sqrt(sum / cloud.count);
    }

    private static boolean isFinite(double v)
    {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static void check(String name, boolean ok)
    {
        checks++;
        if (!ok)
        {
            failures++;
            System.out.println("[FAIL] " + name);
        }
        else
        {
            System.out.println("[ok] " + name);
        }
    }
}
