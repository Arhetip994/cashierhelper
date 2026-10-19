package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.utils.LyricsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LrcRedLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "LrcRedLyricsProvider"
        private const val BASE_URL = "https://lrc.red/api"
        private const val USER_AGENT = "PixelMusic/1.6.0 (Android)"
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Int = -1
    ): String? = withContext(Dispatchers.IO) {
        try {
            val qTitle = URLEncoder.encode(title.trim(), "UTF-8")
            val qArtist = URLEncoder.encode(artist.trim(), "UTF-8")

            // 1. Try exact lookup
            val lookupUrl = "$BASE_URL/lyrics?track_name=$qTitle&artist_name=$qArtist" +
                if (durationSeconds > 0) "&duration=$durationSeconds" else ""

            val request = Request.Builder()
                .url(lookupUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                    if (json != null) {
                        val synced = json.get("syncedLyrics")?.asString
                            ?: json.get("lyrics")?.asString
                        if (!synced.isNullOrBlank()) {
                            Log.d(TAG, "Found lyrics on LRC.red exact lookup for $title - $artist")
                            return@withContext synced
                        }
                    }
                }
            }

            // 2. Fallback to search query
            val searchUrl = "$BASE_URL/search?q=${URLEncoder.encode("$artist $title", "UTF-8")}"
            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val array = runCatching { gson.fromJson(body, JsonArray::class.java) }.getOrNull()
                    if (array != null && array.size() > 0) {
                        for (item in array) {
                            val obj = item.asJsonObject
                            val synced = obj.get("syncedLyrics")?.asString ?: obj.get("lyrics")?.asString
                            if (!synced.isNullOrBlank()) {
                                Log.d(TAG, "Found lyrics on LRC.red search for $title - $artist")
                                return@withContext synced
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRC.red lyrics fetch failed: ${e.message}")
        }
        null
    }
}
