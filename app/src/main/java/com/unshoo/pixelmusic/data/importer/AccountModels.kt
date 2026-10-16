package com.unshoo.pixelmusic.data.importer

enum class AccountImportSource(
    val id: String,
    val displayName: String,
    val brandColorHex: Long
) {
    SPOTIFY("spotify", "Spotify", 0xFF1DB954),
    DEEZER("deezer", "Deezer", 0xFFA238FF),
    YOUTUBE_MUSIC("youtube", "YouTube Music", 0xFFFF0000)
}

data class AccountPlaylist(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val source: AccountImportSource,
    val isLikedSongs: Boolean = false,
    val ownerName: String? = null
)

data class AccountProfile(
    val username: String,
    val avatarUrl: String? = null,
    val source: AccountImportSource
)
