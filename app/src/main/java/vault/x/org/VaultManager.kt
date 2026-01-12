package vault.x.org

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class VaultManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "vault_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isNewUser(): Boolean = !sharedPreferences.contains("USER_PIN")
    fun savePin(pin: String) = sharedPreferences.edit().putString("USER_PIN", pin).apply()
    fun getPin(): String? = sharedPreferences.getString("USER_PIN", null)

    fun checkPin(inputPin: String): Boolean {
        val savedPin = sharedPreferences.getString("USER_PIN", "")
        return savedPin == inputPin
    }

    // BIOMETRICS
    fun isBiometricEnabled(): Boolean = sharedPreferences.getBoolean("BIO_ENABLED", false)
    fun setBiometricEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean("BIO_ENABLED", enabled).apply()

    // AI SETTINGS
    fun isAiEnabled(): Boolean = sharedPreferences.getBoolean("AI_ENABLED", false)
    fun setAiEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean("AI_ENABLED", enabled).apply()

    // SCREENSHOT BLOCK
    fun isScreenshotBlockEnabled(): Boolean = sharedPreferences.getBoolean("BLOCK_SCREENSHOTS", true)
    fun setScreenshotBlockEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean("BLOCK_SCREENSHOTS", enabled).apply()
}