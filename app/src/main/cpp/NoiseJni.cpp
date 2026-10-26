#include <jni.h>
#include "NoiseReducer.h"
#include <memory>
#include <mutex>
#include <unordered_map>
#include <stdexcept>
namespace {
std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<voice::NoiseReducer>> sessions;
jlong nextSession = 1;
std::shared_ptr<voice::NoiseReducer> find(jlong handle) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto item = sessions.find(handle);
    if (item == sessions.end()) throw std::invalid_argument("Noise reduction session closed");
    return item->second;
}
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_fossify_voicerecorder_audio_NativeNoiseReducer_createNative(JNIEnv* env, jobject, jint rate, jint channels) {
    try {
        auto session = std::make_shared<voice::NoiseReducer>(rate, channels);
        std::lock_guard<std::mutex> lock(sessionsMutex);
        jlong handle = nextSession++;
        sessions.emplace(handle, session);
        return handle;
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_org_fossify_voicerecorder_audio_NativeNoiseReducer_closeNative(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    sessions.erase(handle);
}
extern "C" JNIEXPORT jshortArray JNICALL
Java_org_fossify_voicerecorder_audio_NativeNoiseReducer_processNative(JNIEnv* env, jobject, jlong handle,
                                                                   jshortArray input, jboolean finish) {
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (!threadClass) return nullptr;
    jmethodID current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jmethodID interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
    if (!current || !interrupted) return nullptr;
    jobject thread = env->CallStaticObjectMethod(threadClass, current);
    if (!thread || env->ExceptionCheck()) return nullptr;
    const auto canceled = [&]() { return env->CallBooleanMethod(thread, interrupted) || env->ExceptionCheck(); };
    try {
        auto session = find(handle);
        if (!input) throw std::invalid_argument("Missing PCM chunk");
        const jsize length = env->GetArrayLength(input);
        if (length > 65536) throw std::invalid_argument("PCM chunk exceeds streaming limit");
        std::vector<int16_t> samples(length);
        if (length) env->GetShortArrayRegion(input, 0, length, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        auto output = finish ? session->finish(canceled) : session->process(samples, canceled);
        if (env->ExceptionCheck()) return nullptr;
        auto result = env->NewShortArray(static_cast<jsize>(output.size()));
        if (result && !output.empty()) env->SetShortArrayRegion(result, 0, output.size(), output.data());
        return result;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) {
            bool wasCanceled = canceled();
            if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(wasCanceled ? "java/lang/InterruptedException" : "java/lang/IllegalArgumentException"), error.what());
        }
        return nullptr;
    }
}
