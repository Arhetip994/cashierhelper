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
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiSourceLyricsEngine @Inject constructor(
    private val lrcLibApiService: LrcLibApiService,
    private val deezerLyricsProvider: DeezerLyricsProvider,
    private val paxsenixLyricsProvider: PaxsenixLyricsProvider,
    private val betterLyricsProvider: BetterLyricsProvider,
    private val netEaseLyricsProvider: NetEaseLyricsProvider,
    private val kugouLyricsProvider: KugouLyricsProvider,
    private val userPreferencesRepository: com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
) {
    companion object {
        private const val TAG = "MultiSourceLyricsEngine"
    }

    private fun cleanString(input: String): String {
        return LyricsCandidateMatcher.cleanTitle(input)
    }

    fun calculateLyricsQuality(
        lyrics: Lyrics,
        sourceName: String,
        priorityOrder: List<String> = emptyList()
    ): Int {
        val synced = lyrics.synced
        val plain = lyrics.plain

        if (synced.isNullOrEmpty() && plain.isNullOrEmpty()) return 0

        // If only plain text
        if (synced.isNullOrEmpty()) {
            return 10 + (plain?.size ?: 0).coerceAtMost(20)
        }

        // Line-synced base score (60..100)
        var score = 60 + synced.size.coerceAtMost(40)

        // Syllable / word-by-word synced bonus (+60 points)
        val hasWordTimings = synced.any { !it.words.isNullOrEmpty() }
        if (hasWordTimings) {
            score += 60
        }

        // Priority bonus based on user priority order
        val normalizedSource = sourceName.lowercase().replace(" ", "").replace("_", "")
        val priorityIndex = priorityOrder.indexOfFirst { priorityItem ->
            val normPriority = priorityItem.lowercase().replace(" ", "").replace("_", "")
            normalizedSource.contains(normPriority) || normPriority.contains(normalizedSource)
        }
        if (priorityIndex >= 0) {
            score += when (priorityIndex) {
                0 -> 40
                1 -> 25
                2 -> 15
                3 -> 10
                else -> 5
            }
        } else {
            when (normalizedSource) {
                "betterlyrics", "applemusic", "paxsenix" -> score += 30
                "kugou" -> score += 20
                "deezer" -> score += 15
                "lrclib" -> score += 10
                "netease" -> score += 5
            }
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
        val rawArtist = song.displayArtist.ifBlank { song.artist }
        val cleanTitle = LyricsCandidateMatcher.cleanTitle(rawTitle, rawArtist)
        val primaryArtist = LyricsCandidateMatcher.extractPrimaryArtist(rawArtist)
        val cleanArtist = if (primaryArtist.isNotBlank()) primaryArtist else rawArtist

        val durationSeconds = (song.duration / 1000L).toInt().coerceAtLeast(15)

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
                    }.getOrNull() ?: runCatching<LrcLibResponse?> {
                        val searchResults = lrcLibApiService.searchLyrics(query = "$cleanArtist $cleanTitle")
                        if (searchResults != null && searchResults.isNotEmpty()) {
                            var bestScore = 0.0
                            var bestMatch: LrcLibResponse? = null
                            for (candidate in searchResults) {
                                val score = LyricsCandidateMatcher.scoreCandidate(
                                    targetTitle = cleanTitle,
                                    targetArtist = cleanArtist,
                                    targetDurationSec = durationSeconds,
                                    candidateTitle = candidate.name,
                                    candidateArtist = candidate.artistName,
                                    candidateDurationSec = candidate.duration.toInt()
                                )
                                if (score > bestScore && score >= 0.65) {
                                    bestScore = score
                                    bestMatch = candidate
                                }
                            }
                            bestMatch
                        } else null
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

        // 2. Deezer job (fast anonymous GraphQL with duration verification)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawDeezer = deezerLyricsProvider.getLyrics(cleanTitle, cleanArtist, durationSeconds)
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
                    val rawApple = paxsenixLyricsProvider.getAppleMusicLyrics(cleanTitle, cleanArtist, durationSeconds, apiKey = paxsenixApiKey)
                    if (!rawApple.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawApple).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty() || !parsed.plain.isNullOrEmpty()) {
                            channel.send(Triple("AppleMusic", parsed, rawApple))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 4. NetEase job (verified candidates)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawNetease = netEaseLyricsProvider.getLyrics(cleanTitle, cleanArtist, durationSeconds)
                    if (!rawNetease.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawNetease).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty()) {
                            channel.send(Triple("NetEase", parsed, rawNetease))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 5. Kugou job (verified candidates)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawKugou = kugouLyricsProvider.getLyrics(cleanTitle, cleanArtist, durationSeconds)
                    if (!rawKugou.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawKugou).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty()) {
                            channel.send(Triple("Kugou", parsed, rawKugou))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 6. BetterLyrics job (Apple Music TTML / word-level)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawBetter = betterLyricsProvider.getLyrics(cleanTitle, cleanArtist, durationSeconds, song.album)
                    if (!rawBetter.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawBetter).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty() || !parsed.plain.isNullOrEmpty()) {
                            channel.send(Triple("BetterLyrics", parsed, rawBetter))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        val priorityOrder = runCatching {
            userPreferencesRepository.onlineLyricsSourcesPriorityFlow.firstOrNull()
        }.getOrNull() ?: com.unshoo.pixelmusic.data.model.OnlineLyricsSource.DEFAULT_ORDER

        var bestLyrics: Lyrics? = null
        var bestScore = -1
        var bestProvider = ""

        // Wait for fastest result with a reasonable timeout (instant response phase)
        val fastResultTimeoutMs = 3500L
        val maxWaitTimeoutMs = 6000L
        val startTime = System.currentTimeMillis()

        // Phase 1: Wait for first response and emit immediately
        val firstResult = withTimeoutOrNull(fastResultTimeoutMs) {
            channel.receiveCatching().getOrNull()
        }

        if (firstResult != null) {
            val (provider, lyrics, _) = firstResult
            bestLyrics = lyrics
            bestProvider = provider
            bestScore = calculateLyricsQuality(lyrics, provider, priorityOrder)
            Log.d(TAG, "Fastest lyrics from $provider (score=$bestScore). Emitting immediately!")
            onProgressiveUpdate(lyrics)
        }

        // Phase 2: In background, continue listening for superior quality version according to user priority
        val remainingTime = (maxWaitTimeoutMs - (System.currentTimeMillis() - startTime)).coerceAtLeast(1000L)
        withTimeoutOrNull(remainingTime) {
            while (searchJobs.any { it.isActive }) {
                val candidate = channel.receiveCatching().getOrNull() ?: break
                val (provider, lyrics, _) = candidate
                val score = calculateLyricsQuality(lyrics, provider, priorityOrder)
                if (score > bestScore + 8) {
                    Log.d(TAG, "Superior lyrics found from $provider (score=$score vs $bestScore). Upgrading in background!")
                    bestLyrics = lyrics
                    bestProvider = provider
                    bestScore = score
                    onProgressiveUpdate(lyrics)
                }
            }
        }

        searchJobs.forEach { it.cancel() }
        channel.close()
        bestLyrics
    }
}
