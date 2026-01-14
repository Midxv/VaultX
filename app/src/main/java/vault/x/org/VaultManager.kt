package vault.x.org

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class VaultManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "vault_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePin(pin: String) {
        prefs.edit().putString("USER_PIN", pin).apply()
    }

    fun getPin(): String? {
        return prefs.getString("USER_PIN", null)
    }

    fun checkPin(input: String): Boolean {
        return input == getPin()
    }

    fun isNewUser(): Boolean {
        return getPin() == null
    }

    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean("BIO_ENABLED", false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("BIO_ENABLED", enabled).apply()
    }

    // FIXED: Default is TRUE (Block screenshots by default)
    fun isScreenshotBlockEnabled(): Boolean {
        return prefs.getBoolean("BLOCK_SCREENSHOTS", true)
    }

    fun setScreenshotBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("BLOCK_SCREENSHOTS", enabled).apply()
    }
}