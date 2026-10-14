# PixelMusic — New Features, DSP Engine & Technical Architecture

This document provides a comprehensive, in-depth architectural and technical breakdown of all major systems, playback engines, DSP audio pipelines, and optimizations implemented in this release.

---

## 📑 Table of Contents
1. [Dual-Player Engine & Seamless Crossfade Transitions](#1-dual-player-engine--seamless-crossfade-transitions)
2. [Intelligent Queue Preloading & Gapless Caching Engine](#2-intelligent-queue-preloading--gapless-caching-engine)
3. [AutoEq Calibrated Headphone Presets & DSP Audio Suite](#3-autoeq-calibrated-headphone-presets--dsp-audio-suite)
4. [Apple Music-Style Smooth Lyrics & Karaoke Physics](#4-apple-music-style-smooth-lyrics--karaoke-physics)
5. [AI-Powered & Delimited Line-by-Line Lyrics Translation](#5-ai-powered--delimited-line-by-line-lyrics-translation)
6. [Material You 3 Expressive Language Selector Sheet](#6-material-you-3-expressive-language-selector-sheet)
7. [Google Assistant & Gemini Voice Control Integration](#7-google-assistant--gemini-voice-control-integration)
8. [Collision-Free Player Header Layout](#8-collision-free-player-header-layout)
9. [Decoupled Multi-Source Lyrics Resolution](#9-decoupled-multi-source-lyrics-resolution)
10. [Comprehensive Performance & Architectural Optimizations](#10-comprehensive-performance--architectural-optimizations)
11. [Studio Hi-Res Qobuz (Paxsenix API) & Tidal Multi-Source Streaming Engine](#11-studio-hi-res-qobuz-paxsenix-api--tidal-multi-source-streaming-engine)

---

## 1. Dual-Player Engine & Seamless Crossfade Transitions

### 🎯 Overview
Standard mobile media players suffer from awkward pauses, clicks, or abrupt cutoff between tracks. PixelMusic incorporates an advanced dual-engine playback architecture (`DualPlayerEngine`) orchestrating two independent ExoPlayer instances (`masterPlayer` and `nextPlayer`) to deliver true studio-grade crossfades and gapless transitions.

```
                      Song A Ending (Fade Out)
         1.0 ──────┐
                   \
  Volume            \   Equal-Power Curve
                     \
         0.0 ─────────┴─────────────────────► Time
                      /
                     /  Song B Starting (Fade In)
         1.0 ───────┘
             [ ◄── Transition Window (500ms - 12s) ──► ]
```

### ⚙️ How It Works Under the Hood
* **Two-Instance Architecture (`DualPlayerEngine`)**:
  * `masterPlayer` (Player A): Handles active foreground playback, system audio focus, and media notifications.
  * `nextPlayer` (Player B): An auxiliary player pre-buffered with the upcoming track and paused at millisecond 0.
* **Transition Orchestration (`TransitionController`)**:
  * Constantly monitors playback position via high-frequency coroutine polling.
  * When `currentPosition >= (duration - transitionDuration)`, the engine activates Player B and initiates the volume envelope curve.
  * Supports custom per-playlist transition overrides (`TransitionRepository`) as well as a global toggle and duration slider (500 ms to 12,000 ms).
* **Equal-Power Crossfade Algorithm**:
  * Instead of simple linear fading (which causes a perceived dip in volume at 50%), the engine calculates logarithmic/equal-power gain multipliers:
    $$\text{Gain}_{\text{Out}} = \cos\left(\frac{\pi}{2} \cdot t\right), \quad \text{Gain}_{\text{In}} = \sin\left(\frac{\pi}{2} \cdot t\right) \quad (t \in [0, 1])$$
  * Both tracks play concurrently through Android's `AudioTrack` mixer with constant total acoustic energy.
* **Atomic Player Swapping**:
  * Upon crossfade completion, the controller swaps instance roles: Player B becomes the new `masterPlayer`, while Player A is cleared and repurposed as the standby auxiliary player.
  * Active audio effects (Equalizer, Bass Boost, Virtualizer) and MediaSession listeners are re-bound seamlessly without audio clicks or popping.
* **Interactive UI Visualizer (`CrossfadeVisualizer`)**:
  * The transition settings screen renders an animated waveform overlap diagram displaying the real-time crossfade envelope.

---

## 2. Intelligent Queue Preloading & Gapless Caching Engine

### 🎯 Overview
To eliminate buffering delays, network hiccups, and playback stalls when moving to subsequent tracks (especially on variable cellular networks), PixelMusic features a proactive background caching engine (`QueuePreloadManager`).

```
Now Playing: [ Track #1 (Streaming / Playing) ]
                      │
                      ▼ (Triggered on playback start / 80% progress)
Next in Queue: [ Track #2 ] ──► [ QueuePreloadManager ]
                                        │
                                        ▼ (Non-blocking I/O Dispatcher)
                                [ CacheWriter / ExoCache ]
                                        │
                                        ▼
                                Disk Cache (`media_cache/`) ──► Ready for instant zero-latency start!
```

### ⚙️ How It Works Under the Hood
* **Proactive Prefetch Triggering**:
  * The moment the current track transitions or starts playing, `QueuePreloadManager` inspects the active playback queue.
  * The upcoming track is dispatched to a background coroutine (`Dispatchers.IO`) managed by a dedicated `SupervisorJob`.
* **Media3 `CacheWriter` Integration**:
  * Utilizes `androidx.media3.datasource.cache.CacheWriter` wrapping `ExoCache` (a persistent LRU disk cache).
  * Prefetches the critical first 2–5 MB of audio data (or entire file for standard bitrates).
  * When `DualPlayerEngine` primes `nextPlayer`, audio chunks resolve straight from disk cache in under 5 milliseconds with zero network round-trips.
* **Cooperative Cancellation**:
  * If the user taps "Next" or jumps to an arbitrary song in the queue, existing preload jobs are cooperatively cancelled (`Job.cancel()`).
  * Bandwidth and CPU resources are reallocated immediately to preload the newly targeted track.

---

## 3. AutoEq Calibrated Headphone Presets & DSP Audio Suite

### 🎯 Overview
PixelMusic includes a built-in 10-band parametric Digital Signal Processing (DSP) equalizer (`EqualizerViewModel`, `EqualizerManager`) integrated with calibrated **AutoEq** acoustic correction profiles for leading headphones, earphones, and IEMs.

```
┌────────────────────────────────────────────────────────┐
│  🎧 AutoEq Calibrated Profile: SONY WH-1000XM          │
│  Target: Harman In-Ear / Over-Ear Acoustic Standard    │
├────────────────────────────────────────────────────────┤
│  31Hz   62Hz   125Hz  250Hz  500Hz  1kHz  2kHz  4kHz  8kHz  16kHz
│  [-2]   [-4]   [-2]   [ 0]   [+1]   [+2]  [+3]  [+4]  [+3]  [+2] dB
└────────────────────────────────────────────────────────┘
```

### ⚙️ Calibrated Headphone Profiles
Tuned according to the industry-standard Harman acoustic research curves to eliminate muddiness, elevate vocal presence, and balance the frequency spectrum:

1. **Harman Target (`HARMAN_TARGET`)**:
   * Reference neutral target with a controlled sub-bass lift and smooth upper-treble contour.
2. **Apple AirPods Pro (`AIRPODS_PRO`)**:
   * Compensates for slight sub-bass roll-off and balances the 4kHz–8kHz presence region.
3. **Sony WH-1000XM Series (`SONY_WH1000XM`)**:
   * Tames the aggressive 100Hz–250Hz mid-bass bloat, restoring vocal transparency and airy soundstaging.
4. **Sennheiser HD6XX / HD650 / HD600 (`SENNHEISER_HD`)**:
   * Enhances low-end sub-bass extension (<60Hz) while preserving Sennheiser's benchmark midrange realism.
5. **Samsung Galaxy Buds (`GALAXY_BUDS`)**:
   * Harmonizes upper-mid peaks for comfortable, fatigue-free long listening sessions.
6. **Moondrop IEMs (`MOONDROP_IEM`)**:
   * Smooths the 3kHz pinna gain resonance and refines treble sparkle.

### ⚙️ DSP Audio Processing Features
* **10-Band Android OpenSL / AudioEffect Engine**: 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz.
* **Hardware Bass Boost**: Dynamic sub-harmonic frequency bass synthesizer with configurable intensity (0–1000‰).
* **Virtualizer (Spatializer)**: Head-Related Transfer Function (HRTF) widening for an expansive, out-of-head listening stage.
* **Loudness Enhancer**: Peak-controlled dynamic range compression based on Android's `LoudnessEnhancer` audio effect.
* **Dual-Player Effect Re-attachment**: Equalizer and DSP chains automatically re-bind across player swaps during crossfade transitions without dropping audio packets.

---

## 4. Apple Music-Style Smooth Lyrics & Karaoke Physics

### 🎯 Overview
Replaced static, abrupt line transitions with an Apple Music-style fluid experience featuring continuous gradient text sweeping and spring-based autoscroll physics.

```
Timestamp Progress:  [ 0.00s ───────── 1.25s ───────── 2.50s ]
Visual Effect:       [ Unsung Line ─► Sweeping Gradient ─► Full Active Glow ]
Scroll Motion:       [ Smooth Spring Autoscroll (Stiffness: Low, Damping: 0.85) ]
```

### ⚙️ How It Works Under the Hood
* **Smooth Sweeping Text Gradient**:
  * Instead of binary active/inactive line color switching, each active lyric line tracks its millisecond-level playback progress.
  * A horizontal sweep gradient dynamically masks the line from leading to trailing edge based on:
    $$\text{progress} = \frac{\text{currentTime} - \text{lineStartTime}}{\text{lineEndTime} - \text{lineStartTime}}$$
  * Active lines receive a subtle text scale and alpha bloom, while surrounding lines maintain a soft out-of-focus opacity.
* **Spring Autoscroll Engine**:
  * Removed jarring `scrollToItem()` snap jumps.
  * Integrated Jetpack Compose non-linear `spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioLowBouncy)` animations that calculate the target vertical viewport offset relative to user scroll velocity and focal center.
  * User touch gestures smoothly pause autoscroll via gesture detection and seamlessly resume tracking after a brief idle cooldown.

---

## 5. AI-Powered & Delimited Line-by-Line Lyrics Translation

### 🎯 Overview
A dual-engine lyrics translation system that guarantees strict line-by-line synchronization with the audio while providing poetic, context-aware translations.

```
Original LRC Lyrics (Time-synced)
           │
           ▼
HTML `<br>` Protocol Serialization  (Preserves strict line count & ordering)
           │
     ┌─────┴────────────────────────┐
     ▼                              ▼
[AI Orchestrator Engine]   [Google Translate Engine]
 (OpenAI, Gemini, Anthropic,      (Automatic zero-config fallback)
  DeepSeek, Local LLM)
     │                              │
     └─────┬────────────────────────┘
           ▼
1-to-1 Time-Preserved Translated LRC (Rendered in Karaoke & Full Player)
```

### ⚙️ How It Works Under the Hood
* **Delimited Protocol for 1:1 Synchronization**:
  * Traditional batch translation APIs frequently merge, split, or omit lines, causing translations to drift out of sync with audio timestamps.
  * The new `LyricsTranslator` packages lyric lines using atomic `<br>` HTML delimiters. Translation engines are instructed to preserve every delimiter without altering structure.
  * The response is split strictly along delimiters, guaranteeing an exact 1-to-1 mapping with the original LRC millisecond timestamps.
* **Intelligent Dual-Engine Dispatch**:
  1. **AI Engine (`AiOrchestrator`)**:
     * If an AI provider API key is saved (Settings → AI Integration), translation queries the `LYRICS_TRANSLATION` system prompt.
     * The model analyzes the overall artistic mood, slang, metaphors, and cultural context to generate natural, rhythmic, and poetic lyrics in the target language.
  2. **Google Translate Fallback Engine**:
     * If no AI key is configured or when offline/rate-limited, requests route automatically to Google Translate with zero configuration required.
* **Multi-Tier Caching**:
  * Translated lyrics are cached in memory and indexed by `(songId, targetLanguage)` to prevent duplicate API requests and minimize latency.

---

## 6. Material You 3 Expressive Language Selector Sheet

### 🎯 Overview
A native Material You 3 Bottom Sheet replacing legacy rectangular popup menus with fluid animations, dynamic accent colors, and support for 14 target translation languages.

```
┌──────────────────────────────────────────────┐
│                  ───────                     │
│  🌐 Translation Language                     │
│     Select language for lyrics & karaoke     │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │ ✨ Poetic translation via AI (Gemini)   │  │
│  └────────────────────────────────────────┘  │
│                                              │
│  (✓) Русский (RU)                            │
│  ( ) English (EN)                            │
│  ( ) Español (ES)                            │
│  ( ) Deutsch (DE)                            │
│  ( ) Français (FR)                           │
│  ... (14 languages total)                    │
└──────────────────────────────────────────────┘
```

### ⚙️ How It Works Under the Hood
* **Access Points**:
  * **Quick Trigger**: Long-press on the translation icon (`🌐`) in the lyrics top bar.
  * **Settings**: Navigate to **Settings → Appearance → Lyrics Screen → Translation Language**.
* **Visual & Expressive Styling**:
  * 28.dp rounded top corners with `BottomSheetDefaults.DragHandle()`.
  * Context-aware AI status badge that informs the user in real-time whether poetic AI translation or Google Translate is currently active.
  * 18.dp pill-shaped selection cards styled with dynamic Material 3 color schemes, accent checkmarks, and haptic feedback.
* **Full Multi-Locale UI Localization**:
  * Sheet headers, subtitles, and badges are fully localized across 10 system languages: English (`values/`), Russian (`values-ru/`), German (`values-de/`), Spanish (`values-es/`), French (`values-fr/`), Italian (`values-it/`), Korean (`values-ko/`), Indonesian (`values-in/`), Norwegian Bokmål (`values-nb/`), and Chinese (`values-zh-rCN/`).
* **Supported Target Translation Languages**:
  * Russian (`ru`), English (`en`), Spanish (`es`), German (`de`), French (`fr`), Italian (`it`), Ukrainian (`uk`), Kazakh (`kk`), Belarusian (`be`), Polish (`pl`), Chinese (`zh`), Japanese (`ja`), Korean (`ko`), Turkish (`tr`).

---

## 7. Google Assistant & Gemini Voice Control Integration

### 🎯 Overview
Full native integration with **Google Assistant** and **Gemini** on Android, allowing hands-free voice control across local and streaming libraries.

```
"Hey Google, play Queen in PixelMusic"
                  │
                  ▼
[Android OS Intent Dispatcher]
                  │
   Intent: `android.media.action.MEDIA_PLAY_FROM_SEARCH`
   Extras: `SearchManager.QUERY = "Queen"`
                  │
                  ▼
[MainActivity.kt / handlePlayFromSearch]
                  │
   Query Matcher: Songs ──► Artists ──► Albums ──► Shuffle All (Fallback)
                  │
                  ▼
[DualPlayerEngine / Media3 MediaLibrarySession]
                  │
             ▶️ PLAYING
```

### ⚙️ How It Works Under the Hood
1. **Voice Search Intent (`MEDIA_PLAY_FROM_SEARCH`)**:
   * Registered `android.media.action.MEDIA_PLAY_FROM_SEARCH` and `android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` in `AndroidManifest.xml`.
   * `handlePlayFromSearch(intent)` parses intent extras:
     * `SearchManager.QUERY`: raw query string.
     * `MediaStore.EXTRA_MEDIA_TITLE`: specific song title.
     * `MediaStore.EXTRA_MEDIA_ARTIST`: target artist.
     * `MediaStore.EXTRA_MEDIA_ALBUM`: target album.
     * `MediaStore.EXTRA_MEDIA_FOCUS`: search focus type.
   * **Resolution Cascade**:
     1. If song title is specified → searches songs and plays the best match.
     2. If artist is specified (or query matches an artist) → loads and plays artist catalog.
     3. If album is specified → plays the album.
     4. If query is empty (*"Play music in PixelMusic"*) → executes Shuffle All.
     5. If no exact match is found → gracefully falls back to Shuffle All.
2. **Media3 Background Playback Resumption (`onPlaybackResumption`)**:
   * Implemented `onPlaybackResumption` in `MusicService.kt`.
   * When the system or Google Assistant requests resumption while the app process is idle or backgrounded, Media3 loads the latest `PlaybackQueueSnapshot` from database storage and resumes playback at the exact saved position.
3. **Background Voice Routing via MediaSession (`onSetMediaItems`)**:
   * When commands originate from Android Auto, Bluetooth accessories, or locked screens, requests arrive via `MediaLibrarySession.Callback.onSetMediaItems`.
   * Intercepts `requestMetadata.searchQuery` to resolve songs directly in the service background without needing to launch the UI.
4. **Google Assistant App Actions (Built-in Intents)**:
   * Configured `shortcuts.xml` with Google Assistant BII capabilities:
     * `actions.intent.PLAY_MUSIC`: maps voice queries directly to `MEDIA_PLAY_FROM_SEARCH`.
     * `actions.intent.PAUSE_MUSIC`: pauses active playback.
     * `actions.intent.RESUME_MUSIC`: resumes playback.
     * `actions.intent.STOP_MUSIC`: stops playback.

---

## 8. Collision-Free Player Header Layout

### 🎯 Overview
Restructured the top navigation and action bar inside the full player and lyrics sheets to prevent overlaps between track metadata and action buttons on narrow screens or large font scales.

### ⚙️ How It Works Under the Hood
* **Weighted Flex Constraints**:
  * Track title and artist metadata are wrapped in a weighted column (`Modifier.weight(1f, fill = false)`).
  * Text automatically applies `TextOverflow.Ellipsis` with single-line max constraint, ensuring it never collides with action icons.
* **Dedicated Control Slots**:
  * The translation toggle button and additional options menu occupy fixed, non-collapsing slots with standardized 48.dp touch targets.

---

## 9. Decoupled Multi-Source Lyrics Resolution

### 🎯 Overview
Removed hardcoded intercepts that previously forced song lookups exclusively through LRCLIB, restoring a resilient multi-provider lyrics pipeline.

### ⚙️ How It Works Under the Hood
* Multi-source providers (Kugou, NetEase, Deezer, and local LRC files) now resolve in parallel with prioritized fallback order.
* If a provider fails or returns unsynced plain text, subsequent providers are queried automatically to deliver accurate synchronized timing.

---

## 10. Comprehensive Performance & Architectural Optimizations

### 🎯 Overview
Extensive performance optimizations have been executed across compilation pipelines, UI composition trees, background I/O threads, and local databases.

```
┌────────────────────────────────────────────────────────────────────────┐
│                      PIXELMUSIC PERFORMANCE STACK                      │
├────────────────────────────────────────────────────────────────────────┤
│  ⚡ Ahead-of-Time DEX: Baseline Profiles (`baselineprofile/`)          │
│  📦 Bytecode Optimization: R8 ProGuard Minification + Dead Code Stripping│
│  💾 Database Engine: Room DB Asynchronous Non-blocking Querying         │
│  🎨 UI Composition: Memoized Recomposition + 0-overhead Native ICU     │
│  🧠 Memory Architecture: In-Memory Multi-Tier Cache with WeakRefs      │
└────────────────────────────────────────────────────────────────────────┘
```

### ⚙️ Performance Pillars Under the Hood
1. **Android Baseline Profiles & AOT Compilation (`baselineprofile/`)**:
   * Implemented automated Macrobenchmark Baseline Profile generators (`BaselineProfileGenerator.kt`, `PlayerSheetAnimationBenchmarks.kt`, `StartupBenchmarks.kt`).
   * Generates ahead-of-time (AOT) machine code compilation profiles for critical paths: cold app startup, full-player sheet expand/collapse animations, and lazy list scrolling.
   * Eliminates Android runtime JIT interpretation stutter, providing rock-solid 60/120 FPS animations.
2. **Asynchronous Room Database & Debounced Queue Persistence**:
   * Removed synchronous SQLite locks from the main thread.
   * Playback queue snapshots (`PlaybackQueueSnapshot`) utilize a 150ms debounce window before persisting to disk, preventing write saturation during rapid song scrubbing or skipping.
   * Added compound indexing on song metadata, artists, and playlists to accelerate query execution.
3. **APK Footprint Reduction & 0-MB Japanese Romanization**:
   * Reduced APK download size by ~18 MB.
   * Replaced heavy bundled third-party dictionaries with Android's native `android.icu.text.Transliterator` engine, achieving instant Japanese Furigana and Romanization with zero APK weight.
4. **Jetpack Compose Rendering Optimizations**:
   * Replaced heavy double-composition `Crossfade` wrappers in media tab navigation with explicit state branching, preventing duplicate layout passes.
   * Extracted high-frequency lyrics animations (progress sweep, spring vertical scroll) into isolated derived state nodes to prevent parent screen recomposition.
5. **Memory Pooling & Image Cache Optimization (`AlbumArtCacheManager`)**:
   * Implemented hardware bitmap decoding with downsampling matched to screen density.
   * Integrated high-speed LRU memory cache with automatic trim on Android OS low-memory warnings (`onTrimMemory`).

---

## 11. Studio Hi-Res Qobuz (Paxsenix API) & Tidal Multi-Source Streaming Engine

### 🎯 Overview
Lossless audio streaming traditionally requires paid subscriptions, specialized tokens, or private reverse-engineered servers. PixelMusic now integrates a dual-layer Hi-Res resolution engine:
1. **Paxsenix API Integration for Qobuz**: Delivers studio-grade Hi-Res FLAC (up to 24-bit / 192kHz) through direct Akamai CDN delivery links without requiring a paid user account.
2. **Public Tidal & Monochrome Distributed Fallbacks**: Out-of-the-box streaming resolution through redundant public Hi-Fi and Monochrome nodes.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   LOSSLESS RESOLUTION PIPELINE                         │
├────────────────────────────────────────────────────────────────────────┤
│ 1. User Requests Track: "Manalan Mailla"                               │
│ 2. Priority #1: Paxsenix Qobuz Engine                                  │
│    ├── Query: GET api.paxsenix.org/qobuz/search?q=...                 │
│    ├── Match ID: `trackId` from Qobuz Catalog                         │
│    └── Stream: GET api.paxsenix.org/dl/qobuz?url=...&quality=...       │
│        └── Resolves: Direct Akamai CDN FLAC url                        │
│ 3. Priority #2: Tidal Distributed Nodes (Monochrome / Hi-Fi API)       │
│    └── Fallback: 16-bit / 44.1kHz Lossless FLAC / Hi-Fi                │
│ 4. Output: AudioTrack Hardware Direct / 24-bit Bit-Perfect Playback    │
└────────────────────────────────────────────────────────────────────────┘
```

### ⚙️ How It Works Under the Hood
* **Paxsenix Backend Integration (`QobuzAudioProvider.kt`)**:
  * Prioritizes Paxsenix API when an API key is configured (`QobuzAudioProvider.paxApiKey`).
  * Queries `https://api.paxsenix.org/qobuz/search?q={query}` with Bearer authentication to match track titles and artists against the official Qobuz catalog.
  * Requests raw download/stream streams via `https://api.paxsenix.org/dl/qobuz?url=https://open.qobuz.com/track/{id}&quality={quality}`.
  * Automatically maps requested audio quality across tiers:
    * `Studio Quality` / `Hi-Res`: 24-bit / up to 192kHz FLAC.
    * `CD`: 16-bit / 44.1kHz Lossless FLAC.
    * `320kbps`: 320 kbps MP3.
  * Extracts direct Akamai CDN streaming URLs (`directUrl`), enabling ExoPlayer to buffer and stream without proxy overhead or intermediary re-encoding.
* **Tidal Public Node Resiliency (`TidalAudioProvider.kt`)**:
  * Integrated a distributed fallback array of active public endpoints (`DEFAULT_DOWNLOAD_API_ENDPOINTS`):
    * `Monochrome (Samidy)` (`https://monochrome-api.samidy.com`)
    * `Monochrome API` (`https://api.monochrome.tf`)
    * `Hi-Fi API 1` (`https://hifi-api-w9y8.onrender.com`)
    * `Hi-Fi API 2` (`https://hifi-api-bffw.onrender.com`)
  * Automatically balances requests and falls back across nodes if an instance encounters rate limits or downtime.
* **Lossless Settings UI & Source Verification**:
  * Added configurable Paxsenix API Key input under **Settings → Lossless Sources → Qobuz**.
  * Dynamic source status check verifies key readiness in real-time (`SourceCheckService.kt`).

