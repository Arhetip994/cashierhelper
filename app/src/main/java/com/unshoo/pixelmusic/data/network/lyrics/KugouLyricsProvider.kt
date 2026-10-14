package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Base64
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
class KugouLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "KugouLyricsProvider"
        private const val SEARCH_URL = "https://songsearch.kugou.com/song_search_v2?page=1&pagesize=5&platform=WebFilter&format=json&keyword="
        private const val CANDIDATE_URL = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash="
        private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id="
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }

    suspend fun getLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val searchRequest = Request.Builder()
                .url(SEARCH_URL + query)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val fileHash: String? = okHttpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonObject("data") ?: return@use null
                val lists = data.getAsJsonArray("lists") ?: return@use null
                if (lists.size() == 0) return@use null
                lists.get(0).asJsonObject.get("FileHash")?.asString
            }

            if (fileHash.isNullOrBlank()) return@withContext null

            val candRequest = Request.Builder()
                .url(CANDIDATE_URL + fileHash)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val candidatePair: Pair<String, String>? = okHttpClient.newCall(candRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val candidates = json.getAsJsonArray("candidates") ?: return@use null
                if (candidates.size() == 0) return@use null
                val first = candidates.get(0).asJsonObject
                val id = first.get("id")?.asString ?: return@use null
                val accessKey = first.get("accesskey")?.asString ?: return@use null
                Pair(id, accessKey)
            }

            if (candidatePair == null) return@withContext null

            val downloadUrl = "$DOWNLOAD_URL${URLEncoder.encode(candidatePair.first, "UTF-8")}&accesskey=${URLEncoder.encode(candidatePair.second, "UTF-8")}"
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val base64Content = json.get("content")?.asString ?: return@withContext null
                if (base64Content.isNotEmpty()) {
                    val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
                    val lrcString = String(decodedBytes, Charsets.UTF_8).trimStart('\uFEFF')
                    if (lrcString.isNotEmpty()) {
                        Log.d(TAG, "Kugou lyrics fetched successfully (${lrcString.length} chars)")
                        return@withContext lrcString
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kugou lyrics fetch error: ${e.message}")
        }
        null
    }
}
