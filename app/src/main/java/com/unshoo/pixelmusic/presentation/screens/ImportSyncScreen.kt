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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.importer.AccountImportSource
import com.unshoo.pixelmusic.data.importer.AccountPlaylist
import com.unshoo.pixelmusic.presentation.components.CollapsibleCommonTopBar
import com.unshoo.pixelmusic.presentation.components.MiniPlayerHeight
import com.unshoo.pixelmusic.presentation.navigation.Screen
import com.unshoo.pixelmusic.presentation.viewmodel.ImportSyncUiState
import com.unshoo.pixelmusic.presentation.viewmodel.ImportSyncViewModel
import com.unshoo.pixelmusic.presentation.viewmodel.ImportTab
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt

@Composable
fun ImportSyncScreen(
    navController: NavController,
    onBackClick: () -> Unit,
    viewModel: ImportSyncViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lazyListState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Refresh accounts on returning from login webviews
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAuthStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var urlInput by rememberSaveable { mutableStateOf("") }

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

                viewModel.importCsv(
                    content = text,
                    fallbackTitle = fallbackTitle,
                    onSuccess = { playlistId ->
                        Toast.makeText(context, context.getString(R.string.import_finished_toast), Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: context.getString(R.string.import_file_error), Toast.LENGTH_LONG).show()
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
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode Switcher Tab Row
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isAccounts = uiState.currentTab == ImportTab.ACCOUNTS
                        Surface(
                            onClick = { viewModel.selectTab(ImportTab.ACCOUNTS) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isAccounts) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapHoriz,
                                    contentDescription = null,
                                    tint = if (isAccounts) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.import_tab_accounts),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isAccounts) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isAccounts) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val isLink = uiState.currentTab == ImportTab.LINK_OR_FILE
                        Surface(
                            onClick = { viewModel.selectTab(ImportTab.LINK_OR_FILE) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isLink) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = if (isLink) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.import_tab_link),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isLink) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isLink) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Live Import Progress Banner (if importing or recently finished)
            if (uiState.isImporting || uiState.statusMessage != null || uiState.errorMessage != null) {
                item {
                    ImportProgressCard(
                        uiState = uiState,
                        onOpenPlaylist = { id ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(id))
                        }
                    )
                }
            }

            if (uiState.currentTab == ImportTab.ACCOUNTS) {
                // Service Selection Pills (Spotify, Deezer, YouTube Music)
                item {
                    ServiceSelectionSection(
                        uiState = uiState,
                        onSelectService = { source -> viewModel.selectService(source) }
                    )
                }

                val isCurrentLoggedIn = when (uiState.selectedService) {
                    AccountImportSource.SPOTIFY -> uiState.isSpotifyLoggedIn
                    AccountImportSource.DEEZER -> uiState.isDeezerLoggedIn
                    AccountImportSource.YOUTUBE_MUSIC -> uiState.isYouTubeLoggedIn
                }

                if (!isCurrentLoggedIn) {
                    // Connect Card for the selected service
                    item {
                        ServiceConnectCard(
                            source = uiState.selectedService,
                            onConnect = {
                                when (uiState.selectedService) {
                                    AccountImportSource.SPOTIFY -> navController.navigate(Screen.SpotifyLogin.route)
                                    AccountImportSource.DEEZER -> navController.navigate(Screen.DeezerLogin.route)
                                    AccountImportSource.YOUTUBE_MUSIC -> navController.navigate(Screen.YoutubeAuth.route)
                                }
                            }
                        )
                    }
                } else {
                    // User Profile / Connected Header
                    item {
                        ConnectedAccountHeader(
                            source = uiState.selectedService,
                            uiState = uiState,
                            onRefresh = { viewModel.loadPlaylistsForService(uiState.selectedService) },
                            onLogout = { viewModel.logout(uiState.selectedService) }
                        )
                    }

                    // Selection Control Bar
                    if (uiState.playlists.isNotEmpty()) {
                        item {
                            SelectionControlBar(
                                totalCount = uiState.playlists.size,
                                selectedCount = uiState.selectedPlaylistIds.size,
                                onSelectAll = { selectAll -> viewModel.selectAll(selectAll) }
                            )
                        }
                    }

                    // Playlists or Loading Indicator
                    if (uiState.isLoadingPlaylists) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (uiState.playlists.isEmpty()) {
                        item {
                            EmptyPlaylistsCard()
                        }
                    } else {
                        itemsIndexed(
                            items = uiState.playlists,
                            key = { index, playlist -> "${uiState.selectedService.id}_${playlist.id}_$index" }
                        ) { _, playlist ->
                            AccountPlaylistRow(
                                playlist = playlist,
                                isSelected = playlist.id in uiState.selectedPlaylistIds,
                                isBusy = uiState.isImporting,
                                onToggle = { viewModel.togglePlaylistSelection(playlist.id) },
                                onImportSingle = { viewModel.importSinglePlaylist(playlist) }
                            )
                        }
                    }
                }
            } else {
                // LINK OR FILE TAB (Legacy functionality preserved)
                item {
                    LinkImportSection(
                        urlInput = urlInput,
                        onUrlChange = { urlInput = it },
                        isBusy = uiState.isImporting,
                        onImport = { url ->
                            viewModel.importExternalUrl(
                                url = url,
                                onSuccess = { Toast.makeText(context, context.getString(R.string.import_finished_toast), Toast.LENGTH_SHORT).show() },
                                onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            )
                        }
                    )
                }

                item {
                    CsvImportSection(
                        isBusy = uiState.isImporting,
                        onPickFile = { filePicker.launch(arrayOf("text/comma-separated-values", "text/csv", "text/plain", "*/*")) }
                    )
                }

                item {
                    InstructionsSection()
                }
            }
        }

        // Floating Batch Import Button when items are selected in ACCOUNTS tab
        val selectedCount = uiState.selectedPlaylistIds.size
        AnimatedVisibility(
            visible = uiState.currentTab == ImportTab.ACCOUNTS && selectedCount > 0,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    start = 20.dp,
                    end = 20.dp
                )
        ) {
            Button(
                onClick = { viewModel.importSelectedPlaylists() },
                enabled = !uiState.isImporting,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(uiState.selectedService.brandColorHex)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.import_selected_button, selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
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
private fun ServiceSelectionSection(
    uiState: ImportSyncUiState,
    onSelectService: (AccountImportSource) -> Unit
) {
    val services = listOf(
        AccountImportSource.SPOTIFY to uiState.isSpotifyLoggedIn,
        AccountImportSource.DEEZER to uiState.isDeezerLoggedIn,
        AccountImportSource.YOUTUBE_MUSIC to uiState.isYouTubeLoggedIn
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        services.forEach { (source, isConnected) ->
            val isSelected = uiState.selectedService == source
            val brandColor = Color(source.brandColorHex)

            Surface(
                onClick = { onSelectService(source) },
                shape = RoundedCornerShape(18.dp),
                color = if (isSelected) brandColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, brandColor) else null,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(brandColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when (source) {
                            AccountImportSource.SPOTIFY -> Icons.Rounded.MusicNote
                            AccountImportSource.DEEZER -> Icons.Rounded.QueueMusic
                            AccountImportSource.YOUTUBE_MUSIC -> Icons.Rounded.QueueMusic
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = brandColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = source.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isConnected) brandColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) brandColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                            Text(
                                text = if (isConnected) "Подключен" else "Войти",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isConnected) brandColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceConnectCard(
    source: AccountImportSource,
    onConnect: () -> Unit
) {
    val brandColor = Color(source.brandColorHex)
    val gradient = Brush.linearGradient(listOf(brandColor, brandColor.copy(alpha = 0.6f)))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(26.dp, 60),
        colors = CardDefaults.cardColors(
            containerColor = brandColor.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(gradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Login,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = stringResource(R.string.import_connect_service_desc, source.displayName),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )

            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = brandColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.import_account_connect),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ConnectedAccountHeader(
    source: AccountImportSource,
    uiState: ImportSyncUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    val profile = when (source) {
        AccountImportSource.SPOTIFY -> uiState.spotifyProfile
        AccountImportSource.DEEZER -> uiState.deezerProfile
        AccountImportSource.YOUTUBE_MUSIC -> uiState.youTubeProfile
    }
    val brandColor = Color(source.brandColorHex)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (profile?.avatarUrl != null) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(brandColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = brandColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.username ?: "${source.displayName} Account",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GoogleSansRounded,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.import_connected_as, source.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.import_account_refresh))
            }

            IconButton(onClick = onLogout) {
                Icon(Icons.Rounded.Login, contentDescription = stringResource(R.string.import_account_disconnect), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SelectionControlBar(
    totalCount: Int,
    selectedCount: Int,
    onSelectAll: (Boolean) -> Unit
) {
    val isAllSelected = selectedCount == totalCount && totalCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onSelectAll(!isAllSelected) }
        ) {
            Checkbox(
                checked = isAllSelected,
                onCheckedChange = { onSelectAll(it) },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.import_select_all),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = "$selectedCount / $totalCount",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun AccountPlaylistRow(
    playlist: AccountPlaylist,
    isSelected: Boolean,
    isBusy: Boolean,
    onToggle: () -> Unit,
    onImportSingle: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "rowContainer"
    )

    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )

            // Artwork
            if (playlist.isLikedSongs) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFE91E63), Color(0xFF9C27B0))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else if (!playlist.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (playlist.ownerName != null) {
                        "${stringResource(R.string.import_tracks_count, playlist.trackCount)} • ${playlist.ownerName}"
                    } else {
                        stringResource(R.string.import_tracks_count, playlist.trackCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FilledTonalButton(
                onClick = onImportSingle,
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = stringResource(R.string.import_single_button),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyPlaylistsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.import_no_playlists_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImportProgressCard(
    uiState: ImportSyncUiState,
    onOpenPlaylist: (String) -> Unit
) {
    val isBusy = uiState.isImporting
    val isError = uiState.errorMessage != null
    val lastId = uiState.lastImportedPlaylistId

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                lastId != null && !isBusy -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
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
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else if (isError) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                } else if (lastId != null) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (!uiState.currentImportingPlaylistTitle.isNullOrBlank()) {
                        Text(
                            text = uiState.currentImportingPlaylistTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = uiState.errorMessage ?: uiState.statusMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (lastId != null && !isBusy) {
                    Button(
                        onClick = { onOpenPlaylist(lastId) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.import_open_button))
                    }
                }
            }

            if (isBusy && uiState.totalTracksProgress > 0) {
                val fraction = uiState.currentTrackProgress.toFloat() / uiState.totalTracksProgress.toFloat()
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
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
private fun LinkImportSection(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    isBusy: Boolean,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(24.dp, 60),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                onValueChange = onUrlChange,
                placeholder = { Text(stringResource(R.string.import_link_placeholder)) },
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) {
                                onUrlChange(clip.trim())
                            }
                        },
                        enabled = !isBusy
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.import_paste_button))
                    }
                }
            )

            Button(
                onClick = { onImport(urlInput) },
                enabled = urlInput.isNotBlank() && !isBusy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.import_btn_loading))
                } else {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_btn_start), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CsvImportSection(
    isBusy: Boolean,
    onPickFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(24.dp, 60),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                onClick = onPickFile,
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

@Composable
private fun InstructionsSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
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
