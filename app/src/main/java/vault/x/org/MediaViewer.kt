package vault.x.org

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(
    initialIndex: Int,
    items: List<VaultItem>, // Pass the whole list
    userPin: String,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    val context = LocalContext.current
    val crypto = remember { CryptoManager(context) }

    // We only decrypt the CURRENT item to save RAM
    var currentDecryptedFile by remember { mutableStateOf<File?>(null) }

    // Watch page changes to decrypt new file
    LaunchedEffect(pagerState.currentPage) {
        currentDecryptedFile = null // Reset while loading
        val item = items[pagerState.currentPage]

        if (item.type == ItemType.IMAGE || item.type == ItemType.VIDEO) {
            withContext(Dispatchers.IO) {
                val ext = item.name.substringAfterLast('.', "dat")
                val cacheFile = File(context.cacheDir, "VIEW_${item.id}.$ext")

                // Only decrypt if not already cached/playing
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    crypto.decryptToStream(userPin, item.id, FileOutputStream(cacheFile))
                }
                withContext(Dispatchers.Main) { currentDecryptedFile = cacheFile }
            }
        }
    }

    Dialog(onDismissRequest = {
        // Cleanup cache on exit
        File(context.cacheDir, "VIEW_*.dat").delete()
        onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]

                // Only show content if this is the active page and we have a file
                if (page == pagerState.currentPage && currentDecryptedFile != null) {
                    if (item.type == ItemType.VIDEO) {
                        VideoPlayer(Uri.fromFile(currentDecryptedFile!!))
                    } else {
                        ZoomableImage(Uri.fromFile(currentDecryptedFile!!))
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

// Reuse existing helpers (ZoomableImage, VideoPlayer)
// If they are missing from previous steps, paste them here:
@Composable
fun ZoomableImage(uri: Uri) {
    AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}

@Composable
fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    AndroidView(factory = { PlayerView(context).apply { player = exoPlayer } }, modifier = Modifier.fillMaxSize())
}