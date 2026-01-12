package vault.x.org

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.*
import java.security.MessageDigest
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager(private val context: Context) {

    fun getVaultDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val vaultDir = File(dcim, "VaultX")
        if (!vaultDir.exists()) vaultDir.mkdirs()
        return vaultDir
    }

    fun getStorageDir(): File {
        val storage = File(getVaultDir(), "Storage")
        if (!storage.exists()) storage.mkdirs()
        return storage
    }

    private fun getInternalThumbDir(): File {
        val dir = File(context.filesDir, "fast_thumbs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveInternalThumbnail(bitmap: Bitmap, uuid: String) {
        try {
            val file = File(getInternalThumbDir(), "$uuid.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getInternalThumbnail(uuid: String): Bitmap? {
        val file = File(getInternalThumbDir(), "$uuid.jpg")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun getKeyFromPin(pin: String): SecretKeySpec {
        val salt = "VaultX_Fixed_Salt".toByteArray()
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, 65536, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encryptData(pin: String, input: InputStream, uuid: String, onProgress: (Float) -> Unit): File {
        val targetFile = File(getStorageDir(), "$uuid.enc")
        val key = getKeyFromPin(pin)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        FileOutputStream(targetFile).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                val totalSize = input.available()
                var totalRead = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    cos.write(buffer, 0, read)
                    totalRead += read
                    if (totalSize > 0) onProgress(totalRead.toFloat() / totalSize.toFloat())
                }
            }
        }
        return targetFile
    }

    fun decryptToStream(pin: String, uuid: String, outputStream: OutputStream): Boolean {
        val source = File(getStorageDir(), "$uuid.enc")
        if (!source.exists()) return false
        return try {
            val fis = FileInputStream(source)
            val iv = ByteArray(16)
            fis.read(iv)
            val key = getKeyFromPin(pin)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            CipherInputStream(fis, cipher).use { cis ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (cis.read(buffer).also { read = it } != -1) outputStream.write(buffer, 0, read)
            }
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    fun decryptToCache(context: Context, pin: String, uuid: String, extension: String): File? {
        val cacheFile = File(context.cacheDir, "PLAY_$uuid.$extension")
        return if (decryptToStream(pin, uuid, FileOutputStream(cacheFile))) cacheFile else null
    }

    // --- MD5 HASHING (For Duplicate Finder) ---
    // Hashes the encrypted file. If 2 files have same encrypted content, they are dupes.
    fun calculateMD5(uuid: String): String {
        val file = File(getStorageDir(), "$uuid.enc")
        if (!file.exists()) return ""
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // --- THUMBNAILS & INDEX ---
    fun saveEncryptedThumbnail(pin: String, bitmap: Bitmap, uuid: String) {
        val thumbFile = File(getStorageDir(), "$uuid.thumb")
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val bytes = stream.toByteArray()
        val key = getKeyFromPin(pin)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        FileOutputStream(thumbFile).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos -> cos.write(bytes) }
        }
    }

    fun getEncryptedThumbnail(pin: String, uuid: String): Bitmap? {
        val thumbFile = File(getStorageDir(), "$uuid.thumb")
        if (!thumbFile.exists()) return null
        return try {
            val fis = FileInputStream(thumbFile)
            val iv = ByteArray(16)
            fis.read(iv)
            val key = getKeyFromPin(pin)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            BitmapFactory.decodeStream(CipherInputStream(fis, cipher))
        } catch (e: Exception) { null }
    }

    fun writeEncryptedTextFile(pin: String, file: File, text: String) {
        val key = getKeyFromPin(pin)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        FileOutputStream(file).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos -> cos.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    fun readEncryptedTextFile(pin: String, file: File): String? {
        if (!file.exists()) return null
        return try {
            val fis = FileInputStream(file)
            val iv = ByteArray(16)
            fis.read(iv)
            val key = getKeyFromPin(pin)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val cis = CipherInputStream(fis, cipher)
            String(cis.readBytes(), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}