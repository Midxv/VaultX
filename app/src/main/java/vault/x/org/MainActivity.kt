package vault.x.org

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

class MainActivity : FragmentActivity() {

    private var backgroundTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vaultManager = VaultManager(this)
        if (vaultManager.isScreenshotBlockEnabled()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        enableEdgeToEdge()

        setContent {
            VaultxTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {

                    var hasStorage by remember { mutableStateOf(checkStoragePermission()) }

                    DisposableEffect(Unit) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP) backgroundTime = System.currentTimeMillis()
                            else if (event == Lifecycle.Event.ON_START) {
                                if (backgroundTime > 0 && (System.currentTimeMillis() - backgroundTime > 180_000)) {
                                    val intent = intent; finish(); startActivity(intent)
                                }
                            }
                        }
                        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
                    }

                    if (!hasStorage) {
                        PermissionScreen("VaultX needs access to hide your files securely.") {
                            requestStoragePermission()
                            hasStorage = checkStoragePermission()
                        }
                    } else {
                        var authState by remember { mutableStateOf("LOCKED") }
                        var userPin by remember { mutableStateOf("") }

                        LaunchedEffect(Unit) {
                            if (vaultManager.isBiometricEnabled() && !vaultManager.isNewUser()) {
                                showBiometricPrompt {
                                    val savedPin = vaultManager.getPin()
                                    if (savedPin != null) { userPin = savedPin; authState = "UNLOCKED" }
                                }
                            }
                        }

                        when (authState) {
                            "LOCKED" -> PinScreen(
                                isNewUser = vaultManager.isNewUser(),
                                onPinSuccess = { pin ->
                                    if (vaultManager.isNewUser()) vaultManager.savePin(pin)
                                    // Check pin against stored
                                    if (vaultManager.checkPin(pin)) {
                                        userPin = pin
                                        authState = "UNLOCKED"
                                    } else {
                                        Toast.makeText(this@MainActivity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onBiometricClick = {
                                    if (vaultManager.isBiometricEnabled()) {
                                        showBiometricPrompt {
                                            val savedPin = vaultManager.getPin()
                                            if (savedPin != null) { userPin = savedPin; authState = "UNLOCKED" }
                                        }
                                    }
                                }
                            )
                            "UNLOCKED" -> HomeScreen(userPin = userPin)
                        }
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock VaultX").setNegativeButtonText("Use PIN").build()
        prompt.authenticate(info)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 100)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, 100)
            }
        }
    }
}

@Composable
fun PermissionScreen(text: String, onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Lock, null, tint = VioletAccent, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            Text(text, color = TextWhite, textAlign = TextAlign.Center, fontSize = 16.sp)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = VioletAccent), shape = RoundedCornerShape(50)) {
                Text("Grant Permission", color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun PinScreen(isNewUser: Boolean, onPinSuccess: (String) -> Unit, onBiometricClick: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirming by remember { mutableStateOf(false) } // State for confirmation step

    val title = when {
        isNewUser && !isConfirming -> "Create 6-Digit PIN"
        isNewUser && isConfirming -> "Confirm PIN"
        else -> "Enter PIN"
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))

            // 6-DOT DISPLAY
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { i ->
                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(if (pin.length > i) VioletAccent else DarkGray).border(1.dp, VioletAccent, CircleShape))
                }
            }
            Spacer(Modifier.height(48.dp))

            // NUMBER PAD
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Bio", "0", "<")
            LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                items(keys) { key ->
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).clip(CircleShape).background(DarkGray).clickable {
                        when (key) {
                            "<" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                            "Bio" -> onBiometricClick()
                            else -> {
                                if (pin.length < 6) pin += key
                                if (pin.length == 6) {
                                    if (isNewUser) {
                                        if (!isConfirming) {
                                            confirmPin = pin
                                            pin = ""
                                            isConfirming = true
                                        } else {
                                            if (pin == confirmPin) onPinSuccess(pin)
                                            else {
                                                pin = ""; confirmPin = ""; isConfirming = false
                                                // Ideally show Toast here
                                            }
                                        }
                                    } else {
                                        onPinSuccess(pin)
                                        pin = ""
                                    }
                                }
                            }
                        }
                    }) {
                        if (key == "Bio") Icon(Icons.Rounded.Fingerprint, null, tint = VioletAccent)
                        else Text(key, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun VaultxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = VioletAccent, background = Color.Black, surface = DarkGray), content = content)
}