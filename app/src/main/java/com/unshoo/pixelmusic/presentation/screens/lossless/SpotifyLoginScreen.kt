package com.unshoo.pixelmusic.presentation.screens.lossless

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.importer.spotify.SpotifyAccountManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpotifyLoginEntryPoint {
    fun spotifyAccountManager(): SpotifyAccountManager
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? Activity

    val spotifyAccountManager = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SpotifyLoginEntryPoint::class.java
        ).spotifyAccountManager()
    }

    val isLoggedIn by spotifyAccountManager.isLoggedInFlow.collectAsState(initial = false)
    val profile by spotifyAccountManager.profileFlow.collectAsState(initial = null)
    val customClientId by spotifyAccountManager.customClientIdFlow.collectAsState(initial = null)

    var clientIdText by remember(customClientId) { mutableStateOf(customClientId.orEmpty()) }
    var manualSpDc by remember { mutableStateOf("") }
    var manualVerifying by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun handlePkceLogin() {
        if (activity == null) {
            toast("Не удалось определить Activity")
            return
        }
        scope.launch {
            try {
                spotifyAccountManager.startPkceAuth(activity, clientIdText.ifBlank { null })
            } catch (e: Exception) {
                toast("Ошибка запуска авторизации: ${e.message}")
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
                spotifyAccountManager.saveSpDc(spDc)
                val prof = withContext(Dispatchers.IO) { spotifyAccountManager.verifyAndRefreshProfile() }
                if (prof == null) {
                    toast(context.getString(R.string.spotify_cookie_invalid))
                } else {
                    toast(context.getString(R.string.spotify_login_success, prof.username))
                    onBack()
                }
            } finally {
                manualVerifying = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spotify_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Spotify Header Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1DB954)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_headphones_24),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(
                text = stringResource(R.string.spotify_login_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.spotify_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Current logged-in state card
            if (isLoggedIn) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (profile?.avatarUrl != null) {
                            AsyncImage(
                                model = profile?.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile?.username ?: "Spotify подключен",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Сессия активна",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    spotifyAccountManager.logout()
                                    toast("Вы вышли из Spotify")
                                }
                            }
                        ) {
                            Text("Выйти")
                        }
                    }
                }
            }

            // Tab / Mode Selection: Cookie (Recommended) vs OAuth PKCE
            var selectedLoginMode by remember { mutableStateOf(0) } // 0: Cookie sp_dc, 1: OAuth PKCE

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(4.dp)
            ) {
                Surface(
                    onClick = { selectedLoginMode = 0 },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedLoginMode == 0) Color(0xFF1DB954) else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Cookie sp_dc (Быстро)",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selectedLoginMode == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedLoginMode == 0) Color.Black else MaterialTheme.colorScheme.onSurface
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }

                Surface(
                    onClick = { selectedLoginMode = 1 },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedLoginMode == 1) Color(0xFF1DB954) else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "OAuth 2.0 PKCE",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selectedLoginMode == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedLoginMode == 1) Color.Black else MaterialTheme.colorScheme.onSurface
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }

            if (selectedLoginMode == 0) {
                // Cookie sp_dc section
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = manualSpDc,
                        onValueChange = { manualSpDc = it.trim() },
                        label = { Text("Cookie sp_dc") },
                        placeholder = { Text("AQB... (вставьте значение cookie sp_dc)") },
                        supportingText = { Text("Самый надежный способ авторизации без сторонних серверов") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { submitManualSpDc() },
                        enabled = !manualVerifying && manualSpDc.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DB954),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (manualVerifying) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Применить cookie и войти", fontWeight = FontWeight.Bold)
                    }

                    // Instruction Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Инструкция: как получить sp_dc",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "1. Откройте сайт open.spotify.com в браузере (на телефоне в режиме ПК или на компьютере) и войдите в свой Spotify.\n" +
                                        "2. Откройте Инструменты разработчика (F12) или настройки сайта.\n" +
                                        "3. Перейдите во вкладку Application (Хранилище) → Cookies → https://open.spotify.com.\n" +
                                        "4. Найдите строку с именем sp_dc, скопируйте ее значение и вставьте в поле выше.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // OAuth PKCE Section
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Spotify требует зарегистрированное приложение разработчика для авторизации через OAuth 2.0 в браузере.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = clientIdText,
                        onValueChange = {
                            clientIdText = it.trim()
                            scope.launch { spotifyAccountManager.saveCustomClientId(it.ifBlank { null }) }
                        },
                        label = { Text("Ваш Spotify Client ID") },
                        placeholder = { Text("Например: 7a8b9c...") },
                        supportingText = { Text("Redirect URI в приложении Spotify: pixelmusic://spotify-callback") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (clientIdText.isBlank()) {
                                toast("Сначала введите свой Spotify Client ID из developer.spotify.com")
                            } else {
                                handlePkceLogin()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DB954),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Войти через Chrome Custom Tabs", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://developer.spotify.com/dashboard")
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                toast("Не удалось открыть браузер: ${e.message}")
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Создать Client ID на developer.spotify.com")
                    }
                }
            }
        }
    }
}
