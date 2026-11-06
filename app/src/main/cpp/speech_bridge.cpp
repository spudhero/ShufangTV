#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>
#include "c-api.h"

namespace {
void fail(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
const SherpaOnnxOfflineTts *engine(jlong handle) {
  return reinterpret_cast<const SherpaOnnxOfflineTts *>(static_cast<intptr_t>(handle));
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_spudhero_shufangtv_speech_OfflineVoice_create(JNIEnv *env, jclass, jstring directory) {
  const char *raw = env->GetStringUTFChars(directory, nullptr);
  if (!raw) return 0;
  std::string root(raw);
  env->ReleaseStringUTFChars(directory, raw);
  std::string model = root + "/model.onnx", lexicon = root + "/lexicon.txt";
  std::string tokens = root + "/tokens.txt", dict = root + "/dict";
  std::string rules = root + "/phone.fst," + root + "/date.fst," + root + "/number.fst," + root + "/new_heteronym.fst";
  SherpaOnnxOfflineTtsConfig config{};
  config.model.vits.model = model.c_str();
  config.model.vits.lexicon = lexicon.c_str();
  config.model.vits.tokens = tokens.c_str();
  config.model.vits.dict_dir = dict.c_str();
  config.model.vits.noise_scale = 0.667f;
  config.model.vits.noise_scale_w = 0.8f;
  config.model.vits.length_scale = 1.0f;
  config.model.num_threads = 2;
  config.model.provider = "cpu";
  config.rule_fsts = rules.c_str();
  config.max_num_sentences = 1;
  config.silence_scale = 0.2f;
  const auto *tts = SherpaOnnxCreateOfflineTts(&config);
  if (!tts) fail(env, "离线语音初始化失败，请重新安装语音包");
  return static_cast<jlong>(reinterpret_cast<intptr_t>(tts));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_spudhero_shufangtv_speech_OfflineVoice_sampleRate(JNIEnv *, jclass, jlong handle) {
  return handle ? SherpaOnnxOfflineTtsSampleRate(engine(handle)) : 0;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_io_github_spudhero_shufangtv_speech_OfflineVoice_generate(JNIEnv *env, jclass, jlong handle, jbyteArray bytes, jfloat rate) {
  if (!handle || !bytes || !std::isfinite(rate) || rate < 0.5f || rate > 2.0f) {
    fail(env, "无效的语音请求");
    return nullptr;
  }
  const jsize length = env->GetArrayLength(bytes);
  if (length < 1 || length > 2048) {
    fail(env, "朗读片段过长");
    return nullptr;
  }
  std::string text(static_cast<size_t>(length), '\0');
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(text.data()));
  if (env->ExceptionCheck()) return nullptr;
  const auto *audio = SherpaOnnxOfflineTtsGenerate(engine(handle), text.c_str(), 66, rate);
  if (!audio || audio->n <= 0 || audio->sample_rate <= 0 ||
      audio->n > audio->sample_rate * 90) {
    if (audio) SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    fail(env, "此片段未生成有效语音");
    return nullptr;
  }
  std::vector<jshort> pcm(static_cast<size_t>(audio->n));
  for (int32_t i = 0; i < audio->n; ++i) {
    float value = std::isfinite(audio->samples[i]) ? audio->samples[i] : 0;
    pcm[i] = static_cast<jshort>(std::clamp(value, -1.0f, 1.0f) * 32767.0f);
  }
  jshortArray result = env->NewShortArray(audio->n);
  if (result) env->SetShortArrayRegion(result, 0, audio->n, pcm.data());
  SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_spudhero_shufangtv_speech_OfflineVoice_destroy(JNIEnv *, jclass, jlong handle) {
  if (handle) SherpaOnnxDestroyOfflineTts(engine(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_spudhero_shufangtv_speech_OfflineVoice_runtimeVersion(JNIEnv *env, jclass) {
  return env->NewStringUTF(SherpaOnnxGetVersionStr());
}
