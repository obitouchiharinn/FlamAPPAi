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

    private var isEdgeDetectionEnabled = false
    private var isGrayEnabled = false
    private var isInvertEnabled = false
    private var isUploadEnabled = false

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    private var frameUploader: FrameUploader? = null
    private var uploadFrameCounter = 0
    private val UPLOAD_EVERY_N_FRAMES = 30
    private val isUploading = AtomicBoolean(false)

    private val processingThread = HandlerThread("FrameProcessor").apply { start() }
    private val processingHandler = Handler(processingThread.looper)
    private val isProcessing = AtomicBoolean(false)

    private var displayBitmap: Bitmap? = null

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

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        btnBurger = findViewById(R.id.btnBurger)

        btnBurger.setOnClickListener {
            drawerLayout.openDrawer(Gravity.END)
        }

        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_toggle -> toggleMode()
                R.id.menu_gray -> toggleGray()
                R.id.menu_invert -> toggleInvert()
                R.id.menu_upload -> uploadFrame()
            }
            drawerLayout.closeDrawer(Gravity.END)
            true
        }

        if (checkCameraPermission()) initializeCamera()
        else requestCameraPermission()
    }

    private fun initializeCamera() {
        cameraManager = CameraManager(this)
        cameraManager.openCamera { imageData, w, h ->
            processAndRender(imageData, w, h)
        }
    }

    private fun processAndRender(imageData: ByteArray, width: Int, height: Int) {
        if (!isProcessing.compareAndSet(false, true)) return

        processingHandler.post {
            try {
                val startTime = System.currentTimeMillis()

                val processedData = processFrame(imageData, width, height, isEdgeDetectionEnabled)

                val outputWidth = 480
                val outputHeight = (height * outputWidth) / width
                val expected = outputWidth * outputHeight * 4

                if (processedData.size != expected) {
                    Log.e(TAG, "Size mismatch: ${processedData.size} != $expected")
                    isProcessing.set(false)
                    return@post
                }

                if (!isEdgeDetectionEnabled && (isGrayEnabled || isInvertEnabled)) {
                    applyColorTransforms(processedData, isGrayEnabled, isInvertEnabled)
                }

                if (displayBitmap == null ||
                    displayBitmap?.width != outputWidth ||
                    displayBitmap?.height != outputHeight
                ) {
                    displayBitmap?.recycle()
                    displayBitmap =
                        Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                }

                displayBitmap?.copyPixelsFromBuffer(ByteBuffer.wrap(processedData))

                // ------------- ROTATE ONLY (OPTION 1) -------------
                runOnUiThread {
                    displayBitmap?.let { bmp ->
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(90f)

                        val rotated = Bitmap.createBitmap(
                            bmp, 0, 0, bmp.width, bmp.height, matrix, true
                        )

                        imageView.setImageBitmap(rotated)
                    }
                }
                // ---------------------------------------------------

                // Upload logic
                if (isUploadEnabled &&
                    displayBitmap != null &&
                    isUploading.compareAndSet(false, true)
                ) {
                    uploadFrameCounter++
                    if (uploadFrameCounter >= UPLOAD_EVERY_N_FRAMES) {
                        uploadFrameCounter = 0
                        val copyBmp = displayBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
                        frameUploader?.uploadFrame(
                            copyBmp,
                            {
                                copyBmp.recycle()
                                isUploading.set(false)
                            },
                            {
                                copyBmp.recycle()
                                isUploading.set(false)
                            }
                        )
                    } else {
                        isUploading.set(false)
                    }
                }

                // FPS
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    val fps = frameCount * 1000f / (now - lastFpsTime)
                    val ms = now - startTime
                    runOnUiThread {
                        tvFPS.text = "FPS: %.1f (%dms)".format(fps, ms)
                    }
                    frameCount = 0
                    lastFpsTime = now
                }

            } catch (e: Exception) {
                Log.e(TAG, "Processing error", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun applyColorTransforms(pixels: ByteArray, g: Boolean, inv: Boolean) {
        var i = 0
        while (i + 3 < pixels.size) {
            var r = pixels[i].toInt() and 0xFF
            var g2 = pixels[i + 1].toInt() and 0xFF
            var b = pixels[i + 2].toInt() and 0xFF
            val a = pixels[i + 3]

            if (g) {
                val lum = (0.299 * r + 0.587 * g2 + 0.114 * b).toInt()
                r = lum; g2 = lum; b = lum
            }
            if (inv) {
                r = 255 - r
                g2 = 255 - g2
                b = 255 - b
            }

            pixels[i] = r.toByte()
            pixels[i + 1] = g2.toByte()
            pixels[i + 2] = b.toByte()
            pixels[i + 3] = a
            i += 4
        }
    }

    private fun checkCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initializeCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraManager.isInitialized) cameraManager.closeCamera()
    }

    override fun onResume() {
        super.onResume()
        if (::cameraManager.isInitialized && checkCameraPermission()) {
            cameraManager.openCamera { d, w, h -> processAndRender(d, w, h) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        frameUploader?.shutdown()
        processingThread.quitSafely()
        displayBitmap?.recycle()
    }

    private fun toggleMode() {
        isEdgeDetectionEnabled = !isEdgeDetectionEnabled
        Toast.makeText(
            this,
            if (isEdgeDetectionEnabled) "Edge Detection ON" else "Raw RGB ON",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleGray() {
        isGrayEnabled = !isGrayEnabled
        Toast.makeText(this, if (isGrayEnabled) "Gray ON" else "Gray OFF", Toast.LENGTH_SHORT).show()
    }

    private fun toggleInvert() {
        isInvertEnabled = !isInvertEnabled
        Toast.makeText(this, if (isInvertEnabled) "Invert ON" else "Invert OFF", Toast.LENGTH_SHORT)
            .show()
    }

    private fun uploadFrame() {
        isUploadEnabled = !isUploadEnabled
        if (isUploadEnabled) {
            val ip = "192.168.29.12"
            frameUploader = FrameUploader("http://$ip:9000/upload")
            Toast.makeText(this, "Upload enabled → $ip", Toast.LENGTH_SHORT).show()
        } else {
            frameUploader?.shutdown()
            frameUploader = null
            Toast.makeText(this, "Upload disabled", Toast.LENGTH_SHORT).show()
        }
    }
}
