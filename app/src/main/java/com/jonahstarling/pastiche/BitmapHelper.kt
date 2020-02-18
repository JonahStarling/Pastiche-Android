package com.jonahstarling.pastiche

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import java.nio.Buffer

class BitmapHelper {
    
    fun cropCenter(source: Bitmap): Bitmap {
        return when {
            source.width > source.height -> {
                Bitmap.createBitmap(
                    source,
                    source.width / 2 - source.height / 2,
                    0,
                    source.height,
                    source.height
                )
            }
            source.width < source.height -> {
                Bitmap.createBitmap(
                    source,
                    0,
                    source.height / 2 - source.width / 2,
                    source.width,
                    source.width
                )
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

    fun bufferToBitmap(buffer: Buffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}