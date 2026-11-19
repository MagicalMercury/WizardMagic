# 元素场原生内核
#
# 产物（都只编 OBJECT，链接成 DLL 的事由上层 native/CMakeLists.txt 做一次）：
#   element_core      OBJECT 库
#   element_jni       OBJECT 库 —— JNI 入口，只有给了 WMVM_JDK 才存在
#   element_selftest  离机自检（纯 C++，不需要 JVM）
#
# 目录：
#   src/            实现（element-advect 是纯算法；element-jni 是 JNI 壳）
#   selftest/       自检
#
# 共用的 `jmath.h`（Java 数学语义端口）在 native/ 根下，由根 CMakeLists 的
# include_directories 统一暴露 —— 本文件不需要再去 ../wmvm/src 里翻。
add_library(element_core OBJECT src/element-advect.cpp)
target_include_directories(element_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)

if(WMVM_JDK)
    add_library(element_jni OBJECT src/element-jni.cpp)
    target_include_directories(element_jni
            PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
            PRIVATE ${WMVM_JDK}/include)
    if(WIN32)
        target_include_directories(element_jni PRIVATE ${WMVM_JDK}/include/win32)
    else()
        target_include_directories(element_jni PRIVATE ${WMVM_JDK}/include/linux)
    endif()
    message(STATUS "element: 带 JNI（JDK = ${WMVM_JDK}）")
else()
    message(STATUS "element: 不带 JNI（没给 -DWMVM_JDK）")
endif()

add_executable(element_selftest selftest/selftest.cpp $<TARGET_OBJECTS:element_core>)
target_include_directories(element_selftest PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

add_test(NAME element_selftest COMMAND element_selftest)
