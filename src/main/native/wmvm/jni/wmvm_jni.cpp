/*
 * JNI 入口。规矩：
 *   - 只有 extern "C" 的 Java_* 函数，不把 C++ 对象跨边界传；
 *   - 大数组用 GetPrimitiveArrayCritical 钉住（零拷贝），临界区里**不调任何 JNI 函数**；
 *   - 出错只返回编码后的负数，异常由 Java 侧按 pos 表构造（措辞与 Java 后端一致）。
 *
 * 对应 Java 类：com.wizardmagic.core.spell.script.NativeVm
 */
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <mutex>

#include "wmvm.h"

#if !defined(_WIN32)
#define JNICALL_EXT
#endif

namespace
{

/** sin 表必须活到进程结束（Java 侧只灌一次） */
float* g_sin = nullptr;
std::mutex g_sin_mutex;

/** 临界区配对的辅助：拿不到指针就把该数组整个当失败 */
struct Pinned
{
    JNIEnv* env;
    jarray array;
    void* ptr;
};

}  // namespace

extern "C"
{

JNIEXPORT jint JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeInit(JNIEnv*, jclass)
{
    return wmvm_init();
}

JNIEXPORT jstring JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeTarget(JNIEnv* env, jclass)
{
    return env->NewStringUTF(wmvm_target_name());
}

JNIEXPORT jint JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeLanes(JNIEnv*, jclass)
{
    return wmvm_lanes();
}

JNIEXPORT jint JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeForceLanes(JNIEnv*, jclass, jint lanes)
{
    return wmvm_force_lanes(static_cast<int>(lanes));
}

JNIEXPORT jint JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeSetSinTable(JNIEnv* env, jclass, jfloatArray arr)
{
    if (arr == nullptr)
    {
        return -1;
    }
    const jsize n = env->GetArrayLength(arr);
    if (n != 65536)
    {
        return -2;
    }
    std::lock_guard<std::mutex> lock(g_sin_mutex);
    if (g_sin == nullptr)
    {
        g_sin = static_cast<float*>(std::malloc(sizeof(float) * 65536));
        if (g_sin == nullptr)
        {
            return -3;
        }
    }
    auto* src = static_cast<float*>(env->GetPrimitiveArrayCritical(arr, nullptr));
    if (src == nullptr)
    {
        return -4;
    }
    std::memcpy(g_sin, src, sizeof(float) * 65536);
    env->ReleasePrimitiveArrayCritical(arr, src, JNI_ABORT);
    return wmvm_set_sin_table(g_sin, static_cast<int>(n));
}

JNIEXPORT jlong JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeCreate(JNIEnv* env, jclass,
        jintArray code, jdoubleArray consts)
{
    if (code == nullptr)
    {
        return 0;
    }
    auto* c = static_cast<jint*>(env->GetPrimitiveArrayCritical(code, nullptr));
    if (c == nullptr)
    {
        return 0;
    }
    // consts 可以没有（程序不用常量）
    auto* k = consts == nullptr
            ? nullptr : static_cast<jdouble*>(env->GetPrimitiveArrayCritical(consts, nullptr));
    WmvmProgram* p = wmvm_program_create(reinterpret_cast<const int32_t*>(c),
            static_cast<int>(env->GetArrayLength(code)),
            reinterpret_cast<const double*>(k),
            k == nullptr ? 0 : static_cast<int>(env->GetArrayLength(consts)));
    if (k != nullptr)
    {
        env->ReleasePrimitiveArrayCritical(consts, k, JNI_ABORT);
    }
    env->ReleasePrimitiveArrayCritical(code, c, JNI_ABORT);
    return reinterpret_cast<jlong>(p);
}

JNIEXPORT void JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeDestroy(JNIEnv*, jclass, jlong handle)
{
    wmvm_program_destroy(reinterpret_cast<WmvmProgram*>(static_cast<intptr_t>(handle)));
}

JNIEXPORT jint JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeRun(JNIEnv* env, jclass, jlong handle,
        jint from, jint to,
        jdoubleArray jpx, jdoubleArray jpy, jdoubleArray jpz,
        jdoubleArray jvx, jdoubleArray jvy, jdoubleArray jvz,
        jfloatArray jenergy,
        jdoubleArray jadx, jdoubleArray jady, jdoubleArray jadz,
        jdoubleArray jaxisX, jdoubleArray jaxisY, jdoubleArray jaxisZ,
        jdoubleArray jtarget, jdoubleArray jrnd,
        jdouble ox, jdouble oy, jdouble oz,
        jlong seed, jint ageStep, jdouble time, jint randSeq,
        jdoubleArray jfv, jdoubleArray jhv)
{
    WmvmProgram* prog = reinterpret_cast<WmvmProgram*>(static_cast<intptr_t>(handle));
    if (prog == nullptr)
    {
        return -1;
    }

    WmvmBatch b{};
    b.from = static_cast<int>(from);
    b.to = static_cast<int>(to);
    b.origin_x = ox;
    b.origin_y = oy;
    b.origin_z = oz;
    b.seed = static_cast<int64_t>(seed);
    b.age_step = static_cast<int>(ageStep);
    b.time = time;
    b.rand_seq = static_cast<int>(randSeq);

    // 逐个钉住；数组传 null 表示这个量这一段用不到
    struct Arr
    {
        jarray ref;
        void* ptr;
        bool release;
    };
    Arr pinned[16] = {};
    int n = 0;
    auto pin = [&](jarray a) -> void* {
        if (a == nullptr)
        {
            return nullptr;
        }
        void* ptr = env->GetPrimitiveArrayCritical(a, nullptr);
        pinned[n++] = Arr{a, ptr, ptr != nullptr};
        return ptr;
    };

    b.px = static_cast<double*>(pin(jpx));
    b.py = static_cast<double*>(pin(jpy));
    b.pz = static_cast<double*>(pin(jpz));
    b.vx = static_cast<double*>(pin(jvx));
    b.vy = static_cast<double*>(pin(jvy));
    b.vz = static_cast<double*>(pin(jvz));
    b.energy = static_cast<float*>(pin(jenergy));
    b.anchor_dx = static_cast<const double*>(pin(jadx));
    b.anchor_dy = static_cast<const double*>(pin(jady));
    b.anchor_dz = static_cast<const double*>(pin(jadz));
    b.axis_x = static_cast<const double*>(pin(jaxisX));
    b.axis_y = static_cast<const double*>(pin(jaxisY));
    b.axis_z = static_cast<const double*>(pin(jaxisZ));
    b.target = static_cast<const double*>(pin(jtarget));
    b.rnd = static_cast<const double*>(pin(jrnd));
    auto* fv = static_cast<double*>(pin(jfv));
    auto* hv = static_cast<const double*>(pin(jhv));

    jint rc = -2;
    if (b.px != nullptr && b.py != nullptr && b.pz != nullptr
            && b.vx != nullptr && b.vy != nullptr && b.vz != nullptr
            && b.energy != nullptr && fv != nullptr && hv != nullptr)
    {
        rc = wmvm_run(prog, &b, fv, hv);
    }

    for (int i = 0; i < n; ++i)
    {
        if (pinned[i].release)
        {
            env->ReleasePrimitiveArrayCritical(pinned[i].ref, pinned[i].ptr, 0);
        }
    }
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_wizardmagic_core_spell_script_NativeVm_nativeLastError(JNIEnv* env, jclass)
{
    const char* msg = wmvm_last_error();
    return env->NewStringUTF(msg == nullptr ? "" : msg);
}

}  // extern "C"
