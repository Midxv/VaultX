package vault.x.org

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ThumbnailWatcher(private val context: Context, private val crypto: CryptoManager) {

    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    suspend fun ensureThumbnails(userPin: String, items: List<VaultItem>, onUpdate: () -> Unit) {
        withContext(Dispatchers.Default) {
            var changed = false
            items.filter { it.type == ItemType.IMAGE || it.type == ItemType.VIDEO }.forEach { item ->
                val thumbFile = File(thumbDir, "${item.id}.jpg")

                if (!thumbFile.exists()) {
                    val cacheFile = File(context.cacheDir, "temp_thumb.dat")
                    // This line now works because CryptoManager accepts File
                    if (crypto.decryptToStream(userPin, File(item.path), FileOutputStream(cacheFile))) {
                        var bmp: Bitmap? = null
                        try {
                            if (item.type == ItemType.VIDEO) {
                                bmp = ThumbnailUtils.createVideoThumbnail(cacheFile.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                            } else {
                                val opts = android.graphics.BitmapFactory.Options()
                                opts.inSampleSize = 4
                                bmp = android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
                            }

                            if (bmp != null) {
                                FileOutputStream(thumbFile).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                }
                                changed = true
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                        finally { cacheFile.delete() }
                    }
                }
            }
            if (changed) withContext(Dispatchers.Main) { onUpdate() }
        }
    }

    fun getThumbPath(id: String): String? {
        val f = File(thumbDir, "${id}.jpg")
        return if (f.exists()) f.absolutePath else null
    }
}