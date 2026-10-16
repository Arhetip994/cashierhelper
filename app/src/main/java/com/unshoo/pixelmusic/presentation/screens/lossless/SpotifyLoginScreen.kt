package com.unshoo.pixelmusic.presentation.screens.lossless

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.importer.spotify.SpotifyAccountManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val LOGIN_URL = "https://accounts.spotify.com/en/login"
private const val SPOTIFY_OPEN_ORIGIN = "https://open.spotify.com"
private const val SPOTIFY_ACCOUNTS_ORIGIN = "https://accounts.spotify.com"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpotifyLoginEntryPoint {
    fun spotifyAccountManager(): SpotifyAccountManager
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handled = remember { AtomicBoolean(false) }

    val spotifyAccountManager = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SpotifyLoginEntryPoint::class.java
        ).spotifyAccountManager()
    }

    var manualSpDc by remember { mutableStateOf("") }
    var manualVerifying by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun readSpDc(): String? {
        val cm = CookieManager.getInstance()
        val cookies = sequenceOf(
            cm.getCookie(SPOTIFY_OPEN_ORIGIN),
            cm.getCookie(SPOTIFY_ACCOUNTS_ORIGIN),
            cm.getCookie("https://spotify.com")
        ).filterNotNull().joinToString("; ")

        return cookies.split(';').firstNotNullOfOrNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) {
                val name = pieces[0].trim()
                val value = pieces[1].trim()
                if (name.equals("sp_dc", ignoreCase = true) && value.isNotEmpty()) {
                    value
                } else null
            } else null
        }
    }

    suspend fun persist(spDc: String): Boolean {
        spotifyAccountManager.saveSpDc(spDc)
        val profile = withContext(Dispatchers.IO) { spotifyAccountManager.verifyAndRefreshProfile() }
        if (profile == null) {
            toast(context.getString(R.string.spotify_cookie_invalid))
            return false
        }
        toast(context.getString(R.string.spotify_login_success, profile.username))
        return true
    }

    fun finishLogin(spDc: String) {
        scope.launch {
            if (persist(spDc)) {
                onBack()
            } else {
                handled.set(false)
            }
        }
    }

    fun submitManualSpDc() {
        val spDc = manualSpDc.trim()
        if (spDc.length < 20) {
            toast(context.getString(R.string.spotify_cookie_invalid))
            return
        }
        if (manualVerifying) return
        manualVerifying = true
        scope.launch {
            try {
                if (persist(spDc)) onBack()
            } finally {
                manualVerifying = false
            }
        }
    }

    AuthWebViewScreen(
        title = stringResource(R.string.spotify_login_title),
        subtitle = stringResource(R.string.spotify_login_subtitle),
        onBack = onBack,
        footer = {
            OutlinedTextField(
                value = manualSpDc,
                onValueChange = { manualSpDc = it },
                label = { Text(stringResource(R.string.spotify_manual_sp_dc)) },
                supportingText = { Text(stringResource(R.string.spotify_manual_sp_dc_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { submitManualSpDc() },
                enabled = !manualVerifying && manualSpDc.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (manualVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.spotify_manual_sp_dc_apply))
            }
        },
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val spDc = readSpDc() ?: return
                        if (!handled.compareAndSet(false, true)) return
                        finishLogin(spDc)
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                }
                resetAuthWebViewSession(ctx, this, clearCookies = false) {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(LOGIN_URL)
                }
            }
        }
    )
}
