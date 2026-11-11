// The bridge between Kotlin (WhisperEngine) and whisper.cpp.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "voicewhisper", __VA_ARGS__)

namespace {

struct Engine {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aigames_voicewhisper_WhisperEngine_nativeLoad(JNIEnv *env, jobject, jstring model_path) {
    const std::string path = to_string(env, model_path);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) {
        LOG("could not load model %s", path.c_str());
        return 0;
    }
    auto *engine = new Engine();
    engine->ctx = ctx;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jstring JNICALL
Java_com_aigames_voicewhisper_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr || engine->ctx == nullptr) return nullptr;

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> audio(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, audio.data());
    const std::string lang = to_string(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.c_str();
    params.translate = false;
    params.no_timestamps = true;
    params.no_context = true;
    params.suppress_blank = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    engine->abort = false;
    params.abort_callback = [](void *user) { return static_cast<Engine *>(user)->abort.load(); };
    params.abort_callback_user_data = engine;

    if (whisper_full(engine->ctx, params, audio.data(), static_cast<int>(audio.size())) != 0) {
        return nullptr;
    }
    if (engine->abort) return nullptr;

    std::string text;
    const int segments = whisper_full_n_segments(engine->ctx);
    for (int i = 0; i < segments; i++) {
        text += whisper_full_get_segment_text(engine->ctx, i);
    }

    // Build the Java string from UTF-16 ourselves: NewStringUTF wants "modified
    // UTF-8" and can crash on text the model produces.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID from_bytes = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, from_bytes, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    return result;
}

JNIEXPORT void JNICALL
Java_com_aigames_voicewhisper_WhisperEngine_nativeAbort(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) engine->abort = true;
}

JNIEXPORT void JNICALL
Java_com_aigames_voicewhisper_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    whisper_free(engine->ctx);
    delete engine;
}

}  // extern "C"
