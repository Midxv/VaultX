package vault.x.org

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun LoadingScreen(onLoadingFinished: () -> Unit) {
    // 1. ANIMATION STATE
    val transition = rememberInfiniteTransition(label = "Loading")

    // Rotating angle for the arc
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "Rotation"
    )

    // 2. SIMULATE LOADING
    LaunchedEffect(Unit) {
        delay(2000) // 2-second delay to simulate vault decryption
        onLoadingFinished()
    }

    // 3. UI LAYOUT
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated Loading Spinner
            val primaryColor = MaterialTheme.colorScheme.primary

            Canvas(modifier = Modifier.size(64.dp)) {
                // Background track (optional, light circle)
                drawCircle(
                    color = primaryColor.copy(alpha = 0.1f),
                    style = Stroke(width = 6.dp.toPx())
                )

                // Animated Arc
                drawArc(
                    color = primaryColor,
                    startAngle = angle,
                    sweepAngle = 90f, // Length of the moving arc
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Decrypting Vault...",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Please wait a moment",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
        }
    }
}
