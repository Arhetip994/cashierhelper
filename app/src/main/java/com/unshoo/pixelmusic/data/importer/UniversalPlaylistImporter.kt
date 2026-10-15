package com.unshoo.pixelmusic.data.importer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import com.unshoo.pixelmusic.data.database.youtube.AppDatabase
import com.unshoo.pixelmusic.data.model.youtube.PlaylistInfo
import com.unshoo.pixelmusic.data.model.youtube.PlaylistSongCrossRef
import com.unshoo.pixelmusic.data.model.youtube.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ImportedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val videoId: String? = null,
    val coverUrl: String? = null
)

data class ParsedPlaylist(
    val title: String,
    val coverUrl: String? = null,
    val tracks: List<ImportedTrack>,
    val source: String
)

@Singleton
class UniversalPlaylistImporter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        private val YANDEX_PLAYLIST_REGEX = Regex("music\\.yandex\\.(?:ru|com|by|kz)/users/([^/]+)/playlists/(\\d+)")
        private val YANDEX_ALBUM_REGEX = Regex("music\\.yandex\\.(?:ru|com|by|kz)/album/(\\d+)")
        private val SPOTIFY_REGEX = Regex("(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})")
        private val APPLE_REGEX = Regex("music\\.apple\\.com/[a-z]{2}/(playlist|album)/(?:[^/?#]+/)?(pl\\.[A-Za-z0-9-]+|\\d+)")
        private val DEEZER_REGEX = Regex("deezer\\.com/(?:[a-z]{2}/)?(playlist|album)/(\\d+)")
        private val YT_LIST_REGEX = Regex("[?&]list=([A-Za-z0-9_-]+)")
    }

    fun detectSource(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.contains("yandex", ignoreCase = true) -> "Яндекс Музыка"
            trimmed.contains("spotify", ignoreCase = true) -> "Spotify"
            trimmed.contains("music.apple.com", ignoreCase = true) -> "Apple Music"
            trimmed.contains("deezer", ignoreCase = true) -> "Deezer"
            trimmed.contains("youtube.com", ignoreCase = true) || trimmed.contains("youtu.be", ignoreCase = true) -> "YouTube Music"
            else -> null
        }
    }

    fun canHandle(url: String): Boolean {
        val trimmed = url.trim()
        return YANDEX_PLAYLIST_REGEX.containsMatchIn(trimmed) ||
                YANDEX_ALBUM_REGEX.containsMatchIn(trimmed) ||
                SPOTIFY_REGEX.containsMatchIn(trimmed) ||
                APPLE_REGEX.containsMatchIn(trimmed) ||
                DEEZER_REGEX.containsMatchIn(trimmed) ||
                YT_LIST_REGEX.containsMatchIn(trimmed) ||
                trimmed.contains("music.youtube.com") ||
                trimmed.contains("youtu.be") ||
                trimmed.contains("spotify") ||
                trimmed.contains("deezer") ||
                trimmed.contains("yandex")
    }

    suspend fun fetch(url: String): ParsedPlaylist = withContext(Dispatchers.IO) {
        val trimmed = resolveShortUrlIfNeeded(url.trim())
        when {
            YANDEX_PLAYLIST_REGEX.containsMatchIn(trimmed) -> fetchYandexPlaylist(trimmed)
            YANDEX_ALBUM_REGEX.containsMatchIn(trimmed) -> fetchYandexAlbum(trimmed)
            SPOTIFY_REGEX.containsMatchIn(trimmed) || trimmed.contains("spotify") -> fetchSpotify(trimmed)
            APPLE_REGEX.containsMatchIn(trimmed) -> fetchApple(trimmed)
            DEEZER_REGEX.containsMatchIn(trimmed) || trimmed.contains("deezer") -> fetchDeezer(trimmed)
            YT_LIST_REGEX.containsMatchIn(trimmed) || trimmed.contains("youtube.com") || trimmed.contains("youtu.be") -> fetchYouTube(trimmed)
            else -> throw IllegalArgumentException("Неподдерживаемая ссылка. Поддерживаются: Яндекс Музыка, Spotify, Apple Music, Deezer, YouTube.")
        }
    }

    private fun resolveShortUrlIfNeeded(url: String): String {
        if (url.contains("spotify.link") || url.contains("deezer.page.link") || url.contains("youtu.be")) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                okHttpClient.newCall(req).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    if (finalUrl.isNotBlank()) return finalUrl
                }
            } catch (e: Exception) {
                Timber.w(e, "Error resolving short link: $url")
            }
        }
        return url
    }

    fun parseCsv(text: String, fallbackTitle: String = "Импортированный плейлист"): ParsedPlaylist {
        val rows = parseCsvRows(text).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ParsedPlaylist(title = fallbackTitle, tracks = emptyList(), source = "CSV")

        val header = rows.first().map { normalizeCsvHeader(it) }
        fun column(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
            header.indexOf(normalizeCsvHeader(name)).takeIf { it >= 0 }
        } ?: -1

        val titleIdx = column("Track name", "Track", "Title", "Song", "Song name", "Name", "Название", "Трек")
        val artistIdx = column("Artist name", "Artist name(s)", "Artist", "Artists", "Artist names", "Исполнитель", "Артист")
        val albumIdx = column("Album", "Album name", "Album title", "Альбом")
        val playlistIdx = column("Playlist name", "Playlist", "Плейлист")
        val typeIdx = column("Type", "Тип")
        val durationMsIdx = column("Duration (ms)", "Duration ms", "Track duration (ms)", "Длительность (мс)")
        val durationIdx = column("Duration", "Length", "Time", "Длительность")
        val videoIdIdx = column("Video ID", "YouTube ID", "VideoId")
        val thumbnailIdx = column("Thumbnail", "Thumbnail URL", "Cover")

        if (titleIdx < 0) return ParsedPlaylist(title = fallbackTitle, tracks = emptyList(), source = "CSV")

        var detectedPlaylistName = fallbackTitle
        val tracks = mutableListOf<ImportedTrack>()

        for (row in rows.drop(1)) {
            fun cell(idx: Int): String? = idx.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }

            val type = cell(typeIdx)?.lowercase()
            if (type != null && type != "playlist" && type != "track" && type != "song" && type != "favorite" && type != "liked") continue

            val pName = cell(playlistIdx)
            if (!pName.isNullOrBlank() && detectedPlaylistName == fallbackTitle) {
                detectedPlaylistName = pName
            }

            val title = cell(titleIdx) ?: continue
            val artist = cell(artistIdx).orEmpty()
            val videoId = cell(videoIdIdx)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }

            if (artist.isEmpty() && videoId == null) continue

            val durMs = cell(durationMsIdx)?.toLongOrNull() ?: cell(durationIdx)?.let { parseDurationStringMs(it) }

            tracks.add(
                ImportedTrack(
                    title = title,
                    artist = artist,
                    album = cell(albumIdx),
                    durationMs = durMs,
                    videoId = videoId,
                    coverUrl = cell(thumbnailIdx)
                )
            )
        }

        return ParsedPlaylist(
            title = detectedPlaylistName,
            tracks = tracks,
            source = "CSV"
        )
    }

    private fun normalizeCsvHeader(name: String): String =
        name.removePrefix("\uFEFF").trim().lowercase().replace(Regex("[\\s_]+"), " ")

    private fun parseDurationStringMs(text: String): Long? {
        val parts = text.split(':').map { it.trim().toLongOrNull() ?: return null }
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return (seconds * 1000).takeIf { it > 0 }
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val content = text.removePrefix("\uFEFF")
        val headerLine = content.substringBefore('\n')
        val delimiter = if (headerLine.count { it == ';' } > headerLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < content.length) {
            val c = content[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < content.length && content[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delimiter -> endField()
                    '\r' -> {}
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Ошибка запроса (${response.code}): $url")
            }
            return response.body.string()
        }
    }

    // --- YANDEX MUSIC ---
    private fun fetchYandexPlaylist(url: String): ParsedPlaylist {
        val match = YANDEX_PLAYLIST_REGEX.find(url)
            ?: throw IllegalArgumentException("Неверная ссылка на плейлист Яндекс Музыки")
        val user = match.groupValues[1]
        val kind = match.groupValues[2]

        val jsonStr = httpGet("https://api.music.yandex.net/users/$user/playlists/$kind")
        val root = gson.fromJson(jsonStr, JsonObject::class.java)
        val result = root.getAsJsonObject("result")
            ?: throw IllegalStateException("Плейлист Яндекс Музыки приватный или не найден")

        val title = result.get("title")?.asString ?: "Плейлист Яндекс Музыки"
        val coverUri = result.getAsJsonObject("cover")?.get("uri")?.asString
        val coverUrl = coverUri?.let { "https://" + it.replace("%%", "400x400") }

        val tracksArray = result.getAsJsonArray("tracks") ?: JsonArray()
        val tracks = mutableListOf<ImportedTrack>()

        for (elem in tracksArray) {
            val trackObj = elem.asJsonObject.getAsJsonObject("track") ?: continue
            val trackTitle = trackObj.get("title")?.asString ?: continue
            val artists = trackObj.getAsJsonArray("artists")?.mapNotNull {
                it.asJsonObject.get("name")?.asString
            }?.joinToString(", ") ?: ""
            val durationMs = trackObj.get("durationMs")?.asLong
            val albumsArray = trackObj.getAsJsonArray("albums")
            val albumObj = if (albumsArray != null && albumsArray.size() > 0) albumsArray[0].asJsonObject else null
            val albumName = albumObj?.get("title")?.asString
            val trackCover = albumObj?.get("coverUri")?.asString?.let { "https://" + it.replace("%%", "400x400") }

            tracks.add(
                ImportedTrack(
                    title = trackTitle,
                    artist = artists,
                    album = albumName,
                    durationMs = durationMs,
                    coverUrl = trackCover ?: coverUrl
                )
            )
        }

        return ParsedPlaylist(
            title = title,
            coverUrl = coverUrl,
            tracks = tracks,
            source = "Yandex Music"
        )
    }

    private fun fetchYandexAlbum(url: String): ParsedPlaylist {
        val match = YANDEX_ALBUM_REGEX.find(url)
            ?: throw IllegalArgumentException("Неверная ссылка на альбом Яндекс Музыки")
        val albumId = match.groupValues[1]

        val jsonStr = httpGet("https://api.music.yandex.net/albums/$albumId/with-tracks")
        val root = gson.fromJson(jsonStr, JsonObject::class.java)
        val result = root.getAsJsonObject("result")
            ?: throw IllegalStateException("Альбом Яндекс Музыки не найден")

        val title = result.get("title")?.asString ?: "Альбом Яндекс Музыки"
        val coverUri = result.get("coverUri")?.asString
        val coverUrl = coverUri?.let { "https://" + it.replace("%%", "400x400") }
        val artists = result.getAsJsonArray("artists")?.mapNotNull {
            it.asJsonObject.get("name")?.asString
        }?.joinToString(", ") ?: ""

        val tracks = mutableListOf<ImportedTrack>()
        val volumes = result.getAsJsonArray("volumes") ?: JsonArray()
        for (vol in volumes) {
            for (trackElem in vol.asJsonArray) {
                val t = trackElem.asJsonObject
                val trackTitle = t.get("title")?.asString ?: continue
                val trackArtists = t.getAsJsonArray("artists")?.mapNotNull {
                    it.asJsonObject.get("name")?.asString
                }?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: artists
                val dur = t.get("durationMs")?.asLong

                tracks.add(
                    ImportedTrack(
                        title = trackTitle,
                        artist = trackArtists,
                        album = title,
                        durationMs = dur,
                        coverUrl = coverUrl
                    )
                )
            }
        }

        return ParsedPlaylist(
            title = title,
            coverUrl = coverUrl,
            tracks = tracks,
            source = "Yandex Music"
        )
    }

    // --- SPOTIFY ---
    private fun fetchSpotify(url: String): ParsedPlaylist {
        val match = SPOTIFY_REGEX.find(url)
            ?: throw IllegalArgumentException("Неверная ссылка Spotify")
        val (type, id) = match.destructured

        val html = httpGet("https://open.spotify.com/embed/$type/$id")
        val scriptMatch = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: throw IllegalStateException("Не удалось прочитать плейлист Spotify")

        val jsonStr = scriptMatch.groupValues[1]
        val root = gson.fromJson(jsonStr, JsonObject::class.java)
        val entity = root.getAsJsonObject("props")
            ?.getAsJsonObject("pageProps")
            ?.getAsJsonObject("state")
            ?.getAsJsonObject("data")
            ?.getAsJsonObject("entity")
            ?: throw IllegalStateException("Плейлист Spotify приватный или не найден")

        val title = entity.get("name")?.asString ?: entity.get("title")?.asString ?: "Spotify $type"
        val coverUrl = entity.getAsJsonObject("coverArt")
            ?.getAsJsonArray("sources")
            ?.mapNotNull { it.asJsonObject.get("url")?.asString }
            ?.lastOrNull()

        val trackList = entity.getAsJsonArray("trackList") ?: JsonArray()
        val tracks = mutableListOf<ImportedTrack>()

        for (elem in trackList) {
            val obj = elem.asJsonObject
            val trackTitle = obj.get("title")?.asString ?: continue
            val artist = obj.get("subtitle")?.asString ?: ""
            val durationMs = obj.get("duration")?.asLong

            tracks.add(
                ImportedTrack(
                    title = trackTitle,
                    artist = artist,
                    durationMs = durationMs,
                    coverUrl = coverUrl
                )
            )
        }

        return ParsedPlaylist(
            title = title,
            coverUrl = coverUrl,
            tracks = tracks,
            source = "Spotify"
        )
    }

    // --- APPLE MUSIC ---
    private fun fetchApple(url: String): ParsedPlaylist {
        val html = httpGet(url.substringBefore('#'))
        val titleMatch = Regex("<title>(.*?)</title>").find(html)
        val rawTitle = titleMatch?.groupValues?.get(1)?.substringBefore(" on Apple Music")?.substringBefore(" в Apple Music") ?: "Apple Music Playlist"

        val tracks = mutableListOf<ImportedTrack>()
        val trackRegex = Regex("\"name\":\\s*\"([^\"]+)\"[^}]*?\"byArtist\":\\s*\\{[^}]*?\"name\":\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
        for (m in trackRegex.findAll(html)) {
            val trackTitle = m.groupValues[1]
            val artist = m.groupValues[2]
            tracks.add(
                ImportedTrack(
                    title = trackTitle,
                    artist = artist
                )
            )
        }

        return ParsedPlaylist(
            title = rawTitle,
            coverUrl = null,
            tracks = tracks.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" },
            source = "Apple Music"
        )
    }

    // --- DEEZER ---
    private fun fetchDeezer(url: String): ParsedPlaylist {
        val match = DEEZER_REGEX.find(url)
            ?: throw IllegalArgumentException("Неверная ссылка Deezer")
        val (type, id) = match.destructured

        val jsonStr = httpGet("https://api.deezer.com/$type/$id")
        val root = gson.fromJson(jsonStr, JsonObject::class.java)
        if (root.has("error")) {
            throw IllegalStateException("Ошибка Deezer: ${root.getAsJsonObject("error").get("message")?.asString}")
        }

        val title = root.get("title")?.asString ?: "Deezer $type"
        val cover = root.get("picture_xl")?.asString ?: root.get("cover_xl")?.asString

        val tracksArray = root.getAsJsonObject("tracks")?.getAsJsonArray("data") ?: JsonArray()
        val tracks = mutableListOf<ImportedTrack>()

        for (elem in tracksArray) {
            val obj = elem.asJsonObject
            val trackTitle = obj.get("title")?.asString ?: continue
            val artist = obj.getAsJsonObject("artist")?.get("name")?.asString ?: ""
            val durationSec = obj.get("duration")?.asLong ?: 0L

            tracks.add(
                ImportedTrack(
                    title = trackTitle,
                    artist = artist,
                    durationMs = durationSec * 1000L,
                    coverUrl = cover
                )
            )
        }

        return ParsedPlaylist(
            title = title,
            coverUrl = cover,
            tracks = tracks,
            source = "Deezer"
        )
    }

    // --- YOUTUBE MUSIC ---
    private suspend fun fetchYouTube(url: String): ParsedPlaylist {
        val listId = YT_LIST_REGEX.find(url)?.groupValues?.get(1)
            ?: url.substringAfter("playlist?list=").substringBefore("&")

        val page = YouTube.playlist(listId).getOrThrow()
        val tracks = page.songs.map { songItem ->
            ImportedTrack(
                title = songItem.title,
                artist = songItem.artists.joinToString(", ") { it.name },
                album = songItem.album?.name,
                durationMs = songItem.duration?.toLong()?.times(1000L),
                videoId = songItem.id,
                coverUrl = songItem.thumbnail
            )
        }

        return ParsedPlaylist(
            title = page.playlist?.title ?: "YouTube Playlist",
            coverUrl = page.playlist?.thumbnail,
            tracks = tracks,
            source = "YouTube Music"
        )
    }

    /**
     * Resolves imported tracks to playable YouTube songs and saves the playlist to the local database.
     */
    suspend fun importToLibrary(
        context: Context,
        playlist: ParsedPlaylist,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val appDatabase = AppDatabase.getInstance(context)
        val playlistId = "imported_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        val resolvedSongs = mutableListOf<Song>()
        val total = playlist.tracks.size

        // Batch search resolution in chunks of 4 concurrent requests
        coroutineScope {
            val chunks = playlist.tracks.chunked(4)
            var processed = 0

            for (chunk in chunks) {
                val deferreds = chunk.map { track ->
                    async {
                        if (!track.videoId.isNullOrBlank()) {
                            Song(
                                youtubeId = track.videoId,
                                title = track.title,
                                artist = track.artist,
                                duration = if ((track.durationMs ?: 0L) > 0) (track.durationMs!! / 1000).toString() else "",
                                thumbnailHref = track.coverUrl ?: playlist.coverUrl ?: "",
                                album = track.album
                            )
                        } else {
                            try {
                                val query = "${track.title} ${track.artist}".trim()
                                val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                val topSong = searchRes?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                                if (topSong != null) {
                                    Song(
                                        youtubeId = topSong.id,
                                        title = topSong.title,
                                        artist = topSong.artists.joinToString(", ") { it.name },
                                        duration = topSong.duration?.toString() ?: "",
                                        thumbnailHref = topSong.thumbnail ?: track.coverUrl ?: "",
                                        album = topSong.album?.name ?: track.album
                                    )
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to resolve track: ${track.title}")
                                null
                            }
                        }
                    }
                }

                val results = deferreds.awaitAll().filterNotNull()
                resolvedSongs.addAll(results)
                processed += chunk.size
                onProgress(processed.coerceAtMost(total), total)
            }
        }

        val playlistInfo = PlaylistInfo(
            id = playlistId,
            title = playlist.title,
            coverHref = playlist.coverUrl ?: resolvedSongs.firstOrNull()?.thumbnailHref ?: "",
            lastSyncSongCount = resolvedSongs.size,
            lastSyncTimestamp = System.currentTimeMillis()
        )

        val playlistRepo = appDatabase.playlistRepository()
        playlistRepo.insertPlaylist(playlistInfo)
        if (resolvedSongs.isNotEmpty()) {
            playlistRepo.insertSongs(resolvedSongs)
            val refs = resolvedSongs.mapIndexed { index, song ->
                PlaylistSongCrossRef(playlistId, song.youtubeId, index)
            }
            playlistRepo.insertCrossRefs(refs)
        }

        playlistId
    }
}
