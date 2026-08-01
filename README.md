# Suno Local Player

An Android-native (Kotlin / Jetpack Compose) app for downloading Suno playlists and tracks for offline playback, with a playback-polish pass over the ElevenLabs-inspired dark audio player surface.

## Features

- **Cookie-based Suno sync** — enter your session cookie to authenticate with Suno's internal API.
- **Background download/resync** — uses Android WorkManager to fetch playlist metadata and audio files in the background, surviving app restarts.
- **Local playback** — powered by Android Media3 / ExoPlayer with play/pause, next, previous, and shuffle.
- **Save public playlists** — tap **+** and choose *Playlist URL* to save any public Suno playlist or creator URL; choose *Local playlist* to create a named mix from your downloaded tracks.
- **Playlist manager** — rename or delete custom mixes (with confirmation), duplicate any playlist (custom or saved) into a new custom mix that copies the track list and order, and reorder custom mixes with the ↑/↓ controls on each track row. URL-saved playlists show their **Source URL** on the details page with **Open in Suno** / **Share** actions.
- **Update checker** — Settings → Updates compares the installed version against the latest GitHub release of [Duskript/Suno-player](https://github.com/Duskript/Suno-player/releases) and opens the release page in your browser when a newer build exists.
- **Persistent local library** — metadata and downloaded file paths are stored in app-private JSON.
- **Sync status & reliability** — the last sync result (time, counts, failures) persists across restarts and is shown on the Library page and in Settings → Library Sync; transient network failures retry automatically via WorkManager, expired cookies surface re-login guidance, and normal library sync stays playlist-scoped so it does not import every generated-song experiment. Foreground-service notification startup is best-effort so Android foreground-service restrictions do not abort sync. Playlist detail failures no longer silently collapse into empty zero-download syncs, and playlist detail JSON can use the known playlist summary as its metadata base when Suno omits duplicate playlist fields. Detail sync prefers Suno's live frontend endpoint (`/api/playlist/{id}/`) before the alternate v2 endpoint.
- **Search & filters** — a search field on the Library page filters playlists by title/creator, with All / Downloaded only / Custom mixes filter chips; inside a playlist, a track search matches title, creator, lyrics, style prompt, and description prompt.
- **Metadata & discovery** — when Suno provides them, mood/genre/tag fields are fetched, stored (backward-compatible JSON), and shown on track rows and the track detail dialog. Tapping a creator name (track row, detail dialog, or playlist card) opens a local Creator view listing every playlist/track by that creator in the library — no network calls. Track details also show a local **Similar tracks** list scored from shared tags, genre, and style-prompt keywords, again from the library alone.
- **Export / backup** — export the whole library (playlists + tracks + metadata + local file references) as a single JSON file through Android's Storage Access Framework, or import a backup with duplicate-safe merging (existing playlist ids win, duplicate track ids are dropped; importing never deletes existing content). Playlist details also offer **Export M3U** to write a plain-text `.m3u` playlist (local paths when downloaded, Suno URLs otherwise). See [docs/EXPORT_FORMAT.md](docs/EXPORT_FORMAT.md) for the schema. No storage permissions are used and no cookies/secrets are ever exported.

## Architecture

```
com.duskript.sunolocal/
├── core/
│   ├── auth/CookieStore.kt       — encrypted persistent cookie storage
│   ├── download/SunoDownloadWorker.kt  — WorkManager background sync
│   ├── network/SunoApiClient.kt  — Suno unofficial API client
│   ├── player/LocalAudioPlayer.kt — Media3 ExoPlayer wrapper
│   ├── storage/LibraryStore.kt   — JSON-based local library persistence
│   ├── storage/LibraryBackup.kt  — pure export/import/M3U helpers (SAF backup)
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

> **Current build:** `0.1.12-playlist-endpoint-priority` (versionCode 13) — builds with JDK 17+ and Android SDK platform 35 via the Gradle wrapper. Requires JDK 17+ (Android Gradle Plugin 8.7) and the corresponding SDK platform.

## Suno API Status

Suno does **not** provide a stable, documented public API. This app targets the internal/undocumented endpoints that the Suno web frontend uses. These endpoints may break without notice. See [docs/SUNO_API_NOTES.md](docs/SUNO_API_NOTES.md) for details.

## ElevenLabs React Components

The MVP's player UI is inspired by ElevenLabs' dark audio/conversation controls, implemented purely in Jetpack Compose. The `@elevenlabs/react` and `@elevenlabs/react-native` npm packages exist but contain conversation/transcription hooks — **not** a local music-player widget — and cannot be used in Kotlin-native Compose. See [docs/ELEVENLABS_REACT_NOTES.md](docs/ELEVENLABS_REACT_NOTES.md).

## License

MIT — see LICENSE file (not included in MVP).
