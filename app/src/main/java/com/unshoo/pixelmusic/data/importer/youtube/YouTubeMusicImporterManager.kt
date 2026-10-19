package com.unshoo.pixelmusic.data.importer.youtube

import com.unshoo.pixelmusic.data.importer.AccountImportSource
import com.unshoo.pixelmusic.data.importer.AccountPlaylist
import com.unshoo.pixelmusic.data.importer.AccountProfile
import com.unshoo.pixelmusic.data.importer.ImportedTrack
import com.unshoo.pixelmusic.data.importer.ParsedPlaylist
import com.unshoo.pixelmusic.data.remote.youtube.YouTubeItemFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMusicImporterManager @Inject constructor() {

    fun isLoggedIn(): Boolean = YouTube.hasLoginCookie()

    suspend fun fetchProfile(): AccountProfile? = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) return@withContext null
        AccountProfile(
            username = "YouTube Music Account",
            avatarUrl = null,
            source = AccountImportSource.YOUTUBE_MUSIC
        )
    }

    suspend fun fetchUserPlaylists(): List<AccountPlaylist> = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) return@withContext emptyList()
        val result = mutableListOf<AccountPlaylist>()

        // 1. Liked Music / Понравившиеся треки
        try {
            val lmPage = YouTube.playlist("LM").getOrNull()
            val songCount = lmPage?.songs?.size ?: 0
            if (lmPage != null) {
                result.add(
                    AccountPlaylist(
                        id = "LM",
                        title = "Понравившиеся треки (Liked Music)",
                        coverUrl = lmPage.playlist?.thumbnail,
                        trackCount = songCount,
                        source = AccountImportSource.YOUTUBE_MUSIC,
                        isLikedSongs = true,
                        ownerName = "Вы"
                    )
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Error checking YouTube Music LM playlist")
        }

        // 2. User's saved & created playlists
        try {
            val firstPage = YouTube.library("FEmusic_liked_playlists").getOrNull()
            if (firstPage != null) {
                val playlists = firstPage.items.filterIsInstance<PlaylistItem>().filter {
                    YouTubeItemFilter.isMusicPlaylist(it.title, it.id) && it.id != "LM" && it.id != "VLLM"
                }

                for (pl in playlists) {
                    val trackCount = pl.songCountText?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                    result.add(
                        AccountPlaylist(
                            id = pl.id,
                            title = pl.title,
                            coverUrl = pl.thumbnail,
                            trackCount = trackCount,
                            source = AccountImportSource.YOUTUBE_MUSIC,
                            isLikedSongs = false,
                            ownerName = pl.author?.name
                        )
                    )
                }

                var continuation = firstPage.continuation
                var pages = 0
                while (continuation != null && pages < 10) {
                    val next = YouTube.libraryContinuation(continuation).getOrNull() ?: break
                    val newItems = next.items.filterIsInstance<PlaylistItem>().filter {
                        YouTubeItemFilter.isMusicPlaylist(it.title, it.id) && it.id != "LM" && it.id != "VLLM"
                    }
                    for (pl in newItems) {
                        val trackCount = pl.songCountText?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                        result.add(
                            AccountPlaylist(
                                id = pl.id,
                                title = pl.title,
                                coverUrl = pl.thumbnail,
                                trackCount = trackCount,
                                source = AccountImportSource.YOUTUBE_MUSIC,
                                isLikedSongs = false,
                                ownerName = pl.author?.name
                            )
                        )
                    }
                    continuation = next.continuation
                    pages++
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching YouTube Music user playlists")
        }

        result.distinctBy { it.id }
    }

    suspend fun fetchPlaylistTracks(playlist: AccountPlaylist): ParsedPlaylist = withContext(Dispatchers.IO) {
        val page = YouTube.playlist(playlist.id).getOrThrow()
        val allSongs = mutableListOf<unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem>()
        allSongs.addAll(page.songs)

        var continuation = page.songsContinuation
        var pages = 0
        while (continuation != null && pages < 20) {
            val next = YouTube.playlistContinuation(continuation).getOrNull() ?: break
            allSongs.addAll(next.songs)
            continuation = next.continuation
            pages++
        }

        val tracks = allSongs.map { songItem ->
            ImportedTrack(
                title = songItem.title,
                artist = songItem.artists.joinToString(", ") { it.name },
                album = songItem.album?.name,
                durationMs = songItem.duration?.toLong()?.times(1000L),
                videoId = songItem.id,
                coverUrl = songItem.thumbnail
            )
        }

        ParsedPlaylist(
            title = playlist.title,
            coverUrl = playlist.coverUrl ?: page.playlist?.thumbnail,
            tracks = tracks,
            source = "YouTube Music"
        )
    }
}
