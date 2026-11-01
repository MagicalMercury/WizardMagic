# 坩埚状态 API

核心模组对外提供的一组只读契约，供**内容模组**实现状态、供**客户端渲染器**绘制液面与呼吸动画。
API 本身是纯 Java（`com.wizardmagic.api.crucible` 不 import 任何 Minecraft 类），因此可以在
服务端、客户端、单元测试里同样使用。

## 谁负责什么

| 角色 | 负责 | 不负责 |
|---|---|---|
| 核心模组 | 只读契约、默认液面策略、颜色回退、解析与缓存、时间→相位换算 | 不认识具体方块/方块实体，不提供模型与纹理 |
| 内容模组 | 注册方块与方块实体、实现状态源、真实颜色来源、模型 JSON 与纹理、内腔几何数值 | 不重复实现液面换算与缓存逻辑 |
| 渲染器 | 每帧读缓存 → 画液面 / 着色 / 驱动呼吸 | 不直接读流体网络、NBT 或颜色配置 |

## 类型清单

| 类型 | 作用 | 谁实现 | 谁调用 |
|---|---|---|---|
| `CrucibleState` | 只读状态契约（液量、容量、颜色、运行、时间、空判定） | 内容模组的快照类 | 核心解析器 / 缓存 |
| `CrucibleStateSource` | 能力接口，返回 `CrucibleState` | 内容模组的方块实体 | 渲染器（`instanceof` 探测） |
| `FluidSurfaceStrategy` | 液面高度策略 | 核心默认值，可选覆写 | 解析器 / 缓存 |
| `FluidSurfaceStrategies` | `LINEAR` / `CLAMPED` / `clamped(min,max)` | 核心提供 | 内容模组选用 |
| `FluidColorStrategy` | 液体颜色来源 | **内容模组** | 解析器 / 缓存 |
| `FluidColorStrategies` | 回退色 `0xFF8B4DD6`、空安全包装 | 核心提供 | 内容模组 / 渲染器 |
| `CrucibleRenderState` | 解析结果（空、比例、液面 y、颜色、运行） | 核心产出 | 渲染器 |
| `CrucibleRenderResolver` | 状态 + 策略 → 渲染结果（无缓存） | 核心提供 | 一次性查询 |
| `CrucibleRenderCache` | 每帧缓存，签名变化才重算 | 核心提供 | 渲染器 |
| `CrucibleAnimation` | 时间 → `[0,1)` 相位，**不含曲线** | 核心提供 | 渲染器 |

## 两种接入方式

内容模组按需要的保真度二选一，不要混用：

| 方式 | 液面怎么来 | 需要 Java 吗 | 适用 |
|---|---|---|---|
| 纯资源 | 8 档 `blockstate`（`level=0..8`）+ 对应模型 | 不需要 BE 渲染器 | 快速接入、不需要逐帧平滑 |
| 动态渲染 | BE 渲染器按 `surfaceHeight` 缩放液柱 | 需要 | 液面连续变化、颜色自定义 |

**方式一**用现成的 8 个变体，`blockstate` 的 `level` 属性直接选模型；颜色仍可走
`ColorHandlerEvent.Block` + 面里的 `tintindex: 0`。
**方式二**用本 API，液面高度由状态算出，不需要 8 个模型变体（只保留 `crucible.json` 外壳即可）。

## 接入步骤（动态渲染）

### 1. 方块实体实现状态源

```java
public class CrucibleBlockEntity extends BlockEntity implements CrucibleStateSource {

    private int amount;
    private int capacity = 8000;
    private int colorArgb = 0xFF8B4DD6;
    private boolean running;

    // 写入数据时顺手让缓存失效（可选，签名比较已能覆盖大部分情况）
    public void setAmount(int amount) {
        this.amount = amount;
        setChanged();
    }

    @Override
    public CrucibleState crucibleState() {
        long time = level == null ? 0L : level.getGameTime();
        return new Snapshot(amount, capacity, colorArgb, running, time);
    }

    /** 不可变快照；record 足够，每帧新建一个也只是小对象。 */
    private record Snapshot(int amount, int capacity, int colorArgb,
                            boolean running, long time) implements CrucibleState {
        @Override public int fluidAmount() { return amount; }
        @Override public int fluidCapacity() { return capacity; }
        @Override public int fluidColorArgb() { return colorArgb; }
        @Override public boolean running() { return running; }
        @Override public long animationTime() { return time; }

        // 想支持非线性容器时覆写 fillFraction()；默认实现已按比例夹紧到 [0,1]
    }
}
```

### 2. 选定策略与几何

```java
// 几何来自模型：内腔底 y=3.03，顶 14.03 ⇒ 深度 11
private static final float CAVITY_BOTTOM = 3.03F;
private static final float CAVITY_HEIGHT = 11.0F;

private static final FluidSurfaceStrategy SURFACE = FluidSurfaceStrategies.CLAMPED;

// 真实颜色来源由内容模组决定；null 时核心回退到 0xFF8B4DD6
private static final FluidColorStrategy COLOR = state -> switch (liquidKindOf(state)) {
    case LAVA -> 0xFFFF7A18;
    case MANA -> 0xFF6E4BFF;
    default -> FluidColorStrategies.FALLBACK_ARGB;
};
```

### 3. 渲染器里每帧一次

```java
public class CrucibleRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    private final Map<BlockPos, CrucibleRenderCache> caches = new HashMap<>();

    @Override
    public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (!(be instanceof CrucibleStateSource source)) {
            return;                                  // 不是坩埚就什么都不画
        }

        CrucibleRenderCache cache = caches.computeIfAbsent(be.getBlockPos(), p -> new CrucibleRenderCache());
        CrucibleRenderState rs = cache.update(
                source.crucibleState(), SURFACE, COLOR, CAVITY_BOTTOM, CAVITY_HEIGHT);

        long gameTime = be.getLevel() == null ? 0L : be.getLevel().getGameTime();
        float phase = CrucibleAnimation.phase(gameTime, partialTick, 32.0F);   // 8 帧 × 4t

        // 呼吸曲线在渲染器里，不在 API 里
        float breath = rs.running() ? 0.5F + 0.5F * (float) Math.sin(phase * Math.PI * 2.0) : 0.0F;

        if (!rs.empty()) {
            drawFluidSurface(pose, buffers, packedLight,
                    rs.surfaceHeight() / 16.0F,   // 模型单位 → 方块坐标
                    rs.colorArgb(),
                    breath);
        }
        drawRuneOverlay(pose, buffers, packedLight, breath);   // 运行态法阵亮度
    }
}
```

`drawFluidSurface` / `drawRuneOverlay` 是内容模组自己的绘制实现：贴图、UV、混合模式都由它决定。

## 调用频率与失效

| 调用 | 频率 | 重算条件 |
|---|---|---|
| `CrucibleStateSource#crucibleState()` | 每帧最多 1 次 | 每次 `update()` 都会调 |
| `CrucibleRenderCache#update(...)` | 每帧 1 次 | 命中缓存时零策略调用 |
| `FluidSurfaceStrategy#surfaceHeight` | 只在缓存未命中时 | 液量 / 容量 / 颜色 / 运行状态任一变化，或 `invalidate()` |
| `FluidColorStrategy#colorArgb` | 只在缓存未命中时 | 同上 |
| `CrucibleAnimation#phase(...)` | 每帧 1 次 | 纯算术，无分配 |

缓存签名是四个值：`fluidAmount`、`fluidCapacity`、`fluidColorArgb`、`running`。
颜色如果来自会变的配置对象（签名捕捉不到），在写入时调用 `cache.invalidate()`。

自检里跑了 60 帧，策略调用 1 次；改液量后 2 次；`invalidate()` 后 3 次 —— 见
`verify/crucible-api/CrucibleApiSelfCheck.java`。

## 与现有模型资源的对应

| 模型侧 | API 侧 |
|---|---|
| 内腔底 `y=3.03`、深 `11` | `cavityBottom` / `cavityHeight` 常量 |
| `crucible_fluid_1..8` 的 `to.y` 4.405 … 14.03 | 走动态渲染时不再需要，改由 `surfaceHeight` 连续计算 |
| 液体面 `tintindex: 0` | `rs.colorArgb()` |
| `crucible_active.png` 8 帧 × 4t = 32t | `phase(..., 32.0F)`，呼吸曲线留给渲染器 |
| `blockstate` 的 `active` 属性 | `rs.running()` |

## 跑自检

```sh
JDK="C:/Program Files/Java/jdk-17.0.20.1/bin"
"$JDK/javac" -encoding UTF-8 -d verify/crucible-api/out \
  src/main/java/com/wizardmagic/api/crucible/*.java verify/crucible-api/CrucibleApiSelfCheck.java
"$JDK/java" -cp verify/crucible-api/out CrucibleApiSelfCheck
```

期望输出 `checks=29 failures=0`。
