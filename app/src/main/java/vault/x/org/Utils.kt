package vault.x.org

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.Locale

// --- SHARED HELPER FUNCTIONS ---

fun getFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
    var name = "Unknown_${System.currentTimeMillis()}"
    var size = 0L
    try {
        val cr = context.contentResolver
        cr.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (ni != -1) {
                    val rawName = cursor.getString(ni)
                    if (!rawName.isNullOrEmpty()) name = rawName
                }
                if (si != -1) size = cursor.getLong(si)
            }
        }

        // FIX: If name has no extension, try to append one from MimeType
        if (!name.contains(".")) {
            val type = cr.getType(uri)
            if (type != null) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
                if (ext != null) name = "$name.$ext"
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return Pair(name, size)
}

// CENTRALIZED TYPE CHECKER
fun getItemTypeFromName(name: String): ItemType {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        // VIDEO FORMATS (Prioritize .mov and .heiv)
        lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") ||
                lower.endsWith(".avi") || lower.endsWith(".webm") || lower.endsWith(".3gp") ||
                lower.endsWith(".heiv") || lower.endsWith(".ts") || lower.endsWith(".m4v") -> ItemType.VIDEO

        // IMAGE FORMATS
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif") ||
                lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".dng") -> ItemType.IMAGE

        // AUDIO
        lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") ||
                lower.endsWith(".aac") || lower.endsWith(".flac") || lower.endsWith(".ogg") -> ItemType.AUDIO

        // DOCS
        lower.endsWith(".pdf") -> ItemType.PDF

        else -> ItemType.UNKNOWN
    }
}

fun detectMimeType(context: Context, uri: Uri): ItemType {
    // 1. Check Name First (Most Reliable for .mov/.heic)
    val meta = getFileMetadata(context, uri)
    var type = getItemTypeFromName(meta.first)

    // 2. If Unknown, check System Mime
    if (type == ItemType.UNKNOWN) {
        try {
            val mime = context.contentResolver.getType(uri)
            if (mime != null) {
                type = when {
                    mime.startsWith("image/") -> ItemType.IMAGE
                    mime.startsWith("video/") -> ItemType.VIDEO
                    mime.startsWith("audio/") -> ItemType.AUDIO
                    mime.contains("pdf") -> ItemType.PDF
                    else -> ItemType.UNKNOWN
                }
            }
        } catch(e: Exception) {}
    }
    return type
}

fun getPathFromUri(context: Context, uri: Uri): String? {
    return null
}