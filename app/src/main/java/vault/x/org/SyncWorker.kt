package vault.x.org

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pin = inputData.getString("PIN") ?: return Result.failure()

        val crypto = CryptoManager(applicationContext)
        val fileManager = FileManager(applicationContext, crypto)
        val thumbManager = ThumbnailManager(applicationContext, crypto)
        val metaManager = MetadataManager(applicationContext, crypto)

        return withContext(Dispatchers.IO) {
            try {
                // 1. Load all files
                val items = fileManager.loadFiles(null)

                // 2. Generate missing thumbnails (FIXED: Added progress callback)
                thumbManager.ensureThumbnails(pin, items) { progress ->
                    // Optional: Report progress to WorkManager so UI can observe it if needed
                    setProgressAsync(workDataOf("Progress" to progress))
                }

                // 3. Scan metadata for images
                items.filter { it.type == ItemType.IMAGE }.forEach { item ->
                    metaManager.scanMetadata(pin, item)
                }

                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure()
            }
        }
    }
}