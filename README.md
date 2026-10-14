# PixelMusic — New Features & Technical Architecture

This document provides a comprehensive breakdown of the features, architectural enhancements, and optimizations implemented in this release.

---

## 📑 Table of Contents
1. [Apple Music-Style Smooth Lyrics & Karaoke Physics](#1-apple-music-style-smooth-lyrics--karaoke-physics)
2. [AI-Powered & Delimited Line-by-Line Lyrics Translation](#2-ai-powered--delimited-line-by-line-lyrics-translation)
3. [Material You 3 Expressive Language Selector Sheet](#3-material-you-3-expressive-language-selector-sheet)
4. [Collision-Free Player Header Layout](#4-collision-free-player-header-layout)
5. [Google Assistant & Gemini Voice Control Integration](#5-google-assistant--gemini-voice-control-integration)
6. [Decoupled Multi-Source Lyrics Resolution](#6-decoupled-multi-source-lyrics-resolution)

---

## 1. Apple Music-Style Smooth Lyrics & Karaoke Physics

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

## 2. AI-Powered & Delimited Line-by-Line Lyrics Translation

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

## 3. Material You 3 Expressive Language Selector Sheet

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
  * Sheet headers, subtitles, and badges are fully localized across 10 system languages:
    * English (`values/`)
    * Russian (`values-ru/`)
    * German (`values-de/`)
    * Spanish (`values-es/`)
    * French (`values-fr/`)
    * Italian (`values-it/`)
    * Korean (`values-ko/`)
    * Indonesian (`values-in/`)
    * Norwegian Bokmål (`values-nb/`)
    * Chinese (`values-zh-rCN/`)
* **Supported Target Translation Languages**:
  * Russian (`ru`), English (`en`), Spanish (`es`), German (`de`), French (`fr`), Italian (`it`), Ukrainian (`uk`), Kazakh (`kk`), Belarusian (`be`), Polish (`pl`), Chinese (`zh`), Japanese (`ja`), Korean (`ko`), Turkish (`tr`).

---

## 4. Collision-Free Player Header Layout

### 🎯 Overview
Restructured the top navigation and action bar inside the full player and lyrics sheets to prevent overlaps between track metadata and action buttons on narrow screens or large font scales.

### ⚙️ How It Works Under the Hood
* **Weighted Flex Constraints**:
  * Track title and artist metadata are wrapped in a weighted column (`Modifier.weight(1f, fill = false)`).
  * Text automatically applies `TextOverflow.Ellipsis` with single-line max constraint, ensuring it never collides with action icons.
* **Dedicated Control Slots**:
  * The translation toggle button and additional options menu occupy fixed, non-collapsing slots with standardized 48.dp touch targets.

---

## 5. Google Assistant & Gemini Voice Control Integration

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

## 6. Decoupled Multi-Source Lyrics Resolution

### 🎯 Overview
Removed hardcoded intercepts that previously forced song lookups exclusively through LRCLIB, restoring a resilient multi-provider lyrics pipeline.

### ⚙️ How It Works Under the Hood
* Multi-source providers (Kugou, NetEase, Deezer, and local LRC files) now resolve in parallel with prioritized fallback order.
* If a provider fails or returns unsynced plain text, subsequent providers are queried automatically to deliver accurate synchronized timing.
