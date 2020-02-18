package com.jonahstarling.pastiche

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.jonahstarling.pastiche.tflite.ArtisticStyleTransfer
import com.jonahstarling.pastiche.utility.BitmapHelper
import kotlinx.android.synthetic.main.fragment_camera.*
import java.io.File
import java.lang.Math.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor

class CameraFragment : Fragment() {

    private lateinit var outputDirectory: File
    private lateinit var mainExecutor: Executor

    private val bitmapHelper = BitmapHelper()

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var imageCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasPermissions(requireContext())) {
            // Request camera-related permissions
            requestPermissions(PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
        }

        mainExecutor = ContextCompat.getMainExecutor(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        camera_switch_button.setOnClickListener { switchCameras() }
        camera_capture_button.setOnClickListener { captureImage() }
        collection_button.setOnClickListener { convertImage() }
        delete_capture_button.setOnClickListener { deleteCapture() }

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        if (hasPermissions(requireContext())) {
            view_finder.post {
                bindCamera()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (PackageManager.PERMISSION_GRANTED == grantResults.firstOrNull()) {
                // Take the user to the success fragment when permission is granted
                Toast.makeText(context, "Permission request granted", Toast.LENGTH_LONG).show()
                view_finder.post {
                    bindCamera()
                }
            } else {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Declare and bind preview, capture and analysis use cases
    private fun bindCamera() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { view_finder.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = view_finder.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

            // Default PreviewSurfaceProvider
            preview?.setSurfaceProvider(view_finder.previewSurfaceProvider)

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits requested capture mode
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            // Must unbind the use-cases before rebinding them.
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview, imageCapture
                )
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, mainExecutor)
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    // Define callback that will be triggered after a photo has been taken and saved to disk
    private val imageCapturedListener = object : ImageCapture.OnImageCapturedCallback() {
        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "Photo capture failed:", exception)
        }

        override fun onCaptureSuccess(image: ImageProxy) {
            // Handle image captured
            imageCaptured = true
            displayTakenPicture(image)
        }
    }

    private fun switchCameras() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        // Bind use cases
        bindCamera()
    }

    private fun captureImage() {
        // Get a stable reference of the modifiable image capture use case
        imageCapture?.setTargetAspectRatioCustom(Rational(1, 1))
        imageCapture?.let { imageCapture ->

            // Create output file to hold the image
            val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

            // Setup image capture metadata
            val metadata = ImageCapture.Metadata().apply {

                // Mirror image when using the front camera
                isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
            }
            // Create output file options to pass to image capture
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(mainExecutor, imageCapturedListener)

            // Display flash animation to indicate that photo was captured
            view_finder.postDelayed({
                view_finder.foreground = ColorDrawable(Color.WHITE)
                view_finder.postDelayed(
                    { view_finder.foreground = null }, ANIMATION_FAST_MILLIS)
            }, ANIMATION_SLOW_MILLIS)
        }
    }

    private fun convertImage() {
        if (imageCaptured) {
            val contentBitmap = (content_image.drawable as BitmapDrawable).bitmap
            val stylizedBitmap = ArtisticStyleTransfer(
                requireContext(),
                contentBitmap
            ).demo()
            content_image.setImageBitmap(stylizedBitmap)
        }
    }

    // TODO: Find a better way to do this
    // There has to be a better way to do this if I'm having to suppress
    // an unsafe experimental usage error. Will revisit.
    @SuppressLint("UnsafeExperimentalUsageError")
    private fun displayTakenPicture(imageProxy: ImageProxy) {
        imageProxy.image?.let { image ->
            content_image.visibility = View.VISIBLE

            // Fix the image via rotating, cropping, and flipping (only front camera)
            val rotatedBitmap = bitmapHelper.rotateImage(bitmapHelper.imageToBitmap(image), imageProxy.imageInfo.rotationDegrees.toFloat())
            val croppedBitmap = bitmapHelper.cropCenter(rotatedBitmap)
            val finalBitmap = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                bitmapHelper.flipImage(croppedBitmap)
            } else {
                croppedBitmap
            }
            content_image.setImageBitmap(finalBitmap)

            hideCaptureImageButton()
            showDeleteCaptureButton()
        }
    }

    private fun deleteCapture() {
        hideDeleteCaptureButton()
        showCaptureImageButton()

        content_image.visibility = View.INVISIBLE
        content_image.setImageBitmap(null)
    }

    private fun showCaptureImageButton() {
        camera_capture_button.visibility = View.VISIBLE
        camera_capture_button.isEnabled = true
        imageCaptured = false
    }

    private fun hideCaptureImageButton() {
        camera_capture_button.visibility = View.INVISIBLE
        camera_capture_button.isEnabled = false
    }

    private fun showDeleteCaptureButton() {
        delete_capture_button.visibility = View.VISIBLE
        delete_capture_button.isEnabled = true
    }

    private fun hideDeleteCaptureButton() {
        delete_capture_button.visibility = View.INVISIBLE
        delete_capture_button.isEnabled = false

    }

    companion object {
        private const val TAG = "Pastiche"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        private const val ANIMATION_FAST_MILLIS = 50L
        private const val ANIMATION_SLOW_MILLIS = 100L

        private const val PERMISSIONS_REQUEST_CODE = 10
        private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

        // Helper function used to create a timestamped file
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension)

        // Convenience method used to check if all permissions required by this app are granted
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}