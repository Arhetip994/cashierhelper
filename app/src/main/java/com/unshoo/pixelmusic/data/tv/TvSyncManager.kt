package com.unshoo.pixelmusic.data.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.jellyfin.JellyfinRepository
import com.unshoo.pixelmusic.data.listenbrainz.ListenBrainzRepository
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.navidrome.NavidromeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val navidromeRepository: NavidromeRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val listenBrainzRepository: ListenBrainzRepository
) {
    private companion object {
        private const val PREFS_NAME = "pixelmusic_tv_sync_prefs"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_LAN_HOST = "lan_host"
        private const val TAG = "TvSyncManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _pairedCode = MutableStateFlow(prefs.getString(KEY_PAIRING_CODE, null))
    val pairedCode: StateFlow<String?> = _pairedCode.asStateFlow()

    private val _relayTopic = MutableStateFlow(
        _pairedCode.value?.let { "pixelmusic_tv_$it" }
    )
    val relayTopic: StateFlow<String?> = _relayTopic.asStateFlow()

    private val _lanHost = MutableStateFlow(prefs.getString(KEY_LAN_HOST, null))
    val lanHost: StateFlow<String?> = _lanHost.asStateFlow()

    private val _isPaired = MutableStateFlow(!_pairedCode.value.isNullOrBlank() || !_lanHost.value.isNullOrBlank())
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _lastStatus = MutableStateFlow<String?>(null)
    val lastStatus: StateFlow<String?> = _lastStatus.asStateFlow()

    fun pair(rawInput: String): Result<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.tv_sync_error_not_connected)))
        }

        var extractedCode: String? = null
        var extractedHost: String? = null

        // 1. JSON payload support
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = gson.fromJson(trimmed, JsonObject::class.java)
                if (obj.has("code")) {
                    extractedCode = obj.get("code").asString.take(6)
                } else if (obj.has("topic")) {
                    val topic = obj.get("topic").asString
                    extractedCode = topic.substringAfter("pixelmusic_tv_").take(6)
                } else if (obj.has("serverUrl") || obj.has("url") || obj.has("host")) {
                    val u = (obj.get("serverUrl") ?: obj.get("url") ?: obj.get("host")).asString
                    if (u.contains("pixelmusic_tv_")) {
                        extractedCode = u.substringAfter("pixelmusic_tv_").take(6)
                    } else {
                        extractedHost = u.removePrefix("http://").removePrefix("https://").substringBefore('/')
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. ntfy relay topic or URL
        if (extractedCode == null && trimmed.contains("pixelmusic_tv_")) {
            val suffix = trimmed.substringAfter("pixelmusic_tv_")
                .substringBefore('?')
                .substringBefore('/')
                .take(6)
            if (suffix.length == 6 && suffix.all { it.isDigit() }) {
                extractedCode = suffix
            }
        }

        // 3. Deep link scheme (pixelmusic://tv?code=... or pixelmusic://tv/...)
        if (extractedCode == null && (trimmed.startsWith("pixelmusic://") || trimmed.startsWith("pixelmusictv://"))) {
            try {
                val uri = android.net.Uri.parse(trimmed)
                val codeParam = uri.getQueryParameter("code") ?: uri.lastPathSegment
                if (!codeParam.isNullOrBlank()) {
                    val digits = codeParam.filter { it.isDigit() }.take(6)
                    if (digits.length == 6) {
                        extractedCode = digits
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. HTTP / HTTPS link (ntfy or LAN server)
        if (extractedCode == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            if (trimmed.contains("ntfy.sh/pixelmusic_tv_")) {
                val after = trimmed.substringAfter("pixelmusic_tv_")
                    .substringBefore('?')
                    .substringBefore('/')
                    .take(6)
                if (after.length == 6 && after.all { it.isDigit() }) {
                    extractedCode = after
                }
            } else if (!trimmed.contains("ntfy.sh")) {
                try {
                    val uri = android.net.Uri.parse(trimmed)
                    val host = uri.host
                    val port = if (uri.port != -1) uri.port else 8088
                    if (!host.isNullOrBlank()) {
                        extractedHost = "$host:$port"
                    }
                } catch (_: Exception) {
                    extractedHost = trimmed.removePrefix("http://").removePrefix("https://").substringBefore('/')
                }
            }
        }

        // 5. Look for 6 consecutive digits anywhere
        if (extractedCode == null && extractedHost == null) {
            val sixDigitsRegex = Regex("""(?:\D|^)(\d{6})(?:\D|$)""")
            val match = sixDigitsRegex.find(trimmed)
            if (match != null) {
                extractedCode = match.groupValues[1]
            } else {
                val digitsOnly = trimmed.filter { it.isDigit() }
                if (digitsOnly.length == 6) {
                    extractedCode = digitsOnly
                } else {
                    return Result.failure(IllegalArgumentException(context.getString(R.string.tv_sync_error_invalid_code)))
                }
            }
        }

        prefs.edit {
            putString(KEY_PAIRING_CODE, extractedCode)
            putString(KEY_LAN_HOST, extractedHost)
        }

        _pairedCode.value = extractedCode
        _relayTopic.value = extractedCode?.let { "pixelmusic_tv_$it" }
        _lanHost.value = extractedHost
        _isPaired.value = true

        val display = extractedCode ?: extractedHost.orEmpty()
        _lastStatus.value = context.getString(R.string.tv_sync_toast_paired, display)
        return Result.success(display)
    }

    fun unpair() {
        prefs.edit {
            remove(KEY_PAIRING_CODE)
            remove(KEY_LAN_HOST)
        }
        _pairedCode.value = null
        _relayTopic.value = null
        _lanHost.value = null
        _isPaired.value = false
        _lastStatus.value = context.getString(R.string.tv_sync_toast_unpaired)
    }

    fun sendSong(song: Song, action: String = "play", onResult: ((Result<String>) -> Unit)? = null) {
        val topic = _relayTopic.value
        val host = _lanHost.value

        if (topic == null && host == null) {
            val err = Result.failure<String>(IllegalStateException(context.getString(R.string.tv_sync_error_not_connected)))
            _lastStatus.value = context.getString(R.string.tv_sync_error_not_connected)
            onResult?.invoke(err)
            return
        }

        val trackTitle = if (song.displayArtist.isNotBlank()) "${song.displayArtist} — ${song.title}" else song.title
        val songUrl = if (song.contentUriString.startsWith("http://") || song.contentUriString.startsWith("https://")) {
            song.contentUriString
        } else {
            val query = URLEncoder.encode("${song.displayArtist} ${song.title}", StandardCharsets.UTF_8.toString())
            "https://music.youtube.com/search?q=$query"
        }

        val json = JsonObject().apply {
            addProperty("url", songUrl)
            addProperty("title", trackTitle)
            addProperty("artist", song.displayArtist)
            addProperty("action", action)
            addProperty("album", song.album)
            song.albumArtUriString?.let { addProperty("thumbnailUrl", it) }
        }

        dispatchPayload(json.toString(), trackTitle, onResult)
    }

    fun sendPlaylist(name: String, playlistUrl: String? = null, onResult: ((Result<String>) -> Unit)? = null) {
        val json = JsonObject().apply {
            addProperty("url", playlistUrl.orEmpty())
            addProperty("title", name)
            addProperty("action", "save_playlist")
        }
        dispatchPayload(json.toString(), name, onResult)
    }

    fun sendCustomLink(url: String, title: String = "", action: String = "play", onResult: ((Result<String>) -> Unit)? = null) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            val err = Result.failure<String>(IllegalArgumentException("Ссылка не может быть пустой"))
            onResult?.invoke(err)
            return
        }

        val finalTitle = title.ifBlank { trimmedUrl }
        val json = JsonObject().apply {
            addProperty("url", trimmedUrl)
            addProperty("title", finalTitle)
            addProperty("action", action)
        }
        dispatchPayload(json.toString(), finalTitle, onResult)
    }

    fun syncAccounts(onResult: ((Result<String>) -> Unit)? = null) {
        scope.launch {
            _isSending.value = true
            var sentCount = 0

            try {
                // 1. Navidrome
                val navCreds = navidromeRepository.getSavedCredentialsForTvSync()
                if (navCreds != null) {
                    val navJson = JsonObject().apply {
                        addProperty("service", "navidrome")
                        addProperty("url", navCreds.first)
                        addProperty("username", navCreds.second)
                        addProperty("password", navCreds.third)
                    }
                    postPayloadDirect(navJson.toString())
                    sentCount++
                }

                // 2. Jellyfin
                val jellyCreds = jellyfinRepository.getSavedCredentialsForTvSync()
                if (jellyCreds != null) {
                    val jellyJson = JsonObject().apply {
                        addProperty("service", "jellyfin")
                        addProperty("url", jellyCreds.first)
                        addProperty("username", jellyCreds.second)
                        addProperty("password", jellyCreds.third)
                        addProperty("token", jellyCreds.third)
                    }
                    postPayloadDirect(jellyJson.toString())
                    sentCount++
                }

                // 3. ListenBrainz
                val lbCreds = listenBrainzRepository.getSavedCredentialsForTvSync()
                if (lbCreds != null) {
                    val lbJson = JsonObject().apply {
                        addProperty("service", "listenbrainz")
                        addProperty("token", lbCreds.first)
                        lbCreds.second?.let { addProperty("url", it) }
                    }
                    postPayloadDirect(lbJson.toString())
                    sentCount++
                }

                withContext(Dispatchers.Main) {
                    _isSending.value = false
                    if (sentCount > 0) {
                        val msg = context.getString(R.string.tv_sync_toast_accounts_synced)
                        _lastStatus.value = msg
                        onResult?.invoke(Result.success(msg))
                    } else {
                        val msg = context.getString(R.string.tv_sync_toast_no_accounts)
                        _lastStatus.value = msg
                        onResult?.invoke(Result.failure(Exception(msg)))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync accounts")
                withContext(Dispatchers.Main) {
                    _isSending.value = false
                    val msg = context.getString(R.string.tv_sync_toast_failed, e.localizedMessage ?: "")
                    _lastStatus.value = msg
                    onResult?.invoke(Result.failure(e))
                }
            }
        }
    }

    fun generateQrContentForSong(song: Song): String {
        val topic = _relayTopic.value
        val host = _lanHost.value
        if (topic != null) {
            return "https://ntfy.sh/$topic"
        }
        if (host != null) {
            return "http://$host"
        }
        val query = URLEncoder.encode("${song.displayArtist} ${song.title}", StandardCharsets.UTF_8.toString())
        return "https://music.youtube.com/search?q=$query"
    }

    private fun dispatchPayload(
        payloadJson: String,
        itemLabel: String,
        onResult: ((Result<String>) -> Unit)?
    ) {
        scope.launch {
            _isSending.value = true
            try {
                postPayloadDirect(payloadJson)
                withContext(Dispatchers.Main) {
                    _isSending.value = false
                    val msg = context.getString(R.string.tv_sync_toast_sent_track, itemLabel)
                    _lastStatus.value = msg
                    onResult?.invoke(Result.success(msg))
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error transmitting payload to TV")
                withContext(Dispatchers.Main) {
                    _isSending.value = false
                    val msg = context.getString(R.string.tv_sync_toast_failed, e.localizedMessage ?: "")
                    _lastStatus.value = msg
                    onResult?.invoke(Result.failure(e))
                }
            }
        }
    }

    private suspend fun postPayloadDirect(payloadJson: String) = withContext(Dispatchers.IO) {
        val topic = _relayTopic.value
        val host = _lanHost.value

        var relaySuccess = false

        // 1. Send via ntfy cloud relay
        if (topic != null) {
            try {
                val relayUrl = "https://ntfy.sh/$topic"
                val body = payloadJson.toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(relayUrl)
                    .post(body)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        relaySuccess = true
                        Timber.d("$TAG: Sent payload to ntfy relay: $relayUrl")
                    } else {
                        Timber.w("$TAG: Relay responded with HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Relay post failed")
            }
        }

        // 2. Also send via LAN if host is configured
        if (host != null) {
            try {
                val lanUrl = if (host.startsWith("http://") || host.startsWith("https://")) {
                    "$host/api/link"
                } else {
                    "http://$host/api/link"
                }
                val body = payloadJson.toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(lanUrl)
                    .post(body)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Timber.d("$TAG: Sent payload to LAN TV: $lanUrl")
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: LAN post failed")
            }
        }

        if (!relaySuccess && host == null) {
            throw Exception("Не удалось доставить сообщение на ТВ через облачный релей ntfy.sh")
        }
    }
}
