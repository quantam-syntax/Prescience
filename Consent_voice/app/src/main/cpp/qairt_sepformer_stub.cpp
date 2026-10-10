#include <jni.h>

namespace {
constexpr char kUnavailable[] = "QAIRT SDK/runtime and cached SepFormer DLC are not staged for this build";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_atreides_consentvoice_QairtSepformer_initialize(JNIEnv* env, jobject, jstring, jstring) {
  return env->NewStringUTF(kUnavailable);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_atreides_consentvoice_QairtSepformer_separate(JNIEnv* env, jobject, jfloatArray) {
  jclass exception = env->FindClass("java/lang/IllegalStateException");
  env->ThrowNew(exception, kUnavailable);
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_atreides_consentvoice_QairtSepformer_close(JNIEnv*, jobject) {}
