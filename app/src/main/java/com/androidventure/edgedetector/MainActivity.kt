package com.androidventure.edgedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

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
    
    // Network uploader
    private var frameUploader: FrameUploader? = null
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
                // TODO: Replace with your PC's LAN IP (find it with 'ipconfig' on Windows)
                val serverIp = "192.168.29.12" // Your PC's IP address
                frameUploader = FrameUploader("http://$serverIp:9000/upload")
                btnUpload.text = "Stop Upload"
                Toast.makeText(this, "Upload enabled to $serverIp", Toast.LENGTH_SHORT).show()
            } else {
                frameUploader?.shutdown()
                frameUploader = null
                btnUpload.text = "Start Upload"
                Toast.makeText(this, "Upload disabled", Toast.LENGTH_SHORT).show()
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
                
                Log.d(TAG, "Processing frame: ${width}x${height}, size=${imageData.size}")
                
                val processedData = processFrame(imageData, width, height, isEdgeDetectionEnabled)
                
                if (processedData == null || processedData.isEmpty()) {
                    Log.e(TAG, "Processed data is null or empty")
                    isProcessing.set(false)
                    return@post
                }
                
                // Calculate output dimensions
                // For YUV420, the reported height is the actual image height
                // C++ resizes to 480 width maintaining aspect ratio
                val outputWidth = 480
                val outputHeight = (height * outputWidth) / width
                
                val expectedSize = outputWidth * outputHeight * 4 // RGBA = 4 bytes per pixel
                
                Log.d(TAG, "Output dimensions: ${outputWidth}x${outputHeight}, data size: ${processedData.size}, expected: $expectedSize")
                
                if (processedData.size != expectedSize) {
                    Log.e(TAG, "Size mismatch! Got ${processedData.size} bytes, expected $expectedSize")
                    isProcessing.set(false)
                    return@post
                }
                
                // Reuse bitmap or create new one
                if (displayBitmap == null || displayBitmap?.width != outputWidth || displayBitmap?.height != outputHeight) {
                    displayBitmap?.recycle()
                    displayBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    Log.d(TAG, "Created new bitmap: ${outputWidth}x${outputHeight}")
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
                        frameUploader?.uploadFrame(
                            bitmapToUpload,
                            onSuccess = {
                                bitmapToUpload.recycle()
                                isUploading.set(false)
                            },
                            onFailure = { 
                                bitmapToUpload.recycle()
                                isUploading.set(false)
                            }
                        )
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
}
