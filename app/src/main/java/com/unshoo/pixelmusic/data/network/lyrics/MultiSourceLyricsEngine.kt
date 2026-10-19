package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.data.model.LyricsTier
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
    private val lrcRedLyricsProvider: LrcRedLyricsProvider,
    private val musixmatchLyricsProvider: MusixmatchLyricsProvider,
    private val userPreferencesRepository: com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
) {
    companion object {
        private const val TAG = "MultiSourceLyricsEngine"
    }

    /**
     * Calculates lyrics score strictly enforcing Quality Tiers:
     * Tier 1 (TTML + Subphrases): 220+ pts
     * Tier 2 (Word/Syllable sync): 160+ pts
     * Tier 3 (Line-by-line sync): 80+ pts
     * Tier 4 (Plain text): 20+ pts
     */
    fun calculateLyricsQuality(
        lyrics: Lyrics,
        sourceName: String,
        priorityOrder: List<String> = emptyList()
    ): Int {
        val tier = lyrics.resolveTier()
        var baseScore = when (tier) {
            LyricsTier.TIER_1_TTML_SUBPHRASES -> 220
            LyricsTier.TIER_2_WORD_SYNC -> 160
            LyricsTier.TIER_3_LINE_SYNC -> 80
            LyricsTier.TIER_4_PLAIN -> 20
            LyricsTier.TIER_NONE -> 0
        }

        if (baseScore == 0) return 0

        // Small bonus for number of synced lines (completeness)
        val lineBonus = (lyrics.synced?.size ?: lyrics.plain?.size ?: 0).coerceAtMost(30)
        baseScore += lineBonus

        // User priority preference bonus
        val normalizedSource = sourceName.lowercase().replace(" ", "").replace("_", "")
        val priorityIndex = priorityOrder.indexOfFirst { priorityItem ->
            val normPriority = priorityItem.lowercase().replace(" ", "").replace("_", "")
            normalizedSource.contains(normPriority) || normPriority.contains(normalizedSource)
        }
        if (priorityIndex >= 0) {
            baseScore += when (priorityIndex) {
                0 -> 25
                1 -> 18
                2 -> 12
                3 -> 8
                else -> 4
            }
        }

        return baseScore
    }

    /**
     * Executes progressive 2-phase search:
     * Phase 1 (Fastest First): Immediately invokes [onProgressiveUpdate] as soon as the FASTEST valid
     * provider responds with any lyrics tier (0 latency before the user).
     * Phase 2 (Background Upgrade): Continues waiting for higher-tier sources (Tier 1 TTML or Tier 2 Word Sync)
     * and seamlessly upgrades lyrics model if a higher tier is found.
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

        val channel = Channel<Triple<String, Lyrics, String>>(capacity = 8)
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

        // 2. Musixmatch job (free desktop client API with richsync syllables)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val mxLyrics = musixmatchLyricsProvider.getLyrics(cleanTitle, cleanArtist, durationSeconds)
                    if (mxLyrics != null && (!mxLyrics.synced.isNullOrEmpty() || !mxLyrics.plain.isNullOrEmpty())) {
                        channel.send(Triple("Musixmatch", mxLyrics, ""))
                    }
                } catch (_: Exception) {}
            }
        )

        // 3. LRC.red job (Enhanced LRC & karaoke syllables)
        searchJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val rawLrcRed = lrcRedLyricsProvider.getLyrics(cleanTitle, cleanArtist, durationSeconds)
                    if (!rawLrcRed.isNullOrBlank()) {
                        val parsed = LyricsUtils.parseLyrics(rawLrcRed).copy(areFromRemote = true)
                        if (!parsed.synced.isNullOrEmpty() || !parsed.plain.isNullOrEmpty()) {
                            channel.send(Triple("LRC.red", parsed, rawLrcRed))
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // 4. Deezer job (fast anonymous GraphQL)
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

        // 5. Paxsenix Apple Music job (free & rich word-level TTML)
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

        // 7. Kugou job (syllables and backing vocals)
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

        // 8. NetEase job
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

        val priorityOrder = runCatching {
            userPreferencesRepository.onlineLyricsSourcesPriorityFlow.firstOrNull()
        }.getOrNull() ?: com.unshoo.pixelmusic.data.model.OnlineLyricsSource.DEFAULT_ORDER

        var bestLyrics: Lyrics? = null
        var bestScore = -1
        var bestTier = LyricsTier.TIER_NONE
        var bestProvider = ""

        val fastResultTimeoutMs = 3000L
        val maxWaitTimeoutMs = 6000L
        val startTime = System.currentTimeMillis()

        // Phase 1: Wait for the first valid response (Fastest First)
        val firstResult = withTimeoutOrNull(fastResultTimeoutMs) {
            channel.receiveCatching().getOrNull()
        }

        if (firstResult != null) {
            val (provider, lyrics, _) = firstResult
            bestLyrics = lyrics
            bestProvider = provider
            bestTier = lyrics.resolveTier()
            bestScore = calculateLyricsQuality(lyrics, provider, priorityOrder)
            Log.d(TAG, "Fastest lyrics from $provider (tier=$bestTier, score=$bestScore). Emitting immediately!")
            onProgressiveUpdate(lyrics)
        }

        // Phase 2: In background, continue listening for superior tier or higher score
        val remainingTime = (maxWaitTimeoutMs - (System.currentTimeMillis() - startTime)).coerceAtLeast(1000L)
        withTimeoutOrNull(remainingTime) {
            while (searchJobs.any { it.isActive }) {
                val candidate = channel.receiveCatching().getOrNull() ?: break
                val (provider, lyrics, _) = candidate
                val candidateTier = lyrics.resolveTier()
                val score = calculateLyricsQuality(lyrics, provider, priorityOrder)

                val isHigherTier = candidateTier.rank > bestTier.rank
                val isBetterScoreInSameTier = candidateTier.rank == bestTier.rank && score > bestScore + 10

                if (isHigherTier || isBetterScoreInSameTier) {
                    Log.d(
                        TAG,
                        "Superior lyrics upgraded from $provider (tier=$candidateTier vs $bestTier, score=$score vs $bestScore). Upgrading hot!"
                    )
                    bestLyrics = lyrics
                    bestProvider = provider
                    bestTier = candidateTier
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
