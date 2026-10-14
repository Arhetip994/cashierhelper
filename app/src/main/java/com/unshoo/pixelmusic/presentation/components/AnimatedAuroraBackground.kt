package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Apple Music style Animated Aurora Mesh Background.
 * Creates a dreamy, slowly drifting organic ambient glow using dominant song colors.
 */
@Composable
fun AnimatedAuroraBackground(
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    accentColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    baseDarkColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier,
    dimAmount: Float = 0.55f
) {
    val animPrimary by animateColorAsState(targetValue = primaryColor, animationSpec = tween(1200), label = "auroraColor1")
    val animSecondary by animateColorAsState(targetValue = secondaryColor, animationSpec = tween(1200), label = "auroraColor2")
    val animAccent by animateColorAsState(targetValue = accentColor, animationSpec = tween(1200), label = "auroraColor3")

    val infiniteTransition = rememberInfiniteTransition(label = "auroraTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831853f, // 2 * PI
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auroraPhase"
    )

    Box(modifier = modifier.fillMaxSize().background(baseDarkColor)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val maxDim = max(w, h)

            // Orb 1 - Primary color (top-left float)
            val center1 = Offset(
                x = w * (0.35f + 0.20f * sin(phase)),
                y = h * (0.25f + 0.15f * cos(phase))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animPrimary.copy(alpha = 0.70f), Color.Transparent),
                    center = center1,
                    radius = maxDim * 0.70f
                ),
                center = center1,
                radius = maxDim * 0.70f
            )

            // Orb 2 - Secondary color (bottom-right float)
            val center2 = Offset(
                x = w * (0.68f - 0.22f * cos(phase * 0.8f)),
                y = h * (0.65f + 0.18f * sin(phase * 0.8f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animSecondary.copy(alpha = 0.65f), Color.Transparent),
                    center = center2,
                    radius = maxDim * 0.75f
                ),
                center = center2,
                radius = maxDim * 0.75f
            )

            // Orb 3 - Accent color (center floating accent)
            val center3 = Offset(
                x = w * (0.50f + 0.25f * sin(phase * 1.2f + 1f)),
                y = h * (0.45f - 0.20f * cos(phase * 1.2f + 1f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animAccent.copy(alpha = 0.55f), Color.Transparent),
                    center = center3,
                    radius = maxDim * 0.60f
                ),
                center = center3,
                radius = maxDim * 0.60f
            )
        }

        // Soft dark vignette overlay to ensure text contrast matches Apple Music
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = dimAmount * 0.75f),
                            Color.Black.copy(alpha = dimAmount),
                            Color.Black.copy(alpha = dimAmount * 1.25f).copy(alpha = (dimAmount * 1.25f).coerceAtMost(0.92f))
                        )
                    )
                )
        )
    }
}
