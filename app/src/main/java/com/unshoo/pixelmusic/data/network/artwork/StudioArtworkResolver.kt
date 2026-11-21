package com.unshoo.pixelmusic.data.network.artwork

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-resolution studio album artwork resolver.
 * Fetches pristine square studio album artwork (up to 1400x1400px) from Apple Music / iTunes Store
 * and Deezer APIs, replacing video frames, 16:9 thumbnails, and lyric video stills.
 */
@Singleton
class StudioArtworkResolver @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "StudioArtwork"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Resolves pristine, high-resolution official studio album artwork (1400x1400px).
     * Priority:
     * 1. Apple Music / iTunes Store API (1400x1400px master artwork)
     * 2. Deezer API (1000x1000px studio album art)
     * 3. Fallback to provided artwork URL
     */
    suspend fun resolveStudioArtwork(
        title: String,
        artist: String,
        fallbackUrl: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanT = cleanSearchTerm(title)
        val cleanA = cleanSearchTerm(artist.split(',', '&', '/').firstOrNull() ?: artist)

        if (cleanT.isBlank()) return@withContext fallbackUrl.orEmpty()

        val cacheKey = "${cleanA.lowercase()}_${cleanT.lowercase()}"
        cache[cacheKey]?.let { return@withContext it }

        val query = "$cleanA $cleanT".trim()

        // Tier 1: Apple Music / iTunes Store API (1400x1400px master square artwork)
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val itunesUrl = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=1"
            val request = Request.Builder()
                .url(itunesUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                val json = JsonParser.parseString(body).asJsonObject
                val results = json.getAsJsonArray("results")
                if (results != null && results.size() > 0) {
                    val first = results.get(0).asJsonObject
                    val rawArt = first.get("artworkUrl100")?.asString
                    if (!rawArt.isNullOrBlank()) {
                        val highRes = rawArt.replace("100x100bb", "1400x1400bb")
                        cache[cacheKey] = highRes
                        Timber.i("[$TAG] Resolved Apple Music 1400x1400 studio artwork for '$cleanT' by '$cleanA': $highRes")
                        return@withContext highRes
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "[$TAG] iTunes artwork search failed for $cleanT")
        }

        // Tier 2: Deezer API (1000x1000px studio cover)
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val deezerUrl = "https://api.deezer.com/search?q=$encodedQuery&limit=1"
            val request = Request.Builder()
                .url(deezerUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                val json = JsonParser.parseString(body).asJsonObject
                val data = json.getAsJsonArray("data")
                if (data != null && data.size() > 0) {
                    val first = data.get(0).asJsonObject
                    val album = first.getAsJsonObject("album")
                    val coverXl = album?.get("cover_xl")?.asString ?: album?.get("cover_big")?.asString
                    if (!coverXl.isNullOrBlank()) {
                        cache[cacheKey] = coverXl
                        Timber.i("[$TAG] Resolved Deezer 1000x1000 studio artwork for '$cleanT' by '$cleanA': $coverXl")
                        return@withContext coverXl
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "[$TAG] Deezer artwork search failed for $cleanT")
        }

        fallbackUrl.orEmpty()
    }

    private fun cleanSearchTerm(str: String): String =
        str.replace(Regex("""\s*[\(\[][^\)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|mv|feat|ft|prod|clip|клип|премьера)\b[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–—+]\s*\b(remix|rmx|sped\s*up|speed\s*up|slowed|reverb|nightcore|karaoke|instrumental)\b.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
}
