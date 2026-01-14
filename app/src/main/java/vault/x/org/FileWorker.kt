package vault.x.org

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val action = inputData.getString("ACTION") ?: return Result.failure()
        val userPin = inputData.getString("PIN") ?: return Result.failure()
        val targetFolder = inputData.getString("TARGET")
        val parentId = inputData.getString("PARENT_ID")
        val uriListPath = inputData.getString("URI_LIST_FILE")
        val fileIds = inputData.getStringArray("IDS")?.toList() ?: emptyList()

        setForeground(createForegroundInfo(0, 0, action, true))

        val crypto = CryptoManager(applicationContext)
        val fileManager = FileManager(applicationContext, crypto)
        val metaManager = MetadataManager(applicationContext, crypto)

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "IMPORT" -> {
                        val uriFile = File(uriListPath ?: "")
                        if (!uriFile.exists()) return@withContext Result.failure()

                        val lines = uriFile.readLines()
                        val total = lines.size

                        // 1. Load Existing Files to check duplicates
                        val existingFiles = fileManager.loadFiles(parentId)
                        val nameMap = existingFiles.associate { it.name to it.size } // Name -> Size

                        lines.forEachIndexed { index, uriString ->
                            try {
                                val uri = Uri.parse(uriString)
                                val meta = getFileMetadata(applicationContext, uri)
                                var finalName = meta.first
                                val finalSize = meta.second

                                // DUPLICATE CHECK LOGIC
                                if (nameMap.containsKey(finalName)) {
                                    val existingSize = nameMap[finalName]
                                    if (existingSize == finalSize) {
                                        // EXACT MATCH: Skip Import
                                        return@forEachIndexed
                                    } else {
                                        // NAME CONFLICT, DIFF SIZE: Rename
                                        val nameParts = finalName.lastIndexOf('.')
                                        if (nameParts > 0) {
                                            val name = finalName.substring(0, nameParts)
                                            val ext = finalName.substring(nameParts)
                                            var counter = 1
                                            while (nameMap.containsKey("$name($counter)$ext")) { counter++ }
                                            finalName = "$name($counter)$ext"
                                        } else {
                                            finalName = "${finalName}_1"
                                        }
                                    }
                                }

                                val newItem = VaultItem(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = finalName,
                                    type = detectMimeType(applicationContext, uri),
                                    dateModified = System.currentTimeMillis(),
                                    size = finalSize,
                                    path = "",
                                    parentId = parentId
                                )

                                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                                    crypto.encrypt(userPin, input, newItem.name, parentId)
                                }

                                metaManager.scanMetadata(userPin, newItem)

                                if (index % 5 == 0) {
                                    setForegroundAsync(createForegroundInfo(index + 1, total, "Importing", true))
                                    setProgressAsync(workDataOf("Progress" to index + 1, "Total" to total))
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        uriFile.delete()
                    }
                    "EXPORT" -> {
                        val total = fileIds.size
                        if (targetFolder != null) {
                            // Load root + subfolder files to find the right item
                            val allFiles = fileManager.loadFiles(null) + fileManager.loadFiles(parentId)
                            fileIds.forEachIndexed { index, id ->
                                val item = allFiles.find { it.id == id }
                                if (item != null) {
                                    val dest = File(targetFolder, item.name)
                                    crypto.decryptToStream(userPin, File(item.path), FileOutputStream(dest))
                                }
                                setForegroundAsync(createForegroundInfo(index + 1, total, "Exporting", true))
                            }
                        }
                    }
                    "DELETE" -> {
                        val allFiles = fileManager.loadFiles(parentId)
                        val itemsToDelete = allFiles.filter { fileIds.contains(it.id) }
                        fileManager.deletePermanently(itemsToDelete)
                    }
                }

                sendCompletionNotification(action)
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(progress: Int, total: Int, title: String, ongoing: Boolean): ForegroundInfo {
        val channelId = "file_ops"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "File Operations", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("VaultX $title")
            .setContentText("$progress / ${if(total > 0) total else "..."}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, progress, total == 0)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2, notification)
        }
    }

    private fun sendCompletionNotification(action: String) {
        val notification = NotificationCompat.Builder(applicationContext, "file_ops")
            .setContentTitle("Task Complete")
            .setContentText("$action finished successfully.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(3, notification)
    }
}