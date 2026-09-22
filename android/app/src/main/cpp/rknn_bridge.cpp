#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <memory>
#include <vector>
#include "rknn_api.h"

#define TAG "SmartCamRKNN"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
struct Model {
    rknn_context context = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t channels = 3;
    std::vector<rknn_tensor_attr> outputs;
    bool skuDetector = false;
};

static uint8_t clamp8(int value) { return static_cast<uint8_t>(std::max(0, std::min(255, value))); }

static void i420ToBgrLetterbox(const uint8_t* src, int srcW, int srcH, uint8_t* dst, int dstW, int dstH,
                               int cropX, int cropY, int cropW, int cropH, bool rgb) {
    const uint8_t* yPlane = src;
    const uint8_t* uPlane = yPlane + srcW * srcH;
    const uint8_t* vPlane = uPlane + srcW * srcH / 4;
    std::fill(dst, dst + dstW * dstH * 3, static_cast<uint8_t>(114));
    float scale = std::min(static_cast<float>(dstW) / cropW, static_cast<float>(dstH) / cropH);
    int resizedW = std::max(1, static_cast<int>(std::round(cropW * scale)));
    int resizedH = std::max(1, static_cast<int>(std::round(cropH * scale)));
    int left = (dstW - resizedW) / 2;
    int top = (dstH - resizedH) / 2;
    for (int y = 0; y < resizedH; ++y) {
        int sy = std::min(cropH - 1, y * cropH / resizedH) + cropY;
        for (int x = 0; x < resizedW; ++x) {
            int sx = std::min(cropW - 1, x * cropW / resizedW) + cropX;
            int yy = yPlane[sy * srcW + sx];
            int uv = (sy / 2) * (srcW / 2) + (sx / 2);
            int u = uPlane[uv] - 128;
            int v = vPlane[uv] - 128;
            int index = ((y + top) * dstW + x + left) * 3;
            uint8_t b = clamp8(static_cast<int>(yy + 1.772f * u));
            uint8_t g = clamp8(static_cast<int>(yy - 0.344f * u - 0.714f * v));
            uint8_t r = clamp8(static_cast<int>(yy + 1.402f * v));
            if (rgb) { dst[index] = r; dst[index + 1] = g; dst[index + 2] = b; }
            else { dst[index] = b; dst[index + 1] = g; dst[index + 2] = r; }
        }
    }
}

static void i420ToBgrLetterbox(const uint8_t* src, int srcW, int srcH, uint8_t* dst, int dstW, int dstH) {
    i420ToBgrLetterbox(src, srcW, srcH, dst, dstW, dstH, 0, 0, srcW, srcH, false);
}

static std::vector<uint8_t> readFile(const char* path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return {};
    std::streamsize size = file.tellg();
    if (size <= 0) return {};
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    file.seekg(0);
    file.read(reinterpret_cast<char*>(bytes.data()), size);
    return bytes;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_smartcam_capture_NativeRknn_nativeOpen(JNIEnv* env, jclass, jstring path) {
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    auto modelBytes = readFile(pathChars);
    env->ReleaseStringUTFChars(path, pathChars);
    if (modelBytes.empty()) return 0;
    auto model = std::make_unique<Model>();
    if (rknn_init(&model->context, modelBytes.data(), modelBytes.size(), 0, nullptr) != RKNN_SUCC) return 0;
    rknn_tensor_attr input{};
    input.index = 0;
    if (rknn_query(model->context, RKNN_QUERY_INPUT_ATTR, &input, sizeof(input)) != RKNN_SUCC) {
        rknn_destroy(model->context);
        return 0;
    }
    // Runtime converts UINT8 camera pixels to the queried model type (including FP16).
    if (input.n_dims >= 4) {
        if (input.fmt == RKNN_TENSOR_NHWC) {
            model->height = input.dims[1]; model->width = input.dims[2]; model->channels = input.dims[3];
        } else {
            model->channels = input.dims[1]; model->height = input.dims[2]; model->width = input.dims[3];
        }
    }
    if (model->width == 0 || model->height == 0 || model->channels != 3) {
        rknn_destroy(model->context);
        return 0;
    }
    rknn_input_output_num counts{};
    if (rknn_query(model->context, RKNN_QUERY_IN_OUT_NUM, &counts, sizeof(counts)) != RKNN_SUCC
            || counts.n_input != 1 || (counts.n_output != 1 && counts.n_output != 9)) {
        rknn_destroy(model->context);
        return 0;
    }
    model->skuDetector = counts.n_output == 9;
    // RK3588: allow the runtime to partition detector work across all three NPU cores.
    if (model->skuDetector && rknn_set_core_mask(model->context, RKNN_NPU_CORE_0_1_2) != RKNN_SUCC)
        LOGE("Three-core mode unavailable, keeping runtime default");
    model->outputs.resize(counts.n_output);
    for (uint32_t i = 0; i < counts.n_output; ++i) {
        auto& attr = model->outputs[i];
        attr.index = i;
        bool valid = rknn_query(model->context, RKNN_QUERY_OUTPUT_ATTR, &attr, sizeof(attr)) == RKNN_SUCC;
        if (valid && model->skuDetector) {
            bool fiveClass = model->width == 640 && model->height == 640;
            uint32_t classCount = i % 3 == 1 ? attr.dims[1] : 0;
            uint32_t side = (fiveClass ? 80 : 120) >> (i / 3);
            uint32_t channels = i % 3 == 0 ? 64 : (i % 3 == 1 && fiveClass ? classCount : 1);
            valid = (i % 3 != 1 || !fiveClass || classCount == 2 || classCount == 5) && (fiveClass || (model->width == 960 && model->height == 960)) && attr.fmt == RKNN_TENSOR_NCHW
                && attr.n_dims == 4 && attr.dims[0] == 1 && attr.dims[1] == channels
                && attr.dims[2] == side && attr.dims[3] == side && attr.n_elems == channels * side * side;
        }
        if (!valid) {
            LOGE("Unsupported output tensor %u", i);
            rknn_destroy(model->context);
            return 0;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Model loaded: %ux%u, outputs=%u, skuDetector=%d",
                        model->width, model->height, counts.n_output, model->skuDetector);
    return reinterpret_cast<jlong>(model.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smartcam_capture_NativeRknn_nativeIsSkuDetector(JNIEnv*, jclass, jlong handle) {
    auto* model = reinterpret_cast<Model*>(handle);
    return model && model->skuDetector;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_smartcam_capture_NativeRknn_nativeDetect(JNIEnv* env, jclass, jlong handle,
                                                    jbyteArray i420, jint width, jint height) {
    auto* model = reinterpret_cast<Model*>(handle);
    if (!model || !i420 || width <= 0 || height <= 0 || (width & 1) || (height & 1)) return nullptr;
    if (!model->skuDetector && model->outputs[0].n_elems != 42000) return nullptr;
    jsize length = env->GetArrayLength(i420);
    if (length < width * height * 3 / 2) return nullptr;
    std::vector<uint8_t> source(static_cast<size_t>(length));
    env->GetByteArrayRegion(i420, 0, length, reinterpret_cast<jbyte*>(source.data()));
    std::vector<uint8_t> rgb(model->width * model->height * 3);
    i420ToBgrLetterbox(source.data(), width, height, rgb.data(), model->width, model->height,
                       0, 0, width, height, model->skuDetector);
    rknn_input input{};
    input.index = 0; input.type = RKNN_TENSOR_UINT8; input.size = rgb.size();
    input.fmt = RKNN_TENSOR_NHWC; input.buf = rgb.data();
    if (rknn_inputs_set(model->context, 1, &input) != RKNN_SUCC || rknn_run(model->context, nullptr) != RKNN_SUCC) return nullptr;
    std::vector<rknn_output> outputs(model->outputs.size());
    size_t count = 0;
    for (size_t i = 0; i < outputs.size(); ++i) {
        outputs[i].index = i;
        outputs[i].want_float = 1;
        count += model->outputs[i].n_elems;
    }
    if (rknn_outputs_get(model->context, outputs.size(), outputs.data(), nullptr) != RKNN_SUCC) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(count));
    if (result) {
        jsize offset = 0;
        for (size_t i = 0; i < outputs.size(); ++i) {
            jsize length = model->outputs[i].n_elems;
            env->SetFloatArrayRegion(result, offset, length, static_cast<const jfloat*>(outputs[i].buf));
            offset += length;
            if (env->ExceptionCheck()) break;
        }
    }
    rknn_outputs_release(model->context, outputs.size(), outputs.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_smartcam_capture_NativeRknn_nativeEmbed(JNIEnv* env, jclass, jlong handle,
                                                  jbyteArray i420, jint width, jint height,
                                                  jfloat left, jfloat top, jfloat right, jfloat bottom) {
    auto* model = reinterpret_cast<Model*>(handle);
    if (!model || !i420 || width < 2 || height < 2 || (width & 1) || (height & 1)
        || model->outputs.size()!=1 || model->outputs[0].n_elems!=576
        || !std::isfinite(left) || !std::isfinite(top) || !std::isfinite(right) || !std::isfinite(bottom)
        || left<0 || top<0 || right>1 || bottom>1 || right<=left || bottom<=top) return nullptr;
    jsize length = env->GetArrayLength(i420);
    if (length < width * height * 3 / 2) return nullptr;
    std::vector<uint8_t> source(static_cast<size_t>(length));
    env->GetByteArrayRegion(i420, 0, length, reinterpret_cast<jbyte*>(source.data()));
    int x = std::max(0, std::min(width - 2, static_cast<int>(left * width) & ~1));
    int y = std::max(0, std::min(height - 2, static_cast<int>(top * height) & ~1));
    int x2 = std::max(x + 2, std::min(width, static_cast<int>(right * width) & ~1));
    int y2 = std::max(y + 2, std::min(height, static_cast<int>(bottom * height) & ~1));
    std::vector<uint8_t> inputPixels(model->width * model->height * 3);
    // The MobileNet reference library was generated from RGB ImageNet input.
    i420ToBgrLetterbox(source.data(), width, height, inputPixels.data(), model->width, model->height,
                       x, y, x2 - x, y2 - y, true);
    rknn_input input{};
    input.index = 0; input.type = RKNN_TENSOR_UINT8; input.size = inputPixels.size();
    input.fmt = RKNN_TENSOR_NHWC; input.buf = inputPixels.data();
    if (rknn_inputs_set(model->context, 1, &input) != RKNN_SUCC || rknn_run(model->context, nullptr) != RKNN_SUCC) return nullptr;
    rknn_input_output_num outputCount{};
    if (rknn_query(model->context, RKNN_QUERY_IN_OUT_NUM, &outputCount, sizeof(outputCount)) != RKNN_SUCC || outputCount.n_output == 0) return nullptr;
    rknn_tensor_attr outputAttr{};
    outputAttr.index = 0;
    if (rknn_query(model->context, RKNN_QUERY_OUTPUT_ATTR, &outputAttr, sizeof(outputAttr)) != RKNN_SUCC) return nullptr;
    rknn_output output{};
    output.want_float = 1; output.is_prealloc = 0;
    if (rknn_outputs_get(model->context, 1, &output, nullptr) != RKNN_SUCC) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(outputAttr.n_elems));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(outputAttr.n_elems), static_cast<const jfloat*>(output.buf));
    rknn_outputs_release(model->context, 1, &output);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_smartcam_capture_NativeRknn_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* model = reinterpret_cast<Model*>(handle);
    if (!model) return;
    rknn_destroy(model->context);
    delete model;
}
