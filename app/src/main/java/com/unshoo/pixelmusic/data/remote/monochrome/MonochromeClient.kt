package com.unshoo.pixelmusic.data.remote.monochrome

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.unshoo.pixelmusic.data.model.ArtistRef
import com.unshoo.pixelmusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonochromeClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "MonochromeClient"
        const val BASE_URL = "https://tracks.monochrome.st"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    private data class SearchResponse(
        @SerializedName("tracks") val tracks: List<TrackItem>? = null
    )

    private data class TrackItem(
        @SerializedName("id") val id: String? = null,
        @SerializedName("trackId") val trackId: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("artistNames") val artistNames: List<String>? = null,
        @SerializedName("releaseTitle") val releaseTitle: String? = null,
        @SerializedName("albumTitle") val albumTitle: String? = null,
        @SerializedName("artwork") val artwork: String? = null,
        @SerializedName("duration") val duration: Long? = null,
        @SerializedName("playable") val playable: Boolean? = true,
        @SerializedName("isrc") val isrc: String? = null
    )

    suspend fun searchTracks(query: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$BASE_URL/search/tracks?q=$encodedQuery&limit=$limit"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Monochrome search HTTP ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body.string()
                val parsed = gson.fromJson(body, SearchResponse::class.java)
                val tracks = parsed?.tracks.orEmpty()

                tracks.mapNotNull { item ->
                    val resolvedId = item.trackId ?: item.id ?: return@mapNotNull null
                    if (item.playable == false) return@mapNotNull null
                    val title = item.title?.trim().takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
                    val artistsList = item.artistNames.orEmpty().filter { it.isNotBlank() }
                    val artistName = artistsList.joinToString(", ").ifBlank { "Unknown Artist" }
                    val albumName = item.albumTitle ?: item.releaseTitle ?: "Unknown Album"
                    val durationMs = when {
                        item.duration == null -> 0L
                        item.duration > 10_000L -> item.duration
                        else -> item.duration * 1000L
                    }

                    val artistRefs = artistsList.mapIndexed { idx, name ->
                        ArtistRef(
                            name = name,
                            id = name.hashCode().toLong(),
                            isPrimary = idx == 0
                        )
                    }

                    Song(
                        id = "monochrome_$resolvedId",
                        title = title,
                        artist = artistName,
                        artistId = artistName.hashCode().toLong(),
                        artists = artistRefs,
                        album = albumName,
                        albumId = albumName.hashCode().toLong(),
                        albumArtist = artistsList.firstOrNull(),
                        path = "$BASE_URL/track/$resolvedId",
                        contentUriString = "monochrome://$resolvedId",
                        albumArtUriString = item.artwork,
                        duration = durationMs,
                        mimeType = "audio/flac",
                        bitrate = 1411,
                        sampleRate = 44100,
                        dateAdded = System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed searching Monochrome: ${e.message}", e)
            emptyList()
        }
    }

    fun getStreamUrl(trackId: String): String {
        return "$BASE_URL/track/$trackId"
    }
}
