package vault.x.org

import android.graphics.Bitmap
import android.util.LruCache

object ThumbnailCache {
    // Reserve 1/8th of app memory for thumbnails
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // The cache size will be measured in kilobytes rather than number of items
            return bitmap.byteCount / 1024
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        if (get(key) == null) {
            memoryCache.put(key, bitmap)
        }
    }

    fun get(key: String): Bitmap? {
        return memoryCache.get(key)
    }

    fun clear() {
        memoryCache.evictAll()
    }
}