/*
 * 元素场原生内核的 JNI 入口。
 *
 * 由顶层 CMakeLists 链进同一个 native.dll —— 一个 Java 进程只 System.load 一次。
 * 指针全部靠 `GetPrimitiveArrayCritical` 钉住（临界区内不做任何 JNI 调用）。
 *
 * 对应 Java：`com.wizardmagic.core.elements.fluid.NativeElement.advect(...)`
 */
#include <jni.h>

#include "element-advect.h"

extern "C"
{

JNIEXPORT jint JNICALL
Java_com_wizardmagic_core_elements_fluid_NativeElement_advect(JNIEnv* env, jclass,
        jintArray jcurrent, jintArray jscratch, jintArray jbase,
        jlongArray jblocked, jbyteArray jpermeability, jint hasBarrier,
        jfloatArray jfu, jfloatArray jfv, jfloatArray jfw,
        jint fluidOriginX, jint fluidOriginY, jint fluidOriginZ,
        jint sectionX, jint sectionY, jint sectionZ, jdouble dt)
{
    if (jcurrent == nullptr || jscratch == nullptr || jbase == nullptr || jblocked == nullptr
            || jfu == nullptr || jfv == nullptr || jfw == nullptr)
    {
        return -1;
    }

    // 逐个钉住；permeability 允许是 null（等价于处处通透）
    // 槽位数必须等于"最多要钉几个数组"：current/scratch/base/blocked/permeability/fu/fv/fw = 8
    struct Pinned
    {
        jarray ref;
        void* ptr;
    };
    Pinned pinned[8] = {};
    int n = 0;
    auto pin = [&](jarray a) -> void* {
        void* ptr = env->GetPrimitiveArrayCritical(a, nullptr);
        if (ptr != nullptr)
        {
            pinned[n].ref = a;
            pinned[n].ptr = ptr;
            ++n;
        }
        return ptr;
    };

    auto* current = static_cast<jint*>(pin(jcurrent));
    auto* scratch = static_cast<jint*>(pin(jscratch));
    auto* base = static_cast<jint*>(pin(jbase));
    auto* blocked = static_cast<jlong*>(pin(jblocked));
    void* permeability = jpermeability == nullptr
            ? nullptr : env->GetPrimitiveArrayCritical(jpermeability, nullptr);
    if (jpermeability != nullptr && permeability != nullptr)
    {
        pinned[n].ref = jpermeability;
        pinned[n].ptr = permeability;
        ++n;
    }
    auto* fu = static_cast<jfloat*>(pin(jfu));
    auto* fv = static_cast<jfloat*>(pin(jfv));
    auto* fw = static_cast<jfloat*>(pin(jfw));

    jint rc = -1;
    if (current != nullptr && scratch != nullptr && base != nullptr && blocked != nullptr
            && fu != nullptr && fv != nullptr && fw != nullptr)
    {
        wmvm::element::Input in{};
        in.current = reinterpret_cast<int*>(current);
        in.scratch = reinterpret_cast<int*>(scratch);
        in.base = reinterpret_cast<const int*>(base);
        in.blocked = reinterpret_cast<const uint64_t*>(blocked);
        in.permeability = static_cast<const uint8_t*>(permeability);
        in.has_barrier = static_cast<int>(hasBarrier);
        in.fluid_u = reinterpret_cast<const float*>(fu);
        in.fluid_v = reinterpret_cast<const float*>(fv);
        in.fluid_w = reinterpret_cast<const float*>(fw);
        in.fluid_origin_x = static_cast<int>(fluidOriginX);
        in.fluid_origin_y = static_cast<int>(fluidOriginY);
        in.fluid_origin_z = static_cast<int>(fluidOriginZ);
        in.section_x = static_cast<int>(sectionX);
        in.section_y = static_cast<int>(sectionY);
        in.section_z = static_cast<int>(sectionZ);
        in.dt = dt;
        // 原样透传编码：bit0 = 有改动，bit1 = 走的是无流短路；负数 = 参数错误
        rc = wmvm::element::advect(in);
    }

    for (int i = n - 1; i >= 0; --i)
    {
        env->ReleasePrimitiveArrayCritical(pinned[i].ref, pinned[i].ptr, 0);
    }
    return rc;
}

}  // extern "C"
