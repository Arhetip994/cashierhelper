package com.unshoo.pixelmusic.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Quality tiers for lyrics cascade and source resolution.
 */
enum class LyricsTier(val rank: Int) {
    TIER_1_TTML_SUBPHRASES(4), // TTML / rich karaoke with subphrases (backing vocals)
    TIER_2_WORD_SYNC(3),        // Word / syllable karaoke sync without subphrases
    TIER_3_LINE_SYNC(2),        // Line-by-line sync
    TIER_4_PLAIN(1),            // Static unsynced plain text
    TIER_NONE(0)
}

/**
 * Data model for songs lyrics.
 *
 * @param plain List of unsynced plain-text lines.
 * @param synced List of synced lines with timestamps, words, and subphrases.
 * @param areFromRemote True if lyrics were fetched from a remote provider.
 * @param credits Songwriters and contributors.
 */
@Serializable
@Immutable
data class Lyrics(
    val plain: List<String>? = null,
    val synced: List<SyncedLine>? = null,
    val areFromRemote: Boolean = false,
    val credits: List<String>? = null
) {
    val hasWordSync: Boolean
        get() = synced?.any { !it.words.isNullOrEmpty() } == true

    val hasSubPhrases: Boolean
        get() = synced?.any { !it.subPhrases.isNullOrEmpty() || it.isBackground } == true

    val isAdvancedLyrics: Boolean
        get() = hasWordSync || hasSubPhrases

    fun resolveTier(): LyricsTier {
        if (synced.isNullOrEmpty()) {
            return if (!plain.isNullOrEmpty()) LyricsTier.TIER_4_PLAIN else LyricsTier.TIER_NONE
        }
        val wordSync = synced.any { !it.words.isNullOrEmpty() }
        val subPhrases = synced.any { !it.subPhrases.isNullOrEmpty() || it.isBackground }
        return when {
            wordSync && subPhrases -> LyricsTier.TIER_1_TTML_SUBPHRASES
            wordSync -> LyricsTier.TIER_2_WORD_SYNC
            else -> LyricsTier.TIER_3_LINE_SYNC
        }
    }
}

@Serializable
@Immutable
data class SyncedLine(
    val time: Int,
    val line: String,
    val words: List<SyncedWord>? = null, // Null if not a word-by-word synced lyric
    val translation: String? = null, // Translation text paired by identical timestamp
    val romanization: String? = null, // Romanization text paired by identical timestamp
    val isBackground: Boolean = false,
    val agent: String? = null,
    val endTime: Int? = null,
    val subPhrases: List<SubPhrase>? = null
)

@Serializable
@Immutable
data class SubPhrase(
    val startTime: Int,
    val endTime: Int,
    val text: String,
    val words: List<SyncedWord>? = null
)

@Serializable
@Immutable
data class SyncedWord(
    val time: Int,
    val word: String,
    val startsNewWord: Boolean = true,
    val endTime: Int? = null
)
