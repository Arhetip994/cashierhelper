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
class PaxsenixLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "PaxsenixLyricsProvider"
        private const val APPLE_SEARCH_URL_RU = "https://itunes.apple.com/search?entity=song&limit=5&country=ru&term="
        private const val APPLE_SEARCH_URL_US = "https://itunes.apple.com/search?entity=song&limit=5&country=us&term="
        private const val PAXSENIX_FREE_URL = "https://lyrics.paxsenix.org/apple-music/lyrics?id="
        private const val PAXSENIX_API_URL = "https://api.paxsenix.org/lyrics/applemusic?id="
        private const val PAXSENIX_MUSIXMATCH_URL = "https://api.paxsenix.org/lyrics/musixmatch"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }

    suspend fun getAppleMusicLyrics(title: String, artist: String, apiKey: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            var trackId = searchAppleMusic(APPLE_SEARCH_URL_RU + query)
            if (trackId == null) {
                trackId = searchAppleMusic(APPLE_SEARCH_URL_US + query)
            }
            if (trackId == null) return@withContext null

            // 1. Try free Paxsenix endpoint (no key required)
            val freeResult = fetchPaxsenixById(PAXSENIX_FREE_URL + trackId, key = null)
            if (!freeResult.isNullOrBlank()) {
                Log.d(TAG, "Apple Music lyrics fetched via Paxsenix free: $trackId")
                return@withContext freeResult
            }

            // 2. If API key is configured, try api.paxsenix.org
            if (!apiKey.isNullOrBlank()) {
                val apiResult = fetchPaxsenixById(PAXSENIX_API_URL + trackId, key = apiKey)
                if (!apiResult.isNullOrBlank()) {
                    Log.d(TAG, "Apple Music lyrics fetched via Paxsenix API: $trackId")
                    return@withContext apiResult
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Paxsenix Apple Music fetch error: ${e.message}")
        }
        null
    }

    suspend fun getMusixmatchLyrics(title: String, artist: String, durationSeconds: Int = 180, apiKey: String? = null): String? = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext null
        try {
            val qTitle = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "$PAXSENIX_MUSIXMATCH_URL?t=$qTitle&a=$qArtist&d=$durationSeconds"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", apiKey)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val lrc = json.get("lrc")?.asString ?: json.get("lyrics")?.asString
                if (!lrc.isNullOrBlank()) return@withContext lrc
            }
        } catch (e: Exception) {
            Log.w(TAG, "Paxsenix Musixmatch fetch error: ${e.message}")
        }
        null
    }

    private fun searchAppleMusic(url: String): String? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)
                val results = json.getAsJsonArray("results") ?: return null
                if (results.size() > 0) {
                    val first = results.get(0).asJsonObject
                    return first.get("trackId")?.asString
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Apple Music search failed: ${e.message}")
        }
        return null
    }

    private fun fetchPaxsenixById(url: String, key: String?): String? {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
            if (!key.isNullOrBlank()) {
                builder.header("Authorization", key)
            }
            val request = builder.get().build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)
                val lrc = json.get("lrc")?.asString ?: json.get("lyrics")?.asString
                if (!lrc.isNullOrBlank()) return lrc
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPaxsenixById failed: ${e.message}")
        }
        return null
    }
}
