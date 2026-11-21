# Element field native kernels.
#
# Targets:
#   element_core      OBJECT lib: scalar + 4/8-lane (Highway) advect kernels
#   element_jni       OBJECT lib: JNI entry (only when WMVM_JDK is given)
#   element_selftest  offline self-test (pure C++, no JVM)
#
# Shared helpers live in src/element-advect-inl.h; the Highway per-target
# machinery needs them out of the .cpp (foreach_target re-includes the file).
#
# All comments are ASCII on purpose: this file was once written through a
# PowerShell -replace and the encoding got mangled. Keep it plain.
add_library(element_core OBJECT src/element-advect.cpp src/element-advect-simd.cpp)
target_include_directories(element_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
# Highway runtime dispatch (SupportedTargets / GetChosenTarget) lives in libhwy.
target_link_libraries(element_core PUBLIC hwy)

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
    message(STATUS "element: with JNI (JDK = ${WMVM_JDK})")
else()
    message(STATUS "element: no JNI (no -DWMVM_JDK)")
endif()

add_executable(element_selftest selftest/selftest.cpp $<TARGET_OBJECTS:element_core>)
target_include_directories(element_selftest PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(element_selftest PRIVATE hwy)

add_test(NAME element_selftest COMMAND element_selftest)
