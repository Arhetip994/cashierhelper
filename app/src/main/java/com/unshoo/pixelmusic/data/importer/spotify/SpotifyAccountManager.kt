package com.unshoo.pixelmusic.data.importer.spotify

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAccountManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        val KEY_SPOTIFY_ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        val KEY_SPOTIFY_REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        val KEY_SPOTIFY_EXPIRES_AT = longPreferencesKey("spotify_expires_at")
        val KEY_SPOTIFY_PKCE_VERIFIER = stringPreferencesKey("spotify_pkce_verifier")
        val KEY_SPOTIFY_SP_DC = stringPreferencesKey("spotify_sp_dc")
        val KEY_SPOTIFY_USER_NAME = stringPreferencesKey("spotify_user_name")
        val KEY_SPOTIFY_AVATAR = stringPreferencesKey("spotify_avatar")
        val KEY_SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")

        const val DEFAULT_CLIENT_ID = "d8a5dc3440514115b16952d4381b3735"
        const val REDIRECT_URI = "pixelmusic://spotify-callback"
        const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private val tokenMutex = Mutex()
    private var cachedAccessToken: String? = null
    private var tokenExpiryMs: Long = 0L

    val spDcFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SPOTIFY_SP_DC]?.takeIf { it.isNotBlank() }
    }

    val customClientIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SPOTIFY_CLIENT_ID]?.takeIf { it.isNotBlank() }
    }

    val isLoggedInFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val hasOAuth = !prefs[KEY_SPOTIFY_ACCESS_TOKEN].isNullOrBlank() || !prefs[KEY_SPOTIFY_REFRESH_TOKEN].isNullOrBlank()
        val hasSpDc = !prefs[KEY_SPOTIFY_SP_DC].isNullOrBlank()
        hasOAuth || hasSpDc
    }

    val profileFlow: Flow<AccountProfile?> = context.dataStore.data.map { prefs ->
        val hasAuth = !prefs[KEY_SPOTIFY_ACCESS_TOKEN].isNullOrBlank() ||
                !prefs[KEY_SPOTIFY_REFRESH_TOKEN].isNullOrBlank() ||
                !prefs[KEY_SPOTIFY_SP_DC].isNullOrBlank()
        if (!hasAuth) return@map null

        val name = prefs[KEY_SPOTIFY_USER_NAME]?.takeIf { it.isNotBlank() } ?: "Spotify User"
        val avatar = prefs[KEY_SPOTIFY_AVATAR]?.takeIf { it.isNotBlank() }
        AccountProfile(
            username = name,
            avatarUrl = avatar,
            source = AccountImportSource.SPOTIFY
        )
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    suspend fun saveCustomClientId(clientId: String?) {
        context.dataStore.edit { prefs ->
            if (clientId.isNullOrBlank()) {
                prefs.remove(KEY_SPOTIFY_CLIENT_ID)
            } else {
                prefs[KEY_SPOTIFY_CLIENT_ID] = clientId.trim()
            }
        }
    }

    suspend fun startPkceAuth(activity: Activity, customClientId: String? = null) {
        val clientId = customClientId?.trim()?.ifEmpty { null }
            ?: context.dataStore.data.map { it[KEY_SPOTIFY_CLIENT_ID] }.first()?.ifEmpty { null }
            ?: DEFAULT_CLIENT_ID

        val random = SecureRandom()
        val verifierBytes = ByteArray(64).also { random.nextBytes(it) }
        val verifier = base64UrlNoPad(verifierBytes)
        val challenge = base64UrlNoPad(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

        context.dataStore.edit { prefs ->
            prefs[KEY_SPOTIFY_PKCE_VERIFIER] = verifier
            if (customClientId != null) {
                prefs[KEY_SPOTIFY_CLIENT_ID] = customClientId.trim()
            }
        }

        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val authUrl = buildString {
            append("https://accounts.spotify.com/authorize")
            append("?response_type=code")
            append("&client_id=").append(enc(clientId))
            append("&scope=").append(enc(SCOPES))
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
        }

        Timber.d("Launching Spotify Custom Tab for PKCE: $authUrl")
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(activity, Uri.parse(authUrl))
    }

    suspend fun handleAuthCallback(code: String?, error: String?): Result<AccountProfile> = withContext(Dispatchers.IO) {
        if (!error.isNullOrBlank()) {
            Timber.e("Spotify OAuth error callback: $error")
            return@withContext Result.failure(Exception("Spotify authorization failed: $error"))
        }
        if (code.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Authorization code is empty"))
        }

        val prefs = context.dataStore.data.first()
        val verifier = prefs[KEY_SPOTIFY_PKCE_VERIFIER]
            ?: return@withContext Result.failure(Exception("PKCE code verifier not found"))
        val clientId = prefs[KEY_SPOTIFY_CLIENT_ID]?.ifEmpty { null } ?: DEFAULT_CLIENT_ID

        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("User-Agent", USER_AGENT)
            .post(formBody)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                Timber.d("Spotify token exchange HTTP %d: %s", response.code, body)
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Token exchange failed (${response.code}): $body"))
                }
                val json = gson.fromJson(body, JsonObject::class.java)
                val accessToken = json.get("access_token")?.asString
                    ?: return@withContext Result.failure(Exception("Missing access_token in response"))
                val refreshToken = json.get("refresh_token")?.asString
                val expiresInSec = json.get("expires_in")?.asLong ?: 3600L
                val expiresAt = System.currentTimeMillis() + (expiresInSec * 1000L)

                context.dataStore.edit { p ->
                    p[KEY_SPOTIFY_ACCESS_TOKEN] = accessToken
                    if (!refreshToken.isNullOrBlank()) {
                        p[KEY_SPOTIFY_REFRESH_TOKEN] = refreshToken
                    }
                    p[KEY_SPOTIFY_EXPIRES_AT] = expiresAt
                }

                tokenMutex.withLock {
                    cachedAccessToken = accessToken
                    tokenExpiryMs = expiresAt
                }

                val profile = verifyAndRefreshProfile()
                if (profile != null) {
                    Result.success(profile)
                } else {
                    Result.failure(Exception("Failed to fetch Spotify profile"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception exchanging Spotify code")
            Result.failure(e)
        }
    }

    suspend fun saveSpDc(spDc: String, name: String? = null, avatar: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SPOTIFY_SP_DC] = spDc.trim()
            if (name != null) prefs[KEY_SPOTIFY_USER_NAME] = name
            if (avatar != null) prefs[KEY_SPOTIFY_AVATAR] = avatar
        }
        tokenMutex.withLock {
            cachedAccessToken = null
            tokenExpiryMs = 0L
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SPOTIFY_ACCESS_TOKEN)
            prefs.remove(KEY_SPOTIFY_REFRESH_TOKEN)
            prefs.remove(KEY_SPOTIFY_EXPIRES_AT)
            prefs.remove(KEY_SPOTIFY_PKCE_VERIFIER)
            prefs.remove(KEY_SPOTIFY_SP_DC)
            prefs.remove(KEY_SPOTIFY_USER_NAME)
            prefs.remove(KEY_SPOTIFY_AVATAR)
        }
        tokenMutex.withLock {
            cachedAccessToken = null
            tokenExpiryMs = 0L
        }
    }

    private suspend fun refreshTokenInternal(refreshToken: String, clientId: String): String? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("User-Agent", USER_AGENT)
            .post(formBody)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Failed to refresh Spotify token HTTP %d", response.code)
                    return@withContext null
                }
                val body = response.body.string()
                val json = gson.fromJson(body, JsonObject::class.java)
                val newAccessToken = json.get("access_token")?.asString ?: return@withContext null
                val newRefreshToken = json.get("refresh_token")?.asString
                val expiresInSec = json.get("expires_in")?.asLong ?: 3600L
                val expiresAt = System.currentTimeMillis() + (expiresInSec * 1000L)

                context.dataStore.edit { p ->
                    p[KEY_SPOTIFY_ACCESS_TOKEN] = newAccessToken
                    if (!newRefreshToken.isNullOrBlank()) {
                        p[KEY_SPOTIFY_REFRESH_TOKEN] = newRefreshToken
                    }
                    p[KEY_SPOTIFY_EXPIRES_AT] = expiresAt
                }

                cachedAccessToken = newAccessToken
                tokenExpiryMs = expiresAt
                newAccessToken
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during Spotify token refresh")
            null
        }
    }

    suspend fun getFreshAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedAccessToken != null && now < tokenExpiryMs - 60_000L) {
                return@withLock cachedAccessToken
            }

            val prefs = context.dataStore.data.first()
            val refreshToken = prefs[KEY_SPOTIFY_REFRESH_TOKEN]
            val clientId = prefs[KEY_SPOTIFY_CLIENT_ID]?.ifEmpty { null } ?: DEFAULT_CLIENT_ID

            // 1. Try OAuth refresh token if available
            if (!refreshToken.isNullOrBlank()) {
                val token = refreshTokenInternal(refreshToken, clientId)
                if (token != null) return@withLock token
            }

            // 2. Try existing stored access token if not expired
            val storedAccess = prefs[KEY_SPOTIFY_ACCESS_TOKEN]
            val storedExpiry = prefs[KEY_SPOTIFY_EXPIRES_AT] ?: 0L
            if (!forceRefresh && !storedAccess.isNullOrBlank() && now < storedExpiry - 60_000L) {
                cachedAccessToken = storedAccess
                tokenExpiryMs = storedExpiry
                return@withLock storedAccess
            }

            // 3. Fallback to sp_dc web player cookie
            val spDc = prefs[KEY_SPOTIFY_SP_DC]?.takeIf { it.isNotBlank() }
            if (spDc != null) {
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
                        return@withLock token
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error obtaining Spotify access token via sp_dc")
                }
            }

            null
        }
    }

    private suspend fun executeWithAuthRetry(request: Request): Response = withContext(Dispatchers.IO) {
        val initialResp = okHttpClient.newCall(request).execute()
        if (initialResp.code == 401) {
            initialResp.close()
            Timber.w("Spotify returned HTTP 401, forcing token refresh...")
            val newToken = getFreshAccessToken(forceRefresh = true)
            if (newToken != null) {
                val newRequest = request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return@withContext okHttpClient.newCall(newRequest).execute()
            }
        }
        initialResp
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

            executeWithAuthRetry(request).use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Spotify verifyAndRefreshProfile HTTP %d", response.code)
                    return@withContext null
                }
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

            executeWithAuthRetry(likedReq).use { response ->
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

        while (nextUrl != null && pages < 20) {
            try {
                val currentToken = getFreshAccessToken() ?: token
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $currentToken")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                executeWithAuthRetry(req).use { response ->
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
                val currentToken = getFreshAccessToken() ?: token
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $currentToken")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                executeWithAuthRetry(req).use { response ->
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
            while (nextUrl != null && pages < 20) {
                val currentToken = getFreshAccessToken() ?: token
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $currentToken")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                executeWithAuthRetry(req).use { response ->
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

    suspend fun fetchPublicSpotifyTracks(type: String, id: String): ParsedPlaylist? = withContext(Dispatchers.IO) {
        val token = getFreshAccessToken() ?: return@withContext null
        try {
            val metaUrl = "https://api.spotify.com/v1/${type}s/$id"
            val metaReq = Request.Builder()
                .url(metaUrl)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            var playlistTitle = "Spotify $type"
            var coverUrl: String? = null

            executeWithAuthRetry(metaReq).use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Spotify meta request failed HTTP %d for %s/%s", response.code, type, id)
                    return@withContext null
                }
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                playlistTitle = json.get("name")?.asString ?: playlistTitle
                coverUrl = json.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.asString
            }

            val tracks = mutableListOf<ImportedTrack>()
            val endpointTracks = if (type == "album") "https://api.spotify.com/v1/albums/$id/tracks?limit=50" else "https://api.spotify.com/v1/playlists/$id/tracks?limit=100"
            var nextUrl: String? = endpointTracks
            var pages = 0

            while (nextUrl != null && pages < 30) {
                val currentToken = getFreshAccessToken() ?: token
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $currentToken")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                executeWithAuthRetry(req).use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Spotify tracks pagination HTTP %d for %s", response.code, nextUrl)
                        return@use
                    }
                    val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                    val items = json.getAsJsonArray("items") ?: return@use

                    for (elem in items) {
                        val itemObj = elem.asJsonObject
                        val trackObj = if (type == "album") itemObj else (itemObj.getAsJsonObject("track") ?: continue)
                        val title = trackObj.get("name")?.asString ?: continue
                        val artists = trackObj.getAsJsonArray("artists")?.mapNotNull {
                            it.asJsonObject.get("name")?.asString
                        }?.joinToString(", ") ?: ""
                        val albumObj = trackObj.getAsJsonObject("album")
                        val albumName = albumObj?.get("name")?.asString ?: playlistTitle
                        val cover = albumObj?.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.asString ?: coverUrl
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

            ParsedPlaylist(
                title = playlistTitle,
                coverUrl = coverUrl,
                tracks = tracks,
                source = "Spotify"
            )
        } catch (e: Exception) {
            Timber.e(e, "Error fetching public Spotify tracks for %s/%s", type, id)
            null
        }
    }
}
