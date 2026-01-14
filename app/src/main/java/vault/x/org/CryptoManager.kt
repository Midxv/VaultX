package vault.x.org

import android.content.Context
import android.os.Environment
import java.io.*
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager(private val context: Context) {

    // FIXED: Storage is now DCIM/Vaultx
    // 'subPath' allows creating Albums (Sub-folders)
    fun getVaultDir(subPath: String? = null): File {
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Vaultx")
        val target = if (subPath != null) File(root, subPath) else root
        if (!target.exists()) target.mkdirs()
        return target
    }

    private fun getKey(pin: String): SecretKeySpec {
        val salt = "VaultX_Offline_Salt".toByteArray()
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, 65536, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // Encrypts to a specific folder (parentId = folder name)
    fun encrypt(pin: String, input: InputStream, originalName: String, parentId: String?): Boolean {
        return try {
            val id = java.util.UUID.randomUUID().toString()
            // Save to specific folder
            val outFile = File(getVaultDir(parentId), "$id.enc")

            val key = getKey(pin)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv

            val nameBytes = originalName.toByteArray(Charsets.UTF_8)
            val nameLen = ByteBuffer.allocate(4).putInt(nameBytes.size).array()

            FileOutputStream(outFile).use { fos ->
                fos.write(iv)
                fos.write(nameLen)
                fos.write(nameBytes)
                CipherOutputStream(fos, cipher).use { cos ->
                    input.copyTo(cos)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun readMetadata(file: File): String {
        return try {
            FileInputStream(file).use { fis ->
                val iv = ByteArray(16)
                if (fis.read(iv) != 16) return "Unknown"
                val lenBytes = ByteArray(4)
                if (fis.read(lenBytes) != 4) return "Unknown"
                val nameLen = ByteBuffer.wrap(lenBytes).int
                if (nameLen > 255 || nameLen < 0) return "Corrupt"
                val nameBytes = ByteArray(nameLen)
                if (fis.read(nameBytes) != nameLen) return "Unknown"
                String(nameBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) { "Locked File" }
    }

    fun decryptToStream(pin: String, file: File, output: OutputStream): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val iv = ByteArray(16)
                fis.read(iv)
                val lenBytes = ByteArray(4)
                fis.read(lenBytes)
                val nameLen = ByteBuffer.wrap(lenBytes).int
                fis.skip(nameLen.toLong())

                val key = getKey(pin)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

                CipherInputStream(fis, cipher).use { cis ->
                    cis.copyTo(output)
                }
            }
            true
        } catch (e: Exception) { false }
    }
}