# Architecture

[← README](../README.md) · [Bahasa Indonesia](id/ARCHITECTURE.md)

## Modules

| Module | Language / UI | Contents |
|---|---|---|
| `core/` | Kotlin (pure JVM) | Shared logic: M3U playlists (`Sources`), EPG, channel arrangement (`LocalTv`), source checks & multi-stage fuzzy selection (`HlsProbe`, `StreamSelector`, `SourceHealth`), in-app DASH/DRM (`Drm`, `DashToHls`, `CencDecrypt`), logo/thumbnail scraping (`MediaScraper`), HLS recorder (`HlsRecorder`), search, play preferences (`PlayPrefs`), diagnostics (`DiagLog`), TV discovery, ADB (`TvAdb`), phone ↔ TV link (`TvLink`), OTA (`Updates`) |
| `launcher/` | Java, Android Views | Android TV Only Launcher (`tv.clean.home`, package `tech.kousozo.tahoetv.launcher`, label `Tahoe TV Launcher`, release `TahoeTV-Android-TV.apk`, requires `android.software.leanback`). No Compose, to stay light on 1 GB RAM |
| `companion/` | Kotlin, Jetpack Compose | Phone & Tablet App (`tech.kousozo.tahoetv`, label `Tahoe TV`, release `TahoeTV-Phone-and-Tablet.apk`): mobile/tablet Live TV, remote, install/set up the TV over ADB, notification inbox. Ships `TahoeTV-Android-TV.apk` in `assets/` |
| `desktop/` | Kotlin, Compose Multiplatform + JavaFX | macOS/Windows/Linux app: Live TV, DVR, IPTV server, Home TVs, remote + mirroring, update & push notifications |
| `server/` | Node.js + SQLite | Tahoe Control Plane (`https://tahoe.kousozo.tech`): anonymous device counts, real-time SSE & polled push notifications, update modal policy, remote channel overrides |
| `website/` | Static HTML/CSS/JS | [tahoe.kousozo.tech](https://tahoe.kousozo.tech): live landing page, channel directory, and dynamic release downloads |
| `tools/` | Python / shell | `tv.py` (ADB), `disable.sh` (safe debloat), `iptv_server.py` (Python IPTV server), `lucide.py` (icons), `readme-art.py` (README/website art) |

All three apps use the same `:core`, so playback fixes (for example detecting recorded broadcasts) apply everywhere.

## Live TV flow

1. **Load channels** (`Sources.loadAll` + `Sources.scheduleDailyRefresh`): public playlists (`iptv-org`, `dhanytv`, `dhanytv-ott`, `windozalmi`, `yositapjani`), the user's playlists, and local files, refreshed automatically every 24 hours. `MediaScraper` enriches channels with logos and live programme thumbnails.
2. **Arrange** (`LocalTv.arrange`): merge sources with the same name into one channel + backups, assign categories, and sort "near you" (province) first.
3. **Multi-stage check & fuzzy ranking** (`StreamSelector` / `HlsProbe` / `SourceHealth`):
   - **Stage 1 (Liveness):** `ENDLIST`/VOD → **REPLAY**, `PROGRAM-DATE-TIME` older than 15 minutes → **STALE**, invalid HTML/4xx/5xx → **DEAD**, otherwise **LIVE** (HLS & DASH `.mpd`).
   - **Stage 2 (Inspection):** measures response latency, max resolution (`height`), bandwidth, and ABR support.
   - **Stage 3 (Fuzzy Selection):** `StreamSelector.fuzzyScore` computes a `0..100` suitability score; `StreamSelector.rankOnOpen` benchmarks candidate servers in parallel when opening a channel so the best finalist plays first.

   Results (latency, height, bandwidth, score) are persisted in `SourceHealth`.
4. **Play:** sources are ordered by fuzzy score / live status, capped to the chosen quality and played at the chosen distance from live (`PlayPrefs`).
   - TV/phone: ExoPlayer (Media3) with fallback decoders + FFmpeg, plus direct `.mpd` DASH & `ClearKey`/`Widevine`/`PlayReady` DRM (`Drm`).
   - macOS desktop: JavaFX through local `HlsProxy` (including `HlsProxy.wrapDash` + `CencDecrypt` for DASH/ClearKey and header injection).
   - Windows desktop: JavaFX can't play HLS there, so the IPTV server's `/watch` page (hls.js / Shaka Player) plays in an Edge app window, driven by the app through `/api/watch`.
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
