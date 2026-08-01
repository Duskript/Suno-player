# Suno Local Player

An Android-native (Kotlin / Jetpack Compose) app for downloading Suno playlists and tracks for offline playback, with a playback-polish pass over the ElevenLabs-inspired dark audio player surface.

## Features

- **Cookie-based Suno sync** — enter your session cookie to authenticate with Suno's internal API.
- **Background download/resync** — uses Android WorkManager to fetch playlist metadata and audio files in the background, surviving app restarts.
- **Local playback** — powered by Android Media3 / ExoPlayer with play/pause, next, previous, and shuffle.
- **Save public playlists** — tap **+** and choose *Playlist URL* to save any public Suno playlist or creator URL; choose *Local playlist* to create a named mix from your downloaded tracks.
- **Update checker** — Settings → Updates compares the installed version against the latest GitHub release of [Duskript/Suno-player](https://github.com/Duskript/Suno-player/releases) and opens the release page in your browser when a newer build exists.
- **Persistent local library** — metadata and downloaded file paths are stored in app-private JSON.
- **Sync status & reliability** — the last sync result (time, counts, failures) persists across restarts and is shown on the Library page and in Settings → Library Sync; transient network failures retry automatically via WorkManager, and expired cookies surface re-login guidance.
- **Search & filters** — a search field on the Library page filters playlists by title/creator, with All / Downloaded only / Custom mixes filter chips; inside a playlist, a track search matches title, creator, lyrics, style prompt, and description prompt.

## Architecture

```
com.duskript.sunolocal/
├── core/
│   ├── auth/CookieStore.kt       — encrypted persistent cookie storage
│   ├── download/SunoDownloadWorker.kt  — WorkManager background sync
│   ├── network/SunoApiClient.kt  — Suno unofficial API client
│   ├── player/LocalAudioPlayer.kt — Media3 ExoPlayer wrapper
│   ├── storage/LibraryStore.kt   — JSON-based local library persistence
│   └── storage/SyncSummaryStore.kt — persisted last-sync result (suno_last_sync.json)
├── domain/model/
│   ├── SunoTrack.kt
│   ├── SunoPlaylist.kt
│   ├── SyncStatus.kt
│   └── SyncSummary.kt
├── features/
│   ├── library/
│   │   ├── state/LibraryViewModel.kt
│   │   └── ui/LibraryScreen.kt
│   └── settings/ui/SettingsScreen.kt
├── shared/ui/
│   ├── SunoLocalTheme.kt
│   └── ElevenLabsStylePlayer.kt
├── MainActivity.kt
└── SunoLocalApplication.kt
```

## Build Requirements

- **JDK 17+** — required by Android Gradle Plugin 8.7
- **Android SDK 35** — compileSdk target
- **Android Studio Ladybug** 2024.2+ (or Gradle CLI)

## Getting Started

1. **Clone or copy** this project to your machine.
2. Ensure `JAVA_HOME` points to JDK 17+ and `ANDROID_HOME` is set.
3. Run the debug build:
   ```bash
   cd suno-local-player
   ./gradlew assembleDebug
   ```
4. Install the APK on your device/emulator (API 26+).

> **Current build:** `0.1.3-search-filter` (versionCode 4) — builds with JDK 17+ and Android SDK platform 35 via the Gradle wrapper. Requires JDK 17+ (Android Gradle Plugin 8.7) and the corresponding SDK platform.

## Suno API Status

Suno does **not** provide a stable, documented public API. This app targets the internal/undocumented endpoints that the Suno web frontend uses. These endpoints may break without notice. See [docs/SUNO_API_NOTES.md](docs/SUNO_API_NOTES.md) for details.

## ElevenLabs React Components

The MVP's player UI is inspired by ElevenLabs' dark audio/conversation controls, implemented purely in Jetpack Compose. The `@elevenlabs/react` and `@elevenlabs/react-native` npm packages exist but contain conversation/transcription hooks — **not** a local music-player widget — and cannot be used in Kotlin-native Compose. See [docs/ELEVENLABS_REACT_NOTES.md](docs/ELEVENLABS_REACT_NOTES.md).

## License

MIT — see LICENSE file (not included in MVP).
