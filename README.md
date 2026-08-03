# Suno Local Player

An Android-native (Kotlin / Jetpack Compose) app for downloading Suno playlists and tracks for offline playback, with a playback-polish pass over the ElevenLabs-inspired dark audio player surface.

## Features

- **Cookie-based Suno sync** — enter your session cookie to authenticate with Suno's internal API.
- **Background download/resync** — uses Android WorkManager to fetch playlist metadata and audio files in the background, surviving app restarts.
- **Local playback** — powered by Android Media3 / ExoPlayer with play/pause, next, previous, and shuffle. Unplugging a wired headset or losing a Bluetooth audio route pauses playback instead of blasting through the speaker. The current track, queue, and position are saved while playing, so after an app restart the Library page offers a **Resume** card to pick up where you left off (always user-tapped, never automatic). MediaSession metadata now carries the creator as the album title, giving the lockscreen/notification a stable grouping label. Media button / lockscreen control: a single registered media button receiver (`SunoMediaButtonReceiver`, a Media3 `MediaButtonReceiver` subclass) routes wired-headset, Bluetooth, and lockscreen play/pause/next/previous/seek events into the shared MediaSessionService, and tapping the media notification opens the app.
- **Save public playlists** — tap **+** and choose *Playlist URL* to save any public Suno playlist or creator URL; choose *Local playlist* to create a named mix from your downloaded tracks.
- **Playlist manager** — rename or delete custom mixes (with confirmation), duplicate any playlist (custom or saved) into a new custom mix that copies the track list and order, and reorder custom mixes with the ↑/↓ controls on each track row. URL-saved playlists show their **Source URL** on the details page with **Open in Suno** / **Share** actions.
- **Update checker** — Settings → Updates compares the installed version against the latest GitHub release of [Duskript/Suno-player](https://github.com/Duskript/Suno-player/releases) and opens the release page in your browser when a newer build exists.
- **Persistent local library** — metadata and downloaded file paths are stored in app-private JSON.
- **Sync status & reliability** — the last sync result (time, counts, failures) persists across restarts and is shown on the Library page and in Settings → Library Sync; transient network failures retry automatically via WorkManager, expired cookies surface re-login guidance, and normal library sync stays playlist-scoped so it does not import every generated-song experiment. Foreground-service notification startup is best-effort so Android foreground-service restrictions do not abort sync. Playlist detail failures no longer silently collapse into empty zero-download syncs, and playlist detail JSON can use the known playlist summary as its metadata base when Suno omits duplicate playlist fields. Detail sync prefers Suno's live frontend endpoint (`/api/playlist/{id}/`) before the alternate v2 endpoint. Long-press a playlist, or tap Remove on a playlist row, to hide an unwanted synced playlist from future library resyncs; Settings → **Hidden playlists** shows the current count with a **Restore hidden playlists** action (run Resync Library afterwards to bring them back). v0.1.18 adds **Hide empty synced playlists** — a bulk cleanup that hides every non-custom, non-smart-mix synced playlist with zero tracks (often API/server placeholders) in one tap; hiding affects the local library only and cleanup tools never delete downloaded audio. Settings → **Download health** summarises the library (playlist / track / downloaded counts) plus the last sync's new / unchanged / failed totals, with cookie re-login guidance when a sync fails on an auth error — per-track failure lists are not tracked, so none are shown. Background playback preserves the shared Media3 player if Android tears down the media session service while audio is active.
- **Search & filters** — a search field on the Library page filters playlists by title/creator, with All / Downloaded only / Custom mixes filter chips; inside a playlist, a track search matches title, creator, lyrics, style prompt, and description prompt, and All / Favorites / Not downloaded chips further narrow the track list.

- **Favorites & smart mixes** — star any track to add it to your favorites (persisted app-private, never exported or sent to Suno). The Library page also shows derived, read-only smart mixes computed from your local library: **Favorites**, **Recently added** (newest downloads first), and **Not downloaded (streaming only)** — tracks playable over the network that have no local file yet. Smart mixes are never written to the stored library or included in backups.
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

> **Current build:** `0.1.21-durable-media-controls` (versionCode 22) — builds with JDK 17+ and Android SDK platform 35 via the Gradle wrapper. Requires JDK 17+ (Android Gradle Plugin 8.7) and the corresponding SDK platform. Durable media controls: next/previous buttons in the bottom player are now disabled (not just dimmed) when the queue cannot step, matching the Media3 notification/lockscreen command availability; play/pause/next/previous/seek from outside the app (notification, lockscreen, Bluetooth/headset, media keys) sync back into the in-app player state and diagnostics and persist the resume snapshot; Settings gains a one-tap **Allow Notifications** POST_NOTIFICATIONS request (Android 13+) with the open-settings fallback kept, plus next/previous availability rows in Player Diagnostics. The Media3 default media notification provider (channel `default_channel_id`) drives the outside-app controls — no custom provider was added. Playback is designed to continue in the background/screen-off while the queue has playable items and the user has not paused/stopped; note that Android 13+ notification permission may need to be granted for lockscreen/notification controls to appear. This batch does not claim a fix for any specific timed playback failure — bounded soak checks remain verification samples, not the product target. All existing flows (cookie dialog, add playlist, backup import/export via SAF, resume playback, queue, track details) are unchanged.

## Suno API Status

Suno does **not** provide a stable, documented public API. This app targets the internal/undocumented endpoints that the Suno web frontend uses. These endpoints may break without notice. See [docs/SUNO_API_NOTES.md](docs/SUNO_API_NOTES.md) for details.

## ElevenLabs React Components

The MVP's player UI is inspired by ElevenLabs' dark audio/conversation controls, implemented purely in Jetpack Compose. The `@elevenlabs/react` and `@elevenlabs/react-native` npm packages exist but contain conversation/transcription hooks — **not** a local music-player widget — and cannot be used in Kotlin-native Compose. See [docs/ELEVENLABS_REACT_NOTES.md](docs/ELEVENLABS_REACT_NOTES.md).

## License

MIT — see LICENSE file (not included in MVP).
