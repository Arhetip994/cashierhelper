package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.network.youtube.InnerTubeParser
import com.unshoo.pixelmusic.data.network.youtube.YouTubeTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    val innerTubeParser: InnerTubeParser
) : ViewModel() {

    private val _onlineResults = MutableStateFlow<List<YouTubeTrack>>(emptyList())
    val onlineResults: StateFlow<List<YouTubeTrack>> = _onlineResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _onlineResults.value = emptyList()
            _isLoading.value = false
            return
        }

        searchJob = viewModelScope.launch {
            _isLoading.value = true
            delay(350)
            try {
                val results = innerTubeParser.searchSongs(trimmed)
                _onlineResults.value = results
            } catch (e: Exception) {
                Timber.e(e, "Error searching online songs")
                _onlineResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toSong(track: YouTubeTrack): Song {
        return innerTubeParser.toSong(track)
    }

    suspend fun resolvePlayable(track: YouTubeTrack): Song {
        _loadingTrackId.value = track.videoId
        return try {
            innerTubeParser.resolvePlayableSong(track) ?: innerTubeParser.toSong(track)
        } finally {
            _loadingTrackId.value = null
        }
    }
}
