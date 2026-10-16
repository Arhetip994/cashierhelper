package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DotCount = 3

/** Gaps shorter than this are just breathing room between lines, not an interlude. */
const val MinInterludeGapMs = 4_000L

/** Height of the row when fully grown in. */
private val RowHeight = 52.dp

private val DotSize = 12.dp
private val DotGap = 8.dp

private const val RestAlpha = 0.35f
private const val RestScale = 0.82f
private const val LitScale = 1.15f

/** How far past the dot the glow reaches, as a multiple of its radius. */
private const val GlowRadiusFactor = 2.8f
private const val GlowPeakAlpha = 0.65f

/** After a dot finishes lighting, its glow fades over roughly this long. */
private const val GlowFadeMs = 850f

/** Idle bob: one slow breath up and down, each dot a little behind the one before it. */
private const val BobPeriodMs = 2_600f
private val BobHeight = 2.dp
private const val BobPhaseStep = 0.85f

/** Pop-in stagger between dots. */
private const val PopStaggerMs = 65L

private fun Color.asGlow(): Color {
    val target = 0.85f
    return Color(
        red = red + (target - red).coerceAtLeast(0f) * 0.65f,
        green = green + (target - green).coerceAtLeast(0f) * 0.65f,
        blue = blue + (target - blue).coerceAtLeast(0f) * 0.65f,
        alpha = 1f,
    )
}

/** How long a dot takes to travel from unlit to lit, as a fraction of its own slice. */
private const val DotFadeFraction = 0.55f

/** The last beat before the next line: all three dots swell together. */
private const val FinaleFraction = 0.88f

enum class LyricsInterludeAlignment {
    START, CENTER, END
}

/**
 * Three animated countdown dots for instrumental breaks (Apple Music & Musicfy style).
 * Rendered using graphicsLayer and drawBehind in a frame callback to eliminate recompositions.
 */
@Composable
fun LyricsInterludeDots(
    startMs: Long,
    endMs: Long,
    positionProvider: () -> Long,
    accentColor: Color,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    alignment: LyricsInterludeAlignment = LyricsInterludeAlignment.CENTER,
    collapseEasing: Easing = FastOutSlowInEasing
) {
    val span = (endMs - startMs).coerceAtLeast(1L)
    val glowColor = remember(accentColor) { accentColor.asGlow() }

    val fills = remember { Array(DotCount) { mutableFloatStateOf(0f) } }
    val glows = remember { Array(DotCount) { mutableFloatStateOf(0f) } }
    val finale = remember { mutableFloatStateOf(0f) }
    val clockMs = remember { mutableLongStateOf(0L) }

    val height = remember { Animatable(if (visible) 1f else 0f) }
    val pops = remember { Array(DotCount) { Animatable(if (visible) 1f else 0f) } }

    LaunchedEffect(visible) {
        if (visible) {
            launch {
                height.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
                )
            }
            pops.forEachIndexed { index, pop ->
                launch {
                    delay(index * PopStaggerMs)
                    pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
                }
            }
        } else {
            launch {
                height.animateTo(0f, tween(300, easing = collapseEasing))
            }
            pops.forEach { pop ->
                launch { pop.animateTo(0f, tween(150, easing = collapseEasing)) }
            }
        }
    }

    LaunchedEffect(startMs, endMs, positionProvider, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameMs ->
                clockMs.longValue = frameMs
                val progress = ((positionProvider() - startMs).toFloat() / span).coerceIn(0f, 1f)
                val finaleNow = smoothstep((progress - FinaleFraction) / (1f - FinaleFraction))
                finale.floatValue = finaleNow

                for (index in 0 until DotCount) {
                    val sliceStart = index.toFloat() / DotCount
                    val within = (progress - sliceStart) * DotCount / DotFadeFraction
                    val fill = smoothstep(within)
                    fills[index].floatValue = fill

                    val litAt = sliceStart + DotFadeFraction / DotCount
                    val glow = if (progress < litAt) {
                        fill
                    } else {
                        exp(-((progress - litAt) * span) / GlowFadeMs)
                    }
                    glows[index].floatValue = maxOf(glow, sin(PI.toFloat() * finaleNow) * 0.7f)
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val full = RowHeight.roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = full, maxHeight = full))
                val shown = (full * height.value.coerceIn(0f, 1f)).roundToInt()
                layout(placeable.width, shown) {
                    placeable.place(0, (shown - full) / 2)
                }
            }
            .height(RowHeight)
            .padding(horizontal = 24.dp)
            .graphicsLayer { alpha = height.value.coerceIn(0f, 1f) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = when (alignment) {
            LyricsInterludeAlignment.START -> Arrangement.Start
            LyricsInterludeAlignment.CENTER -> Arrangement.Center
            LyricsInterludeAlignment.END -> Arrangement.End
        }
    ) {
        repeat(DotCount) { index ->
            Spacer(
                modifier = Modifier
                    .size(DotSize)
                    .graphicsLayer {
                        val fill = fills[index].floatValue
                        val swell = finale.floatValue
                        val pop = pops[index].value
                        val s = (RestScale + (LitScale - RestScale) * fill + 0.14f * swell) * pop
                        scaleX = s
                        scaleY = s
                        alpha = (RestAlpha + (1f - RestAlpha) * maxOf(fill, swell)) * pop.coerceIn(0f, 1f)

                        val phase = clockMs.longValue / BobPeriodMs * 2f * PI.toFloat() - index * BobPhaseStep
                        translationY = sin(phase) * BobHeight.toPx()
                    }
                    .drawBehind {
                        val fill = fills[index].floatValue
                        val glow = glows[index].floatValue
                        val radius = size.minDimension / 2f

                        if (glow > 0.01f) {
                            val glowRadius = radius * GlowRadiusFactor
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        glowColor.copy(alpha = GlowPeakAlpha * glow),
                                        Color.Transparent,
                                    ),
                                    center = center,
                                    radius = glowRadius,
                                ),
                                radius = glowRadius,
                            )
                        }

                        drawCircle(Color.White.copy(alpha = 0.75f + 0.25f * fill), radius)
                    }
            )
            if (index < DotCount - 1) Spacer(modifier = Modifier.size(DotGap))
        }
    }
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
