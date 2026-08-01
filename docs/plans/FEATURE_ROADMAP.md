# Suno Local Player — Feature Roadmap

This document is a **plan only**. Batches are listed in the order they should be
implemented. Each batch is small enough to review, verify, commit, and push on
its own. Nothing in this document is implemented yet — the current shipped
surface is described in the project `README.md`.

> ⚠️ **Commit/push checkpoint language is for the planner/human maintainer.**
> Executors working from these batches must **not** commit or push themselves;
> they return results to the planner, who runs the checkpoint after review.

---

## Batch 1 — Playback polish

**Goal:** make the player feel like a real music app, not a demo.

- [ ] Seek bar in the bottom player (drag to scrub), driven by Media3 position/duration.
- [ ] Show track duration and elapsed time in the player row.
- [ ] Repeat modes (off / all / one) in addition to shuffle.
- [ ] Playback error surface: if a local file is missing/corrupt, show a clear message and auto-skip to the next playable track.
- [ ] Keep queue/lyrics/details flows unchanged.

**Verification**
1. `export JAVA_HOME="$HOME/.local/jdks/jdk-17" ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" PATH="$HOME/.local/jdks/jdk-17/bin:$HOME/Android/Sdk/platform-tools:$HOME/Android/Sdk/cmdline-tools/latest/bin:$PATH"; ./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
2. Manual: play a track, scrub, cycle repeat modes, trigger a corrupt-file skip on device/emulator.

**Checkpoint:** review diff → `git add -A && git commit -m "feat(player): playback polish — seek, repeat, error skip"` → `git push` → tag a release if the APK changed behavior visibly.

---

## Batch 2 — Sync visibility & reliability

**Goal:** the user always knows what the library sync is doing and why it failed.

- [ ] Persist last sync result (timestamp, counts, error) and show it on the library page and in Settings → Library Sync.
- [ ] Per-playlist sync state: queued / syncing / done / failed with retry.
- [ ] Network failure retry with backoff inside `SunoDownloadWorker` (WorkManager retry policy).
- [ ] 401 cookie-expiry detection: surface "cookie expired — re-login in Settings" banner instead of a generic sync error.
- [ ] Keep the existing manual Resync in Settings and the auto-sync scheduler untouched.

**Verification**
1. `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
2. Kill network mid-sync on device; confirm retry and readable failure state; expire cookie and confirm the 401 banner.

**Checkpoint:** review diff → commit (`feat(sync): visibility and reliability — persisted status, retries, 401 banner`) → push → smoke-test on device.

---

## Batch 3 — Search / filter

**Goal:** find tracks and playlists without scrolling.

- [ ] Search field on the library page filtering playlists by title/creator.
- [ ] In-playlist track search (title / creator / lyrics text).
- [ ] Filter chips: All / Downloaded only / Custom mixes.
- [ ] Keep list item rendering and detail dialogs unchanged.

**Verification**
1. `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
2. Unit tests for the filter logic (pure functions over `List<SunoPlaylist>`/`List<SunoTrack>`).

**Checkpoint:** review diff → commit (`feat(library): search and filters`) → push.

---

## Batch 4 — Playlist manager

**Goal:** full CRUD over playlists instead of create-only.

- [ ] Rename and delete custom playlists (with confirm dialog).
- [ ] Duplicate a playlist (copy track list into a new custom mix).
- [ ] Drag-to-reorder tracks inside custom playlists (replacing the up/down buttons).
- [ ] Playlist details: show source URL for URL-saved playlists; share/export the playlist link.
- [ ] Keep `LibraryStore` as the single persistence layer.

**Verification**
1. `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
2. Manual: rename/delete/duplicate/reorder on device; verify order survives app restart.

**Checkpoint:** review diff → commit (`feat(playlists): manager — rename, delete, duplicate, drag reorder`) → push.

---

## Batch 5 — Metadata & discovery

**Goal:** richer track info and browsing by creator.

- [ ] Fetch and store full lyrics (multi-paragraph) and style/prompt metadata for all tracks on sync.
- [ ] Creator page: tap a creator name to list all their playlists/tracks in the library.
- [ ] "Similar tracks" hint using shared style prompt / genre keywords (local-only heuristic, no new network calls).
- [ ] Show song mood/genre tags on the track row and detail dialog when Suno provides them.
- [ ] Keep existing fields (`metadataSummary`, `descriptionPrompt`, `stylePrompt`) as the source of truth.

**Verification**
1. `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
2. Unit tests for lyrics extraction and creator grouping helpers.

**Checkpoint:** review diff → commit (`feat(metadata): lyrics depth, creator pages, similar tracks`) → push.

---

## Batch 6 — Export / backup

**Goal:** the library is portable and never locked in app-private JSON.

- [ ] Export library as a single JSON file via Android Storage Access Framework (SAF) — playlists + tracks + metadata + local file references.
- [ ] Import library JSON via SAF with conflict resolution (skip duplicates by track id).
- [ ] Optional: export a plain-text/m3u playlist file for one playlist.
- [ ] Document the JSON schema in `docs/EXPORT_FORMAT.md`.

**Verification**
1. `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
2. Unit tests: export → import round-trip preserves ids, order, custom playlist membership.
3. Manual: export, wipe app data, import, confirm library restored.

**Checkpoint:** review diff → commit (`feat(export): SAF JSON backup and restore`) → push → release APK with release notes.

---

## Housekeeping

- Each batch must keep the existing behavior working: WebView login, cookie
  auto-sync, `show_sharelist` playlist sync, Media3 playback, queue/lyrics/
  details, creator display, and the app-resume fix.
- No silent APK update/install logic ever — Android forbids it for normal apps;
  updates always go through the browser (Settings → Updates).
- Executors: do not commit, push, initialize git, or touch GitHub. Return the
  standard executor report; the planner runs the checkpoint.
