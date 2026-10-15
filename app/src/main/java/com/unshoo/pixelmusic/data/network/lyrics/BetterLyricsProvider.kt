package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.unshoo.pixelmusic.utils.TtmlLyricsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BetterLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "BetterLyricsProvider"
        private const val BASE_URL = "https://lyrics-api.boidu.dev/getLyrics"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Int = -1,
        album: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val qTitle = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val urlBuilder = StringBuilder("$BASE_URL?s=$qTitle&a=$qArtist")

            if (durationSeconds > 0) {
                urlBuilder.append("&d=$durationSeconds")
            }
            if (!album.isNullOrBlank()) {
                val qAlbum = URLEncoder.encode(album, "UTF-8")
                urlBuilder.append("&al=$qAlbum")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                val json = gson.fromJson(body, JsonObject::class.java) ?: return@withContext null

                val ttml = json.get("ttml")?.asString
                if (!ttml.isNullOrBlank()) {
                    val enhancedLrc = TtmlLyricsParser.parseToEnhancedLrc(ttml)
                    if (!enhancedLrc.isNullOrBlank()) {
                        Log.d(TAG, "Successfully parsed Apple Music TTML via BetterLyrics: $title - $artist")
                        return@withContext enhancedLrc
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "BetterLyrics fetch error: ${e.message}")
        }
        null
    }
}
