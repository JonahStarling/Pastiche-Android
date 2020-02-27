package com.jonahstarling.pastiche.utility

import android.graphics.*
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BitmapHelper {
    
    fun cropCenter(source: Bitmap): Bitmap {
        return when {
            source.width > source.height -> {
                Bitmap.createBitmap(source,source.width / 2 - source.height / 2,0, source.height, source.height)
            }
            source.width < source.height -> {
                Bitmap.createBitmap(source,0,source.height / 2 - source.width / 2, source.width, source.width)
            }
            else -> source
        }
    }

    fun rotateImage(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun flipImage(source: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun imageToBitmap(image: Image): Bitmap {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // From the TensorFlow Lite Style Transfer Android Demo
    // https://github.com/tensorflow/examples/tree/master/lite/examples/style_transfer/android
    private fun scaleBitmapAndKeepRatio(
        targetBmp: Bitmap,
        reqHeightInPixels: Int,
        reqWidthInPixels: Int
    ): Bitmap {
        if (targetBmp.height == reqHeightInPixels && targetBmp.width == reqWidthInPixels) {
            return targetBmp
        }
        val matrix = Matrix()
        matrix.setRectToRect(
            RectF(
                0f, 0f,
                targetBmp.width.toFloat(),
                targetBmp.width.toFloat()
            ),
            RectF(
                0f, 0f,
                reqWidthInPixels.toFloat(),
                reqHeightInPixels.toFloat()
            ),
            Matrix.ScaleToFit.FILL
        )
        return Bitmap.createBitmap(
            targetBmp, 0, 0,
            targetBmp.width,
            targetBmp.width, matrix, true
        )
    }

    // From the TensorFlow Lite Style Transfer Android Demo
    // https://github.com/tensorflow/examples/tree/master/lite/examples/style_transfer/android
    fun bitmapToByteBuffer(
        bitmapIn: Bitmap,
        width: Int,
        height: Int,
        mean: Float = 0.0f,
        std: Float = 255.0f
    ): ByteBuffer {
        val bitmap = scaleBitmapAndKeepRatio(bitmapIn, width, height)
        val inputImage = ByteBuffer.allocateDirect(1 * width * height * 3 * 4)
        inputImage.order(ByteOrder.nativeOrder())
        inputImage.rewind()

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)
        var pixel = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = intValues[pixel++]

                // Normalize channel values to [-1.0, 1.0]. This requirement varies by
                // model. For example, some models might require values to be normalized
                // to the range [0.0, 1.0] instead.
                inputImage.putFloat(((value shr 16 and 0xFF) - mean) / std)
                inputImage.putFloat(((value shr 8 and 0xFF) - mean) / std)
                inputImage.putFloat(((value and 0xFF) - mean) / std)
            }
        }

        inputImage.rewind()
        return inputImage
    }

    // From the TensorFlow Lite Style Transfer Android Demo
    // https://github.com/tensorflow/examples/tree/master/lite/examples/style_transfer/android
    fun convertArrayToBitmap(
        imageArray: Array<Array<Array<FloatArray>>>,
        imageWidth: Int,
        imageHeight: Int
    ): Bitmap {
        val conf = Bitmap.Config.ARGB_8888 // see other conf types
        val styledImage = Bitmap.createBitmap(imageWidth, imageHeight, conf)

        for (x in imageArray[0].indices) {
            for (y in imageArray[0][0].indices) {
                val color = Color.rgb(
                    ((imageArray[0][x][y][0] * 255).toInt()),
                    ((imageArray[0][x][y][1] * 255).toInt()),
                    (imageArray[0][x][y][2] * 255).toInt()
                )

                // this y, x is in the correct order!!!
                styledImage.setPixel(y, x, color)
            }
        }
        return styledImage
    }
}