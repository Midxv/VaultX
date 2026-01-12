package vault.x.org

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class VaultApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .build()
    }
}