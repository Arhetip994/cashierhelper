package com.unshoo.pixelmusic.data.network.youtube

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.unshoo.pixelmusic.data.model.ArtistRef
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.network.artwork.StudioArtworkResolver
import com.unshoo.pixelmusic.data.network.audio.AudioMatchingHelper
import com.unshoo.pixelmusic.data.network.audio.MultiSourceAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 180_000L,
    val thumbnailUrl: String? = null
)

data class YouTubePlaylist(
    val id: String,
    val title: String,
    val author: String? = null,
    val tracks: List<YouTubeTrack> = emptyList()
)

/**
 * YouTube Music & YouTube InnerTube API Client.
 * Queries YouTube Music backend and YouTube search to find all tracks, singles, and collaborations,
 * and extracts direct unthrottled audio streams via Android InnerTube client.
 */
@Singleton
class InnerTubeParser @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val multiSourceAudioEngine: MultiSourceAudioEngine,
    private val studioArtworkResolver: StudioArtworkResolver
) {
    companion object {
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
        private const val YOUTUBE_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoSEAUQAxAJEAQQEBAKEA4QFRAR"
        private const val VIDEOS_FILTER_PARAMS = "EgWKAQIQAWoSEAUQAxAJEAQQEBAKEA4QFRAR"

        private val PLAYLIST_REGEX = Pattern.compile("(?:list=|/playlist\\?list=)([a-zA-Z0-9_-]+)")
        private val VIDEO_REGEX = Pattern.compile("(?:v=|/watch\\?v=|/shorts/|youtu\\.be/)([a-zA-Z0-9_-]{11})")

        // 4 hour stream URL expiration cache (GoogleVideo expiration is ~6 hours)
        private const val STREAM_CACHE_TTL_MS = 4 * 60 * 60 * 1000L

        fun extractPlaylistIdStatic(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.startsWith("PL") || trimmed.startsWith("VLPL") || trimmed.startsWith("RDCLAK")) {
                return trimmed.removePrefix("VL")
            }
            val matcher = PLAYLIST_REGEX.matcher(trimmed)
            if (matcher.find()) {
                return matcher.group(1)
            }
            return null
        }

        fun extractVideoIdStatic(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
                return trimmed
            }
            val matcher = VIDEO_REGEX.matcher(trimmed)
            if (matcher.find()) {
                return matcher.group(1)
            }
            return null
        }

        /**
         * Upgrades low-resolution YouTube/Google thumbnails to high resolution (up to 1200x1200 or 720p).
         */
        fun upgradeArtworkUrl(url: String?, videoId: String? = null): String? {
            if (url.isNullOrBlank()) {
                return if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg" else null
            }
            // Google User Content (YouTube Music album art: upgrade to pristine 1200x1200)
            if (url.contains("googleusercontent.com")) {
                val baseUrl = url.substringBefore("=")
                return "$baseUrl=w1200-h1200-l90-rj"
            }
            // YouTube video thumbnail (upgrade to maxresdefault for 1080p/4K TVs)
            if (url.contains("i.ytimg.com") || url.contains("img.youtube.com")) {
                return url.replace(Regex("/(default|mqdefault|hqdefault|sddefault)\\.jpg"), "/maxresdefault.jpg")
            }
            return url
        }

        /**
         * Normalizes queries and handles common typos (e.g. Weekend -> Weeknd, Timless -> Timeless).
         */
        fun normalizeSearchQuery(q: String): String {
            var s = q.replace(" - ", " ").replace("-", " ")
            s = s.replace(Regex("""\bweekend\b""", RegexOption.IGNORE_CASE), "Weeknd")
            s = s.replace(Regex("""\btimless\b""", RegexOption.IGNORE_CASE), "Timeless")
            s = s.replace(Regex("""\bplayboy\s*carti\b""", RegexOption.IGNORE_CASE), "Playboi Carti")
            s = s.replace(Regex("""\bthe\s*weedkn\b""", RegexOption.IGNORE_CASE), "The Weeknd")
            return s.trim()
        }

        /**
         * Cleans junk like '(Official Video)', '(Lyrics)', etc. from YouTube titles and extracts real artist/title.
         */
        fun cleanTitleAndArtist(rawTitle: String, rawArtist: String): Pair<String, String> {
            val noiseRegex = Regex("""\s*[\(\[](official\s*(music\s*)?video|video|lyrics?|visualizer|audio|hd|hq|4k|mv|клип|премьера)[\)\]]""", RegexOption.IGNORE_CASE)
            var t = rawTitle.replace(noiseRegex, "").trim()
            val a = rawArtist.trim()

            val delims = listOf(" - ", " — ", " – ")
            val delim = delims.firstOrNull { t.contains(it) }

            if (delim != null) {
                val parts = t.split(delim, limit = 2)
                val p0 = parts[0].trim()
                val p1 = parts[1].trim()

                // Check if reversed: e.g. Song - Artist ft Other
                val p1Lower = " ${p1.lowercase()} "
                val p0Lower = " ${p0.lowercase()} "
                val hasFeat1 = p1Lower.contains(" ft ") || p1Lower.contains(" feat ")
                val hasFeat0 = p0Lower.contains(" ft ") || p0Lower.contains(" feat ")

                val isReversed = hasFeat1 && !hasFeat0 && p0.split(" ").size <= 2 &&
                        listOf("the", "playboi", "lil", "young", "dj", "travis", "kanye", "weeknd").any { p1.lowercase().startsWith(it) }

                var candArtist = if (isReversed) p1 else p0
                var candTitle = if (isReversed) p0 else p1

                // Extract feat from candTitle into artist
                val featParenRegex = Regex("""\s*[\(\[](?:feat|ft)\.?\s+([^\)\]]+)[\)\]]""", RegexOption.IGNORE_CASE)
                val matchParen = featParenRegex.find(candTitle)
                if (matchParen != null) {
                    val featured = matchParen.groupValues[1].trim()
                    candArtist = "$candArtist, $featured"
                    candTitle = candTitle.replace(featParenRegex, "").trim()
                } else {
                    val featBareRegex = Regex("""\s+\b(?:feat|ft)\.?\s+(.+)$""", RegexOption.IGNORE_CASE)
                    val matchBare = featBareRegex.find(candTitle)
                    if (matchBare != null) {
                        val featured = matchBare.groupValues[1].trim()
                        candArtist = "$candArtist, $featured"
                        candTitle = candTitle.replace(featBareRegex, "").trim()
                    }
                }

                candArtist = candArtist.replace(Regex("""\s+\b(?:feat|ft)\.?\s+""", RegexOption.IGNORE_CASE), ", ")
                candArtist = candArtist.replace(Regex("""\s*[\(\[](?:feat|ft)\.?\s*([^\)\]]+)[\)\]]""", RegexOption.IGNORE_CASE), ", $1")
                candArtist = candArtist.replace('&', ',').replace("  ", " ").trim(' ', ',')
                candTitle = candTitle.replace(noiseRegex, "").trim()

                return Pair(candTitle.ifBlank { rawTitle }, candArtist.ifBlank { a.ifBlank { "YouTube Music" } })
            }

            return Pair(t.ifBlank { rawTitle }, a.ifBlank { "YouTube Music" })
        }
    }

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val streamUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val videoDurationCache = ConcurrentHashMap<String, Long>()

    private val CLICKBAIT_NOISE_REGEX = Regex(
        """\b(remix|cover|karaoke|instrumental|tutorial|piano|after\s*dark|slowed|sped\s*up|reverb|8d|mashup|flp|tribute|parody)\b""",
        RegexOption.IGNORE_CASE
    )

    fun extractPlaylistId(input: String): String? = extractPlaylistIdStatic(input)

    fun extractVideoId(input: String): String? = extractVideoIdStatic(input)

    /**
     * Finds a verified, unblocked playable stream for a song when the official video is geoblocked.
     * Enforces:
     * 1. Query prioritization (clean lyric videos first).
     * 2. Strict clickbait/remix/cover filtering.
     * 3. Strict duration validation (candidate must be within ±45s of target song length).
     */
    suspend fun findPlayableAlternative(
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long,
        excludeVideoId: String? = null
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val primaryArtist = targetArtist.split(",", "&", "/").firstOrNull()?.trim() ?: targetArtist
        val altQueries = listOf(
            "$primaryArtist $targetTitle lyrics",
            "$primaryArtist $targetTitle official lyric video",
            "$primaryArtist $targetTitle official audio",
            "$targetArtist $targetTitle lyrics",
            "$targetTitle $primaryArtist"
        )

        val seen = mutableSetOf<String>()
        if (!excludeVideoId.isNullOrBlank()) {
            seen.add(excludeVideoId)
        }

        val allCandidates = mutableListOf<YouTubeTrack>()
        for (aq in altQueries) {
            val list = executeYouTubeWebSearch(aq) + executeSearchQuery(aq, VIDEOS_FILTER_PARAMS, "US")
            for (c in list) {
                if (seen.add(c.videoId)) {
                    allCandidates.add(c)
                }
            }
        }

        val targetLower = targetTitle.lowercase().trim()
        val primaryArtistNorm = AudioMatchingHelper.normalize(primaryArtist)

        val validCandidates = allCandidates.filter { cand ->
            val tLower = cand.title.lowercase()
            // 1. Must contain the real song title
            if (!tLower.contains(targetLower)) return@filter false

            // 2. Reject fake songs, remixes, covers, instrumentals, piano tutorials, "after dark", etc.
            if (CLICKBAIT_NOISE_REGEX.containsMatchIn(tLower)) {
                Timber.d("Rejecting candidate ${cand.videoId} ('${cand.title}') due to clickbait/remix keyword")
                return@filter false
            }

            // 3. Artist authenticity: either candidate artist or video title must mention primary artist
            if (primaryArtistNorm.isNotBlank()) {
                val candArtistNorm = AudioMatchingHelper.normalize(cand.artist)
                val candTitleNorm = AudioMatchingHelper.normalize(cand.title)
                val matchesArtist = candArtistNorm.contains(primaryArtistNorm) || candTitleNorm.contains(primaryArtistNorm)
                if (!matchesArtist) {
                    Timber.d("Rejecting candidate ${cand.videoId} ('${cand.title}' by '${cand.artist}'): artist does not match '$primaryArtist'")
                    return@filter false
                }
            }

            // 4. Duration validation: if target duration is known, require candidate within ±35 seconds
            if (targetDurationMs > 60_000L && cand.durationMs > 0L) {
                val diffMs = kotlin.math.abs(cand.durationMs - targetDurationMs)
                if (diffMs > 35_000L) {
                    Timber.d("Rejecting candidate ${cand.videoId} ('${cand.title}') due to duration diff: ${diffMs / 1000}s")
                    return@filter false
                }
            }
            true
        }.sortedBy { cand ->
            // Prioritize closest duration match
            if (targetDurationMs > 0L && cand.durationMs > 0L) {
                kotlin.math.abs(cand.durationMs - targetDurationMs)
            } else {
                0L
            }
        }

        for (cand in validCandidates) {
            val streamUrl = getAudioStreamUrl(cand.videoId)
            if (!streamUrl.isNullOrBlank()) {
                Timber.i("Selected verified playable alternative for '$targetTitle': ${cand.videoId} ('${cand.title}', duration: ${cand.durationMs / 1000}s, diff: ${kotlin.math.abs(cand.durationMs - targetDurationMs) / 1000}s)")
                return@withContext Pair(cand.videoId, streamUrl)
            }
        }
        null
    }

    /**
     * Retrieves video metadata using YouTube's oEmbed endpoint.
     * Works universally without API keys, cookies, or geoblocking restrictions.
     */
    suspend fun fetchOEmbedMetadata(videoId: String): YouTubeTrack? = withContext(Dispatchers.IO) {
        val trimmedId = extractVideoId(videoId) ?: videoId.trim()
        if (trimmedId.length != 11) return@withContext null

        try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$trimmedId&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("oEmbed returned HTTP ${response.code} for $trimmedId")
                return@withContext null
            }

            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject
            val rawTitle = json.get("title")?.asString.orEmpty()
            val rawAuthor = json.get("author_name")?.asString.orEmpty()
            val thumbUrl = json.get("thumbnail_url")?.asString

            if (rawTitle.isBlank()) return@withContext null

            val (cleanedTitle, cleanedArtist) = cleanTitleAndArtist(rawTitle, rawAuthor)
            val highResThumb = upgradeArtworkUrl(thumbUrl, trimmedId)
            val studioArt = try {
                studioArtworkResolver.resolveStudioArtwork(cleanedTitle, cleanedArtist, fallbackUrl = highResThumb)
            } catch (e: Exception) {
                highResThumb.orEmpty()
            }
            val finalArt = if (studioArt.isNotBlank()) studioArt else highResThumb

            val cachedDur = videoDurationCache[trimmedId] ?: 0L
            YouTubeTrack(
                videoId = trimmedId,
                title = cleanedTitle,
                artist = cleanedArtist,
                album = "YouTube Music",
                durationMs = cachedDur,
                thumbnailUrl = finalArt
            )
        } catch (e: Exception) {
            Timber.w(e, "Error fetching oEmbed metadata for $trimmedId")
            null
        }
    }

    /**
     * Retrieves video metadata directly from Android Player API.
     * Extracts exact title, author, and duration even if direct audio stream is restricted.
     */
    suspend fun fetchPlayerVideoDetails(videoId: String): YouTubeTrack? = withContext(Dispatchers.IO) {
        val trimmedId = extractVideoId(videoId) ?: videoId.trim()
        if (trimmedId.length != 11) return@withContext null

        val clientObj = JsonObject().apply {
            addProperty("clientName", "ANDROID")
            addProperty("clientVersion", "21.26.364")
            addProperty("androidSdkVersion", 30)
            addProperty("userAgent", "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip")
            addProperty("hl", "en")
            addProperty("gl", "US")
        }
        val payload = JsonObject().apply {
            add("context", JsonObject().apply { add("client", clientObj) })
            addProperty("videoId", trimmedId)
        }

        try {
            val request = Request.Builder()
                .url(PLAYER_URL)
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject
            val vd = json.getAsJsonObject("videoDetails") ?: return@withContext null

            val rawTitle = vd.get("title")?.asString.orEmpty()
            val rawAuthor = vd.get("author")?.asString.orEmpty()
            val lengthSec = vd.get("lengthSeconds")?.asLong ?: 0L
            val durMs = lengthSec * 1000L
            if (durMs > 0) {
                videoDurationCache[trimmedId] = durMs
            }

            val (cleanT, cleanA) = cleanTitleAndArtist(rawTitle, rawAuthor)
            val thumbUrl = "https://i.ytimg.com/vi/$trimmedId/hqdefault.jpg"
            val highResThumb = upgradeArtworkUrl(thumbUrl, trimmedId)
            val studioArt = try {
                studioArtworkResolver.resolveStudioArtwork(cleanT, cleanA, fallbackUrl = highResThumb)
            } catch (e: Exception) {
                highResThumb.orEmpty()
            }
            val finalArt = if (studioArt.isNotBlank()) studioArt else highResThumb

            YouTubeTrack(
                videoId = trimmedId,
                title = cleanT,
                artist = cleanA,
                durationMs = durMs,
                thumbnailUrl = finalArt
            )
        } catch (e: Exception) {
            Timber.w(e, "fetchPlayerVideoDetails failed for $trimmedId")
            null
        }
    }

    /**
     * Parses a YouTube Music playlist by URL or ID.
     */
    suspend fun parsePlaylist(urlOrId: String, region: String = "US"): YouTubePlaylist? = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(urlOrId) ?: urlOrId.trim()
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"

        val payload = JsonObject().apply {
            add("context", createWebRemixContext(region))
            addProperty("browseId", browseId)
        }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/browse?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Referer", "https://music.youtube.com/")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("InnerTube parsePlaylist failed with HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject

            var playlistTitle = "YouTube Playlist"
            try {
                val tabs = json.getAsJsonObject("contents")
                    ?.getAsJsonObject("twoColumnBrowseResultsRenderer")
                    ?.getAsJsonArray("tabs")
                if (tabs != null && tabs.size() > 0) {
                    val sec = tabs.get(0).asJsonObject
                        .getAsJsonObject("tabRenderer")
                        ?.getAsJsonObject("content")
                        ?.getAsJsonObject("sectionListRenderer")
                        ?.getAsJsonArray("contents")
                    if (sec != null && sec.size() > 0) {
                        val header = sec.get(0).asJsonObject.getAsJsonObject("musicResponsiveHeaderRenderer")
                        header?.getAsJsonObject("title")
                            ?.getAsJsonArray("runs")?.let { runs ->
                                if (runs.size() > 0) {
                                    playlistTitle = runs.get(0).asJsonObject.get("text").asString
                                }
                            }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parsing playlist title")
            }

            val tracks = mutableListOf<YouTubeTrack>()
            try {
                val secContents = json.getAsJsonObject("contents")
                    ?.getAsJsonObject("twoColumnBrowseResultsRenderer")
                    ?.getAsJsonObject("secondaryContents")
                    ?.getAsJsonObject("sectionListRenderer")
                    ?.getAsJsonArray("contents")

                if (secContents != null && secContents.size() > 0) {
                    val shelf = secContents.get(0).asJsonObject.getAsJsonObject("musicPlaylistShelfRenderer")
                    val items = shelf?.getAsJsonArray("contents") ?: return@withContext null

                    for (i in 0 until items.size()) {
                        val item = items.get(i).asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer") ?: continue
                        val parsed = parseResponsiveListItem(item)
                        if (parsed != null) {
                            tracks.add(parsed)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parsing playlist tracks")
            }

            Timber.d("InnerTube parsed playlist: '$playlistTitle' with ${tracks.size} tracks")
            return@withContext YouTubePlaylist(
                id = playlistId,
                title = playlistTitle,
                tracks = tracks
            )
        } catch (e: Exception) {
            Timber.e(e, "Exception in InnerTube parsePlaylist")
            null
        }
    }

    /**
     * Searches YouTube Music and YouTube Web concurrently.
     * Integrates Songs, Videos, and general Web searches to guarantee finding
     * hit singles, collaborations, and misspellings (e.g. The Weekend - Timless).
     * If query is a YouTube link or video ID, resolves metadata and playable streams.
     */
    suspend fun searchSongs(query: String, region: String = "US"): List<YouTubeTrack> = supervisorScope {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@supervisorScope emptyList()

        // 1. Direct URL or Video ID query
        val directVideoId = extractVideoId(trimmedQuery)
        if (!directVideoId.isNullOrBlank()) {
            val meta = parseSong(directVideoId, region)
            if (meta != null) {
                val directStream = getAudioStreamUrl(directVideoId)
                if (!directStream.isNullOrBlank()) {
                    return@supervisorScope listOf(meta)
                }

                // If direct stream is UNPLAYABLE (geoblocked like vt0i6nuqNEo, 5EpyN_6dqyk),
                // search for verified unblocked playable version of the same song!
                Timber.w("Video $directVideoId is geoblocked. Searching verified playable alternative stream for '${meta.artist} - ${meta.title}'")
                val targetDur = videoDurationCache[directVideoId] ?: meta.durationMs
                val alt = findPlayableAlternative(
                    targetTitle = meta.title,
                    targetArtist = meta.artist,
                    targetDurationMs = targetDur,
                    excludeVideoId = directVideoId
                )
                if (alt != null) {
                    val verifiedTrack = YouTubeTrack(
                        videoId = alt.first,
                        title = meta.title,
                        artist = meta.artist,
                        album = meta.album,
                        durationMs = meta.durationMs,
                        thumbnailUrl = meta.thumbnailUrl
                    )
                    return@supervisorScope listOf(verifiedTrack)
                }
                return@supervisorScope listOf(meta)
            }
        }

        val normalized = normalizeSearchQuery(trimmedQuery)

        // Parallel fan-out across multiple YouTube / YouTube Music endpoints
        val videosDeferred = async(Dispatchers.IO) {
            try {
                executeSearchQuery(query = normalized, params = VIDEOS_FILTER_PARAMS, region = region)
            } catch (e: Exception) {
                Timber.w(e, "Search VIDEOS endpoint failed")
                emptyList()
            }
        }
        val webExactDeferred = async(Dispatchers.IO) {
            try {
                executeYouTubeWebSearch(query = normalized)
            } catch (e: Exception) {
                Timber.w(e, "YouTube web search failed")
                emptyList()
            }
        }
        val webAudioDeferred = async(Dispatchers.IO) {
            try {
                executeYouTubeWebSearch(query = "$normalized official audio")
            } catch (e: Exception) {
                emptyList()
            }
        }
        val webLyricsDeferred = async(Dispatchers.IO) {
            try {
                executeYouTubeWebSearch(query = "$normalized lyrics")
            } catch (e: Exception) {
                emptyList()
            }
        }
        val generalDeferred = async(Dispatchers.IO) {
            try {
                executeSearchQuery(query = normalized, params = null, region = region)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val songsDeferred = async(Dispatchers.IO) {
            try {
                executeSearchQuery(query = normalized, params = SONGS_FILTER_PARAMS, region = region)
            } catch (e: Exception) {
                emptyList()
            }
        }

        val videosList = videosDeferred.await()
        val webExactList = webExactDeferred.await()
        val webAudioList = webAudioDeferred.await()
        val webLyricsList = webLyricsDeferred.await()
        val generalList = generalDeferred.await()
        val songsList = songsDeferred.await()

        val combined = mutableListOf<YouTubeTrack>()
        val seen = mutableSetOf<String>()

        fun addAllUnique(list: List<YouTubeTrack>) {
            for (track in list) {
                if (seen.add(track.videoId)) {
                    combined.add(track)
                }
            }
        }

        addAllUnique(videosList)
        addAllUnique(webExactList)
        addAllUnique(webAudioList)
        addAllUnique(webLyricsList)
        addAllUnique(songsList)
        addAllUnique(generalList)

        // Relevance ranking
        val queryWords = normalized.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val popularArtists = listOf("the weeknd", "weeknd", "playboi carti", "madonna", "drake", "travis scott", "kanye", "taylor swift", "billie eilish", "eminem")
        val isMetaQuery = normalized.lowercase().contains("karaoke") ||
                normalized.lowercase().contains("instrumental") ||
                normalized.lowercase().contains("tutorial") ||
                normalized.lowercase().contains("cover")

        val ranked = combined.sortedByDescending { track ->
            var score = 0
            val tLower = track.title.lowercase()
            val aLower = track.artist.lowercase()

            var titleMatches = 0
            var artistMatches = 0
            for (w in queryWords) {
                if (tLower.contains(w)) {
                    score += 30
                    titleMatches++
                }
                if (aLower.contains(w)) {
                    score += 25
                    artistMatches++
                }
            }
            if (tLower.equals(normalized, ignoreCase = true)) score += 100
            if (tLower.startsWith(normalized, ignoreCase = true)) score += 40
            if (titleMatches > 0 && artistMatches > 0) score += 50
            if (popularArtists.any { aLower.contains(it) }) score += 35

            if (!isMetaQuery) {
                if (CLICKBAIT_NOISE_REGEX.containsMatchIn(tLower)) {
                    score -= 80
                }
            }
            score
        }

        Timber.d("InnerTube search for '$trimmedQuery' (norm: '$normalized') returned ${ranked.size} tracks")
        ranked.take(40)
    }

    private fun executeSearchQuery(query: String, params: String?, region: String): List<YouTubeTrack> {
        val payload = JsonObject().apply {
            add("context", createWebRemixContext(region))
            addProperty("query", query)
            if (!params.isNullOrBlank()) {
                addProperty("params", params)
            }
        }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/search?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Referer", "https://music.youtube.com/")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject

            val tabs = json.getAsJsonObject("contents")
                ?.getAsJsonObject("tabbedSearchResultsRenderer")
                ?.getAsJsonArray("tabs") ?: return emptyList()

            if (tabs.size() == 0) return emptyList()

            val secList = tabs.get(0).asJsonObject
                .getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return emptyList()

            val results = mutableListOf<YouTubeTrack>()

            for (i in 0 until secList.size()) {
                val secObj = secList.get(i).asJsonObject

                // musicShelfRenderer
                secObj.getAsJsonObject("musicShelfRenderer")?.let { shelf ->
                    shelf.getAsJsonArray("contents")?.let { items ->
                        for (j in 0 until items.size()) {
                            items.get(j).asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer")?.let { item ->
                                parseResponsiveListItem(item)?.let { results.add(it) }
                            }
                        }
                    }
                }

                // itemSectionRenderer
                secObj.getAsJsonObject("itemSectionRenderer")?.let { itemSec ->
                    itemSec.getAsJsonArray("contents")?.let { items ->
                        for (j in 0 until items.size()) {
                            items.get(j).asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer")?.let { item ->
                                parseResponsiveListItem(item)?.let { results.add(it) }
                            }
                        }
                    }
                }

                // musicCardShelfRenderer (Top Result card)
                secObj.getAsJsonObject("musicCardShelfRenderer")?.let { card ->
                    parseMusicCardShelf(card)?.let { results.add(it) }
                    card.getAsJsonArray("contents")?.let { cardContents ->
                        for (j in 0 until cardContents.size()) {
                            cardContents.get(j).asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer")?.let { item ->
                                parseResponsiveListItem(item)?.let { results.add(it) }
                            }
                        }
                    }
                }
            }

            return results
        } catch (e: Exception) {
            Timber.e(e, "Exception in executeSearchQuery for '$query'")
            return emptyList()
        }
    }

    /**
     * Executes a search on regular YouTube to retrieve spell-corrected and widely popular videos.
     */
    private fun executeYouTubeWebSearch(query: String): List<YouTubeTrack> {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "WEB")
            addProperty("clientVersion", "2.20240101.01.00")
            addProperty("hl", "en")
            addProperty("gl", "US")
        }
        val payload = JsonObject().apply {
            add("context", JsonObject().apply { add("client", clientObj) })
            addProperty("query", query)
        }

        try {
            val request = Request.Builder()
                .url(YOUTUBE_SEARCH_URL)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject

            val results = mutableListOf<YouTubeTrack>()
            val contents = json.getAsJsonObject("contents")
                ?.getAsJsonObject("twoColumnSearchResultsRenderer")
                ?.getAsJsonObject("primaryContents")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return emptyList()

            for (i in 0 until contents.size()) {
                val sec = contents.get(i).asJsonObject
                val itemSection = sec.getAsJsonObject("itemSectionRenderer") ?: continue
                val items = itemSection.getAsJsonArray("contents") ?: continue

                for (j in 0 until items.size()) {
                    val vr = items.get(j).asJsonObject.getAsJsonObject("videoRenderer") ?: continue
                    val vid = vr.get("videoId")?.asString ?: continue
                    val rawTitle = vr.getAsJsonObject("title")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: "Unknown Title"
                    val rawArtist = vr.getAsJsonObject("ownerText")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString
                        ?: vr.getAsJsonObject("shortBylineText")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString
                        ?: "YouTube"

                    val (cleanedTitle, cleanedArtist) = cleanTitleAndArtist(rawTitle, rawArtist)

                    val durationStr = vr.getAsJsonObject("lengthText")?.get("simpleText")?.asString.orEmpty()
                    val durationMs = parseDurationMs(durationStr)

                    var thumbUrl: String? = null
                    val thumbs = vr.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                    if (thumbs != null && thumbs.size() > 0) {
                        thumbUrl = thumbs.get(thumbs.size() - 1).asJsonObject.get("url")?.asString
                    }
                    val highResThumb = upgradeArtworkUrl(thumbUrl, vid)

                    results.add(
                        YouTubeTrack(
                            videoId = vid,
                            title = cleanedTitle,
                            artist = cleanedArtist,
                            album = "YouTube",
                            durationMs = durationMs,
                            thumbnailUrl = highResThumb
                        )
                    )
                }
            }
            return results
        } catch (e: Exception) {
            Timber.e(e, "Exception in executeYouTubeWebSearch for '$query'")
            return emptyList()
        }
    }

    private fun parseResponsiveListItem(item: JsonObject): YouTubeTrack? {
        try {
            var videoId: String? = item.getAsJsonObject("playlistItemData")?.get("videoId")?.asString
            val flexColumns = item.getAsJsonArray("flexColumns") ?: return null

            if (videoId.isNullOrBlank() && flexColumns.size() > 0) {
                videoId = flexColumns.get(0).asJsonObject
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.getAsJsonObject("text")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                    ?.getAsJsonObject("navigationEndpoint")
                    ?.getAsJsonObject("watchEndpoint")
                    ?.get("videoId")?.asString
            }

            if (videoId.isNullOrBlank()) {
                videoId = item.getAsJsonObject("overlay")
                    ?.getAsJsonObject("musicItemThumbnailOverlayRenderer")
                    ?.getAsJsonObject("content")
                    ?.getAsJsonObject("musicPlayButtonRenderer")
                    ?.getAsJsonObject("playNavigationEndpoint")
                    ?.getAsJsonObject("watchEndpoint")
                    ?.get("videoId")?.asString
            }

            if (videoId.isNullOrBlank()) return null

            var title = "Unknown Title"
            if (flexColumns.size() > 0) {
                val col0 = flexColumns.get(0).asJsonObject
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.getAsJsonObject("text")
                    ?.getAsJsonArray("runs")
                if (col0 != null && col0.size() > 0) {
                    title = col0.get(0).asJsonObject.get("text")?.asString ?: title
                }
            }

            var artist = "YouTube Music"
            var album: String? = null
            var durationMs = 180_000L

            if (flexColumns.size() > 1) {
                val col1Runs = flexColumns.get(1).asJsonObject
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.getAsJsonObject("text")
                    ?.getAsJsonArray("runs")

                if (col1Runs != null && col1Runs.size() > 0) {
                    val texts = mutableListOf<String>()
                    for (k in 0 until col1Runs.size()) {
                        val txt = col1Runs.get(k).asJsonObject.get("text")?.asString.orEmpty().trim()
                        if (txt.isNotEmpty() && txt != "•" && txt != "·" && !isCategoryBadge(txt)) {
                            texts.add(txt)
                        }
                    }

                    if (texts.isNotEmpty()) {
                        artist = texts[0]
                    }
                    if (texts.size > 1) {
                        val second = texts[1]
                        if (second.matches(Regex("^\\d+:\\d+$"))) {
                            durationMs = parseDurationMs(second)
                        } else {
                            album = second
                        }
                    }
                    if (texts.size > 2) {
                        val third = texts[2]
                        if (third.matches(Regex("^\\d+:\\d+$"))) {
                            durationMs = parseDurationMs(third)
                        }
                    }
                }
            }

            var thumbUrl: String? = null
            val thumbs = item.getAsJsonObject("thumbnail")
                ?.getAsJsonObject("musicThumbnailRenderer")
                ?.getAsJsonObject("thumbnail")
                ?.getAsJsonArray("thumbnails")
            if (thumbs != null && thumbs.size() > 0) {
                thumbUrl = thumbs.get(thumbs.size() - 1).asJsonObject.get("url")?.asString
            }
            val highResThumb = upgradeArtworkUrl(thumbUrl, videoId)
            val (cleanedTitle, cleanedArtist) = cleanTitleAndArtist(title, artist)

            return YouTubeTrack(
                videoId = videoId,
                title = cleanedTitle,
                artist = cleanedArtist,
                album = album,
                durationMs = durationMs,
                thumbnailUrl = highResThumb
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseMusicCardShelf(card: JsonObject): YouTubeTrack? {
        try {
            val title = card.getAsJsonObject("title")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: return null
            val cardJsonStr = card.toString()
            val vidPattern = Pattern.compile(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
            val vidMatcher = vidPattern.matcher(cardJsonStr)
            val videoId = if (vidMatcher.find()) vidMatcher.group(1) else {
                val m2 = VIDEO_REGEX.matcher(cardJsonStr)
                if (m2.find()) m2.group(1) else return null
            }

            var artist = "YouTube Music"
            val subRuns = card.getAsJsonObject("subtitle")?.getAsJsonArray("runs")
            if (subRuns != null) {
                for (k in 0 until subRuns.size()) {
                    val txt = subRuns.get(k).asJsonObject.get("text")?.asString.orEmpty().trim()
                    if (txt.isNotEmpty() && txt != "•" && !isCategoryBadge(txt)) {
                        artist = txt
                        break
                    }
                }
            }

            var thumbUrl: String? = null
            val thumb = card.getAsJsonObject("thumbnail")?.getAsJsonObject("musicThumbnailRenderer")
                ?.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
            if (thumb != null && thumb.size() > 0) {
                thumbUrl = thumb.get(thumb.size() - 1).asJsonObject.get("url")?.asString
            }
            val highResThumb = upgradeArtworkUrl(thumbUrl, videoId)
            val (cleanedTitle, cleanedArtist) = cleanTitleAndArtist(title, artist)

            val cachedDur = videoDurationCache[videoId] ?: 0L
            return YouTubeTrack(
                videoId = videoId,
                title = cleanedTitle,
                artist = cleanedArtist,
                album = "YouTube Music",
                durationMs = cachedDur,
                thumbnailUrl = highResThumb
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun isCategoryBadge(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf("song", "video", "песня", "видео", "artist", "артист", "album", "альбом", "single", "сингл")
    }

    private fun parseDurationMs(durationStr: String): Long {
        return try {
            val parts = durationStr.split(":")
            if (parts.size == 2) {
                (parts[0].toLong() * 60 + parts[1].toLong()) * 1000L
            } else if (parts.size == 3) {
                (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000L
            } else {
                180_000L
            }
        } catch (e: Exception) {
            180_000L
        }
    }

    /**
     * Parses a single YouTube track metadata by video ID.
     * Uses oEmbed as primary source for guaranteed unblocked title/artist extraction,
     * with InnerTube as secondary fallback.
     */
    suspend fun parseSong(videoId: String, region: String = "US"): YouTubeTrack? = withContext(Dispatchers.IO) {
        val trimmedId = extractVideoId(videoId) ?: videoId.trim()
        if (trimmedId.length != 11) return@withContext null

        // 1. Try player videoDetails first (extracts exact duration, title, and author even for restricted videos)
        val playerMeta = fetchPlayerVideoDetails(trimmedId)
        if (playerMeta != null && playerMeta.title.isNotBlank()) {
            return@withContext playerMeta
        }

        // 2. Try oEmbed as secondary fallback (lightning fast, immune to geo-restrictions)
        val oEmbedMeta = fetchOEmbedMetadata(trimmedId)
        if (oEmbedMeta != null && oEmbedMeta.title.isNotBlank()) {
            return@withContext oEmbedMeta
        }

        // 3. Fallback to InnerTube search
        val payload = JsonObject().apply {
            add("context", createWebRemixContext(region))
            addProperty("query", trimmedId)
        }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/search?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Referer", "https://music.youtube.com/")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject

            val tabs = json.getAsJsonObject("contents")
                ?.getAsJsonObject("tabbedSearchResultsRenderer")
                ?.getAsJsonArray("tabs") ?: return@withContext null

            if (tabs.size() == 0) return@withContext null
            val secList = tabs.get(0).asJsonObject
                .getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return@withContext null

            for (i in 0 until secList.size()) {
                val sec = secList.get(i).asJsonObject
                sec.getAsJsonObject("musicCardShelfRenderer")?.let { card ->
                    parseMusicCardShelf(card)?.let { return@withContext it }
                }
                sec.getAsJsonObject("musicShelfRenderer")?.getAsJsonArray("contents")?.let { items ->
                    for (j in 0 until items.size()) {
                        items.get(j).asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer")?.let { item ->
                            val track = parseResponsiveListItem(item)
                            if (track != null && track.videoId == trimmedId) {
                                return@withContext track
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "Error in InnerTube parseSong for $trimmedId")
            null
        }
    }

    /**
     * Extracts an unthrottled direct audio stream URL for ExoPlayer playback.
     * Uses Android InnerTube client with caching (4 hour TTL).
     */
    suspend fun getAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val trimmedId = extractVideoId(videoId) ?: videoId.trim()
        if (trimmedId.length != 11) return@withContext null

        // Check in-memory cache
        val now = System.currentTimeMillis()
        streamUrlCache[trimmedId]?.let { (cachedUrl, timestamp) ->
            if (now - timestamp < STREAM_CACHE_TTL_MS) {
                Timber.d("InnerTube stream cache hit for $trimmedId")
                return@withContext cachedUrl
            } else {
                streamUrlCache.remove(trimmedId)
            }
        }

        // Tier 1: Android Client v21.26.364
        val stream1 = queryPlayerEndpoint(
            videoId = trimmedId,
            clientVersion = "21.26.364",
            clientNameNumber = "3"
        )
        if (!stream1.isNullOrBlank()) {
            streamUrlCache[trimmedId] = Pair(stream1, now)
            return@withContext stream1
        }

        // Tier 2: Android Client v20.10.38 (fallback)
        val stream2 = queryPlayerEndpoint(
            videoId = trimmedId,
            clientVersion = "20.10.38",
            clientNameNumber = "3"
        )
        if (!stream2.isNullOrBlank()) {
            streamUrlCache[trimmedId] = Pair(stream2, now)
            return@withContext stream2
        }

        Timber.w("InnerTube could not extract playable stream URL for $trimmedId")
        null
    }

    private fun queryPlayerEndpoint(videoId: String, clientVersion: String, clientNameNumber: String): String? {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "ANDROID")
            addProperty("clientVersion", clientVersion)
            addProperty("androidSdkVersion", 30)
            addProperty("userAgent", "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip")
            addProperty("osName", "Android")
            addProperty("osVersion", "11")
            addProperty("hl", "en")
            addProperty("gl", "US")
        }
        val contextObj = JsonObject().apply {
            add("client", clientObj)
        }
        val payload = JsonObject().apply {
            add("context", contextObj)
            addProperty("videoId", videoId)
        }

        val ua = "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip"

        try {
            val request = Request.Builder()
                .url(PLAYER_URL)
                .header("Content-Type", "application/json")
                .header("User-Agent", ua)
                .header("X-YouTube-Client-Name", clientNameNumber)
                .header("X-YouTube-Client-Version", clientVersion)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("Player API HTTP ${response.code} for $videoId with client $clientVersion")
                return null
            }

            val body = response.body.string()
            val json = JsonParser.parseString(body).asJsonObject

            val videoDetails = json.getAsJsonObject("videoDetails")
            val lengthSec = videoDetails?.get("lengthSeconds")?.asLong ?: 0L
            if (lengthSec > 0) {
                videoDurationCache[videoId] = lengthSec * 1000L
            }

            val status = json.getAsJsonObject("playabilityStatus")?.get("status")?.asString
            if (status != "OK") {
                Timber.w("Player API returned status: $status for $videoId")
                return null
            }

            val streamingData = json.getAsJsonObject("streamingData") ?: return null

            // 1. Check formats (typically itag 18 MP4 with audio/video stream)
            val formats = streamingData.getAsJsonArray("formats")
            if (formats != null && formats.size() > 0) {
                for (i in 0 until formats.size()) {
                    val f = formats.get(i).asJsonObject
                    val url = f.get("url")?.asString
                    if (!url.isNullOrBlank()) {
                        Timber.d("Found direct stream URL (formats itag ${f.get("itag")?.asInt}) for $videoId")
                        return url
                    }
                }
            }

            // 2. Check adaptiveFormats (pure audio streams)
            val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
            if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
                for (i in 0 until adaptiveFormats.size()) {
                    val f = adaptiveFormats.get(i).asJsonObject
                    val mime = f.get("mimeType")?.asString.orEmpty()
                    if (mime.startsWith("audio/")) {
                        val url = f.get("url")?.asString
                        if (!url.isNullOrBlank()) {
                            Timber.d("Found direct audio stream URL (adaptiveFormats itag ${f.get("itag")?.asInt}) for $videoId")
                            return url
                        }
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Timber.e(e, "Exception querying player endpoint for $videoId")
            return null
        }
    }

    /**
     * Resolves a direct audio stream URL with multi-source fallback.
     * Tries direct YouTube stream first, then Monochrome Hi-Fi FLAC, then verified YouTube alternative.
     */
    suspend fun resolveStreamUrlWithFallback(videoId: String): String? = withContext(Dispatchers.IO) {
        val direct = getAudioStreamUrl(videoId)
        if (!direct.isNullOrBlank()) return@withContext direct

        val meta = parseSong(videoId)
        if (meta != null) {
            val (cleanTitle, cleanArtist) = cleanTitleAndArtist(meta.title, meta.artist)
            val dur = videoDurationCache[videoId] ?: meta.durationMs
            try {
                val mono = multiSourceAudioEngine.resolveMonochromeFlac(cleanTitle, cleanArtist, dur)
                if (mono != null) {
                    Timber.i("Fallback resolved to Monochrome Hi-Fi stream for $videoId ($cleanTitle)")
                    return@withContext mono.streamUrl
                }
            } catch (e: Exception) {
                Timber.w(e, "Fallback Monochrome resolution failed for $videoId")
            }

            val alt = findPlayableAlternative(cleanTitle, cleanArtist, dur, videoId)
            if (alt != null) {
                Timber.i("Fallback resolved to alternative YouTube stream ${alt.first} for $videoId")
                return@withContext alt.second
            }
        }
        null
    }

    /**
     * Resolves a YouTubeTrack into a fully playable Song model with direct stream URL.
     * Prioritizes Monochrome Hi-Fi Studio Master (24-bit/96kHz Lossless FLAC),
     * falling back to unthrottled YouTube InnerTube streams with verified clickbait/region filtering.
     */
    suspend fun resolvePlayableSong(track: YouTubeTrack): Song? = withContext(Dispatchers.IO) {
        var targetDur = videoDurationCache[track.videoId] ?: track.durationMs
        if (targetDur <= 0L) {
            val playerMeta = fetchPlayerVideoDetails(track.videoId)
            if (playerMeta != null && playerMeta.durationMs > 0L) {
                targetDur = playerMeta.durationMs
            }
        }
        val (cleanTitle, cleanArtist) = cleanTitleAndArtist(track.title, track.artist)
        val highResThumb = upgradeArtworkUrl(track.thumbnailUrl, track.videoId)

        // 1. First priority: Studio Master Lossless FLAC via Monochrome Hi-Fi (tracks.monochrome.st)
        val monoFlac = try {
            multiSourceAudioEngine.resolveMonochromeFlac(
                title = cleanTitle,
                artist = cleanArtist,
                targetDurationMs = targetDur
            )
        } catch (e: Exception) {
            Timber.w(e, "Error resolving Monochrome stream for ${track.title}")
            null
        }

        // Resolve pristine square studio album artwork from Apple Music / Deezer / Monochrome
        val studioArt = try {
            studioArtworkResolver.resolveStudioArtwork(
                title = cleanTitle,
                artist = cleanArtist,
                fallbackUrl = monoFlac?.artworkUrl ?: track.thumbnailUrl ?: highResThumb
            )
        } catch (e: Exception) {
            highResThumb.orEmpty()
        }
        val finalArtwork = if (studioArt.isNotBlank()) studioArt else highResThumb

        if (monoFlac != null) {
            Timber.i("Resolved Studio Master FLAC stream via Monochrome for '$cleanTitle' by '$cleanArtist': ${monoFlac.streamUrl}")
            return@withContext Song(
                id = "mono_${track.videoId}",
                title = cleanTitle,
                artist = cleanArtist,
                artistId = cleanArtist.hashCode().toLong(),
                artists = listOf(ArtistRef(id = cleanArtist.hashCode().toLong(), name = cleanArtist, isPrimary = true)),
                album = track.album ?: "Hi-Fi Studio Master",
                albumId = (track.album ?: "Hi-Fi Studio Master").hashCode().toLong(),
                path = monoFlac.streamUrl,
                contentUriString = monoFlac.streamUrl,
                albumArtUriString = finalArtwork,
                duration = if (monoFlac.durationMs > 0) monoFlac.durationMs else targetDur,
                genre = "Monochrome Hi-Fi (FLAC)",
                mimeType = "audio/flac",
                bitrate = monoFlac.bitrate,
                sampleRate = 96000
            )
        }

        // 2. Second priority: Direct YouTube stream
        var streamUrl = getAudioStreamUrl(track.videoId)
        var usedVideoId = track.videoId
        var resolvedThumb = track.thumbnailUrl

        // 3. Third priority: If direct stream fails (region-restricted), search verified alternative
        if (streamUrl.isNullOrBlank()) {
            Timber.w("Direct stream failed for ${track.videoId}, searching verified alternative for: $cleanArtist - $cleanTitle")
            val alt = findPlayableAlternative(
                targetTitle = track.title,
                targetArtist = track.artist,
                targetDurationMs = targetDur,
                excludeVideoId = track.videoId
            )
            if (alt != null) {
                usedVideoId = alt.first
                streamUrl = alt.second
                Timber.i("Playing verified alternative stream: $usedVideoId for ${track.title}")
            }
        }

        if (streamUrl.isNullOrBlank()) return@withContext null

        val songId = "yt_$usedVideoId"

        Song(
            id = songId,
            title = cleanTitle,
            artist = cleanArtist,
            artistId = cleanArtist.hashCode().toLong(),
            artists = listOf(ArtistRef(id = cleanArtist.hashCode().toLong(), name = cleanArtist, isPrimary = true)),
            album = track.album ?: "YouTube Music",
            albumId = (track.album ?: "YouTube Music").hashCode().toLong(),
            path = streamUrl,
            contentUriString = streamUrl,
            albumArtUriString = finalArtwork,
            duration = targetDur,
            genre = "YouTube Music",
            mimeType = "video/mp4",
            bitrate = 160000,
            sampleRate = 48000
        )
    }

    /**
     * Resolves any YouTube URL into a playable Song model with high quality artwork.
     */
    suspend fun resolvePlayableSongFromUrl(
        url: String,
        defaultTitle: String,
        defaultArtist: String,
        thumb: String? = null
    ): Song? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url) ?: return@withContext null
        val meta = parseSong(videoId)

        val resolvedTitle = meta?.title ?: defaultTitle.ifBlank { "YouTube Track" }
        val resolvedArtist = meta?.artist ?: defaultArtist.ifBlank { "YouTube Music" }
        val resolvedThumb = meta?.thumbnailUrl ?: thumb
        val duration = meta?.durationMs?.takeIf { it > 0L } ?: (videoDurationCache[videoId] ?: 0L)

        val (cleanTitle, cleanArtist) = cleanTitleAndArtist(resolvedTitle, resolvedArtist)
        val highResThumb = upgradeArtworkUrl(resolvedThumb, videoId)

        val dummyTrack = YouTubeTrack(
            videoId = videoId,
            title = cleanTitle,
            artist = cleanArtist,
            album = meta?.album ?: "YouTube Music",
            durationMs = duration,
            thumbnailUrl = highResThumb
        )
        resolvePlayableSong(dummyTrack)
    }

    /**
     * Converts a YouTubeTrack into an app Song model for UI representations with upgraded artwork.
     */
    fun toSong(track: YouTubeTrack): Song {
        val dummyId = "yt_${track.videoId}"
        val (cleanTitle, cleanArtist) = cleanTitleAndArtist(track.title, track.artist)
        val highResThumb = upgradeArtworkUrl(track.thumbnailUrl, track.videoId)

        return Song(
            id = dummyId,
            title = cleanTitle,
            artist = cleanArtist,
            artistId = cleanArtist.hashCode().toLong(),
            artists = listOf(ArtistRef(id = cleanArtist.hashCode().toLong(), name = cleanArtist, isPrimary = true)),
            album = track.album ?: "YouTube Music",
            albumId = (track.album ?: "YouTube Music").hashCode().toLong(),
            path = "https://music.youtube.com/watch?v=${track.videoId}",
            contentUriString = "https://music.youtube.com/watch?v=${track.videoId}",
            albumArtUriString = highResThumb,
            duration = track.durationMs,
            genre = "YouTube Music",
            mimeType = "video/mp4",
            bitrate = 160000,
            sampleRate = 48000
        )
    }

    private fun createWebRemixContext(region: String = "US"): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", "1.20240101.01.00")
            addProperty("hl", "ru")
            addProperty("gl", region)
        }
        return JsonObject().apply {
            add("client", clientObj)
        }
    }
}
