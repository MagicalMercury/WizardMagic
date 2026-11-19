# WizardMagic 原生代码（一个 DLL，两个模块）

```
src/main/native/
├── CMakeLists.txt      ← 唯一链接 DLL 的地方；共享头文件也在这里暴露
├── jmath.h             ← 两个模块共用：Java 数学语义端口（floor/round/min/max/饱和转换）
├── wmvm/               ← 法术寄存器机
│   ├── CMakeLists.txt
│   ├── include/wmvm.h  C ABI
│   ├── src/            指令集、标量内核、Highway 向量内核、sin 表、形状采样
│   ├── jni/            JNI 入口（NativeVm 对应的那 9 个 Java_ 符号）
│   └── selftest/       离机对拍 + 基准
└── element/            ← 元素场
    ├── CMakeLists.txt
    ├── src/            平流算法（纯 C++）+ JNI 壳
    └── selftest/       语义边界 + 基准
```

## 三条硬规则

1. **DLL 只在 `native/CMakeLists.txt` 里链接一次**，名字固定 `native.dll`。
   子模块一律编成 OBJECT 库（`wmvm_core` / `wmvm_jni` / `element_core` / `element_jni`）。
   原因：一个 JVM 里同一份 DLL 只能 `System.load` 一次，分散成多个 DLL 迟早会撞
   `UnsatisfiedLinkError: already loaded`。
2. **共享头文件放 `native/` 根下**（现在只有 `jmath.h`），用 `include_directories()` 统一暴露。
   模块私有的东西放各自的 `src/`，不要跨模块 `../` 去捞。
3. **逐位一致的两条编译开关不许动**：`-ffp-contract=off`、`-fno-fast-math`。
   另外 `wmvm/src/vm-vec-inl.h` **不能加 include guard / `#pragma once`** —— 它按 HWY 目标
   各展开一次，加了就只剩第一个目标，报错会指到 `vm.cpp:35` 说 `wmvm_vec` 未声明。

## 构建

```bash
cmake -S src/main/native -B build-native            # 纯 C++（无 JNI）
cmake --build build-native --parallel 8
ctest --test-dir build-native --output-on-failure
```

Gradle 侧（推荐）：

```bash
gradlew compileNative      # 带 -DWMVM_JDK，产出 build-native/native.dll
gradlew nativeSelftest     # ctest：wmvm_selftest + element_selftest
gradlew elementAdvectCheck # 元素平流：Java / 原生 / 旧实现 三方逐位对拍 + 成本
gradlew spellNativeCheck spellParityCheck
```

## 两个模块各自的契约

- `wmvm`：跟 `RegVm.java` 逐位一致；`exp/pow/atan2` 未实现时明确报错，Java 侧会提前路由回解释器。
- `element`：跟 `ElementAdvection.java` 逐位一致（`ElementAdvectCheck` 用旧实现的逐行抄件当基准）。
  运行期可以用 `-Dwizardmagic.element.native=0` 强制回 Java。
