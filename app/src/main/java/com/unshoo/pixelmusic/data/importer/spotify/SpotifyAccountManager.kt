package com.unshoo.pixelmusic.data.importer.spotify

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.unshoo.pixelmusic.data.importer.AccountImportSource
import com.unshoo.pixelmusic.data.importer.AccountPlaylist
import com.unshoo.pixelmusic.data.importer.AccountProfile
import com.unshoo.pixelmusic.data.importer.ImportedTrack
import com.unshoo.pixelmusic.data.importer.ParsedPlaylist
import com.unshoo.pixelmusic.data.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAccountManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        val KEY_SPOTIFY_SP_DC = stringPreferencesKey("spotify_sp_dc")
        val KEY_SPOTIFY_USER_NAME = stringPreferencesKey("spotify_user_name")
        val KEY_SPOTIFY_AVATAR = stringPreferencesKey("spotify_avatar")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val tokenMutex = Mutex()
    private var cachedAccessToken: String? = null
    private var tokenExpiryMs: Long = 0L

    val spDcFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SPOTIFY_SP_DC]?.takeIf { it.isNotBlank() }
    }

    val isLoggedInFlow: Flow<Boolean> = spDcFlow.map { it != null }

    val profileFlow: Flow<AccountProfile?> = context.dataStore.data.map { prefs ->
        val spDc = prefs[KEY_SPOTIFY_SP_DC]?.takeIf { it.isNotBlank() } ?: return@map null
        val name = prefs[KEY_SPOTIFY_USER_NAME]?.takeIf { it.isNotBlank() } ?: "Spotify User"
        val avatar = prefs[KEY_SPOTIFY_AVATAR]?.takeIf { it.isNotBlank() }
        AccountProfile(
            username = name,
            avatarUrl = avatar,
            source = AccountImportSource.SPOTIFY
        )
    }

    suspend fun saveSpDc(spDc: String, name: String? = null, avatar: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SPOTIFY_SP_DC] = spDc.trim()
            if (name != null) prefs[KEY_SPOTIFY_USER_NAME] = name
            if (avatar != null) prefs[KEY_SPOTIFY_AVATAR] = avatar
        }
        // Invalidate token cache
        tokenMutex.withLock {
            cachedAccessToken = null
            tokenExpiryMs = 0L
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SPOTIFY_SP_DC)
            prefs.remove(KEY_SPOTIFY_USER_NAME)
            prefs.remove(KEY_SPOTIFY_AVATAR)
        }
        tokenMutex.withLock {
            cachedAccessToken = null
            tokenExpiryMs = 0L
        }
    }

    suspend fun getFreshAccessToken(): String? = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedAccessToken != null && now < tokenExpiryMs - 60_000L) {
                return@withLock cachedAccessToken
            }

            val spDc = spDcFlow.first() ?: return@withLock null

            try {
                val request = Request.Builder()
                    .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "sp_dc=$spDc")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Spotify token fetch failed HTTP %d", response.code)
                        return@withLock null
                    }
                    val body = response.body.string()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val isAnonymous = json.get("isAnonymous")?.asBoolean ?: true
                    if (isAnonymous) {
                        Timber.w("Spotify returned anonymous token, sp_dc may be expired")
                        return@withLock null
                    }
                    val token = json.get("accessToken")?.asString ?: return@withLock null
                    val expiry = json.get("accessTokenExpirationTimestampMs")?.asLong
                        ?: (now + 3600_000L)

                    cachedAccessToken = token
                    tokenExpiryMs = expiry
                    token
                }
            } catch (e: Exception) {
                Timber.e(e, "Error obtaining Spotify access token")
                null
            }
        }
    }

    suspend fun verifyAndRefreshProfile(): AccountProfile? = withContext(Dispatchers.IO) {
        val token = getFreshAccessToken() ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                val json = gson.fromJson(body, JsonObject::class.java)
                val displayName = json.get("display_name")?.asString ?: json.get("id")?.asString ?: "Spotify User"
                val avatar = json.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.asString

                context.dataStore.edit { prefs ->
                    prefs[KEY_SPOTIFY_USER_NAME] = displayName
                    if (avatar != null) prefs[KEY_SPOTIFY_AVATAR] = avatar
                }

                AccountProfile(
                    username = displayName,
                    avatarUrl = avatar,
                    source = AccountImportSource.SPOTIFY
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify Spotify profile")
            null
        }
    }

    suspend fun fetchUserPlaylists(): List<AccountPlaylist> = withContext(Dispatchers.IO) {
        val token = getFreshAccessToken() ?: return@withContext emptyList()
        val result = mutableListOf<AccountPlaylist>()

        // 1. Fetch Liked Songs / Любимые треки count
        try {
            val likedReq = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?limit=1")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(likedReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val total = json.get("total")?.asInt ?: 0
                    if (total > 0) {
                        result.add(
                            AccountPlaylist(
                                id = "SPOTIFY_LIKED_SONGS",
                                title = "Любимые треки (Liked Songs)",
                                coverUrl = null,
                                trackCount = total,
                                source = AccountImportSource.SPOTIFY,
                                isLikedSongs = true,
                                ownerName = "Вы"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error checking Spotify liked tracks count")
        }

        // 2. Fetch user playlists (paginate up to 200 playlists)
        var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0

        while (nextUrl != null && pages < 4) {
            try {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val items = json.getAsJsonArray("items") ?: return@use

                    for (elem in items) {
                        val obj = elem.asJsonObject
                        val id = obj.get("id")?.asString ?: continue
                        val name = obj.get("name")?.asString ?: "Untitled Playlist"
                        val totalTracks = obj.getAsJsonObject("tracks")?.get("total")?.asInt ?: 0
                        val cover = obj.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                        val owner = obj.getAsJsonObject("owner")?.get("display_name")?.asString

                        result.add(
                            AccountPlaylist(
                                id = id,
                                title = name,
                                coverUrl = cover,
                                trackCount = totalTracks,
                                source = AccountImportSource.SPOTIFY,
                                isLikedSongs = false,
                                ownerName = owner
                            )
                        )
                    }

                    nextUrl = json.get("next")?.takeIf { !it.isJsonNull }?.asString
                    pages++
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching Spotify user playlists")
                break
            }
        }

        result
    }

    suspend fun fetchPlaylistTracks(playlist: AccountPlaylist): ParsedPlaylist = withContext(Dispatchers.IO) {
        val token = getFreshAccessToken() ?: throw IllegalStateException("Не удалось получить токен Spotify. Авторизуйтесь снова.")
        val tracks = mutableListOf<ImportedTrack>()

        if (playlist.isLikedSongs) {
            var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
            var pages = 0
            while (nextUrl != null && pages < 20) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Spotify HTTP error ${response.code}")
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val items = json.getAsJsonArray("items") ?: return@use

                    for (elem in items) {
                        val trackObj = elem.asJsonObject.getAsJsonObject("track") ?: continue
                        val title = trackObj.get("name")?.asString ?: continue
                        val artists = trackObj.getAsJsonArray("artists")?.mapNotNull {
                            it.asJsonObject.get("name")?.asString
                        }?.joinToString(", ") ?: ""
                        val albumObj = trackObj.getAsJsonObject("album")
                        val albumName = albumObj?.get("name")?.asString
                        val cover = albumObj?.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                        val durMs = trackObj.get("duration_ms")?.asLong

                        tracks.add(
                            ImportedTrack(
                                title = title,
                                artist = artists,
                                album = albumName,
                                durationMs = durMs,
                                coverUrl = cover
                            )
                        )
                    }
                    nextUrl = json.get("next")?.takeIf { !it.isJsonNull }?.asString
                    pages++
                }
            }
        } else {
            var nextUrl: String? = "https://api.spotify.com/v1/playlists/${playlist.id}/tracks?limit=100"
            var pages = 0
            while (nextUrl != null && pages < 10) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Spotify HTTP error ${response.code}")
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val items = json.getAsJsonArray("items") ?: return@use

                    for (elem in items) {
                        val trackObj = elem.asJsonObject.getAsJsonObject("track") ?: continue
                        val title = trackObj.get("name")?.asString ?: continue
                        val artists = trackObj.getAsJsonArray("artists")?.mapNotNull {
                            it.asJsonObject.get("name")?.asString
                        }?.joinToString(", ") ?: ""
                        val albumObj = trackObj.getAsJsonObject("album")
                        val albumName = albumObj?.get("name")?.asString
                        val cover = albumObj?.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                            ?: playlist.coverUrl
                        val durMs = trackObj.get("duration_ms")?.asLong

                        tracks.add(
                            ImportedTrack(
                                title = title,
                                artist = artists,
                                album = albumName,
                                durationMs = durMs,
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
            source = "Spotify"
        )
    }
}
