/*
package com.example.thermalprinterapp

import android.graphics.Bitmap
import java.io.OutputStream

fun printBitmap(bitmap: Bitmap, outputStream: OutputStream) {
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        384,
        (bitmap.height * (384.0 / bitmap.width)).toInt(),
        false // 🚀 VERY IMPORTANT
    )


    val data = EscPosEncoder.encode(scaled)
    outputStream.write(data)
    outputStream.flush()
}
*/
