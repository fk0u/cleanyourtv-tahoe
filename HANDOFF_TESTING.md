# 📋 Hand-off Dokumen: Implementasi & Pengujian Tahoe IPTV Server, Live TV, Sports & VOD

Dokumen ini disusun untuk tim developer dan kontributor guna memverifikasi, melakukan *build*, serta menjalankan pengujian menyeluruh terhadap fitur **IPTV Server LAN**, **Pengkategorian Live TV & VOD**, serta **Pengecekan Status Siaran (Live vs Offline)** pada aplikasi Tahoe TV.

---

## 1. Ringkasan Implementasi

### A. Integrasi Penuh IPTV Server ke Aplikasi Desktop
* **Otomatis Berjalan saat Aplikasi Dibuka**: IPTV Server (`IptvServer.kt`) secara *default* langsung menyala pada *port* 8088 (`http://localhost:8088/` atau IP LAN) begitu aplikasi dimuat via [`Main.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Main.kt) dan [`Live.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Live.kt).
* **Seluruh Saluran Masuk Tanpa Disaring**: Menggantikan filter lawas `Sources.free()`, fungsi baru [`Sources.loadAll()`](core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt) memuat **986+ saluran secara lengkap** dari `koutv.m3u`, `anotheriptv.m3u`, direktori `~/.tahoetv/playlists/`, serta URL playlist kustom.
* **Header-Injecting Relay**: Saluran premium/restream yang mewajibkan header (`DENSGO`, `Referer: https://www.dens.tv/`, `detik.com`, `inisemar`) diproxy transparan sehingga tidak lagi terkena blokir HTTP 403 atau *stream reset*.

### B. Pengkategorian Lengkap & Detail (Sports, Movies, VOD, Hiburan, dll.)
Telah ditambahkan klasifikasi kategori cerdas pada [`LocalTv.kt`](core/src/main/kotlin/tech/kousozo/tahoetv/core/LocalTv.kt):
1. **Olahraga / Sports**: beIN Sports 1/2/3, SPOTV 1/2, Champions TV 1/2/3/5/6, Premier League, Soccer Channel, Sportstars, NBA, Eurosport, Golf, Fight Sports, MotoGP, F1 TV, Mola, TVRI Sport, WorldCup.
2. **Film & Sinema**: HBO, HBO Hits, HBO Family, Cinemax, Fox Movies, Cinema World, Celestial Movies, Hits Movies, Zee Bioskop, Galaxy, Flik, My Cinema, Bioskop Indonesia, Paramount.
3. **VOD (Video On Demand)**: Grup VOD INDO, file `.mp4`, `.mkv`, film/serial *on-demand*, dan klip.
4. **Hiburan & Serial**: tvN, K-Plus, AXN, Animax, Warner TV, HITS, Lifetime, ONE, Comedy Channels, Series Channels, Drama.
5. **Berita & Informasi**: CNN Indonesia, CNBC Indonesia, Metro TV, TVOne, Kompas TV, BeritaSatu, iNews, BBC, Al Jazeera, CNA, Bloomberg.
6. **Anak & Animasi**: Cartoon Network, Nickelodeon, Nick Jr, Boomerang, DreamWorks, BabyFirst, CBeebies, My Kidz, Animax, Space Toon.
7. **Dokumenter & Edukasi**: Discovery Channel, National Geographic, Nat Geo Wild, Animal Planet, History, BBC Earth, CGTN Doc, Crime+Investigation.
8. **Musik & Radio**: MTV, MTV Live, Trace Urban, AFN, RRI Net, Internet Radio.
9. **Gaya Hidup & Hobi**: Cooking, Food Network, HGTV, TLC, Otomotif, Arirang.
10. **Religi & Dakwah**: Rodja TV, Surau TV, Quran TV, TV9, Madani TV, Ahsan TV, Nabawi TV, Christian Channels.
11. **Nasional**: Trans TV, Trans7, RCTI, SCTV, Indosiar, MNCTV, GTV, ANTV, TVRI Nasional, RTV, BTV, dll.
12. **TVRI Daerah & TV Lokal**: Seluruh stasiun TVRI provinsi dan stasiun TV daerah (JTV, Padang TV, Bali TV, dll.).

### C. Pengecekan Server Otomatis (Live vs Offline)
* **Background Asynchronous Connectivity Checker**: Fungsi [`Sources.checkStatus()`](core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt) menguji ketersediaan setiap server/stream secara paralel (32 *worker threads*).
* **Indikator Visual di Tampilan Antarmuka**:
  * Badge **`LIVE`** (Warna Hijau `#30D158`) untuk saluran yang aktif dan servernya merespons.
  * Badge **`OFFLINE`** (Warna Merah `#FF453A`) untuk saluran yang tidak dapat diakses / mati / 404 / 403.
  * Badge **`CEK...`** saat sedang memeriksa koneksi di awal pemuatan.
* **Filter Cepat Status**: Di atas daftar saluran, pengguna dapat menyaring:
  * `Semua`
  * `Live (Jumlah)`
  * `Offline (Jumlah)`

### D. Web Player & Proteksi DRM
* Dashboard Web (`http://localhost:8088/`) telah diperbarui dengan **Shaka Player** yang mampu mendekripsi **ClearKey DRM** secara otomatis langsung di peramban, serta menampilkan *badge* `Relay`, `DASH`, `DRM`, dan `VOD`.
* Di aplikasi desktop, jika saluran berproteksi DASH/DRM dipilih, panel detail menyediakan tombol satu klik **"Buka Web Player"** untuk menonton dengan dekripsi DRM peramban.

---

## 2. File-file yang Diubah & Dibuat

| Modul / Lokasi | File | Keterangan Perubahan |
| :--- | :--- | :--- |
| `core` | [`core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt`](core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt) | Menambahkan `loadAll()`, `checkStatus()` dengan *callback progress*. |
| `core` | [`core/src/main/kotlin/tech/kousozo/tahoetv/core/LocalTv.kt`](core/src/main/kotlin/tech/kousozo/tahoetv/core/LocalTv.kt) | Menambahkan kategori `Sports`, `Movies`, `Vod`, `Entertainment`, `Documentary`, `Music`, `Lifestyle`; klasifikasi otomatis. |
| `core` | [`core/src/main/kotlin/tech/kousozo/tahoetv/core/M3u.kt`](core/src/main/kotlin/tech/kousozo/tahoetv/core/M3u.kt) | Menangkap `tvgId`, `drmType`, `drmKey` dari `#KODIPROP`. |
| `desktop` | [`desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Live.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Live.kt) | Integrasi `loadAll()`, auto-start server, background status check, badge `LIVE`/`OFFLINE`/`VOD`/`DRM`, filter status. |
| `launcher` | [`launcher/src/main/java/tv/clean/home/Live.java`](launcher/src/main/java/tv/clean/home/Live.java) | Menggunakan `Sources.loadAll()` dan kategori dinamis untuk Android TV launcher. |
| `companion` | [`companion/src/main/java/tech/kousozo/tahoetv/Live.kt`](companion/src/main/java/tech/kousozo/tahoetv/Live.kt) | Menggunakan `Sources.loadAll()` dan kategori dinamis pada aplikasi mobile. |
| `desktop` | [`desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/IptvServer.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/IptvServer.kt) | Native server JVM: Shaka Player, ClearKey auto-decrypt, tag VOD/DRM/DASH/Relay, endpoint `/api/channels` dengan `is_vod`. |
| `desktop` | [`desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/PlayerWindow.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/PlayerWindow.kt) | Penanganan ramah pengguna saat memutar DASH/DRM di JavaFX Media. |
| `desktop` | [`desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Main.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Main.kt) | Auto-start IPTV Server saat app menyala dan *graceful shutdown*. |
| `desktop` | [`desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Home.kt`](desktop/src/main/kotlin/tech/kousozo/tahoetv/desktop/Home.kt) | Memperbarui deskripsi siaran TV Live & VOD. |
| `tools` | [`tools/iptv_server.py`](tools/iptv_server.py) | Standalone Python server: deteksi VOD, badge VOD, Shaka Player auto-config. |
| `tests` | [`core/src/test/kotlin/tech/kousozo/tahoetv/core/LocalTvTest.kt`](core/src/test/kotlin/tech/kousozo/tahoetv/core/LocalTvTest.kt) | Menambahkan pengetesan untuk `Sports`, `Movies`, `Vod`, `Entertainment`. |
| `tests` | [`desktop/src/test/kotlin/tech/kousozo/tahoetv/desktop/IptvServerTest.kt`](desktop/src/test/kotlin/tech/kousozo/tahoetv/desktop/IptvServerTest.kt) | Pengujian *lifecycle* server, master playlist, API channels, dan web dashboard. |

---

## 3. Panduan Perintah Build & Testing

Jalankan perintah-perintah berikut di terminal:

### A. Menjalankan Unit & Integration Tests (Gradle)
```bash
# Jalankan test suite pada modul core dan desktop
./gradlew :core:test :desktop:test
```
*Ekspektasi*: Semua test (termasuk `LocalTvTest`, `SourcesTest`, `M3uTest`, dan `IptvServerTest`) lulus tanpa kesalahan (`BUILD SUCCESSFUL`).

### B. Menjalankan Server Standalone CLI (Python)
```bash
# Nyalakan server standalone
./tools/start-iptv-server.sh
```
Lalu verifikasi endpoint pada jendela terminal terpisah:
```bash
# 1. Cek status server
curl -s http://localhost:8088/api/status | jq .

# 2. Cek saluran VOD
curl -s http://localhost:8088/api/channels | jq '.channels[] | select(.is_vod == true) | {name, group, is_vod}'

# 3. Cek saluran Sports
curl -s http://localhost:8088/api/channels | jq '.channels[] | select(.group | test("Sport"; "i")) | {name, group}'

# 4. Cek ekspor M3U dengan KODIPROP
curl -s http://localhost:8088/playlist.m3u | grep -E "KODIPROP|EXTINF" | head -n 20
```

### C. Menjalankan Aplikasi Tahoe TV Desktop
```bash
./gradlew :desktop:run
```
*Hal yang perlu diverifikasi secara visual pada aplikasi desktop*:
1. **Layar TV Live**:
   * Amati bahwa jumlah saluran mencapai **900+ saluran** (menggabungkan `koutv.m3u` dan `anotheriptv.m3u`).
   * Amati proses pengecekan status di awal (`Memeriksa siaran (X/Y)...`).
   * Saluran yang aktif menampilkan badge hijau **`LIVE`**, sedangkan yang mati menampilkan badge merah **`OFFLINE`**.
   * Tes filter tombol status: klik `Live (...)` dan pastikan hanya saluran yang aktif yang tampil.
   * Tes kategori di sidebar: klik `Olahraga`, `Film & Sinema`, `VOD & Film`, `Nasional` dan pastikan saluran tersaring dengan benar.
2. **Layar Setelan (Settings)**:
   * Masuk ke tab **Setelan** &rarr; bagian **Server IPTV (LAN)**.
   * Pastikan status server menampilkan tanda hijau **Aktif**, tombol Salin M3U/EPG berfungsi, dan tombol **Buka Web Player** membuka peramban ke `http://localhost:8088/`.
3. **Web Player di Peramban**:
   * Buka `http://localhost:8088/`.
   * Klik saluran berproteksi ClearKey DRM / DASH; Shaka Player akan otomatis mengonfigurasi `clearKeys` dan memutar siaran.

---

## 4. Checklist Verifikasi Mandiri untuk Tim Rilis v2.9.5

- [x] `./gradlew :core:test :desktop:test` berhasil dijalankan dengan status `BUILD SUCCESSFUL`.
- [x] `./gradlew -PpublicBuild :launcher:assembleRelease :companion:assembleRelease :desktop:assemble` berhasil dikompilasi tanpa error.
- [x] Kategori `LocalTv.Category.Sports` dan `LocalTv.Category.Vod` terdaftar dan dikenali di seluruh platform (Android TV, Android Mobile, Desktop).
- [x] Fungsi `Sources.loadAll()` menyertakan fallback classpath resources (`/koutv.m3u` & `/anotheriptv.m3u`) sehingga 986+ saluran tersedia langsung di semua platform tanpa file eksternal.
- [x] ExoPlayer di Android TV (`launcher`) dan Android Mobile (`companion`) dilengkapi header relay injection dan pustaka `androidx.media3:media3-exoplayer-dash`.
- [x] GitHub Actions Workflow rilis (`.github/workflows/desktop.yml`) mengompilasi dan mengunggah 5 installer lengkap:
  1. `TahoeTV-macos-arm64.dmg` (macOS Apple Silicon)
  2. `TahoeTV-macos-intel.dmg` (macOS Intel)
  3. `TahoeTV-windows.msi` (Windows Installer)
  4. `tahoe-launcher.apk` (Android TV)
  5. `tahoe-tv.apk` (Android Companion)

