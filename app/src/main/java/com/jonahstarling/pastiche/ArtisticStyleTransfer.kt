package com.jonahstarling.pastiche

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel.MapMode.READ_ONLY
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import java.io.FileInputStream
import java.nio.Buffer
import java.nio.ByteBuffer

class ArtisticStyleTransfer(private val context: Context, private val contentBitmap: Bitmap) {

    private val contentImage: TensorImage
        get() = convertContentImageToBitmap()

    fun demo(): Bitmap? {
        val styleImage = resizeStyleImage()
        val styleBottleneck = runStylePredictionModel(styleImage)
        return if (styleBottleneck != null) {
            runStyleTransferModel(styleBottleneck)
        } else {
            Log.e("JONAH", "Error getting style bottleneck.")
            null
        }
    }

    private fun convertContentImageToBitmap(): TensorImage {
        val cropSize = Math.min(contentBitmap.width, contentBitmap.height)
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeWithCropOrPadOp(cropSize, cropSize))
            .add(ResizeOp(384, 384, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 1.0f))
            .build()
        var contentImage = TensorImage(DataType.FLOAT32)
        contentImage.load(contentBitmap)
        contentImage = imageProcessor.process(contentImage)
        return contentImage
    }

    private fun resizeStyleImage(): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(256, 256, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 1.0f))
            .build()
        var styleImage = TensorImage(DataType.FLOAT32)
        val styleImageBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.starry_night)
        styleImage.load(styleImageBitmap)
        styleImage = imageProcessor.process(styleImage)
        return styleImage
    }

    private fun runStylePredictionModel(styleImage: TensorImage): TensorBuffer? {
        loadMappedByteBufferFromAssets("style_predict_quantized_256.tflite")?.let { stylePredictModel ->
            val interpreter = Interpreter(stylePredictModel)
            val styleBottleneck = TensorBuffer.createFixedSize(intArrayOf(1, 1, 100), DataType.FLOAT32)

            interpreter.run(styleImage.tensorBuffer.buffer, styleBottleneck.buffer)

            return styleBottleneck
        } ?: run {
            return null
        }
    }

    private fun runStyleTransferModel(styleBottleneck: TensorBuffer): Bitmap? {
        loadMappedByteBufferFromAssets("style_transfer_quantized_dynamic.tflite")?.let { styleTransferModel ->
            val interpreter = Interpreter(styleTransferModel)

            val input = arrayOf(contentImage.tensorBuffer.buffer, styleBottleneck.buffer)
            val output = mapOf(Pair(0, TensorBuffer.createFixedSize(intArrayOf(384, 384, 3), DataType.FLOAT32).buffer))
            interpreter.runForMultipleInputsOutputs(input, output)

            val outputByteBuffer = output[0] as ByteBuffer
            return getBitmap(outputByteBuffer, 384, 384)
        } ?: run {
            return null
        }
    }

    private fun getBitmap(buffer: Buffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
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
            Log.e("tfliteSupport", "Error reading model", e)
            null
        }
    }

}