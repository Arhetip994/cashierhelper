package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeezerLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "DeezerLyricsProvider"
        private const val SEARCH_URL = "https://api.deezer.com/search?limit=5&q="
        private const val PASS_URL = "https://auth.deezer.com/login/anonymous?jo=p&rto=c&i=c"
        private const val PIPE_URL = "https://pipe.deezer.com/api"
        private const val LYRIC_QUERY = "query TrackLyrics(\$trackId: String!) { track(trackId: \$trackId) { lyrics { synchronizedLines { lrcTimestamp line } } } }"
    }

    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpiryTime: Long = 0L

    private suspend fun getAnonymousToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiryTime) {
            return@withContext cachedToken
        }
        try {
            val request = Request.Builder()
                .url(PASS_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val jwt = json.get("jwt")?.asString
                if (!jwt.isNullOrBlank()) {
                    cachedToken = jwt
                    tokenExpiryTime = now + 30 * 60 * 1000L // 30 minutes
                    return@withContext jwt
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Deezer anonymous token: ${e.message}")
        }
        null
    }

    suspend fun getLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val jwt = getAnonymousToken() ?: return@withContext null
        try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val searchRequest = Request.Builder()
                .url(SEARCH_URL + query)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                .get()
                .build()

            val trackId: String? = okHttpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonArray("data") ?: return@use null
                if (data.size() == 0) return@use null
                data.get(0).asJsonObject.get("id")?.asString
            }

            if (trackId.isNullOrBlank()) return@withContext null

            val variables = JsonObject().apply {
                addProperty("trackId", trackId)
            }
            val requestPayload = JsonObject().apply {
                addProperty("operationName", "TrackLyrics")
                add("variables", variables)
                addProperty("query", LYRIC_QUERY)
            }

            val mediaType = "application/json".toMediaType()
            val requestBody = requestPayload.toString().toRequestBody(mediaType)
            val pipeRequest = Request.Builder()
                .url(PIPE_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                .header("Authorization", "Bearer $jwt")
                .post(requestBody)
                .build()

            okHttpClient.newCall(pipeRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val dataObj = json.getAsJsonObject("data") ?: return@withContext null
                val trackObj = dataObj.getAsJsonObject("track") ?: return@withContext null
                val lyricsObj = trackObj.getAsJsonObject("lyrics") ?: return@withContext null
                val syncLines = lyricsObj.getAsJsonArray("synchronizedLines") ?: return@withContext null

                if (syncLines.size() == 0) return@withContext null

                val sb = StringBuilder()
                for (i in 0 until syncLines.size()) {
                    val lineObj = syncLines.get(i).asJsonObject
                    val timestamp = lineObj.get("lrcTimestamp")?.asString ?: continue
                    val text = lineObj.get("line")?.asString ?: ""
                    sb.append(timestamp).append(text).append("\n")
                }
                val result = sb.toString().trim()
                if (result.isNotEmpty()) {
                    Log.d(TAG, "Deezer lyrics successfully fetched (${syncLines.size()} lines)")
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deezer fetch error: ${e.message}")
        }
        null
    }
}
