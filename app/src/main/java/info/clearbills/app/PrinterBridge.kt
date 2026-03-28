package info.clearbills.app

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream

class PrinterBridge(private val context: Context) {

    @JavascriptInterface
    fun printPdf(base64Pdf: String) {
        val pdfBytes = Base64.decode(base64Pdf, Base64.DEFAULT)

        val pdfFile = File(context.cacheDir, "invoice.pdf")
        FileOutputStream(pdfFile).use {
            it.write(pdfBytes)
        }

        ThermalPrinterManager(context).printPdf(pdfFile)
    }
}
