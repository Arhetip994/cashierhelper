package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.utils.LyricsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiSourceLyricsEngine @Inject constructor(
    private val lrcLibApiService: LrcLibApiService,
    private val deezerLyricsProvider: DeezerLyricsProvider,
    private val paxsenixLyricsProvider: PaxsenixLyricsProvider,
    private val netEaseLyricsProvider: NetEaseLyricsProvider,
    private val kugouLyricsProvider: KugouLyricsProvider
) {
    companion object {
        private const val TAG = "MultiSourceLyricsEngine"

        private val NOISE_REGEX = Regex(
            """\s*[\(\[][^\)\]]*\b(official|video|audio|lyrics?|visualizer|remaster(ed)?|hd|hq|4k|mv|feat|ft|prod|edit|mix|version|cover|клип|премьера)\b[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun cleanString(input: String): String {
        return NOISE_REGEX.replace(input, "").replace(Regex("""\s+"""), " ").trim()
    }

    fun calculateLyricsQuality(lyrics: Lyrics, sourceName: String): Int {
        val synced = lyrics.synced
        val plain = lyrics.plain

        if (synced.isNullOrEmpty() && plain.isNullOrEmpty()) return 0

        // If only plain text
        if (synced.isNullOrEmpty()) {
            return 10 + (plain?.size ?: 0).coerceAtMost(20)
        }

        // Line-synced base score
        var score = 50 + synced.size.coerceAtMost(40)

        // Syllable / word-by-word synced bonus (Apple Music / Paxsenix / Kugou word format)
        val hasWordTimings = synced.any { !it.words.isNullOrEmpty() }
        if (hasWordTimings) {
            score += 50
        }

        // Provider trustworthiness bonus
        when (sourceName.lowercase()) {
            "applemusic", "paxsenix" -> score += 20
            "deezer" -> score += 15
            "lrclib" -> score += 10
            "netease" -> score += 5
        }

        return score
    }

    /**
     * Executes progressive 2-phase search:
     * Phase 1: Immediately invokes [onProgressiveUpdate] as soon as the FASTEST valid provider responds.
     * Phase 2: In background, continues searching for a SUPERIOR quality version (e.g. word-by-word synced),
     * and if found, invokes [onProgressiveUpdate] again with the upgraded lyrics.
     */
    suspend fun fetchLyricsProgressive(
        song: Song,
        paxsenixApiKey: String? = null,
        onProgressiveUpdate: (Lyrics) -> Unit
    ): Lyrics? = coroutineScope {
        val rawTitle = song.title
        val rawArtist = song.displayArtist
        val cleanTitle = cleanString(rawTitle)
        val cleanArtist = cleanString(rawArtist)

        val durationSeconds = (song.duration / 1000L).toInt().coerceAtLeast(30)

        val channel = Channel<Triple<String, Lyrics, String>>(capacity = 6)
        val searchJobs = mutableListOf<Job>()

        // 1. LRCLIB job
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val lrcResponse = runCatching {
                        lrcLibApiService.getLyrics(
                            trackName = cleanTitle,
                            artistName = cleanArtist,
                            albumName = song.album.orEmpty(),
                            duration = durationSeconds
                        )
                    }.getOrNull() ?: runCatching {
                        lrcLibApiService.searchLyrics(query = "$cleanArtist $cleanTitle")?.firstOrNull()
                    }.getOrNull()

                    val rawLrc = lrcResponse?.syncedLyrics ?: lrcResponse?.plainLyrics
                    if (!rawLrc.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawLrc).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty() || !parsed.plain.isNullOrEmpty()) {
                            channel.send(Triple("LRCLIB", parsed, rawLrc))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 2. Deezer job (fast anonymous GraphQL)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawDeezer = deezerLyricsProvider.getLyrics(cleanTitle, cleanArtist)
                    if (!rawDeezer.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawDeezer).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty()) {
                            channel.send(Triple("Deezer", parsed, rawDeezer))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 3. Paxsenix Apple Music job (free & rich word-level / synced)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawApple = paxsenixLyricsProvider.getAppleMusicLyrics(cleanTitle, cleanArtist, apiKey = paxsenixApiKey)
                    if (!rawApple.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawApple).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty() || !parsed.plain.isNullOrEmpty()) {
                            channel.send(Triple("AppleMusic", parsed, rawApple))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 4. NetEase job
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawNetease = netEaseLyricsProvider.getLyrics(cleanTitle, cleanArtist)
                    if (!rawNetease.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawNetease).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty()) {
                            channel.send(Triple("NetEase", parsed, rawNetease))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 5. Kugou job
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawKugou = kugouLyricsProvider.getLyrics(cleanTitle, cleanArtist)
                    if (!rawKugou.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawKugou).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty()) {
                            channel.send(Triple("Kugou", parsed, rawKugou))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        var bestLyrics: Lyrics? = null
        var bestScore = -1
        var bestProvider = ""

        // Wait for fastest result with a reasonable timeout
        val fastResultTimeoutMs = 3500L
        val maxWaitTimeoutMs = 6000L
        val startTime = System.currentTimeMillis()

        // Wait for first response
        val firstResult = withTimeoutOrNull(fastResultTimeoutMs) {
            channel.receiveCatching().getOrNull()
        }

        if (firstResult != null) {
            val (provider, lyrics, _) = firstResult
            bestLyrics = lyrics
            bestProvider = provider
            bestScore = calculateLyricsQuality(lyrics, provider)
            Log.d(TAG, "Fastest lyrics from $provider (score=$bestScore). Emitting immediately!")
            onProgressiveUpdate(lyrics)

            // If already top tier (word-by-word synced or score >= 120), stop immediately!
            if (bestScore >= 120) {
                searchJobs.forEach { it.cancel() }
                channel.close()
                return@coroutineScope bestLyrics
            }
        }

        // Wait remaining time up to maxWaitTimeoutMs for possible superior version
        val remainingTime = (maxWaitTimeoutMs - (System.currentTimeMillis() - startTime)).coerceAtLeast(500L)
        withTimeoutOrNull(remainingTime) {
            while (searchJobs.any { it.isActive }) {
                val candidate = channel.receiveCatching().getOrNull() ?: break
                val (provider, lyrics, _) = candidate
                val score = calculateLyricsQuality(lyrics, provider)
                if (score > bestScore + 10) {
                    Log.d(TAG, "Superior lyrics found from $provider (score=$score vs $bestScore). Upgrading!")
                    bestLyrics = lyrics
                    bestProvider = provider
                    bestScore = score
                    onProgressiveUpdate(lyrics)

                    if (bestScore >= 120) {
                        break
                    }
                }
            }
        }

        searchJobs.forEach { it.cancel() }
        channel.close()
        bestLyrics
    }
}
