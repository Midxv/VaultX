package vault.x.org

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// --- 1. COLORS ---
val PitchBlack = Color(0xFF000000)
val DarkGray = Color(0xFF121212)
val VioletAccent = Color(0xFF6200EE)
val WarningRed = Color(0xFFCF6679)
val TextWhite = Color(0xFFEEEEEE)

// --- 2. DATA MODELS ---
enum class ItemType { IMAGE, VIDEO, AUDIO, PDF, FOLDER, UNKNOWN }

data class VaultItem(
    val id: String,
    val name: String,
    val type: ItemType,
    val dateModified: Long,
    val size: Long,
    val path: String,
    var parentId: String? = null,
    var isDeleted: Boolean = false,
    var deletedTimestamp: Long = 0L,
    var tags: List<String> = emptyList(),
    var isAiProcessed: Boolean = false
)

data class MediaMeta(val id: String, val lat: Double?, val lon: Double?)

// --- 3. METADATA MANAGER ---
class MetadataManager(context: Context, private val crypto: CryptoManager) {
    private val dbHelper = object : SQLiteOpenHelper(context, "meta.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE meta (id TEXT PRIMARY KEY, lat REAL, lon REAL)") }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS meta"); onCreate(db) }
    }

    fun saveMeta(id: String, lat: Double, lon: Double) {
        val sql = "INSERT OR REPLACE INTO meta (id, lat, lon) VALUES (?, ?, ?)"
        dbHelper.writableDatabase.execSQL(sql, arrayOf(id, lat, lon))
    }

    fun getMapMarkers(): List<MediaMeta> {
        val list = mutableListOf<MediaMeta>()
        dbHelper.readableDatabase.rawQuery("SELECT * FROM meta", null).use { c ->
            while (c.moveToNext()) list.add(MediaMeta(c.getString(0), c.getDouble(1), c.getDouble(2)))
        }
        return list
    }

    suspend fun scanMetadata(pin: String, item: VaultItem) {
        if (item.type == ItemType.IMAGE) {
            val temp = File.createTempFile("meta", ".tmp")
            try {
                if (crypto.decryptToStream(pin, File(item.path), FileOutputStream(temp))) {
                    val exif = ExifInterface(temp.absolutePath)
                    val latLong = exif.latLong
                    if (latLong != null) saveMeta(item.id, latLong[0], latLong[1])
                }
            } catch(_: Exception){} finally { temp.delete() }
        }
    }
}

// --- 4. FILE MANAGER ---
class FileManager(private val context: Context, private val crypto: CryptoManager) {
    private val vaultDir = crypto.getVaultDir()
    private val trashDir = File(vaultDir, ".trash").apply { mkdirs() }

    suspend fun loadFiles(parentId: String? = null): List<VaultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<VaultItem>()

        // Active Files
        val targetDir = if(parentId == null) vaultDir else File(vaultDir, parentId)
        if (targetDir.exists()) {
            targetDir.listFiles()?.forEach { file ->
                if (file.name != ".trash" && file.name != "thumbs") parseFile(file, parentId, false)?.let { items.add(it) }
            }
        }

        // Trash Files (Only at root)
        if (parentId == null) {
            trashDir.listFiles()?.forEach { file -> parseFile(file, null, true)?.let { items.add(it) } }
        }
        items.sortedByDescending { it.dateModified }
    }

    private fun parseFile(file: File, parentId: String?, isDeleted: Boolean): VaultItem? {
        return if (file.isDirectory) {
            VaultItem(file.name, file.name, ItemType.FOLDER, file.lastModified(), 0, file.absolutePath, parentId, isDeleted)
        } else if (file.name.endsWith(".enc")) {
            val name = crypto.readMetadata(file)
            val type = if(name.endsWith("mp4", true)) ItemType.VIDEO else ItemType.IMAGE
            VaultItem(file.nameWithoutExtension, name, type, file.lastModified(), file.length(), file.absolutePath, parentId, isDeleted)
        } else null
    }

    suspend fun moveToTrash(items: List<VaultItem>) = withContext(Dispatchers.IO) {
        items.forEach { item ->
            val source = File(item.path)
            val dest = File(trashDir, source.name)
            source.renameTo(dest)
        }
    }

    suspend fun restoreFromTrash(items: List<VaultItem>) = withContext(Dispatchers.IO) {
        items.forEach { item ->
            val source = File(item.path)
            val dest = File(vaultDir, source.name)
            source.renameTo(dest)
        }
    }

    suspend fun deletePermanently(items: List<VaultItem>) = withContext(Dispatchers.IO) {
        items.forEach {
            val f = File(it.path)
            if(f.isDirectory) f.deleteRecursively() else f.delete()
            File(context.filesDir, "thumbs/${it.id}.jpg").delete()
        }
    }

    // Alias for backward compatibility
    suspend fun deleteItems(items: List<VaultItem>) { deletePermanently(items) }

    fun createFolder(name: String, parentId: String?) {
        val parent = if(parentId == null) vaultDir else File(vaultDir, parentId)
        File(parent, name).mkdirs()
    }

    // FIXED: Now accepts 'pin' to match HomeScreen call
    fun moveItems(items: List<VaultItem>, targetFolderId: String?, pin: String) {
        val targetDir = if(targetFolderId == null) vaultDir else File(vaultDir, targetFolderId)
        if(!targetDir.exists()) targetDir.mkdirs()
        items.forEach { item ->
            val source = File(item.path)
            val dest = File(targetDir, source.name)
            source.renameTo(dest)
        }
    }
}

// --- 5. THUMBNAIL MANAGER ---
class ThumbnailManager(private val context: Context, private val crypto: CryptoManager) {
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    suspend fun ensureThumbnails(pin: String, items: List<VaultItem>, onProgress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        val mediaItems = items.filter { it.type == ItemType.IMAGE || it.type == ItemType.VIDEO }
        if (mediaItems.isEmpty()) return@withContext

        mediaItems.forEachIndexed { index, item ->
            val thumb = File(thumbDir, "${item.id}.jpg")
            if (!thumb.exists() || thumb.length() == 0L) {
                val temp = File.createTempFile("thumb", ".tmp", context.cacheDir)
                try {
                    if (crypto.decryptToStream(pin, File(item.path), FileOutputStream(temp))) {
                        val bmp = if(item.type == ItemType.VIDEO)
                            ThumbnailUtils.createVideoThumbnail(temp.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                        else {
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            android.graphics.BitmapFactory.decodeFile(temp.absolutePath, opts)
                        }
                        if (bmp != null) FileOutputStream(thumb).use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                    }
                } catch(_: Exception){} finally { temp.delete() }
            }
            if (index % 5 == 0) onProgress((index + 1) / mediaItems.size.toFloat())
        }
        onProgress(1.0f)
    }

    fun getThumbnailMap(): Map<String, String> {
        return thumbDir.listFiles()?.associate { it.nameWithoutExtension to it.absolutePath } ?: emptyMap()
    }
}