package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetEaseLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "NetEaseLyricsProvider"
        private const val SEARCH_URL = "https://music.163.com/api/cloudsearch/pc?type=1&limit=5&s="
        private const val LYRIC_URL = "https://music.163.com/api/song/lyric?lv=-1&kv=-1&tv=-1&id="
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }

    suspend fun getLyrics(title: String, artist: String, durationSeconds: Int = -1): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val searchRequest = Request.Builder()
                .url(SEARCH_URL + query)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val songId: String? = okHttpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonObject("result") ?: return@use null
                val songs = result.getAsJsonArray("songs") ?: return@use null
                if (songs.size() == 0) return@use null

                var bestScore = 0.0
                var bestId: String? = null

                for (i in 0 until songs.size()) {
                    val item = songs.get(i).asJsonObject
                    val id = item.get("id")?.asString ?: continue
                    val candTitle = item.get("name")?.asString.orEmpty()
                    val candArtist = item.getAsJsonArray("ar")?.mapNotNull {
                        it.asJsonObject.get("name")?.asString
                    }?.joinToString(", ").orEmpty()
                    val candDuration = ((item.get("dt")?.asLong ?: 0L) / 1000L).toInt()

                    val score = LyricsCandidateMatcher.scoreCandidate(
                        targetTitle = title,
                        targetArtist = artist,
                        targetDurationSec = durationSeconds,
                        candidateTitle = candTitle,
                        candidateArtist = candArtist,
                        candidateDurationSec = candDuration
                    )

                    if (score > bestScore && score >= 0.65) {
                        bestScore = score
                        bestId = id
                    }
                }
                bestId
            }

            if (songId.isNullOrBlank()) return@withContext null

            val lyricRequest = Request.Builder()
                .url(LYRIC_URL + songId)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(lyricRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val lrcObj = json.getAsJsonObject("lrc") ?: return@withContext null
                val lyricText = lrcObj.get("lyric")?.asString
                if (!lyricText.isNullOrBlank()) {
                    Log.d(TAG, "NetEase lyrics fetched successfully for $songId")
                    return@withContext lyricText
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetEase lyrics fetch error: ${e.message}")
        }
        null
    }
}
