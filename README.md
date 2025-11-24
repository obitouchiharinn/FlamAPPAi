# Flamapp.AI — Quick Project README

A compact reference for the Android + Web real‑time edge detection project. Use this to get the app building, understand the native/JNI architecture, and run the lightweight TypeScript web viewer.

---

## Key features (implemented)
- Real‑time camera feed (Camera2 API)
- Native OpenCV C++ processing (JNI/NDK)
  - Canny edge detection
  -  grayscale / invert color transforms
- Live preview (RGBA) and FPS overlay
- **Modern navigation drawer with toggles for Canny edge, Grayscale, and Invert modes**
- Toggleable modes: Canny / Raw RGB / Grayscale / Invert
- Frame upload to a local server (HTTP/WebSocket support)
- Lightweight web dashboard (TypeScript) for viewing frames
- Modern Material-like UI with custom drawer + status indicators

---

## Quick setup

Prerequisites
- Android Studio (stable/Hedgehog)
- Android SDK + platform-tools
- Android NDK r25 or newer
- CMake 3.22+ (installed via SDK manager or system)
- Node.js 18.x+ (for web viewer)
- OpenCV Android SDK 4.x

Android native & OpenCV
1. Install NDK + CMake via Android Studio SDK Manager.
2. Download OpenCV Android SDK (4.x) from opencv.org and extract.
3. Copy SDK into the Android project:
   - Recommended location: app/src/main/cpp/opencv/
   - Example:
     mkdir -p app/src/main/cpp/opencv
     cp -r ~/Downloads/OpenCV-android-sdk/sdk app/src/main/cpp/opencv/

Gradle / Dependencies
- Typical app/build.gradle additions:
  - implementation "org.jetbrains.kotlin:kotlin-stdlib:1.8.x"
  - implementation "androidx.appcompat:appcompat:1.6.x"
  - implementation "com.google.android.material:material:1.8.x"
  - add OpenCV native include/link flags in CMakeLists.txt (see app/src/main/cpp/CMakeLists.txt)

Build & Run (Android)
- From project root:
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk

Web viewer (TypeScript)
1. cd web
2. npm install
3. npm run dev
- The web viewer connects to the upload/websocket server (configure server IP/port in MainActivity or FrameUploader).

Useful dev tips
- If build fails due to native libs, verify NDK path and CMake settings in local.properties:
  ndk.dir=/path/to/android-ndk
- Ensure OpenCV libs are included in CMakeLists.txt and packaged under src/main/jniLibs if required.

---

## How to Run

### Android (Kotlin) App

1. Open the project in Android Studio.
2. Make sure you have installed the required NDK, CMake, and OpenCV SDK as described above.
3. Connect your Android device (or use an emulator with Camera2 support).
4. Build and install the app:
   ```sh
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. Launch the app on your device.

### Web App (TypeScript Dashboard)

1. Open a terminal and navigate to the `web` directory:
   ```sh
   cd web
   ```
2. Install dependencies:
   ```sh
   npm install
   ```
3. Start the development server:
   ```sh
   npm run dev
   ```
4. Open your browser and go to [http://localhost:3000](http://localhost:3000) (or the port shown in your terminal).
5. Make sure your Android app is configured to upload frames to the correct server IP and port.

---

## Architecture (high level)

1) Camera & Android layer
- MainActivity handles UI, permissions, camera lifecycle and rendering the received RGBA byte arrays to an ImageView.
- CameraManager encapsulates Camera2 API: it opens camera, creates an ImageReader, and passes frames to the processing pipeline.

2) Frame flow (simplified)
- Camera2 Image -> CameraManager.onImageAvailable -> imageToByteArray(Image)
  - imageToByteArray safely extracts YUV planes into a byte array (guards against BufferUnderflow)
- Kotlin layer -> JNI bridge call: processFrame(byte[] yuv, width, height, applyEdge)
  - JNI signature exposed via native library (libnative-lib.so / libedge_processor.so)
- Native C++ (OpenCV)
  - YUV420 -> BGR conversion
  - Optional: resize, grayscale, Canny edge detection
  - Convert to RGBA byte buffer and return to Java
- MainActivity receives RGBA bytes
  - Optionally apply grayscale / invert transforms on RGBA (Kotlin)
  - copyPixelsFromBuffer into Bitmap and display
  - optionally upload frame via FrameUploader

3) JNI / Native details
- Native lib contains:
  - yuv420ToBGR conversion (cv::Mat)
  - applyCanny / toRGBA helpers
  - Exposed function e.g. Java_com_androidventure_edgedetector_EdgeProcessor_processFrame(...)
- Keep native code stable: validate buffer sizes, handle exceptions, return empty vector on error.

4) Web viewer (TypeScript)
- Simple Node/Express or WebSocket server receives uploads (multipart or binary).
- TypeScript client receives frames via WebSocket or polling and renders to an HTML5 canvas.
- Client shows status, FPS and resolution metrics.

---

## Common troubleshooting
- BufferUnderflowException during imageToByteArray:
  - Ensure you use buffer.remaining() and rewind buffers before reading.
  - Validate plane rowStride/pixelStride for YUV_420_888 → NV21 conversion.
- Native lib not found:
  - Confirm libs exist under app/src/main/jniLibs/<abi>/lib*.so or are built & packaged by CMake.
- Drawer/overlay issues:
  - Confirm custom drawer is last child in DrawerLayout and overlay z-order is correct.

---

## File map (important files)
- app/src/main/java/com/androidventure/edgedetector/
  - MainActivity.kt
  - CameraManager.kt
  - FrameUploader.kt
- app/src/main/cpp/
  - edge_processor.cpp
  - CMakeLists.txt
- web/
  - src/ (TypeScript client)
  - server.js / index.ts

---

