package com.unshoo.pixelmusic.data.model

enum class OnlineLyricsSource(
    val id: String,
    val displayName: String,
    val description: String,
    val badge: String
) {
    APPLE_MUSIC("applemusic", "Apple Music", "Syllable / TTML word-by-word synced", "Слоги / TTML"),
    MUSIXMATCH("musixmatch", "Musixmatch", "Richsync syllables & full lyrics", "Richsync / Слоги"),
    LRC_RED("lrcred", "LRC.red", "Enhanced LRC & karaoke syllables", "Караоке / Enhanced"),
    LRCLIB("lrclib", "LRCLIB", "Massive open database of synced lyrics", "Синхронизированные"),
    DEEZER("deezer", "Deezer", "Fast synced karaoke lyrics", "Караоке"),
    KUGOU("kugou", "Kugou", "Word-by-word syllable karaoke", "Пословное"),
    NETEASE("netease", "NetEase", "Cloud music synced lyrics", "Синхронизированные");

    companion object {
        fun fromId(id: String?): OnlineLyricsSource =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: APPLE_MUSIC

        val DEFAULT_ORDER = listOf(
            APPLE_MUSIC.id,
            MUSIXMATCH.id,
            LRC_RED.id,
            LRCLIB.id,
            DEEZER.id,
            KUGOU.id,
            NETEASE.id
        )
    }
}
