# Suno Local Player Media Hardening Spec

> **For Hermes/Fusion:** Use this document as the source spec for the next Fusion command. The planner should inspect current code, then delegate feature batches one at a time. Executor must not commit/push/release/device-install; the planner/reviewer handles those gates.

**Current baseline:** `v0.1.19-main-screen-cleanup` / versionCode `20` / HEAD `a3b1976` at time of writing.

**User-reported gaps:**
- Playback still does not behave like a real media player: it keeps stopping unless the screen stays on.
- Controls outside the app are still not reliable enough: lockscreen, notification, Bluetooth/headset controls should be first-class.
- Suno cookies are not refreshing reliably; the user has to log back into Suno too often.

**Goal:** Bring the app to a “daily-driver media player” state: durable screen-off playback, real outside-app controls, predictable queue/resume behavior, clear playback diagnostics, and auth/cookie handling that refreshes from the in-app WebView session whenever possible before asking the user to log in again.

**Non-goals:**
- Do not integrate new external services.
- Do not import full Suno generation history by default.
- Do not delete downloaded audio or clear app data.
- Do not invent a way to extend Suno server-side JWT lifetime. If Suno invalidates the server session, the app must surface that honestly and make re-login fast.
- Do not silently install APK updates.

---

## Current Code Reality

### Playback files

- `app/src/main/java/com/duskript/sunolocal/core/player/SunoPlaybackEngine.kt`
  - Process-wide `ExoPlayer` singleton.
  - `MediaSession.Builder(...).setSessionActivity(...)` exists.
  - `shouldKeepPlaybackAlive()` preserves player while `isPlaying`, `playWhenReady`, `STATE_BUFFERING`, or `STATE_READY`.
  - `setWakeMode(C.WAKE_MODE_LOCAL)` is already configured.
- `app/src/main/java/com/duskript/sunolocal/core/player/SunoPlaybackService.kt`
  - Extends `MediaSessionService`.
  - Creates session in `onCreate()`.
  - Preserves shared player in `onDestroy()` when active.
- `app/src/main/java/com/duskript/sunolocal/core/player/SunoMediaButtonReceiver.kt`
  - Extends Media3 `MediaButtonReceiver`.
  - Registered as the single `MEDIA_BUTTON` receiver.
- `app/src/main/AndroidManifest.xml`
  - Has `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.
  - `SunoPlaybackService` declares `android:foregroundServiceType="mediaPlayback"`.
- `app/src/main/java/com/duskript/sunolocal/core/player/LocalAudioPlayer.kt`
  - UI-facing wrapper around the shared player.
  - Starts `SunoPlaybackService` before play with `startService()`.
  - Persists resume state every ~5s while position timer runs.

### Auth/cookie files

- `app/src/main/java/com/duskript/sunolocal/core/auth/CookieStore.kt`
  - Stores normalized cookie header.
  - Extracts `__session` JWT expiry when possible.
- `app/src/main/java/com/duskript/sunolocal/core/auth/WebViewCookieBridge.kt`
  - Reads cookies from app-owned WebView jar for `suno.com`, `www.suno.com`, and `studio-api-prod.suno.com`.
  - Saves cookie if `__session=` exists.
- `app/src/main/java/com/duskript/sunolocal/core/auth/SunoCookieRefreshWorker.kt`
  - Copies WebView cookie jar into `CookieStore`, but is not visibly scheduled in `SunoLocalApplication`.
- `app/src/main/java/com/duskript/sunolocal/core/download/SunoAutoSyncScheduler.kt`
  - Schedules `SunoDownloadWorker` every 15 minutes, Android’s minimum periodic cadence.
- `app/src/main/java/com/duskript/sunolocal/core/download/SunoDownloadWorker.kt`
  - Calls `WebViewCookieBridge.refreshCookieStore(cookieStore)` before sync.
  - Does not prove the refreshed cookie is newer or valid before continuing.
- `app/src/main/java/com/duskript/sunolocal/features/settings/ui/SettingsScreen.kt`
  - In-app Suno login WebView captures cookie on page finish and on Done.
  - Tells user to tap Done, then Test Connection.

---

# Phase 1 — Playback Must Survive Screen-Off / Background Like a Real Media App

## Objective

Stop playback from dying when the screen turns off or the Activity is backgrounded. The app must maintain a foreground Media3 playback notification while audio is active, expose usable lockscreen/notification controls, and produce diagnostics proving the service/player remain alive during screen-off.

## Required behavior

- Starting playback from the app must create/maintain a foreground media playback session.
- Locking the screen must not pause/stop audio.
- Turning the screen off for 10+ minutes must not release the shared `ExoPlayer`.
- Swiping app away must not kill active playback unless the user explicitly pauses/stops.
- Notification/lockscreen controls must include play/pause and skip next/previous when the queue supports them.
- Tapping notification/lockscreen artwork/control surface must reopen `MainActivity`.
- If Android notification permission is missing on Android 13+, Settings must surface that as a media-control problem with a user action.

## Implementation contract

### Files to inspect/modify

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/duskript/sunolocal/SunoLocalApplication.kt`
- `app/src/main/java/com/duskript/sunolocal/core/player/SunoPlaybackEngine.kt`
- `app/src/main/java/com/duskript/sunolocal/core/player/SunoPlaybackService.kt`
- `app/src/main/java/com/duskript/sunolocal/core/player/LocalAudioPlayer.kt`
- `app/src/main/java/com/duskript/sunolocal/core/player/SunoMediaButtonReceiver.kt`
- `app/src/main/java/com/duskript/sunolocal/features/settings/ui/SettingsScreen.kt`
- `app/src/main/java/com/duskript/sunolocal/features/library/state/LibraryViewModel.kt`
- `app/src/main/java/com/duskript/sunolocal/features/library/ui/LibraryScreen.kt`

### Specific additions/adjustments

1. **Explicit playback foreground-service lifecycle hardening**
   - Verify whether Media3 is actually promoting `SunoPlaybackService` to foreground while playing on target devices.
   - Add service/player instrumentation logs for:
     - service `onCreate`, `onGetSession`, `onTaskRemoved`, `onDestroy`
     - player state changes: `STATE_READY`, `STATE_BUFFERING`, `STATE_ENDED`, `isPlaying`, `playWhenReady`
     - notification/session lifecycle if Media3 exposes callbacks in the current API.
   - If Media3 default session notification is not durable enough, add a compile-safe Media3 `MediaNotification.Provider` or equivalent supported API for Media3 `1.5.1`. Do **not** guess API names; inspect dependency symbols or compile in a narrow branch.
   - Keep the existing singleton + `releaseIfIdle()` guard.

2. **Screen-off / wake / audio-focus audit**
   - Confirm `AudioAttributes` are still `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`.
   - Confirm `setWakeMode(C.WAKE_MODE_LOCAL)` is used and not removed.
   - Verify whether downloaded local files and remote URLs behave differently during screen-off.
   - If remote streaming stalls during screen-off, add explicit network/wake diagnostics and prefer downloaded local file URI when available.

3. **Notification permission UX**
   - Add a Settings media-control status section:
     - notification permission granted/missing
     - playback notification channel enabled/muted if inspectable
     - media session active when playing
   - Add user-mediated request/open-settings action for Android 13+ notification permission if not granted.
   - Do not block playback solely because notification permission is missing, but make the loss of outside-app controls obvious.

4. **Outside-app controls verification**
   - Verify `dumpsys media_session` shows an active `com.duskript.sunolocal/androidx.media3.session.id.suno-local-playback` session while playing.
   - Verify `cmd media_session dispatch play/pause/next/previous` or equivalent adb media key events affect playback.
   - Verify Bluetooth/headset `MEDIA_BUTTON` routing still uses exactly one receiver.

5. **User-facing playback diagnostics**
   - Add a lightweight Settings → Player diagnostics card with:
     - current track title or “none”
     - player state label
     - isPlaying/playWhenReady
     - queue length
     - service/session status if available
     - last player error, if any
   - This is not a debug dump; it is a support surface for “why did it stop?”

## Acceptance criteria

- [ ] **AC1:** With a downloaded track playing, `adb shell input keyevent 26` turns the screen off and playback continues for at least 10 minutes; logcat contains no `SunoPlaybackEngine.release`, no `SunoPlaybackService ... releasing shared player`, and no `FATAL EXCEPTION`.
- [ ] **AC2:** While screen is off and playback is active, `adb shell dumpsys media_session | grep -A20 com.duskript.sunolocal` shows an active session with playback state not `NONE`.
- [ ] **AC3:** ADB media key events affect playback:
  - `adb shell input keyevent KEYCODE_MEDIA_PAUSE` pauses.
  - `adb shell input keyevent KEYCODE_MEDIA_PLAY` resumes.
  - `adb shell input keyevent KEYCODE_MEDIA_NEXT` advances when a next item exists.
- [ ] **AC4:** Notification/lockscreen surface opens `MainActivity` when tapped on-device; if notification permission is missing, Settings clearly says outside-app controls may not appear and provides a user action.
- [ ] **AC5:** Backgrounding via Home and task switcher does not stop playback for at least 10 minutes.
- [ ] **AC6:** Swiping the app from recents while playback is active does not release the shared player; pausing/stopping then idling allows cleanup.
- [ ] **AC7:** Existing flows still pass: in-app controls, queue sheet, resume card, headset/Bluetooth unplug pause, media button receiver, and playlist playback.

## Verification commands

```bash
export JAVA_HOME="$HOME/.local/jdks/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

./gradlew testDebugUnitTest assembleDebug --console=plain --rerun-tasks
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt2"
$AAPT dump badging app/build/outputs/apk/debug/app-debug.apk | grep '^package'

adb -s 21251FDF60016K install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 21251FDF60016K shell monkey -p com.duskript.sunolocal -c android.intent.category.LAUNCHER 1
adb -s 21251FDF60016K logcat -c
# Human/automation starts a downloaded track here.
adb -s 21251FDF60016K shell input keyevent 26
sleep 600
adb -s 21251FDF60016K shell dumpsys media_session | grep -A40 -i 'com.duskript.sunolocal' || true
adb -s 21251FDF60016K logcat -d -t 3000 | grep -Ei 'SunoPlayback|MediaSession|FATAL EXCEPTION|AndroidRuntime|release|destroy|foreground|notification'
adb -s 21251FDF60016K shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 2
adb -s 21251FDF60016K shell input keyevent KEYCODE_MEDIA_PLAY
sleep 2
adb -s 21251FDF60016K shell input keyevent KEYCODE_MEDIA_NEXT
```

---

# Phase 2 — Real Outside-App Control Surface

## Objective

Make lockscreen/notification/Bluetooth controls feel native and predictable, not accidental.

## Required behavior

- Notification/lockscreen shows current song title, creator, artwork when available, and stable app identity.
- Actions: previous, play/pause, next. Seek may be present when duration is known.
- When paused from outside the app, bottom player state updates when the app is reopened.
- When next/previous is pressed outside the app, queue and current track state sync back into Compose.

## Implementation contract

### Files to inspect/modify

- `SunoPlaybackEngine.kt`
- `LocalAudioPlayer.kt`
- `SunoPlaybackService.kt`
- `SunoMediaButtonReceiver.kt`
- `ElevenLabsStylePlayer.kt`
- `LibraryScreen.kt`
- `SettingsScreen.kt`

### Specific additions/adjustments

1. **Controller-to-UI state sync**
   - Ensure `LocalAudioPlayer.syncStateFromPlayer()` runs on every relevant player event from external controllers.
   - Validate `MediaItem.localConfiguration.tag` survives MediaSession/controller operations; if not, add robust `mediaId -> track` restoration from persisted queue.

2. **Artwork handling**
   - Confirm remote artwork URI in `MediaMetadata` is acceptable for notification/lockscreen.
   - If artwork does not display, add a small image-loading path compatible with Media3 notification metadata or document limitation honestly.

3. **Command availability**
   - Ensure next/previous are disabled/unavailable when queue cannot handle them, or no-op safely.
   - Do not expose destructive queue controls outside app.

4. **Outside-control test harness**
   - Add a small debug-only or test-only helper, if needed, to start a known local playback queue for reliable adb tests.
   - Avoid shipping fake data or test-only UI in release behavior.

## Acceptance criteria

- [ ] **AC1:** With a 3-track queue playing, outside-app next/previous changes the track and the in-app bottom player reflects the same track after reopening.
- [ ] **AC2:** Outside-app pause/play updates the in-app play/pause icon state after reopening without requiring manual refresh.
- [ ] **AC3:** Lockscreen/notification displays track title and creator for tracks that have metadata.
- [ ] **AC4:** Tapping notification opens the app to the Library screen without starting a duplicate player.
- [ ] **AC5:** Repeated media key presses do not crash or desync queue state.

---

# Phase 3 — Cookie Refresh / Auth Reliability

## Objective

Stop making the user repeatedly paste/login unless Suno has truly invalidated the WebView session. The app should proactively capture WebView cookies, validate them, refresh before sync/download, and make re-login a one-tap recovery flow when server-side auth expires.

## Key reality

Suno’s `__session` appears to be a short-lived server-issued JWT. The app cannot extend or forge it. The only legitimate refresh path is to keep an authenticated in-app WebView session and copy fresh cookies from its cookie jar into `CookieStore`, then validate against `GET /api/playlist/me`. If the WebView session itself is logged out/expired, the app must ask the user to log in again.

## Required behavior

- Cookie status should show more than “Configured”: show expiry countdown when JWT `exp` exists.
- Before any manual sync or auto-sync, app should:
  1. copy WebView cookies into `CookieStore`,
  2. compare expiry/newness where possible,
  3. validate with `playlist/me` if the cookie is near-expired or previous sync got 401,
  4. only ask user to login if validation fails.
- In-app login should not require “Done → Test Connection” as two separate mental steps. Done should capture and validate automatically.
- Auto-sync should not overwrite a known-good stored cookie with an older/expired WebView cookie.
- If auth fails mid-download, app should attempt one fresh WebView-cookie recapture + validation before surfacing “login required”.

## Implementation contract

### Files to inspect/modify

- `app/src/main/java/com/duskript/sunolocal/core/auth/CookieStore.kt`
- `app/src/main/java/com/duskript/sunolocal/core/auth/WebViewCookieBridge.kt`
- `app/src/main/java/com/duskript/sunolocal/core/auth/SunoCookieRefreshWorker.kt`
- `app/src/main/java/com/duskript/sunolocal/core/download/SunoAutoSyncScheduler.kt`
- `app/src/main/java/com/duskript/sunolocal/core/download/SunoDownloadWorker.kt`
- `app/src/main/java/com/duskript/sunolocal/core/network/SunoApiClient.kt`
- `app/src/main/java/com/duskript/sunolocal/features/library/state/LibraryViewModel.kt`
- `app/src/main/java/com/duskript/sunolocal/features/settings/ui/SettingsScreen.kt`
- Tests under `app/src/test/java/com/duskript/sunolocal/core/auth/` and any worker/helper test locations.

### Specific additions/adjustments

1. **Cookie freshness model**
   - Add a small value type such as `CookieFreshness`:
     - `hasSession: Boolean`
     - `expiresAtEpochSeconds: Long?`
     - `secondsUntilExpiry: Long?`
     - `isExpired: Boolean`
     - `expiresWithin(windowSeconds: Long): Boolean`
   - Add helpers in `CookieStore` or a pure auth helper so tests can validate JWT parsing and status labels.

2. **Safe WebView-cookie adoption**
   - Replace boolean `WebViewCookieBridge.refreshCookieStore(cookieStore)` with a richer result, e.g. `CookieRefreshResult`:
     - `captured: Boolean`
     - `saved: Boolean`
     - `reason: String`
     - `newExpiresAt: Long?`
     - `oldExpiresAt: Long?`
   - Do not overwrite a stored cookie with a WebView cookie that has an older/equal `__session` expiry unless the stored cookie is missing.
   - Still preserve non-`__session` cookies from WebView when adopting a newer session.

3. **Capture + validate flow**
   - Add `LibraryViewModel.captureAndValidateWebViewCookie()`:
     - captures WebView cookie,
     - calls `apiClient.testConnection()`,
     - updates `cookieStatus` and `connectionTestStatus` with clear result.
   - Settings `Done` should call this flow, not just capture.
   - Settings login page should show “Captured, validating…” then “Valid” or “Login still required”.

4. **Pre-sync auth guard**
   - In `SunoDownloadWorker`, before `syncMyLibrary()`/`syncPlaylistUrl()`:
     - try safe WebView adoption,
     - if no cookie or expired/near-expired, validate if possible,
     - if validation fails with 401/403, save a `SyncSummary` with auth guidance and return failure/success according to manual vs auto semantics.
   - If download fails with auth error after playlist metadata fetch, perform one recapture + retry for that track or fail loudly with auth guidance; do not loop forever.

5. **Schedule cookie refresh worker or delete dead worker**
   - Current `SunoCookieRefreshWorker` exists but no obvious schedule was found.
   - Either:
     - schedule it explicitly with WorkManager and expose status, **or**
     - fold the richer refresh into `SunoDownloadWorker`/manual sync and remove dead worker if unused.
   - Prefer fewer moving parts unless periodic cookie-only refresh has a clear user benefit.

6. **Auth UX status**
   - Settings cookie section should show:
     - Missing / Valid / Expired / Rejected / Not tested
     - expiry countdown if known: “expires in ~12 min”
     - last refresh source: manual paste / WebView capture / auto pre-sync
     - last validation time/result
   - Add a button: **Refresh from Suno login** or **Refresh cookie from WebView** that captures + validates without leaving Settings if WebView session still exists.

## Acceptance criteria

- [ ] **AC1:** After logging in inside the app WebView and tapping Done, Settings automatically captures and validates; status becomes `Valid — playlist/me returned HTTP 200` without requiring a separate Test Connection tap.
- [ ] **AC2:** If stored cookie expires in less than 5 minutes and WebView has a newer `__session`, pre-sync adopts the newer cookie and sync proceeds without asking the user to login.
- [ ] **AC3:** If WebView has an older/expired cookie and stored cookie is still newer, refresh does **not** overwrite the stored cookie.
- [ ] **AC4:** If both stored and WebView cookies are expired/rejected, manual sync fails loudly with cookie-expired guidance and Settings shows Login to Suno as the recovery action.
- [ ] **AC5:** Auto-sync does not spam failures; it saves a concise `SyncSummary` like `Auto-sync skipped: login required` and does not clear library data.
- [ ] **AC6:** Unit tests cover JWT expiry parsing, freshness comparison, and “do not overwrite newer cookie with older WebView cookie”.
- [ ] **AC7:** No cookies, JWTs, or secrets are printed in logs/test output; logs may print booleans and expiry timestamps only.

## Suggested tests

- `CookieStoreTest.kt`
  - normalizes pasted cookie/header/Netscape export.
  - parses JWT `exp`.
  - computes freshness labels.
- New `WebViewCookieBridgeTest.kt` or pure helper test
  - chooses newer WebView session over older stored session.
  - refuses older WebView session when stored session is newer.
  - handles missing `__session`.
- Worker/auth guard tests if practical with pure helper extraction.

---

# Phase 4 — Queue, Offline Preference, and Resume Polish

## Objective

Make playback feel predictable even after app restarts, background churn, network stalls, and external controls.

## Required behavior

- Prefer downloaded local file path over remote `audioUrl` whenever `localPath` exists and file exists.
- If a remote stream stalls/fails while screen off, surface that as streaming/download issue, not generic “playback stopped”.
- Queue survives Activity recreation and external controls.
- Resume should restore queue and position without surprising autoplay except when user explicitly taps Resume.

## Acceptance criteria

- [ ] **AC1:** For a track with valid `localPath`, `MediaItem.uri` uses local file URI, not remote URL.
- [ ] **AC2:** If local file is missing but `audioUrl` exists, playback falls back to streaming and exposes that state in details/diagnostics.
- [ ] **AC3:** After external next/previous, closing/reopening app shows correct current track and queue.
- [ ] **AC4:** After process recreation, Resume reconstructs queue and seeks near saved position.
- [ ] **AC5:** Missing/corrupt files produce a readable playback error and guarded auto-skip; no infinite loop.

---

# Phase 5 — Release Train / Fusion Execution Plan

Run these as separate Fusion batches and push after each successful feature, same pattern as `v0.1.17`–`v0.1.19`.

## Batch A — Playback lifetime diagnostics + notification permission/status

**Target release:** `v0.1.20-playback-lifetime-hardening`, versionCode `21`.

**Scope:** instrumentation, Settings player diagnostics, notification permission/status, screen-off proof. Minimal behavior changes first so the next bug report has evidence.

**Files:** playback engine/service/player, Settings, README, Gradle.

**Gate:** build + 10-minute screen-off playback test + media session dump.

## Batch B — Durable foreground media notification / lockscreen controls

**Target release:** `v0.1.21-durable-media-controls`, versionCode `22`.

**Scope:** make media notification/lockscreen controls reliably visible and actionable; add provider only if Media3 1.5.1 API supports it cleanly.

**Gate:** notification/lockscreen controls verified on device; adb media keys play/pause/next/previous work while screen off.

## Batch C — Cookie freshness model + safe WebView adoption

**Target release:** `v0.1.22-cookie-freshness`, versionCode `23`.

**Scope:** pure cookie freshness helpers, no-overwrite-older-cookie rule, richer refresh result, tests.

**Gate:** unit tests for expiry/freshness/adoption; no secret logging.

## Batch D — Capture + validate login flow and pre-sync auth guard

**Target release:** `v0.1.23-auth-refresh-flow`, versionCode `24`.

**Scope:** Done validates automatically, Settings status improves, pre-sync refresh/validate guard, one retry on auth failure where safe.

**Gate:** login → Done → Valid without separate Test Connection; expired-cookie path fails loud with recovery action.

## Batch E — Queue/offline preference polish

**Target release:** `v0.1.24-media-player-polish`, versionCode `25`.

**Scope:** prefer local file URI, improve queue/state sync after external controls, refine resume diagnostics.

**Gate:** local playback survives screen off; external controls remain synced; missing/corrupt file behavior is clear.

---

# Fusion Prompt Template

Use this when invoking the build command:

```text
FUSION MODE TASK

You are the PLANNER/REVIEWER for this coding task. The active main model is the expensive reasoning model. The executor is configured through Hermes delegate_task as provider opencode-go, model deepseek-v4-flash.

User task:
Implement the next batch from docs/plans/MEDIA_PLAYER_HARDENING_SPEC.md. Start with Batch A unless I specify another batch. Push to GitHub after each successful feature release.

Fusion rules:
1. Load and follow docs/plans/MEDIA_PLAYER_HARDENING_SPEC.md.
2. Do prerequisite inspection yourself with read/search/safe terminal commands.
3. Do NOT directly edit implementation code or config files as planner.
4. Your only path to code changes is delegate_task to the executor.
5. Before delegating, write a strict Spec Contract with Objective / Files / Interfaces / Constraints / Verification.
6. Executor must return exactly STATUS / CHANGES / VERIFIED / GAPS.
7. After executor returns, independently review git diff, run forced Gradle build, badging, device smoke, and the batch-specific acceptance gate.
8. If clean, planner commits, pushes, creates GitHub release, installs APK on both devices, and reports proof.
9. If executor misses the spec once, narrow redelegate. If it misses twice, revise plan or dictate exact replacements.
```

---

# Global Verification Gates for Every Batch

```bash
git diff --check
export JAVA_HOME="$HOME/.local/jdks/jdk-17" ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" PATH="$HOME/.local/jdks/jdk-17/bin:$HOME/Android/Sdk/platform-tools:$HOME/Android/Sdk/cmdline-tools/latest/bin:$PATH"
./gradlew testDebugUnitTest assembleDebug --console=plain --rerun-tasks
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt2"
$AAPT dump badging app/build/outputs/apk/debug/app-debug.apk | grep '^package'
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb devices
adb -s 21251FDF60016K install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 21251FDF60016K shell monkey -p com.duskript.sunolocal -c android.intent.category.LAUNCHER 1
adb -s 21251FDF60016K shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb -s 21251FDF60016K logcat -d -t 800 | grep -Ei 'FATAL EXCEPTION|AndroidRuntime|ANR|ForegroundServiceDidNotStart|startForegroundService\(\) not allowed' || true
```

Repeat device install/smoke for `192.168.1.18:5555` when connected.

---

# Definition of Done

A batch is done only when:

- APK builds with `BUILD SUCCESSFUL`.
- APK badging has the expected versionCode/versionName.
- Unit tests for any new pure logic pass.
- Device install/launch smoke passes on both configured devices when connected.
- Batch-specific acceptance criteria are verified with real command/device output.
- GitHub commit pushed to `origin/main`.
- GitHub release exists with `suno-local-player-debug.apk` asset and SHA-256 in notes.
- README current-build line is honest and does not overclaim unverified behavior.
