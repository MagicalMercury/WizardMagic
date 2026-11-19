# WizardMagic 原生代码
#
#   cmake -S src/main/native -B build-native
#   cmake --build build-native --parallel 8
#   ctest --test-dir build-native --output-on-failure
#
# Gradle 侧：`gradlew compileNative` 会带 `-DWMVM_JDK=<jdk17>` 调上面两条，
# 产物 native.dll 被复制进 jar 的 wizardmagic/native/windows/x86_64/。
#
# 目录约定见 README.md。三条硬规则：
#   1. **DLL 只在这里链接一次**（子模块一律 OBJECT 库）——一个 JVM 只 System.load 一次；
#   2. 共享头文件放 native/ 根下（现在只有 jmath.h），用 include_directories 统一暴露；
#   3. 逐位一致相关：`-ffp-contract=off -fno-fast-math` 不许去掉。
cmake_minimum_required(VERSION 3.20)
project(wizardmagic_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# 逐位一致的前提：不许把 a*b+c 收缩成 FMA、不许 fast-math。
# （GCC/Clang 默认 -ffp-contract=fast，会把两个函数的结果改掉）
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-ffp-contract=off -fno-fast-math)
elseif(MSVC)
    add_compile_options(/fp:strict)
endif()

# 模块间共享的头文件（jmath.h）在这个目录，全部子目录都能直接 #include
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

enable_testing()   # 顶层开，否则 `ctest --test-dir build-native` 一个用例都找不到

add_subdirectory(wmvm)
add_subdirectory(element)

# ---------------------------------------------------------------- 统一产物
# 名字就叫 native：Java 侧加载 /wizardmagic/native/<os>/<arch>/native.dll
set(NATIVE_OBJECTS
        $<TARGET_OBJECTS:wmvm_core>
        $<TARGET_OBJECTS:element_core>)
if(TARGET wmvm_jni)
    list(APPEND NATIVE_OBJECTS $<TARGET_OBJECTS:wmvm_jni>)
endif()
if(TARGET element_jni)
    list(APPEND NATIVE_OBJECTS $<TARGET_OBJECTS:element_jni>)
endif()

add_library(native SHARED ${NATIVE_OBJECTS})

target_include_directories(native
        PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/wmvm/include)

# JNI 入口是 extern "C" 的 Java_*，Windows 上要把它们导出
set_target_properties(native PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
# 玩家机上没有 MSYS2 的运行时，必须全静态链（否则依赖 libstdc++-6.dll / libwinpthread-1.dll）
set_target_properties(native PROPERTIES PREFIX "")
# Highway 的「向量算子」是 header-only，但运行期分派（SupportedTargets / GetChosenTarget）
# 在 libhwy 里，必须链上
target_link_libraries(native PRIVATE hwy)
if(MINGW)
    target_link_options(native PRIVATE -static -static-libgcc -static-libstdc++)
endif()
# 产物放在 build-native/ 根下（Gradle 直接取 build-native/native.dll）
set_target_properties(native PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}
        RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
