package com.example.thermalprinterapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.print.PrintHelper
import java.io.IOException
import java.util.UUID


class WebAppInterface(private val activity: AppCompatActivity, private val webView: WebView) {

    // Standard Serial Port Service ID (SPP) for Thermal Printers
    private val PRINTER_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val biometricHelper = BiometricHelper(activity)

    @JavascriptInterface
    fun saveUserCredentials(username: String, pass: String) {
        activity.runOnUiThread {
            biometricHelper.saveCredentials(username, pass)
        }
    }

    @JavascriptInterface
    fun openDialer(phoneNumber: String?) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phoneNumber))
        activity.startActivity(intent)
    }

    @JavascriptInterface
    fun openEmailClient(email: String?) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email))
        activity.startActivity(intent)
    }

    @JavascriptInterface
    fun printInvoiceImageBase64(base64Image: String) {
        // Run Bluetooth operations on a background thread to prevent UI freeze
        Thread {
            val thermalSuccess = tryThermalPrint(base64Image)

            if (!thermalSuccess) {
                // If thermal fails (no printer/error), switch to UI thread and use System Print
                activity.runOnUiThread {
                    printViaSystem(base64Image)
                }
            }
        }.start()
    }

    @JavascriptInterface
    fun isFingerprintAvailable(): Boolean {
        return biometricHelper.hasSavedCredentials()
    }

    @JavascriptInterface
    fun requestBiometricLogin() {
        activity.runOnUiThread {
            biometricHelper.authenticate { u, p ->
                val json = "{\"username\": \"$u\", \"password\": \"$p\"}"
                val safeJson = json.replace("'", "\\'")
                webView.evaluateJavascript("window.onBiometricSuccess && window.onBiometricSuccess('$safeJson')", null)
            }
        }
    }

    @JavascriptInterface
    fun shareImageWhatsApp(base64Data: String, message: String) {
        activity.runOnUiThread {
            val handler = DownloadHandler(activity)
            handler.shareImageToWhatsApp(base64Data, message)
        }
    }

    @JavascriptInterface
    fun setPullToRefreshEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            (activity as? MainActivity)?.setPullToRefreshEnabled(enabled)
        }
    }

    @JavascriptInterface
    fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        val handler = DownloadHandler(activity)
        handler.saveBase64File(base64Data, fileName, mimeType)
    }

    /**
     * Tries to print to a paired Bluetooth Thermal Printer.
     * Returns true if successful, false otherwise.
     */
    private fun tryThermalPrint(base64: String): Boolean {
        // 1. Check Permissions (Critical for Android 12+)
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return false

        // 2. Find Paired Printer (Explicitly typed as BluetoothDevice)
        val printer: BluetoothDevice = adapter.bondedDevices.firstOrNull {
            it.name != null && (
                    it.name.contains("mt", true) ||
                            it.name.contains("printer", true) ||
                            it.name.contains("thermal", true) ||
                            it.name.contains("pos", true) ||
                            it.name.contains("rpp", true)
                    )
        } ?: return false // Exit if no printer found

        return try {
            // 3. Connect Socket
            val socket: BluetoothSocket = printer.createRfcommSocketToServiceRecord(PRINTER_UUID)
            socket.connect()

            // 4. Decode Image
            val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // 5. Resize to 384px (Standard 2-inch width)
            // This reduces data size significantly, preventing buffer overflows
            if (bmp.width != 384) {
                val aspectRatio = bmp.height.toFloat() / bmp.width.toFloat()
                val newHeight = (384 * aspectRatio).toInt()
                bmp = Bitmap.createScaledBitmap(bmp, 384, newHeight, true)
            }

            // 6. Encode & Send (with Delay logic)
            // EscPosEncoder now returns List<ByteArray> (chunks)
            val chunks = EscPosEncoder.encode(bmp)
            val outputStream = socket.outputStream

            for (chunk in chunks) {
                outputStream.write(chunk)
                outputStream.flush()

                // 🟢 CRITICAL DELAY: 50ms pause between chunks allows printer buffer to clear.
                // This prevents "Gibberish" printing on long labels.
                try { Thread.sleep(50) } catch (e: InterruptedException) { }
            }

            // Final wait to ensure print finishes before closing connection
            Thread.sleep(500)
            socket.close()
            true
        } catch (e: IOException) {
            Log.e("THERMAL", "Print failed", e)
            false
        }
    }

    private fun printViaSystem(base64: String) {
        try {
            val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            if (bmp != null) {
                val printHelper = PrintHelper(activity)
                printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT
                printHelper.printBitmap("Invoice", bmp)
            } else {
                Toast.makeText(activity, "Image Error", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "System Print Failed", Toast.LENGTH_SHORT).show()
        }
    }
}