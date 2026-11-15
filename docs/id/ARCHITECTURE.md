# Arsitektur

[← README](../../README.id.md) · [English](../ARCHITECTURE.md)

## Modul

| Modul | Bahasa / UI | Isi |
|---|---|---|
| `core/` | Kotlin (JVM murni) | Logika bersama: playlist M3U (`Sources`), EPG, susunan saluran (`LocalTv`), pengecekan sumber & seleksi fuzzy multi-tahap (`HlsProbe`, `StreamSelector`, `SourceHealth`), pemutaran DASH/DRM (`Drm`, `DashToHls`, `CencDecrypt`), scraping logo/thumbnail (`MediaScraper`), perekam HLS (`HlsRecorder`), pencarian, log diagnostik (`DiagLog`), penemuan TV, ADB (`TvAdb`), link HP ↔ TV (`TvLink`), OTA (`Updates`) |
| `launcher/` | Java, Android Views | Aplikasi TV & HP potret (`tv.clean.home`, paket `tech.kousozo.tahoetv.launcher`, rilis `TahoeTV-App-for-TV-and-Phone.apk`). Tanpa Compose agar ringan di RAM 1 GB |
| `companion/` | Kotlin, Jetpack Compose | Aplikasi HP Remote & Setup (`tech.kousozo.tahoetv`, rilis `TahoeTV-Phone-Remote-and-Setup.apk`): pasang/atur TV lewat ADB, remote, Live TV, kotak masuk notifikasi. Membawa APK TV+HP di `assets/` |
| `desktop/` | Kotlin, Compose Multiplatform + JavaFX | App macOS/Windows/Linux: Live TV, DVR, server IPTV, TV Rumah, remote + cermin, serta notifikasi update & push |
| `server/` | Node.js + SQLite | Control Plane Tahoe (`https://tahoe.kousozo.tech`): statistik perangkat anonim, push notification SSE & polling, kebijakan modal pembaruan, override saluran |
| `website/` | HTML/CSS/JS Statis | [tahoe.kousozo.tech](https://tahoe.kousozo.tech): situs produk, direktori saluran publik, dan tautan unduhan dinamis |
| `tools/` | Python / shell | `tv.py` (ADB), `disable.sh` (debloat aman), `iptv_server.py` (server IPTV versi Python), `lucide.py` (ikon) |

Ketiga aplikasi memakai `:core` yang sama, jadi perbaikan pemutaran (misalnya deteksi siaran rekaman) berlaku di semua platform.

## Alur Live TV

1. **Muat saluran** (`Sources.loadAll` + `Sources.scheduleDailyRefresh`): playlist publik (`iptv-org`, `dhanytv`, `dhanytv-ott`, `windozalmi`, `yositapjani`), playlist pengguna, dan file lokal, dengan pembaruan otomatis tiap 24 jam. `MediaScraper` melengkapi logo saluran dan thumbnail acara yang sedang tayang.
2. **Susun** (`LocalTv.arrange`): gabungkan sumber dengan nama sama jadi satu saluran + cadangan, beri kategori, dan urutkan "dekat kamu" (provinsi) lebih dulu.
3. **Cek sumber multi-tahap & ranking fuzzy** (`StreamSelector` / `HlsProbe` / `SourceHealth`):
   - **Tahap 1 (Liveness):** playlist `ENDLIST`/VOD → **REPLAY**, `PROGRAM-DATE-TIME` lebih dari 15 menit lalu → **STALE**, HTML/4xx/5xx → **DEAD**, selain itu **LIVE** (HLS & DASH `.mpd`).
   - **Tahap 2 (Inspeksi):** mengukur latensi respons, resolusi maksimum (`height`), bandwidth, dan dukungan ABR.
   - **Tahap 3 (Seleksi Fuzzy):** `StreamSelector.fuzzyScore` menghitung skor `0..100`; `StreamSelector.rankOnOpen` menguji semua server kandidat secara paralel saat saluran dibuka agar server terbaik langsung dipilih.

   Hasil (latensi, resolusi, bandwidth, skor) disimpan di `SourceHealth`.
4. **Putar:** sumber diurutkan berdasarkan skor fuzzy tertinggi / status live. ExoPlayer Media3 (TV/HP) dengan dekoder cadangan + FFmpeg serta dukungan langsung DASH `.mpd` & DRM (`ClearKey`/`Widevine`/`PlayReady`); JavaFX di macOS lewat `HlsProxy` lokal (termasuk `HlsProxy.wrapDash` + `CencDecrypt`); dan Web Player (hls.js / Shaka Player) di Windows.
5. **Pulih otomatis:** sumber yang sempat tayang lalu putus disambung ulang; yang tidak pernah tayang (20 detik), macet, atau ternyata rekaman → sumber cadangan berikutnya.

## HP ↔ TV

Launcher menjalankan server di **port 8765** (`Companion.java`, NanoHTTPD + WebSocket) dan mengumumkan diri lewat mDNS `_tahoetv._tcp`.

| Endpoint | Auth | Fungsi |
|---|---|---|
| `GET /`, `/manifest.webmanifest` | – | Web remote (PWA) |
| `GET /info` | – | Identitas TV (untuk pemindai di HP) |
| `POST /pair` | kode 6 digit sekali pakai | Mengembalikan token |
| `GET /apps` | token | Daftar aplikasi |
| `POST /upload?kind=photo\|wallpaper\|adzan` | token | Unggah file |
| `GET /log` | token | Laporan diagnostik TV (Kirim log) |
| `POST /kids_pin` (body `{"pin":"1234"}`) | token | Atur ulang PIN profil Anak |
| `WS /ws` | token | Tombol, teks, volume, launch, notifikasi & musik HP, status TV |

Pemasangan dan pengaturan awal memakai **ADB lewat Wi-Fi** (`TvAdb`, dadb) dari HP/desktop.

## Server IPTV (desktop)

`IptvServer.kt`, default port **8088**:

| Path | Fungsi |
|---|---|
| `/` | Dashboard + web player (Shaka, ClearKey) |
| `/playlist.m3u` (`?live=1` = hanya yang live) | Playlist master untuk TV/HP/VLC/Kodi/TiviMate |
| `/epg.xml` | Jadwal acara (cache 6 jam) |
| `/live/<id>.m3u8`, `/live/<id>.mpd`, `/segment` | Relay dengan header yang dibutuhkan sumber |
| `/api/status`, `/api/channels`, `/api/health` | Status server, daftar saluran, kesehatan sumber |
| `/api/recordings`, `/recordings/<id>` | Rekaman (daftar untuk LAN; jadwal/hapus hanya dari komputer itu sendiri) |

## Build & rilis

- JDK 17, Android SDK 36. `./gradlew :core:test :desktop:test` untuk tes.
- APK rilis: `./gradlew -PpublicBuild :launcher:assembleRelease :companion:assembleRelease` (hanya ABI ARM, R8).
- Tag `v*` → GitHub Actions ([`.github/workflows/desktop.yml`](../../.github/workflows/desktop.yml)) membangun DMG (Intel & Apple Silicon), MSI, dan kedua APK, lalu melampirkannya ke rilis. Catatan rilis diambil dari bagian versi itu di [`CHANGELOG.md`](../../CHANGELOG.md).
- APK ditandatangani dengan satu kunci rilis (secret CI), jadi OTA memperbarui di tempat.
