package com.unshoo.pixelmusic.data.network.lyrics

import android.util.Log
import android.util.LruCache
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.unshoo.pixelmusic.data.ai.AiOrchestrator
import com.unshoo.pixelmusic.data.ai.AiSystemPromptType
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.data.model.SyncedLine
import com.unshoo.pixelmusic.data.preferences.AiPreferencesRepository
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsTranslator @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val aiOrchestratorLazy: Lazy<AiOrchestrator>? = null,
    private val aiPreferencesRepository: AiPreferencesRepository? = null
) {
    companion object {
        private const val TAG = "LyricsTranslator"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val DELIMITER = " <br> "
    }

    private val translationCache = LruCache<String, Lyrics>(100)

    suspend fun translateLyrics(lyrics: Lyrics, targetLang: String = "ru", songId: String? = null): Lyrics = withContext(Dispatchers.IO) {
        val hasAi = try {
            aiPreferencesRepository?.hasConfiguredKey() == true
        } catch (_: Exception) {
            false
        }
        val cacheKey = if (songId != null) "${songId}_${targetLang}_${if (hasAi) "ai" else "google"}" else null
        if (cacheKey != null) {
            val cached = translationCache.get(cacheKey)
            if (cached != null) return@withContext cached
        }

        val synced = lyrics.synced
        val plain = lyrics.plain

        if (!synced.isNullOrEmpty()) {
            val linesToTranslate = synced.map { it.line }
            val translated = translateBatch(linesToTranslate, targetLang)
            val updatedSynced = synced.mapIndexed { index, item ->
                val tr = translated.getOrNull(index)
                if (!tr.isNullOrBlank() && tr.trim() != item.line.trim()) {
                    item.copy(translation = tr)
                } else {
                    item
                }
            }
            val result = lyrics.copy(synced = updatedSynced)
            if (cacheKey != null) translationCache.put(cacheKey, result)
            return@withContext result
        }

        if (!plain.isNullOrEmpty()) {
            val translated = translateBatch(plain, targetLang)
            val combined = plain.mapIndexed { index, orig ->
                val tr = translated.getOrNull(index)
                if (!tr.isNullOrBlank() && tr.trim() != orig.trim()) {
                    "$orig\n$tr"
                } else {
                    orig
                }
            }
            val result = lyrics.copy(plain = combined)
            if (cacheKey != null) translationCache.put(cacheKey, result)
            return@withContext result
        }

        lyrics
    }

    suspend fun translateToRussian(lyrics: Lyrics, cacheKey: String? = null): Lyrics {
        return translateLyrics(lyrics, "ru", cacheKey)
    }

    private suspend fun translateBatch(lines: List<String>, targetLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()

        if (aiPreferencesRepository?.hasConfiguredKey() == true && aiOrchestratorLazy != null) {
            val aiResult = translateWithAi(lines, targetLang)
            if (aiResult != null && aiResult.isNotEmpty()) {
                Log.d(TAG, "Lyrics successfully translated with AI")
                return aiResult
            }
            Log.d(TAG, "AI translation fallback to Google Translate")
        }

        return translateBatchWithGoogle(lines, targetLang)
    }

    private suspend fun translateWithAi(lines: List<String>, targetLang: String): List<String>? {
        return try {
            val nonBlankIndices = mutableListOf<Int>()
            val nonBlankLines = mutableListOf<String>()

            lines.forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    nonBlankIndices.add(idx)
                    nonBlankLines.add(trimmed)
                }
            }

            if (nonBlankLines.isEmpty()) return lines

            val targetLangName = when (targetLang.lowercase()) {
                "ru" -> "Russian"
                "en" -> "English"
                "es" -> "Spanish"
                "de" -> "German"
                "fr" -> "French"
                "it" -> "Italian"
                "uk" -> "Ukrainian"
                "kk" -> "Kazakh"
                "be" -> "Belarusian"
                "pl" -> "Polish"
                "zh" -> "Chinese"
                "ja" -> "Japanese"
                "ko" -> "Korean"
                "tr" -> "Turkish"
                else -> targetLang
            }

            val prompt = """
                Translate the following song lyrics lines into $targetLangName.
                Requirements:
                1. Make the translation artistic, poetic, emotionally evocative, and natural for song lyrics.
                2. Preserve the exact line structure: the input has lines separated by " <br> ".
                3. You MUST output each translated line separated by the exact same delimiter " <br> ".
                4. Do NOT output notes, introduction, markdown blocks, or greetings.
                
                Lines to translate:
                ${nonBlankLines.joinToString(DELIMITER)}
            """.trimIndent()

            val orchestrator = aiOrchestratorLazy?.get() ?: return null
            val raw = orchestrator.generateContent(
                prompt = prompt,
                type = AiSystemPromptType.LYRICS_TRANSLATION,
                temperature = 0.3f
            )

            if (raw.isBlank()) return null

            val parts = raw
                .replace("(?i)<br\\s*/?>".toRegex(), "~~~BR~~~")
                .split("~~~BR~~~")
                .map { it.trim().trim('"', '`', '\'') }

            if (parts.size >= (nonBlankLines.size * 0.75).toInt()) {
                val translatedMap = mutableMapOf<Int, String>()
                for (j in nonBlankLines.indices) {
                    val origIdx = nonBlankIndices[j]
                    val tr = parts.getOrNull(j)?.takeIf { it.isNotBlank() } ?: nonBlankLines[j]
                    translatedMap[origIdx] = tr
                }
                lines.mapIndexed { index, orig ->
                    translatedMap[index] ?: orig
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI lyrics translation error, falling back to Google: ${e.message}")
            null
        }
    }

    private fun translateBatchWithGoogle(lines: List<String>, targetLang: String): List<String> {
        val nonBlankIndices = mutableListOf<Int>()
        val nonBlankLines = mutableListOf<String>()

        lines.forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                nonBlankIndices.add(idx)
                nonBlankLines.add(trimmed)
            }
        }

        if (nonBlankLines.isEmpty()) return lines

        val translatedMap = mutableMapOf<Int, String>()
        val chunkSize = 25

        for (i in nonBlankLines.indices step chunkSize) {
            val chunkLines = nonBlankLines.subList(i, minOf(i + chunkSize, nonBlankLines.size))
            val chunkIndices = nonBlankIndices.subList(i, minOf(i + chunkSize, nonBlankIndices.size))

            val joined = chunkLines.joinToString(DELIMITER)
            val translatedJoined = translateSingleTextGoogle(joined, targetLang)

            val parts = translatedJoined
                .replace("(?i)<br\\s*/?>".toRegex(), "~~~BR~~~")
                .split("~~~BR~~~")
                .map { it.trim() }

            for (j in chunkLines.indices) {
                val origIdx = chunkIndices[j]
                val tr = parts.getOrNull(j)?.takeIf { it.isNotBlank() } ?: chunkLines[j]
                translatedMap[origIdx] = tr
            }
        }

        return lines.mapIndexed { index, orig ->
            translatedMap[index] ?: orig
        }
    }

    private fun translateSingleTextGoogle(text: String, targetLang: String): String {
        try {
            val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
            val url = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&tl=$targetLang&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return text
                val responseBody = response.body ?: return text
                val body = responseBody.string()
                val rootArray = gson.fromJson(body, JsonArray::class.java)
                val sentences = rootArray.get(0)?.asJsonArray ?: return text
                val sb = java.lang.StringBuilder()
                for (element in sentences) {
                    val pair = element.asJsonArray
                    if (pair.size() > 0) {
                        sb.append(pair.get(0).asString)
                    }
                }
                val translated = sb.toString()
                if (translated.isNotBlank()) return translated
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}")
        }
        return text
    }
}
