# Suno API Notes

## ⚠️ Unofficial / Unstable

Suno does **not** publish a stable, documented, public REST API. All endpoints used by this app are derived from the currently shipped Suno web app bundles and are **subject to change without notice**.

## Current Endpoint Findings — 2026-07-31

The original MVP used old guessed endpoints under `https://suno.com/api/v1/*`:

| Old endpoint | Observed result |
|---|---|
| `https://suno.com/api/v1/playlists/` | HTTP 404 Next.js HTML error page |
| `https://suno.com/api/v1/playlist/{id}/` | HTTP 404 Next.js HTML error page |
| `https://suno.com/api/v1/me/` | HTTP 404 Next.js HTML error page |

The current web app bundles use `https://studio-api-prod.suno.com` and an OpenAPI-style client. Relevant paths discovered from the live JS bundles:

| Purpose | Method | Current path |
|---|---:|---|
| My playlists | GET | `https://studio-api-prod.suno.com/api/playlist/me?page=1&show_trashed=false&show_sharelist=false` |
| Saved/shared playlists from other profiles | GET | `https://studio-api-prod.suno.com/api/playlist/me?page=1&show_trashed=false&show_sharelist=true` |
| Playlist detail | GET | `https://studio-api-prod.suno.com/api/playlist/v2/{playlist_id}?page=1` preferred; fallback `https://studio-api-prod.suno.com/api/playlist/{playlist_id}/?page=1` |
| User session/config | GET | `https://studio-api-prod.suno.com/api/session/` |
| Current-user metadata | GET | `https://studio-api-prod.suno.com/api/user/metadata` |
| Session id helper | GET | `https://studio-api-prod.suno.com/api/user/get_user_session_id/` |
| Clip lookup | GET | `https://studio-api-prod.suno.com/api/clip/{clip_id}` |
| Library/history/generated songs feed | POST | `https://studio-api-prod.suno.com/api/feed/v3` |
| Track audio fallback | GET | `https://cdn1.suno.ai/{track_id}.mp3` |

## Authentication

Suno browser API calls require more than a raw cookie jar for private library endpoints:

- `Cookie:` header with Suno cookies
- `Authorization: Bearer <__session JWT>` extracted from the `__session` cookie
- browser-like `Origin: https://suno.com` and `Referer: https://suno.com/`

Important: `__session` JWTs are short-lived. The user-provided cookie export contained `__session` tokens that expired around one hour after creation. With an expired token, authenticated endpoints such as `/api/playlist/me` return HTTP 401 even when public profile/session endpoints still return HTTP 200.

## Cookie Export Handling

The app accepts Netscape cookie exports and normalizes them into a single HTTP `Cookie` header value before saving. Example input format:

```text
# Netscape HTTP Cookie File
.suno.com	TRUE	/	TRUE	1817018096	__session	<jwt>
```

The stored value becomes:

```text
__session=<jwt>; other_cookie=value
```

## Known Issues

- Private playlist sync fails with HTTP 401 if the pasted cookie export is stale/expired; export a fresh cookie from an active logged-in Suno browser session.
- Public endpoints can return HTTP 200 while private endpoints return 401, so `/api/session/` alone is not a sufficient auth check for library sync.
- Playlist response shape includes `playlists` and `playlist_clips[].clip`; parsers should support both direct tracks and nested playlist clip wrappers.
- Playlists saved from other profiles are not included by the default `show_sharelist=false` query; sync must also query `show_sharelist=true` and de-dupe by playlist id.
- `/api/playlist/me` returns explicit playlists, not necessarily every generated song. Accounts with generated songs but no playlists can validly return HTTP 200 with `0 total`; sync must also query `/api/feed/v3` with the signed-in `user_id` from `/api/user/metadata` and expose those clips as the synthetic local `My Songs` playlist.
- Other-profile playlist metadata/lyrics require the v2 playlist detail endpoint; summary/list responses may omit full `playlist_clips[].clip.metadata`.
- Audio files may be served as MP3, WAV, OGG, or expiring CDN URLs depending on generation mode.
- Suno may rotate endpoint paths without notice.

## Maintenance Checklist

If tracks stop downloading or playlists stop loading:

1. Probe `https://studio-api-prod.suno.com/api/playlist/me` with a fresh browser token.
2. Inspect the current Suno web JS bundle for `useUserPlaylists`, `/api/playlist/me`, and `/api/playlist/v2`.
3. Verify whether private endpoints return 401 (auth/session expired) or 404 (endpoint path changed).
4. Update `SunoApiClient` constants and parser shapes.
5. Rebuild and verify on-device with logcat.
