package com.jonahstarling.pastiche

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.*
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
import kotlinx.android.synthetic.main.help_dialog.*
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
    private var imageConverted = false
    private var contentBitmap: Bitmap? = null
    private var convertedBitmap: Bitmap? = null

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
        artGrid.adapter = artworkAdapter
        artGrid.layoutManager = artworkLayoutManager

        helpButton.setOnClickListener { helpTapped() }
        fadedBackground.setOnClickListener { dismissHelp() }
        dismissHelp.setOnClickListener { dismissHelp() }

        cameraSwitchButton.setOnClickListener { switchCamerasTapped() }
        collectionButton.setOnClickListener { onContentSelected() }
        cameraCaptureButton.setOnClickListener { captureImageTapped() }
        deleteCaptureButton.setOnClickListener { deleteCaptureTapped() }
        cameraButton.setOnClickListener { returnToCamera() }
        saveButton.setOnClickListener { saveImage() }
        logo.setOnClickListener { navigateToMediumArticle() }

        if (hasPermissions(requireContext())) {
            viewFinder.post {
                bindCamera()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (PackageManager.PERMISSION_GRANTED == grantResults.firstOrNull()) {
                viewFinder.post {
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
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = cameraHelper.aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = viewFinder.display.rotation

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
            preview?.setSurfaceProvider(viewFinder.previewSurfaceProvider)

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
            viewFinder.postDelayed({
                viewFinder.foreground = ColorDrawable(Color.WHITE)
                viewFinder.postDelayed(
                    { viewFinder.foreground = null }, ANIMATION_FAST_MILLIS)
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
                        convertedBitmap = stylizedBitmap
                        contentImage.setImageBitmap(stylizedBitmap)
                        saveButton.isEnabled = true
                        imageConverted = true
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
            contentImage.visibility = View.VISIBLE

            // Fix the image via rotating, cropping, and flipping (only front camera)
            val rotatedBitmap = bitmapHelper.rotateImage(bitmapHelper.imageToBitmap(image), imageProxy.imageInfo.rotationDegrees.toFloat())
            val croppedBitmap = bitmapHelper.cropCenter(rotatedBitmap)
            val finalBitmap = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                bitmapHelper.flipImage(croppedBitmap)
            } else {
                croppedBitmap
            }
            imageProxy.close()
            contentImage.setImageBitmap(finalBitmap)
            userThumbnail.setPadding(0, 0, 0, 0)
            userThumbnail.setImageBitmap(finalBitmap)

            cameraCaptureButton.visibility = View.GONE
            cameraSwitchButton.visibility = View.GONE

            collectionButton.visibility = View.VISIBLE
            deleteCaptureButton.visibility = View.VISIBLE
            contentBitmap = (contentImage.drawable as BitmapDrawable).bitmap
        }
    }

    private fun saveImage() {
        context?.let {
            MediaStore.Images.Media.insertImage(
                context?.contentResolver,
                convertedBitmap,
                "Pastiche",
                "Image from Pastiche"
            )
            val savedImageToast = Toast.makeText(context, "Image Saved to Gallery", Toast.LENGTH_SHORT)
            val yOffset = ((contentImage.y + contentImage.height / 2) - resources.displayMetrics.heightPixels / 2).toInt()
            savedImageToast.setGravity(Gravity.CENTER, 0, yOffset)
            savedImageToast.show()
        }
    }

    override fun onArtworkSelected(id: Int) {
        convertedBitmap = null
        artThumbnail.setPadding(0, 0, 0 ,0)
        artThumbnail.setImageResource(id)
        launch {
            convertImage(id)

        }
        artGrid.visibility = View.GONE
        cameraButton.visibility = View.GONE
        helpButton.visibility = View.GONE

        collectionButton.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE
        saveButton.isEnabled = false
    }

    private fun onContentSelected() {
        collectionButton.visibility = View.GONE
        saveButton.visibility = View.GONE

        artGrid.visibility = View.VISIBLE
        cameraButton.visibility = View.VISIBLE
        helpButton.visibility = View.VISIBLE
    }
    
    private fun helpTapped() {
        helpDialog.visibility = View.VISIBLE
    }

    private fun dismissHelp() {
        helpDialog.visibility = View.GONE
    }

    private fun navigateToMediumArticle() {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://medium.com/@starling.jonah/artistic-style-transfer-with-tensorflow-lite-on-android-943af9ca28d8?source=friends_link&sk=8c83cf644c459cdac87242425cc24639"))
        startActivity(browserIntent)
    }

    private fun deleteCaptureTapped() {
        artGrid.visibility = View.GONE
        deleteCaptureButton.visibility = View.GONE
        collectionButton.visibility = View.GONE
        cameraButton.visibility = View.GONE
        saveButton.visibility = View.GONE

        cameraSwitchButton.visibility = View.VISIBLE
        cameraCaptureButton.visibility = View.VISIBLE
        helpButton.visibility = View.VISIBLE

        contentImage.visibility = View.INVISIBLE
        contentImage.setImageBitmap(null)
        imageConverted = false
        contentBitmap = null
        convertedBitmap = null
        val paddingDp = (resources.displayMetrics.density * 10).toInt()
        artThumbnail.setPadding(paddingDp, paddingDp, paddingDp, paddingDp)
        artThumbnail.setImageResource(R.drawable.ic_gallery_white)
        userThumbnail.setPadding(paddingDp, paddingDp, paddingDp, paddingDp)
        userThumbnail.setImageResource(R.drawable.ic_person_white)
    }

    private fun returnToCamera() {
        artGrid.visibility = View.GONE
        cameraButton.visibility = View.GONE

        collectionButton.visibility = View.VISIBLE
        if (imageConverted) {
            helpButton.visibility = View.GONE

            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = true
        }
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