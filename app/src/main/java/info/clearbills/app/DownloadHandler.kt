package info.clearbills.app

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import java.io.File
import java.net.URL
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.FileOutputStream

class DownloadHandler(private val context: Context) {

    fun downloadFile(url: String, fileName: String, mimeType: String, userAgent: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadViaMediaStore(url, fileName, mimeType, userAgent)
        } else {
            downloadViaDownloadManager(url, fileName, mimeType, userAgent)
        }
    }

    private fun downloadViaDownloadManager(url: String, fileName: String, mimeType: String, userAgent: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Downloading file...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                allowScanningByMediaScanner()
            }
            val dm = context.getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        Thread {
            try {
                val fileBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val resolver = context.contentResolver

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Failed to create MediaStore entry")

                resolver.openOutputStream(uri)?.use { output ->
                    output.write(fileBytes)
                }

                // 🟢 THE FIX: Manually trigger the notification
                triggerNotification(uri, fileName, mimeType)

                (context as? AppCompatActivity)?.runOnUiThread {
                    Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun shareImageToWhatsApp(base64Data: String, message: String) {
        var contentUri: Uri? = null
        try {
            val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
            val cachePath = File(context.cacheDir, "images")
            cachePath.mkdirs()
            val file = File(cachePath, "shared_invoice.png")
            val stream = FileOutputStream(file)
            stream.write(imageBytes)
            stream.close()

            contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // 🟢 THE FIX: Create intent and target WhatsApp package directly
            val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // This line removes the "Choose App" popup and targets WhatsApp
                setPackage("com.whatsapp")
            }

            // Start activity directly instead of using Intent.createChooser
            context.startActivity(whatsappIntent)

        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: If WhatsApp isn't installed, show the chooser as a backup
            if (contentUri != null) {
                val genericIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(genericIntent, "Share via"))
            }
        }
    }

    private fun triggerNotification(fileUri: Uri, fileName: String, mimeType: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "download_channel"

        // 1. Create Channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        // 2. Intent to open the file
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Build Notification
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText("Download complete. Tap to open.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun downloadViaMediaStore(url: String, fileName: String, mimeType: String, userAgent: String) {
        Thread {
            try {
                val conn = URL(url).openConnection()
                conn.setRequestProperty("User-Agent", userAgent)
                val input = conn.getInputStream()

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Failed to create file")

                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                }

                (context as? AppCompatActivity)?.runOnUiThread {
                    Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                (context as? AppCompatActivity)?.runOnUiThread {
                    Toast.makeText(context, "Download Error", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}