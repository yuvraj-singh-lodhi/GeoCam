package com.yuganetra.geocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.yuganetra.geocam.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: androidx.camera.video.VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isRecording = false
    private var isPhotoMode = true

    // Location services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private var currentAddress: String = "Address not available"
    private var isLocationEnabled = true

    // Zoom functionality
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomRatio = 1f

    // Coroutine scope for background tasks
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Request permissions
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            Log.d("GeoCam", "Permissions result: Camera=$cameraGranted, Location=$locationGranted, Audio=$audioGranted")

            when {
                cameraGranted -> {
                    Log.d("GeoCam", "Camera permission granted, starting camera")
                    startCamera()
                    if (locationGranted) {
                        Log.d("GeoCam", "Location permission granted, starting location updates")
                        startLocationUpdates()
                    } else {
                        Log.d("GeoCam", "Location permission denied")
                        Toast.makeText(this, "Location permission denied - photos will not have GPS data", Toast.LENGTH_LONG).show()
                    }
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

        // Initialize location services
        initializeLocationServices()

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

        // Check and request permissions
        checkAndRequestPermissions()
    }

    private fun initializeLocationServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(5f)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    Log.d("GeoCam", "Location received: ${location.latitude}, ${location.longitude}")
                    currentLocation = location
                    updateLocationDisplay(location)
                }
            }
        }
    }

    private fun updateLocationDisplay(location: Location) {
        val locationText = "📍 ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}"

        // Get address in background
        mainScope.launch {
            try {
                val address = withContext(Dispatchers.IO) {
                    getAddressFromLocation(location)
                }
                currentAddress = address
                // Update UI overlay
                runOnUiThread {
                    binding.locationOverlay.text = "$locationText\n$address"
                }
            } catch (e: Exception) {
                Log.e("GeoCam", "Error getting address", e)
                runOnUiThread {
                    binding.locationOverlay.text = locationText
                }
            }
        }
    }

    private fun getAddressFromLocation(location: Location): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses: List<Address>? = geocoder.getFromLocation(
                location.latitude, location.longitude, 1
            )

            if (addresses?.isNotEmpty() == true) {
                val address = addresses[0]
                buildString {
                    address.featureName?.let { append("$it, ") }
                    address.locality?.let { append("$it, ") }
                    address.adminArea?.let { append("$it, ") }
                    address.countryName?.let { append(it) }
                }.removeSuffix(", ")
            } else {
                "Address not found"
            }
        } catch (e: Exception) {
            Log.e("GeoCam", "Geocoder error", e)
            "Address unavailable"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            Log.d("GeoCam", "Starting location updates")
            isLocationEnabled = true

            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )

                // Get last known location immediately
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        Log.d("GeoCam", "Got last known location: ${it.latitude}, ${it.longitude}")
                        currentLocation = it
                        updateLocationDisplay(it)
                    } ?: run {
                        Log.d("GeoCam", "No last known location available")
                    }
                }.addOnFailureListener {
                    Log.e("GeoCam", "Failed to get last location", it)
                }
            } catch (e: Exception) {
                Log.e("GeoCam", "Error starting location updates", e)
            }
        } else {
            Log.e("GeoCam", "Location permission not granted")
        }
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

        Log.d("GeoCam", "Required permissions: ${requiredPermissions.joinToString()}")
        Log.d("GeoCam", "Denied permissions: ${deniedPermissions.joinToString()}")

        if (deniedPermissions.isEmpty()) {
            Log.d("GeoCam", "All permissions already granted")
            startCamera()
            startLocationUpdates()
        } else {
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
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
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

        // Toggle location overlay
        binding.locationToggleButton.setOnClickListener {
            toggleLocationOverlay()
        }
    }

    private fun toggleLocationOverlay() {
        isLocationEnabled = !isLocationEnabled
        if (isLocationEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            binding.locationOverlay.visibility = android.view.View.VISIBLE
            Toast.makeText(this, "Location overlay enabled", Toast.LENGTH_SHORT).show()
        } else {
            stopLocationUpdates()
            binding.locationOverlay.visibility = android.view.View.GONE
            Toast.makeText(this, "Location overlay disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e("GeoCam", "Error stopping location updates", e)
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

        // Set 4:3 aspect ratio for both preview and image capture
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = androidx.camera.video.VideoCapture.withOutput(recorder)

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
                    binding.cameraCaptureButton.isEnabled = true
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("GeoCam", "Photo saved successfully to: ${photoFile.absolutePath}")

                    // Always add location overlay if location is available
                    if (currentLocation != null) {
                        Log.d("GeoCam", "Adding location overlay to photo")
                        mainScope.launch {
                            try {
                                val processedFile = addLocationOverlayToImage(photoFile)
                                scanMediaFile(processedFile)
                                Toast.makeText(
                                    baseContext,
                                    "✅ Photo with GPS saved to DCIM/GeoCam",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Log.e("GeoCam", "Error processing image", e)
                                scanMediaFile(photoFile)
                                Toast.makeText(
                                    baseContext,
                                    "✅ Photo saved to DCIM/GeoCam (GPS failed)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } else {
                        Log.d("GeoCam", "No location available for overlay")
                        scanMediaFile(photoFile)
                        Toast.makeText(
                            baseContext,
                            "✅ Photo saved to DCIM/GeoCam (No GPS)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    binding.cameraCaptureButton.isEnabled = true
                }
            }
        )
    }

    private suspend fun addLocationOverlayToImage(imageFile: File): File {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)

                // Calculate overlay dimensions and position
                val overlayHeight = 160
                val overlayY = mutableBitmap.height - overlayHeight

                // Create semi-transparent overlay background
                val overlayPaint = Paint().apply {
                    color = Color.argb(180, 0, 0, 0) // Semi-transparent black
                    style = Paint.Style.FILL
                }

                // Draw overlay background
                canvas.drawRect(0f, overlayY.toFloat(), mutableBitmap.width.toFloat(), mutableBitmap.height.toFloat(), overlayPaint)

                // Create text paint
                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 28f
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

                // Prepare location text like in your example
                val location = currentLocation!!
                val dateTime = SimpleDateFormat("EEEE, dd MMMM yyyy hh:mm a", Locale.getDefault())
                    .format(Date())

                // Format location text exactly like your example
                val locationTitle = if (currentAddress != "Address unavailable" && currentAddress.isNotEmpty()) {
                    currentAddress.split(",").firstOrNull()?.trim() ?: "Location"
                } else {
                    "Unknown Location"
                }

                val locationSubtitle = if (currentAddress != "Address unavailable" && currentAddress.isNotEmpty()) {
                    currentAddress
                } else {
                    "Address not available"
                }

                val coordinates = "Lat ${String.format("%.4f", location.latitude)}° ${if(location.latitude >= 0) "N" else "S"}    Long ${String.format("%.4f", Math.abs(location.longitude))}° ${if(location.longitude >= 0) "E" else "W"}"

                // Draw text lines
                val padding = 20f
                val lineHeight = 32f
                var yPos = overlayY + padding + 25f

                // Draw title (larger)
                val titlePaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 32f
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                canvas.drawText(locationTitle, padding, yPos, titlePaint)
                yPos += lineHeight

                // Draw subtitle
                canvas.drawText(locationSubtitle, padding, yPos, textPaint)
                yPos += lineHeight

                // Draw coordinates
                canvas.drawText(coordinates, padding, yPos, textPaint)
                yPos += lineHeight

                // Draw timestamp
                canvas.drawText(dateTime, padding, yPos, textPaint)

                // Save the processed image
                val outputStream = FileOutputStream(imageFile)
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                outputStream.close()

                Log.d("GeoCam", "Location overlay added successfully")
                imageFile
            } catch (e: Exception) {
                Log.e("GeoCam", "Error adding overlay to image", e)
                throw e
            }
        }
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

    // Rest of the methods remain the same...
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
        stopLocationUpdates()
        mainScope.cancel()
        cameraExecutor.shutdown()
    }

    override fun onPause() {
        super.onPause()
        if (isLocationEnabled) {
            stopLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isLocationEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }
}
