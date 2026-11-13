package com.wizardmagic.core.spell;

import java.util.Arrays;

/**
 * 方块碰撞：**一朵云一步一次**，不是一颗粒子一次 {@code Entity.move()}。
 *
 * <p>做法是「格位图 + 三轴扫掠」：</p>
 * <ol>
 *   <li>一步开始时按所有粒子的包围盒建一张小格位图（典型 12³～20³）；</li>
 *   <li>格位图有两个填法，结果逐位相同，只是成本不同（见 {@link Fill}）：
 *       <b>惰性</b>（默认）用到哪格问哪格；<b>整盒</b>先用
 *       {@link SolidOracle#fill} 一次遍历把盒子填满；</li>
 *   <li>每颗粒子先只看自己所在的那一格——<b>是空气就直接跳过</b>，这是绝大多数情况；</li>
 *   <li>只有落在实心格里的粒子才做 X/Y/Z 三轴扫掠，回退到方格表面并改速度。</li>
 * </ol>
 *
 * <p><b>为什么默认是惰性</b>：惰性问到的格子永远是整盒的子集（每格最多问一次），
 * 所以「整盒一次遍历」只可能把方块查询次数变多，唯一的好处是每格更便宜
 * （{@code LevelSolidOracle#fill} 一列只查一次区块）。实测（{@code plan/probe}）：
 * 龙卷那种稀疏云的盒子只有 ~5% 的格子会被粒子碰到，整盒填满要问 5 倍多的格子，
 * 而这份成本压根不是大头——每颗粒子那 6 次取整 + 2 次格位图读（~15 ns/粒）才是。
 *
 * <p>成本模型：每粒 1～2 次格位图读 + 少量整数运算。格位图每步重建，
 * 所以玩家中途放方块也算数。</p>
 *
 * <p>与 Minecraft 无关：{@link SolidOracle} 由服务端/客户端各自实现。这是刻意的——
 * 离机自检能直接量这个内核的成本，不需要启动游戏。</p>
 */
public final class ParticleCollider
{
    /** 格位图取值：不挡 */
    private static final byte AIR = 0;
    /** 格位图取值：实心 */
    private static final byte SOLID = 1;
    /** 格位图取值：还没问过（只有惰性填充才可能出现） */
    private static final byte UNKNOWN = 2;

    /** 回退到方格表面时留的余量，避免下一帧又被判成「在里面」 */
    private static final double EPS = 1.0E-3;
    /** 单步位移超过这个值就拆成子步，防止穿墙 */
    private static final double MAX_SUBSTEP = 0.5;
    /** 子步上限（脚本瞬移不该被拆成上百步） */
    private static final int MAX_SUBSTEPS = 8;
    /** 格位图上限（32³ = 32 KB/朵）；超过就退化为逐次查询，避免一朵云吃掉一大块内存 */
    private static final long MAX_CELLS = 32L * 32L * 32L;

    /**
     * 默认填法：{@code -Dwizardmagic.spell.collision=box} 时改成整盒一次遍历。
     *
     * <p>留这个开关是为了在实机里做对照——离机探针没法模拟「方块查询与粒子运算交错」
     * 对缓存的影响，只有进游戏才能看到真实差距（{@code /spell stats} 看 p99）。</p>
     */
    private static final Fill DEFAULT_FILL =
            "box".equals(System.getProperty("wizardmagic.spell.collision")) ? Fill.BOX : Fill.LAZY;

    private final SolidOracle oracle;
    private final CollisionMode mode;

    private byte[] cells = new byte[0];
    private int minX;
    private int minY;
    private int minZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private int volume;
    /** false = 包围盒太大，这一朵云不走缓存 */
    private boolean cached;
    private Fill fill = DEFAULT_FILL;
    /** true = 这一步的格位图是整盒填满的（热循环里不会有 {@link #UNKNOWN}） */
    private boolean prefilled;

    /**
     * 格位图怎么填。
     *
     * <p>两档的<b>结果必须逐位相同</b>，只有成本不同——自检里就是这么做对拍的。</p>
     */
    public enum Fill
    {
        /** 用到哪格问哪格（默认）：查询次数最少，但查询散在粒子循环里 */
        LAZY,
        /** 一次遍历填满整个包围盒：查询按列连续、每列只查一次区块，但格子问得更多 */
        BOX
    }

    public ParticleCollider(SolidOracle oracle, CollisionMode mode)
    {
        this.oracle = oracle;
        this.mode = mode == null ? CollisionMode.NONE : mode;
    }

    public CollisionMode mode()
    {
        return mode;
    }

    /** 切换填充策略（不影响结果，只影响成本） */
    public void setFill(Fill fill)
    {
        this.fill = fill == null ? DEFAULT_FILL : fill;
    }

    public Fill fill()
    {
        return fill;
    }

    /** 这一步的格位图是不是整盒填满的（统计/日志用） */
    public boolean prefilled()
    {
        return prefilled;
    }

    /**
     * 推进后校正：把落进方块的粒子退回表面，并按策略改速度。
     *
     * <p>调用点在 {@link SpellCloud#finishStep()} 里脚本/定律之后、步数自增之前——
     * 也就是所有粒子区间 join 完、回到调用线程那一刻。</p>
     *
     * <p>顺带算出这一步的<b>撞击事实</b>（哪些粒子、撞在哪、撞前多快）记到云上，
     * {@code on hit} 事件用的就是它。门槛见 {@link CollisionMode#HIT_MIN_SPEED}。</p>
     */
    public void resolve(SpellCloud c)
    {
        if (mode == CollisionMode.NONE || c.count == 0)
        {
            c.recordHits(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null);
            return;
        }
        cached = buildRegion(c);
        if (cached)
        {
            // 惰性只问粒子真正碰到的那几格；整盒则一次遍历把包围盒填满。
            // 整盒的每格更便宜（一列只查一次区块），但格子数只多不少。
            prefilled = fill == Fill.BOX;
            if (prefilled)
            {
                oracle.fill(minX, minY, minZ, sizeX, sizeY, sizeZ, cells);
            }
            else
            {
                Arrays.fill(cells, 0, volume, UNKNOWN);
            }
        }
        else
        {
            prefilled = false;
        }

        final double dt = SpellConstants.DT;
        // 粒子按半径 pr 的球处理：撞上后球心停在「表面 ± pr」。
        // 否则球心贴到表面，画面上看就是下半截埋进方块里（billboard 有半径）。
        final double pr = c.particleRadius();
        final int n = c.count;
        // 撞击事件的累加值（和而不是平均：每步最多一次除法，落在 getter 里）
        int hits = 0;
        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        double sumVX = 0.0;
        double sumVY = 0.0;
        double sumVZ = 0.0;
        double sumNX = 0.0;
        double sumNY = 0.0;
        double sumNZ = 0.0;
        // 撞到的方块 id（本步第一个计入事件的撞击格；没有就是 null）
        String hitBlock = null;
        for (int i = 0; i < n; i++)
        {
            double px = c.x[i];
            double py = c.y[i];
            double pz = c.z[i];
            double vx = c.vx[i];
            double vy = c.vy[i];
            double vz = c.vz[i];

            // 物理已经积分过了：用 pos - v*dt 还原上一位置
            double ox = px - vx * dt;
            double oy = py - vy * dt;
            double oz = pz - vz * dt;

            // 快筛：球心所在格、「球心沿速度推出 pr 的前沿」所在格、以及长步位移扫过的格子
            // 全是空的才跳过。落在地面上静止的粒子靠第二个条件命中（球心还在空气格，
            // 前沿已经进了地面）；一步能走半格以上的快粒子靠第三个条件，
            // 否则它会**一步穿过一格厚的墙**（dt=0.1，5 格/秒就是半格/步）。
            if (!maybeSolid(ox, oy, oz, px, py, pz, vx, vy, vz, pr))
            {
                continue;
            }

            // 球心在起点就已经在方块里（出生在墙里 / 世界刚变化）：放它出去，别把它弹到别的面
            if (solidAt(floor(ox), floor(oy), floor(oz)))
            {
                continue;
            }

            // 撞击速度 = 撞上之前那一瞬间的速度（respond 会把 vx/vy/vz 改掉）
            double inVX = vx;
            double inVY = vy;
            double inVZ = vz;
            double inSpeed = Math.sqrt(inVX * inVX + inVY * inVY + inVZ * inVZ);

            double dx = px - ox;
            double dy = py - oy;
            double dz = pz - oz;
            double maxDisp = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            int substeps = maxDisp > MAX_SUBSTEP ? (int) Math.ceil(maxDisp / MAX_SUBSTEP) : 1;
            if (substeps > MAX_SUBSTEPS)
            {
                // 脚本瞬移导致的超长一步：不为它做上百次子步（瞬移本来也不该参与碰撞）
                substeps = MAX_SUBSTEPS;
            }

            boolean hit = false;
            // 每轴只处理第一次穿入：撞过之后这个分量已经定住了，后面的子步不该再推进它
            boolean hitX = vx == 0.0;
            boolean hitY = vy == 0.0;
            boolean hitZ = vz == 0.0;
            // "真的撞上了"（区别于上面"这轴不参与"）：法向要从它来
            boolean bumpX = false;
            boolean bumpY = false;
            boolean bumpZ = false;
            // 撞到的那个格子（第一个撞上的轴），用来回答"撞到了什么方块"
            boolean haveCell = false;
            int cellX = 0;
            int cellY = 0;
            int cellZ = 0;
            double cx = ox;
            double cy = oy;
            double cz = oz;
            for (int s = 1; s <= substeps; s++)
            {
                double sx = ox + dx * s / substeps;
                double sy = oy + dy * s / substeps;
                double sz = oz + dz * s / substeps;

                if (!hitX)
                {
                    double sxp = probe(sx, vx, pr);
                    int fx = floor(sxp);
                    int fy = floor(cy);
                    int fz = floor(cz);
                    if (solidAt(fx, fy, fz))
                    {
                        if (!haveCell)
                        {
                            haveCell = true;
                            cellX = fx;
                            cellY = fy;
                            cellZ = fz;
                        }
                        cx = clampFace(fx, vx, pr);
                        vx = respond(vx);
                        hitX = true;
                        bumpX = true;
                        hit = true;
                    }
                    else
                    {
                        cx = sx;
                    }
                }
                if (!hitY)
                {
                    double syp = probe(sy, vy, pr);
                    int fx = floor(cx);
                    int fy = floor(syp);
                    int fz = floor(cz);
                    if (solidAt(fx, fy, fz))
                    {
                        if (!haveCell)
                        {
                            haveCell = true;
                            cellX = fx;
                            cellY = fy;
                            cellZ = fz;
                        }
                        cy = clampFace(fy, vy, pr);
                        vy = respond(vy);
                        hitY = true;
                        bumpY = true;
                        hit = true;
                    }
                    else
                    {
                        cy = sy;
                    }
                }
                if (!hitZ)
                {
                    double szp = probe(sz, vz, pr);
                    int fx = floor(cx);
                    int fy = floor(cy);
                    int fz = floor(szp);
                    if (solidAt(fx, fy, fz))
                    {
                        if (!haveCell)
                        {
                            haveCell = true;
                            cellX = fx;
                            cellY = fy;
                            cellZ = fz;
                        }
                        cz = clampFace(fz, vz, pr);
                        vz = respond(vz);
                        hitZ = true;
                        bumpZ = true;
                        hit = true;
                    }
                    else
                    {
                        cz = sz;
                    }
                }
            }

            if (!hit)
            {
                continue;
            }
            c.x[i] = cx;
            c.y[i] = cy;
            c.z[i] = cz;
            c.vx[i] = vx;
            c.vy[i] = vy;
            c.vz[i] = vz;
            float energy = c.energy[i] - (float) impactCost();
            c.energy[i] = energy < 0.0F ? 0.0F : energy;
            // 慢速贴着地面的接触不算撞击事件（见 CollisionMode.HIT_MIN_SPEED）
            if (inSpeed > CollisionMode.HIT_MIN_SPEED)
            {
                if (hits == 0 && haveCell)
                {
                    // 撞到什么方块：取本步第一个计入事件的撞击格（再问一次 oracle，每步最多一次）
                    hitBlock = oracle.blockId(cellX, cellY, cellZ);
                }
                hits++;
                sumX += cx;
                sumY += cy;
                sumZ += cz;
                sumVX += inVX;
                sumVY += inVY;
                sumVZ += inVZ;
                // 法向：撞哪一面就朝哪一面。斜着同时撞两面（棱角）时两个分量都算，
                // 加起来再归一化就是那个棱角的对角方向——脚本拿它当"冲击波所在的平面"。
                if (bumpX)
                {
                    sumNX += inVX > 0.0 ? -1.0 : 1.0;
                }
                if (bumpY)
                {
                    sumNY += inVY > 0.0 ? -1.0 : 1.0;
                }
                if (bumpZ)
                {
                    sumNZ += inVZ > 0.0 ? -1.0 : 1.0;
                }
            }
        }
        c.recordHits(hits, sumX, sumY, sumZ, sumVX, sumVY, sumVZ, sumNX, sumNY, sumNZ, hitBlock);
    }

    /**
     * 快筛：这一步"可能撞到"吗？只做格位图读，命中才进后面的三轴扫掠。
     *
     * <p>三个条件：球心格实心 / 球前沿格实心 / 长步位移扫过的中间格实心。
     * 第三个是必须的——{@code dt=0.1} 时 5 格/秒就是半格/步，10 格/秒就是整格，
     * 只看首尾会让粒子从一格厚的墙、平台里直接穿过去（自检里用一列墙钉着）。</p>
     */
    private boolean maybeSolid(double ox, double oy, double oz, double px, double py, double pz,
                               double vx, double vy, double vz, double pr)
    {
        if (solidAt(floor(px), floor(py), floor(pz)))
        {
            return true;
        }
        if (solidAt(floor(probe(px, vx, pr)), floor(probe(py, vy, pr)), floor(probe(pz, vz, pr))))
        {
            return true;
        }
        double dx = px - ox;
        double dy = py - oy;
        double dz = pz - oz;
        double disp = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (disp <= MAX_SUBSTEP)
        {
            return false;
        }
        int samples = (int) Math.ceil(disp / MAX_SUBSTEP);
        for (int s = 1; s < samples; s++)
        {
            double t = (double) s / samples;
            if (solidAt(floor(ox + dx * t), floor(oy + dy * t), floor(oz + dz * t)))
            {
                return true;
            }
        }
        return false;
    }

    private double respond(double v)
    {
        return switch (mode)
        {
            // 非弹性：反向并吸收 20%（切向分量在这一步之外，不受影响）
            case INELASTIC -> -v * CollisionMode.RESTITUTION;
            case STOP -> 0.0;
            case NONE -> v;
        };
    }

    private double impactCost()
    {
        return mode == CollisionMode.STOP
                ? CollisionMode.IMPACT_ENERGY_COST + CollisionMode.STOP_ENERGY_COST
                : CollisionMode.IMPACT_ENERGY_COST;
    }

    /** 球心沿速度方向推出半径，得到球「前沿」的位置；速度为 0 时就是球心 */
    private static double probe(double p, double v, double r)
    {
        return v > 0.0 ? p + r : v < 0.0 ? p - r : p;
    }

    /**
     * 沿法向把球心放到「表面 ± 半径」：v>0 从负侧进入（表面 = cell），v<0 从正侧进入（表面 = cell+1）。
     */
    private static double clampFace(double cell, double v, double r)
    {
        return v >= 0.0 ? cell - r - EPS : cell + 1.0 + r + EPS;
    }

    // ------------------------------------------------------------------ 格位图

    /**
     * 按当前粒子包围盒重建格位图。
     *
     * <p>盒子只按<b>当前位置</b>建：连上一步的位置一起算会把盒子撑大、每步多填不少格子
     * （实测碰撞内核 +60%）。扫掠起点落在盒外的少数快粒子会绕过格位图直接问世界，那是正确的，
     * 只是慢。</p>
     *
     * @return false = 包围盒超过 {@link #MAX_CELLS}，这一朵云退化为逐次查询
     */
    private boolean buildRegion(SpellCloud c)
    {
        this.volume = 0;
        double loX = Double.MAX_VALUE;
        double loY = Double.MAX_VALUE;
        double loZ = Double.MAX_VALUE;
        double hiX = -Double.MAX_VALUE;
        double hiY = -Double.MAX_VALUE;
        double hiZ = -Double.MAX_VALUE;

        for (int i = 0; i < c.count; i++)
        {
            double x = c.x[i];
            double y = c.y[i];
            double z = c.z[i];
            if (x < loX) loX = x;
            if (x > hiX) hiX = x;
            if (y < loY) loY = y;
            if (y > hiY) hiY = y;
            if (z < loZ) loZ = z;
            if (z > hiZ) hiZ = z;

        }
        if (!(loX <= hiX))
        {
            return false; // NaN
        }
        int x0 = floor(loX) - 1;
        int y0 = floor(loY) - 1;
        int z0 = floor(loZ) - 1;
        int x1 = floor(hiX) + 1;
        int y1 = floor(hiY) + 1;
        int z1 = floor(hiZ) + 1;
        long sx = (long) x1 - x0 + 1L;
        long sy = (long) y1 - y0 + 1L;
        long sz = (long) z1 - z0 + 1L;
        long volume = sx * sy * sz;
        if (volume <= 0L || volume > MAX_CELLS)
        {
            return false;
        }
        if (cells.length < volume)
        {
            cells = new byte[(int) volume];
        }
        this.volume = (int) volume;
        minX = x0;
        minY = y0;
        minZ = z0;
        sizeX = (int) sx;
        sizeY = (int) sy;
        sizeZ = (int) sz;
        return true;
    }

    private boolean solidAt(int x, int y, int z)
    {
        if (!cached)
        {
            return oracle.solid(x, y, z);
        }
        int ix = x - minX;
        int iy = y - minY;
        int iz = z - minZ;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= sizeX || iy >= sizeY || iz >= sizeZ)
        {
            return oracle.solid(x, y, z);
        }

        int idx = (ix * sizeY + iy) * sizeZ + iz;
        byte v = cells[idx];
        if (v == UNKNOWN)
        {
            v = oracle.solid(x, y, z) ? SOLID : AIR;
            cells[idx] = v;
        }
        return v == SOLID;
    }

    /** 快于 {@code (int) Math.floor(v)}，且对负数正确 */
    static int floor(double v)
    {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
