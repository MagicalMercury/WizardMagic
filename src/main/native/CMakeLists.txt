# WizardMagic 原生代码（顶层）
#
#   cmake -S src/main/native -B build-native -DCMAKE_BUILD_TYPE=Release
#   cmake --build build-native -j
#
# 以后加别的东西（元素场内核、流体）就在这里 add_subdirectory。
# 用法约定：编译器一律从 PATH 里找，不写死任何绝对路径。
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

enable_testing()   # 顶层开，否则 `ctest --test-dir build-native` 一个用例都找不到

add_subdirectory(wmvm)
