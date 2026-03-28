package info.clearbills.app

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

object EscPosEncoder {

    fun encode(bitmap: Bitmap): List<ByteArray> {
        val width = bitmap.width
        val height = bitmap.height
        // Calculate bytes per line (width / 8, rounded up)
        val bytesPerLine = (width + 7) / 8

        val chunks = mutableListOf<ByteArray>()

        // 1. Initialize Printer
        val init = ByteArrayOutputStream()
        init.write(0x1B) // ESC
        init.write(0x40) // @
        chunks.add(init.toByteArray())

        // 2. Chunking Logic (Split image into 100px slices)
        val maxChunkHeight = 100
        var y = 0

        while (y < height) {
            val output = ByteArrayOutputStream()
            val remainingHeight = height - y
            val chunkHeight = if (remainingHeight > maxChunkHeight) maxChunkHeight else remainingHeight

            // ESC/POS Command: GS v 0 (Raster Bit Image)
            output.write(0x1D) // GS
            output.write(0x76) // v
            output.write(0x30) // 0
            output.write(0x00) // m (Normal)

            // xL, xH (Width in bytes)
            output.write(bytesPerLine and 0xFF)
            output.write((bytesPerLine shr 8) and 0xFF)

            // yL, yH (Height in dots)
            output.write(chunkHeight and 0xFF)
            output.write((chunkHeight shr 8) and 0xFF)

            // Write Pixel Data
            for (row in 0 until chunkHeight) {
                val currentY = y + row
                for (x in 0 until bytesPerLine) {
                    var byte = 0
                    for (bit in 0 until 8) {
                        val px = x * 8 + bit
                        if (px < width) {
                            val p = bitmap.getPixel(px, currentY)

                            // Extract RGB
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF

                            // Grayscale brightness (Luminance)
                            val gray = (r * 0.299 + g * 0.587 + b * 0.114)

                            // If darker than 128, print black dot (set bit to 1)
                            if (gray < 128) {
                                byte = byte or (1 shl (7 - bit))
                            }
                        }
                    }
                    output.write(byte)
                }
            }
            chunks.add(output.toByteArray())
            y += chunkHeight
        }

        // 3. Final Feed & Cut
        val footer = ByteArrayOutputStream()

        // Feed 3 lines (ESC d 3) to clear print head
        footer.write(0x1B)
        footer.write(0x64)
        footer.write(0x03)

        // Partial Cut (GS V 66 0)
        footer.write(0x1D)
        footer.write(0x56)
        footer.write(0x42)
        footer.write(0x00)

        chunks.add(footer.toByteArray())

        return chunks
    }
}