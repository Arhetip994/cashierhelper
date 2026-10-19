package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.importer.AccountImportSource
import com.unshoo.pixelmusic.data.importer.AccountPlaylist
import com.unshoo.pixelmusic.data.importer.AccountProfile
import com.unshoo.pixelmusic.data.importer.ParsedPlaylist
import com.unshoo.pixelmusic.data.importer.UniversalPlaylistImporter
import com.unshoo.pixelmusic.data.importer.deezer.DeezerImporterManager
import com.unshoo.pixelmusic.data.importer.spotify.SpotifyAccountManager
import com.unshoo.pixelmusic.data.importer.youtube.YouTubeMusicImporterManager
import com.unshoo.pixelmusic.data.lossless.constants.DeezerAccountNameKey
import com.unshoo.pixelmusic.data.lossless.constants.DeezerArlKey
import com.unshoo.pixelmusic.data.preferences.dataStore
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import javax.inject.Inject

enum class ImportTab {
    ACCOUNTS,
    LINK_OR_FILE
}

data class ImportSyncUiState(
    val currentTab: ImportTab = ImportTab.ACCOUNTS,
    val selectedService: AccountImportSource = AccountImportSource.SPOTIFY,
    val isSpotifyLoggedIn: Boolean = false,
    val spotifyProfile: AccountProfile? = null,
    val isDeezerLoggedIn: Boolean = false,
    val deezerProfile: AccountProfile? = null,
    val isYouTubeLoggedIn: Boolean = false,
    val youTubeProfile: AccountProfile? = null,
    val isLoadingPlaylists: Boolean = false,
    val playlists: List<AccountPlaylist> = emptyList(),
    val selectedPlaylistIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val currentImportingPlaylistTitle: String? = null,
    val currentTrackProgress: Int = 0,
    val totalTracksProgress: Int = 0,
    val completedPlaylistsCount: Int = 0,
    val totalPlaylistsCount: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastImportedPlaylistId: String? = null
)

@HiltViewModel
class ImportSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val spotifyAccountManager: SpotifyAccountManager,
    val deezerImporterManager: DeezerImporterManager,
    val youTubeMusicImporterManager: YouTubeMusicImporterManager,
    private val universalPlaylistImporter: UniversalPlaylistImporter
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportSyncUiState())
    val uiState: StateFlow<ImportSyncUiState> = _uiState.asStateFlow()

    init {
        // Collect Spotify auth state
        viewModelScope.launch {
            spotifyAccountManager.isLoggedInFlow.collect { loggedIn ->
                _uiState.update { it.copy(isSpotifyLoggedIn = loggedIn) }
                if (loggedIn && _uiState.value.selectedService == AccountImportSource.SPOTIFY) {
                    loadPlaylistsForService(AccountImportSource.SPOTIFY)
                }
            }
        }
        viewModelScope.launch {
            spotifyAccountManager.profileFlow.collect { profile ->
                _uiState.update { it.copy(spotifyProfile = profile) }
            }
        }

        // Collect Deezer auth state
        viewModelScope.launch {
            deezerImporterManager.isLoggedInFlow.collect { loggedIn ->
                _uiState.update { it.copy(isDeezerLoggedIn = loggedIn) }
                if (loggedIn && _uiState.value.selectedService == AccountImportSource.DEEZER) {
                    loadPlaylistsForService(AccountImportSource.DEEZER)
                }
            }
        }
        viewModelScope.launch {
            deezerImporterManager.profileFlow.collect { profile ->
                _uiState.update { it.copy(deezerProfile = profile) }
            }
        }

        // Check YouTube auth state
        val ytLoggedIn = youTubeMusicImporterManager.isLoggedIn()
        _uiState.update {
            it.copy(
                isYouTubeLoggedIn = ytLoggedIn,
                youTubeProfile = if (ytLoggedIn) AccountProfile("YouTube Music", null, AccountImportSource.YOUTUBE_MUSIC) else null
            )
        }

        // Load default service playlists
        loadPlaylistsForService(AccountImportSource.SPOTIFY)
    }

    fun selectTab(tab: ImportTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun selectService(source: AccountImportSource) {
        _uiState.update {
            it.copy(
                selectedService = source,
                selectedPlaylistIds = emptySet(),
                errorMessage = null
            )
        }
        loadPlaylistsForService(source)
    }

    fun refreshAuthStates() {
        val ytLoggedIn = youTubeMusicImporterManager.isLoggedIn()
        _uiState.update {
            it.copy(
                isYouTubeLoggedIn = ytLoggedIn,
                youTubeProfile = if (ytLoggedIn) AccountProfile("YouTube Music", null, AccountImportSource.YOUTUBE_MUSIC) else null
            )
        }
        loadPlaylistsForService(_uiState.value.selectedService)
    }

    fun loadPlaylistsForService(source: AccountImportSource) {
        viewModelScope.launch {
            val isLoggedIn = when (source) {
                AccountImportSource.SPOTIFY -> _uiState.value.isSpotifyLoggedIn
                AccountImportSource.DEEZER -> _uiState.value.isDeezerLoggedIn
                AccountImportSource.YOUTUBE_MUSIC -> youTubeMusicImporterManager.isLoggedIn()
            }

            if (!isLoggedIn) {
                _uiState.update {
                    it.copy(
                        playlists = emptyList(),
                        selectedPlaylistIds = emptySet(),
                        isLoadingPlaylists = false
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoadingPlaylists = true, errorMessage = null) }

            try {
                val list = when (source) {
                    AccountImportSource.SPOTIFY -> spotifyAccountManager.fetchUserPlaylists()
                    AccountImportSource.DEEZER -> deezerImporterManager.fetchUserPlaylists()
                    AccountImportSource.YOUTUBE_MUSIC -> youTubeMusicImporterManager.fetchUserPlaylists()
                }
                _uiState.update {
                    it.copy(
                        isLoadingPlaylists = false,
                        playlists = list.distinctBy { pl -> pl.id },
                        selectedPlaylistIds = emptySet()
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading playlists for %s", source.displayName)
                _uiState.update {
                    it.copy(
                        isLoadingPlaylists = false,
                        errorMessage = e.message ?: "Ошибка загрузки плейлистов"
                    )
                }
            }
        }
    }

    fun togglePlaylistSelection(playlistId: String) {
        _uiState.update { state ->
            val set = state.selectedPlaylistIds.toMutableSet()
            if (set.contains(playlistId)) {
                set.remove(playlistId)
            } else {
                set.add(playlistId)
            }
            state.copy(selectedPlaylistIds = set)
        }
    }

    fun selectAll(selectAll: Boolean) {
        _uiState.update { state ->
            val set = if (selectAll) {
                state.playlists.map { it.id }.toSet()
            } else {
                emptySet()
            }
            state.copy(selectedPlaylistIds = set)
        }
    }

    fun logout(source: AccountImportSource) {
        viewModelScope.launch {
            when (source) {
                AccountImportSource.SPOTIFY -> spotifyAccountManager.logout()
                AccountImportSource.DEEZER -> {
                    context.dataStore.edit { prefs ->
                        prefs.remove(DeezerArlKey)
                        prefs.remove(DeezerAccountNameKey)
                    }
                }
                AccountImportSource.YOUTUBE_MUSIC -> {
                    // YouTube logout
                }
            }
            loadPlaylistsForService(source)
        }
    }

    fun importSinglePlaylist(playlist: AccountPlaylist) {
        importPlaylists(listOf(playlist))
    }

    fun importSelectedPlaylists() {
        val selected = _uiState.value.playlists.filter { it.id in _uiState.value.selectedPlaylistIds }
        if (selected.isNotEmpty()) {
            importPlaylists(selected)
        }
    }

    private fun importPlaylists(playlistsToImport: List<AccountPlaylist>) {
        if (_uiState.value.isImporting || playlistsToImport.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    totalPlaylistsCount = playlistsToImport.size,
                    completedPlaylistsCount = 0,
                    errorMessage = null,
                    statusMessage = context.getString(R.string.import_status_fetching)
                )
            }

            var lastId: String? = null
            var successCount = 0

            for ((index, accountPlaylist) in playlistsToImport.withIndex()) {
                try {
                    _uiState.update {
                        it.copy(
                            currentImportingPlaylistTitle = accountPlaylist.title,
                            completedPlaylistsCount = index,
                            statusMessage = context.getString(R.string.import_status_progress, 0, accountPlaylist.trackCount)
                        )
                    }

                    // 1. Fetch tracks from service API
                    val parsed: ParsedPlaylist = when (accountPlaylist.source) {
                        AccountImportSource.SPOTIFY -> spotifyAccountManager.fetchPlaylistTracks(accountPlaylist)
                        AccountImportSource.DEEZER -> deezerImporterManager.fetchPlaylistTracks(accountPlaylist)
                        AccountImportSource.YOUTUBE_MUSIC -> youTubeMusicImporterManager.fetchPlaylistTracks(accountPlaylist)
                    }

                    _uiState.update {
                        it.copy(
                            totalTracksProgress = parsed.tracks.size,
                            currentTrackProgress = 0
                        )
                    }

                    // 2. Resolve tracks & save to Room Library
                    val playlistId = universalPlaylistImporter.importToLibrary(context, parsed) { curr, total ->
                        _uiState.update {
                            it.copy(
                                currentTrackProgress = curr,
                                totalTracksProgress = total,
                                statusMessage = context.getString(R.string.import_status_progress, curr, total)
                            )
                        }
                    }

                    lastId = playlistId
                    successCount++
                } catch (e: Exception) {
                    Timber.e(e, "Error importing playlist: %s", accountPlaylist.title)
                }
            }

            _uiState.update {
                it.copy(
                    isImporting = false,
                    completedPlaylistsCount = playlistsToImport.size,
                    currentImportingPlaylistTitle = null,
                    lastImportedPlaylistId = lastId,
                    selectedPlaylistIds = emptySet(),
                    statusMessage = context.getString(R.string.import_batch_completed, successCount)
                )
            }
        }
    }

    fun importExternalUrl(url: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isImporting = true, statusMessage = context.getString(R.string.import_status_fetching)) }
                val parsed = universalPlaylistImporter.fetch(url)
                val id = universalPlaylistImporter.importToLibrary(context, parsed) { curr, total ->
                    _uiState.update {
                        it.copy(
                            currentTrackProgress = curr,
                            totalTracksProgress = total,
                            statusMessage = context.getString(R.string.import_status_progress, curr, total)
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        lastImportedPlaylistId = id,
                        statusMessage = context.getString(R.string.import_status_success)
                    )
                }
                onSuccess(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, errorMessage = e.message) }
                onError(e.message ?: "Ошибка импорта")
            }
        }
    }

    fun importCsv(content: String, fallbackTitle: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isImporting = true, statusMessage = context.getString(R.string.import_status_reading_csv)) }
                val parsed = universalPlaylistImporter.parseCsv(content, fallbackTitle)
                val id = universalPlaylistImporter.importToLibrary(context, parsed) { curr, total ->
                    _uiState.update {
                        it.copy(
                            currentTrackProgress = curr,
                            totalTracksProgress = total,
                            statusMessage = context.getString(R.string.import_status_progress, curr, total)
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        lastImportedPlaylistId = id,
                        statusMessage = context.getString(R.string.import_status_success)
                    )
                }
                onSuccess(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, errorMessage = e.message) }
                onError(e.message ?: "Ошибка импорта")
            }
        }
    }
}
