# Arsitektur

[← README](../../README.id.md) · [English](../ARCHITECTURE.md)

## Modul

| Modul | Bahasa / UI | Isi |
|---|---|---|
| `core/` | Kotlin (JVM murni) | Logika bersama: playlist M3U, EPG, susunan saluran (`LocalTv`), pengecekan sumber (`Sources`, `HlsProbe`, `SourceHealth`), perekam HLS (`HlsRecorder`), pencarian, log diagnostik (`DiagLog`), penemuan TV, ADB (`TvAdb`), link HP ↔ TV (`TvLink`), OTA (`Updates`) |
| `launcher/` | Java, Android Views | Launcher Android TV (`tv.clean.home`, paket `tech.kousozo.tahoetv.launcher`). Tanpa Compose agar ringan di RAM 1 GB |
| `companion/` | Kotlin, Jetpack Compose | App HP (`tech.kousozo.tahoetv`): pasang/atur TV lewat ADB, remote, Live TV, notifikasi ke TV. Membawa APK launcher di `assets/` |
| `desktop/` | Kotlin, Compose Multiplatform + JavaFX | App macOS/Windows: Live TV, DVR, server IPTV, TV Rumah, remote + cermin |
| `tools/` | Python / shell | `tv.py` (ADB), `disable.sh` (debloat aman), `iptv_server.py` (server IPTV versi Python), `lucide.py` (ikon) |

Ketiga aplikasi memakai `:core` yang sama, jadi perbaikan pemutaran (misalnya deteksi siaran rekaman) berlaku di semua platform.

## Alur Live TV

1. **Muat saluran** (`Sources.loadAll`): playlist publik (iptv-org, dhanytv, yositapjani), playlist pengguna, dan file lokal. Disaring "TV gratis" (tanpa DRM, tanpa restream berbayar).
2. **Susun** (`LocalTv.arrange`): gabungkan sumber dengan nama sama jadi satu saluran + cadangan, beri kategori, dan urutkan "dekat kamu" (provinsi) lebih dulu.
3. **Cek sumber** (`Sources.check` / `HlsProbe`) paralel di latar:
   - playlist `ENDLIST`/VOD → **REPLAY**;
   - `PROGRAM-DATE-TIME` lebih dari 15 menit lalu → **STALE** (siaran kemarin yang diulang);
   - selain itu **LIVE**.

   Hasil disimpan di `SourceHealth` (cache + daftar sumber yang ditandai pengguna).
4. **Putar:** sumber diurutkan hidup-duluan. ExoPlayer (TV/HP) dengan dekoder cadangan + FFmpeg; JavaFX di desktop lewat `HlsProxy` lokal untuk sumber yang butuh header (`Referer`, `Origin`, `User-Agent`).
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
