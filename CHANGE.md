# 元素魔法系统 · 粒子实体与六定律（实施变更）

> 依据：`plan/spell-system-spec.md` v1.1（§6.9 形状采样、§7 模拟、§11 性能）。
> 本文只覆盖**模拟层的前两步**：先把粒子实体立起来，再把六条定律跑通；碰撞/沉积/伤害/元素效果**本阶段不做**。
> 旧版 `CHANGE.md`（元素场流体化重构）已实施并归档：
> `backups/change-md/CHANGE-元素场流体化重构-已实施-20261111-1343.md`（原文件 548 行 / 60 KB）。

---

## 0. 范围

### 0.1 本次要做

| 步骤 | 内容 | 可见结果 |
| --- | --- | --- |
| **P0** | 粒子实体：`SpellCloud`（SoA）、`SpellCloudManager`、生成/销毁、网络包、客户端镜像、Spark 渲染接管、调试命令 | `/spell debug spawn fire 1024` 能看见一簇火粒子，按寿命消失 |
| **P1** | 六定律（浮力 / 阻尼 / 旋流 / 扩散 / 衰减 / 积分）+ 确定性噪声 + 10 Hz 推进 + 客户端同 seed 镜像 | 粒子会上升、打旋、扩散、耗尽；同一 seed 两次结果完全一致 |

### 0.2 本次**不做**（原因）

| 不做 | 原因 |
| --- | --- |
| 碰撞合并 / 元素反应 | 需要密度保护与查表，先用 P1 的均匀运动验证步进与确定性 |
| 沉积 / 方块交互 | 依赖「元素 × 方块 tag」表，尚未整理 |
| 伤害 / 元素效果 | 依赖实体命中判定与 `dps` 属性，等运动稳定后再接 |
| 形状采样（helix 等 7 种） | P2 再做；P0/P1 只用 `point`（球内均匀散布）验证管道 |
| 元素场扣费 | 与粒子运动解耦，放到施法流程阶段 |
| 符文 / 精华 / 机器 / 脚本语言 | 属于后续阶段（见 §8 路线图） |

### 0.3 完成判据

> 一条命令生成一团元素粒子，粒子按六定律运动、寿命耗尽后干净消失；**同一 `castSeed` 的服务端与客户端轨迹逐位一致**；4096 粒单云的服务端步进开销可测且 < 0.5 ms。

---

### 0.4 实施状态（2026-11-11）

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| P0 粒子实体 | ✅ 已实现 | `gradlew build` 通过；`SpellCloud`/`SpellCloudManager`/3 个包/命令/渲染接入齐全 |
| P1 六定律 | ✅ 已实现 | 离机自检 `verify/spell/SpellSelfCheck.java`：**checks=29 failures=0**；4096 粒单步 **0.203 ms** |
| 池拆分 + 可见性同步 | ✅ 已实现 | 服务端模拟池独立；常驻云不自然死亡；进入 64 格补发快照、离开发移除 |
| 常驻云静止 | ✅ 已实现 | `Laws` 对 `persistent` 直接返回；离机自检 `checks=33`，其中 4 条专测「不移动 / 不衰减 / 不死亡 / 步数仍增长」 |
| 三态可见性 | ✅ 已实现 | FULL ≤64 / SEMI 64–160（只发生成包 + 客户端淡出）/ HIDDEN >176；渲染拆两段 draw，法术裁距 192 |
| P2 形状采样 | ✅ 已实现 | `api/spell` 下 11 种内置形状 + `/spell debug cast`、`/spell debug firestorm`；离机自检 **checks=66** 全过 |
| 压地力（hold） | ✅ 已实现 | 竖直回位弹簧 `vy -= hold*(y-anchorY)*dt`；自检含「无 hold 上飘 / 有 hold 偏移 <2 格」对照 |
| 区块生命周期 | ✅ 已实现 | 未加载不推进；一次性云随区块丢弃、常驻云冻结保留；冻结云不同步；客户端区块卸载清镜像 |
| 进游戏实机 | ⏳ 待验证 | `/spell debug spawn fire 1024`、`/spell debug anchor fire 256` 的视觉与走远/返回行为 |

实现文件（相对 `src/main/java/com/wizardmagic/`）：

| 层 | 文件 |
| --- | --- |
| 引擎 `core/spell/` | `SpellConstants`、`SpellRandom`、`SpellNoise`、`SpellElements`、`SpellCloud`、`Laws`、`SpellCloudManager`、`event/SpellServerEvents` |
| 形状 API `api/spell/` | `SpellShape`（契约）、`ShapeParams`（8 参数 schema）、`Shapes`（11 种内置 + 注册表） |
| 网络 `common/network/` | `SpellCloudSpawnPayload`（含 `persistent` / `initHash` / 形状）、`SpellCloudSnapshotPayload`、`SpellCloudRemovePayload`；`ModNetwork` 协议 `"1"`→`"6"` |
| 渲染 `common/client/` | `fx/SpellInstanceSource`、`fx/SparkBatch`（法术实例前缀接入）、`spell/SpellCloudClient`、`spell/SpellClientEvents` |
| 命令 `common/command/` | `SpellCommand` |

> 与计划的偏差（已记录、非阻塞）：形状目前只有 `point` 球内散布（P2 才做 §6.9 的 7 种）；元素颜色用临时表，非基础元素回退紫白（M0 补 `color`）；`mediumDrag` 恒 0（P3 再做介质采样）。

---

## 1. 现状基线（全部是代码里已存在的事实）

| 事实 | 位置 |
| --- | --- |
| 实例流布局：`iPos`(3f) + `iPrev`(3f) + `iColor`(4B) + `iMeta`(4f) = **11 float / 44 B** | `SparkBatch#writeInstance`、`spark_instanced.vsh` |
| 着色器用 `mix(iPrev, iPos, uPartial)` 做 tick 间插值，`iMeta.y/z` 是 birth/life（tick 单位），`alpha=(1-t)²` | `spark_instanced.vsh:27-41` |
| 池分两区：burst **262,144** + 光点 persist **65,535**，合计 **327,679** | `SparkBatch:31-35` |
| 每帧 `MAX_DRAW = 262,144`；先填 burst（每 20 tick 按距离重排），光点整组放得下才画 | `SparkBatch:57, 256-275` |
| burst 满 → 覆盖最老的（`replaceCursor`），不失败 | `SparkBatch:97-102` |
| **实测**：`/wmfx burst 262144` 打满 burst 区，实机顺滑（2026-11-11） | 用户实机 |
| 网络：`SimpleChannel`，协议版本 `"1"`，现有 2 个包 | `ModNetwork` |
| 命令注册：`RegisterCommandsEvent` + `Commands.literal(...)` | `FxCommand`、`ElementCommand` |
| 元素注册表：50 个 `wizardmagic:element`，顺序 = `elements.json` | `ModElements`、`ElementFusion` |
| 元素数据**目前没有** `composition` / `color`（spec v2 才加） | `fusion/elements.json` |

> 结论：**渲染侧不需要建新管线**。法术粒子只要以「实例」形式写进 burst 区之前的实例数组前缀，就能直接复用现成的实例化 + 半分辨率 FBO + 双趟分流。

---

## 2. 三条关键决策

### 2.1 服务端权威 + 客户端同 seed 镜像（不逐 tick 同步）

- 服务端持有 `SpellCloud` 真值，按六定律推进；
- 客户端只收 `SpellCloudSpawnPayload`（元素、数量、seed、原点、寿命、flow/energy 参数），本地用**同一份 `Laws` 代码、同一 seed** 复算；
- **不发逐 tick 位置包**：4096 粒 × 64 云逐 tick 同步会打爆带宽。确定性由 P1 的 hash 比对验收保证。

### 2.2 复用 Spark 实例流，法术占「前缀」

`SparkBatch` 新增一个外部实例源，每帧最先填充实例数组（法术优先于 burst 与光点），**不新建 program / FBO / 池**。

### 2.3 SoA 基本类型数组，禁止 `Object[]`

4096 粒/云的 `Particle` 对象会带来 GC 压力与缓存抖动；全部用 `double[]/float[]/short[]` 平铺。

---

## 3. 数据结构

### 3.1 `SpellCloud`（服务端真值）

```java
package com.wizardmagic.core.spell;

public final class SpellCloud {
    public final int id;              // 全局自增，客户端靠它认云
    public final long castSeed;       // 所有随机/噪声都由它派生
    public final byte elementId;      // 元素表下标 0..49（不是 ElementTypes）
    public final int count;           // 8..4096
    public final double ox, oy, oz;   // 原点（P0/P1 用 point 散布中心）

    // ---- SoA ----
    private final double[] x, y, z;   // 世界坐标
    private final double[] vx, vy, vz;// 速度，格/秒
    private final float[] energy;     // 0..100
    private final float[] lifeSec;    // 单粒寿命（秒）
    private final short[] mass;       // 合并质量，P0/P1 恒为 1

    // ---- 云级参数（来自描述符-lite）----
    public float spin, up, out, drag;      // flow
    public float energyInit, energyDecay;  // energy
    public int ageSteps;                   // 已推进步数
    public final int maxSteps;             // life / DT

    public boolean isDead() { ... }
}
```

内存：4096 粒 × (6×8 + 2×4 + 2) ≈ **232 KB/云**；64 云上限 ≈ 15 MB。

**客户端镜像额外多 3 个 `double[]` 存上一步位置**（`prevX/prevY/prevZ`），用于着色器插值；服务端不需要。

### 3.2 `SpellCloudManager`（每维度一份）

```java
public final class SpellCloudManager {
    private final Map<ResourceKey<Level>, List<SpellCloud>> byLevel;
    private int nextId = 1;

    public SpellCloud spawn(ServerLevel level, byte elementId, int amount,
                            Vec3 origin, long seed, float lifeSec, FlowParams flow);
    public void clear(ServerLevel level);
    public void tick(ServerLevel level);          // 每 2 tick
    public Stats stats(ServerLevel level);        // 供 /spell debug stats
}
```

准入（超限即拒绝并提示，不排队）：

| 限制 | 值 |
| --- | --- |
| 单云粒子 | 8–4096 |
| 每维度云数 | 64 |
| 每维度**服务端模拟**粒子总数 | 262,144（独立于渲染池，可单独调大） |
| 每维度常驻云（法阵/结界） | 32（与一次性云分开计） |

> **渲染池 ≠ 模拟池**：服务端准入只受「模拟池」约束；客户端每帧画多少由 `SparkBatch.MAX_DRAW`（burst 区 262,144）决定，且只画**附近玩家能看到的部分**。两者当前数值相同纯属巧合，调大模拟池不需要动渲染池。

### 3.3 时间基准

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `STEP_TICKS` | 2 | 每 2 tick 推进一步（10 Hz） |
| `DT` | 0.1 s | 一步的物理时长 |
| `DT_INV` | 10 | |
| 云寿命 | 描述符 `life` 0.5–10 s | 转成 `maxSteps = ceil(life / DT)` |

---

## 4. 六定律

### 4.1 公式（每步、每粒）

```
1 浮力   vy -= gravity(e) * 9.8 * dt        // gravity > 0 下沉，< 0 上浮
1b 回位  vy -= hold * (y - anchorY) * dt    // 压地力：把粒子按在形状高度上（hold=0 关闭）
2 阻尼   v  *= max(0, 1 - (drag + mediumDrag) * dt)
3 旋流   切向速度 → ω·r（指数松弛，ω = spin）；并补向心加速度 -vt²/d
3b 径向  vr += ( -hold·(d - R0) - c·(vr - v_axis) + out ) · dt   // 形状约束（弹簧-阻尼）
3c 消散  寿命最后 18%：hold→0、spin→0.25ω、径向叠加 +14 m/s² 爆开（配合着色器平台+尾段淡出）
4 扩散   v  += noise3(castSeed, p*0.5, step) * volatility(e) * dt
5 衰减   energy -= energyDecay * dt
6 积分   p  += v * dt
```

> **实现时修正了 spec v1.0 的符号错误**：原式 `vy += (gravity(e) - 1.0) * 9.8 * dt` 会让火（gravity = -0.9）向下加速。按 §3.3 表的语义（正 = 下沉、负 = 上浮）改为 `vy -= gravity(e) * 9.8 * dt`；自检 `verify/spell/SpellSelfCheck.java` 的「火上升 / 水、地下沉」断言已通过。
>
> **两处长寿命相关的坑**（都是显式积分造成的）：
> ① `spin` 原来是恒定角加速度，长时间会越转越快、把圆环离心吹散 → 改成「目标角速度 ω·r + 指数松弛」，速度有上限；
> ② 每步沿直线弦移动会带来 `ω²d·dt²/2` 的半径增长（实测 6s 后半径胀到约 4 倍）→ 补上向心加速度 `-vt²/d`，再加 `hold` 驱动的径向弹簧-阻尼，6s 后平均径向偏差从 3.4 格降到 **0.28 格**。
>
> **消散爆开（3c）**：约束把结尾的"迅速扩散"也一起吃掉了，所以寿命最后 18% 主动释放——`hold` 随 `smoothstep(0.82→1.0)` 降到 0、`spin` 降到 25%、径向叠加 **14 m/s²** 向外加速度。实测 70% 寿命时仍是圆锥（偏差 <0.5 格），95% 时平均半径扩张到 **1.8 倍以上**。
>
> **半径脉动（R(y,t)）**：目标半径按 `R(y,t) = R0(y)·[1 + ε_r·sin(k_r·y − ω_r·t + φ_r)]` 随时间变化，径向弹簧去追它——形态会像呼吸/波纹一样起伏，但仍由力驱动，不做运动学摆放。`ε_r = radiusPulse`、`k_r = radiusWave`、`ω_r = radiusSpeed`；底部半径 `R_b = 0.02·R_t` 尽量小，形成针尖似的根部。
>
> **着色器配合**：法术粒子的生命期 alpha 从全局的 `(1-t)²` 改成**平台 + 尾段淡出**（`uLifeFade=1`：`1 − smoothstep(0.82, 1.0, t)`），否则 12s 寿命到 75% 时只剩 6% 亮度，爆开根本看不见；火花仍用原来的 `(1-t)²`。

- `gravity(e)` / `volatility(e)`：P1 先用**临时常量表**（4 基础元素 + spec §3.3 的 18 个覆盖值），M0 数据阶段改成读 `elements.json`。位置：`core/spell/SpellElements.java`。
- `mediumDrag`：P1 **恒为 0**（空气）。水/岩浆要逐粒查方块，等 P3 用「按 section 采样 + 缓存」再做，避免 4096 次 `getBlockState`/步。
- `noise3`：自写 `SpellNoise.value3(seed, x, y, z, step)`，整数 hash + smoothstep 三线性插值，返回 `[-1, 1]`；**纯 Java、无状态**，客户端/服务端逐位一致。禁止用 `Random` 或 Minecraft 的 `NoiseGenerator`（版本/实现差异会破坏一致性）。

### 4.2 确定性铁律

| 要求 | 做法 |
| --- | --- |
| 初始位置 | `hash(castSeed, i)` → 球内均匀（P0/P1），P2 换成形状采样 |
| 初始速度 | 同一 hash 流的下一个字段，方向 = 单位球内均匀 × 初速 |
| 噪声 | 只用 `SpellNoise`，与 tick 相位、线程、玩家无关 |
| 推进顺序 | 固定 `i = 0..count-1`，不做并行归约 |
| 帧率 | 物理只在 `gameTime % STEP_TICKS == 0` 推进一次；渲染插值不影响物理 |

### 4.3 客户端插值（复用现有着色器的 `uPartial`）

着色器的 `uPartial` 是「tick 内的小数」，而物理是 2 tick 一步。若直接写 10 Hz 的 prev/current，插值会每两步卡一下。

**做法**：客户端仍按 10 Hz 存真值，但**每 tick** 生成一条插值快照写入实例流：

```
stepPos   = 第 n 步结束位置
stepPosPrev = 第 n-1 步结束位置
每 tick t:  u = (tickInStep) / 2.0            // 0.0 或 0.5
           renderPos     = lerp(stepPosPrev, stepPos, u)
           renderPosPrev = lerp(stepPosPrev, stepPos, u - 0.5)   // 上一 tick 的同一插值
```

写进 `iPos` / `iPrev` 后，着色器再做一次 tick 内插值即可，运动连续、物理仍是 10 Hz。

---

## 5. 调度与预算（P1 先简单版）

| 项 | P1 做法 | 后续（P3+） |
| --- | --- | --- |
| 推进时机 | `ServerTickEvent.END`，`gameTime % 2 == 0` | 错峰：按距离分层，每 tick 只推进一部分云 |
| 预算 | 不做硬预算，只统计 | 每 tick 3 ms 预算 + 近处优先（spec §11） |
| 统计 | `stepCostNs` 的 p50/p99、云数、粒子数 | `/spell stats` 汇总 |

**为什么要先留统计**：burst 实测只证明渲染与「简单积分」便宜，`noise3` 与将来的碰撞成本还没量过；没有 p50/p99 就没法判断是否要上错峰。

### 5.1 区块生命周期（必须守住的四条）

法术云与区块的关系必须显式处理，否则会出现「区块卸载了还在模拟」「走远回来法阵没了」「客户端留着幽灵云」。

| 场景 | 规则 | 实现位置 |
| --- | --- | --- |
| 每一朵云 | 记录**原点区块** `(chunkX, chunkZ)`，作为加载判定的唯一依据 | `SpellCloud.chunkX()/chunkZ()` |
| 区块未加载 | **不推进**（`level.hasChunk` 兜底检查，每步都查） | `SpellCloudManager.tick` |
| 一次性云遇到区块卸载 | **直接丢弃** + 给已同步玩家发移除包（短命法术不该在未加载区继续存在） | `ChunkEvent.Unload` → `onChunkUnload` |
| 常驻云遇到区块卸载 | **冻结保留**（状态留在内存，不广播移除）；区块重新加载后从原状态继续 | 同上，`persistent` 分支 |
| 冻结中的云 | **不同步给任何玩家**（否则 60 格外会收到一朵冻结云的快照） | `syncVisibility` 的加载检查 |
| 客户端区块卸载 | 丢掉原点在该区块的镜像云（防御性；正常由可见性同步移除） | `SpellClientEvents.onChunkUnload` |

**常驻云是静止的**：`Laws.step` 对 `persistent` 直接返回，粒子保持在初始位置——调试命令生成的常驻云不会受浮力/旋流影响自己飘走（日志里步数仍增长，但位置不变）。将来真正的方阵法阵要做动画时，由宿主方块实体驱动，或另加动态标记。

### 5.2 三态可见性

| 状态 | 距离 | 服务端发什么 | 客户端表现 |
| --- | --- | --- | --- |
| `FULL` | ≤ 64 格（退出 96） | 生成包 / 状态快照（完整数据） | 全亮 |
| `SEMI` | 64–160 格（退出 176） | **只发生成包**（约 60 B，不传快照） | 64→160 格平滑淡出，仍能看到形体 |
| `HIDDEN` | > 176 格 | 移除包 | 不渲染 |

- 阈值带迟滞：进 64 / 出 96、进 160 / 出 176，避免在边界来回走时反复同步（上一轮日志就出现过抖动）。
- 半可见用「只发生成包」而不是快照：静止云本来就是精确状态；运动中的云在 160 格外淡成半透明，近似可接受，省掉每次 213 KB 的快照。
- 客户端在 `SpellCloudClient.fill` 里按到云原点的距离算 alpha 平滑系数，写进实例颜色，与 shader 无关。

**跨重启的持久化边界**（明确写死，避免误以为 debug 云会存盘）：

- 一次性云：不持久化，重启即消失（本来寿命也只有几秒）。
- 常驻云：**由宿主方块（法阵方块实体）负责**——方块实体 `saveAdditional` 存 seed/参数，`onLoad` 时重新 spawn；区块卸载时它随世界数据一起保存。
- `/spell debug anchor` 创建的是**无宿主**的调试常驻云，只在本次会话有效（日志会打印 `persistent=true` 但没有宿主）。

> 这也是为什么 P2 推荐「固定法阵做成方块实体 + 客户端程序化视觉」：持久化天然落在方块实体上，不需要给粒子云单独写 `SavedData`。

---

## 6. 渲染接入（改动 `SparkBatch`）

### 6.1 新增接口

```java
/** 法术实例源：每帧重建实例表时最先调用 */
public interface SpellInstanceSource {
    /** 以 11 float/实例的既有布局填充 data（从 0 开始），返回写入数量，不得超过 max */
    int fill(float[] data, int maxInstances);
}

public void setSpellSource(SpellInstanceSource source);
public void markDirty();   // 云新增/推进/结束时调用，触发重建
```

### 6.2 `rebuildInstances()` 改动（唯一一处）

```java
int idx = 0, written = 0;
int limit = MAX_DRAW;

// 0) 法术粒子优先
if (spellSource != null) {
    int n = Math.min(spellSource.fill(instanceData, limit), limit);
    written = n;
    idx = n * FLOATS_PER_INSTANCE;
}

// 1) burst（原有逻辑，起始 written 不再是 0）
for (int k = 0; k < sortCount && written < limit; k++) { ... }

// 2) 光点：written + persistCount <= limit 才整组画（原有逻辑不变）
```

> 排序策略保持现状：法术不参与距离排序（它优先于 burst）；burst 与光点仍按距离重排。

### 6.3 客户端镜像

```java
package com.wizardmagic.common.client.spell;

public final class SpellCloudClient implements SpellInstanceSource {
    private final Map<Integer, ClientCloud> clouds = ...;

    public void onSpawn(SpellCloudSpawnPayload p);   // 建 ClientCloud，跑同一套 Laws
    public void tick();                              // ClientTickEvent.END，gameTime % 2 == 0
    @Override public int fill(float[] data, int max); // 每帧写 11 float/实例
}
```

渲染参数（P0/P1）：

| 实例字段 | 取值 |
| --- | --- |
| `iPos` / `iPrev` | §4.3 的插值快照 |
| `iColor` | 元素颜色 × `energy/100` 亮度（P1 颜色用临时表，见 §7.3） |
| `iMeta.x` size | `0.06 + 0.02·∛amount`（spec §6.9） |
| `iMeta.y` birth | 云生成时的 `gameTime` |
| `iMeta.z` life | `lifeSec * 20`（tick） |
| `iMeta.w` phase | `hash(castSeed, i) & 0xFFFF / 65535f`（给着色器做随机相位） |

---

## 7. 网络与命令

### 7.1 新增包（协议版本 `"1"` → `"6"`）

| 包 | 方向 | 字段 | 大小 |
| --- | --- | --- | --- |
| `SpellCloudSpawnPayload` | S→C | `id, elementId, count, seed, origin×3, lifeSec, spin/up/out/drag, energyInit/energyDecay, persistent, initHash` | ≈ 61 B |
| `SpellCloudSnapshotPayload` | S→C | 同生成包参数 + `ageSteps` + `float[count*7]`（x,y,z,vx,vy,vz,energy） | ≈ 28 B/粒（4096 粒 ≈ 112 KB，只在进入范围时发一次） |
| `SpellCloudRemovePayload` | S→C | `id, steps, hash` | 9 B |

- 一次性云：生成发一次，**没有逐 tick 同步包**。
- **三态可见性**（每 10 tick 核对一次，新云生成时立即核对）：
  - `FULL`（≤64 格）：进入时发完整数据（新云发生成包、进行中的云发状态快照）；
  - `SEMI`（64–160 格）：**半可见**——只发生成包（不传大快照），客户端按距离平滑淡出；
  - `HIDDEN`（>176 格）：发移除包。
  - 半可见 → 完整时补一份快照校正状态；完整 → 半可见不发包，只淡出。
- **客户端渲染要单独一段 draw**：`SparkBatch` 里法术实例与光点实例现在分两次 `drawRange`，法术用 `uMaxDist = 192`（半可见区间），光点仍是配置的 64——否则 shader 会在 64 格把法术剔掉。
- 客户端校验 `count <= 4096`，超限直接丢弃并记日志。

### 7.2 命令

| 命令 | 作用 |
| --- | --- |
| `/spell debug spawn <element> <amount> [life] [seed]` | 在视线前方 6 格生成一朵云并广播 |
| `/spell debug clear` | 清空本维度所有云（服务端 + 广播移除） |
| `/spell debug stats` | 云数、粒子数、上一步耗时 p50/p99、客户端实例数 |
| `/spell debug hash` | 打印本端所有粒子位置的 FNV-1a 校验和（用于服务端/客户端比对） |

### 7.3 元素颜色（临时）

`elements.json` 还没有 `color`，P0/P1 用临时表：`earth #7A5230`、`water #2E6FD8`、`wind #D8E6F0`、`fire #FF6A2A`；其余元素回退到四元素按组成加权——但 `composition` 也没有，所以**非基础元素的临时颜色统一给 `#C0C0FF`**，并在日志里打一行「使用临时回退色」。M0 数据阶段补 `color` 后删除此表。

---

## 8. 分步实施

### P0 · 粒子实体（本次）

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | `SpellCloud` + `SpellCloudManager`（生成/销毁/上限/统计） | 服务端真值 |
| 2 | `SpellCloudSpawnPayload` / `RemovePayload` + `ModNetwork` 注册（版本 2） | 网络通道 |
| 3 | `SpellInstanceSource` + `SparkBatch` 前缀接入 | 渲染可见 |
| 4 | `SpellCloudClient`（只做匀速直线 + 到期消失，尚未接入定律） | 客户端镜像 |
| 5 | `SpellCommand`（`/spell debug ...`）+ 注册 | 可操作 |
| 6 | 元素颜色临时表 | 看得出是哪种元素 |

**P0 验收**
- `/spell debug spawn fire 1024` → 视线前方出现一团火色粒子，`life` 秒后消失，无残留对象（`/spell debug stats` 归零）。
- `/spell debug clear` 立即清空，光点区与 burst 区不受影响。
- `/wmfx burst 262144` 仍然顺滑；两种特效同时存在时，法术实例优先占 `MAX_DRAW` 名额。
- 客户端断线重连后无幽灵云（镜像随维度/连接清空）。

### P1 · 六定律（本次）

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | `SpellNoise`（value noise，确定性） | 定律 4 依赖 |
| 2 | `ElementPhysics`（gravity/volatility/drag 临时表） | 定律 1、4 依赖 |
| 3 | `Laws.step(cloud, dt, stepIndex)` | 六定律本体 |
| 4 | 服务端 10 Hz 推进 + 统计 | 权威模拟 |
| 5 | 客户端同 seed 镜像 + 每 tick 插值快照（§4.3） | 视觉连续 |
| 6 | `/spell debug hash` 双端比对 | 确定性证据 |

**P1 验收**
- 同一 `seed` 连续两次 spawn → `/spell debug hash` 完全相同。
- 服务端与客户端的**初始状态 hash 一致**（spawn 包携带 `initHash`，客户端收到即比对，不等步数）；死亡时记录双方最终 hash 与步数供人工核对（步数天然可能差 1~2，因为客户端收包有延迟）。
- 火焰元素明显上升、水元素下沉、风元素扩散最快（`gravity`/`volatility` 生效）。
- 4096 粒单云：服务端单步 p50 ≤ 0.5 ms（本机标定，记入 stats）。
- `energy` 归零或寿命到期的粒子不再参与步进（无泄漏）。

### P2 · 形状采样（本次）

形状是**公共契约**，放在 `api/spell/`（纯 Java、可离机测试，和 `api/crucible` 同性质）：

| 文件 | 作用 |
| --- | --- |
| `api/spell/SpellShape.java` | 契约：`id()` / `defaults()` / `sample(index, count, params, seed, out)` |
| `api/spell/ShapeParams.java` | 统一 8 参数 schema（radius/thickness/height/turns/sides/length/amplitude/frequency），加形状不用改协议 |
| `api/spell/Shapes.java` | 11 种内置形状 + 注册表（内容模组可 `register` 自定义形状） |

| 形状 id | 采样 | 默认参数 |
| --- | --- | --- |
| `point` | 球内均匀点云 | r=0.6 |
| `line` | 沿 X 的线段 | len=4, t=0.1 |
| `disc` | XZ 实心圆盘 | r=2, t=0.2 |
| `ring` | XZ 圆环带 | r=2, t=0.4 |
| `polygon` | 正多边形轮廓 | r=2, sides=6, t=0.1 |
| `sphere` | 实心球 | r=2 |
| `shell` | 球壳 | r=2, t=0.3 |
| `cone` | 圆锥（底 y=0，顶 y=height） | r=2, h=4 |
| `helix` | 竖向随机轴 + 每高度一圈 | r=2.6（R_t）, t=0.35（环厚）, h=8, turns=1.2（ω）, len=1.2（A_t）, amp=2.2（p）, freq=1（k_y） |
| `sine` | 正弦曲线 | len=6, amp=1.5, freq=1 |
| `cosine` | 余弦曲线 | len=6, amp=1.5, freq=1 |

- 采样用 `Math.sin/cos/cbrt`（只在生成时一次，误差 ~1e-16 ≪ hash 的 1/4096 量化）；**六定律仍只用四则运算与开方**。
- **`helix` 按参数化方程实现，但时间只作用在中轴线上**（不推翻六定律）：
  - 轴：`A(y) = A_b + (A_t-A_b)(y/H)^q`（`A_b=0, q=1.6`），`x_c = A·cos(k_y·y − ωt + φ0)`、`z_c = A·sin(...)`；
  - 半径：`R0(y) = R_b + (R_t-R_b)(y/H)^p`（`R_b = 0.02·R_t`），再叠加时间脉动 `R(y,t) = R0·[1+ε_r·sin(k_r y − ω_r t + φ_r)]`；每个高度一整圈、角度黄金比均匀；
  - 粒子：`x = x_c + R0·cosθ`，`z = z_c + R0·sinθ`；
  - **每步**六定律的切向/径向都绕「当前时刻 t 的轴点」计算（`SpellShape.centerAt(..., t)`），所以轴会随时间转动/扭摆，而粒子依旧由速度积分驱动；
  - 参数映射：`radius=R_t, thickness=环厚, height=H, turns=ω(rad/s), length=A_t, amplitude=p, frequency=k_y(全高波数)`；`turns` 不再是静态扭转。
- **压地力 `hold`**：火元素 `gravity = -0.9`，每步有 +0.88 m/s 的向上加速度，没有对抗力整团会飘走。新增竖直回位弹簧 `vy -= hold*(y - anchorY)*dt`（`anchorY` 是每粒在形状里的初始高度），龙卷预设给 `hold = 8`，粒子被按在各自高度上旋转。
- 形状进网络包：spawn / snapshot 各加 `shapeId(utf, ≤32)` + **11 参数**（≈ 37 B），协议 `"3"` → `"6"`。
- 命令：
  - `/spell debug shapes` —— 列出内置形状；
  - `/spell debug cast <shape> <element> <amount>` —— 用形状默认参数放一朵；
  - `/spell debug firestorm` —— 内置预设：3000 火粒子的空心中空涡柱（helix + spin 3.0 / up 2.2）。

**P2 验收**
- `/spell debug shapes` 列出 11 种；每种 `cast` 后形状可辨认（线是线、环是环、球是球）。
- `/spell debug firestorm`：**寿命 12s**、≥3000 火粒子构成竖向随机轴 + 幂律展开的喇叭涡柱（底部约 0.05–0.2 格、顶部 ≈2.6 格、每高度整圈均匀、中心空心、轴随时间扭摆、**半径带 `R(y,t)` 脉动**）；最后 18% 寿命释放约束并以 14 m/s² 迅速向外爆开；`hold=8` 同时负责竖直回位与径向约束（6s 后平均径向偏差 0.28 格，无约束对照组 0.80）。
- 同一 seed 两次施法轨迹一致（`initHash` 相同）；快照重入后形状不变。
- 离机自检新增 47 条形状/弹簧/消散断言（11 种 × 可复现/不外溢、龙卷全周均匀/幂律展开/轴摆动/底部窄/顶部空心、轴点可复现/随时间转动/随高度增大、无 hold 上飘 vs 有 hold 站住、径向约束保持圆锥、释放前圆锥/末端 1.8 倍扩张），合计 **checks=79，failures=0**；龙卷 3000 粒单步约 0.29 ms（含每粒轴点重算）。

### 后续阶段（只列顺序，不动手）

| 阶段 | 内容 | 关键验收 |
| --- | --- | --- |
| P2 ✅ | 形状采样 11 种（`api/spell`）+ 火焰风暴预设 | 已实现，见上方「P2 · 形状采样」 |
| P3 | 碰撞（密度保护）、沉积、伤害（`dps` + 原版 `hurt`）、元素效果 | 火焰风暴每秒伤害 ≤ 6、击退同原版 |
| P4 | 元素表扩字段（composition/color/tags）、精华 50 种、符文、符文板、机器 | 刻符→符文板→施法闭环 |
| P5 | 脚本语言（P1 子集）+ 编辑 UI + 热重载 | 板 → AST → 施法 |
| P6 | 预设/定型、`timeline`、文本往返 | 板↔文本 AST 相等 |

### P5 · 行为语言（语言内核已实施）

- 契约：`plan/spell-script-language.md`（取代 `plan/spell-system-spec.md` §6 的纯表达式版）
- 目录：`core/spell/script/`（Token / Lexer / Ast / Parser / Value / Env / Host / Interpreter / EmitSpec / CastResult / SpellScript），**不 import Minecraft**，可离机测试
- 执行：`on cast` 服务端一次（可读世界）→ `emit` 出云；`on tick(dt)` 双端每步各跑一次（**禁止读世界**，编译期静态检查）
- 接入：`SpellCloud.script / scriptVars / broken`；生成包与快照包追加「脚本 id + 源码 + 云级变量」（协议 `"7"`）
- 内置脚本：`data/wizardmagic/spells/{firestorm,frost_nova,flame_pillar,meteor}.spell`
- 命令：`/spell debug run <id>`、`/spell debug scripts`
- 实测：`ScriptSelfCheck` **checks=77 failures=0**；`ScriptSpecCheck` **checks=177 failures=0**；`SpellSelfCheck` **checks=98 failures=0**；3000 粒火焰风暴 **解释器 6.0 ms/步 → 寄存器机 1.34 ms/步（4.5×，单脚本新 JVM 预热后）**
- 方块碰撞（`CollisionMode` / `SolidOracle` / `ParticleCollider` / `LevelSolidOracle`）：**一朵云一步一次**，不是一颗粒子一次 `Entity.move()`。按当前粒子包围盒建小格位图（默认「用到哪格问哪格」，`-Dwizardmagic.spell.collision=box` 可切成整盒一次遍历，**两者结果逐位相同**，自检对拍），每粒先只看自己那格——空气就跳过；只有落在实心格的粒子才做 X/Y/Z 三轴扫掠、回退到方格表面并改速度。策略由元素决定（气/雾/虚空/光/雷/动力穿方块，其余非弹性：法向反向保留 80%）。粒子按**球**处理（半径 = 渲染的 billboard 半径，量化到 1/256 保证两端逐位一致），球心停在「表面 ± 半径」，所以画面上刚好贴着表面。离机实测：4096 点云 **0.279 ms/步（空世界）/ 0.251 ms（地面+墙）**，3000 龙卷 **0.227 / 0.239 ms**，方块查询被格位图去重到 **0.216 次/粒**
- 事件钩子（`on hit` / `on expire`）：两者都跑在 SoA 上，能读写粒子、能 `emit` 子云；`emit` 只能写在粒子循环外（与 tick 同一条规则），否则每粒一次会把世界塞满。`on hit` 在**碰撞之后**触发，带内建记录 `hit`（`pos` 撞击点 / `vel` / `normal` 撞击面法向 / `speed` 撞前速度 / `count` 本步撞击粒子数，位置与速度是所有撞击粒子的平均）；一步最多一次，且**撞击速度必须 > 1 格/秒**才算一次事件（否则贴地静止的粒子每 50 ms 就"撞"一次，落地炸开会变成连环炸）。`hit.normal` 让"任何角度都能撞"成立：撞地是 `(0,1,0)`、撞墙是墙面朝向，拿它当冲击波所在的平面，环就会趴在地上或立在墙上。`emit` 的默认出生点按阶段取：cast 用施法者脚下、tick 用云出生点、hit 用**撞击点**、expire 用**粒子现在的质心**（不是出生点）。`emit inherit: false` 生成"不带脚本、只按元素定律飘"的子云（烟/灰尘/碎片），雾的浮力为负所以会自己上浮。`on expire` 只在**服务端**跑（云的死在服务端是权威判定，客户端等移除包），子云生成统一排在整批 join 之后。带事件的脚本**不影响快速路径**：事件体是解释器一次性求值，tick 主循环照样编成寄存器机。示例见 `meteor.spell`（火球砸地 → 整团摊成环并向外扩散 + 一口往上飘的烟）
- 碰撞补一条**防穿墙**：快筛原先只看"球心格 + 前沿格"，而 `dt=0.1` 时 10 格/秒就是整格/步，粒子会从一格厚的墙、平台里直接穿过去。现在位移超过半格时沿位移采样中间格（`ParticleCollider#maybeSolid`），一列墙的用例钉着（顺带验出 `hit.normal` 撞墙的正确性）
- 伤害（P3 的第一块，**只做伤害**，不做击退/点燃）：脚本里写 `hurt(半径, 伤害[, 位置])`，返回 `void`。`core/spell/script/HurtSink` 是唯一出口：服务端挂 `McHurtSink`（AABB 粗筛 + 按球精确判定、`damageSources().magic()`、**跳过施法者自己**），客户端与离机自检挂 `null`（调用即 no-op，粒子轨迹两端仍逐位一致，有 hash 用例钉着）。`hurt` 只能写在 `on hit` / `on expire`（`on tick` 会每步结算、`on cast` 还没有云，两者编译期直接拒），也不许写在粒子循环里；默认落点是**事件点**（hit = 撞击点，expire = 质心），写第三参就显式指定；终身配额 8 次、半径夹在 0.5..16、单次伤害 (0, 40]。返回值必须是 `void`——脚本要是能读到"打到了几个"，客户端读到 0、服务端读到真实值，两端立刻分叉。`meteor.spell` 已接上：撞击时 `hurt(4.0, 8.0)`
- 服务端模拟池 262,144 → **524,288**（512 K），单朵云上限 4096 → **8192**，云数上限 64 → **128**。三个数是自洽的：`64 × 8192 = 128 × 4096 = 524,288`，所以满池可以是一朵朵大云、也可以是两倍的小云。**客户端不动**：`SparkBatch.MAX_DRAW` 仍是 262,144，模拟得起、未必看得全。单朵 8192 粒时 SoA 约 0.95 MB、快照包（`count*7` 个 double）约 459 KB（只发一次，仍在 SimpleChannel 上限内）。`spellMillion` 新增两个满池刻度实测（本机、`SpellBatch` 整批并行）：**64 朵 × 8192 = 524,288 → 51.8 ms/步（99 ns/粒，占 10 Hz 单步预算 52%）**；**128 朵 × 4096 = 524,288 → 52.0 ms/步（52%）**；单朵云多大几乎不影响每粒成本。旧的 1M 刻度仍是 95.9–99.8 ms/步（96–100%，刚好卡在窗口上）。满池纯 SoA 数组约 58 MB（每粒约 116 B），实测堆峰值 187–249 MB（含解析与临时对象，不是精确值）
- `/spell reload`：作者主循环（改 `.spell` → reload → `run`，不重启）。编译失败会把"第几行第几列、错在哪"直接打在聊天栏；已经在飞的云不换脚本，新施法才用新代码；`/spell debug cache` 复用同一条路径
- 投射物能力（`look()` + `fireball.spell`）：`look()` 返回施法者视线方向（已归一化），和 `here()` 一样是 **cast 专属**——`tick` 里读它两端朝向未必一致，编译期就拒。有了它，`on cast` 把方向存进云级变量（`let dir = look()`），`tick` 里 `p.pos = p.pos + dir * 速度 * dt` 就是一颗**会飞的法术**，撞到方块由 `on hit` 引爆。示例 `fireball.spell`（炎爆弹）：260 粒火球 16 格/秒飞出、撞墙那一刻炸开（900 粒火壳向外扩 + 240 粒雾往上飘 + `hurt(3.5, 9.0)`），用 `armed` 云变量保证**只炸一次**（撞地后本体还活到寿命结束，不卡就会二次爆炸），飞到期满没撞到东西则按质心在半空炸
- **自定义事件**（`on 名字 { ... }` + `call 名字()`）：语言不加函数，但允许"给一段代码起名字"。五条规矩，全部在编译期检查：① 没有参数、没有返回值；② 被调用的事件只拿得到**根作用域**（云级变量、`dt/time/life/count/step`，以及 `on hit` 里的 `hit`），**看不到调用者的局部变量**（否则同一段代码被谁调用行为都不一样）；③ 不能写在粒子循环里（它看不到 `p`，而且会让"循环里 emit/hurt"绕过后面的保护）；④ 不能递归（`a → b → a` 编译期报错，顺便挡住了运行期栈溢出）；⑤ **按调用图判阶段**：`hurt` 只许出现在"只被 hit/expire 调用"的事件里、世界查询只许出现在"只被 cast 调用"的事件里，被 `on tick` 调用到就报错并点名是谁调的。`on tick` 里用 `call` 会掉快速路径（事件体是解释器跑的，寄存器机没法中途跳出去跑一段再回来），原因写在退化信息里。`fireball.spell` 已改用它：爆炸只写一遍（`on explode`），`on hit` 和 `on expire` 各 `call` 一次
- **自定义事件带参数**：`on explode(radius, damage) { ... }` + `call explode(3.5, 9.0)`。实参在**调用者**作用域里求值（能传局部变量、`hit.count`），形参是被调事件的局部变量；同一事件在两处调用可以给不同值（`fireball` 撞墙 3.5/9.0、半空炸 3.0/7.0）。编译期检查：实参个数必须对上、形参不能在调用前重复、**形参不能和内建量（`dt/step/hit`…）或云级变量同名**（同名会静默遮住云级变量，赋值就写不到状态上）
- **`hit.block` + 字符串字面量**：撞击事件多了"撞到的是什么方块"（`"minecraft:ice"` 这种 id，取本步第一个撞击格）。为此给语言加了**最小的字符串**：只能写字符串字面量、只能 `==` / `!=` 比较，不能拼接/算术、不能存进云级变量（所以生成包与存档格式不变，两端一致性也不受影响）；快速路径里没有字符串（只在事件体里用）。`SolidOracle` 多一个默认方法 `blockId()`，离机假世界不实现就返回 `null`（脚本看到空串）
- **`on touch`（碰到生物）+ `hurt(半径 0)` + `end()`**：`on touch` 在**步末、只在服务端**触发（客户端的实体位置和主机不可能一致，拿它判定必然分叉），**每个实体一步一次**（一步最多 8 个、终身 64 次）。内建记录 `touch`：`touch.entity`（`type` / `pos` / `id` / `health`，只读）、`touch.pos`（接触点 = 第一个落在它身体里的粒子位置）。`hurt` 的半径允许写 **0**，语义是"只打碰到的那个实体"（`SpellWorld#hurtEntity`，按 id 精确命中），只有 `on touch` 里有这个对象，写在 `on hit` 里是运行期错误。新增 `end()`：立刻结束这朵云，**不触发 `on expire`**（不是寿终，是作者主动喊停），只能写在 hit / touch / expire（tick 是编译执行的，寄存器机没有中途结束的通道）。`HurtSink` 因此改名并扩成 `SpellWorld`（`hurt` / `hurtEntity` / `touches`），服务端实现是 `McSpellWorld`：先按云的包围盒查一次实体、再逐个体看"有没有粒子在它身体里"，**只在这份脚本有 `on touch` 时才会被调用**（没钩子的法术一个字节都不花）。`fireball` 接上：碰到生物 → `hurt(0, 9.0)`（只打那一只）+ 炸开 + `end()`
- **新内建 `%` / `sign` / `pow` / `atan2`**：`%` 是和 `*` `/` 同级的运算符（语义同 Java，符号跟随被除数，**除数为零报错**而不是 NaN）；`pow`/`atan2` 用 `StrictMath`（`Math` 允许 ±1 ulp 平台差异，两端是不同 JVM 会分叉）；`sign` 用比较写出（避开 `Math.signum` 的 ±0.0 细节）。四个都进了快速路径（`OP_MOD_N/OP_SIGN/OP_POW/OP_ATAN2`），`IR_VERSION` 5 → 6，旧 `.spellc` 自动作废
- **`here()` 语义修正 + 新增 `aim(d)`**：`here()` 一直被喂成"眼睛 + 视线 × 6"（`/spell debug run` 当初为了"在瞄准点炸开"定的），于是所有用 `at: here()` 的法术都生在你身前 6 格。现在拆成三件事：`here()` = **施法者眼睛位置**、`look()` = 视线方向、`aim(d)` = `here + look × d`。`fireball` 的枪口改成 `at: aim(1.5)`（正好身前 1.5 格）；firestorm / flame_pillar / frost_nova 改成 `at: aim(6)`、meteor 改成 `aim(6) + vec(0,2,0)` 来**保持原有观感不变**（否则它们会突然挪到施法者身上）。另外删掉 fireball 里我加的 `p.pos += vec(0, 1.6*dt, 0)`——那是在位置上叠竖直偏移，16 格/秒下弹道会向上偏 5.7°（"瞄准了却往上飘"）
- **寿命上限 60 秒 → 1800 秒（30 分钟）**：`emit` 的 `life` 以前被静默夹到 60，作者写 `life: 240` 实际只活 60 秒（"我写了 4 分钟，怎么 1 分钟就没了"）。现在夹到 0.1..1800，常量在 `SpellConstants.MIN_LIFE_SEC / MAX_LIFE_SEC`；`SpellCloud` 构造时也夹一次，并用 long 算 `maxSteps` 再夹回 `int/4`——外部传 1e9 秒会让 `(int)` 溢出成负数、云永远不死。真要"永不消失"仍用常驻云（`/spell debug anchor`）：寿命再长也是每步都在推进粒子，60 朵 8192 粒的长驻云会一直占着 524,288 的池子
- **碰撞：试过"高度图提前退出"，已还原**：原方案每步按包围盒的列取一次 `MOTION_BLOCKING` 高度图，`y >= 该列高度` 的格子直接判空气、整朵云都在所有列之上时整云跳过（空中云 0 次方块查询）。原理上它依赖"MOTION_BLOCKING 高度图一定盖住所有有碰撞体积的方块"，对原版方块成立，但**模组方块/高度图未及时更新/客户端高度图与服务端不同步**这些情况下会让本该发生的碰撞被跳过——空战里就是"穿过去没撞到"。收益（省方块查询）远不值得冒这个险，代码与 `SolidOracle.heights()` 接口一并删除。整盒预填（`Fill.BOX`）也确认无收益（填充成本 ∝ 盒子体积，惰性只问粒子附近的格子），保留为实验开关
- **客户端实例表不再每 tick 重建两遍**：spark 采样显示渲染线程里 `SpellCloudClient.fill` 占 8.28%，拆开是两条路径——`SparkBatch.tick()` 的一次（5.53%）+ 包/快照处理里 `rebuildNow()` 的一次（2.75%）。现在包处理只 `markDirty()`，重建统一交给每 tick 的 `SparkBatch.tick()`（它在渲染之前跑，画面不会晚一帧）；`SpellClientEvents.onClientTick` 提到 `EventPriority.HIGH`，保证镜像云先更新、再重建实例表
- **并行改成动态领块**：以前是"按线程数等分 P 段、调用线程跑第一段"，等分的是粒子数而不是工作量，快的段跑完就在 join 上等（spark 采样：服务端 `FutureTask.get` 的 park 13.2%、客户端 10.1%）。现在固定块大小（`max(1024, count/(线程×8))`）+ 原子计数器领块，调用线程也参与；每颗粒子照样只碰自己的下标，结果与串行逐位相同
- **slow step 日志降噪**：整批一步超过 8 ms 才告警（原来 3 ms、注释还写成"30% 预算"——10 Hz 一步是 100 ms，3 ms 只有 3%），并且**最长每秒一条**。实机 28 万粒时每步都打，881 条 slow 日志占了整个 `latest.log` 的 75%
- 基础定律瘦身：`Laws` 从 178 行「六定律 + 龙卷补丁」缩到 40 行通用核（元素浮力 / 线性阻尼 / 噪声扩散 / 能量衰减 / 积分）。旋涡、上升、回位弹簧、消散爆发、轴心前馈全部只在 `.spell` 里。`SpellCloud` 删掉 `spin/up/out/hold` 四个字段，生成包/快照包同步删 4 个 float，协议升 **"8"**；旧 `/spell debug firestorm` 删除（统一用 `run firestorm`）。基础核单步：4096 粒 0.41→**0.21 ms**，helix 3000 粒 0.32→**0.16 ms**。
- 快速路径：`core/spell/script/FastProgram.java`（编译）+ `RegVm.java`（执行），静态类型推断 + **三地址寄存器机** + 操作数打包（`op<<24|dst<<16|a<<8|b`，绝大多数指令只读 1 个 int）+ 每粒轴点/目标半径缓存 + 粒子无关 `let` 外提 + 单分量取数指令（`LPOSX/LAXISY/LLOCALZ/...`）；变量即寄存器，`let`/`=` 不再有 LOAD/STORE；形状侧 `SpellShape.prepare()` 预计算每粒 `pow`；三角改用 `SpellMath`（与 `Mth.sin/cos` 同表的查表实现）；与解释器逐位一致（自检对 3 个内置脚本各跑 60–120 步比对 hash）；不满足编译条件时自动退回解释器。火焰风暴编成 **26 个寄存器 / 198 个 int（≈150 条指令）**（栈机版 522 个 int）
- 冷热拆分：`runLoop` 不再是一个几百行的大方法。`FastProgram.prepare`（冷：校验 + 搬自由变量 + 用解释器算外提量，失败返回 `null` 且**全部循环准备完才动粒子**）+ `RegVm.execute`（热：只收 `int[]/double[]/cloud/count/time` 的静态方法）+ `RegVm.divZero`（冷抛错）。实测：单脚本隔离 1.34 ms 与栈机持平（分派只占每粒成本三分之一），**整套自检同进程连跑 8+ 脚本时 1.95–2.14 → 1.62–1.70 ms（−21%）**，"同体型去噪声"纯算术体 **1.48 → 1.00 ms（−32%）**
- 逐部件实测（3000 粒，写 `p.pos` 避免能量归零导致粒子被淘汰）：空循环 3 ns/粒、`p.axis` 采样 ≈12 ns、`len_xz(pos-axis)` 53 ns、`tangent` 39 ns、一次 `noise` 35 ns（火焰风暴调 3 次 ≈105 ns，占整步 28%）→ 下一步性能大头是 `SpellNoise.value3` 与形状采样，不是解释器
- 已试并回退：`noise3`（三流共享 fade/floor）。本机实测字节码路径 **1.23 → 1.7 ms**（慢 40%），解释器反而 6.0→5.1 ms；展开成直线代码也没有改善，说明瓶颈在 `value3` 的 8 次 hash 本来就被内联，而共享版因为方法体变大失去内联。代码已全部回退，不保留。
- 存档法术目录：`<存档>/wizard_magic/{spell,.cache}`。`spell/<id>.spell` 放玩家自定义（文件名即 id，同名覆盖预设，编译失败自动退回预设并告警）；`.cache/<id>.spellc` 存预设与玩家法术的编译产物。取源码顺序：存档优先 → 资源包回落
- `.spellc` 格式：`SpellBytecode` 信封（魔数 + 信封版本 + `IR_VERSION` + 源码 FNV-1a 64 + 变量签名 + 32 位校验和）+ `FastProgram` 的寄存器机指令流。**不是可分发的产物**：顶层标量与外提表达式仍是语法树，读回时用同一份源码重新解析、按 `let` 编号挂回。信封任一条对不上就重编并覆盖。实测体积：火焰风暴 1002 B / 冰霜新星 635 B / 火焰冲击 594 B
- 缓存不省帧时间：解析 16 µs + 代码生成 14 µs = **30 µs/脚本**，而一次 3000 粒步进 1300 µs（差 41 倍）。它的价值是"法术落进存档、看得见、能核对"与玩家法术存放，不是帧时间
- 顺带修掉的潜在 bug：快速路径原先不考虑 `cast` 变量的类型，脚本被多朵云复用时可能沿用按另一套变量推断的代码；现在按变量签名核对，不一致就重编
- 接线：开服 `warmUp`（全编 + 写缓存 + 一行摘要）；`/spell debug scripts` 显示 `[内置]/[存档]`、快速路径或解释器、缓存字节；`/spell debug cache` 重建缓存；客户端按源码字符串缓存编译结果（上限 64）
- 客户端步进/插值时钟（**结论：仍用 `level.getGameTime()` 的奇偶，步进与相位共用**）：踩过两次坑，第三次才算有数据
  - 第一次错：`stepClock` 只在 `gameTime` 变化时 +1，而门是每个 client tick 都判的 → 门会连开，双步 + 相位滑（画面每步先冲过去再退回来）
  - 第二次尝试：换成纯本地单调计数器（每客户端 tick 无条件 +1）。实测体感**更差**，已回退——原因至今没有确证，不要再盲试
  - 回退后实测是好的：单云时 `render diag` 的 `phase` 全程 `010101…`、步间隔 92–104 ms、火焰风暴 **120 步对 120 步 `compare=OK`**（零丢步）
  - 曾经看到过"每秒抽一下"：那次是 8 朵云 / 3 种脚本同时在跑，客户端 120 步丢 5 步、lag 随寿命增长。同一份代码单云时不复现，怀疑与多脚本 JIT 反复编译/去优化有关（此前实测多脚本会把步进从 1.34 抖到 2.1 ms），**未定论**
  - 诊断（默认关，`-Dwizardmagic.script.debug=1` 才打）：客户端 `gameTime` 增量 ≠ 1 时打 `client clock delta=… drift=…`；每秒一行 `render diag: ticks= steps= gap=…ms phase=…`。复现"抽动"时先看这两个：`phase` 非 `0101` 或 `gap` 出现 50/150 = 相位/步进被撬；两者都规整 = 去查渲染或 JIT 那条线
- 同步修正：生成包携带 `spawnGameTime`，客户端按 `expectedAge()` 快进补齐服务端在生成 tick 已走的那一步；暂停时不同步
- 未做：用户函数、数组/字符串、`%`/`pow`/`atan2`、每个粒子的状态槽、玩家脚本目录、符文板 UI、`on hit` 的逐粒子版本（`p.hit`）

---

## 9. 验收清单（本次全部可执行）

| # | 操作 | 期望 |
| --- | --- | --- |
| 1 | `/spell debug spawn fire 1024` | 火色粒子团，按寿命消失 |
| 2 | `/spell debug spawn water 4096 5` | 4096 粒不卡；stats 显示 4096 |
| 3 | 连续 spawn 65 次 | 第 65 次被拒并提示上限 |
| 4 | `/spell debug hash`（服务端） vs 客户端 | 100 步后一致 |
| 5 | `/spell debug stats` | p50/p99 有数，单步 ≤ 0.5 ms（4096 粒） |
| 6 | `/wmfx burst 262144` + 法术同时 | 法术优先，整体仍顺滑 |
| 7 | 退出重进世界 | 无残留云（纯内存，不落盘） |
| 8 | 关闭法术 | 元素场 `adv/flow/fluid` 的 p50/p99 与改动前一致 |
| 9 | `/spell debug anchor fire 256` → 走远 100 格 → 回来 | 走远时粒子消失（看不见），服务端 `stats` 仍显示这朵云在推进；回来 0.5 s 内重新出现且**位置不变**（常驻云静止）；粒子不自己飘走 |
| 10 | 常驻云 → 走到区块外让它卸载（视距调小或用 `/forceload` 反操作）再回来 | 卸载期间服务端 `stats` 的云数不变（冻结，不推进）；`latest.log` 无 `dropped`；回来继续推进 |
| 11 | 一次性云 → 立刻把视距调到 2 走到区块外 | 日志出现 `dropped: chunk (x, z) not loaded`，`stats` 云数减少，客户端无残留 |
| 12 | `/spell debug run meteor`（站在空地上） | 火球从头顶砸向地面，**落地那一下**炸成一团火 + 一片土，之后不再反复爆；6 s 后消散时留一口烟；日志出现 `collision=lazy` |
| 13 | `run meteor` 对着水/岩浆（穿方块元素改 `element: air` 的脚本变体） | 气元素直接穿地不掉头，说明碰撞策略是按元素走的、不是硬编码 |

---

## 10. 风险与待定

| 风险 | 说明 | 应对 |
| --- | --- | --- |
| 服务端步进成本 | burst 实测只覆盖渲染；`noise3` + 将来碰撞的成本未量 | P1 就把 p50/p99 打出来；超 0.5 ms/云先优化噪声查表，再上错峰 |
| 双端浮点一致性 | 同一段 Java 在两 JVM 上按 IEEE 754 双精度运算结果一致；但 `Math.pow`/`sin` 等库函数**不保证** | 定律里只用 `+ - * /` 与 `sqrt`；`sin/cos` 留给渲染，不参与物理 |
| 客户端相位差 | 服务端与客户端 `gameTime` 可能差 1 tick | hash 比对按「同一步号」比较，不做逐 tick 对齐 |
| 元素颜色缺失 | 非基础元素只能给回退色 | P0/P1 接受；M0 补 `color` 后替换 |
| `MAX_DRAW` 竞争 | 法术 + burst + 光点同时打满 | 法术优先（既有设计），光点整组让位；观感 M5 截图确认 |
| 内存 | 64 云 × 4096 粒 ≈ 15 MB 服务端 + 客户端镜像 | 可接受；上限就是上限，不做动态缩容 |

**待定项**

1. `gravity/volatility` 临时常量表的最终归属（P4 迁移进 `elements.json`）。
2. 客户端实例填充是「每帧重建」还是「每步重建 + 着色器插值」——P1 先按 §4.3 每 tick 写，实测后若开销高再改。

---

## 附录 A · 常量表

| 常量 | 值 | 位置 |
| --- | --- | --- |
| `MAX_CLOUD_PARTICLES` | 4096 | `SpellCloudManager` |
| `MAX_CLOUDS_PER_LEVEL` | 64 | 同上 |
| `MAX_SIM_PARTICLES_PER_LEVEL` | 262,144 | 服务端模拟池（独立于渲染池） |
| `MAX_ANCHORED_CLOUDS_PER_LEVEL` | 32 | 常驻云数量上限 |
| `VISIBILITY_SYNC_TICKS` | 10 | 玩家↔云可见性核对周期 |
| `STEP_TICKS` / `DT` | 2 / 0.1 s | `Laws` |
| 粒子尺寸 | `0.06 + 0.02·∛amount` | `SpellCloudClient` |
| `MAX_DRAW` | 262,144 | `SparkBatch`（既有） |
| burst / persist 区 | 262,144 / 65,535 | `SparkBatch`（既有） |
| 广播半径 | 64 格 | 复用 `SparkFx` |
| 协议版本 | `"6"` | `ModNetwork` |

## 附录 B · 与规格的对应

| 本文 | spec v1.1 |
| --- | --- |
| §3 数据结构 | §7.1 粒子状态 |
| §4 六定律 | §7.2 |
| §4.2 确定性 | §6.6 求值 / §7.2 备注 |
| §4.3 插值 | §7.8 客户端视觉 |
| §6 渲染接入 | §7.8 |
| §5 调度与预算 | §11 性能预算 |
| §3.2 准入上限 | §7.7 资源上限 |
| P2 形状采样 | §6.9 |
| P3 伤害 | §7.5（单次命中 + `dps` + 原版 `hurt`） |
