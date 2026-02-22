package com.example.thermalprinterapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.OutputStream
import java.util.*

class ThermalPrinterManager(private val context: Context) {

    // Standard UUID for SPP (Serial Port Profile)
    private val PRINTER_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun printPdf(pdfFile: File) {
        // 1. Convert PDF to Bitmaps
        // Ensure you have a PdfToBitmap utility class or library
        val bitmaps = PdfToBitmap.render(context, pdfFile)

        // 2. Find Printer
        val device = getPairedPrinter()
        if (device == null) {
            Log.e("ThermalPrinter", "No printer found")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("ThermalPrinter", "Missing Bluetooth Permissions")
            return
        }

        try {
            // 3. Connect
            val socket = device.createRfcommSocketToServiceRecord(PRINTER_UUID)
            socket.connect()
            val outputStream = socket.outputStream

            // 4. Print each page
            for (bitmap in bitmaps) {
                printBitmap(bitmap, outputStream)

                // Small delay between pages to prevent buffer overflow
                Thread.sleep(1000)
            }

            outputStream.close()
            socket.close()
        } catch (e: Exception) {
            Log.e("ThermalPrinter", "Print failed", e)
        }
    }

    // 🟢 ADDED: The missing function
    private fun printBitmap(bitmap: Bitmap, outputStream: OutputStream) {
        // Resize to standard 2-inch width (384px) if needed
        var printBmp = bitmap
        if (bitmap.width != 384) {
            val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
            val newHeight = (384 * aspectRatio).toInt()
            printBmp = Bitmap.createScaledBitmap(bitmap, 384, newHeight, true)
        }

        // Use our EscPosEncoder to get chunks
        val chunks = EscPosEncoder.encode(printBmp)

        for (chunk in chunks) {
            outputStream.write(chunk)
            outputStream.flush()
            // Delay to prevent garbage characters
            try { Thread.sleep(50) } catch (e: InterruptedException) { }
        }
    }

    private fun getPairedPrinter(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        // Filter for common thermal printer names or device class
        return adapter.bondedDevices.firstOrNull {
            // Class 1664 is often printers, but name matching is safer
            it.bluetoothClass.deviceClass == 1664 ||
                    it.name.contains("thermal", true) ||
                    it.name.contains("printer", true) ||
                    it.name.contains("pos", true) ||
                    it.name.contains("mt", true)
        }
    }
}