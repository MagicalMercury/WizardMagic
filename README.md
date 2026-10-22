# Wizard Magic

Minecraft **1.20.1** 魔法主题模组，基于 **Forge 47.1.3**（通过 NeoForged 的 ModDevGradle `legacyforge` 插件构建）。

## 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17（toolchain） | 本机可装 17/21，Gradle 本体用 21 亦可，编译目标由 toolchain 固定为 17 |
| Gradle | 8.14.5 | 由 wrapper 自动下载 |
| 网络 | `neoforged.forgecdn.net` 可直连 | ModDevGradle/FG 所需的 `net.neoforged:*` 由该 CloudFront 镜像提供 |

`JAVA_HOME` 必须指向真实存在的 JDK 目录，否则 wrapper 会直接报
`ERROR: JAVA_HOME is set to an invalid directory`。

## 常用命令

| 命令 | 作用 |
|---|---|
| `./gradlew build` | 编译 + 打包 `build/libs/wizardmagic-0.1.0.jar` |
| `./gradlew runClient` | 启动带模组的客户端 |
| `./gradlew runServer` | 启动带模组的服务端（`--nogui`） |
| `./gradlew runData` | 运行数据生成器，输出到 `src/generated/resources` |
| `./gradlew runGameTestServer` | 运行 GameTest 后退出 |
| `./gradlew --refresh-dependencies` | 依赖异常时强制刷新 |

## 项目结构

```
src/main/java/com/wizardmagic/
├── WizardMagic.java        # @Mod 主类，MODID / 注册表 / 事件总线
└── Config.java             # ForgeConfigSpec 配置骨架
src/main/resources/assets/wizardmagic/lang/en_us.json   # 语言文件
src/main/resources/META-INF/mods.toml                   # 元数据（构建时展开 ${}）
src/main/resources/pack.mcmeta
src/generated/resources/   # 数据生成输出（构建时自动纳入）
```

`mods.toml` / `pack.mcmeta` 里的 `${mod_id}`、`${mod_version}` 等由 `build.gradle` 的
`generateModMetadata` 任务从 `gradle.properties` 展开，**不要直接把值写死在模板里**。

## 模组元信息

全部在 `gradle.properties` 中：

```properties
mod_id=wizardmagic
mod_name=Wizard Magic
mod_group_id=com.wizardmagic
mod_version=0.1.0
mod_authors=MagicalMercury
mod_license=All Rights Reserved
```

改 `mod_id` 或 `mod_group_id` 时需同步改三处：Java 包目录、`assets/` 目录、`@Mod` 主类的
`MODID` 常量。

## 下载源配置

`build.gradle` 的 `repositories` 已按此顺序配置（`maven.neoforged.net` 在部分网络下会被污染/不可达）：

| 仓库 | 用途 |
|---|---|
| `bmclapi2.bangbang93.com/maven` | `net.neoforged:forge` 本体镜像（快） |
| `neoforged.forgecdn.net/releases` | **NeoForge 官方 maven 的 CloudFront 全量镜像**（fancymodloader 等） |
| `mavenCentral()` | log4j、cpw.mods、asm 等 |
| `maven.minecraftforge.net` | mcp_config、binarypatcher、accesstransformers 等 |

两个 `net.neoforged.*` 仓库都加了 `content { includeGroupByRegex 'net\\.neoforged.*' }` 过滤：
否则 Gradle 会把 `net.minecraft:client`、`log4j` 等请求也发给它们，卡在连接超时上。

已验证 `neoforged.forgecdn.net` 是 NeoForge 而非 Forge：
`net.neoforged:forge:1.20.1-47.1.106:userdev` 与官方镜像 md5 一致
（`52c12f0bd7fbcad476a6154a59c753ec`），其 `config.json` 含 7 个 `fancymodloader`、0 个 `fmlloader`。

如需改用官方源或本地代理，在 `gradle.properties` 里取消注释 `systemProp.*` 即可。

## 许可

`LICENSE.txt` 为 NeoForged 模板自带的 MIT 许可（仅覆盖模板文件本身）。
模组自身的授权口径见 `gradle.properties` 的 `mod_license`，发布前按需调整。
