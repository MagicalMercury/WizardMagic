# 元素系统性能优化方案（v2）

> 替换原 Phase 0–5 计划（该计划已全部实施完毕）。
> 本轮目标：把「生成慢」和「越玩越慢」压到可接受范围，同时保留全部功能语义。

---

## 0. 现状（实测，2026-10-22）

| 指标 | 数值 | 来源 |
|---|---|---|
| 单 section 生成耗时 | 0.40 ms（fill）+ 0.10 ms（boxFilter）+ 方块卷积/强源 | 离机基准（3000 次迭代） |
| 实际单 section | 约 0.6–0.8 ms | 由 16 chunk/s 反推 |
| 生成吞吐 | **16 chunk/s**（390 chunk / 24 s） | latest.log 13:44:47 → 13:45:11 |
| 视距 10 覆盖时间 | 约 27 s | 441 chunk ÷ 16 |
| 方块刷新入队 | **59,593 次**，其中真正重算 5,031 次 | /element stats |
| 活跃 chunk | 2,145 | /element stats |
| 存档体积 | 32 MB / 5,285 chunk = **6.2 KB/chunk**；元素仅占 0.4% | region 解析 |

结论：**存档体积已不是问题**（元素稳态不写盘 + region 重排已生效）；本轮只解决 CPU 与调度。

---

## 1. 瓶颈拆解

单 section（0.6–0.8 ms）大致构成：

| 阶段 | 成本 | 说明 |
|---|---|---|
| `fillFromNoiseAndBiome` | 0.40 ms | 64 粗格 × 4 元素 fbm，再对 16³×4 做三线性插值 |
| `ElementConvolution.apply` 方块采样 | 0.10 ms | 64 粗格 × 8 采样点 = 512 次 `getBlockState` |
| `boxFilter` | 0.10 ms | 64 格 × 125 邻域 × 4 元素 ≈ 32,000 次 `sqrt` |
| 卷积写回 16³×4 | 0.05 ms | 又一遍三线性插值 + `addBase` |
| `BlockElementInfluence` | 0.00–0.30 ms | 4096 次扫描；有岩浆/火时逐源球体展开 |
| 调度与方块刷新 | 不确定 | 5 万次无效入队、5031 次白算 |

关键观察：**噪声和卷积各自对 16³ 做了一整遍插值**，且每次 `trilinear()` 都重算 `Math.floor`、坐标除法与权重，还在 4 个元素的循环里重复算同一套坐标。

---

## 2. 优化项

### P0-1　合并为单一插值管线

**问题**：`fillFromNoiseAndBiome` 对 16³ 插值一遍（写 `base`），`ElementConvolution.apply` 又对 16³ 插值一遍（`addBase`），两次扫描、两套权重。两者粗格完全对齐（都是 `COARSE=4, CELL=4`），插值又是线性运算。

**做法**：

```text
coarse[t][x][y][z] = biomeBase[t](粗格) + noiseCoarse[t] * 35 + heightWind(粗格中心y) + convCoarse[t]
然后一次三线性插值 → 16³ → 直接写 base
```

- 高度风 `(worldY - 63) * 0.5` 在粗格中心取值后插值，误差 < 1（斜率 0.5/格 × 4 格采样间距），可接受；若要求精确，可在插值后逐格补 0.5 × (y - 粗格中心 y)。
- 强源贡献仍是逐格后加（有独立半径语义，见 P0-4），不进粗格。

**收益**：少一遍 16³ 扫描与插值，预计 **0.15–0.2 ms/section**。
**风险**：浮点顺序改变，个别格 ±1。**必须把 `CURRENT_BASE_VERSION` 升到 5**，旧存档自动重算。

---

### P0-2　分离式插值 + 权重预计算

**问题**：`trilinear()` 每格调用一次（16³ × 4 元素 = 65,536 次），每次都做 `Math.floor`、除法、8 次数组索引、7 次 lerp；且 4 个元素循环共用同一坐标却各算一遍。

**做法**：

1. 预计算每个轴的表：`int[16] idx0/idx1`、`double[16] w`，消除逐格除法和 `floor`；
2. 分离式插值：先沿 x 从 `4×4×4 → 16×4×4`，再沿 y 到 `16×16×4`，最后沿 z 到 `16×16×16`；
3. 每个元素各自的中间缓冲复用（`ThreadLocal<double[]>`），不每 section 分配。

每元素 lerp 次数：`4·4·16×1`（x）+ `4·16·16×1`（y）+ `16·16·16×1`（z）≈ 5.4K，替代原来的 28.7K；索引/权重从 65,536 次计算降到 16 次/轴。

**收益**：插值部分 **5–8×**，预计贡献 0.25–0.3 ms/section。
**风险**：低（纯计算重写，结果与原实现等价，允许 ±1 舍入差）。

---

### P0-3　boxFilter 权重表

**问题**：125 个邻域的 `1/(1+距离)` 是常量，却在 64 格 × 4 元素里重复 `sqrt` 32,000 次。

**做法**：

- 静态 `double[5][5][5] WEIGHTS` 一次算好；
- 边缘越界时跳过邻域，分母改为按位置预计算的 `double[4][4][4] NORM`（64 个常量），而不是循环内求和。

**收益**：**0.05–0.10 ms/section**。
**风险**：无。

---

### P0-4　强源优化

**问题**：每个非空气 section 无条件扫 4096 格；有源时对每源展开球体（r=6 约 904 格），每格 `sqrt + exp`。

**做法**：

1. **快速排除**：`section.maybeHas(state -> hotSource(state) != null)`，无源直接返回（地下纯石 section 这一项归零）；
2. **查表**：按半径预生成 `double[RADIUS+1][RADIUS+1][RADIUS+1]` 衰减表（r ≤ 6，只有 3 张表），内层只做数组索引；
3. **球体偏移预计算**：每个半径一份 `int[][] offsets`，避免每次重算 dx/dy/dz 的平方和。

**收益**：无源 section 接近 0；有源 section **5–20×**（0.3 ms → 0.02–0.06 ms）。
**风险**：低。**不采用**「先 splat 到粗网格」方案——会破坏「越靠近岩浆火越高」的逐格梯度。

---

### P1-1　方块刷新过滤（保留事件，只砍无效工作）

**前提**：**不削减 `NeighborNotifyEvent` 的监听范围**。后续会有大量「方块更新 → 元素计算」的玩法，砍事件等于自断后路。只做三件事：

1. **跳过未生成元素场的 chunk**：`storage == null || !storage.isGenerated()` 直接返回。世界生成阶段放置的方块属于这一类——它们随后会走正常生成，`base` 本来就算对了。这一条预计消掉 59,593 次入队里的绝大部分。
2. **按边界决定刷新几个 section**：变化点距 section 边界 > 8 格（= 卷积影响半径）时只刷自身；否则刷相邻 ±1。当前无条件刷 3 个，浪费 2/3。
3. **同 section 冷却**：同一 section 两次刷新间隔 < 20 tick 时合并（`Map<SectionKey, Long> lastRefresh`），防止流水/火焰蔓延刷屏。

> 说明：外部评审建议「比较变化前后的 `BlockElementWeights.contribution`」，**不可行**——`NeighborNotifyEvent` 只携带变化后的 `BlockState`（`getState()`），旧状态取不到。若确实需要「权重没变就不刷新」，得改挂 `BlockEvent.BreakEvent` / `EntityPlaceEvent` 的前置事件并自行缓存旧值，属独立工作。

**收益**：5,031 次白算 → 预计数百次，省 2–4 s CPU，并消除「越玩越慢」。
**风险**：低。

---

### P1-2　调度与吞吐

1. **预算改为毫秒制**：`generationTimeBudgetMs = 2.0`，循环内 `System.nanoTime()` 判断，避免单 tick 尖峰（当前 8 section × 0.8 ms = 6.4 ms 固定尖峰）；
2. **空队列 ×2**（本轮明确要求）：`ElementRefreshQueue.pending() == 0` 时预算 ×2，有刷新积压时回落基础值——把闲置预算让给生成；
3. **队列按玩家距离优先**：生成队列从 `ArrayDeque`（严格 FIFO）改为按「到玩家距离」排序的优先队列（每 20 tick 重排一次，避免每 tick 排序）；当前远处的 chunk 会堵住脚下的 chunk；
4. **section 顺序按高度靠近玩家优先**：同一 chunk 内先算离玩家 y 最近的 section（地表优先），深层 section 延后。

**收益**：体感显著（脚下的 chunk 先有数据），吞吐约 +20–30%（少做无效尖峰）。
**风险**：低；距离优先可能导致远处长时间不生成，提供配置开关。

---

### P1-3　预生成范围收窄（方案 C）

**问题**：`ChunkEvent.Load` 对**所有**加载 chunk 入队，视距 10 就是 441 个 chunk × ~10 section ≈ 4,400 section。

**做法**：

- 新增 `elements.generationRadius`（默认 5，单位 chunk，指玩家周围）；
- 只在半径内预生成；半径外的 chunk **不入队**，等玩家靠近或首次 `ElementAccess.get/set` 时**惰性生成**（`ElementAccess.field()` 已支持）；
- 惰性生成仍受毫秒预算约束。

**收益**：视距 10 时预生成量 441 → 121 chunk（**3.6×**）。
**风险**：远处第一次 `get` 有 0.4 ms 延迟；`/element stats` 需要显示「惰性生成次数」以便观察。

---

### P2-1　多线程（ForkJoinPool + work stealing）

**可行性结论**：可以，但必须严格限定分工。

| 可在 worker 执行 | 依据 |
|---|---|
| `section.getBlockState(x,y,z)` | `PalettedContainer` 只读数组访问，chunk 加载后不变 |
| `chunk.getNoiseBiome(x,y,z)` | 只读 biome paletted container |
| `level.getSeed()` | 纯字段 |
| `ElementNoise.fbm`、三线性插值、boxFilter、强源展开 | 纯函数 / 独占缓冲 |
| 写任务私有的 `byte[]` 结果 | 无共享 |

| 必须主线程 | 原因 |
|---|---|
| `level.getBlockState/getChunk` | 走 `ChunkMap`，有锁与主线程假设 |
| 写 `ElementField` / capability | 共享可变状态 |
| `chunk.setUnsaved(true)` | 主线程 API |

**架构**：

```text
主线程每 tick：
  ├─ 取 K 个 (chunk, sectionIndex) → ForkJoinPool.submit(SectionTask)
  │     worker：读 section → fill + 卷积 + 强源 → 产出 byte[4][4096]
  └─ 消费上一 tick 的 Future → 写入 ElementField → setUnsaved(true)
```

- 线程数 `clamp(cores/2, 2, 6)`，用独立 pool（不占 `commonPool`，避免与其它 mod 抢）；
- 任务内**只持有 chunk/section 引用**，绝不调用 Level 的可变 API；
- chunk 在 worker 期间被卸载：对象仍被引用，读到旧数据不崩，本次结果作废（生成只做一次）。

**分两步**：先并「纯噪声」（`ElementNoise` + 粗格，零 MC 依赖，风险近零），验证收益后再并入卷积读方块。

**收益**：4 线程下 **2.5–3.5×**（主线程写回 ≤ 0.05 ms/section 不成为瓶颈）。
**风险**：中。需要把 `fillFromNoiseAndBiome` 从「直接写 field」重构为「返回数据」，并引入任务生命周期管理。若 P0/P1 后已达到目标，**可以不启用**。

---

### P2-2　数据布局

- `BlockElementWeights.contribution(BlockState)` 改为 `contribution(BlockState, int[] out)` 或返回打包 `int`，消除 512 次/section 的 `int[4]` 分配；
- 粗格 `int[4][4][4][4]`、平滑中间量改用 `ThreadLocal` 复用；
- 若 profile 显示方块分类仍是大头，再加 `BlockState → 贡献` 的小型缓存（`Reference2ByteMap`）。

**收益**：GC 压力下降，单 section 再省 0.03–0.08 ms。

---

## 3. 实施顺序

| 步骤 | 内容 | 验收信号 |
|---|---|---|
| 0 | **加分段计时**：fill / 卷积 / boxFilter / 强源各自计时，`/element stats` 输出最近 100 section 的平均值 | 能看出各阶段真实占比，避免优化错对象 |
| 1 | P0-1 合并插值 + P0-2 分离式 + 权重表 | 单 section ≤ 0.35 ms（离机基准） |
| 2 | P0-3 boxFilter 权重表 | 再降 0.05–0.10 ms |
| 3 | P0-4 强源 maybeHas + 查表 | 无源 section 强源成本 ≈ 0 |
| 4 | P1-1 刷新过滤 | 入队次数下降 ≥ 90%，无功能丢失 |
| 5 | P1-2 毫秒预算 + 距离优先 + 空队列 ×2 | MSPT 峰值 < 45 ms，生成 ≥ 30 chunk/s |
| 6 | P1-3 预生成范围（方案 C） | 视距 10 覆盖时间 < 15 s |
| 7 | 实测评估是否上 P2-1 多线程 | 若 ≥ 40 chunk/s 且 MSPT 稳定，则不做 |

每完成一步都先 `./gradlew build`，再进游戏验证数值（同一坐标优化前后对比，允许 ±1）。

---

## 4. 验收标准

| 项目 | 目标 |
|---|---|
| 单 section 生成 | ≤ 0.35 ms（当前 0.6–0.8 ms） |
| 生成吞吐 | ≥ 30 chunk/s（当前 16） |
| 视距 10 覆盖 | ≤ 15 s（当前 27 s） |
| 方块刷新入队 | 同类场景下降 ≥ 90% |
| MSPT 峰值 | < 45 ms（不出现明显卡顿） |
| 元素数值 | 同坐标优化前后 ±1 内；`BaseVersion` 升到 5 后旧存档自动重算 |
| 存档体积 | 不回退（元素占比仍 < 1%） |
| 功能 | 强源半径（岩浆 d3 / 火 d5 / 其余 r6）、有限扩散、20 tick 冷却全部保持 |

---

## 5. 风险与回滚

| 风险 | 处置 |
|---|---|
| 插值合并引起数值漂移 | `BaseVersion` 升级，旧存档重算；对比同坐标差 ≤ 1 才放行 |
| 多线程竞态 | 限定「只读 section + 独占输出」；先只并噪声；出问题直接关 `elements.asyncGeneration` 开关 |
| 距离优先导致远处久不生成 | 配置 `generationRadius` 可关（设 -1 = 全量预生成） |
| 刷新过滤漏掉真实变化 | 保留全量事件监听，只做「未生成 / 边界 / 冷却」三重过滤；可用 `/element regenerate` 手工兜底 |
| region 再次膨胀 | `tools/repack_region.py` 定期回收扇区空洞（无损，逐 chunk 校验） |
