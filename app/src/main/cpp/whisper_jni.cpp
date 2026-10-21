// JNI bridge between WhisperNative.kt and whisper.cpp.
// Callers (WhisperCppEngine) serialise all calls on one context with a mutex.
#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "lyricsjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string trim(const std::string &s) {
    size_t b = 0, e = s.size();
    while (b < e && (s[b] == ' ' || s[b] == '\t' || s[b] == '\n' || s[b] == '\r')) b++;
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\n' || s[e - 1] == '\r')) e--;
    return s.substr(b, e - b);
}

// Special or non-lexical tokens such as "[_BEG_]", "[Music]", "(music)", "<|en|>".
bool isSpecial(const std::string &w) {
    if (w.empty()) return true;
    const char f = w.front(), l = w.back();
    return (f == '[' && l == ']') || (f == '(' && l == ')') || (f == '<' && l == '>') ||
           (f == '*' && l == '*' && w.size() > 1);
}

// Builds a java.lang.String from raw UTF-8 bytes; NewStringUTF would reject 4-byte or partial sequences.
jstring utf8String(JNIEnv *env, const std::string &s, jclass stringClass, jmethodID ctor, jstring charset) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    auto str = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    return str;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lyricsmachine_app_speech_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    if (ctx == nullptr) LOGE("whisper_init failed for %s", p);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lyricsmachine_app_speech_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring lang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    jclass stringClass = env->FindClass("java/lang/String");
    if (ctx == nullptr) return env->NewObjectArray(0, stringClass, nullptr);

    const char *langChars = lang != nullptr ? env->GetStringUTFChars(lang, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    params.language = langChars != nullptr ? langChars : "auto";
    params.translate = false;
    params.no_context = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    const jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    const int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (langChars != nullptr) env->ReleaseStringUTFChars(lang, langChars);

    std::vector<std::string> words;
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
    } else {
        const whisper_token eot = whisper_token_eot(ctx);
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            const char *raw = whisper_full_get_segment_text(ctx, i);
            const std::string text = trim(raw != nullptr ? raw : "");
            if (isSpecial(text)) continue;
            // Segment times are in 10 ms units.
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            float pSum = 0.f;
            int pCount = 0;
            const int tokens = whisper_full_n_tokens(ctx, i);
            for (int j = 0; j < tokens; j++) {
                if (whisper_full_get_token_id(ctx, i, j) >= eot) continue;  // special tokens
                pSum += whisper_full_get_token_p(ctx, i, j);
                pCount++;
            }
            const float p = pCount > 0 ? pSum / static_cast<float>(pCount) : 0.f;
            words.push_back(std::to_string(t0) + "|" + std::to_string(t1) + "|" + std::to_string(p) + "|" + text);
        }
    }

    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(words.size()), stringClass, nullptr);
    for (size_t k = 0; k < words.size(); k++) {
        jstring s = utf8String(env, words[k], stringClass, ctor, charset);
        env->SetObjectArrayElement(out, static_cast<jsize>(k), s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(charset);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lyricsmachine_app_speech_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}
