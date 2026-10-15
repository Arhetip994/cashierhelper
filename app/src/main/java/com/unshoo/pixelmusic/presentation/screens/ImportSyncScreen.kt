@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.unshoo.pixelmusic.presentation.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.unshoo.pixelmusic.presentation.components.CollapsibleCommonTopBar
import com.unshoo.pixelmusic.presentation.components.MiniPlayerHeight
import com.unshoo.pixelmusic.presentation.navigation.Screen
import com.unshoo.pixelmusic.presentation.viewmodel.PlaylistViewModel
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.unshoo.pixelmusic.R
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt

data class ImportServiceItem(
    val name: String,
    val subtitle: String,
    val brandColor: Color,
    val iconVector: ImageVector,
    val placeholder: String
)

@Composable
fun ImportSyncScreen(
    navController: NavController,
    playlistViewModel: PlaylistViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lazyListState = rememberLazyListState()

    val importProgress by playlistViewModel.importProgress.collectAsStateWithLifecycle()

    var urlInput by rememberSaveable { mutableStateOf("") }
    var isImportingLocal by remember { mutableStateOf(false) }
    var lastImportedPlaylistId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.import_file_error), Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }
                val fallbackTitle = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?: context.getString(R.string.import_dialog_title)

                isImportingLocal = true
                statusMessage = context.getString(R.string.import_status_reading_csv)
                isError = false

                playlistViewModel.importCsvContent(
                    content = text,
                    fallbackTitle = fallbackTitle,
                    onProgress = { current, total ->
                        statusMessage = context.getString(R.string.import_status_progress, current, total)
                    },
                    onSuccess = { playlistId ->
                        isImportingLocal = false
                        lastImportedPlaylistId = playlistId
                        statusMessage = context.getString(R.string.import_status_success)
                        Toast.makeText(context, context.getString(R.string.import_finished_toast), Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        isImportingLocal = false
                        isError = true
                        statusMessage = err
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                )
            } catch (e: Exception) {
                isImportingLocal = false
                isError = true
                statusMessage = e.message ?: context.getString(R.string.import_file_error)
                Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    // TopBar Collapse calculation
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 180.dp
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(topBarHeight.value) {
        collapseFraction = 1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }
                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight
                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

    val detectedService = remember(urlInput) {
        val trimmed = urlInput.trim().lowercase()
        when {
            trimmed.contains("yandex") -> "Яндекс Музыка" to Color(0xFFFC3F1D)
            trimmed.contains("spotify") -> "Spotify" to Color(0xFF1DB954)
            trimmed.contains("music.apple.com") -> "Apple Music" to Color(0xFFFA2D48)
            trimmed.contains("deezer") -> "Deezer" to Color(0xFFA238FF)
            trimmed.contains("youtube.com") || trimmed.contains("youtu.be") -> "YouTube Music" to Color(0xFFFF0000)
            else -> null
        }
    }

    val services = remember(context) {
        listOf(
            ImportServiceItem(
                name = context.getString(R.string.import_yandex_music),
                subtitle = context.getString(R.string.import_yandex_subtitle),
                brandColor = Color(0xFFFC3F1D),
                iconVector = Icons.Rounded.MusicNote,
                placeholder = "https://music.yandex.ru/users/.../playlists/..."
            ),
            ImportServiceItem(
                name = context.getString(R.string.import_spotify),
                subtitle = context.getString(R.string.import_spotify_subtitle),
                brandColor = Color(0xFF1DB954),
                iconVector = Icons.Rounded.Link,
                placeholder = "https://open.spotify.com/playlist/..."
            ),
            ImportServiceItem(
                name = context.getString(R.string.import_apple_music),
                subtitle = context.getString(R.string.import_apple_subtitle),
                brandColor = Color(0xFFFA2D48),
                iconVector = Icons.Rounded.Link,
                placeholder = "https://music.apple.com/playlist/..."
            ),
            ImportServiceItem(
                name = context.getString(R.string.import_deezer),
                subtitle = context.getString(R.string.import_deezer_subtitle),
                brandColor = Color(0xFFA238FF),
                iconVector = Icons.Rounded.Link,
                placeholder = "https://www.deezer.com/playlist/..."
            ),
            ImportServiceItem(
                name = context.getString(R.string.import_youtube_music),
                subtitle = context.getString(R.string.import_youtube_subtitle),
                brandColor = Color(0xFFFF0000),
                iconVector = Icons.Rounded.Link,
                placeholder = "https://music.youtube.com/playlist?list=..."
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = currentTopBarHeightDp + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Banner
            item {
                ImportHeroCard()
            }

            // Live Progress / Status Card
            val isBusy = isImportingLocal || importProgress.isImporting
            if (isBusy || statusMessage != null) {
                item {
                    val defaultMatching = stringResource(R.string.import_status_matching)
                    val importedCountFormat = stringResource(R.string.import_status_progress_imported)
                    ImportStatusCard(
                        isBusy = isBusy,
                        progressText = if (isBusy) {
                            if (importProgress.totalTracks > 0) {
                                String.format(importedCountFormat, importProgress.currentTrackIndex, importProgress.totalTracks)
                            } else {
                                statusMessage ?: defaultMatching
                            }
                        } else {
                            statusMessage.orEmpty()
                        },
                        playlistName = importProgress.playlistName,
                        progressFraction = if (importProgress.totalTracks > 0) {
                            importProgress.currentTrackIndex.toFloat() / importProgress.totalTracks.toFloat()
                        } else 0f,
                        isError = isError,
                        lastPlaylistId = lastImportedPlaylistId,
                        onOpenPlaylist = { id ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(id))
                        }
                    )
                }
            }

            // Paste Link Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTL = 24.dp, smoothnessAsPercentTL = 60,
                        cornerRadiusTR = 24.dp, smoothnessAsPercentTR = 60,
                        cornerRadiusBL = 24.dp, smoothnessAsPercentBL = 60,
                        cornerRadiusBR = 24.dp, smoothnessAsPercentBR = 60
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.import_link_card_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GoogleSansRounded
                                )
                                Text(
                                    text = stringResource(R.string.import_link_card_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = { Text(stringResource(R.string.import_link_placeholder)) },
                            singleLine = true,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clip.isNullOrBlank()) {
                                            urlInput = clip.trim()
                                        }
                                    },
                                    enabled = !isBusy
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.import_paste_button))
                                }
                            }
                        )

                        // Service Detection Pill
                        if (detectedService != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(detectedService.second.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(detectedService.second)
                                )
                                Text(
                                    text = stringResource(R.string.import_detected_prefix, detectedService.first),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = detectedService.second
                                )
                            }
                        }

                        val fetchingInfoStatus = stringResource(R.string.import_status_fetching)
                        val trackProgressFormat = stringResource(R.string.import_status_progress)
                        val importSuccessToast = stringResource(R.string.import_status_success)
                        val importDoneToast = stringResource(R.string.import_finished_toast)

                        Button(
                            onClick = {
                                if (urlInput.isNotBlank()) {
                                    isImportingLocal = true
                                    isError = false
                                    statusMessage = fetchingInfoStatus
                                    lastImportedPlaylistId = null

                                    playlistViewModel.importExternalPlaylist(
                                        url = urlInput,
                                        onProgress = { current, total ->
                                            statusMessage = String.format(trackProgressFormat, current, total)
                                        },
                                        onSuccess = { playlistId ->
                                            isImportingLocal = false
                                            lastImportedPlaylistId = playlistId
                                            statusMessage = importSuccessToast
                                            Toast.makeText(context, importDoneToast, Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            isImportingLocal = false
                                            isError = true
                                            statusMessage = err
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            },
                            enabled = urlInput.isNotBlank() && !isBusy,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.import_btn_loading))
                            } else {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.import_btn_start), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // CSV / TuneMyMusic File Import Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTL = 24.dp, smoothnessAsPercentTL = 60,
                        cornerRadiusTR = 24.dp, smoothnessAsPercentTR = 60,
                        cornerRadiusBL = 24.dp, smoothnessAsPercentBL = 60,
                        cornerRadiusBR = 24.dp, smoothnessAsPercentBR = 60
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.import_csv_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GoogleSansRounded
                            )
                            Text(
                                text = stringResource(R.string.import_csv_card_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("text/comma-separated-values", "text/csv", "text/plain", "*/*")) },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.import_csv_pick_button))
                        }
                    }
                }
            }

            // Supported Platforms Section Header
            item {
                Text(
                    text = stringResource(R.string.import_supported_platforms_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // Platform Cards
            items(
                count = services.size,
                key = { services[it].name }
            ) { index ->
                val service = services[index]
                PlatformCard(
                    service = service,
                    onClick = {
                        urlInput = service.placeholder
                    }
                )
            }

            // Instructions / Tips
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.import_how_it_works_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.import_how_it_works_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.import_sync_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = onBackClick,
            expandedTitleStartPadding = 20.dp
        )
    }
}

@Composable
private fun ImportHeroCard() {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val gradient = remember(primary, secondary) {
        Brush.linearGradient(listOf(primary, secondary))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 28.dp, smoothnessAsPercentTL = 60,
            cornerRadiusTR = 28.dp, smoothnessAsPercentTR = 60,
            cornerRadiusBL = 28.dp, smoothnessAsPercentBL = 60,
            cornerRadiusBR = 28.dp, smoothnessAsPercentBR = 60
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(gradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.import_sync_hero_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GoogleSansRounded
                    )
                    Text(
                        text = stringResource(R.string.import_sync_hero_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = stringResource(R.string.import_sync_hero_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun ImportStatusCard(
    isBusy: Boolean,
    progressText: String,
    playlistName: String,
    progressFraction: Float,
    isError: Boolean,
    lastPlaylistId: String?,
    onOpenPlaylist: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                lastPlaylistId != null -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                } else if (isError) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                } else if (lastPlaylistId != null) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (playlistName.isNotBlank()) {
                        Text(
                            text = playlistName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (lastPlaylistId != null && !isBusy) {
                    Button(
                        onClick = { onOpenPlaylist(lastPlaylistId) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.import_open_button))
                    }
                }
            }

            if (isBusy) {
                LinearProgressIndicator(
                    progress = { if (progressFraction > 0f) progressFraction else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun PlatformCard(
    service: ImportServiceItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 20.dp, smoothnessAsPercentTL = 60,
            cornerRadiusTR = 20.dp, smoothnessAsPercentTR = 60,
            cornerRadiusBL = 20.dp, smoothnessAsPercentBL = 60,
            cornerRadiusBR = 20.dp, smoothnessAsPercentBR = 60
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(service.brandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(service.brandColor)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GoogleSansRounded
                )
                Text(
                    text = service.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Rounded.ContentPaste,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
