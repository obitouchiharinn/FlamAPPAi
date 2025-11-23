package com.androidventure.edgedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    private lateinit var tvFPS: TextView

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var btnBurger: View

    private var isEdgeDetectionEnabled = false // default: normal RGB feed
    private var isGrayEnabled = false
    private var isInvertEnabled = false
    private var isUploadEnabled = false

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    // Network uploader
    private var frameUploader: FrameUploader? = null
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
        tvFPS = findViewById(R.id.tvFPS)

        // Drawer and burger
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        btnBurger = findViewById(R.id.btnBurger)

        // Open drawer when burger clicked (right-side)
        btnBurger.setOnClickListener {
            drawerLayout.openDrawer(Gravity.END)
        }

        // Handle navigation menu item clicks
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_toggle -> toggleMode()
                R.id.menu_gray -> toggleGray()
                R.id.menu_invert -> toggleInvert()
                R.id.menu_upload -> uploadFrame()
            }
            // close drawer after action
            drawerLayout.closeDrawer(Gravity.END)
            true
        }

        // Initialize FPS text and other UI that remains
        tvFPS = findViewById(R.id.tvFPS)

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

                // Apply color transforms (only when NOT in edge detection mode)
                if (!isEdgeDetectionEnabled && (isGrayEnabled || isInvertEnabled)) {
                    applyColorTransforms(processedData, isGrayEnabled, isInvertEnabled)
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

    /**
     * Apply grayscale and/or invert transformations to an RGBA byte array in place.
     * Assumes ordering R,G,B,A (OpenCV COLOR_*2RGBA produces RGBA).
     */
    private fun applyColorTransforms(pixels: ByteArray, applyGray: Boolean, applyInvert: Boolean) {
        var i = 0
        val len = pixels.size
        while (i + 3 < len) {
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            val a = pixels[i + 3] // preserve alpha as-is

            var nr = r
            var ng = g
            var nb = b

            if (applyGray) {
                // Use integer approximation for luminance: 0.299R + 0.587G + 0.114B
                val lum = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
                nr = lum
                ng = lum
                nb = lum
            }

            if (applyInvert) {
                nr = 255 - nr
                ng = 255 - ng
                nb = 255 - nb
            }

            pixels[i] = nr.toByte()
            pixels[i + 1] = ng.toByte()
            pixels[i + 2] = nb.toByte()
            pixels[i + 3] = a

            i += 4
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

    private fun toggleMode() {
        isEdgeDetectionEnabled = !isEdgeDetectionEnabled
        val modeText = if (isEdgeDetectionEnabled) "Edge Detection ON" else "Raw RGB ON"
        Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
    }

    private fun toggleGray() {
        // Grayscale only applies when not in edge mode per original logic
        isGrayEnabled = !isGrayEnabled
        val msg = if (isGrayEnabled) "Grayscale enabled" else "Grayscale disabled"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toggleInvert() {
        // Invert only applies when not in edge mode per original logic
        isInvertEnabled = !isInvertEnabled
        val msg = if (isInvertEnabled) "Invert enabled" else "Invert disabled"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun uploadFrame() {
        isUploadEnabled = !isUploadEnabled
        if (isUploadEnabled) {
            val serverIp = "192.168.29.12"
            frameUploader = FrameUploader("http://$serverIp:9000/upload")
            Toast.makeText(this, "Upload enabled to $serverIp", Toast.LENGTH_SHORT).show()
        } else {
            frameUploader?.shutdown()
            frameUploader = null
            Toast.makeText(this, "Upload disabled", Toast.LENGTH_SHORT).show()
        }
    }
}

