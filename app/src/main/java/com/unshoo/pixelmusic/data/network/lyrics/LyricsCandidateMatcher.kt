package com.unshoo.pixelmusic.data.network.lyrics

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent song & lyrics candidate matcher.
 * Filters out wrong tracks, wrong artists, unwanted remixes/covers/lives,
 * and tracks with mismatched duration (tolerance +-15s).
 */
object LyricsCandidateMatcher {

    private val NOISE_TAGS_REGEX = Regex(
        """\s*[\(\[][^\)\]]*\b(official(\s+music)?\s+video|official\s+audio|lyrics?(\s+video)?|visualizer|remaster(ed)?|hd|hq|4k|mv|клип|премьера|full\s+song)\b[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    private val FEAT_REGEX = Regex(
        """\s*[\(\[][^\)\]]*\b(feat\.?|ft\.?|prod\.?|featuring)\b[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    private val TRACK_NUMBER_REGEX = Regex("""^\d{1,3}[\.\s\-–—]+\s*""")

    /**
     * Cleans song title:
     * - Removes leading track numbers ("01 - ", "1. ")
     * - Removes video noise ("(Official Video)", "[Visualizer]")
     * - Removes "Artist - " prefix if title is structured as "Artist - Title"
     */
    fun cleanTitle(rawTitle: String, artist: String? = null): String {
        var title = TRACK_NUMBER_REGEX.replace(rawTitle, "")
        title = NOISE_TAGS_REGEX.replace(title, "")
        title = FEAT_REGEX.replace(title, "")

        // Handle "Artist - Title"
        val dashIndex = title.indexOfAny(listOf(" - ", " – ", " — "))
        if (dashIndex != -1) {
            val prefix = title.substring(0, dashIndex).trim()
            val suffix = title.substring(dashIndex + 3).trim()
            if (!artist.isNullOrBlank()) {
                val normPrefix = normalize(prefix)
                val normArtist = normalize(artist)
                if (normPrefix.contains(normArtist) || normArtist.contains(normPrefix)) {
                    title = suffix
                }
            } else if (suffix.isNotBlank()) {
                // If artist is unknown, the part after dash is likely the track name
                title = suffix
            }
        }

        return title.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Extracts the primary artist name if multiple artists are joined by commas, &, ft, etc.
     */
    fun extractPrimaryArtist(rawArtist: String): String {
        val cleaned = rawArtist
            .split(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring|with|x|vs\.?|&)\s+|,\s*|;\s*|/\s*"""))
            .firstOrNull()
            ?.trim()
            .orEmpty()
        return if (cleaned.isNotBlank()) cleaned else rawArtist.trim()
    }

    /**
     * Normalizes text for comparison: lowercases, removes diacritics/accents and punctuation.
     */
    fun normalize(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return nfd.replace(Regex("""\p{M}"""), "")
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Scores how well a candidate matches the target track.
     * @return Score between 0.0 (completely rejected) and 1.0 (perfect match).
     * Candidates below 0.65 should be discarded.
     */
    fun scoreCandidate(
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Int,
        candidateTitle: String,
        candidateArtist: String?,
        candidateDurationSec: Int = -1
    ): Double {
        val normTargetTitle = normalize(cleanTitle(targetTitle, targetArtist))
        val normCandTitle = normalize(cleanTitle(candidateTitle, candidateArtist))

        if (normTargetTitle.isBlank() || normCandTitle.isBlank()) return 0.0

        // 1. Duration Check
        var durationScore = 0.85
        if (targetDurationSec > 0 && candidateDurationSec > 0) {
            val diff = abs(targetDurationSec - candidateDurationSec)
            if (diff > 18) {
                // Reject tracks with duration difference > 18s (different song, remix, or extended cut)
                return 0.0
            }
            durationScore = when {
                diff <= 3 -> 1.0
                diff <= 7 -> 0.95
                diff <= 12 -> 0.85
                else -> 0.70
            }
        }

        // 2. Unwanted Version / Tag Penalty
        val targetLower = targetTitle.lowercase()
        val candLower = candidateTitle.lowercase()

        val isTargetRemix = targetLower.contains("remix") || targetLower.contains(" mix")
        val isCandRemix = candLower.contains("remix") || candLower.contains(" mix")
        if (!isTargetRemix && isCandRemix) return 0.0

        val isTargetLive = targetLower.contains("live") || targetLower.contains("concert")
        val isCandLive = candLower.contains("live") || candLower.contains("concert") || candLower.contains("acapella")
        if (!isTargetLive && isCandLive) return 0.0

        val isTargetCover = targetLower.contains("cover") || targetLower.contains("tribute") || targetLower.contains("karaoke")
        val isCandCover = candLower.contains("cover") || candLower.contains("tribute") || candLower.contains("karaoke")
        if (!isTargetCover && isCandCover) return 0.0

        val isTargetSlowed = targetLower.contains("slowed") || targetLower.contains("speed") || targetLower.contains("nightcore")
        val isCandSlowed = candLower.contains("slowed") || candLower.contains("speed up") || candLower.contains("nightcore")
        if (!isTargetSlowed && isCandSlowed) return 0.0

        // 3. Title Similarity
        val titleSim = calculateStringSimilarity(normTargetTitle, normCandTitle)
        if (titleSim < 0.50) {
            // Check if one title contains the other as whole words
            val isContained = normTargetTitle.contains(normCandTitle) || normCandTitle.contains(normTargetTitle)
            if (!isContained) return 0.0
        }

        // 4. Artist Similarity
        val normTargetArtist = normalize(targetArtist)
        val primaryTargetArtist = normalize(extractPrimaryArtist(targetArtist))
        val normCandArtist = candidateArtist?.let { normalize(it) }.orEmpty()

        var artistSim = 0.5 // neutral if artist not available
        if (normTargetArtist.isNotBlank()) {
            if (normCandArtist.isNotBlank()) {
                val primaryCandArtist = normalize(extractPrimaryArtist(candidateArtist ?: ""))
                artistSim = when {
                    normTargetArtist == normCandArtist -> 1.0
                    primaryTargetArtist.isNotBlank() && (normCandArtist.contains(primaryTargetArtist) || primaryTargetArtist.contains(primaryCandArtist)) -> 0.95
                    normCandArtist.contains(normTargetArtist) || normTargetArtist.contains(normCandArtist) -> 0.90
                    calculateStringSimilarity(normTargetArtist, normCandArtist) >= 0.7 -> 0.85
                    // Maybe artist is in candidate title (very common in Kugou / NetEase)
                    normCandTitle.contains(primaryTargetArtist) || (primaryTargetArtist.length >= 4 && normCandTitle.contains(primaryTargetArtist)) -> 0.80
                    else -> 0.0 // Artist completely mismatched!
                }
            } else {
                // Candidate has no explicit artist field: check if candidate title includes target artist
                artistSim = if (primaryTargetArtist.isNotBlank() && normCandTitle.contains(primaryTargetArtist)) {
                    0.80
                } else {
                    0.40
                }
            }
        }

        if (artistSim == 0.0 && titleSim < 0.92) {
            return 0.0 // Reject completely if different artist and title is not virtually identical
        }

        // Final Composite Score
        val composite = (titleSim * 0.45) + (artistSim * 0.30) + (durationScore * 0.25)
        return composite
    }

    /**
     * Calculates Jaccard + Levenshtein hybrid similarity between two normalized strings.
     */
    private fun calculateStringSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        if (s1.contains(s2) || s2.contains(s1)) {
            val ratio = min(s1.length, s2.length).toDouble() / max(s1.length, s2.length)
            return max(ratio, 0.82)
        }

        // Token Jaccard
        val words1 = s1.split(" ").filter { it.isNotBlank() }.toSet()
        val words2 = s2.split(" ").filter { it.isNotBlank() }.toSet()
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        val jaccard = if (union > 0) intersection.toDouble() / union else 0.0

        // Levenshtein
        val maxLen = max(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        val levSim = 1.0 - (distance.toDouble() / maxLen)

        return (jaccard * 0.5) + (levSim * 0.5)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
