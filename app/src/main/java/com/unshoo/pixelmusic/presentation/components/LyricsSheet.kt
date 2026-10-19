package com.unshoo.pixelmusic.presentation.components

import android.widget.Toast
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.R
import androidx.activity.compose.BackHandler
import com.unshoo.pixelmusic.presentation.components.scoped.LyricsPredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import com.unshoo.pixelmusic.presentation.components.SmartImage
import com.unshoo.pixelmusic.presentation.components.AutoScrollingText
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.network.lyrics.LyricsTranslator
import okhttp3.OkHttpClient
import com.google.gson.Gson
import androidx.compose.material.icons.outlined.Language
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.consumePositionChange
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.unshoo.pixelmusic.data.model.SyncedLine
import com.unshoo.pixelmusic.data.model.SyncedWord
import com.unshoo.pixelmusic.data.repository.LyricsSearchResult
import com.unshoo.pixelmusic.presentation.screens.TabAnimation
import com.unshoo.pixelmusic.presentation.components.subcomps.FetchLyricsDialog
import com.unshoo.pixelmusic.presentation.components.subcomps.PlayerSeekBar
import com.unshoo.pixelmusic.presentation.viewmodel.LyricsSearchUiState
import com.unshoo.pixelmusic.presentation.viewmodel.StablePlayerState
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import com.unshoo.pixelmusic.utils.BubblesLine
import com.unshoo.pixelmusic.utils.ProviderText
import com.unshoo.pixelmusic.presentation.components.snapping.ExperimentalSnapperApi
import com.unshoo.pixelmusic.presentation.components.snapping.SnapperLayoutInfo
import com.unshoo.pixelmusic.presentation.components.snapping.rememberLazyListSnapperLayoutInfo
import com.unshoo.pixelmusic.presentation.components.snapping.rememberSnapperFlingBehavior
import com.unshoo.pixelmusic.utils.LyricsUtils
import com.unshoo.pixelmusic.presentation.components.subcomps.LyricsMoreBottomSheet
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unshoo.pixelmusic.data.preferences.dataStore

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import com.unshoo.pixelmusic.presentation.components.LyricsInterludeDots
import com.unshoo.pixelmusic.presentation.components.LyricsInterludeAlignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import com.unshoo.pixelmusic.presentation.components.subcomps.PlayingEqIcon
import com.unshoo.pixelmusic.utils.MultiLangRomanizer

internal data class LyricsSheetColors(
    val container: Color,
    val content: Color,
    val controlContainer: Color,
    val controlContent: Color,
    val accent: Color,
    val accentContent: Color,
    val lyricHighlight: Color,
    val playPauseContainer: Color,
    val playPauseContent: Color,
    val syncButtonContainer: Color,
    val syncButtonContent: Color
)

internal fun lyricsSheetColors(colorScheme: ColorScheme): LyricsSheetColors {
    val container = colorScheme.primaryContainer
    val content = colorScheme.onPrimaryContainer
    val accent = colorScheme.primary
    val accentContent = colorScheme.onPrimary

    return LyricsSheetColors(
        container = container,
        content = content,
        controlContainer = colorScheme.surfaceContainerLowest,
        controlContent = colorScheme.onSurface,
        accent = accent,
        accentContent = accentContent,
        lyricHighlight = preferredContrastColor(
            background = container,
            preferred = accent,
            fallback = content
        ),
        playPauseContainer = colorScheme.tertiaryFixedDim,
        playPauseContent = colorScheme.onTertiaryFixed,
        syncButtonContainer = colorScheme.secondaryFixedDim,
        syncButtonContent = colorScheme.onSecondaryFixed
    )
}

private fun preferredContrastColor(
    background: Color,
    preferred: Color,
    fallback: Color,
    minContrastRatio: Double = 4.5
): Color {
    if (contrastRatio(preferred, background) >= minContrastRatio) return preferred
    if (contrastRatio(fallback, background) >= minContrastRatio) return fallback

    val blackContrast = contrastRatio(Color.Black, background)
    val whiteContrast = contrastRatio(Color.White, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val foregroundLuminance = foreground.relativeLuminance()
    val backgroundLuminance = background.relativeLuminance()
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double {
    val argb = encodedSrgbArgb()
    val red = linearizedChannel((argb shr 16) and 0xFF)
    val green = linearizedChannel((argb shr 8) and 0xFF)
    val blue = linearizedChannel(argb and 0xFF)
    return (0.2126 * red) + (0.7152 * green) + (0.0722 * blue)
}

private fun Color.encodedSrgbArgb(): Int = (value shr 32).toInt()

private fun linearizedChannel(channel: Int): Double {
    val value = channel / 255.0
    return if (value <= 0.03928) {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).pow(2.4)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun LyricsSheet(
    stablePlayerStateFlow: StateFlow<StablePlayerState>,
    playbackPositionFlow: StateFlow<Long>,
    lyricsSearchUiState: LyricsSearchUiState,
    resetLyricsForCurrentSong: () -> Unit,
    onSearchLyrics: (Boolean) -> Unit,
    onPickResult: (LyricsSearchResult) -> Unit,
    onManualSearch: (String, String?) -> Unit,
    onImportLyrics: () -> Unit,
    onDismissLyricsSearch: () -> Unit,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    lyricsTextStyle: TextStyle,
    colorScheme: ColorScheme,
    onBackClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    immersiveLyricsEnabled: Boolean,
    immersiveLyricsTimeout: Long,
    isImmersiveTemporarilyDisabled: Boolean,
    onSetImmersiveTemporarilyDisabled: (Boolean) -> Unit,
    onSaveLyricsToFile: (Song, Lyrics, Boolean) -> Unit,
    // BottomToggleRow Params
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    swipeThreshold: Dp = 100.dp,
    highlightZoneFraction: Float = 0.08f, // Reduced from 0.22 for less padding
    highlightOffsetDp: Dp = 32.dp,
    autoscrollAnimationSpec: AnimationSpec<Float>? = null, // null = auto-detect from preference
    lyricsTranslator: LyricsTranslator? = null,
    getActiveAiProviderName: (suspend () -> String?)? = null
) {
    // ─── Enter / Exit animation state ────────────────────────────────────────
    // Mirrors the player-sheet pattern: a plain Float in state drives graphicsLayer
    // at draw-phase (no recomposition per frame). 0f = fully visible, 1f = dismissed.
    var backProgress by remember { mutableFloatStateOf(1f) }

    // Draw-phase lambda provider — read only inside graphicsLayer so layout is never
    // re-triggered during the gesture (same technique as SheetVisualState).
    val backProgressProvider = rememberUpdatedState(backProgress)

    // Enter animation: slide up from +6 % height + fade in.
    LaunchedEffect(Unit) {
        val anim = Animatable(1f)
        anim.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioLowBouncy
            )
        ) { backProgress = value }
    }

    // Predictive-back (Android 13+) or plain back on older devices.
    LyricsPredictiveBackHandler(
        enabled = true,
        onProgressChanged = { backProgress = it },
        onBack = onBackClick
    )

    val stablePlayerState by stablePlayerStateFlow.collectAsStateWithLifecycle()
    val sheetColors = remember(colorScheme) { lyricsSheetColors(colorScheme) }
    val backgroundColor = sheetColors.controlContainer
    val onBackgroundColor = sheetColors.controlContent
    val containerColor = sheetColors.container
    val contentColor = sheetColors.content
    val accentColor = sheetColors.accent
    val onAccentColor = sheetColors.accentContent
    val lyricHighlightColor = sheetColors.lyricHighlight
    val playPauseColor = sheetColors.playPauseContainer
    val onPlayPauseColor = sheetColors.playPauseContent

    val isLoadingLyrics by remember(stablePlayerState) { derivedStateOf { stablePlayerState.isLoadingLyrics } }
    val lyrics by remember(stablePlayerState) { derivedStateOf { stablePlayerState.lyrics } }
    val isPlaying by remember(stablePlayerState) { derivedStateOf { stablePlayerState.isPlaying } }
    val currentSong by remember(stablePlayerState) { derivedStateOf { stablePlayerState.currentSong } }

    val okHttpClient = remember { OkHttpClient.Builder().build() }
    val gson = remember { Gson() }
    val effectiveLyricsTranslator = remember(lyricsTranslator) {
        lyricsTranslator ?: LyricsTranslator(okHttpClient, gson)
    }

    var activeAiProviderName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (getActiveAiProviderName != null) {
            activeAiProviderName = getActiveAiProviderName()
        }
    }

    var showLanguageSheet by remember { mutableStateOf(false) }
    var localTranslatedLyrics by remember(currentSong?.id) { mutableStateOf<Lyrics?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var showRussianTranslation by remember(currentSong?.id) { mutableStateOf(false) }

    val effectiveLyrics = if (showRussianTranslation && localTranslatedLyrics != null) {
        localTranslatedLyrics
    } else {
        lyrics
    }

    val context = LocalContext.current

    val lyricsTranslationTargetLangFlow = remember(context) {
        context.dataStore.data.map { it[stringPreferencesKey("lyrics_translation_target_lang")] ?: "ru" }
    }
    val lyricsTranslationTargetLang by lyricsTranslationTargetLangFlow.collectAsStateWithLifecycle(initialValue = "ru")

    LaunchedEffect(lyricsTranslationTargetLang) {
        localTranslatedLyrics = null
    }

    val coroutineScope = rememberCoroutineScope()

    val onToggleRussian: () -> Unit = {
        val currentL = lyrics
        if (currentL != null) {
            if (localTranslatedLyrics == null && !isTranslating) {
                isTranslating = true
                coroutineScope.launch {
                    try {
                        val tr = effectiveLyricsTranslator.translateLyrics(currentL, lyricsTranslationTargetLang, currentSong?.id)
                        localTranslatedLyrics = tr
                        showRussianTranslation = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isTranslating = false
                    }
                }
            } else {
                showRussianTranslation = !showRussianTranslation
            }
        }
    }

    val hasTranslatedLyrics = remember(effectiveLyrics) {
        effectiveLyrics?.synced?.any { !it.translation.isNullOrBlank() } == true
    }

    val hasRomanizedLyrics = remember(effectiveLyrics) {
        val hasSynced = effectiveLyrics?.synced?.any { !it.romanization.isNullOrBlank() } == true
        val hasPlain = effectiveLyrics?.plain?.any { line ->
            MultiLangRomanizer.isScriptThatNeedsRomanization(line)
        } == true
        hasSynced || hasPlain
    }

    // Read lyrics alignment preference internally from DataStore
    val lyricsAlignmentFlow = remember(context) {
        context.dataStore.data.map { it[stringPreferencesKey("lyrics_alignment")] ?: "left" }
    }
    val lyricsAlignment by lyricsAlignmentFlow.collectAsStateWithLifecycle(initialValue = "left")

    // Read lyrics translation preference internally from DataStore
    val showLyricsTranslationFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("show_lyrics_translation")] ?: true }
    }
    val showLyricsTranslation by showLyricsTranslationFlow.collectAsStateWithLifecycle(initialValue = true)

    // Read lyrics romanization preference internally from DataStore
    val showLyricsRomanizationFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("show_lyrics_romanization")] ?: true }
    }
    val showLyricsRomanization by showLyricsRomanizationFlow.collectAsStateWithLifecycle(initialValue = true)

    // Read animated lyrics preference internally from DataStore
    val useAnimatedLyricsFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("use_animated_lyrics")] ?: true }
    }
    val rawUseAnimatedLyrics by useAnimatedLyricsFlow.collectAsStateWithLifecycle(initialValue = true)

    val performanceModeEnabledFlow = remember(context) {
        context.dataStore.data.map {
            (it[booleanPreferencesKey("performance_mode_enabled")] ?: false) ||
            (it[booleanPreferencesKey("lyrics_performance_mode")] ?: false)
        }
    }
    val performanceModeEnabled by performanceModeEnabledFlow.collectAsStateWithLifecycle(initialValue = false)

    // Automatically enable Advanced Lyrics View if lyrics have word-sync or subphrases (Tier 1 & Tier 2)
    val isAdvancedLyrics = effectiveLyrics?.isAdvancedLyrics == true
    val useAnimatedLyrics = if (isAdvancedLyrics) true else (rawUseAnimatedLyrics && !performanceModeEnabled)

    val isBlurSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !performanceModeEnabled
    val animatedLyricsBlurEnabledFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("animated_lyrics_blur_enabled")] ?: true }
    }
    val animatedLyricsBlurEnabledRaw by animatedLyricsBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val animatedLyricsBlurEnabled = animatedLyricsBlurEnabledRaw && isBlurSupported

    val animatedLyricsBlurStrengthFlow = remember(context) {
        context.dataStore.data.map { it[androidx.datastore.preferences.core.floatPreferencesKey("animated_lyrics_blur_strength")] ?: 2.5f }
    }
    val animatedLyricsBlurStrength by animatedLyricsBlurStrengthFlow.collectAsStateWithLifecycle(initialValue = 2.5f)

    // Read keep-screen-on preference from DataStore
    val keepScreenOnFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("keep_screen_on_lyrics")] ?: false }
    }
    var keepScreenOn by remember { mutableStateOf(false) }
    // Sync DataStore → local state
    LaunchedEffect(Unit) {
        keepScreenOnFlow.collect { keepScreenOn = it }
    }

    // Apply FLAG_KEEP_SCREEN_ON via the window when enabled
    val view = LocalView.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(keepScreenOn, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && keepScreenOn) {
                keepScreenOn = false
                coroutineScope.launch {
                    context.dataStore.edit { prefs ->
                        prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                    }
                }
            }
        }

        if (keepScreenOn) {
            view.keepScreenOn = true
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            view.keepScreenOn = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(keepScreenOn, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && keepScreenOn) {
                keepScreenOn = false
                coroutineScope.launch {
                    context.dataStore.edit { prefs ->
                        prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                    }
                }
            }
        }
        
        if (keepScreenOn) {
            view.keepScreenOn = true
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            view.keepScreenOn = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val resolvedAutoscrollSpec = autoscrollAnimationSpec ?: if (useAnimatedLyrics) {
        spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy
        )
    } else {
        tween(durationMillis = 450, easing = FastOutSlowInEasing)
    }

    var showFetchLyricsDialog by remember { mutableStateOf(false) }
    // Flag to prevent dialog from showing briefly after reset
    var wasResetTriggered by remember { mutableStateOf(false) }
    // Save lyrics dialog state
    var showSaveLyricsDialog by remember { mutableStateOf(false) }
    var showSyncControls by remember { mutableStateOf(false) }
    var previewSeekPositionMs by remember(currentSong?.id) { mutableStateOf<Long?>(null) }

    var showSyncedLyrics by remember(effectiveLyrics) {
        mutableStateOf(
            when {
                !effectiveLyrics?.synced.isNullOrEmpty() -> true
                !effectiveLyrics?.plain.isNullOrEmpty() -> false
                else -> null
            }
        )
    }

    val hasSyncedLyrics = remember(effectiveLyrics) {
        !effectiveLyrics?.synced.isNullOrEmpty()
    }

    // Immersive Mode State
    var immersiveMode by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showMoreSheet by remember { mutableStateOf(false) }
    val moreSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Swipe Gesture State
    val hapticFeedback = LocalHapticFeedback.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeActive by remember { mutableStateOf(false) }
    var hasTriggeredAction by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(LocalDensity.current) { swipeThreshold.toPx() }
    val overlayTranslation = remember { Animatable(0f) }
    val swipeProgress = remember { Animatable(0f) }

    // Reset keep-screen-on when the physical screen goes off (power button / OEM sleep gesture).
    // ACTION_SCREEN_OFF is a guaranteed platform broadcast; no OEM can suppress it.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    keepScreenOn = false
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Auto-hide controls logic
    LaunchedEffect(immersiveLyricsEnabled, lastInteractionTime, showSyncedLyrics, isImmersiveTemporarilyDisabled) {
        if (immersiveLyricsEnabled && showSyncedLyrics == true && !isImmersiveTemporarilyDisabled) {
            delay(immersiveLyricsTimeout)
            immersiveMode = true
        } else {
            immersiveMode = false
        }
    }

    // Font Scaling
    val fontScale by animateFloatAsState(
        targetValue = if (immersiveMode) 1.4f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "fontScale"
    )
    
    val scaledTextStyle = lyricsTextStyle.copy(
        fontSize = lyricsTextStyle.fontSize * fontScale,
        lineHeight = (lyricsTextStyle.fontSize.value * fontScale * 1.35f).sp
    )

    fun resetImmersiveTimer() {
        lastInteractionTime = System.currentTimeMillis()
        immersiveMode = false
    }

    LaunchedEffect(currentSong, lyrics, isLoadingLyrics) {
        if (lyrics != null || isLoadingLyrics) {
            showFetchLyricsDialog = false
            wasResetTriggered = false
        }
    }

    if (showFetchLyricsDialog) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            FetchLyricsDialog(
                uiState = lyricsSearchUiState,
                currentSong = currentSong,
                onConfirm = onSearchLyrics,
                onPickResult = onPickResult,
                onManualSearch = onManualSearch,
                onDismiss = {
                    showFetchLyricsDialog = false
                    onDismissLyricsSearch()
                    if (lyrics == null && !isLoadingLyrics) {
                        onBackClick()
                    }
                },
                onImport = onImportLyrics
            )
        }
    }

    // Save Lyrics Dialog
    if (showSaveLyricsDialog && lyrics != null && currentSong != null) {
        val hasSynced = !lyrics?.synced.isNullOrEmpty()
        val hasPlain = !lyrics?.plain.isNullOrEmpty()
        
        AlertDialog(
            onDismissRequest = { showSaveLyricsDialog = false },
            title = { Text(stringResource(R.string.save_lyrics_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.save_lyrics_dialog_message))
                    Spacer(modifier = Modifier.height(16.dp))
                    if (hasSynced) {
                        FilledTonalButton(
                            onClick = {
                                showSaveLyricsDialog = false
                                onSaveLyricsToFile(
                                    currentSong!!,
                                    lyrics!!,
                                    true
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.save_synced_lyrics))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (hasPlain) {
                        OutlinedButton(
                            onClick = {
                                showSaveLyricsDialog = false
                                onSaveLyricsToFile(
                                    currentSong!!,
                                    lyrics!!,
                                    false
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.save_plain_lyrics))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSaveLyricsDialog = false }) {
                    Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // ─── Enter / Predictive-back exit transformation ──────────────────
            // Read backProgressProvider inside graphicsLayer (draw-phase) — no layout
            // pass is triggered per gesture frame, same pattern as SheetVisualState.
            // 0f = fully visible, 1f = fully dismissed.
            // Effect: scale down to 92 % + slide down 8 % of height + fade to 72 % alpha.
            // Matches Android predictive back spec for full-screen destinations and
            // mirrors the scale+alpha treatment used across the rest of the app.
            .graphicsLayer {
                val p = backProgressProvider.value
                val scale = lerp(1f, 0.92f, p)
                scaleX = scale
                scaleY = scale
                translationY = lerp(0f, size.height * 0.08f, p)
            }
            .clip(RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isSwipeActive = true
                        hasTriggeredAction = false
                        dragOffset = 0f
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            swipeProgress.snapTo(0f)
                        }
                    },
                    onDragEnd = {
                        isSwipeActive = false
                        val committed = abs(dragOffset) > swipeThresholdPx && !hasTriggeredAction 
                        
                        if (committed) {
                            if (dragOffset > 0) onPrev() else onNext()
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        coroutineScope.launch {
                             swipeProgress.animateTo(0f, tween(200))
                             dragOffset = 0f
                        }
                    },
                    onDragCancel = {
                        isSwipeActive = false
                        dragOffset = 0f
                        coroutineScope.launch {
                            swipeProgress.animateTo(0f, tween(200))
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        resetImmersiveTimer()
                        
                        if (!hasTriggeredAction) {
                            dragOffset += dragAmount.x
                            val progress = (abs(dragOffset) / swipeThresholdPx).coerceIn(0f, 1f)
                            
                            coroutineScope.launch {
                                swipeProgress.snapTo(progress)
                            }
                        }
                    }
                )
            },
        containerColor = Color.Transparent,
        contentColor = contentColor,
        // Removed TopBar and FAB
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Live Apple Music style animated aurora mesh gradient
            AnimatedAuroraBackground(
                primaryColor = lyricHighlightColor,
                secondaryColor = accentColor,
                accentColor = backgroundColor,
                baseDarkColor = containerColor,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    resetImmersiveTimer()
                }
        ) {
            val initialSyncedLineIndex = remember(effectiveLyrics, playbackPositionFlow, lyricsSyncOffset) {
                resolveCurrentLineIndex(
                    lines = effectiveLyrics?.synced.orEmpty(),
                    position = (playbackPositionFlow.value + lyricsSyncOffset).coerceAtLeast(0L)
                ).coerceAtLeast(0)
            }
            val syncedListState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialSyncedLineIndex
            )
            val staticListState = rememberLazyListState()

            // Lyrics Content (Weight 1)
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Top Header Row: Track Info and Translation Pill (guaranteed no overlap)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp, start = 18.dp, end = 18.dp)
                        .align(Alignment.TopCenter)
                        .zIndex(3f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AnimatedContent(
                        targetState = currentSong,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(300)) + 
                             scaleIn(initialScale = 0.9f, animationSpec = tween(300)))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                        },
                        modifier = Modifier.weight(1f, fill = false),
                        label = "headerAnimation"
                    ) { song ->
                        LyricsTrackInfo(
                            song = song,
                            modifier = Modifier
                                .background(
                                    color = backgroundColor.copy(alpha = 0.85f),
                                    shape = CircleShape
                                )
                                .wrapContentWidth()
                                .animateContentSize(),
                            backgroundColor = backgroundColor.copy(alpha = 0.85f),
                            contentColor = onBackgroundColor,
                            isPlaying = isPlaying
                        )
                    }

                    if (lyrics != null && (!lyrics?.synced.isNullOrEmpty() || !lyrics?.plain.isNullOrEmpty())) {
                        Spacer(modifier = Modifier.width(10.dp))
                        val haptic = LocalHapticFeedback.current

                        Surface(
                            shape = CircleShape,
                            color = if (showRussianTranslation && localTranslatedLyrics != null) accentColor else backgroundColor.copy(alpha = 0.85f),
                            contentColor = if (showRussianTranslation && localTranslatedLyrics != null) onAccentColor else onBackgroundColor,
                            tonalElevation = 4.dp,
                            shadowElevation = 2.dp
                        ) {
                            val langTag = lyricsTranslationTargetLang.uppercase()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = { onToggleRussian() },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showLanguageSheet = true
                                        }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                if (isTranslating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = if (showRussianTranslation && localTranslatedLyrics != null) onAccentColor else accentColor
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = "Translate to $langTag (long press to change)",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = if (showRussianTranslation && localTranslatedLyrics != null) "$langTag Вкл" else langTag,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                when (showSyncedLyrics) {
                    null -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 110.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
                        ) {
                            item(key = "loader_or_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxSize(),
                                     contentAlignment = Alignment.Center
                                ) {
                                    if (isLoadingLyrics) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = context.resources.getString(R.string.loading_lyrics),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearWavyProgressIndicator(
                                                trackColor = accentColor.copy(alpha = 0.4f),
                                                color = accentColor,
                                                modifier = Modifier.width(100.dp)
                                            )
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = contentColor.copy(alpha = 0.35f),
                                                modifier = Modifier.size(52.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = context.resources.getString(R.string.lyrics_not_found),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = contentColor.copy(alpha = 0.55f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    true -> {
                        effectiveLyrics?.synced?.let { synced ->
                            SyncedLyricsList(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp),
                                contentPadding = PaddingValues(top = 130.dp, bottom = 100.dp),
                                lines = synced,
                                listState = syncedListState,
                                playbackPositionFlow = playbackPositionFlow,
                                lyricsSyncOffset = lyricsSyncOffset,
                                positionOverrideMs = previewSeekPositionMs,
                                accentColor = lyricHighlightColor,
                                textStyle = scaledTextStyle,
                                onLineClick = { syncedLine -> 
                                    onSeekTo(
                                        resolveSeekPositionMs(
                                            lineTimeMs = syncedLine.time.toLong(),
                                            lyricsSyncOffsetMs = lyricsSyncOffset
                                        )
                                    )
                                    resetImmersiveTimer()
                                },
                                highlightZoneFraction = highlightZoneFraction,
                                highlightOffsetDp = highlightOffsetDp,
                                autoscrollAnimationSpec = resolvedAutoscrollSpec,
                                useAnimatedLyrics = useAnimatedLyrics,
                                animatedLyricsBlurEnabled = animatedLyricsBlurEnabled,
                                animatedLyricsBlurStrength = animatedLyricsBlurStrength,
                                immersiveMode = immersiveMode,
                                lyricsAlignment = lyricsAlignment,
                                showTranslation = showLyricsTranslation || (showRussianTranslation && localTranslatedLyrics != null),
                                showRomanization = showLyricsRomanization,
                                performanceMode = performanceModeEnabled,
                                footer = {
                                    effectiveLyrics?.credits?.takeIf { it.isNotEmpty() }?.let { credits ->
                                        item {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 36.dp, bottom = 48.dp, start = 20.dp, end = 20.dp),
                                                horizontalAlignment = when (lyricsAlignment) {
                                                    "center" -> Alignment.CenterHorizontally
                                                    "right" -> Alignment.End
                                                    else -> Alignment.Start
                                                }
                                            ) {
                                                Text(
                                                    text = "Songwriters & Contributors",
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        color = LocalContentColor.current.copy(alpha = 0.5f),
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    modifier = Modifier.padding(bottom = 6.dp)
                                                )
                                                credits.forEach { creditLine ->
                                                    Text(
                                                        text = creditLine,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            color = LocalContentColor.current.copy(alpha = 0.65f)
                                                        ),
                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }

                    false -> {
                        effectiveLyrics?.plain?.let { plain ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = staticListState,
                                contentPadding = PaddingValues(
                                    start = 24.dp,
                                    end = 24.dp,
                                    top = 130.dp,
                                    bottom = 24.dp
                                )
                            ) {
                                itemsIndexed(
                                    items = plain,
                                    key = { index, line -> "$index-$line" }
                                ) { _, line ->
                                    PlainLyricsLine(
                                        line = line,
                                        style = lyricsTextStyle,
                                        lyricsAlignment = lyricsAlignment,
                                        showTranslation = if (hasTranslatedLyrics || (showRussianTranslation && localTranslatedLyrics != null)) showLyricsTranslation || (showRussianTranslation && localTranslatedLyrics != null) else true,
                                        showRomanization = if (hasRomanizedLyrics) showLyricsRomanization else true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }
                
                // Top Gradient for fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(containerColor.copy(alpha = 0.75f), Color.Transparent)
                            )
                        )
                )

                // Bottom Gradient for fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, containerColor.copy(alpha = 0.75f))
                            )
                        )
                )
            }

            // Controls Section (Auto-hide in immersive mode)
            AnimatedVisibility(
                visible = !immersiveMode,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor)
                        .padding(bottom = paddingValues.calculateBottomPadding() + 10.dp, end = 16.dp, start = 16.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // Reset timer on any touch down or move in this area
                                    if (event.changes.any { it.pressed }) {
                                         resetImmersiveTimer()
                                    }
                                }
                            }
                        }
                ) {
                                AnimatedVisibility(
                    visible = showSyncedLyrics == true && lyrics?.synced != null && showSyncControls,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    LyricsSyncControls(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        offsetMillis = lyricsSyncOffset,
                        onOffsetChange = onLyricsSyncOffsetChange,
                        backgroundColor = backgroundColor,
                        accentColor = sheetColors.syncButtonContainer,
                        onAccentColor = sheetColors.syncButtonContent,
                        onBackgroundColor = onBackgroundColor
                    )
                }

                // Playback Controls Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play/Pause Button (Smaller)
                    val playPauseCornerRadius by animateDpAsState(
                        targetValue = if (isPlaying) 18.dp else 50.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "playPauseShape"
                    )

                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(playPauseCornerRadius))
                            .background(playPauseColor)
                            .clickable {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = isPlaying,
                            label = "playPauseIconAnimation"
                        ) { playing ->
                            if (playing) {
                                Icon(
                                    modifier = Modifier.size(32.dp),
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = "Pause",
                                    tint = onPlayPauseColor
                                )
                            } else {
                                Icon(
                                    modifier = Modifier.size(32.dp),
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_play),
                                    tint = onPlayPauseColor
                                )
                            }
                        }
                    }

                    // Progress Bar
                    LyricsPlaybackSeekBar(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        playbackPositionFlow = playbackPositionFlow,
                        backgroundColor = backgroundColor,
                        onBackgroundColor = onBackgroundColor,
                        accentColor = accentColor,
                        totalDuration = stablePlayerState.totalDuration,
                        onSeekTo = onSeekTo,
                        onSeekPreviewChange = { previewSeekPositionMs = it },
                        isPlaying = isPlaying,
                        songId = stablePlayerState.currentSong?.id ?: ""
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Floating Toolbar
                LyricsFloatingToolbar(
                    modifier = Modifier.padding(horizontal = 0.dp),
                    showSyncedLyrics = showSyncedLyrics,
                    hasSyncedLyrics = hasSyncedLyrics,
                    onShowSyncedLyricsChange = { showSyncedLyrics = it },
                    onNavigateBack = {
                        onBackClick()
                    },
                    onMoreClick = { showMoreSheet = true },
                    backgroundColor = backgroundColor,
                    onBackgroundColor = onBackgroundColor,
                    accentColor = accentColor,
                    onAccentColor = onAccentColor,
                    // Pass progress so the back button animates with the gesture (draw-phase).
                    backProgressProvider = { backProgressProvider.value },
                )
             }
            }
        }

        if (showMoreSheet) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes
            ) {
                LyricsMoreBottomSheet(
                    onDismissRequest = { showMoreSheet = false },
                    sheetState = moreSheetState,
                    lyrics = lyrics,
                    showSyncedLyrics = showSyncedLyrics == true,
                    isSyncControlsVisible = showSyncControls,
                    onSaveLyricsAsLrc = { showSaveLyricsDialog = true },
                    onResetImportedLyrics = {
                        wasResetTriggered = true
                        resetLyricsForCurrentSong()
                    },
                    onToggleSyncControls = {
                        resetImmersiveTimer()
                        showSyncControls = !showSyncControls
                    },
                    isImmersiveTemporarilyDisabled = isImmersiveTemporarilyDisabled,
                    onSetImmersiveTemporarilyDisabled = {
                        resetImmersiveTimer()
                        onSetImmersiveTemporarilyDisabled(it)
                    },
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnChange = { enabled ->
                        keepScreenOn = enabled
                        coroutineScope.launch {
                            context.dataStore.edit { prefs ->
                                prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = enabled
                            }
                        }
                    },
                    lyricsAlignment = lyricsAlignment,
                    onLyricsAlignmentChange = { newAlignment ->
                        coroutineScope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[stringPreferencesKey("lyrics_alignment")] = newAlignment
                            }
                        }
                    },
                    hasTranslatedLyrics = hasTranslatedLyrics,
                    hasRomanizedLyrics = hasRomanizedLyrics,
                    showTranslation = showLyricsTranslation,
                    showRomanization = showLyricsRomanization,
                    onShowTranslationChange = { enabled ->
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[booleanPreferencesKey("show_lyrics_translation")] = enabled
                            }
                        }
                    },
                    onShowRomanizationChange = { enabled ->
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[booleanPreferencesKey("show_lyrics_romanization")] = enabled
                            }
                        }
                    },
                    immersiveLyricsEnabled = immersiveLyricsEnabled,
                    isShuffleEnabled = isShuffleEnabled,
                    repeatMode = repeatMode,
                    isFavoriteProvider = isFavoriteProvider,
                    onShuffleToggle = {
                        resetImmersiveTimer()
                        onShuffleToggle()
                    },
                    onRepeatToggle = {
                        resetImmersiveTimer()
                        onRepeatToggle()
                    },
                    onFavoriteToggle = {
                        resetImmersiveTimer()
                        onFavoriteToggle()
                    },
                )
            }
        }

        if (showLanguageSheet) {
            val languageSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            MaterialTheme(
                colorScheme = colorScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes
            ) {
                ModalBottomSheet(
                    onDismissRequest = { showLanguageSheet = false },
                    sheetState = languageSheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    dragHandle = { BottomSheetDefaults.DragHandle() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = accentColor.copy(alpha = 0.15f),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.lyrics_translation_language_title),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.lyrics_translation_language_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val isAiActive = !activeAiProviderName.isNullOrBlank()
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isAiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isAiActive) Icons.Rounded.AutoAwesome else Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = if (isAiActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                val badgeText = if (isAiActive) {
                                    stringResource(R.string.lyrics_translation_ai_active, activeAiProviderName.orEmpty())
                                } else {
                                    stringResource(R.string.lyrics_translation_google_active)
                                }
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isAiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val targetLanguages = remember {
                            listOf(
                                "ru" to "Русский (RU)",
                                "en" to "English (EN)",
                                "es" to "Español (ES)",
                                "de" to "Deutsch (DE)",
                                "fr" to "Français (FR)",
                                "it" to "Italiano (IT)",
                                "uk" to "Українська (UK)",
                                "kk" to "Қазақша (KK)",
                                "be" to "Беларуская (BE)",
                                "pl" to "Polski (PL)",
                                "zh" to "中文 (ZH)",
                                "ja" to "日本語 (JA)",
                                "ko" to "한국어 (KO)",
                                "tr" to "Türkçe (TR)"
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(targetLanguages.size) { idx ->
                                val (code, name) = targetLanguages[idx]
                                val isSelected = lyricsTranslationTargetLang.equals(code, ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isSelected) accentColor else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                                    contentColor = if (isSelected) onAccentColor else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable {
                                            showLanguageSheet = false
                                            coroutineScope.launch {
                                                context.dataStore.edit { preferences ->
                                                    preferences[stringPreferencesKey("lyrics_translation_target_lang")] = code
                                                }
                                            }
                                            val currentL = lyrics
                                            if (showRussianTranslation && currentL != null) {
                                                localTranslatedLyrics = null
                                                isTranslating = true
                                                coroutineScope.launch {
                                                    try {
                                                        val tr = effectiveLyricsTranslator.translateLyrics(currentL, code, currentSong?.id)
                                                        localTranslatedLyrics = tr
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    } finally {
                                                        isTranslating = false
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = onAccentColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

       // Show Controls Button (Overlay)
       AnimatedVisibility(
            visible = immersiveMode,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            FilledIconButton(
                onClick = { resetImmersiveTimer() },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = accentColor,
                    contentColor = onAccentColor
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Show Controls"
                )
            }
        }
       
       // Swipe Feedback Overlay
       if (isSwipeActive || swipeProgress.value > 0f) {
           val isNext = dragOffset < 0
           val overlayAlignment = if (isNext) Alignment.CenterEnd else Alignment.CenterStart
           val icon = if (isNext) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious
           
           Box(
               modifier = Modifier
                   .align(overlayAlignment)
                   .size(100.dp) // Base size
                   .padding(
                       start = if(isNext) 0.dp else 6.dp,
                       end = if(isNext) 6.dp else 0.dp
                   )
                   .graphicsLayer {
                        val widthPx = size.width
                        val initialOffset = if(isNext) widthPx else -widthPx
                        translationX = initialOffset * (1f - swipeProgress.value)

                        scaleX = 0.8f + (swipeProgress.value * 0.2f)
                        scaleY = 0.8f + (swipeProgress.value * 0.2f)
                   }
                   .background(
                        color = accentColor, // No alpha modulation
                        shape = RoundedCornerShape(
                            topStart = if(isNext) 360.dp else 8.dp,
                            bottomStart = if(isNext) 360.dp else 8.dp,
                            topEnd = if(isNext) 8.dp else 360.dp,
                            bottomEnd = if(isNext) 8.dp else 360.dp
                        )
                   ),
               contentAlignment = Alignment.Center
           ) {
               Icon(
                   imageVector = icon,
                   contentDescription = null,
                   modifier = Modifier.size(48.dp),
                   tint = onAccentColor
               )
           }
       }

      }
    }
}

@Composable
private fun LyricsPlaybackSeekBar(
    playbackPositionFlow: StateFlow<Long>,
    backgroundColor: Color,
    onBackgroundColor: Color,
    accentColor: Color,
    totalDuration: Long,
    onSeekTo: (Long) -> Unit,
    onSeekPreviewChange: (Long?) -> Unit,
    isPlaying: Boolean,
    songId: String,
    modifier: Modifier = Modifier
) {
    val playbackPosition by playbackPositionFlow.collectAsStateWithLifecycle()

    PlayerSeekBar(
        backgroundColor = backgroundColor,
        onBackgroundColor = onBackgroundColor,
        primaryColor = accentColor,
        currentPosition = playbackPosition,
        totalDuration = totalDuration,
        onSeek = onSeekTo,
        onSeekPreview = onSeekPreviewChange,
        isPlaying = isPlaying,
        songId = songId,
        modifier = modifier
    )
}

@OptIn(ExperimentalSnapperApi::class)
@Composable
fun SyncedLyricsList(
    lines: List<SyncedLine>,
    listState: LazyListState,
    playbackPositionFlow: StateFlow<Long>,
    lyricsSyncOffset: Int,
    positionOverrideMs: Long? = null,
    accentColor: Color,
    textStyle: TextStyle,
    onLineClick: (SyncedLine) -> Unit,
    highlightZoneFraction: Float,
    highlightOffsetDp: Dp,
    autoscrollAnimationSpec: AnimationSpec<Float>,
    useAnimatedLyrics: Boolean = false,
    animatedLyricsBlurEnabled: Boolean = true,
    animatedLyricsBlurStrength: Float = 2.5f,
    immersiveMode: Boolean = false,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    performanceMode: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    footer: LazyListScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val playbackPosition by playbackPositionFlow.collectAsStateWithLifecycle()
    var smoothPosition by remember { mutableLongStateOf(playbackPosition) }

    LaunchedEffect(playbackPosition, positionOverrideMs, performanceMode) {
        if (positionOverrideMs != null) {
            smoothPosition = positionOverrideMs
            return@LaunchedEffect
        }
        val startBase = playbackPosition
        val startTime = android.os.SystemClock.uptimeMillis()
        val tickIntervalMs = if (performanceMode) 33L else 16L
        while (isActive) {
            val elapsed = android.os.SystemClock.uptimeMillis() - startTime
            smoothPosition = startBase + elapsed
            delay(tickIntervalMs)
        }
    }

    val position = if (positionOverrideMs != null) {
        positionOverrideMs
    } else {
        (smoothPosition + lyricsSyncOffset).coerceAtLeast(0L)
    }
    val isPreviewSeeking = positionOverrideMs != null
    val effectiveLines = remember(lines) {
        LyricsUtils.splitMultiLineSyncedLines(lines)
    }
    val currentLineIndex by remember(position, effectiveLines) {
        derivedStateOf {
            resolveCurrentLineIndex(lines = effectiveLines, position = position)
        }
    }
    var hasAlignedInitialLine by remember(effectiveLines) { mutableStateOf(false) }
    var lastAutoScrolledLineIndex by remember(effectiveLines) { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier = modifier) {
        val metrics = remember(maxHeight, highlightZoneFraction, highlightOffsetDp) {
            calculateHighlightMetrics(maxHeight, highlightZoneFraction, highlightOffsetDp)
        }
        val highlightOffsetPx = remember(highlightOffsetDp, density) { with(density) { highlightOffsetDp.toPx() } }

        val snapperLayoutInfo = rememberLazyListSnapperLayoutInfo(
            lazyListState = listState,
            snapOffsetForItem = { layoutInfo, item ->
                val viewportHeight = layoutInfo.endScrollOffset - layoutInfo.startScrollOffset
                highlightSnapOffsetPx(viewportHeight, item.size, highlightOffsetPx)
            }
        )
        val flingBehavior = rememberSnapperFlingBehavior(layoutInfo = snapperLayoutInfo)

        LaunchedEffect(currentLineIndex, effectiveLines.size, metrics, isPreviewSeeking) {
            if (effectiveLines.isEmpty()) return@LaunchedEffect
            if (currentLineIndex !in effectiveLines.indices) return@LaunchedEffect
            if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect

            if (!hasAlignedInitialLine) {
                listState.scrollToItem(currentLineIndex)
                snapToSnapIndex(
                    listState = listState,
                    layoutInfo = snapperLayoutInfo,
                    targetIndex = currentLineIndex
                )
                hasAlignedInitialLine = true
                lastAutoScrolledLineIndex = currentLineIndex
                return@LaunchedEffect
            }

            if (listState.isScrollInProgress && !isPreviewSeeking) return@LaunchedEffect

            val lineJumpDistance = if (lastAutoScrolledLineIndex >= 0) {
                abs(currentLineIndex - lastAutoScrolledLineIndex)
            } else {
                0
            }

            if (isPreviewSeeking) {
                if (lineJumpDistance > 2) {
                    listState.scrollToItem(currentLineIndex)
                    snapToSnapIndex(
                        listState = listState,
                        layoutInfo = snapperLayoutInfo,
                        targetIndex = currentLineIndex
                    )
                } else {
                    animateToSnapIndex(
                        listState = listState,
                        layoutInfo = snapperLayoutInfo,
                        targetIndex = currentLineIndex,
                        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing)
                    )
                }
                lastAutoScrolledLineIndex = currentLineIndex
                return@LaunchedEffect
            }

            // Tactile smooth spring scrolling (Apple Music physics with gentle fluid glide)
            val dynamicAnimationSpec = if (useAnimatedLyrics) {
                spring<Float>(
                    stiffness = Spring.StiffnessLow,
                    dampingRatio = 0.90f
                )
            } else {
                autoscrollAnimationSpec
            }

            animateToSnapIndex(
                listState = listState,
                layoutInfo = snapperLayoutInfo,
                targetIndex = currentLineIndex,
                animationSpec = dynamicAnimationSpec
            )
            lastAutoScrolledLineIndex = currentLineIndex
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                flingBehavior = flingBehavior,
                contentPadding = contentPadding
            ) {
                itemsIndexed(
                    items = effectiveLines,
                    key = { index, item -> "${item.time}_$index" }
                ) { index, line ->
                    val nextTime = effectiveLines.subList(index + 1, effectiveLines.size).firstOrNull { it.time > line.time }?.time
                        ?: effectiveLines.getOrNull(index + 1)?.time
                        ?: Int.MAX_VALUE
                    val distanceFromCurrent = if (currentLineIndex != -1) {
                        when {
                            // Subphrase paired with preceding lead line: keep both in focus
                            line.isBackground && index > 0 && currentLineIndex == index - 1 -> 0
                            // Lead line paired with following subphrase: keep both in focus
                            !line.isBackground && index + 1 < effectiveLines.size && effectiveLines[index + 1].isBackground && currentLineIndex == index + 1 -> 0
                            else -> abs(currentLineIndex - index)
                        }
                    } else 100
                    
                    val parallaxModifier = if (useAnimatedLyrics) {
                        Modifier.graphicsLayer {
                            // Calculate translation dynamically inside graphicsLayer to avoid recomposing the row during scroll
                            val currentLayoutInfo = listState.layoutInfo
                            val lineItemInfo = currentLayoutInfo.visibleItemsInfo.find { it.index == index }
                            val itemCenter = lineItemInfo?.let { it.offset + (it.size / 2f) }
                            val viewportCenter = currentLayoutInfo.viewportEndOffset / 2f
                            
                            val distanceFromCenter = itemCenter?.let { it - viewportCenter } ?: 0f
                            
                            val maxTranslation = 40f 
                            val distanceRatio = (distanceFromCenter / viewportCenter).coerceIn(-1f, 1f)
                            translationY = distanceRatio * distanceRatio * distanceRatio * maxTranslation 
                        }
                    } else Modifier

                    if (line.line.isNotBlank()) {
                        LyricLineRow(
                            line = line,
                            nextTime = nextTime,
                            position = position,
                            distanceFromCurrent = distanceFromCurrent,
                            useAnimatedLyrics = useAnimatedLyrics,
                            animatedLyricsBlurEnabled = animatedLyricsBlurEnabled,
                            animatedLyricsBlurStrength = animatedLyricsBlurStrength,
                            immersiveMode = immersiveMode,
                            lyricsAlignment = lyricsAlignment,
                            showTranslation = showTranslation,
                            showRomanization = showRomanization,
                            performanceMode = performanceMode,
                            accentColor = accentColor,
                            style = textStyle,
                            modifier = parallaxModifier
                                .fillMaxWidth()
                                .testTag("synced_line_${line.time}"),
                            onClick = { onLineClick(line) }
                        )
                    } else {
                        val endMs = if (nextTime < Int.MAX_VALUE) nextTime.toLong() else (line.time + 4000L)
                        val isInterludeActive = position in line.time.toLong()..<endMs
                        LyricsInterludeDots(
                            startMs = line.time.toLong(),
                            endMs = endMs,
                            positionProvider = { position },
                            accentColor = accentColor,
                            visible = isInterludeActive,
                            alignment = when {
                                line.agent == "v1" -> LyricsInterludeAlignment.START
                                line.agent == "v2" -> LyricsInterludeAlignment.END
                                lyricsAlignment == "center" -> LyricsInterludeAlignment.CENTER
                                lyricsAlignment == "right" -> LyricsInterludeAlignment.END
                                else -> LyricsInterludeAlignment.CENTER
                            },
                            modifier = parallaxModifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
// 16 dp Spacer removed to allow dynamic padding in LyricLineRow
                }
                footer()
            }

//            if (metrics.zoneHeight > 0.dp) {
//                Box(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .offset(y = metrics.topPadding)
//                        .height(metrics.zoneHeight)
//                        .align(Alignment.TopCenter)
//                        .clip(RoundedCornerShape(18.dp))
//                        .background(accentColor.copy(alpha = 0.12f))
//                        .testTag("synced_highlight_zone")
//                )
//            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricLineRow(
    line: SyncedLine,
    nextTime: Int,
    position: Long,
    distanceFromCurrent: Int = 100,
    useAnimatedLyrics: Boolean = false,
    animatedLyricsBlurEnabled: Boolean = true,
    animatedLyricsBlurStrength: Float = 2.5f,
    immersiveMode: Boolean = false,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    performanceMode: Boolean = false,
    accentColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val sanitizedLine = remember(line.line) { sanitizeLyricLineText(line.line) }
    val lineEndTime = remember(line, nextTime) {
        resolveLineEndTimeMs(line, nextTime)
    }
    val sanitizedWords = remember(line.words) {
        line.words?.let(::sanitizeSyncedWords)?.takeIf { it.isNotEmpty() }
    }
    val sanitizedWordClusters = remember(sanitizedWords) {
        sanitizedWords?.takeIf { it.isNotEmpty() }?.let(::clusterSyncedWords)
    }
    val isCurrentLine by remember(position, line.time, lineEndTime) {
        derivedStateOf { position in line.time.toLong()..<lineEndTime }
    }
    val unhighlightedColor = LocalContentColor.current.copy(alpha = 0.45f)
    val lineColor by animateColorAsState(
        targetValue = if (isCurrentLine) accentColor else unhighlightedColor,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "lineColor"
    )

    // Animated mode: fisheye scaling + alpha based on distance from current line
    val targetScale = if (useAnimatedLyrics) {
        if (line.isBackground) {
            when (distanceFromCurrent) {
                0 -> 1.0f
                1 -> 0.94f
                else -> 0.88f
            }
        } else {
            when (distanceFromCurrent) {
                0 -> if (immersiveMode) 1.03f else 1.08f
                1 -> 0.96f
                else -> 0.88f
            }
        }
    } else 1f
    val verticalPadding = if (useAnimatedLyrics) 16.dp else 12.dp
    val baseTargetAlpha = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> 1.0f; 1 -> 0.65f; 2 -> 0.45f; else -> 0.28f
    } else 1f
    val targetAlpha = if (line.isBackground) baseTargetAlpha * 0.88f else baseTargetAlpha

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ) else tween(durationMillis = 200),
        label = "lineScale"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (useAnimatedLyrics) tween(
            durationMillis = 350,
            easing = FastOutSlowInEasing
        ) else tween(durationMillis = 200),
        label = "lineAlpha"
    )

    // Blur Effect
    val targetBlur = if (useAnimatedLyrics && animatedLyricsBlurEnabled && distanceFromCurrent > 0) {
        (distanceFromCurrent * animatedLyricsBlurStrength).coerceAtMost(10f).dp
    } else 0.dp

    val blurRadius by animateDpAsState(
        targetValue = targetBlur,
        animationSpec = if (useAnimatedLyrics) tween(durationMillis = 400, easing = FastOutSlowInEasing) else tween(durationMillis = 200),
        label = "lineBlur"
    )

    // Background vocals & duet styling
    val effectiveStyle = remember(style, line.isBackground) {
        if (line.isBackground) {
            style.copy(
                fontSize = (style.fontSize.value * 0.88f).sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                lineHeight = (style.fontSize.value * 0.88f * 1.45f).sp
            )
        } else {
            style.copy(
                lineHeight = (style.fontSize.value * 1.35f).sp
            )
        }
    }

    // Animated mode: apply graphicsLayer for scale/alpha transforms
    val baseModifier = if (useAnimatedLyrics && !immersiveMode) {
        when {
            line.isBackground -> modifier.padding(horizontal = 12.dp)
            line.agent == "v1" -> modifier.padding(end = 40.dp)
            line.agent == "v2" -> modifier.padding(start = 40.dp)
            lyricsAlignment == "center" -> modifier.padding(horizontal = 36.dp)
            lyricsAlignment == "right" -> modifier.padding(start = 36.dp)
            else -> modifier.padding(end = 36.dp)
        }
    } else {
        modifier
    }
    val animatedModifier = if (useAnimatedLyrics) {
        baseModifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(
                    pivotFractionX = when {
                        line.isBackground -> 0.5f
                        line.agent == "v1" -> 0f
                        line.agent == "v2" -> 1f
                        lyricsAlignment == "center" -> 0.5f
                        lyricsAlignment == "right" -> 1f
                        else -> 0f
                    },
                    pivotFractionY = 0.5f
                )
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
    } else baseModifier

    // Roman or Translate Logic
    val translationText = line.translation
    val romanizationText = line.romanization

    val secondaryStyle = remember(effectiveStyle) {
        effectiveStyle.copy(
            fontSize = (effectiveStyle.fontSize.value * 0.75f).sp,
            fontWeight = FontWeight.Normal
        )
    }

    val romanizationColor = lineColor.copy(alpha = lineColor.alpha * 0.85f)
    val translationColor = lineColor.copy(alpha = lineColor.alpha * 0.55f)

    val horizontalAlignment = when {
        line.isBackground -> Alignment.CenterHorizontally
        line.agent == "v1" -> Alignment.Start
        line.agent == "v2" -> Alignment.End
        lyricsAlignment == "center" -> Alignment.CenterHorizontally
        lyricsAlignment == "right" -> Alignment.End
        else -> Alignment.Start
    }

    val textAlign = when {
        line.isBackground -> TextAlign.Center
        line.agent == "v1" -> TextAlign.Left
        line.agent == "v2" -> TextAlign.Right
        lyricsAlignment == "center" -> TextAlign.Center
        lyricsAlignment == "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val boxAlignment = when {
        line.isBackground -> Alignment.TopCenter
        line.agent == "v1" -> Alignment.TopStart
        line.agent == "v2" -> Alignment.TopEnd
        lyricsAlignment == "center" -> Alignment.TopCenter
        lyricsAlignment == "right" -> Alignment.TopEnd
        else -> Alignment.TopStart
    }

    if (sanitizedWordClusters.isNullOrEmpty()) {
        Column(
            modifier = animatedModifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(vertical = verticalPadding, horizontal = 8.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            val subLines = remember(sanitizedLine) {
                sanitizedLine.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(sanitizedLine) }
            }
            val numSubLines = subLines.size

            subLines.forEachIndexed { subIndex, subText ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = boxAlignment
                ) {
                    // Invisible bold text to reserve layout space and prevent reflow
                    Text(
                        text = subText,
                        style = effectiveStyle,
                        color = Color.Transparent,
                        fontWeight = FontWeight.Bold,
                        textAlign = textAlign,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    // Base unsung / normal line text
                    Text(
                        text = subText,
                        style = effectiveStyle,
                        color = if (isCurrentLine && useAnimatedLyrics) unhighlightedColor else lineColor,
                        fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                        textAlign = textAlign,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    // Progressive sweep fill for active line (Apple Music shimmering line style)
                    if (isCurrentLine && useAnimatedLyrics) {
                        val totalDuration = (lineEndTime - line.time).coerceAtLeast(1L)
                        val subDuration = totalDuration / numSubLines
                        val subStart = line.time.toLong() + (subIndex * subDuration)
                        val subEnd = subStart + subDuration

                        val rawProgress = if (position <= subStart) {
                            0f
                        } else if (position >= subEnd) {
                            1f
                        } else {
                            ((position - subStart).toFloat() / subDuration.toFloat()).coerceIn(0f, 1f)
                        }

                        val feather = 0.05f
                        val shimmerWidth = 0.12f
                        val sweepBrush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to accentColor,
                                (rawProgress - shimmerWidth).coerceAtLeast(0f) to accentColor,
                                (rawProgress - feather).coerceIn(0f, 1f) to Color.White.copy(alpha = 0.95f),
                                rawProgress to Color.White,
                                (rawProgress + feather).coerceIn(0f, 1f) to accentColor.copy(alpha = 0.85f),
                                (rawProgress + shimmerWidth).coerceAtMost(1f) to Color.Transparent,
                                1.0f to Color.Transparent
                            )
                        )

                        Text(
                            text = subText,
                            style = effectiveStyle.copy(
                                brush = sweepBrush,
                                shadow = Shadow(
                                    color = accentColor.copy(alpha = 0.35f),
                                    offset = Offset.Zero,
                                    blurRadius = 10f
                                )
                            ),
                            fontWeight = FontWeight.Bold,
                            textAlign = textAlign,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (showRomanization && !romanizationText.isNullOrBlank()) {
                Text(
                    text = romanizationText,
                    style = secondaryStyle,
                    color = romanizationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && !translationText.isNullOrBlank()) {
                Text(
                    text = translationText,
                    style = secondaryStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (!line.subPhrases.isNullOrEmpty()) {
                SubPhrasesSection(
                    subPhrases = line.subPhrases,
                    position = position,
                    isCurrentLine = isCurrentLine,
                    useAnimatedLyrics = useAnimatedLyrics,
                    performanceMode = performanceMode,
                    style = effectiveStyle,
                    accentColor = accentColor,
                    unhighlightedColor = unhighlightedColor,
                    alignment = horizontalAlignment,
                    textAlign = textAlign
                )
            }
        }
    } else {
        val isLineActiveOrAdjacent = abs(distanceFromCurrent) <= 1
        val highlightedWordIndex by remember(position, sanitizedWords, line.time, lineEndTime, isLineActiveOrAdjacent) {
            derivedStateOf {
                if (!isLineActiveOrAdjacent) null
                else resolveHighlightedWordIndex(
                    words = requireNotNull(sanitizedWords),
                    positionMs = position,
                    lineStartTimeMs = line.time.toLong(),
                    lineEndTimeMs = lineEndTime
                )
            }
        }

        Column(
            modifier = animatedModifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(vertical = verticalPadding, horizontal = 8.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = when {
                    line.isBackground -> Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                    line.agent == "v1" -> Arrangement.spacedBy(10.dp, Alignment.Start)
                    line.agent == "v2" -> Arrangement.spacedBy(10.dp, Alignment.End)
                    lyricsAlignment == "center" -> Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                    lyricsAlignment == "right" -> Arrangement.spacedBy(10.dp, Alignment.End)
                    else -> Arrangement.spacedBy(10.dp, Alignment.Start)
                },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sanitizedWordClusters.forEach { cluster ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        cluster.words.forEachIndexed { clusterOffset, word ->
                            val wordIndex = cluster.startIndex + clusterOffset
                            key("${line.time}_${word.time}_${word.word}_$wordIndex") {
                                val activeIdx = highlightedWordIndex ?: -1
                                val isHighlighted = isCurrentLine && wordIndex == activeIdx
                                val isPast = isCurrentLine && activeIdx >= 0 && wordIndex < activeIdx
                                val wordStart = word.time.toLong()
                                val explicitEnd = word.endTime?.toLong()
                                val nextWordTime = if (explicitEnd != null && explicitEnd > wordStart) {
                                    explicitEnd
                                } else if (sanitizedWords != null && wordIndex + 1 < sanitizedWords.size) {
                                    sanitizedWords[wordIndex + 1].time.toLong()
                                } else {
                                    line.endTime?.toLong() ?: minOf(wordStart + 1500L, lineEndTime)
                                }
                                val wordEnd = normalizeWordEndTime(wordStart, nextWordTime, lineEndTime)
                                LyricWordSpan(
                                    word = word,
                                    isHighlighted = isHighlighted,
                                    isPast = isPast,
                                    useAnimatedLyrics = useAnimatedLyrics,
                                    style = effectiveStyle,
                                    highlightedColor = accentColor,
                                    unhighlightedColor = unhighlightedColor,
                                    position = position,
                                    wordStartTime = wordStart,
                                    wordEndTime = wordEnd,
                                    isBackground = line.isBackground,
                                    performanceMode = performanceMode
                                )
                            }
                        }
                    }
                }
            }

            if (showRomanization && !romanizationText.isNullOrBlank()) {
                Text(
                    text = romanizationText,
                    style = secondaryStyle,
                    color = romanizationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && !translationText.isNullOrBlank()) {
                Text(
                    text = translationText,
                    style = secondaryStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (!line.subPhrases.isNullOrEmpty()) {
                SubPhrasesSection(
                    subPhrases = line.subPhrases,
                    position = position,
                    isCurrentLine = isCurrentLine,
                    useAnimatedLyrics = useAnimatedLyrics,
                    performanceMode = performanceMode,
                    style = effectiveStyle,
                    accentColor = accentColor,
                    unhighlightedColor = unhighlightedColor,
                    alignment = horizontalAlignment,
                    textAlign = textAlign
                )
            }
        }
    }
}

@Composable
private fun SubPhrasesSection(
    subPhrases: List<com.unshoo.pixelmusic.data.model.SubPhrase>,
    position: Long,
    isCurrentLine: Boolean,
    useAnimatedLyrics: Boolean,
    performanceMode: Boolean,
    style: TextStyle,
    accentColor: Color,
    unhighlightedColor: Color,
    alignment: Alignment.Horizontal,
    textAlign: TextAlign
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalAlignment = alignment
    ) {
        for (subPhrase in subPhrases) {
            val cleanSubPhraseText = remember(subPhrase.text) {
                LyricsUtils.cleanLyricBrackets(
                    subPhrase.text.replace(Regex("""[\(\)\[\]（）【】〔〕《》\{\}]"""), "")
                ).trim()
            }
            if (cleanSubPhraseText.isEmpty()) continue

            val subStart = subPhrase.startTime.toLong()
            val subEnd = maxOf(subPhrase.endTime.toLong(), subStart + 1200L)

            // Subphrases smoothly appear ONLY when their moment is active
            val isVisible = isCurrentLine && (position in (subStart - 200L)..(subEnd + 350L))
            val isActive = isCurrentLine && (position in subStart..subEnd)

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = if (performanceMode) 150 else 280,
                        easing = FastOutSlowInEasing
                    )
                ) + expandVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioLowBouncy
                    ),
                    expandFrom = Alignment.Top
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = if (performanceMode) 150 else 300,
                        easing = FastOutSlowInEasing
                    )
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = if (performanceMode) 150 else 280,
                        easing = FastOutSlowInEasing
                    ),
                    shrinkTowards = Alignment.Top
                )
            ) {
                val subStyle = remember(style) {
                    style.copy(
                        fontSize = (style.fontSize.value * 0.82f).sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontWeight = FontWeight.Medium
                    )
                }

                val cleanWords = remember(subPhrase.words) {
                    subPhrase.words?.mapNotNull { w ->
                        val cleanedWord = LyricsUtils.cleanLyricBrackets(
                            w.word.replace(Regex("""[\(\)\[\]（）【】〔〕《》\{\}]"""), "")
                        ).trim()
                        if (cleanedWord.isEmpty()) null else w.copy(word = cleanedWord)
                    }
                }

                if (!cleanWords.isNullOrEmpty() && useAnimatedLyrics) {
                    FlowRow(
                        modifier = Modifier
                            .padding(vertical = 2.dp, horizontal = 4.dp),
                        horizontalArrangement = when (alignment) {
                            Alignment.CenterHorizontally -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            Alignment.End -> Arrangement.spacedBy(8.dp, Alignment.End)
                            else -> Arrangement.spacedBy(8.dp, Alignment.Start)
                        },
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cleanWords.forEachIndexed { idx, word ->
                            val wordStart = word.time.toLong()
                            val nextWordTime = if (idx + 1 < cleanWords.size) {
                                cleanWords[idx + 1].time.toLong()
                            } else {
                                subEnd
                            }
                            val wordEnd = normalizeWordEndTime(wordStart, nextWordTime, subEnd)
                            val isHighlighted = isVisible && position >= wordStart && position < wordEnd
                            val isPast = isVisible && position >= wordEnd

                            LyricWordSpan(
                                word = word,
                                isHighlighted = isHighlighted,
                                isPast = isPast,
                                useAnimatedLyrics = useAnimatedLyrics,
                                style = subStyle,
                                highlightedColor = accentColor,
                                unhighlightedColor = unhighlightedColor,
                                position = position,
                                wordStartTime = wordStart,
                                wordEndTime = wordEnd,
                                isBackground = true,
                                performanceMode = performanceMode
                            )
                        }
                    }
                } else {
                    val boxAlignment = when (alignment) {
                        Alignment.CenterHorizontally -> Alignment.Center
                        Alignment.End -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    }
                    Box(
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp),
                        contentAlignment = boxAlignment
                    ) {
                        // Base unsung / background subphrase text
                        Text(
                            text = cleanSubPhraseText,
                            style = subStyle,
                            color = if (useAnimatedLyrics && (isActive || position >= subStart)) unhighlightedColor else accentColor.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium,
                            textAlign = textAlign
                        )

                        // Progressive shimmering sweep ("переливание") when appearing / active
                        if (useAnimatedLyrics && (isActive || position >= subStart)) {
                            val rawProgress = if (subEnd > subStart) {
                                ((position - subStart).toFloat() / (subEnd - subStart).toFloat()).coerceIn(0f, 1f)
                            } else 1f

                            val feather = 0.06f
                            val shimmerWidth = 0.14f
                            val sweepBrush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.0f to accentColor,
                                    (rawProgress - shimmerWidth).coerceAtLeast(0f) to accentColor,
                                    (rawProgress - feather).coerceIn(0f, 1f) to Color.White.copy(alpha = 0.95f),
                                    rawProgress to Color.White,
                                    (rawProgress + feather).coerceIn(0f, 1f) to accentColor.copy(alpha = 0.85f),
                                    (rawProgress + shimmerWidth).coerceAtMost(1f) to Color.Transparent,
                                    1.0f to Color.Transparent
                                )
                            )

                            Text(
                                text = cleanSubPhraseText,
                                style = subStyle.copy(
                                    brush = sweepBrush,
                                    shadow = Shadow(
                                        color = accentColor.copy(alpha = 0.40f),
                                        offset = Offset.Zero,
                                        blurRadius = 10f
                                    )
                                ),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = textAlign
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricWordSpan(
    word: SyncedWord,
    isHighlighted: Boolean,
    isPast: Boolean = false,
    useAnimatedLyrics: Boolean = false,
    style: TextStyle,
    highlightedColor: Color,
    unhighlightedColor: Color,
    position: Long = 0L,
    wordStartTime: Long = 0L,
    wordEndTime: Long = 0L,
    isBackground: Boolean = false,
    performanceMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cleanText = remember(word.word) {
        LyricsUtils.cleanLyricBrackets(word.word.replace(Regex("""[\(\)\[\]（）【】〔〕《》\{\}]"""), "")).trim()
    }
    if (cleanText.isEmpty()) return

    val wordDuration = (wordEndTime - wordStartTime).coerceAtLeast(1L)
    val rawProgress = when {
        !useAnimatedLyrics -> if (isHighlighted || isPast) 1f else 0f
        isPast -> 1f
        !isHighlighted -> 0f
        position >= wordEndTime -> 1f
        position < wordStartTime -> 0f
        else -> ((position - wordStartTime).toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
    }

    // Apple Music Syllable Physics: word floats up and gently expands while sung
    val targetFloat = if (useAnimatedLyrics && isHighlighted) -5f else 0f
    val floatOffset by animateFloatAsState(
        targetValue = targetFloat,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "WordFloatOffset"
    )

    val targetScale = if (useAnimatedLyrics && isHighlighted) 1.05f else 1.0f
    val wordScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "WordScale"
    )

    // Dynamic glowing flare shadow
    val glowAlpha = if (useAnimatedLyrics && isHighlighted && !performanceMode) 0.50f else 0f
    val glowRadius = if (useAnimatedLyrics && isHighlighted && !performanceMode) 12f else 0f

    val textShadow = if (glowAlpha > 0f) {
        Shadow(
            color = highlightedColor.copy(alpha = glowAlpha),
            offset = Offset.Zero,
            blurRadius = glowRadius
        )
    } else null

    val targetAlpha = when {
        isHighlighted -> if (isBackground) 0.88f else 1f
        isPast -> if (useAnimatedLyrics) (if (isBackground) 0.75f else 0.95f) else 1f
        else -> if (useAnimatedLyrics) (if (isBackground) 0.35f else 0.45f) else 0.85f
    }

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "wordAlpha"
    )

    val density = LocalDensity.current
    val edgeWidthPx = remember(density) { with(density) { 8.dp.toPx() } }

    Box(
        modifier = modifier
            .padding(horizontal = 3.dp, vertical = 2.dp)
            .graphicsLayer {
                translationY = floatOffset * density.density
                scaleX = wordScale
                scaleY = wordScale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center
    ) {
        // Base unsung / unhighlighted text (generously padded so italic glyphs never clip)
        Text(
            text = cleanText,
            style = style,
            color = if (isPast && !useAnimatedLyrics) highlightedColor else unhighlightedColor,
            fontWeight = if (isHighlighted || isPast) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            softWrap = false
        )

        // Highlighted text swept with Apple Music soft-edge mask (exact real-time frame sync, zero latency)
        if (useAnimatedLyrics && (isHighlighted || isPast)) {
            Text(
                text = cleanText,
                style = style.copy(shadow = textShadow),
                color = highlightedColor.copy(alpha = if (isBackground) 0.88f else 1f),
                fontWeight = if (isHighlighted || isPast) FontWeight.Bold else FontWeight.SemiBold,
                softWrap = false,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .then(
                        if (isHighlighted && rawProgress < 1f) {
                            Modifier
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    if (performanceMode) {
                                        drawRect(
                                            color = Color.Black,
                                            size = androidx.compose.ui.geometry.Size((size.width * rawProgress).coerceAtLeast(0f), size.height),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    } else {
                                        val center = (size.width + edgeWidthPx * 2) * rawProgress - edgeWidthPx
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startX = center - edgeWidthPx,
                                                endX = center + edgeWidthPx,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                }
                        } else Modifier
                    )
            )
        }
    }
}

@Composable
fun PlainLyricsLine(
    line: String,
    style: TextStyle,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    modifier: Modifier = Modifier
) {
    val sanitizedLines = remember(line) { line.split("\n") }
    val primaryText = remember(sanitizedLines) { if (sanitizedLines.isNotEmpty()) sanitizeLyricLineText(sanitizedLines[0]) else "" }

    val isRomanizedScript = remember(primaryText) {
        MultiLangRomanizer.isScriptThatNeedsRomanization(primaryText)
    }

    val translationText = remember(sanitizedLines, primaryText, isRomanizedScript) {
        if (sanitizedLines.size > 1) {
            val firstExtra = sanitizedLines[1]
            val rest = if (sanitizedLines.size > 2) sanitizedLines.drop(2).joinToString("\n") { sanitizeLyricLineText(it) } else ""
            
            val isLatin = firstExtra.any { it.code in 32..126 } 
            val isFirstRomanization = isRomanizedScript && isLatin

            if (isFirstRomanization) rest else sanitizedLines.drop(1).joinToString("\n") { sanitizeLyricLineText(it) }
        } else ""
    }

    val romanizationText = remember(sanitizedLines, primaryText, isRomanizedScript) {
         if (sanitizedLines.size > 1) {
            val firstExtra = sanitizedLines[1]
            val isLatin = firstExtra.any { it.code in 32..126 } 
            val isFirstRomanization = isRomanizedScript && isLatin
            
            if (isFirstRomanization) sanitizeLyricLineText(firstExtra) else ""
        } else ""
    }
    val textAlign = when (lyricsAlignment) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val horizontalAlignment = when (lyricsAlignment) {
        "center" -> Alignment.CenterHorizontally
        "right" -> Alignment.End
        else -> Alignment.Start
    }

    val translationStyle = remember(style) {
        style.copy(
            fontSize = (style.fontSize.value * 0.75f).sp,
            fontWeight = FontWeight.Normal
        )
    }
    val translationColor = LocalContentColor.current.copy(alpha = 0.45f)

    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        if (primaryText.isNotBlank()) {
            Text(text = primaryText, style = style, color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = textAlign)

            if (showRomanization && romanizationText.isNotBlank()) {
                Text(
                    text = romanizationText,
                    style = translationStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && translationText.isNotBlank()) {
                Text(
                    text = translationText,
                    style = translationStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = if (showRomanization && romanizationText.isNotBlank()) 2.dp else 4.dp)
                )
            }
        }
    }
}

private val LeadingTagRegex = Regex("^v\\d+:\\s*", RegexOption.IGNORE_CASE)

internal fun sanitizeLyricLineText(raw: String): String =
    LyricsUtils.cleanLyricBrackets(LyricsUtils.stripLrcTimestamps(raw).replace(LeadingTagRegex, "")).trim()

internal fun sanitizeSyncedWords(words: List<SyncedWord>): List<SyncedWord> =
    buildList {
        words.forEachIndexed { index, word ->
            val sanitized = if (index == 0) LeadingTagRegex.replace(word.word, "") else word.word
            val normalized = LyricsUtils.cleanLyricBrackets(sanitized.replace(Regex("""[\(\)\[\]（）【】〔〕《》\{\}]"""), "")).trim()
            if (normalized.isEmpty()) return@forEachIndexed

            add(
                word.copy(
                    word = normalized,
                    startsNewWord = if (isEmpty()) true else word.startsNewWord
                )
            )
        }
    }

internal data class SyncedWordCluster(
    val startIndex: Int,
    val words: List<SyncedWord>
)

internal fun clusterSyncedWords(words: List<SyncedWord>): List<SyncedWordCluster> {
    if (words.isEmpty()) return emptyList()

    val clusters = mutableListOf<SyncedWordCluster>()
    var currentWords = mutableListOf<SyncedWord>()
    var currentStartIndex = 0

    words.forEachIndexed { index, word ->
        if (word.startsNewWord && currentWords.isNotEmpty()) {
            clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
            currentWords = mutableListOf()
            currentStartIndex = index
        } else if (currentWords.isEmpty()) {
            currentStartIndex = index
        }

        currentWords += word
    }

    if (currentWords.isNotEmpty()) {
        clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
    }

    return clusters
}

internal fun normalizeWordEndTime(
    currentWordTimeMs: Long,
    nextWordTimeMs: Long,
    lineEndTimeMs: Long
): Long {
    val minEnd = currentWordTimeMs + 1L
    val boundedLineEnd = lineEndTimeMs.coerceAtLeast(minEnd)
    return nextWordTimeMs.coerceIn(minEnd, boundedLineEnd)
}

internal fun resolveLineEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    val baseEnd = nextLineStartMs.toLong()
    val lastWordStart = line.words?.maxOfOrNull { it.time.toLong() } ?: line.time.toLong()
    return maxOf(baseEnd, lastWordStart + 1L)
}

internal fun resolveHighlightedWordIndex(
    words: List<SyncedWord>,
    positionMs: Long,
    lineStartTimeMs: Long,
    lineEndTimeMs: Long
): Int {
    if (positionMs < lineStartTimeMs || positionMs >= lineEndTimeMs) return -1
    return words.indexOfLast { it.time.toLong() <= positionMs }
}

internal fun resolveSeekPositionMs(
    lineTimeMs: Long,
    lyricsSyncOffsetMs: Int
): Long = (lineTimeMs - lyricsSyncOffsetMs.toLong()).coerceAtLeast(0L)

internal data class HighlightZoneMetrics(
    val topPadding: Dp,
    val bottomPadding: Dp,
    val zoneHeight: Dp,
    val centerFromTop: Dp
)

internal fun calculateHighlightMetrics(
    containerHeight: Dp,
    highlightZoneFraction: Float,
    highlightOffset: Dp
): HighlightZoneMetrics {
    val container = containerHeight.value
    val zoneHeight = (containerHeight * highlightZoneFraction).value.coerceAtLeast(0f)
    val offset = highlightOffset.value
    val minCenter = zoneHeight / 2f
    val maxCenter = (container - zoneHeight / 2f).coerceAtLeast(minCenter)
    val unclampedCenter = container / 2f - offset
    val center = unclampedCenter.coerceIn(minCenter, maxCenter)
    val topPadding = (center - zoneHeight / 2f).coerceAtLeast(0f)
    val bottomPadding = (container - center - zoneHeight / 2f).coerceAtLeast(0f)

    return HighlightZoneMetrics(
        topPadding = topPadding.dp,
        bottomPadding = bottomPadding.dp,
        zoneHeight = zoneHeight.dp,
        centerFromTop = center.dp
    )
}

internal fun highlightSnapOffsetPx(
    viewportHeight: Int,
    itemSize: Int,
    highlightOffsetPx: Float
): Int {
    if (viewportHeight <= 0 || itemSize <= 0) return 0
    if (itemSize >= viewportHeight) return 0
    val viewport = viewportHeight.toFloat()
    val halfItem = itemSize / 2f
    val targetCenter = (viewport / 2f) - highlightOffsetPx
    val clampedCenter = targetCenter.coerceIn(halfItem, viewport - halfItem)
    return (clampedCenter - halfItem).roundToInt()
}

internal suspend fun animateToSnapIndex(
    listState: LazyListState,
    layoutInfo: SnapperLayoutInfo,
    targetIndex: Int,
    animationSpec: AnimationSpec<Float>
) {
    val distance = layoutInfo.distanceToIndexSnap(targetIndex)
    if (distance == 0) return

    listState.scroll {
        var previous = 0f
        AnimationState(initialValue = 0f).animateTo(
            targetValue = distance.toFloat(),
            animationSpec = animationSpec
        ) {
            val delta = value - previous
            val consumed = scrollBy(delta)
            previous = value
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
    }
}

internal suspend fun snapToSnapIndex(
    listState: LazyListState,
    layoutInfo: SnapperLayoutInfo,
    targetIndex: Int
) {
    val distance = layoutInfo.distanceToIndexSnap(targetIndex)
    if (distance == 0) return

    listState.scroll {
        scrollBy(distance.toFloat())
    }
}

internal fun resolveCurrentLineIndex(
    lines: List<SyncedLine>,
    position: Long
): Int {
    if (lines.isEmpty()) return -1

    // Anticipate the upcoming line by 250ms so scroll starts smoothly gliding into position
    val targetPosition = position + 250L

    val activeIndex = lines.indexOfLast { line ->
        line.time.toLong() <= targetPosition
    }

    return if (activeIndex != -1) activeIndex else 0
}

@Composable
private fun LyricsTrackInfo(
    song: Song?,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    contentColor: Color,
    isPlaying: Boolean
) {
    if (song == null) return

    val albumShape = CircleShape

    // Helper state to stop rotation when paused, but we want it to pause in place?
    // Using infiniteTransition.animateFloat will reset on recomposition if spec changes or stops.
    // For a realistic vinyl pause, we need a manual Animatable that loops.
    // But for simplicity requested: "Animate the cover art to rotate... when music is playing".
    // If we just use conditional Modifier.graphicsLayer rotation, it might jump.
    // Let's use a simpler approach: if isPlaying, rotate.
    
    // Better approach for pausing rotation in place is non-trivial without a dedicated running time state.
    // Given the constraints, I will use a simple AnimatedVisibility or just let it reset, OR
    // use a monotonic clock if possible.
    // Let's stick to infinite transition for running, and maybe 0f for static?
    // Actually, user said "simulate a vinyl record". This implies continuous storage of rotation?
    // I'll try to implement continuous rotation.
    
    val currentRotation = remember { Animatable(0f) }
    
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Spin forever. 8s per revolution halves the effective per-second animation work
            // vs the original 4s cadence — visually still clearly a rotating "vinyl", but
            // drives fewer Compose invalidations during long listening sessions.
            while (true) {
                currentRotation.animateTo(
                    targetValue = currentRotation.value + 360f,
                    animationSpec = tween(8000, easing = LinearEasing)
                )
            }
        } else {
             currentRotation.stop()
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SmartImage(
            model = song.albumArtUriString ?: R.drawable.rounded_album_24,
            shape = albumShape,
            contentDescription = "Cover Art",
            modifier = Modifier
                .size(66.dp)
                .padding(6.dp)
                .graphicsLayer {
                    rotationZ = currentRotation.value % 360f
                }
                .clip(albumShape),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = false) // Allow shrinking if content is small
                .padding(vertical = 6.dp)
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    //textGeometricTransform = TextGeometricTransform(scaleX = (0.9f)),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = contentColor.copy(alpha = 0.7f),
                    //textGeometricTransform = TextGeometricTransform(scaleX = (0.9f)),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        PlayingEqIcon(
            modifier = Modifier
                .padding(start = 8.dp, end = 18.dp)
                .size(width = 18.dp, height = 16.dp),
            color = contentColor,
            isPlaying = isPlaying
        )
    }
}
