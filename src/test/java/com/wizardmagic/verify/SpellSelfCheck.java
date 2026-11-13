package com.wizardmagic.verify;

import com.wizardmagic.api.spell.ShapeParams;
import com.wizardmagic.api.spell.SpellShape;
import com.wizardmagic.api.spell.Shapes;
import com.wizardmagic.core.spell.CollisionMode;
import com.wizardmagic.core.spell.Laws;
import com.wizardmagic.core.spell.ParticleCollider;
import com.wizardmagic.core.spell.SolidOracle;
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
        System.exit(run() == 0 ? 0 : 1);
    }

    /**
     * 跑一遍全部检查。
     *
     * @return 失败条数；给 JUnit 壳子用，不退出 JVM
     */
    public static int run()
    {
        checks = 0;
        failures = 0;
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
        collision();
        costCollision();

        System.out.println("checks=" + checks + " failures=" + failures);
        return failures;
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

    /**
     * 并行分段（{@link com.wizardmagic.core.spell.SpellParallel#forEachRange}）的三条硬约束：
     * 每个下标**恰好**跑一次（不重不漏）、结果与串行逐位相同、成本不均时也不会漏块。
     */
    private static void parallelRanges()
    {
        final int n = 100_000;
        int[] hits = new int[n];
        long[] sum = new long[1];
        com.wizardmagic.core.spell.SpellParallel.forEachRange(n, (from, to) ->
        {
            for (int i = from; i < to; i++)
            {
                hits[i]++;
            }
        });
        int bad = 0;
        for (int i = 0; i < n; i++)
        {
            if (hits[i] != 1)
            {
                bad++;
            }
        }
        check("并行分段：每个下标恰好跑一次（越界/重复 " + bad + " 个）", bad == 0);

        // 成本不均：前 1/8 故意做 200 倍的活，验证动态领块不会把尾巴留到最后
        java.util.Arrays.fill(hits, 0);
        long[] acc = new long[1];
        com.wizardmagic.core.spell.SpellParallel.forEachRange(n, (from, to) ->
        {
            long local = 0;
            for (int i = from; i < to; i++)
            {
                hits[i]++;
                local += i;
                if (i < n / 8)
                {
                    for (int k = 0; k < 200; k++)
                    {
                        local += (k * i) & 7;
                    }
                }
            }
            synchronized (acc)
            {
                acc[0] += local;
            }
        });
        long expect = 0;
        for (int i = 0; i < n; i++)
        {
            expect += i;
            if (i < n / 8)
            {
                for (int k = 0; k < 200; k++)
                {
                    expect += (k * i) & 7;
                }
            }
        }
        bad = 0;
        for (int i = 0; i < n; i++)
        {
            if (hits[i] != 1)
            {
                bad++;
            }
        }
        check("并行分段：成本不均时仍然不重不漏（" + bad + " 个）", bad == 0);
        check("并行分段：累加结果与串行逐位相同", acc[0] == expect);

        // 小批量走串行分支，不能因为 < MIN_CHUNK 就漏掉
        int[] small = new int[100];
        com.wizardmagic.core.spell.SpellParallel.forEachRange(100, (from, to) ->
        {
            for (int i = from; i < to; i++)
            {
                small[i]++;
            }
        });
        boolean allOne = true;
        for (int i = 0; i < 100; i++)
        {
            allOne &= small[i] == 1;
        }
        check("并行分段：小批量（100 粒）也逐粒跑一次", allOne);
    }

    private static void determinism()
    {
        parallelRanges();
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

        tiltedShapes();
    }

    /**
     * 形状朝向：把「本地 +Y 轴」指向任意方向（{@code emit { facing: … }}）。
     *
     * <p>朝向朝上时必须是恒等变换（旧行为逐位不变）；倾斜后图样要真的转到那个平面，
     * 锚点/轴心也要跟着转——否则回位弹簧和切向力会把人往原来的方向拽。</p>
     */
    private static void tiltedShapes()
    {
        int fire = SpellElements.indexOf("fire");

        // ring 默认躺在水平面（XZ），朝 X 之后应该立在 YZ 面
        SpellCloud flat = shaped(120, fire, 512, "ring", Shapes.RING.defaults(), 9L);
        SpellCloud upright = tilted(121, fire, 512, "ring", Shapes.RING.defaults(), 9L, 1.0, 0.0, 0.0);
        double[] eFlat = extent(flat);
        double[] eUp = extent(upright);
        check(String.format("水平 ring 铺在 XZ（x=%.2f z=%.2f y=%.2f）",
                eFlat[0], eFlat[2], eFlat[1]), eFlat[0] > 1.0 && eFlat[2] > 1.0 && eFlat[1] < 0.35);
        check(String.format("朝 X 的 ring 立在 YZ（x=%.2f y=%.2f z=%.2f）",
                eUp[0], eUp[1], eUp[2]), eUp[0] < 0.35 && eUp[1] > 1.0 && eUp[2] > 1.0);

        // 朝上的朝向必须等价于不写 facing（逐位一致）
        check("facing 朝上 == 不写 facing（逐位一致）",
                flat.stateHash() == tilted(122, fire, 512, "ring", Shapes.RING.defaults(),
                        9L, 0.0, 1.0, 0.0).stateHash());
        SpellCloud down = tilted(123, fire, 512, "ring", Shapes.RING.defaults(), 9L, 0.0, -1.0, 0.0);
        double[] eDown = extent(down);
        check("朝下是合法朝向（不会退化）", eDown[0] > 1.0 && eDown[2] > 1.0 && eDown[1] < 0.35);
        check("零向量朝向下退回竖直向上",
                tilted(124, fire, 128, "ring", Shapes.RING.defaults(), 9L, 0.0, 0.0, 0.0).faceY == 1.0);

        // 锚点跟着转：朝 X 的 ring，锚点应该落在竖直方向（原来的锚点是沿 Y 的）
        boolean anchorRotated = true;
        for (int i = 0; i < upright.count; i++)
        {
            double ax = upright.anchorX(i) - upright.originX;
            double ay = upright.anchorYWorld(i) - upright.originY;
            double az = upright.anchorZ(i) - upright.originZ;
            // 原来沿 Y 的分量现在应该转到 X 轴上
            anchorRotated &= Math.abs(ay) < Math.abs(ax) + 1.0E-9 || Math.abs(ay) < 1.0E-9;
            anchorRotated &= Double.isFinite(ax + ay + az);
        }
        check("倾斜后锚点跟着转（不再沿 Y）", anchorRotated);

        // 动画形状的轴心也要转：同一个粒子、同一时刻，倾斜后的轴心偏移必须是原偏移的旋转
        ShapeParams hp = new ShapeParams(2.6, 0.35, 8.0, 1.2, 6, 1.2, 2.2, 1.0, 0.18, 2.0, 2.5);
        SpellCloud hFlat = shaped(126, fire, 512, "helix", hp, 7L);
        SpellCloud hTilt = tilted(127, fire, 512, "helix", hp, 7L, 1.0, 0.0, 0.0);
        int probe = 200;
        double[] a0 = new double[3];
        double[] a1 = new double[3];
        hFlat.axisAt(probe, 0.0, a0);
        hTilt.axisAt(probe, 0.0, a1);
        double len0 = Math.sqrt(sq(a0[0] - hFlat.originX) + sq(a0[1] - hFlat.originY) + sq(a0[2] - hFlat.originZ));
        double len1 = Math.sqrt(sq(a1[0] - hTilt.originX) + sq(a1[1] - hTilt.originY) + sq(a1[2] - hTilt.originZ));
        double moved = Math.abs(a0[0] - a1[0]) + Math.abs(a0[1] - a1[1]) + Math.abs(a0[2] - a1[2]);
        check("倾斜后轴心跟着转（长度不变、方向变了）",
                Math.abs(len0 - len1) < 1.0E-9 && moved > 1.0E-3);

        check("朝向不同的云哈希不同", flat.stateHash() != upright.stateHash());
        check("朝向可复现（同朝向两次相同）",
                upright.stateHash() == tilted(125, fire, 512, "ring", Shapes.RING.defaults(),
                        9L, 1.0, 0.0, 0.0).stateHash());
    }

    /** 取一朵云某粒的轴心（朝向不同时它应该落在不同的轴上） */
    private static double sq(double v)
    {
        return v * v;
    }

    /** 云在三个轴上的跨度 */
    private static double[] extent(SpellCloud cloud)
    {
        double loX = Double.MAX_VALUE, hiX = -Double.MAX_VALUE;
        double loY = Double.MAX_VALUE, hiY = -Double.MAX_VALUE;
        double loZ = Double.MAX_VALUE, hiZ = -Double.MAX_VALUE;
        for (int i = 0; i < cloud.count; i++)
        {
            loX = Math.min(loX, cloud.x(i));
            hiX = Math.max(hiX, cloud.x(i));
            loY = Math.min(loY, cloud.y(i));
            hiY = Math.max(hiY, cloud.y(i));
            loZ = Math.min(loZ, cloud.z(i));
            hiZ = Math.max(hiZ, cloud.z(i));
        }
        return new double[]{hiX - loX, hiY - loY, hiZ - loZ};
    }

    private static SpellCloud tilted(int id, int elementId, int particles, String shapeId,
                                     ShapeParams params, long seed,
                                     double fx, double fy, double fz)
    {
        return new SpellCloud(id, seed, elementId, particles, 0.0, 64.0, 0.0,
                shapeId, params, 3.0F, 0.0F, 60.0F, 0.0F, false, fx, fy, fz);
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

    // --------------------------------------------------------------- 碰撞

    /** 地面在 y=50 以下：所有粒子从 y≈64 往下掉，应该停在地面上方 */
    private static final SolidOracle FLOOR = (x, y, z) -> y < 50;
    /** 地面 + 一堵在 x=3 的墙（用来量「撞墙」路径） */
    private static final SolidOracle FLOOR_AND_WALL = (x, y, z) -> y < 50 || x == 3;

    private static void collision()
    {
        // 1 确定性：同 seed + 同一个世界 → 逐位一致
        SpellCloud a = collideCloud(50, "earth", 512, 77L, FLOOR);
        SpellCloud b = collideCloud(51, "earth", 512, 77L, FLOOR);
        step(a, 40);
        step(b, 40);
        check("碰撞：同 seed 同世界 40 步后一致", a.stateHash() == b.stateHash());
        check("碰撞：没有碰撞的区别（对照）", a.stateHash()
                != collideHash(52, "earth", 512, 77L, null));

        // 2 不穿方块：土元素被地面挡住
        boolean above = true;
        for (int i = 0; i < a.count; i++)
        {
            above &= a.y(i) >= 50.0;
        }
        check("碰撞：土元素 40 步后没有掉进地面", above);

        // 3 无形元素照穿
        SpellCloud air = collideCloud(53, "air", 512, 78L, FLOOR);
        for (int i = 0; i < air.count; i++)
        {
            air.setVel(i, 0.0, -12.0, 0.0);
        }
        step(air, 20);
        boolean below = false;
        for (int i = 0; i < air.count; i++)
        {
            below |= air.y(i) < 50.0;
        }
        check("碰撞：无形元素（气）直接穿过地面", below);

        // 4 非弹性碰撞：法向速度反向并保留 80%（吸收 20%），切向分量不变
        SpellCloud hit = new SpellCloud(55, 79L, SpellElements.indexOf("earth"), 8, 0.0, 64.0, 0.0, 3.0F);
        for (int i = 0; i < hit.count; i++)
        {
            hit.setPos(i, 0.5, 49.5, 0.5);   // 压进地面一格，制造一次撞击
            hit.setVel(i, 1.5, -10.0, -2.0);
        }
        new ParticleCollider((x, y, z) -> y < 50, CollisionMode.INELASTIC).resolve(hit);
        boolean absorbed = true;
        for (int i = 0; i < hit.count; i++)
        {
            // -10 撞上去 → +8：法向吸收 20%；切向 1.5 / -2 原样保留
            absorbed &= Math.abs(hit.velY(i) - 8.0) < 1.0E-9
                    && Math.abs(hit.velX(i) - 1.5) < 1.0E-9
                    && Math.abs(hit.velZ(i) + 2.0) < 1.0E-9;
        }
        check(String.format("碰撞：非弹性反弹保留 80%%（-10 → %.1f）", hit.velY(0)), absorbed);

        // 4c 撞击事实：撞了几粒、撞在哪、撞前多快、撞的是哪一面（on hit 的事件数据）
        SpellCloud.HitFacts facts = hit.hitFacts();
        check("碰撞：八粒都记成一次撞击", facts != null && facts.count() == hit.count);
        check(String.format("碰撞：撞击点落在表面上（y=%.3f，表面 %.3f）",
                        facts == null ? -1.0 : facts.y(), 50.0 + hit.particleRadius()),
                facts != null && Math.abs(facts.y() - (50.0 + hit.particleRadius())) < 1.0E-2);
        check("碰撞：撞击事实记的是撞前速度", facts != null
                && Math.abs(facts.vx() - 1.5) < 1.0E-9
                && Math.abs(facts.vz() + 2.0) < 1.0E-9);
        check("碰撞：撞地时法向朝上（0,1,0）", facts != null
                && Math.abs(facts.nx()) < 1.0E-9 && Math.abs(facts.ny() - 1.0) < 1.0E-9
                && Math.abs(facts.nz()) < 1.0E-9);

        // 4e 撞竖墙：法向朝 +X（脚本拿它当"冲击波所在的平面"，所以撞墙不会是趴着的环）
        SpellCloud wall = new SpellCloud(60, 82L, SpellElements.indexOf("earth"), 8, 0.0, 60.0, 0.0, 3.0F);
        double wallPr = wall.particleRadius();
        for (int i = 0; i < wall.count; i++)
        {
            // 一步从墙外跨进墙格（dt=0.1，v=-15 → 1.5 格）：顺带验证"不会穿墙而过"
            wall.setPos(i, 2.7, 60.0, 0.5);
            wall.setVel(i, -15.0, 0.0, 0.0);
        }
        new ParticleCollider((x, y, z) -> x == 3, CollisionMode.INELASTIC).resolve(wall);
        SpellCloud.HitFacts wallFacts = wall.hitFacts();
        check("碰撞：撞墙时法向朝 +X（1,0,0）", wallFacts != null
                && Math.abs(wallFacts.nx() - 1.0) < 1.0E-9 && Math.abs(wallFacts.ny()) < 1.0E-9
                && Math.abs(wallFacts.nz()) < 1.0E-9);
        boolean stoppedAtWall = true;
        for (int i = 0; i < wall.count; i++)
        {
            // 站在墙的外侧：x ≥ 4 + 半径（不是穿过墙跑到 x<3 那一侧）
            stoppedAtWall &= wall.x(i) >= 4.0 + wallPr - 1.0E-6;
        }
        check(String.format("碰撞：快粒子不会一步穿过一格厚的墙（x=%.2f）", wall.x(0)), stoppedAtWall);

        // 4d 慢速接触不算撞击（否则落地的云会每步触发一次 on hit）
        SpellCloud nudge = new SpellCloud(59, 81L, SpellElements.indexOf("earth"), 8, 0.0, 64.0, 0.0, 3.0F);
        double nudgePr = nudge.particleRadius();
        for (int i = 0; i < nudge.count; i++)
        {
            // 球心在表面上一点点，前沿探进地面，但速度只有 0.4 格/秒（< HIT_MIN_SPEED）
            nudge.setPos(i, 0.5, 50.0 + nudgePr - 0.01, 0.5);
            nudge.setVel(i, 0.0, -0.4, 0.0);
        }
        new ParticleCollider((x, y, z) -> y < 50, CollisionMode.INELASTIC).resolve(nudge);
        check("碰撞：速度低于门槛的接触不算撞击", nudge.hitCount() == 0);
        check("碰撞：没有撞击时 hitFacts 为空", nudge.hitFacts() == null);

        // 4b 粒子是球不是点：落地后球心停在「表面 + 半径」，不是贴着表面
        double pr = hit.particleRadius();
        check(String.format("粒子半径按粒子数定（%.4f）", pr), pr > 0.05 && pr < 0.5);
        SpellCloud rest = new SpellCloud(56, 80L, SpellElements.indexOf("earth"), 64, 0.0, 64.0, 0.0, 3.0F);
        ParticleCollider floor = new ParticleCollider((x, y, z) -> y < 50, CollisionMode.INELASTIC);
        double restPr = rest.particleRadius();
        for (int i = 0; i < rest.count; i++)
        {
            rest.setPos(i, 0.5, 50.5, 0.5);
            rest.setVel(i, 0.0, -3.0, 0.0);
        }
        for (int k = 0; k < 20; k++)
        {
            for (int i = 0; i < rest.count; i++)
            {
                // 只推重力 + 位移，模拟一步物理（不经脚本，避免干扰）
                rest.setVel(i, rest.velX(i), rest.velY(i) - 0.98, rest.velZ(i));
                rest.setPos(i, rest.x(i), rest.y(i) + rest.velY(i) * 0.1, rest.z(i));
            }
            floor.resolve(rest);
        }
        boolean onSurface = true;
        for (int i = 0; i < rest.count; i++)
        {
            onSurface &= rest.y(i) >= 50.0 + restPr - 1.0E-6;
        }
        check(String.format("碰撞：球心停在地面上方一个半径（y ≥ %.2f）", 50.0 + restPr), onSurface);

        // 5 世界中途变化，下一步就生效（格位图每步重建）
        SolidOracle[] world = { (x, y, z) -> false };
        SpellCloud live = cloud(54, "earth", 256, 88L);
        live.setCollider(new ParticleCollider((x, y, z) -> world[0].solid(x, y, z), CollisionMode.INELASTIC));
        for (int i = 0; i < live.count; i++)
        {
            live.setVel(i, 0.0, -6.0, 0.0);
        }
        step(live, 5);
        boolean crossed = false;
        for (int i = 0; i < live.count; i++)
        {
            crossed |= live.y(i) < 63.0;
        }
        check("碰撞：空气世界不挡", crossed);
        world[0] = (x, y, z) -> y < 58;
        step(live, 30);
        boolean stopped = true;
        for (int i = 0; i < live.count; i++)
        {
            stopped &= live.y(i) >= 58.0;
        }
        check("碰撞：中途放下的方块下一步就生效", stopped);

        // 6 数值仍然有限
        boolean finite = true;
        for (int i = 0; i < a.count; i++)
        {
            finite &= isFinite(a.x(i)) && isFinite(a.y(i)) && isFinite(a.z(i));
        }
        check("碰撞：坐标仍是有限值", finite);

        // 7 两种格位图填法（惰性 / 整盒一次遍历）必须逐位一致——它们只是填法不同，
        //   物理是同一条代码。这条断言把"性能开关不能改结果"钉死。
        SpellCloud lazyFill = collideCloud(57, "earth", 512, 91L, FLOOR, ParticleCollider.Fill.LAZY);
        SpellCloud boxFill = collideCloud(58, "earth", 512, 91L, FLOOR, ParticleCollider.Fill.BOX);
        step(lazyFill, 40);
        step(boxFill, 40);
        check("碰撞：整盒一次遍历与惰性逐格结果逐位一致", lazyFill.stateHash() == boxFill.stateHash());
    }

    private static SpellCloud collideCloud(int id, String element, int particles, long seed, SolidOracle oracle)
    {
        return collideCloud(id, element, particles, seed, oracle, null);
    }

    private static SpellCloud collideCloud(int id, String element, int particles, long seed,
                                           SolidOracle oracle, ParticleCollider.Fill fill)
    {
        SpellCloud cloud = cloud(id, element, particles, seed);
        CollisionMode mode = SpellElements.collision(SpellElements.indexOf(element));
        if (mode != CollisionMode.NONE)
        {
            ParticleCollider collider = new ParticleCollider(oracle, mode);
            if (fill != null)
            {
                collider.setFill(fill);
            }
            cloud.setCollider(collider);
        }
        return cloud;
    }

    private static long collideHash(int id, String element, int particles, long seed, SolidOracle oracle)
    {
        SpellCloud cloud = oracle == null
                ? cloud(id, element, particles, seed)
                : collideCloud(id, element, particles, seed, oracle);
        step(cloud, 40);
        return cloud.stateHash();
    }

    /**
     * 碰撞内核成本：与 {@link #cost(int)} 同口径，空世界 / 地面+墙各量一次。
     *
     * <p>空世界的差值就是「每粒一次格位图读 + 索引运算」的纯内核成本；地面+墙再叠加
     * 真正撞墙的那部分粒子。方块查询次数单独打印——这是决定 {@code getBlockState}
     * 总开销的唯一变量。</p>
     */
    private static void costCollision()
    {
        int fire = SpellElements.indexOf("fire");

        // 点云 4096：和 cost(4096) 直接对比，差值就是碰撞内核
        SpellCloud point = new SpellCloud(310, 41L, fire, 4096, 0.0, 64.0, 0.0, 3.0F);
        double[] pointEmpty = collisionCost(point, (x, y, z) -> false, CollisionMode.INELASTIC);
        SpellCloud point2 = new SpellCloud(311, 42L, fire, 4096, 0.0, 64.0, 0.0, 3.0F);
        double[] pointSolid = collisionCost(point2, FLOOR_AND_WALL, CollisionMode.INELASTIC);

        // 龙卷 3000：真实形状（跨度 ~7×10×7），格位图区域明显更大
        ShapeParams helix = new ShapeParams(2.6, 0.35, 8.0, 1.2, 6, 1.2, 2.2, 1.0, 0.18, 2.0, 2.5);
        SpellCloud storm = new SpellCloud(312, 43L, fire, 3000, 0.0, 64.0, 0.0,
                "helix", helix, 4.5F, 0.05F, 70.0F, 0.03F, false);
        double[] stormEmpty = collisionCost(storm, (x, y, z) -> false, CollisionMode.INELASTIC);
        SpellCloud storm2 = new SpellCloud(313, 44L, fire, 3000, 0.0, 64.0, 0.0,
                "helix", helix, 4.5F, 0.05F, 70.0F, 0.03F, false);
        double[] stormSolid = collisionCost(storm2, FLOOR_AND_WALL, CollisionMode.INELASTIC);

        System.out.printf(java.util.Locale.ROOT,
                "[cost] 碰撞 4096 点云：空世界 %.3f ms/步（%.0f 次查询），地面+墙 %.3f ms/步（%.0f 次查询）%n",
                pointEmpty[0], pointEmpty[1], pointSolid[0], pointSolid[1]);
        System.out.printf(java.util.Locale.ROOT,
                "[cost] 碰撞 3000 龙卷：空世界 %.3f ms/步（%.0f 次查询），地面+墙 %.3f ms/步"
                        + "（%.0f 次查询 = %.3f 次/粒）%n",
                stormEmpty[0], stormEmpty[1], stormSolid[0], stormSolid[1], stormSolid[1] / 3000.0);
        check("碰撞 4096 粒单步 < 5 ms", pointSolid[0] < 5.0);
        check("碰撞：方块查询被格位图去重（< 1 次/粒）", stormSolid[1] < 3000.0);

        // 惰性 vs 整盒一次遍历：查询次数（这是唯一能确定的差别，耗时看 plan/probe）
        SpellCloud qc = new SpellCloud(314, 45L, fire, 3000, 0.0, 64.0, 0.0,
                "helix", helix, 4.5F, 0.05F, 70.0F, 0.03F, false);
        long qLazy = queriesPerResolve(qc, FLOOR_AND_WALL, ParticleCollider.Fill.LAZY);
        long qBox = queriesPerResolve(qc, FLOOR_AND_WALL, ParticleCollider.Fill.BOX);
        System.out.printf(java.util.Locale.ROOT,
                "[cost] 碰撞查询次数（3000 龙卷）：惰性 %d 次，整盒一次遍历 %d 次（%.1f 倍）%n",
                qLazy, qBox, qBox / (double) Math.max(1, qLazy));
        check("碰撞：整盒填满只会问得更多（惰性问的是盒子子集）", qBox >= qLazy);
    }

    /**
     * 一次 {@code resolve} 的方块查询次数。
     *
     * <p>惰性问的格子永远是整盒的子集（每格最多问一次），所以整盒在查询次数上
     * 永远不占便宜——它换来的是「每格更便宜」。这条不变式是判断这类优化值不值的前提。</p>
     */
    private static long queriesPerResolve(SpellCloud cloud, SolidOracle world, ParticleCollider.Fill fill)
    {
        CountingOracle counting = new CountingOracle(world);
        ParticleCollider collider = new ParticleCollider(counting, CollisionMode.INELASTIC);
        collider.setFill(fill);
        collider.resolve(cloud);
        return counting.calls;
    }

    /** @return {ms/步, 每步方块查询次数} */
    private static double[] collisionCost(SpellCloud cloud, SolidOracle world, CollisionMode mode)
    {
        CountingOracle counting = new CountingOracle(world);
        cloud.setCollider(new ParticleCollider(counting, mode));
        step(cloud, 5);
        long best = Long.MAX_VALUE;
        long calls = 0;
        for (int round = 0; round < 5; round++)
        {
            counting.calls = 0;
            long t0 = System.nanoTime();
            step(cloud, 10);
            best = Math.min(best, System.nanoTime() - t0);
            calls = counting.calls;
        }
        return new double[]{ best / 10.0 / 1.0E6, calls / 10.0 };
    }

    /** 计数代理：把「方块查询次数」和「内核耗时」分开看 */
    private static final class CountingOracle implements SolidOracle
    {
        long calls;
        private final SolidOracle delegate;

        CountingOracle(SolidOracle delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public boolean solid(int x, int y, int z)
        {
            calls++;
            return delegate.solid(x, y, z);
        }
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
