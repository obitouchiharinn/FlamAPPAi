#include "EdgeProcessor.h"
#include <android/log.h>
#include <cstring>

#define EP_TAG "EdgeProcessor"
#define EP_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, EP_TAG, __VA_ARGS__))

void EdgeProcessor::processFrame(unsigned char* data, int width, int height, bool useCuda) {
    // Guard: if data null or dims invalid, return quickly
    if (data == nullptr || width <= 0 || height <= 0) {
        EP_LOGI("processFrame called with invalid params: data=%p width=%d height=%d useCuda=%d",
                static_cast<void*>(data), width, height, useCuda ? 1 : 0);
        return;
    }

    // Correct, concise logging of dims
    EP_LOGI("processFrame called: %d x %d, useCuda=%d", width, height, useCuda ? 1 : 0);

    // Minimal stub: no-op or simple operation (e.g., invert first few bytes for quick test)
    // Remove or replace with actual OpenCV processing as needed.
    size_t n = static_cast<size_t>(width) * static_cast<size_t>(height);
    // If buffer is larger (e.g. YUV420), limit to n or input size; this is a conservative example.
    // Perform a tiny, safe modification for visible effect in testing:
    if (n > 0) {
        // flip first byte if exists
        data[0] = static_cast<unsigned char>(~data[0]);
    }
}
