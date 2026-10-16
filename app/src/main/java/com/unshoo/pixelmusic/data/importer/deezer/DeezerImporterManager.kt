package com.unshoo.pixelmusic.data.importer.deezer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.unshoo.pixelmusic.data.importer.AccountImportSource
import com.unshoo.pixelmusic.data.importer.AccountPlaylist
import com.unshoo.pixelmusic.data.importer.AccountProfile
import com.unshoo.pixelmusic.data.importer.ImportedTrack
import com.unshoo.pixelmusic.data.importer.ParsedPlaylist
import com.unshoo.pixelmusic.data.lossless.constants.DeezerAccountNameKey
import com.unshoo.pixelmusic.data.lossless.constants.DeezerArlKey
import com.unshoo.pixelmusic.data.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeezerImporterManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    val arlFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[DeezerArlKey]?.takeIf { it.isNotBlank() }
    }

    val isLoggedInFlow: Flow<Boolean> = arlFlow.map { it != null }

    val profileFlow: Flow<AccountProfile?> = context.dataStore.data.map { prefs ->
        val arl = prefs[DeezerArlKey]?.takeIf { it.isNotBlank() } ?: return@map null
        val name = prefs[DeezerAccountNameKey]?.takeIf { it.isNotBlank() } ?: "Deezer User"
        AccountProfile(
            username = name,
            avatarUrl = null,
            source = AccountImportSource.DEEZER
        )
    }

    private suspend fun getUserId(arl: String): Long? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.deezer.com/ajax/gw-light.php?method=deezer.getUserData&input=3&api_version=1.0&api_token="
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "arl=$arl")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val results = json.getAsJsonObject("results")
                val user = results?.getAsJsonObject("USER")
                val id = user?.get("USER_ID")?.asLong
                if (id != null && id > 0L) id else null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching Deezer user id")
            null
        }
    }

    suspend fun fetchUserPlaylists(): List<AccountPlaylist> = withContext(Dispatchers.IO) {
        val arl = arlFlow.first() ?: return@withContext emptyList()
        val userId = getUserId(arl) ?: return@withContext emptyList()
        val result = mutableListOf<AccountPlaylist>()

        // 1. Fetch Loved Tracks / Любимые треки count
        try {
            val lovedReq = Request.Builder()
                .url("https://api.deezer.com/user/$userId/tracks?limit=1")
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "arl=$arl")
                .get()
                .build()

            okHttpClient.newCall(lovedReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val total = json.get("total")?.asInt ?: 0
                    if (total > 0) {
                        result.add(
                            AccountPlaylist(
                                id = "DEEZER_LOVED",
                                title = "Любимые треки (Loved Tracks)",
                                coverUrl = null,
                                trackCount = total,
                                source = AccountImportSource.DEEZER,
                                isLikedSongs = true,
                                ownerName = "Вы"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching Deezer loved tracks count")
        }

        // 2. Fetch User Playlists
        var nextUrl: String? = "https://api.deezer.com/user/$userId/playlists?limit=50"
        var pages = 0

        while (nextUrl != null && pages < 10) {
            try {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "arl=$arl")
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val data = json.getAsJsonArray("data") ?: return@use

                    for (elem in data) {
                        val obj = elem.asJsonObject
                        val id = obj.get("id")?.asString ?: continue
                        val title = obj.get("title")?.asString ?: "Untitled Playlist"
                        val count = obj.get("nb_tracks")?.asInt ?: 0
                        val cover = obj.get("picture_xl")?.asString
                            ?: obj.get("picture_medium")?.asString
                        val creator = obj.getAsJsonObject("creator")?.get("name")?.asString

                        result.add(
                            AccountPlaylist(
                                id = id,
                                title = title,
                                coverUrl = cover,
                                trackCount = count,
                                source = AccountImportSource.DEEZER,
                                isLikedSongs = false,
                                ownerName = creator
                            )
                        )
                    }

                    nextUrl = json.get("next")?.takeIf { !it.isJsonNull }?.asString
                    pages++
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching Deezer user playlists")
                break
            }
        }

        result
    }

    suspend fun fetchPlaylistTracks(playlist: AccountPlaylist): ParsedPlaylist = withContext(Dispatchers.IO) {
        val arl = arlFlow.first() ?: throw IllegalStateException("Deezer ARL не найден. Авторизуйтесь снова.")
        val tracks = mutableListOf<ImportedTrack>()

        if (playlist.isLikedSongs) {
            val userId = getUserId(arl) ?: throw IllegalStateException("Не удалось получить Deezer user id")
            var nextUrl: String? = "https://api.deezer.com/user/$userId/tracks?limit=100"
            var pages = 0

            while (nextUrl != null && pages < 10) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "arl=$arl")
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Deezer HTTP error ${response.code}")
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val data = json.getAsJsonArray("data") ?: return@use

                    for (elem in data) {
                        val obj = elem.asJsonObject
                        val title = obj.get("title")?.asString ?: continue
                        val artist = obj.getAsJsonObject("artist")?.get("name")?.asString ?: ""
                        val durationSec = obj.get("duration")?.asLong ?: 0L
                        val albumObj = obj.getAsJsonObject("album")
                        val albumTitle = albumObj?.get("title")?.asString
                        val cover = albumObj?.get("cover_xl")?.asString ?: albumObj?.get("cover_medium")?.asString

                        tracks.add(
                            ImportedTrack(
                                title = title,
                                artist = artist,
                                album = albumTitle,
                                durationMs = durationSec * 1000L,
                                coverUrl = cover
                            )
                        )
                    }
                    nextUrl = json.get("next")?.takeIf { !it.isJsonNull }?.asString
                    pages++
                }
            }
        } else {
            var nextUrl: String? = "https://api.deezer.com/playlist/${playlist.id}/tracks?limit=100"
            var pages = 0

            while (nextUrl != null && pages < 10) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "arl=$arl")
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Deezer HTTP error ${response.code}")
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val data = json.getAsJsonArray("data") ?: return@use

                    for (elem in data) {
                        val obj = elem.asJsonObject
                        val title = obj.get("title")?.asString ?: continue
                        val artist = obj.getAsJsonObject("artist")?.get("name")?.asString ?: ""
                        val durationSec = obj.get("duration")?.asLong ?: 0L
                        val albumObj = obj.getAsJsonObject("album")
                        val albumTitle = albumObj?.get("title")?.asString
                        val cover = albumObj?.get("cover_xl")?.asString ?: albumObj?.get("cover_medium")?.asString
                            ?: playlist.coverUrl

                        tracks.add(
                            ImportedTrack(
                                title = title,
                                artist = artist,
                                album = albumTitle,
                                durationMs = durationSec * 1000L,
                                coverUrl = cover
                            )
                        )
                    }
                    nextUrl = json.get("next")?.takeIf { !it.isJsonNull }?.asString
                    pages++
                }
            }
        }

        ParsedPlaylist(
            title = playlist.title,
            coverUrl = playlist.coverUrl,
            tracks = tracks,
            source = "Deezer"
        )
    }
}
