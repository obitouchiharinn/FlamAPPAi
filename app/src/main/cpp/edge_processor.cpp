#include "edge_processor.h"
#include <android/log.h>

#define LOG_TAG "EdgeProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> EdgeProcessor::processFrame(
    const uint8_t* inputData,
    int width,
    int height,
    bool applyEdgeDetection
) {
    try {
        LOGI("Input dimensions: %dx%d", width, height);
        
        // Convert YUV420 to BGR
        cv::Mat bgrImage = yuv420ToBGR(inputData, width, height);
        
        if (bgrImage.empty()) {
            LOGE("Failed to convert YUV to BGR");
            return std::vector<uint8_t>();
        }
        
        LOGI("BGR image size: %dx%d, channels: %d", bgrImage.cols, bgrImage.rows, bgrImage.channels());
        
        // Downscale for faster processing
        cv::Mat resized;
        int targetWidth = 480;
        int targetHeight = (height * targetWidth) / width;  // Use original height, not BGR height
        cv::resize(bgrImage, resized, cv::Size(targetWidth, targetHeight), 0, 0, cv::INTER_LINEAR);
        
        LOGI("Resized to: %dx%d", resized.cols, resized.rows);
        
        cv::Mat processedImage;
        
        if (applyEdgeDetection) {
            // Apply Canny edge detection
            processedImage = applyCanny(resized);
        } else {
            // Convert to grayscale for raw feed
            cv::cvtColor(resized, processedImage, cv::COLOR_BGR2GRAY);
        }
        
        LOGI("Processed image: %dx%d, channels: %d", processedImage.cols, processedImage.rows, processedImage.channels());
        
        // Convert to RGBA for display
        auto result = toRGBA(processedImage);
        LOGI("Output RGBA size: %zu bytes (expected: %d)", result.size(), targetWidth * targetHeight * 4);
        
        return result;
        
    } catch (const cv::Exception& e) {
        LOGE("OpenCV exception: %s", e.what());
        return std::vector<uint8_t>();
    } catch (const std::exception& e) {
        LOGE("Exception: %s", e.what());
        return std::vector<uint8_t>();
    }
}

cv::Mat EdgeProcessor::yuv420ToBGR(const uint8_t* yuvData, int width, int height) {
    try {
        LOGI("YUV conversion input: %dx%d", width, height);
        
        // NV21 format: Y plane (width x height) + interleaved VU plane (width x height/2)
        // OpenCV expects the data as a single channel image with height = height * 1.5
        cv::Mat yuvMat(height + height / 2, width, CV_8UC1, (void*)yuvData);
        
        // Convert NV21 to BGR
        cv::Mat bgrImage;
        cv::cvtColor(yuvMat, bgrImage, cv::COLOR_YUV2BGR_NV21);
        
        if (bgrImage.empty() || bgrImage.cols != width || bgrImage.rows != height) {
            LOGE("BGR conversion failed: empty=%d, size=%dx%d (expected %dx%d)", 
                 bgrImage.empty(), bgrImage.cols, bgrImage.rows, width, height);
            return cv::Mat();
        }
        
        LOGI("BGR conversion successful: %dx%d, channels=%d", bgrImage.cols, bgrImage.rows, bgrImage.channels());
        
        return bgrImage;
    } catch (const cv::Exception& e) {
        LOGE("YUV to BGR conversion exception: %s", e.what());
        return cv::Mat();
    }
}

cv::Mat EdgeProcessor::applyCanny(const cv::Mat& input) {
    try {
        cv::Mat gray, edges;
        
        // Convert to grayscale
        if (input.channels() == 3) {
            cv::cvtColor(input, gray, cv::COLOR_BGR2GRAY);
        } else {
            gray = input.clone();
        }
        
        // Skip blur for maximum speed, apply Canny directly
        cv::Canny(gray, edges, 30, 90, 3, false);
        
        return edges;
    } catch (const cv::Exception& e) {
        LOGE("Canny edge detection failed: %s", e.what());
        return input;
    }
}

std::vector<uint8_t> EdgeProcessor::toRGBA(const cv::Mat& input) {
    try {
        cv::Mat rgba;
        
        if (input.channels() == 1) {
            // Grayscale to RGBA
            cv::cvtColor(input, rgba, cv::COLOR_GRAY2RGBA);
        } else if (input.channels() == 3) {
            // BGR to RGBA
            cv::cvtColor(input, rgba, cv::COLOR_BGR2RGBA);
        } else if (input.channels() == 4) {
            rgba = input.clone();
        } else {
            LOGE("Unsupported number of channels: %d", input.channels());
            return std::vector<uint8_t>();
        }
        
        // Convert to vector
        size_t dataSize = rgba.total() * rgba.elemSize();
        std::vector<uint8_t> result(dataSize);
        std::memcpy(result.data(), rgba.data, dataSize);
        
        return result;
    } catch (const cv::Exception& e) {
        LOGE("RGBA conversion failed: %s", e.what());
        return std::vector<uint8_t>();
    }
}
