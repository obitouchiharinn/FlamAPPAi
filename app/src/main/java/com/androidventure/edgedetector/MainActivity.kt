package com.androidventure.edgedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST = 100

        init {
            System.loadLibrary("native-lib")
        }
    }

    private lateinit var imageView: ImageView
    private lateinit var cameraManager: CameraManager
    private lateinit var btnToggle: Button
    private lateinit var tvFPS: TextView
    private lateinit var btnUpload: Button

    private var isEdgeDetectionEnabled = true
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    // Network uploader (replaced FrameUploader usage with a simple internal uploader)
    // frameUploader remains but is unused if you prefer internal uploader.
    private var frameUploader: FrameUploader? = null
    private var serverUploadUrl: String? = null // set when upload enabled
    private var isUploadEnabled = false
    private var uploadFrameCounter = 0
    private val UPLOAD_EVERY_N_FRAMES = 30 // Upload every 30th frame (~1 FPS at 30fps camera)
    private val isUploading = AtomicBoolean(false) // Prevent concurrent uploads

    // Processing thread
    private val processingThread = HandlerThread("FrameProcessor").apply { start() }
    private val processingHandler = Handler(processingThread.looper)
    private val isProcessing = AtomicBoolean(false)

    // Reusable bitmap
    private var displayBitmap: Bitmap? = null

    // Native methods
    external fun processFrame(
        inputData: ByteArray,
        width: Int,
        height: Int,
        applyEdgeDetection: Boolean
    ): ByteArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        btnToggle = findViewById(R.id.btnToggle)
        tvFPS = findViewById(R.id.tvFPS)
        btnUpload = findViewById(R.id.btnUpload)

        btnToggle.setOnClickListener {
            isEdgeDetectionEnabled = !isEdgeDetectionEnabled
            btnToggle.text = if (isEdgeDetectionEnabled) {
                "Show Raw Feed"
            } else {
                "Show Edge Detection"
            }
        }

        btnUpload.setOnClickListener {
            isUploadEnabled = !isUploadEnabled
            if (isUploadEnabled) {
                // Use PC LAN IP + port + path (explicit)
                val serverIp = "192.168.29.208" // Change if needed
                serverUploadUrl = "http://$serverIp:9001/upload"
                btnUpload.text = "Stop Upload"
                Toast.makeText(this, "Upload enabled to $serverUploadUrl", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Upload enabled, target=$serverUploadUrl")
            } else {
                // disable upload
                serverUploadUrl = null
                btnUpload.text = "Start Upload"
                Toast.makeText(this, "Upload disabled", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Upload disabled")
            }
        }

        if (checkCameraPermission()) {
            initializeCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun initializeCamera() {
        cameraManager = CameraManager(this)
        cameraManager.openCamera { imageData, imgWidth, imgHeight ->
            processAndRender(imageData, imgWidth, imgHeight)
        }
    }

    private fun processAndRender(imageData: ByteArray, width: Int, height: Int) {
        // Skip frame if still processing previous one
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }

        processingHandler.post {
            try {
                val startTime = System.currentTimeMillis()

                val processedData = processFrame(imageData, width, height, isEdgeDetectionEnabled)

                // Calculate output dimensions (C++ resizes to 480 width)
                val outputWidth = 480
                val outputHeight = (height * outputWidth) / width

                // Reuse bitmap or create new one
                if (displayBitmap == null || displayBitmap?.width != outputWidth || displayBitmap?.height != outputHeight) {
                    displayBitmap?.recycle()
                    displayBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                }

                displayBitmap?.copyPixelsFromBuffer(ByteBuffer.wrap(processedData))

                runOnUiThread {
                    displayBitmap?.let { imageView.setImageBitmap(it) }
                }

                // Upload frame if enabled (rate-limited)
                if (isUploadEnabled && displayBitmap != null && isUploading.compareAndSet(false, true)) {
                    uploadFrameCounter++
                    if (uploadFrameCounter >= UPLOAD_EVERY_N_FRAMES) {
                        uploadFrameCounter = 0
                        val bitmapToUpload = displayBitmap!!.copy(Bitmap.Config.ARGB_8888, false)

                        // Use the internal uploader (network call on background processing thread)
                        val url = serverUploadUrl
                        if (url != null) {
                            uploadBitmapInternal(url, bitmapToUpload,
                                onSuccess = {
                                    bitmapToUpload.recycle()
                                    isUploading.set(false)
                                    Log.i(TAG, "Upload success to $url")
                                },
                                onFailure = { err ->
                                    bitmapToUpload.recycle()
                                    isUploading.set(false)
                                    Log.w(TAG, "Upload failed to $url: $err")
                                }
                            )
                        } else {
                            bitmapToUpload.recycle()
                            isUploading.set(false)
                            Log.w(TAG, "serverUploadUrl is null, skipping upload")
                        }
                    } else {
                        isUploading.set(false)
                    }
                }

                // Update FPS
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFpsTime >= 1000) {
                    val fps = frameCount.toFloat() / ((currentTime - lastFpsTime) / 1000f)
                    val processingTime = currentTime - startTime
                    runOnUiThread {
                        tvFPS.text = String.format("FPS: %.1f (%.0fms)", fps, processingTime.toFloat())
                    }
                    frameCount = 0
                    lastFpsTime = currentTime
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraManager.isInitialized) {
            cameraManager.closeCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::cameraManager.isInitialized && checkCameraPermission()) {
            cameraManager.openCamera { imageData, width, height ->
                processAndRender(imageData, width, height)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        frameUploader?.shutdown()
        processingThread.quitSafely()
        displayBitmap?.recycle()
        displayBitmap = null
    }

    // Internal uploader: convert Bitmap -> PNG bytes -> base64, POST JSON { "image": "data:image/png;base64,..." }
    private fun uploadBitmapInternal(
        url: String,
        bitmap: Bitmap,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        // Running on processingHandler thread; do a synchronous OkHttp call here (already off UI)
        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            val pngBytes = baos.toByteArray()
            baos.close()

            val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
            val payload = "{\"image\":\"data:image/png;base64,$b64\"}"

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = payload.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val client = OkHttpClient()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    onSuccess()
                } else {
                    onFailure("HTTP ${resp.code}: ${resp.message}")
                }
            }
        } catch (e: Exception) {
            onFailure(e.message ?: "unknown error")
        }
    }
}
