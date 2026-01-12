package vault.x.org

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await // This works after Step 1

// Constructor accepts Context to match HomeScreen usage
class AiScanner(private val context: Context) {

    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    suspend fun scanImage(bitmap: Bitmap): List<String> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            // await() now works because of the dependency
            val labels = labeler.process(image).await()

            labels.filter { it.confidence > 0.7 }
                .sortedByDescending { it.confidence }
                .take(5)
                .map { it.text }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}