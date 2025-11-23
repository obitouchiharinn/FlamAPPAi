#pragma once

#include <cstdint>

class EdgeProcessor {
public:
    // Accepts a mutable buffer pointer; match call from native-lib.cpp
    static void processFrame(unsigned char* data, int width, int height, bool useCuda);
};
