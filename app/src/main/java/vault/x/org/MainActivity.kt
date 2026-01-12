package vault.x.org

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager // <--- Import this
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

class MainActivity : FragmentActivity() {

    private var backgroundTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. APPLY SCREENSHOT BLOCK (If Enabled)
        val vaultManager = VaultManager(this)
        if (vaultManager.isScreenshotBlockEnabled()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        enableEdgeToEdge()

        setContent {
            VaultxTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var hasPermission by remember { mutableStateOf(checkStoragePermission()) }

                    if (!hasPermission) {
                        PermissionScreen {
                            requestStoragePermission()
                            hasPermission = checkStoragePermission()
                        }
                    } else {
                        // APP FLOW
                        var authState by remember { mutableStateOf("LOCKED") }
                        var userPin by remember { mutableStateOf("") }

                        // AUTO LOCK LOGIC
                        DisposableEffect(Unit) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_STOP) backgroundTime = System.currentTimeMillis()
                                else if (event == Lifecycle.Event.ON_START) {
                                    if (backgroundTime > 0 && (System.currentTimeMillis() - backgroundTime > 180_000)) {
                                        authState = "LOCKED"; userPin = ""; backgroundTime = 0
                                    }
                                }
                            }
                            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                            onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
                        }

                        // BIOMETRIC AUTO-LOGIN
                        LaunchedEffect(Unit) {
                            if (vaultManager.isBiometricEnabled() && !vaultManager.isNewUser()) {
                                showBiometricPrompt(
                                    onSuccess = {
                                        val savedPin = vaultManager.getPin()
                                        if (savedPin != null) { userPin = savedPin; authState = "LOADING" }
                                    }
                                )
                            }
                        }

                        when (authState) {
                            "LOCKED" -> PinScreen(onPinSuccess = { pin -> userPin = pin; authState = "LOADING" })
                            "LOADING" -> LoadingScreen(onLoadingFinished = { authState = "UNLOCKED" })
                            "UNLOCKED" -> HomeScreen(userPin = userPin)
                        }
                    }
                }
            }
        }
    }

    // ... (Rest of the file remains the same: showBiometricPrompt, checkStoragePermission, etc.)
    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(result); onSuccess() }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock VaultX").setSubtitle("Use your fingerprint or face").setNegativeButtonText("Use PIN").build()
        prompt.authenticate(info)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, 100)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, 100)
            }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VaultX needs storage access.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("Grant Permission") }
        }
    }
}

@Composable
fun VaultxTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colorScheme, content = content)
}