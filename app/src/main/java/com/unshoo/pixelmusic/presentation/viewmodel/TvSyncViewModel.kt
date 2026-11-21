package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.tv.TvSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TvSyncViewModel @Inject constructor(
    val tvSyncManager: TvSyncManager
) : ViewModel() {

    fun sendSong(song: Song, action: String = "play", onResult: ((Result<String>) -> Unit)? = null) {
        tvSyncManager.sendSong(song, action, onResult)
    }

    fun pair(code: String): Result<String> {
        return tvSyncManager.pair(code)
    }

    fun unpair() {
        tvSyncManager.unpair()
    }
}
