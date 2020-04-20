package com.jonahstarling.pastiche.tflite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.jonahstarling.pastiche.utility.BitmapHelper
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel.MapMode.READ_ONLY
import java.io.FileInputStream
import java.nio.ByteBuffer

class ArtisticStyleTransfer(private val context: Context, private val contentBitmap: Bitmap) {

    private val bitmapHelper = BitmapHelper()
    private var stylePredictModel: MappedByteBuffer?
    private var styleTransferModel: MappedByteBuffer?

    init {
        stylePredictModel = loadMappedByteBufferFromAssets("style_predict_quantized_256.tflite")
        styleTransferModel = loadMappedByteBufferFromAssets("style_transfer_quantized_dynamic.tflite")
    }

    fun apply(id: Int): Bitmap? {
        val styleImageBitmap = BitmapFactory.decodeResource(context.resources, id)
        val styleImage = bitmapHelper.bitmapToByteBuffer(styleImageBitmap, 256, 256)
        val styleBottleneck = runStylePredictionModel(styleImage)
        val contentByteBuffer = bitmapHelper.bitmapToByteBuffer(contentBitmap, 384, 384)
        return if (styleBottleneck != null) {
            runStyleTransferModel(contentByteBuffer, styleBottleneck)
        } else {
            Log.e(TAG, "Error getting style bottleneck.")
            null
        }
    }

    private fun runStylePredictionModel(styleImage: ByteBuffer): TensorBuffer? {
        if (stylePredictModel == null) {
            stylePredictModel = loadMappedByteBufferFromAssets("style_predict_quantized_256.tflite")
        }
        stylePredictModel?.let { stylePredictModel ->
            val interpreter = Interpreter(stylePredictModel)
            val styleBottleneck = TensorBuffer.createFixedSize(intArrayOf(1, 1, 100), DataType.FLOAT32)

            interpreter.run(styleImage, styleBottleneck.buffer)

            return styleBottleneck
        } ?: run {
            return null
        }
    }

    private fun runStyleTransferModel(contentByteBuffer: ByteBuffer, styleBottleneck: TensorBuffer): Bitmap? {
        if (styleTransferModel == null) {
            styleTransferModel = loadMappedByteBufferFromAssets("style_transfer_quantized_dynamic.tflite")
        }
        styleTransferModel?.let { styleTransferModel ->
            val interpreter = Interpreter(styleTransferModel)

            val input = arrayOf(contentByteBuffer, styleBottleneck.buffer)
            val output = HashMap<Int, Any>()
            val outputArray = Array(1) { Array(384) { Array(384) { FloatArray(3) } } }
            output[0] = outputArray
            interpreter.runForMultipleInputsOutputs(input, output)

            return bitmapHelper.convertArrayToBitmap(outputArray, 384, 384)
        } ?: run {
            return null
        }
    }

    private fun loadMappedByteBufferFromAssets(fileName: String): MappedByteBuffer? {
        return try {
            val fileDescriptor = context.assets.openFd(fileName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength

            val fileChannel = inputStream.channel
            fileChannel.map(READ_ONLY, startOffset, declaredLength)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading model", e)
            null
        }
    }


    companion object {
        private const val TAG = "ArtisticStyleTransfer"
    }
}