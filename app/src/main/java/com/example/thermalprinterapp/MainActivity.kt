package com.example.thermalprinterapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message // 🟢 Added for Razorpay window creation
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ⚠️ Ensure this is correct (HTTPS or Local IP)
    private val MAIN_URL = "http://192.168.29.241:3000"
    //private val MAIN_URL = "https://clearbills.info/"

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var downloadHandler: DownloadHandler

    // 🟢 1. WebView File Upload Variables
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // 🟢 2. Activity Result Launcher (Handles the return from Camera/Gallery)
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var results: Array<Uri>? = null

        if (result.resultCode == Activity.RESULT_OK) {
            val dataString = result.data?.dataString
            if (dataString != null) {
                // User picked an image from the Gallery
                results = arrayOf(Uri.parse(dataString))
            } else if (cameraPhotoPath != null) {
                // User took a photo with the Camera
                results = arrayOf(Uri.parse(cameraPhotoPath))
            }
        }

        // Pass the result back to the React WebView (or null if they canceled)
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        downloadHandler = DownloadHandler(this)

        // 1. Initialize Layouts
        swipeRefreshLayout = SwipeRefreshLayout(this)
        webView = WebView(this)

        // 2. Set Layout Params
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.layoutParams = params
        swipeRefreshLayout.layoutParams = params

        // Increase Swipe Distance
        swipeRefreshLayout.setDistanceToTriggerSync(500)

        // 3. Add WebView to SwipeLayout
        swipeRefreshLayout.addView(webView)
        setContentView(swipeRefreshLayout)

        // 4. Setup Logic
        setupWebViewSettings()
        setupSwipeRefresh()
        checkPermissions()


        // 🟢 Default URL
        var urlToLoad = MAIN_URL

        // 🟢 Check if the app was opened from a Push Notification click
        if (intent.extras != null && intent.hasExtra("TARGET_URL")) {
            val targetUrl = intent.getStringExtra("TARGET_URL")
            if (targetUrl != null) {
                urlToLoad = targetUrl // Override default URL with the specific notification URL
            }
        }

        // 5. Add JS Interface & Load
        webView.addJavascriptInterface(WebAppInterface(this, webView), "Android")
        webView.loadUrl(MAIN_URL)

        // 6. Handle Back Button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else isEnabled = false
            }
        })

    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        // Only enable pull-to-refresh if we are at the top of the page
        webView.viewTreeObserver.addOnScrollChangedListener {
            if (swipeRefreshLayout.isEnabled) {
                swipeRefreshLayout.isEnabled = webView.scrollY == 0
            }
        }
    }

    fun setPullToRefreshEnabled(enabled: Boolean) {
        runOnUiThread {
            swipeRefreshLayout.isEnabled = enabled
        }
    }

    private fun setupWebViewSettings() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            // 🟢 Settings required for Razorpay Popups
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            downloadHandler.downloadFile(url, fileName, mimeType, userAgent)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed() // Allow local IP HTTPS
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefreshLayout.isRefreshing = false

                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        Log.d("FCM", "Token fetched, waiting for React to catch it: $token")

                        // 🟢 Smart JS injector: Checks every 500ms until React is fully hydrated
                        val jsCode = """
                    javascript:(function() {
                        var attempts = 0;
                        var checkAndSend = setInterval(function() {
                            if (typeof window.receiveAndroidFcmToken === 'function') {
                                window.receiveAndroidFcmToken('$token');
                                clearInterval(checkAndSend);
                            }
                            attempts++;
                            if (attempts > 10) clearInterval(checkAndSend); // Stop trying after 5 seconds
                        }, 500);
                    })();
                """.trimIndent()

                        view?.evaluateJavascript(jsCode, null)
                    }
                }
            }

            // 🟢 Handles opening external UPI apps (GPay, PhonePe, Paytm)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // If it's a standard web URL, let the WebView handle it
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // If it's a UPI link or Intent, hand it to the Android OS
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.e("WebView", "App not installed or intent failed for URL: $url")
                }

                return true
            }
        }

        // 🟢 3. WebChromeClient Updated with File Chooser & Razorpay Multi-Window Logic
        webView.webChromeClient = object : WebChromeClient() {

            // Handle Camera/Microphone permissions from JS
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            // 🟢 Razorpay Popup Handling (Creates a temporary WebView for Bank OTP/3D Secure)
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val rootLayout = findViewById<ViewGroup>(android.R.id.content)
                rootLayout.addView(newWebView)

                newWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        rootLayout.removeView(newWebView)
                        newWebView.destroy()
                    }
                }

                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            try {
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                startActivity(intent)
                                return true
                            } catch (e: Exception) {
                                Log.e("WebView", "Intent failed in popup")
                            }
                        }
                        return false
                    }
                }

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }

            // Handle <input type="file"> clicks (Camera & Gallery)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                // Cancel any existing file request
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // Intent for the Camera
                var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent?.resolveActivity(packageManager) != null) {
                    var photoFile: File? = null
                    try {
                        photoFile = createImageFile()
                    } catch (ex: IOException) {
                        Log.e("WebView", "Unable to create Image File", ex)
                    }

                    if (photoFile != null) {
                        cameraPhotoPath = "file:${photoFile.absolutePath}"
                        val photoURI: Uri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${applicationContext.packageName}.fileprovider",
                            photoFile
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    } else {
                        takePictureIntent = null
                    }
                }

                // Intent for the Gallery
                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }

                val intentArray: Array<Intent> = takePictureIntent?.let { arrayOf(it) } ?: emptyArray()

                // Combine both intents into a Chooser dialog
                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select Image Source")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                }

                try {
                    fileChooserLauncher.launch(chooserIntent)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }

                return true
            }
        }
    }

    // 🟢 4. Helper to create a temporary file for the Camera to save into
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName, /* prefix */
            ".jpg",        /* suffix */
            storageDir     /* directory */
        )
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }
}