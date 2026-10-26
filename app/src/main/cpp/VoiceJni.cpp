#include <jni.h>
#include "VoiceProcessor.h"
#include <new>
#include <stdexcept>

extern "C" JNIEXPORT jshortArray JNICALL
Java_org_fossify_voicerecorder_audio_NativeVoiceProcessor_processNative(
    JNIEnv* env, jobject, jshortArray input, jint rate, jint channels,
    jdouble pitch, jdouble formant, jdouble speed, jint effect, jdouble mix) {
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (!threadClass) return nullptr;
    jmethodID current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jmethodID interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
    if (!current || !interrupted) return nullptr;
    jobject thread = env->CallStaticObjectMethod(threadClass, current);
    if (!thread || env->ExceptionCheck()) return nullptr;
    const auto canceled = [&]() { return env->CallBooleanMethod(thread, interrupted) || env->ExceptionCheck(); };
    try {
        if (!input) throw std::invalid_argument("Missing audio");
        jsize length = env->GetArrayLength(input);
        if (length <= 0 || length > 8'000'000) throw std::invalid_argument("Audio exceeds memory limit");
        std::vector<int16_t> samples(length);
        env->GetShortArrayRegion(input, 0, length, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        auto output = voice::process(samples, rate, channels, pitch, formant, speed, effect, mix, canceled);
        if (env->ExceptionCheck()) return nullptr;
        jshortArray result = env->NewShortArray(static_cast<jsize>(output.size()));
        if (result) env->SetShortArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        return result;
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Not enough memory to edit this recording; choose a shorter clip");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) {
            bool wasCanceled = canceled();
            if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(wasCanceled ? "java/lang/InterruptedException" : "java/lang/IllegalArgumentException"), error.what());
        }
    }
    return nullptr;
}
