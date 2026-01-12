package vault.x.org

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ThumbnailManager(private val context: Context, private val crypto: CryptoManager) {

    private val db = ThumbnailDatabase(context)
    private val thumbDir = File(context.filesDir, "app_thumbnails").apply { mkdirs() }

    // Semaphore: Limit parallel processing to prevent memory crashes
    private val processingLimiter = Semaphore(3)

    suspend fun syncThumbnails(
        userPin: String,
        allItems: List<VaultItem>,
        onProgress: (Int, Int) -> Unit,
        onUpdate: (Map<String, String>) -> Unit
    ) {
        withContext(Dispatchers.Default) {
            val currentMap = db.getAllThumbnails().toMutableMap()

            // Clean up DB entries for deleted files
            val validIds = allItems.map { it.id }.toSet()
            currentMap.keys.filter { !validIds.contains(it) }.forEach {
                // In a real app, delete the physical file here too
            }
            onUpdate(currentMap.toMap())

            // Filter items that NEED thumbnails (Images, Video, Audio, PDF)
            val missingItems = allItems.filter { item ->
                (item.type != ItemType.FOLDER && item.type != ItemType.UNKNOWN) &&
                        (!currentMap.containsKey(item.id) || !File(currentMap[item.id]!!).exists())
            }

            if (missingItems.isEmpty()) {
                onProgress(0, 0)
                return@withContext
            }

            var processed = 0
            val total = missingItems.size

            missingItems.chunked(5).forEach { batch ->
                batch.forEach { item ->
                    processingLimiter.withPermit {
                        val path = generateAndSave(userPin, item)
                        if (path != null) {
                            db.addThumbnail(item.id, path)
                            synchronized(currentMap) { currentMap[item.id] = path }
                        }
                    }
                    processed++
                    onProgress(processed, total)
                }
                onUpdate(currentMap.toMap())
            }
            onProgress(total, total)
        }
    }

    private fun generateAndSave(userPin: String, item: VaultItem): String? {
        // Decrypt file to temp cache
        val tempFile = crypto.decryptToCache(context, userPin, item.id, "dat") ?: return null
        var bitmap: Bitmap? = null

        try {
            when (item.type) {
                ItemType.VIDEO -> {
                    bitmap = ThumbnailUtils.createVideoThumbnail(tempFile.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                }
                ItemType.IMAGE -> {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                    opts.inSampleSize = calculateInSampleSize(opts, 256, 256)
                    opts.inJustDecodeBounds = false
                    bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                }
                ItemType.AUDIO -> {
                    // Extract Album Art
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(tempFile.absolutePath)
                    val rawArt = mmr.embeddedPicture
                    if (rawArt != null) {
                        bitmap = android.graphics.BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size)
                    }
                    mmr.release()
                }
                ItemType.PDF -> {
                    // Render First Page
                    val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(fd)
                    if (renderer.pageCount > 0) {
                        val page = renderer.openPage(0)
                        bitmap = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                    }
                    renderer.close()
                    fd.close()
                }
                else -> {}
            }
        } catch (e: Exception) { e.printStackTrace() }

        tempFile.delete() // Cleanup

        // Save Result
        if (bitmap != null) {
            return try {
                val dest = File(thumbDir, "${item.id}.jpg")
                FileOutputStream(dest).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
                dest.absolutePath
            } catch (e: Exception) { null }
        }
        return null
    }

    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}