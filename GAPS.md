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
