package com.unshoo.pixelmusic.data.network.lyrics

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.data.model.SubPhrase
import com.unshoo.pixelmusic.data.model.SyncedLine
import com.unshoo.pixelmusic.data.model.SyncedWord
import com.unshoo.pixelmusic.data.preferences.dataStore
import com.unshoo.pixelmusic.utils.LyricsUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusixmatchLyricsProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "MusixmatchLyricsProvider"
        private const val BASE_URL = "https://apic-desktop.musixmatch.com/ws/1.1"
        private const val APP_ID = "web-desktop-app-v1.0"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private const val COOKIE_HEADER = "awselb=unknown"
        private val KEY_MUSIXMATCH_TOKEN = stringPreferencesKey("musixmatch_user_token")
    }

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null

    private suspend fun getUserToken(): String? = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            if (!cachedToken.isNullOrBlank()) {
                return@withLock cachedToken
            }

            // Check DataStore
            val savedToken = context.dataStore.data.map { it[KEY_MUSIXMATCH_TOKEN] }.firstOrNull()
            if (!savedToken.isNullOrBlank()) {
                cachedToken = savedToken
                return@withLock savedToken
            }

            // Request fresh token from API
            try {
                val url = "$BASE_URL/token.get?app_id=$APP_ID"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", COOKIE_HEADER)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Failed to get Musixmatch token: HTTP ${response.code}")
                        return@withLock null
                    }
                    val body = response.body.string()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val token = json.getAsJsonObject("message")
                        ?.getAsJsonObject("body")
                        ?.get("user_token")?.asString

                    if (!token.isNullOrBlank()) {
                        cachedToken = token
                        context.dataStore.edit { it[KEY_MUSIXMATCH_TOKEN] = token }
                        Log.d(TAG, "Obtained fresh Musixmatch user token")
                        return@withLock token
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching Musixmatch token: ${e.message}")
            }
            null
        }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Int = -1
    ): Lyrics? = withContext(Dispatchers.IO) {
        val token = getUserToken() ?: return@withContext null

        try {
            val qTrack = URLEncoder.encode(title.trim(), "UTF-8")
            val qArtist = URLEncoder.encode(artist.trim(), "UTF-8")
            val qDur = if (durationSeconds > 0) durationSeconds else 180

            val url = "$BASE_URL/macro.subtitles.get?" +
                "format=json" +
                "&q_track=$qTrack" +
                "&q_artist=$qArtist" +
                "&q_duration=$qDur" +
                "&namespace=lyrics_richsynched" +
                "&subtitle_format=mxm" +
                "&app_id=$APP_ID" +
                "&usertoken=$token"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", COOKIE_HEADER)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Musixmatch macro request failed: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body.string()
                val json = gson.fromJson(body, JsonObject::class.java)
                val macroCalls = json.getAsJsonObject("message")
                    ?.getAsJsonObject("body")
                    ?.getAsJsonObject("macro_calls") ?: return@withContext null

                // Priority 1: track.richsync.get (Tier 2 / Word-level syllables)
                val richsyncCall = macroCalls.getAsJsonObject("track.richsync.get")
                val richsyncStatus = richsyncCall?.getAsJsonObject("message")
                    ?.getAsJsonObject("header")
                    ?.get("status_code")?.asInt ?: 0

                if (richsyncStatus == 200) {
                    val richsyncBodyRaw = richsyncCall?.getAsJsonObject("message")
                        ?.getAsJsonObject("body")
                        ?.getAsJsonObject("richsync")
                        ?.get("richsync_body")?.asString

                    if (!richsyncBodyRaw.isNullOrBlank()) {
                        val parsed = parseRichsyncBody(richsyncBodyRaw)
                        if (parsed != null && !parsed.synced.isNullOrEmpty()) {
                            Log.d(TAG, "Parsed full Richsync karaoke from Musixmatch for $title - $artist")
                            return@withContext parsed.copy(areFromRemote = true)
                        }
                    }
                }

                // Priority 2: track.subtitles.get (Tier 3 / Line-by-line)
                val subtitlesCall = macroCalls.getAsJsonObject("track.subtitles.get")
                val subtitlesStatus = subtitlesCall?.getAsJsonObject("message")
                    ?.getAsJsonObject("header")
                    ?.get("status_code")?.asInt ?: 0

                if (subtitlesStatus == 200) {
                    val subtitleList = subtitlesCall?.getAsJsonObject("message")
                        ?.getAsJsonObject("body")
                        ?.getAsJsonArray("subtitle_list")

                    val subtitleBody = subtitleList?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("subtitle")
                        ?.get("subtitle_body")?.asString

                    if (!subtitleBody.isNullOrBlank()) {
                        if (subtitleBody.contains("gopini", ignoreCase = true) || subtitleBody.contains("woxica", ignoreCase = true)) {
                            Log.w(TAG, "Musixmatch returned dummy honeypot placeholder! Discarding.")
                            return@withContext null
                        }
                        val parsed = LyricsUtils.parseLyrics(subtitleBody)
                        if (!parsed.synced.isNullOrEmpty() || !parsed.plain.isNullOrEmpty()) {
                            Log.d(TAG, "Parsed line subtitles from Musixmatch for $title - $artist")
                            return@withContext parsed.copy(areFromRemote = true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Musixmatch fetch error: ${e.message}")
        }
        null
    }

    private fun parseRichsyncBody(jsonString: String): Lyrics? {
        return runCatching {
            val array = gson.fromJson(jsonString, JsonArray::class.java) ?: return null
            val syncedLines = mutableListOf<SyncedLine>()

            for (elem in array) {
                val lineObj = elem.asJsonObject
                val ts = lineObj.get("ts")?.asDouble ?: continue
                val te = lineObj.get("te")?.asDouble ?: (ts + 3.0)
                val lineStartMs = (ts * 1000).toInt()
                val lineEndMs = (te * 1000).toInt()

                val syllablesArray = lineObj.getAsJsonArray("l") ?: continue
                val words = mutableListOf<SyncedWord>()
                val lineTextBuilder = StringBuilder()

                for (sElem in syllablesArray) {
                    val sObj = sElem.asJsonObject
                    val text = sObj.get("c")?.asString ?: ""
                    val offsetSec = sObj.get("o")?.asDouble ?: 0.0
                    val wordTimeMs = (ts * 1000 + offsetSec * 1000).toInt()

                    lineTextBuilder.append(text)
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        words.add(
                            SyncedWord(
                                time = wordTimeMs,
                                word = trimmed,
                                startsNewWord = text.firstOrNull()?.isWhitespace() == true || words.isEmpty()
                            )
                        )
                    }
                }

                val fullLine = lineTextBuilder.toString().trim()
                if (fullLine.isNotEmpty()) {
                    var mainText = fullLine
                    var subPhrases: List<SubPhrase>? = null

                    if (fullLine.contains("(") && fullLine.contains(")")) {
                        val match = Regex("""\(([^)]+)\)""").find(fullLine)
                        if (match != null) {
                            val bg = match.groupValues[1].trim()
                            mainText = fullLine.replace(match.value, "").trim()
                            subPhrases = listOf(
                                SubPhrase(
                                    startTime = lineStartMs,
                                    endTime = lineEndMs,
                                    text = bg
                                )
                            )
                        }
                    }

                    syncedLines.add(
                        SyncedLine(
                            time = lineStartMs,
                            line = mainText,
                            words = words.ifEmpty { null },
                            endTime = lineEndMs,
                            subPhrases = subPhrases
                        )
                    )
                }
            }

            if (syncedLines.isEmpty()) null else Lyrics(synced = syncedLines)
        }.getOrNull()
    }
}
