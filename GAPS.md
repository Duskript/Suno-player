# GAPS — Known limitations & honest caveats

Tracked gaps for the Suno Local Player release train. A gap is removed only
when verified closed — never silently.

## Batch D (v0.1.23-auth-refresh-flow) — capture + validate login flow and pre-sync auth guard

- **One auth retry per worker run.** The mid-sync 401/403 retry (safe WebView
  adoption + `playlist/me` validation) is capped at one attempt per worker
  run, shared across playlist-fetch and per-track download failure paths. If
  Suno rejects again after the retry, the sync fails loudly with login-required
  guidance. No retry loops are possible by construction (`MAX_AUTH_RETRIES = 1`).
- **Retry only helps when the in-app WebView holds a session.** If the user
  never logged in through the app WebView (or that WebView session is also
  expired/rejected), the retry adoption finds nothing usable and login-required
  guidance surfaces immediately. The app cannot extend or forge Suno
  server-side JWTs — the only legitimate refresh path is a fresh authenticated
  WebView session.
- **Auto-sync skip returns success.** When the pre-sync guard decides login is
  required for an auto-sync, the worker returns `Result.success` with an
  "Auto-sync skipped: login required" `SyncSummary` so WorkManager does not
  retry-spam a user who simply has not logged in. Manual sync returns
  `Result.failure` with the same guidance.
- **Pre-sync validation window is 5 minutes.** A stored cookie that is missing,
  expired, or expires within 5 minutes triggers a live `playlist/me`
  validation before sync. A fresher cookie is trusted without a network probe
  to keep background sync quiet; cookies that expire mid-sync are caught by the
  single auth retry.
- **No endpoint changes.** `playlist/me` (via `SunoApiClient.testConnection()`)
  is the validation probe; private API paths were untouched in this batch.
- **Per-track download auth failures** are recorded as failed tracks with
  cookie guidance after the one retry; per-track failure lists are not
  persisted (pre-existing limitation, unchanged).

## Batch E (v0.1.24-media-player-polish) — queue/offline preference polish

- **Local-file preference is checked at queue-build time.** `PlaybackSource`
  verifies `File.exists && length > 0` when a queue is built (setQueue /
  addToQueue / resume). A local file deleted *after* the queue is already
  loaded cannot be re-checked without rebuilding the queue; that case surfaces
  as a source-aware playback error dialog (auto-skip guard still applies, one
  skip per item per session). No file watcher is installed.
- **Downloaded counts can lag reality.** `SunoTrack.isDownloaded` and playlist
  downloaded-track counts still reflect the recorded `localPath` (pure domain
  model, no I/O), so counts may overstate after files are removed outside the
  app. Only queue building uses the live file check.
- **Streaming fallback is logged, not shown in Settings.** A stale local path
  with a valid `audioUrl` streams instead, and logcat records the fallback
  ("Local file missing for …"). Settings → Player diagnostics (PlaybackDiagnostics)
  was intentionally left untouched this batch, so it has no dedicated
  source-label row; the missing-file guidance appears in the playback-error
  dialog and Resume messaging instead.
- **Resume with a salvageable queue.** When only the saved track is unplayable
  (missing local file, no audioUrl) but the rest of the saved queue is
  playable, tapping Resume starts from the first playable track and shows an
  explanatory message. All other failure modes (track missing from library,
  no playable tracks) stop with a message and do not start playback — Resume
  never auto-plays.
- **No data deletion.** This batch deletes no library records and no downloaded
  audio; missing-file guidance always points at resync/re-download as the
  recovery action.
