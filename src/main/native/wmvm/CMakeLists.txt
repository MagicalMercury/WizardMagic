# wmvm：法术寄存器机（VM）
#
# 产物（都只编 OBJECT，链接成 DLL 的事由上层 native/CMakeLists.txt 做一次）：
#   wmvm_core      OBJECT：指令集 / 标量内核 / Highway 向量内核（不含 JNI）
#   wmvm_jni       OBJECT：JNI 入口，只有给了 WMVM_JDK 才存在
#   wmvm_selftest  离机对拍
#
# WMVM_JDK 由 Gradle 用 javaToolchains 解析后传进来（-DWMVM_JDK=<jdk17>），
# 不传就只构建纯 C++ 部分 —— 这样 `cmake -S src/main/native -B build-native`
# 在任何机器上都能直接跑。
add_library(wmvm_core OBJECT src/vm.cpp)
target_include_directories(wmvm_core
        PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(wmvm_core PUBLIC hwy)

if(WMVM_JDK)
    add_library(wmvm_jni OBJECT jni/wmvm_jni.cpp)
    target_include_directories(wmvm_jni
            PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include
            PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
            PRIVATE ${WMVM_JDK}/include)
    if(WIN32)
        target_include_directories(wmvm_jni PRIVATE ${WMVM_JDK}/include/win32)
    else()
        target_include_directories(wmvm_jni PRIVATE ${WMVM_JDK}/include/linux)
    endif()
    target_compile_definitions(wmvm_jni PRIVATE WMVM_WITH_JNI=1)
    message(STATUS "wmvm: 带 JNI（JDK = ${WMVM_JDK}）")
else()
    message(STATUS "wmvm: 不带 JNI（没给 -DWMVM_JDK）")
endif()

add_executable(wmvm_selftest selftest/selftest.cpp $<TARGET_OBJECTS:wmvm_core>)
target_include_directories(wmvm_selftest
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(wmvm_selftest PRIVATE hwy)

add_test(NAME wmvm_selftest COMMAND wmvm_selftest)
