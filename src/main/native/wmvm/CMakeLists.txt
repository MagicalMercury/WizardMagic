# WMVM：法术寄存器机的原生内核
#
# 产物：
#   wmvm        共享库（JNI 入口 + C ABI）；给了 WMVM_JDK 才会编 JNI 那一层
#   wmvm_selftest  离机对拍
#
# WMVM_JDK 由 Gradle 用 javaToolchains 解析后传进来（-DWMVM_JDK=<jdk17>），
# 不传就只构建纯 C++ 部分 —— 这样 `cmake -S src/main/native -B build-native`
# 在任何机器上都能直接跑。
add_library(wmvm SHARED src/vm.cpp)
target_include_directories(wmvm
        PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

if(WMVM_JDK)
    target_sources(wmvm PRIVATE jni/wmvm_jni.cpp)
    target_include_directories(wmvm PRIVATE ${WMVM_JDK}/include)
    if(WIN32)
        target_include_directories(wmvm PRIVATE ${WMVM_JDK}/include/win32)
    else()
        target_include_directories(wmvm PRIVATE ${WMVM_JDK}/include/linux)
    endif()
    target_compile_definitions(wmvm PRIVATE WMVM_WITH_JNI=1)
    message(STATUS "wmvm: 带 JNI（JDK = ${WMVM_JDK}）")
else()
    message(STATUS "wmvm: 不带 JNI（没给 -DWMVM_JDK）")
endif()

# JNI 入口是 extern "C" 的 Java_*，Windows 上要把它们导出
set_target_properties(wmvm PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
# 玩家机上没有 MSYS2 的运行时，必须全静态链（否则依赖 libstdc++-6.dll / libwinpthread-1.dll）
set_target_properties(wmvm PROPERTIES PREFIX "")
# Highway 的「向量算子」是 header-only，但运行期分派（SupportedTargets / GetChosenTarget）
# 在 libhwy 里，必须链上
target_link_libraries(wmvm PRIVATE hwy)
if(MINGW)
    target_link_options(wmvm PRIVATE -static -static-libgcc -static-libstdc++)
endif()

add_executable(wmvm_selftest selftest/selftest.cpp)
target_sources(wmvm_selftest PRIVATE src/vm.cpp)
target_include_directories(wmvm_selftest
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(wmvm_selftest PRIVATE hwy)

add_test(NAME wmvm_selftest COMMAND wmvm_selftest)
