package vault.x.org

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PinScreen(onPinSuccess: (String) -> Unit) {
    val context = LocalContext.current
    val vaultManager = remember { VaultManager(context) }

    // STATES
    var pin by remember { mutableStateOf("") }
    var isNewUser by remember { mutableStateOf(vaultManager.isNewUser()) }
    var step by remember { mutableStateOf(if (isNewUser) "CREATE" else "LOGIN") }

    // "tempPin" is used when creating a PIN (to confirm the second time)
    var tempPin by remember { mutableStateOf("") }

    var statusText by remember {
        mutableStateOf(if (isNewUser) "Create New PIN" else "Enter PIN")
    }
    var isError by remember { mutableStateOf(false) }

    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxSize().background(bgColor).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text("VaultX", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = primaryColor)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = statusText,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        // DOTS ANIMATION
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { index ->
                val isFilled = index < pin.length
                val color by animateColorAsState(if (isFilled) primaryColor else MaterialTheme.colorScheme.surfaceVariant)
                val size by animateDpAsState(if (isFilled) 20.dp else 16.dp)
                Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // NUMBER PAD
        val rows = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("","0","DEL"))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (key in row) {
                        PinButton(symbol = key) {
                            if (key == "DEL") {
                                if (pin.isNotEmpty()) pin = pin.dropLast(1)
                            } else if (key.isNotEmpty() && pin.length < 6) {
                                pin += key

                                // LOGIC TRIGGERS WHEN 6 DIGITS ENTERED
                                if (pin.length == 6) {
                                    when (step) {
                                        "LOGIN" -> {
                                            if (vaultManager.checkPin(pin)) {
                                                onPinSuccess(pin)
                                            } else {
                                                pin = ""; isError = true; statusText = "Wrong PIN"
                                            }
                                        }
                                        "CREATE" -> {
                                            tempPin = pin; pin = ""; step = "CONFIRM"; statusText = "Confirm PIN"
                                        }
                                        "CONFIRM" -> {
                                            if (pin == tempPin) {
                                                vaultManager.savePin(pin)
                                                onPinSuccess(pin)
                                            } else {
                                                pin = ""; tempPin = ""; step = "CREATE"
                                                isError = true; statusText = "Mismatch. Create PIN again."
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun PinButton(symbol: String, onClick: () -> Unit) {
    if (symbol.isEmpty()) { Box(modifier = Modifier.size(80.dp)); return }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(35)).background(MaterialTheme.colorScheme.secondaryContainer).clickable { onClick() }
    ) {
        if (symbol == "DEL") {
            // FIXED: Using standard ArrowBack icon instead of Backspace
            Icon(Icons.Default.ArrowBack, "Del", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            Text(symbol, fontSize = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}