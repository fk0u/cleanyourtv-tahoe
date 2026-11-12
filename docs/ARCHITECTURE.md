# Architecture

[← README](../README.md) · [Bahasa Indonesia](id/ARCHITECTURE.md)

## Modules

| Module | Language / UI | Contents |
|---|---|---|
| `core/` | Kotlin (pure JVM) | Shared logic: M3U playlists, EPG, channel arrangement (`LocalTv`), source checks (`Sources`, `HlsProbe`, `SourceHealth`), HLS recorder (`HlsRecorder`), search, play preferences (`PlayPrefs`), diagnostics (`DiagLog`), TV discovery, ADB (`TvAdb`), phone ↔ TV link (`TvLink`), OTA (`Updates`) |
| `launcher/` | Java, Android Views | Android TV launcher (`tv.clean.home`, package `tech.kousozo.tahoetv.launcher`). No Compose, to stay light on 1 GB RAM |
| `companion/` | Kotlin, Jetpack Compose | Phone app (`tech.kousozo.tahoetv`): install/set up the TV over ADB, remote, Live TV, notifications to the TV. Ships the launcher APK in `assets/` |
| `desktop/` | Kotlin, Compose Multiplatform + JavaFX | macOS/Windows app: Live TV, DVR, IPTV server, Home TVs, remote + mirroring |
| `tools/` | Python / shell | `tv.py` (ADB), `disable.sh` (safe debloat), `iptv_server.py` (Python IPTV server), `lucide.py` (icons), `readme-art.py` (README/website art) |
| `website/` | Static HTML/CSS/JS | [tahoe.kousozo.tech](https://tahoe.kousozo.tech): profile, features, downloads, how-to; see [website/README.md](../website/README.md) |

All three apps use the same `:core`, so playback fixes (for example detecting recorded broadcasts) apply everywhere.

## Live TV flow

1. **Load channels** (`Sources.loadAll`): public playlists (iptv-org, dhanytv, yositapjani), the user's playlists and local files. The built-in sources are trimmed to free TV (`Sources.load`); DRM channels from the user's own playlists are kept and only play where a DRM player exists (the server dashboard's Shaka player).
2. **Arrange** (`LocalTv.arrange`): merge sources with the same name into one channel + backups, assign categories, and sort "near you" (province) first.
3. **Check sources** (`Sources.check` / `HlsProbe`) in parallel in the background:
   - `ENDLIST`/VOD playlist → **REPLAY**;
   - `PROGRAM-DATE-TIME` more than 15 minutes ago → **STALE** (yesterday's broadcast on repeat);
   - otherwise **LIVE**.

   Results are kept in `SourceHealth` (cache + sources the user marked).
4. **Play:** sources are ordered live-first, capped to the chosen quality and played at the chosen distance from live (`PlayPrefs`).
   - TV/phone: ExoPlayer with fallback decoders + FFmpeg.
   - macOS desktop: JavaFX through a local `HlsProxy` for sources that need headers (`Referer`, `Origin`, `User-Agent`).
   - Windows desktop: JavaFX can't play HLS there, so the IPTV server's `/watch` page (hls.js) plays in an Edge app window, driven by the app through `/api/watch`.
5. **Recover automatically:** a source that played and then dropped reconnects; one that never plays (20 seconds), stalls or turns out to be a recording → next backup.

## Phone ↔ TV

The launcher runs a server on **port 8765** (`Companion.java`, NanoHTTPD + WebSocket) and announces itself over mDNS `_tahoetv._tcp`.

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /`, `/manifest.webmanifest` | – | Web remote (PWA) |
| `GET /info` | – | TV identity (for the phone's scanner) |
| `POST /pair` | one-time 6-digit code | Returns a token |
| `GET /apps` | token | App list |
| `POST /upload?kind=photo\|wallpaper\|adzan` | token | Upload a file |
| `GET /log` | token | TV diagnostic report (Send log) |
| `POST /kids_pin` (body `{"pin":"1234"}`) | token | Reset the Kids profile PIN |
| `WS /ws` | token | Keys, text, volume, launch, phone notifications & music, TV status |

Install and first setup use **ADB over Wi-Fi** (`TvAdb`, dadb) from the phone/desktop.

## IPTV server (desktop)

`IptvServer.kt`, default port **8088**:

| Path | Purpose |
|---|---|
| `/` | Dashboard + web player (Shaka, ClearKey) |
| `/watch`, `/api/watch` | The desktop app's own web player (Windows); `/api/watch` answers only this computer, same-origin |
| `/playlist.m3u` (`?live=1` = live only) | Master playlist for TVs/phones/VLC/Kodi/TiviMate |
| `/epg.xml` | Program guide (6-hour cache) |
| `/live/<id>.m3u8`, `/live/<id>.mpd`, `/segment` | Relay with the headers each source needs (signed URLs) |
| `/api/status`, `/api/channels`, `/api/health` | Server status, channel list, source health |
| `/api/recordings`, `/recordings/<id>` | Recordings (list for the LAN; schedule/delete only from this computer) |

## Build & release

- JDK 17, Android SDK 36. Tests: `./gradlew :core:test :desktop:test`. Pull requests run them in CI ([`pr.yml`](../.github/workflows/pr.yml)).
- Release APKs: `./gradlew -PpublicBuild :launcher:assembleRelease :companion:assembleRelease` (ARM ABIs only, R8).
- A `v*` tag → GitHub Actions ([`desktop.yml`](../.github/workflows/desktop.yml)) builds the DMGs (Intel & Apple Silicon), MSI and both APKs and attaches them to the release. Release notes come from that version's section in [`CHANGELOG.md`](../CHANGELOG.md).
- APKs are signed with one release key (a CI secret), so OTA updates install in place.
- The website deploys from `website/` with [`website.yml`](../.github/workflows/website.yml).
