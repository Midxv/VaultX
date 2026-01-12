package vault.x.org

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    userPin: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val crypto = remember { CryptoManager(context) }
    val indexManager = remember { IndexManager(context, crypto) }

    var url by remember { mutableStateOf("https://google.com") }
    var inputUrl by remember { mutableStateOf(url) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var progress by remember { mutableStateOf(0f) }

    // FULLSCREEN VIDEO STATE
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val adDomains = listOf("doubleclick.net", "googlesyndication.com", "adservice.google.com", "facebook.com/tr", "adnxs.com")

    // Handle Back Press (Video -> Browser -> Exit)
    BackHandler {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
        } else if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onClose()
        }
    }

    // MAIN CONTAINER (Black Background for Notch)
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {

        if (customView == null) {
            // --- HEADER (Hidden when video is fullscreen) ---
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(bottom = 8.dp)
            ) {
                // Top Control Row
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onClose() }) { Icon(Icons.Default.Close, "Exit") }

                    // Rounded Search Box
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    IconButton(onClick = { url = if(inputUrl.startsWith("http")) inputUrl else "https://$inputUrl" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Go", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (progress < 1.0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }

        // --- WEBVIEW / VIDEO CONTAINER ---
        Box(Modifier.fillMaxSize()) {
            if (customView != null) {
                // SHOW FULLSCREEN VIDEO
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            addView(customView)
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // SHOW BROWSER
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.mediaPlaybackRequiresUserGesture = false

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { if (url != null) inputUrl = url }
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val reqUrl = request?.url.toString()
                                    if (adDomains.any { reqUrl.contains(it) }) return WebResourceResponse("text/plain", "utf-8", null)
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress / 100f }

                                // ENTER FULLSCREEN
                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    customView = view
                                    customViewCallback = callback
                                }
                                // EXIT FULLSCREEN
                                override fun onHideCustomView() {
                                    customView = null
                                    customViewCallback = null
                                }
                            }

                            setDownloadListener { downloadUrl, _, contentDisposition, mimetype, _ ->
                                Toast.makeText(ctx, "Downloading...", Toast.LENGTH_SHORT).show()
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val stream: InputStream = URL(downloadUrl).openStream()
                                        val filename = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                                        val newItem = VaultItem(name = filename, type = ItemType.UNKNOWN, parentId = null)
                                        val type = when {
                                            mimetype.startsWith("image/") -> ItemType.IMAGE
                                            mimetype.startsWith("video/") -> ItemType.VIDEO
                                            else -> ItemType.UNKNOWN
                                        }
                                        val finalItem = newItem.copy(type = type)
                                        crypto.encryptData(userPin, stream, finalItem.id) { }
                                        withContext(Dispatchers.Main) {
                                            indexManager.loadIndex(userPin); indexManager.addFile(finalItem); indexManager.saveIndex(userPin)
                                            Toast.makeText(ctx, "Saved to Vault", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    update = { if (it.url != url) it.loadUrl(url) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}