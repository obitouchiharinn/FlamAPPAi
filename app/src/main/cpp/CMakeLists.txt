cmake_minimum_required(VERSION 3.4.1)

# minimal project name
project(edgedetection LANGUAGES C CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# If OpenCV_DIR is provided by Gradle, prefer it
if (DEFINED OpenCV_DIR)
  find_package(OpenCV REQUIRED PATHS ${OpenCV_DIR} NO_DEFAULT_PATH)
else()
  find_package(OpenCV REQUIRED)
endif()

# include OpenCV headers (if found)
if (OpenCV_FOUND)
  include_directories(${OpenCV_INCLUDE_DIRS})
endif()

# Add native source(s) — reference files relative to this folder (no duplicate src path)
add_library(
  native-lib
  SHARED
  native-lib.cpp   # <- ensure this file exists in this folder
  EdgeProcessor.cpp   # <- added so EdgeProcessor implementation gets compiled and linked
)

# Link libraries
find_library(log-lib log)

if (OpenCV_FOUND)
  target_link_libraries(native-lib ${OpenCV_LIBS} ${log-lib})
else()
  target_link_libraries(native-lib ${log-lib})
endif()
