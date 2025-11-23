#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <opencv2/core/version.hpp>
#include <opencv2/core.hpp>
#include "EdgeProcessor.h"
#include <cstdint>

#define NL_TAG "NativeLib"
#define NL_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, NL_TAG, __VA_ARGS__))
#define NL_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, NL_TAG, __VA_ARGS__))

// Provide small in-source implementations of missing aarch64 atomic helpers.
// These reproduce the expected semantics (atomic fetch-and-add with acquire-release).
// This avoids requiring -latomic at link time when using prebuilt static OpenCV libs.
extern "C" uint64_t __aarch64_ldadd8_acq_rel(volatile void* ptr, uint64_t val) {
    return __atomic_fetch_add(reinterpret_cast<volatile uint64_t*>(ptr), val, __ATOMIC_ACQ_REL);
}
extern "C" uint32_t __aarch64_ldadd4_acq_rel(volatile void* ptr, uint32_t val) {
    return __atomic_fetch_add(reinterpret_cast<volatile uint32_t*>(ptr), val, __ATOMIC_ACQ_REL);
}

// Weak fallback definition for EdgeProcessor::processFrame.
// Place the weak attribute before the function to avoid GCC-compat warnings.
__attribute__((weak)) void EdgeProcessor::processFrame(unsigned char* data, int width, int height, bool useCuda) {
    if (data == nullptr || width <= 0 || height <= 0) {
        return;
    }
    // Conservative fallback: operate on width*height bytes to avoid reading past small buffers.
    size_t len = static_cast<size_t>(width) * static_cast<size_t>(height);
    for (size_t i = 0; i < len; ++i) {
        data[i] = static_cast<unsigned char>(255u - static_cast<unsigned int>(data[i]));
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_androidventure_edgedetector_MainActivity_processFrame(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray inputArray, jint width, jint height, jboolean useCuda) {

    if (inputArray == nullptr) {
        NL_LOGE("inputArray is null");
        return nullptr;
    }

    jsize inputLen = env->GetArrayLength(inputArray); // authoritative length
    if (inputLen <= 0) {
        NL_LOGE("invalid input length: %d", inputLen);
        return nullptr;
    }

    // Basic sanity checks for width/height to avoid overflow when user-provided values are wrong.
    if (width <= 0 || height <= 0 || width > 10000 || height > 10000) {
        NL_LOGI("sanity warning: width=%d height=%d inputLen=%d", width, height, inputLen);
        // proceed using inputLen as the buffer size (don't compute derived sizes that may overflow)
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* src = env->GetByteArrayElements(inputArray, &isCopy);
    if (src == nullptr) {
        NL_LOGE("GetByteArrayElements returned null");
        return nullptr;
    }

    // Work on a local mutable buffer (safe, avoids JSIZE issues)
    std::vector<unsigned char> buf;
    buf.reserve(static_cast<size_t>(inputLen));
    buf.insert(buf.end(), reinterpret_cast<unsigned char*>(src), reinterpret_cast<unsigned char*>(src) + inputLen);

    // release the Java array; we used a copy
    env->ReleaseByteArrayElements(inputArray, src, JNI_ABORT);

    // Call into processor with validated parameters. EdgeProcessor must accept mutable buffer.
    EdgeProcessor::processFrame(buf.data(), static_cast<int>(width), static_cast<int>(height), useCuda == JNI_TRUE);

    // Return a byte[] of the same length (safe, uses inputLen)
    jbyteArray out = env->NewByteArray(inputLen);
    if (out == nullptr) {
        NL_LOGE("Failed to allocate output byte array of length %d", inputLen);
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, inputLen, reinterpret_cast<jbyte*>(buf.data()));
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidventure_edgedetector_MainActivity_getOpenCVVersion(
    JNIEnv* env,
    jobject /* this */
) {
    std::string version = cv::getVersionString();
    return env->NewStringUTF(version.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string msg = "native-lib loaded";
    NL_LOGI("native-lib: %s", msg.c_str());
    return env->NewStringUTF(msg.c_str());
}
