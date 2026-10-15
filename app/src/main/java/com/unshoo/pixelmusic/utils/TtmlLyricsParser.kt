package com.unshoo.pixelmusic.utils

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

internal object TtmlLyricsParser {
    private const val MAX_TTML_PARAGRAPHS = 5_000

    data class ParsedLine(
        val text: String,
        val startTimeMs: Int,
        val words: List<ParsedWord>,
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList()
    )

    data class ParsedWord(
        val text: String,
        val startTimeMs: Int,
        val endTimeMs: Int
    )

    private data class SpanInfo(
        val text: String,
        val startTimeMs: Int,
        val endTimeMs: Int,
        val hasTrailingSpace: Boolean
    )

    fun parseToEnhancedLrc(ttmlText: String): String? {
        return runCatching {
            val normalizedTtml = normalizeTtmlDocument(ttmlText)
            if (normalizedTtml.isBlank()) {
                return null
            }

            val builder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setExpandEntityReferences(false)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }.newDocumentBuilder().apply {
                setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
            }

            val document = builder.parse(InputSource(StringReader(normalizedTtml)))
            val paragraphNodes = document.getElementsByTagNameNS("*", "p")
            if (paragraphNodes.length == 0 || paragraphNodes.length > MAX_TTML_PARAGRAPHS) {
                return null
            }

            val parsedLines = mutableListOf<ParsedLine>()

            for (index in 0 until paragraphNodes.length) {
                val paragraph = paragraphNodes.item(index) as? Element ?: continue
                val beginMs = resolveParagraphStartMs(paragraph) ?: continue
                val agent = getAttributeByLocalName(paragraph, "agent").ifEmpty { null }

                val spanInfos = mutableListOf<SpanInfo>()
                val backgroundLines = mutableListOf<ParsedLine>()

                val childNodes = paragraph.childNodes
                for (j in 0 until childNodes.length) {
                    val node = childNodes.item(j)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        val span = node as? Element
                        if (span != null && localNameOf(span) == "span") {
                            val role = getAttributeByLocalName(span, "role")
                            when (role) {
                                "x-bg" -> {
                                    val bgLine = parseBackgroundSpan(span, beginMs)
                                    if (bgLine != null) {
                                        backgroundLines.add(bgLine)
                                    }
                                }
                                "x-translation", "x-roman" -> {
                                    // Skip translation/romanization spans from polluting main lyric line
                                }
                                else -> {
                                    val wordBegin = span.getAttribute("begin")
                                    val wordEnd = span.getAttribute("end")
                                    val wordText = sanitizeTextFragment(span.textContent.orEmpty()).trim()

                                    val wordBeginMs = parseTimeExpression(wordBegin)
                                    val wordEndMs = parseTimeExpression(wordEnd)

                                    if (wordText.isNotEmpty() && wordBeginMs != null) {
                                        val nextSibling = node.nextSibling
                                        val hasTrailingSpace = isWordBoundary(nextSibling)
                                        spanInfos.add(
                                            SpanInfo(
                                                text = wordText,
                                                startTimeMs = wordBeginMs,
                                                endTimeMs = wordEndMs ?: (wordBeginMs + 300),
                                                hasTrailingSpace = hasTrailingSpace
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val words = mergeSpansIntoWords(spanInfos)
                val lineText = words.joinToString(" ") { it.text }

                val finalText = if (lineText.isEmpty()) {
                    getDirectTextContent(paragraph).trim()
                } else {
                    lineText
                }

                if (finalText.isNotEmpty()) {
                    parsedLines.add(
                        ParsedLine(
                            text = finalText,
                            startTimeMs = beginMs,
                            words = words,
                            agent = agent,
                            isBackground = false,
                            backgroundLines = backgroundLines
                        )
                    )
                }
            }

            if (parsedLines.isEmpty()) return null

            toLrcString(parsedLines)
        }.getOrNull()
    }

    private fun parseBackgroundSpan(span: Element, parentStartTimeMs: Int): ParsedLine? {
        val bgBegin = span.getAttribute("begin")
        val bgEnd = span.getAttribute("end")
        val bgStartTimeMs = parseTimeExpression(bgBegin) ?: parentStartTimeMs

        val spanInfos = mutableListOf<SpanInfo>()
        val childNodes = span.childNodes

        for (j in 0 until childNodes.length) {
            val node = childNodes.item(j)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val innerSpan = node as? Element
                if (innerSpan != null && localNameOf(innerSpan) == "span") {
                    val role = getAttributeByLocalName(innerSpan, "role")
                    if (role == "x-translation" || role == "x-roman") continue

                    val wordBegin = innerSpan.getAttribute("begin")
                    val wordEnd = innerSpan.getAttribute("end")
                    val wordText = sanitizeTextFragment(innerSpan.textContent.orEmpty()).replace(Regex("""[\(\)\[\]（）【】〔〕《》]"""), "").trim()

                    val wordBeginMs = parseTimeExpression(wordBegin)
                    val wordEndMs = parseTimeExpression(wordEnd)

                    if (wordText.isNotEmpty() && wordBeginMs != null) {
                        val nextSibling = node.nextSibling
                        val hasTrailingSpace = isWordBoundary(nextSibling)
                        spanInfos.add(
                            SpanInfo(
                                text = wordText,
                                startTimeMs = wordBeginMs,
                                endTimeMs = wordEndMs ?: (wordBeginMs + 300),
                                hasTrailingSpace = hasTrailingSpace
                            )
                        )
                    }
                }
            }
        }

        val words = mergeSpansIntoWords(spanInfos).map {
            it.copy(text = LyricsUtils.cleanLyricBrackets(it.text.replace(Regex("""[\(\)\[\]（）【】〔〕《》]"""), "")))
        }.filter { it.text.isNotEmpty() }
        val lineText = words.joinToString(" ") { it.text }

        val rawContent = if (lineText.isEmpty()) getDirectTextContent(span).trim() else lineText
        val finalText = LyricsUtils.cleanLyricBrackets(rawContent)

        return if (finalText.isNotEmpty()) {
            ParsedLine(
                text = finalText,
                startTimeMs = bgStartTimeMs,
                words = words,
                agent = null,
                isBackground = true,
                backgroundLines = emptyList()
            )
        } else null
    }

    private fun getDirectTextContent(element: Element): String {
        val sb = StringBuilder()
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.TEXT_NODE) {
                sb.append(node.textContent)
            } else if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as? Element
                val role = el?.let { getAttributeByLocalName(it, "role") } ?: ""
                if (role != "x-bg" && role != "x-translation" && role != "x-roman") {
                    if (el != null && localNameOf(el) == "span") {
                        sb.append(el.textContent ?: "")
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun isWordBoundary(node: Node?): Boolean {
        if (node == null || node.nodeType != Node.TEXT_NODE) return false
        val between = node.textContent ?: return false
        if (between.isEmpty()) return false
        if (between.contains('\n') || between.contains('\r')) return false
        return between.any { it == ' ' || it == '\t' || it == '\u00A0' }
    }

    private fun mergeSpansIntoWords(spanInfos: List<SpanInfo>): List<ParsedWord> {
        if (spanInfos.isEmpty()) return emptyList()

        val words = mutableListOf<ParsedWord>()
        var currentText = StringBuilder()
        var currentStartTime = spanInfos[0].startTimeMs
        var currentEndTime = spanInfos[0].endTimeMs

        for ((index, span) in spanInfos.withIndex()) {
            if (index == 0) {
                currentText.append(span.text)
                currentStartTime = span.startTimeMs
                currentEndTime = span.endTimeMs
            } else {
                val prevSpan = spanInfos[index - 1]
                if (prevSpan.hasTrailingSpace) {
                    if (currentText.isNotEmpty()) {
                        words.add(
                            ParsedWord(
                                text = currentText.toString().trim(),
                                startTimeMs = currentStartTime,
                                endTimeMs = currentEndTime
                            )
                        )
                    }
                    currentText = StringBuilder(span.text)
                    currentStartTime = span.startTimeMs
                    currentEndTime = span.endTimeMs
                } else {
                    currentText.append(span.text)
                    currentEndTime = span.endTimeMs
                }
            }
        }

        if (currentText.isNotEmpty()) {
            words.add(
                ParsedWord(
                    text = currentText.toString().trim(),
                    startTimeMs = currentStartTime,
                    endTimeMs = currentEndTime
                )
            )
        }

        return words
    }

    private fun toLrcString(lines: List<ParsedLine>): String {
        return buildString {
            lines.sortedBy { it.startTimeMs }.forEach { line ->
                val timeStr = formatLrcTimestamp(line.startTimeMs)
                val agentPrefix = if (!line.agent.isNullOrEmpty()) "{agent:${line.agent}}" else ""
                val bgPrefix = if (line.isBackground) "{bg}" else ""

                // Format line with word-by-word timestamp tags if available
                if (line.words.isNotEmpty()) {
                    val wordTaggedLine = line.words.joinToString(" ") { word ->
                        "<${formatLrcTimestamp(word.startTimeMs)}>${word.text}"
                    }
                    appendLine("[$timeStr]$bgPrefix$agentPrefix$wordTaggedLine")

                    // Also output pipe-delimited words metadata for rich syllable sync engines
                    val wordsData = line.words.joinToString("|") { word ->
                        val startSec = word.startTimeMs / 1000.0
                        val endSec = word.endTimeMs / 1000.0
                        "${word.text}:$startSec:$endSec"
                    }
                    appendLine("<$wordsData>")
                } else {
                    appendLine("[$timeStr]$bgPrefix$agentPrefix${line.text}")
                }

                // Append any discrete background lines
                line.backgroundLines.sortedBy { it.startTimeMs }.forEach { bgLine ->
                    val bgTimeStr = formatLrcTimestamp(bgLine.startTimeMs)
                    if (bgLine.words.isNotEmpty()) {
                        val wordTaggedBg = bgLine.words.joinToString(" ") { word ->
                            "<${formatLrcTimestamp(word.startTimeMs)}>${word.text}"
                        }
                        appendLine("[$bgTimeStr]{bg}$wordTaggedBg")

                        val bgWordsData = bgLine.words.joinToString("|") { word ->
                            val startSec = word.startTimeMs / 1000.0
                            val endSec = word.endTimeMs / 1000.0
                            "${word.text}:$startSec:$endSec"
                        }
                        appendLine("<$bgWordsData>")
                    } else {
                        appendLine("[$bgTimeStr]{bg}${LyricsUtils.cleanLyricBrackets(bgLine.text)}")
                    }
                }
            }
        }.trim()
    }

    private fun getAttributeByLocalName(element: Element, localName: String): String {
        val nsValue = element.getAttributeNS("http://www.w3.org/ns/ttml#metadata", localName)
        if (nsValue.isNotEmpty()) return nsValue

        val prefixedValue = element.getAttribute("ttm:$localName")
        if (prefixedValue.isNotEmpty()) return prefixedValue

        val directValue = element.getAttribute(localName)
        if (directValue.isNotEmpty()) return directValue

        val attrs = element.attributes
        if (attrs != null) {
            for (i in 0 until attrs.length) {
                val attr = attrs.item(i)
                val attrName = attr.nodeName ?: continue
                if (attrName == localName || attrName.endsWith(":$localName")) {
                    return attr.nodeValue ?: ""
                }
            }
        }
        return ""
    }

    private fun normalizeTtmlDocument(raw: String): String {
        val withoutLeadingNoise = raw.trimStart { char ->
            char.isWhitespace() ||
                char == '\uFEFF' ||
                Character.getType(char).toByte() == Character.FORMAT
        }

        return if (withoutLeadingNoise.startsWith("<?xml", ignoreCase = true)) {
            withoutLeadingNoise.substringAfter("?>", missingDelimiterValue = withoutLeadingNoise).trimStart()
        } else {
            withoutLeadingNoise
        }
    }

    private fun resolveParagraphStartMs(paragraph: Element): Int? {
        parseTimeExpression(paragraph.getAttribute("begin"))?.let { return it }

        val spans = paragraph.getElementsByTagNameNS("*", "span")
        for (index in 0 until spans.length) {
            val span = spans.item(index) as? Element ?: continue
            parseTimeExpression(span.getAttribute("begin"))?.let { return it }
        }
        return null
    }

    private fun localNameOf(element: Element): String {
        return element.localName?.lowercase()
            ?: element.tagName.substringAfterLast(':').lowercase()
    }

    private fun sanitizeTextFragment(raw: String): String {
        if (raw.isEmpty()) return raw

        val cleaned = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filterNot { char ->
                Character.getType(char).toByte() == Character.FORMAT ||
                    (Character.isISOControl(char) && char != '\n' && char != '\t')
            }

        if (cleaned.isEmpty()) return ""
        if (cleaned.all { it.isWhitespace() }) {
            return if (cleaned.any { it == '\n' || it == '\t' }) "" else " "
        }

        return cleaned.replace(Regex("\\s+"), " ")
    }

    private fun parseTimeExpression(value: String?): Int? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null

        if (normalized.endsWith("s", ignoreCase = true)) {
            val seconds = normalized.removeSuffix("s").toDoubleOrNull() ?: return null
            return (seconds * 1000).roundToInt()
        }

        normalized.toDoubleOrNull()?.let { seconds ->
            return (seconds * 1000).roundToInt()
        }

        val parts = normalized.split(':')
        return when (parts.size) {
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val (seconds, millis) = parseSecondsWithFraction(parts[1]) ?: return null
                (minutes * 60_000L + seconds * 1_000L + millis).toInt()
            }
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val (seconds, millis) = parseSecondsWithFraction(parts[2]) ?: return null
                (hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis).toInt()
            }
            else -> null
        }
    }

    private fun parseSecondsWithFraction(value: String): Pair<Long, Long>? {
        val parts = value.split('.', limit = 2)
        val seconds = parts[0].toLongOrNull() ?: return null
        val fraction = parts.getOrNull(1).orEmpty()
        if (fraction.isEmpty()) return seconds to 0L

        val digits = fraction.take(3).padEnd(3, '0')
        val millis = digits.toLongOrNull() ?: return null
        return seconds to millis
    }

    private fun formatLrcTimestamp(timeMs: Int): String {
        val totalSeconds = timeMs / 1_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (timeMs % 1_000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }
}
