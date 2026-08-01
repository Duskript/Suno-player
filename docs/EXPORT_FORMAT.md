# Suno Local Player — Library Backup Format (EXPORT_FORMAT.md)

This document describes the JSON format used by the Batch 6 export/backup
feature. The format is intentionally **identical to the app-private storage
schema** (`suno_library.json` written by `LibraryStore`), so:

- a backup exported from the app can be re-imported by the app itself,
- an export is human-readable and tool-parseable,
- app-private storage and backup files can never drift apart (both are
  serialized/parsed by the same helpers).

## Top-level format

The exported file is a **raw JSON array** of playlist objects:

```json
[
  { "id": "pl-1", "title": "My Favorites", "...": "..." },
  { "id": "pl-2", "title": "Custom Mix", "...": "..." }
]
```

On import the app also accepts a **wrapper object** form for forward
compatibility (the importer reads the `playlists` key):

```json
{
  "version": 1,
  "playlists": [ ... ]
}
```

An empty library exports as `[]`.

## Playlist object

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | string | yes | Unique playlist id (Suno playlist id, `custom-…` for local mixes). Playlists without a usable id are rejected on import. |
| `title` | string | yes | Display title (`Untitled` fallback when missing). |
| `creator_name` | string/null | no | Creator display name (`You` for custom mixes). |
| `source_url` | string/null | no | Suno page URL the playlist was saved from. |
| `saved_from_other_creator` | boolean | no | True for playlists saved from another creator's profile. |
| `is_custom` | boolean | no | True for user-built local mixes. |
| `last_synced_at_epoch_ms` | number | no | Epoch millis of the last sync. |
| `tracks` | array | no | Ordered list of track objects (below). |

## Track object

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | string | yes | Unique track id. Tracks without a usable id are skipped on parse. |
| `title` | string | yes | Track title (`Untitled` fallback when missing). |
| `audio_url` | string/null | no | Suno CDN audio URL. |
| `local_path` | string/null | no | Absolute local file path when downloaded; the offline playback reference. |
| `image_url` | string/null | no | Cover art URL. |
| `duration_ms` | number/null | no | Duration in milliseconds. |
| `playlist_id` | string/null | no | Id of the playlist this track record belongs to. |
| `creator_name` | string/null | no | Track creator display name. |
| `source_url` | string/null | no | Suno song page URL. |
| `lyrics` | string/null | no | Full generated lyrics (may contain newlines). |
| `style_prompt` | string/null | no | Style prompt / tags used to generate the song. |
| `description_prompt` | string/null | no | Description prompt used to generate the song. |
| `tags` | array of strings | no | Discovery tags (Batch 5); written as `[]` when empty. |
| `mood` | string/null | no | Discovery mood (Batch 5). |
| `genre` | string/null | no | Discovery genre (Batch 5). |
| `downloaded_at_epoch_ms` | number | no | Epoch millis when the audio was downloaded. |

## Backward compatibility

- Optional keys missing from a backup file load with their defaults
  (`null` / `false` / `0` / `[]`), so backups written by any older batch
  (which predate `tags`/`mood`/`genre`, for example) import unchanged.
- Batch 6 adds **no new fields** to the schema — it only reuses the existing
  one for export and import.
- Import accepts both the raw array and the `{"version": …, "playlists": […]}`
  wrapper; unknown keys are ignored.

## Duplicate handling on import

Import **never deletes existing library content** — it merges:

1. **Duplicate playlist ids:** if an incoming playlist's `id` already exists
   in the library, the whole incoming playlist is skipped (existing wins). Its
   tracks are not merged in.
2. **Duplicate track ids:** within an imported (non-duplicate) playlist,
   tracks with an id that already appeared earlier in that same playlist are
   dropped — first occurrence wins and order is preserved. (Track uniqueness
   is enforced per imported playlist, matching how the app's own storage
   behaves.)
3. The merged result (existing playlists first, then imported ones) is
   persisted and reloaded; the open playlist/creator selection is re-resolved.

The import result dialog reports counts: playlists added, playlists skipped,
tracks added, and duplicate tracks dropped.

## What is NOT exported

- **No cookies, credentials, or secrets.** The JSON contains only library
  metadata, local file references, and Suno media/`suno.com` URLs — exactly
  what app-private storage holds. Export is a pure serialization of the
  library; it never touches `CookieStore` or the WebView session.
- **No audio files.** Backup references local paths/URLs; it does not copy
  audio data.

## M3U export (single playlist)

Playlist details offer **Export M3U** (SAF `CreateDocument` with MIME
`audio/x-mpegurl`). The generated file is plain text:

```m3u
#EXTM3U
#EXTINF:217,Neon Drive
/data/user/0/com.duskript.sunolocal/music/t-1.mp3
#EXTINF:-1,Cloud Only
https://cdn1.suno.ai/t-2.mp3
```

- `#EXTINF` carries the duration in whole seconds (`-1` when unknown) and the
  track title.
- Each entry's location is `local_path` when the track is downloaded,
  otherwise `audio_url`, otherwise `source_url`. Tracks with no location at
  all are omitted (no dangling lines).
