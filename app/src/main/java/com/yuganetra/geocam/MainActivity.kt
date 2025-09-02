package com.yuganetra.geocam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.yuganetra.geocam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isRecording = false
    private var isPhotoMode = true

    // Zoom functionality
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomRatio = 1f

    // Request permissions
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            // Check storage permissions based on Android version
            val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
            } else {
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
            }

            when {
                cameraGranted -> {
                    Log.d("GeoCam", "Camera permission granted, starting camera")
                    startCamera()
                }
                else -> {
                    Log.d("GeoCam", "Camera permission denied")
                    Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Create DICM/GeoCam folder structure
        createGeoCamFolders()

        // Initialize zoom gesture detector
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                val newZoomRatio = currentZoomRatio * scale

                camera?.let { cam ->
                    val zoomState = cam.cameraInfo.zoomState.value
                    val clampedRatio = zoomState?.let { state ->
                        min(max(newZoomRatio, state.minZoomRatio), state.maxZoomRatio)
                    } ?: newZoomRatio

                    currentZoomRatio = clampedRatio
                    cam.cameraControl.setZoomRatio(clampedRatio)
                }
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        // Set up button listeners
        setupButtonListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check and request permissions only once
        checkAndRequestPermissions()
    }

    private fun createGeoCamFolders() {
        try {
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val geocamDir = File(dcimDir, "GeoCam")

            if (!geocamDir.exists()) {
                val created = geocamDir.mkdirs()
                Log.d("GeoCam", "GeoCam folder created: $created at ${geocamDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("GeoCam", "Error creating GeoCam folder", e)
        }
    }

    private fun getGeoCamFolder(): File {
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val geocamDir = File(dcimDir, "GeoCam")

        if (!geocamDir.exists()) {
            geocamDir.mkdirs()
        }

        return geocamDir
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = getRequiredPermissions()
        val deniedPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isEmpty()) {
            // All permissions are already granted
            Log.d("GeoCam", "All permissions already granted")
            startCamera()
        } else {
            // Request missing permissions
            Log.d("GeoCam", "Requesting permissions: ${deniedPermissions.joinToString()}")
            requestPermissions.launch(deniedPermissions.toTypedArray())
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    private fun setupButtonListeners() {
        binding.cameraCaptureButton.setOnClickListener {
            if (isPhotoMode) {
                takePhoto()
            } else {
                captureVideo()
            }
        }

        binding.switchCameraButton.setOnClickListener {
            switchCamera()
        }

        binding.galleryButton.setOnClickListener {
            openGallery()
        }

        binding.photoModeButton.setOnClickListener {
            switchToPhotoMode()
        }

        binding.videoModeButton.setOnClickListener {
            switchToVideoMode()
        }

        binding.zoomInButton.setOnClickListener {
            zoomIn()
        }

        binding.zoomOutButton.setOnClickListener {
            zoomOut()
        }

        binding.flashButton.setOnClickListener {
            toggleFlash()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (exc: Exception) {
                Log.e("GeoCam", "Camera provider initialization failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()

            camera = if (isPhotoMode) {
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } else {
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
            }

            currentZoomRatio = 1f
            camera?.cameraControl?.setZoomRatio(currentZoomRatio)

        } catch (exc: Exception) {
            Log.e("GeoCam", "Use case binding failed", exc)
            Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Disable the button to prevent multiple clicks
        binding.cameraCaptureButton.isEnabled = false

        val geocamDir = getGeoCamFolder()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val photoFile = File(geocamDir, "IMG_$name.jpg")

        Log.d("GeoCam", "Taking photo, saving to: ${photoFile.absolutePath}")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("GeoCam", "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(
                        baseContext,
                        "Photo capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Re-enable the button
                    binding.cameraCaptureButton.isEnabled = true
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("GeoCam", "Photo saved successfully to: ${photoFile.absolutePath}")

                    // Only scan the file once to avoid duplicates
                    scanMediaFile(photoFile)

                    Toast.makeText(
                        baseContext,
                        "✅ Photo saved to DCIM/GeoCam",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Re-enable the button
                    binding.cameraCaptureButton.isEnabled = true
                }
            }
        )
    }

    private fun scanMediaFile(file: File) {
        try {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val contentUri = Uri.fromFile(file)
            mediaScanIntent.data = contentUri
            sendBroadcast(mediaScanIntent)
            Log.d("GeoCam", "Media scan broadcast sent for: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("GeoCam", "Error scanning media file", e)
        }
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        binding.cameraCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val geocamDir = getGeoCamFolder()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val videoFile = File(geocamDir, "VID_$name.mp4")

        Log.d("GeoCam", "Recording video to: ${videoFile.absolutePath}")

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.cameraCaptureButton.apply {
                            setImageResource(android.R.drawable.ic_media_pause)
                            isEnabled = true
                        }
                        isRecording = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Log.d("GeoCam", "Video saved successfully to: ${videoFile.absolutePath}")
                            scanMediaFile(videoFile)
                            Toast.makeText(
                                baseContext,
                                "✅ Video saved to DCIM/GeoCam",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e("GeoCam", "Video capture failed: ${recordEvent.error}")
                        }
                        binding.cameraCaptureButton.apply {
                            setImageResource(android.R.drawable.ic_menu_camera)
                            isEnabled = true
                        }
                        isRecording = false
                    }
                }
            }
    }

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        bindCameraUseCases()
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToPhotoMode() {
        if (!isPhotoMode) {
            isPhotoMode = true
            binding.photoModeButton.setTextColor(getColor(android.R.color.holo_orange_light))
            binding.videoModeButton.setTextColor(getColor(android.R.color.white))
            binding.cameraCaptureButton.setImageResource(android.R.drawable.ic_menu_camera)
            bindCameraUseCases()
        }
    }

    private fun switchToVideoMode() {
        if (isPhotoMode) {
            isPhotoMode = false
            binding.photoModeButton.setTextColor(getColor(android.R.color.white))
            binding.videoModeButton.setTextColor(getColor(android.R.color.holo_orange_light))
            binding.cameraCaptureButton.setImageResource(android.R.drawable.ic_menu_camera)
            bindCameraUseCases()
        }
    }

    private fun zoomIn() {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value
            zoomState?.let { state ->
                val newRatio = min(currentZoomRatio * 1.2f, state.maxZoomRatio)
                currentZoomRatio = newRatio
                cam.cameraControl.setZoomRatio(newRatio)
            }
        }
    }

    private fun zoomOut() {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value
            zoomState?.let { state ->
                val newRatio = max(currentZoomRatio / 1.2f, state.minZoomRatio)
                currentZoomRatio = newRatio
                cam.cameraControl.setZoomRatio(newRatio)
            }
        }
    }

    private fun toggleFlash() {
        camera?.let {
            val flashMode = imageCapture?.flashMode
            val newFlashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = newFlashMode

            val flashText = when (newFlashMode) {
                ImageCapture.FLASH_MODE_ON -> "Flash: ON"
                ImageCapture.FLASH_MODE_AUTO -> "Flash: AUTO"
                else -> "Flash: OFF"
            }
            Toast.makeText(this, flashText, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
