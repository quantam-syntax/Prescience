#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "QairtCpp/QairtApi.hpp"
#include "QairtCpp/System/Qairt.hpp"
#include "QairtCpp/System/QairtSystemApi.hpp"
#include "QairtCpp/System/QairtSystemBuilder.hpp"
#include "QairtCpp/System/QairtSystemContext.hpp"
#include "QairtCpp/System/QairtSystemDlc.hpp"

namespace {
constexpr char kTag[] = "QairtSepformer";
constexpr size_t kSamples = 32000;
constexpr size_t kOutputSamples = kSamples * 2;

struct Quantization {
  float scale = 1.f;
  int32_t offset = 0;
};

Quantization quantizationFor(const qairt::SystemContextTensorInfo& tensor) {
  const auto& info = tensor.getQuantInfo();
  const auto type = info.getQuantizationType();
  if (type == qairt::SystemContextQuantInfoType::QAIRT_QUANTIZATION_INFO_SCALE_OFFSET) {
    const auto& encoding = info.getScaleOffset();
    return {encoding.getScale(), encoding.getOffset()};
  }
  if (type == qairt::SystemContextQuantInfoType::QAIRT_QUANTIZATION_INFO_BW_SCALE_OFFSET) {
    const auto& encoding = info.getBwScaleOffset();
    return {encoding.getScale(), encoding.getOffset()};
  }
  throw std::runtime_error("SepFormer uses an unsupported tensor quantization encoding");
}

bool isSigned16(qairt::DataType type) {
  return type == qairt::DataType::Int16 || type == qairt::DataType::SFixedPoint16;
}

bool isUnsigned16(qairt::DataType type) {
  return type == qairt::DataType::UInt16 || type == qairt::DataType::UFixedPoint16;
}

class SepformerRuntime {
 public:
  void initialize(const std::string& model_path, const std::string& native_lib_dir) {
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked();
    // QAIRT resolves the HTP stub and DSP-side V81 libraries from this directory.
    setenv("ADSP_LIBRARY_PATH", native_lib_dir.c_str(), 1);
    system_api_ = std::make_shared<qairt::SystemApi>("libQairtSystem.so");
    dlc_ = system_api_->makeShared<qairt::SystemDlc>(model_path);
    auto builder = system_api_->make<qairt::QairtSystemBuilder>();
    builder.setDlc(dlc_).setBackendType(qairt::BackendType::Htp);
    qairt_ = std::make_unique<qairt::Qairt>(builder.build());

    if (qairt_->getNumGraphInfos() != 1) throw std::runtime_error("Expected exactly one SepFormer graph");
    auto& graph = qairt_->getGraphInfoAt(0);
    graph_name_ = graph.getGraphName();
    auto& inputs = graph.getGraphInputs();
    auto& outputs = graph.getGraphOutputs();
    if (inputs.size() != 1 || outputs.size() != 1) throw std::runtime_error("Unexpected SepFormer I/O count");
    input_info_ = &inputs.front();
    output_info_ = &outputs.front();
    input_quant_ = quantizationFor(*input_info_);
    output_quant_ = quantizationFor(*output_info_);
    input_type_ = input_info_->getDataType();
    output_type_ = output_info_->getDataType();
    if ((!isSigned16(input_type_) && !isUnsigned16(input_type_)) ||
        (!isSigned16(output_type_) && !isUnsigned16(output_type_))) {
      throw std::runtime_error("Cached SepFormer must expose 16-bit quantized I/O");
    }
    input_buffer_.resize(kSamples * sizeof(int16_t));
    output_buffer_.resize(kOutputSamples * sizeof(int16_t));
  }

  std::vector<float> separate(const jfloat* samples, size_t count) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!qairt_) throw std::runtime_error("QAIRT SepFormer is not initialized");
    if (count != kSamples) throw std::runtime_error("SepFormer expects 32,000 samples at 8 kHz");
    auto* input = reinterpret_cast<int16_t*>(input_buffer_.data());
    for (size_t i = 0; i < kSamples; ++i) input[i] = quantize(samples[i], input_quant_, input_type_);
    std::vector<std::pair<qairt::SystemContextTensorInfo*, uint8_t*>> inputs{{input_info_, input_buffer_.data()}};
    std::vector<std::pair<qairt::SystemContextTensorInfo*, uint8_t*>> outputs{{output_info_, output_buffer_.data()}};
    qairt_->executeGraph(graph_name_, inputs, outputs);
    const auto* output = reinterpret_cast<const int16_t*>(output_buffer_.data());
    std::vector<float> result(kOutputSamples);
    for (size_t i = 0; i < kOutputSamples; ++i) result[i] = dequantize(output[i], output_quant_, output_type_);
    return result;
  }

  void close() { std::lock_guard<std::mutex> lock(mutex_); closeLocked(); }

 private:
  static int16_t quantize(float value, const Quantization& q, qairt::DataType type) {
    const int32_t raw = static_cast<int32_t>(std::lround(value / q.scale)) - q.offset;
    if (isSigned16(type)) return static_cast<int16_t>(std::clamp(raw, -32768, 32767));
    return static_cast<int16_t>(std::clamp(raw, 0, 65535));
  }
  static float dequantize(int16_t value, const Quantization& q, qairt::DataType type) {
    const int32_t raw = isSigned16(type) ? value : static_cast<uint16_t>(value);
    return (raw + q.offset) * q.scale;
  }
  void closeLocked() {
    qairt_.reset(); dlc_.reset(); system_api_.reset(); input_info_ = nullptr; output_info_ = nullptr;
    input_buffer_.clear(); output_buffer_.clear(); graph_name_.clear();
  }
  std::mutex mutex_;
  std::shared_ptr<qairt::SystemApi> system_api_;
  std::shared_ptr<qairt::SystemDlc> dlc_;
  std::unique_ptr<qairt::Qairt> qairt_;
  qairt::SystemContextTensorInfo* input_info_ = nullptr;
  qairt::SystemContextTensorInfo* output_info_ = nullptr;
  qairt::DataType input_type_ = qairt::DataType::Undefined;
  qairt::DataType output_type_ = qairt::DataType::Undefined;
  Quantization input_quant_, output_quant_;
  std::string graph_name_;
  std::vector<uint8_t> input_buffer_, output_buffer_;
};

SepformerRuntime g_runtime;

std::string toString(JNIEnv* env, jstring value) {
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

void throwJava(JNIEnv* env, const char* type, const std::string& message) {
  jclass klass = env->FindClass(type);
  env->ThrowNew(klass, message.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_atreides_consentvoice_QairtSepformer_initialize(JNIEnv* env, jobject, jstring model_path, jstring native_library_dir) {
  try {
    g_runtime.initialize(toString(env, model_path), toString(env, native_library_dir));
    return nullptr;
  } catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "Initialization failed: %s", e.what());
    return env->NewStringUTF(e.what());
  }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_atreides_consentvoice_QairtSepformer_separate(JNIEnv* env, jobject, jfloatArray audio) {
  const jsize size = env->GetArrayLength(audio);
  if (size != static_cast<jsize>(kSamples)) { throwJava(env, "java/lang/IllegalArgumentException", "Expected 32,000 samples at 8 kHz"); return nullptr; }
  jfloat* input = env->GetFloatArrayElements(audio, nullptr);
  try {
    std::vector<float> output = g_runtime.separate(input, size);
    env->ReleaseFloatArrayElements(audio, input, JNI_ABORT);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
  } catch (const std::exception& e) {
    env->ReleaseFloatArrayElements(audio, input, JNI_ABORT);
    throwJava(env, "java/lang/IllegalStateException", e.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_atreides_consentvoice_QairtSepformer_close(JNIEnv*, jobject) { g_runtime.close(); }
