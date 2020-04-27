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
import androidx.recyclerview.widget.GridLayoutManager
import com.jonahstarling.pastiche.data.ArtworkRepository
import com.jonahstarling.pastiche.tflite.ArtisticStyleTransfer
import com.jonahstarling.pastiche.utility.BitmapHelper
import com.jonahstarling.pastiche.utility.CameraHelper
import kotlinx.android.synthetic.main.camera_controls.*
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.android.synthetic.main.information_header.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext

class CameraFragment : Fragment(), ArtworkAdapter.OnArtSelectedListener, CoroutineScope {

    private lateinit var mainExecutor: Executor

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    private val bitmapHelper = BitmapHelper()
    private val cameraHelper = CameraHelper()

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var imageCaptured = false
    private var contentBitmap: Bitmap? = null

    private lateinit var artworkAdapter: ArtworkAdapter
    private lateinit var artworkLayoutManager: GridLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasPermissions(requireContext())) {
            requestPermissions(PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
        }

        mainExecutor = ContextCompat.getMainExecutor(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        artworkAdapter = ArtworkAdapter(this.requireContext(), ArtworkRepository.localArtworks)
        artworkAdapter.artAdapterListener = this
        artworkLayoutManager = GridLayoutManager(activity, 3, GridLayoutManager.HORIZONTAL, false)
        art_grid.adapter = artworkAdapter
        art_grid.layoutManager = artworkLayoutManager

        help_button.setOnClickListener { helpTapped() }
        camera_switch_button.setOnClickListener { switchCamerasTapped() }
        collection_button.setOnClickListener { onContentSelected() }
        camera_capture_button.setOnClickListener { captureImageTapped() }
        delete_capture_button.setOnClickListener { deleteCaptureTapped() }
        camera_button.setOnClickListener { returnToCamera() }

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
                view_finder.post {
                    bindCamera()
                }
            } else {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
                // TODO: Add some message to let user know that the camera is needed
            }
        }
    }

    // Declare and bind preview and capture use cases
    private fun bindCamera() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { view_finder.display.getRealMetrics(it) }
        val screenAspectRatio = cameraHelper.aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = view_finder.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Setup the Preview to display to view finder
            preview = Preview.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(rotation)
                .build()
            preview?.setSurfaceProvider(view_finder.previewSurfaceProvider)

            // Setup the Image Capture so the user can take a photo
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(rotation)
                .build()
            cameraProvider.unbindAll()

            try {
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview, imageCapture
                )
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, mainExecutor)
    }

    // Define callback that will be triggered after a photo has been captured
    private val imageCapturedListener = object : ImageCapture.OnImageCapturedCallback() {
        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "Photo capture failed:", exception)
        }

        override fun onCaptureSuccess(image: ImageProxy) {
            imageCaptured = true
            displayTakenPicture(image)
        }
    }

    private fun switchCamerasTapped() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        // Rebind use cases
        bindCamera()
    }

    private fun captureImageTapped() {
        // Get a stable reference of the modifiable image capture use case
        imageCapture?.setTargetAspectRatioCustom(Rational(1, 1))
        imageCapture?.let { imageCapture ->

            // Setup image captured listener which is triggered after photo has been taken
            imageCapture.takePicture(mainExecutor, imageCapturedListener)

            // Display a "flash" animation to indicate that photo was captured
            view_finder.postDelayed({
                view_finder.foreground = ColorDrawable(Color.WHITE)
                view_finder.postDelayed(
                    { view_finder.foreground = null }, ANIMATION_FAST_MILLIS)
            }, ANIMATION_SLOW_MILLIS)
        }
    }

    private suspend fun convertImage(id: Int) {
        if (imageCaptured) {
            withContext(Dispatchers.IO) {
                contentBitmap?.let {
                    val stylizedBitmap = ArtisticStyleTransfer(
                        requireContext(),
                        it
                    ).apply(id)
                    withContext(Dispatchers.Main) {
                        content_image.setImageBitmap(stylizedBitmap)
                    }
                }
            }
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
            imageProxy.close()
            content_image.setImageBitmap(finalBitmap)
            user_thumbnail.setPadding(0, 0, 0, 0)
            user_thumbnail.setImageBitmap(finalBitmap)

            hideCaptureImageButton()
            hideFlipCameraButton()
            showArtCollectionButton()
            showDeleteCaptureButton()
            contentBitmap = (content_image.drawable as BitmapDrawable).bitmap
        }
    }

    override fun onArtworkSelected(id: Int) {
        art_thumbnail.setPadding(0, 0, 0 ,0)
        art_thumbnail.setImageResource(id)
        launch {
            convertImage(id)
        }
        hideArtGrid()
        hideCameraButton()
        showArtCollectionButton()
    }

    private fun onContentSelected() {
        hideArtCollectionButton()
        showArtGrid()
        showCameraButton()
    }
    
    private fun helpTapped() {
        // TODO: Add Help popup
    }

    private fun deleteCaptureTapped() {
        hideArtGrid()
        hideDeleteCaptureButton()
        hideArtCollectionButton()
        hideCameraButton()
        showFlipCameraButton()
        showCaptureImageButton()

        content_image.visibility = View.INVISIBLE
        content_image.setImageBitmap(null)
        val paddingDp = (resources.displayMetrics.density * 10).toInt()
        art_thumbnail.setPadding(paddingDp, paddingDp, paddingDp, paddingDp)
        art_thumbnail.setImageResource(R.drawable.ic_gallery_white)
        user_thumbnail.setPadding(paddingDp, paddingDp, paddingDp, paddingDp)
        user_thumbnail.setImageResource(R.drawable.ic_person_white)
    }

    private fun returnToCamera() {
        hideArtGrid()
        hideCameraButton()
        showArtCollectionButton()
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

    private fun showFlipCameraButton() {
        camera_switch_button.visibility = View.VISIBLE
        camera_switch_button.isEnabled = true
    }

    private fun hideFlipCameraButton() {
        camera_switch_button.visibility = View.INVISIBLE
        camera_switch_button.isEnabled = false
    }

    private fun showArtCollectionButton() {
        collection_button.visibility = View.VISIBLE
        collection_button.isEnabled = true
    }

    private fun hideArtCollectionButton() {
        collection_button.visibility = View.INVISIBLE
        collection_button.isEnabled = false
    }

    private fun showArtGrid() {
        art_grid.visibility = View.VISIBLE
    }

    private fun hideArtGrid() {
        art_grid.visibility = View.GONE
    }

    private fun showCameraButton() {
        camera_button.visibility = View.VISIBLE
        camera_button.isEnabled = true
    }

    private fun hideCameraButton() {
        camera_button.visibility = View.GONE
        camera_button.isEnabled = false
    }

    companion object {
        private const val TAG = "Pastiche"

        private const val ANIMATION_FAST_MILLIS = 50L
        private const val ANIMATION_SLOW_MILLIS = 100L

        private const val PERMISSIONS_REQUEST_CODE = 10
        private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

        // Convenience method used to check if all permissions required by this app are granted
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}