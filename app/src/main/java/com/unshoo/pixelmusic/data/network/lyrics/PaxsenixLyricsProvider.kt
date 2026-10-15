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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaxsenixLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "PaxsenixLyricsProvider"
        private const val PAXSENIX_SEARCH_URL = "https://lyrics.paxsenix.org/apple-music/search?q="
        private const val APPLE_SEARCH_URL_RU = "https://itunes.apple.com/search?entity=song&limit=5&country=ru&term="
        private const val APPLE_SEARCH_URL_US = "https://itunes.apple.com/search?entity=song&limit=5&country=us&term="
        private const val PAXSENIX_FREE_URL = "https://lyrics.paxsenix.org/apple-music/lyrics?id="
        private const val PAXSENIX_API_URL = "https://api.paxsenix.org/lyrics/applemusic?id="
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }

    suspend fun getAppleMusicLyrics(title: String, artist: String, apiKey: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")

            // 1. First search via Paxsenix Apple Music search API
            var trackId = searchPaxsenix(PAXSENIX_SEARCH_URL + query)

            // 2. Fallback to iTunes search if needed
            if (trackId == null) {
                trackId = searchAppleMusic(APPLE_SEARCH_URL_RU + query)
            }
            if (trackId == null) {
                trackId = searchAppleMusic(APPLE_SEARCH_URL_US + query)
            }
            if (trackId == null) return@withContext null

            // 3. Try free Paxsenix endpoint (no key required)
            val freeResult = fetchPaxsenixById(PAXSENIX_FREE_URL + trackId, key = null)
            if (!freeResult.isNullOrBlank()) {
                Log.d(TAG, "Apple Music lyrics fetched via Paxsenix free: $trackId")
                return@withContext freeResult
            }

            // 4. If API key is configured, try api.paxsenix.org
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

    private fun searchPaxsenix(url: String): String? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java) ?: return null
                if (jsonArray.size() > 0) {
                    val first = jsonArray.get(0).asJsonObject
                    return first.get("id")?.asString
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Paxsenix direct search failed: ${e.message}")
        }
        return null
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
                val body = response.body.string()
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
                val body = response.body.string()
                val json = gson.fromJson(body, JsonObject::class.java) ?: return null

                // 1. TTML Content (Apple Music Timed Text XML with word-level sync)
                val ttml = json.get("ttmlContent")?.asString
                if (!ttml.isNullOrBlank()) {
                    val converted = TtmlLyricsParser.parseToEnhancedLrc(ttml)
                    if (!converted.isNullOrBlank()) {
                        Log.d(TAG, "Converted ttmlContent to enhanced LRC")
                        return converted
                    }
                }

                // 2. Pre-formatted ELRC fields
                val elrcMulti = json.get("elrcMultiPerson")?.asString
                if (!elrcMulti.isNullOrBlank()) return elrcMulti

                val elrc = json.get("elrc")?.asString
                if (!elrc.isNullOrBlank()) return elrc

                // 3. Syllable/word-level content array
                val contentArray = json.getAsJsonArray("content")
                val lyricsType = json.get("type")?.asString ?: ""
                val isSyllable = lyricsType.equals("Syllable", ignoreCase = true)

                if (contentArray != null && contentArray.size() > 0) {
                    val lrcBuilder = StringBuilder()
                    for (i in 0 until contentArray.size()) {
                        val lineObj = contentArray.get(i).asJsonObject
                        val timeMs = lineObj.get("timestamp")?.asLong ?: 0L
                        val minutes = timeMs / 60000
                        val seconds = (timeMs % 60000) / 1000
                        val hundredths = (timeMs % 1000) / 10
                        val lineTimestamp = String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)

                        val textArray = lineObj.getAsJsonArray("text")
                        if (isSyllable && textArray != null && textArray.size() > 0) {
                            val lineWords = StringBuilder()
                            for (j in 0 until textArray.size()) {
                                val wordObj = textArray.get(j).asJsonObject
                                val wordText = wordObj.get("text")?.asString ?: ""
                                val wordTimeMs = wordObj.get("timestamp")?.asLong ?: timeMs
                                val wMin = wordTimeMs / 60000
                                val wSec = (wordTimeMs % 60000) / 1000
                                val wHun = (wordTimeMs % 1000) / 10
                                val wordTag = String.format(Locale.US, "<%02d:%02d.%02d>", wMin, wSec, wHun)

                                if (lineWords.isNotEmpty() && !wordText.startsWith(" ")) {
                                    lineWords.append(" ")
                                }
                                lineWords.append(wordTag).append(wordText)
                            }
                            lrcBuilder.append(lineTimestamp).append(lineWords).append("\n")
                        } else {
                            val plainText = lineObj.get("text")?.let {
                                if (it.isJsonArray) it.asJsonArray.joinToString("") { elem ->
                                    elem.asJsonObject.get("text")?.asString ?: ""
                                } else it.asString
                            } ?: ""
                            if (plainText.isNotBlank()) {
                                lrcBuilder.append(lineTimestamp).append(plainText).append("\n")
                            }
                        }
                    }
                    val resultLrc = lrcBuilder.toString().trim()
                    if (resultLrc.isNotBlank()) return resultLrc
                }

                // 4. Plain text fallback
                val plain = json.get("plain")?.asString
                if (!plain.isNullOrBlank()) return plain

                // 5. Generic lrc/lyrics key fallback
                val lrc = json.get("lrc")?.asString ?: json.get("lyrics")?.asString
                if (!lrc.isNullOrBlank()) return lrc
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPaxsenixById failed: ${e.message}")
        }
        return null
    }
}
