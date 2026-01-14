package vault.x.org

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(
    initialIndex: Int,
    items: List<VaultItem>,
    userPin: String,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    val context = LocalContext.current
    val crypto = remember { CryptoManager(context) }

    // State to lock paging when zoomed
    var isZoomed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            File(context.cacheDir.path).listFiles()?.filter { it.name.startsWith("VIEW_") }?.forEach { it.delete() }
        }
    }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { items[it].id },
                userScrollEnabled = !isZoomed
            ) { page ->
                val item = items[page]
                var decryptedFile by remember { mutableStateOf<File?>(null) }

                LaunchedEffect(item.id) {
                    withContext(Dispatchers.IO) {
                        val ext = item.name.substringAfterLast('.', "dat")
                        val cacheFile = File(context.cacheDir, "VIEW_${item.id}.$ext")
                        if (!cacheFile.exists()) {
                            crypto.decryptToStream(userPin, File(item.path), FileOutputStream(cacheFile))
                        }
                        withContext(Dispatchers.Main) { decryptedFile = cacheFile }
                    }
                }

                if (decryptedFile != null) {
                    if (item.type == ItemType.VIDEO) {
                        AdvancedVideoPlayer(Uri.fromFile(decryptedFile!!))
                    } else {
                        // Pass callback to update zoom state
                        ZoomableImage(Uri.fromFile(decryptedFile!!)) { zoomed -> isZoomed = zoomed }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).statusBarsPadding()
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun ZoomableImage(uri: Uri, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val oldScale = scale
                    scale = (scale * zoom).coerceIn(1f, 4f)

                    if (scale > 1f) {
                        val maxTranslateX = (size.width * (scale - 1)) / 2
                        val maxTranslateY = (size.height * (scale - 1)) / 2
                        offset = androidx.compose.ui.geometry.Offset(
                            x = (offset.x + pan.x).coerceIn(-maxTranslateX, maxTranslateX),
                            y = (offset.y + pan.y).coerceIn(-maxTranslateY, maxTranslateY)
                        )
                    } else {
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    }

                    if (oldScale == 1f && scale > 1f) onZoomChange(true)
                    else if (oldScale > 1f && scale == 1f) onZoomChange(false)
                }
            }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AdvancedVideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}