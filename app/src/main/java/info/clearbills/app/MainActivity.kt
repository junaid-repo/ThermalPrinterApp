package info.clearbills.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.provider.ContactsContract
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ⚠️ Ensure this is correct (HTTPS or Local IP)
    //private val MAIN_URL = "http://10.229.79.219:3000"
    private val MAIN_URL = "https://clearbills.info/"

    private val GOOGLE_WEB_CLIENT_ID = "642231628593-eso4jie2p3cu670djrtqauq0qh741nk3.apps.googleusercontent.com"

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var downloadHandler: DownloadHandler

     private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

     private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var results: Array<Uri>? = null

        if (result.resultCode == Activity.RESULT_OK) {
            val dataString = result.data?.dataString
            if (dataString != null) {
                results = arrayOf(Uri.parse(dataString))
            } else if (cameraPhotoPath != null) {
                results = arrayOf(Uri.parse(cameraPhotoPath))
            }
        }

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
      //  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Configure Status Bar & Navigation Bar Colors dynamically
        val isSystemInDarkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val windowController = WindowCompat.getInsetsController(window, window.decorView)

        if (isSystemInDarkMode) {
            window.statusBarColor = Color.parseColor("#1A3025FF")
            window.navigationBarColor = Color.parseColor("#1A3025FF")
            windowController.isAppearanceLightStatusBars = false
            windowController.isAppearanceLightNavigationBars = false
        } else {
            window.statusBarColor = Color.parseColor("#faf8f5")
            window.navigationBarColor = Color.parseColor("#faf8f5")
            windowController.isAppearanceLightStatusBars = true
            windowController.isAppearanceLightNavigationBars = true
        }

        // 1. Initialize Handlers and Layouts ONCE
        downloadHandler = DownloadHandler(this)
        swipeRefreshLayout = SwipeRefreshLayout(this)
        webView = WebView(this)

        // Measure system bars and apply exact padding
        // Measure system bars and apply exact padding
        // 🟢 Get screen density to convert Android Pixels to CSS Pixels
        val density = resources.displayMetrics.density

        // Measure system bars and apply exact padding
        ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshLayout) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // 🟢 REMOVED: The 'ime' (keyboard) variables that were manually pushing the screen up

            // Top padding always clears the status bar
            val topPadding = systemBars.top

            // 🟢 THE FIX: Always pad by the nav bar, but NEVER by the keyboard.
            // This allows the keyboard to slide smoothly over the top of the WebView.
            val bottomPadding = navBars.bottom

            view.setPadding(systemBars.left, topPadding, systemBars.right, bottomPadding)

            // 🟢 THE FIX: Cache the nav height globally, and trigger a React state update if available
            val navBarHeightDp = navBars.bottom / density

            webView.post {
                val jsCode = """
                    window.androidNavHeightCache = $navBarHeightDp;
                    if (typeof window.setAndroidNavHeight === 'function') {
                        window.setAndroidNavHeight($navBarHeightDp);
                    }
                """.trimIndent()
                webView.evaluateJavascript(jsCode, null)
            }

            windowInsets
        }
        // 2. Set Layout Params
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.layoutParams = params
        swipeRefreshLayout.layoutParams = params

        swipeRefreshLayout.setDistanceToTriggerSync(500)
        swipeRefreshLayout.addView(webView)
        setContentView(swipeRefreshLayout)

        // 3. Setup Logic
        setupWebViewSettings()
        setupSwipeRefresh()
        checkPermissions()

        var urlToLoad = MAIN_URL

        if (intent.extras != null && intent.hasExtra("TARGET_URL")) {
            val targetUrl = intent.getStringExtra("TARGET_URL")

            if (targetUrl != null) {
                try {
                     val parsedUri = Uri.parse(targetUrl)
                    val host = parsedUri.host ?: ""

                     val isSafeHost = host == "clearbills.info" ||
                            host == "www.clearbills.info" ||
                            host == "clearbill.store" ||
                            host == "www.clearbill.store"


                    if (isSafeHost) {
                        urlToLoad = targetUrl
                    } else {
                        Log.w("Security", "Blocked untrusted intent URL host: $host")
                        urlToLoad = MAIN_URL
                    }
                } catch (e: Exception) {
                    Log.e("Security", "Malformed URL in intent", e)
                    urlToLoad = MAIN_URL
                }
            }
        }
        webView.addJavascriptInterface(WebAppInterface(this, webView), "Android")
        webView.loadUrl(urlToLoad)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else isEnabled = false
            }
        })
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            val urlToLoad = webView.url ?: MAIN_URL
            webView.loadUrl(urlToLoad)
        }

        webView.viewTreeObserver.addOnScrollChangedListener {
            if (swipeRefreshLayout.isEnabled) {
                swipeRefreshLayout.isEnabled = webView.scrollY == 0
            }
        }
    }

    fun startGoogleOneTapLogin() {
        lifecycleScope.launch {
            try {
                val credentialManager = CredentialManager.create(this@MainActivity)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(GOOGLE_WEB_CLIENT_ID)
                    .setAutoSelectEnabled(true)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity
                )

                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken

                    this@MainActivity.runOnUiThread {
                        val jsCode = "javascript:if(window.onNativeGoogleLoginSuccess) { window.onNativeGoogleLoginSuccess('$idToken', null); }"
                        webView.evaluateJavascript(jsCode, null)
                    }
                } else {
                    this@MainActivity.runOnUiThread {
                        webView.evaluateJavascript("javascript:if(window.onNativeGoogleLoginSuccess) { window.onNativeGoogleLoginSuccess(null, 'Unexpected credential type'); }", null)
                    }
                }

            } catch (e: GetCredentialException) {
                Log.e("GoogleLogin", "Login failed or cancelled: ${e.message}")
                val safeError = e.message?.replace("'", "\\'") ?: "Cancelled"
                this@MainActivity.runOnUiThread {
                    webView.evaluateJavascript("javascript:if(window.onNativeGoogleLoginSuccess) { window.onNativeGoogleLoginSuccess(null, '$safeError'); }", null)
                }
            } catch (e: Exception) {
                Log.e("GoogleLogin", "Error: ${e.message}")
                val safeError = e.message?.replace("'", "\\'") ?: "Unknown Error"
                this@MainActivity.runOnUiThread {
                    webView.evaluateJavascript("javascript:if(window.onNativeGoogleLoginSuccess) { window.onNativeGoogleLoginSuccess(null, '$safeError'); }", null)
                }
            }
        }
    }

    fun setPullToRefreshEnabled(enabled: Boolean) {
        runOnUiThread {
            swipeRefreshLayout.isEnabled = enabled
        }
    }

    fun launchNativeScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom() // 🟢 ADD THIS LINE: Focuses on the center barcode and zooms in
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue ?: ""
                val safeValue = rawValue.replace("'", "\\'")
                val jsCode = "javascript:if(window.onNativeScanSuccess) { window.onNativeScanSuccess('$safeValue'); }"
                webView.evaluateJavascript(jsCode, null)
            }
            .addOnCanceledListener {
                webView.evaluateJavascript("javascript:if(window.onNativeScanCancel) { window.onNativeScanCancel(); }", null)
            }
            .addOnFailureListener { e ->
                Log.e("Scanner", "Scan failed", e)
                webView.evaluateJavascript("javascript:if(window.onNativeScanCancel) { window.onNativeScanCancel(); }", null)
            }
    }

    private fun setupWebViewSettings() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // 🟢 THE FIX (Issue 2: Cross-App Scripting): Disable explicit file access.
            // FileProvider content URIs for camera/sharing will continue to work via allowContentAccess.
            allowFileAccess = false
            allowContentAccess = true

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            downloadHandler.downloadFile(url, fileName, mimeType, userAgent)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // 🟢 THE FIX (Issue 1: Unsafe SSL): Cancel on SSL errors instead of blindly proceeding.
                handler?.cancel()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefreshLayout.isRefreshing = false

                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        Log.d("FCM", "Token fetched, waiting for React to catch it: $token")

                        val jsCode = """
                            javascript:(function() {
                                var attempts = 0;
                                var checkAndSend = setInterval(function() {
                                    if (typeof window.receiveAndroidFcmToken === 'function') {
                                        window.receiveAndroidFcmToken('$token');
                                        clearInterval(checkAndSend);
                                    }
                                    attempts++;
                                    if (attempts > 10) clearInterval(checkAndSend);
                                }, 500);
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(jsCode, null)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    swipeRefreshLayout.isRefreshing = false

                    val failedUrl = request.url.toString()

                    val offlineHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <style>
                                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 90vh; background-color: #f8fafc; color: #0f172a; margin: 0; text-align: center; padding: 20px; }
                                .icon-container { width: 80px; height: 80px; background: #fee2e2; border-radius: 24px; display: flex; align-items: center; justify-content: center; margin-bottom: 24px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05); }
                                .icon { font-size: 36px; }
                                h1 { font-size: 24px; font-weight: 800; margin: 0 0 8px 0; color: #1e293b; }
                                p { font-size: 15px; color: #64748b; margin: 0 0 32px 0; max-width: 280px; line-height: 1.5; }
                                button { background-color: #3b82f6; color: white; border: none; padding: 16px 32px; border-radius: 16px; font-size: 16px; font-weight: bold; cursor: pointer; box-shadow: 0 10px 15px -3px rgba(59, 130, 246, 0.3); transition: transform 0.1s; width: 100%; max-width: 250px; }
                                button:active { transform: scale(0.96); }
                                .hint { font-size: 13px; color: #94a3b8; margin-top: 24px; }
                            </style>
                        </head>
                        <body>
                            <div class="icon-container">
                                <div class="icon">📡</div>
                            </div>
                            <h1>You're Offline</h1>
                            <p>ClearBills couldn't connect to the server. Please check your internet connection.</p>
                            <button onclick="window.location.href = '$failedUrl'">Try Again</button> 
                        </body>
                        </html>
                    """.trimIndent()

                    view?.loadDataWithBaseURL(failedUrl, offlineHtml, "text/html", "UTF-8", null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

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

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

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

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

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

                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }

                val intentArray: Array<Intent> = takePictureIntent?.let { arrayOf(it) } ?: emptyArray()

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

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,
            ".jpg",
            storageDir
        )
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.READ_CONTACTS)
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

    val contactPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri: Uri? = result.data?.data
            if (contactUri != null) {
                var name = ""
                var phone = ""

                val cursor: Cursor? = contentResolver.query(contactUri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                        if (nameIndex > -1) name = it.getString(nameIndex) ?: ""
                        if (phoneIndex > -1) phone = it.getString(phoneIndex) ?: ""
                    }
                }

                name = name.replace("'", "\\'")

                val jsCode = "javascript:if(window.onContactPicked) { window.onContactPicked('$name', '$phone'); }"
                webView.evaluateJavascript(jsCode, null)
            }
        }
    }

    fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        contactPickerLauncher.launch(intent)
    }
}