package info.clearbills.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

object BitmapUtils {

    // Helper to scale image to fit printer width (default 384px for 2-inch)
    fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width == targetWidth) return src
        val ratio = targetWidth.toFloat() / src.width
        val height = (src.height * ratio).roundToInt()
        return Bitmap.createScaledBitmap(src, targetWidth, height, false)
    }

    // Helper to convert color image to Black/White dots (Dithering)
    fun toDitheredBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val gray = Array(h) { IntArray(w) }

        for (y in 0 until h)
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                gray[y][x] = (0.299 * Color.red(p) +
                        0.587 * Color.green(p) +
                        0.114 * Color.blue(p)).toInt()
            }

        for (y in 0 until h)
            for (x in 0 until w) {
                val old = gray[y][x]
                val new = if (old < 128) 0 else 255
                val err = old - new

                out.setPixel(x, y, if (new == 0) Color.BLACK else Color.WHITE)

                if (x + 1 < w) gray[y][x + 1] += err * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) gray[y + 1][x - 1] += err * 3 / 16
                    gray[y + 1][x] += err * 5 / 16
                    if (x + 1 < w) gray[y + 1][x + 1] += err / 16
                }
            }
        return out
    }

    // Helper to convert Bitmap to ESC/POS Bytes
    fun bitmapToEscPos(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val bytes = ArrayList<Byte>()

        bytes.addAll(byteArrayOf(0x1B, 0x61, 0x01).toList()) // Align Center

        var y = 0
        while (y < h) {
            bytes.addAll(
                byteArrayOf(
                    0x1B, 0x2A, 33,
                    (w and 0xFF).toByte(),
                    ((w shr 8) and 0xFF).toByte()
                ).toList()
            )

            for (x in 0 until w) {
                for (k in 0..2) {
                    var slice = 0
                    for (b in 0..7) {
                        val yy = y + k * 8 + b
                        if (yy < h && bitmap.getPixel(x, yy) == Color.BLACK) {
                            slice = slice or (1 shl (7 - b))
                        }
                    }
                    bytes.add(slice.toByte())
                }
            }
            // ✅ THE FIX: Use ESC J (0x4A) to feed exactly 24 dots
            bytes.addAll(byteArrayOf(0x1B, 0x4A, 24).toList())
            y += 24
        }

        bytes.addAll(byteArrayOf(0x1B, 0x61, 0x00).toList()) // Reset Align
        bytes.addAll(byteArrayOf(0x1B, 0x64, 0x02).toList()) // Feed 2 lines
        return bytes.toByteArray()
    }
}