# Handoff: lanjutan Redesign Tahoe TV 4.0 (untuk Gemini)

> `[HANDOFF] to=Gemini · date=2026-11-18 · main=7792227 · rilis terakhir=v3.5.0-alpha.12 ("v3.500.12 Alpha Dev", prerelease)`
>
> Baca dokumen ini sampai habis sebelum mengubah kode. Rencana desain lengkap ada di [`docs/design/REDESIGN-4.md`](design/REDESIGN-4.md); epic di GitHub: #184.

## 1. Posisi sekarang

| Hal | Status |
| --- | --- |
| Versi di `gradle.properties` | `tahoe.version=3.5.0`, `tahoe.versionCode=32` |
| Rilis terakhir | [v3.5.0-alpha.12](https://github.com/fk0u/cleanyourtv-tahoe/releases/tag/v3.5.0-alpha.12), judul "v3.500.12 Alpha Dev", prerelease, 8 aset (2 APK TV, APK HP, DMG arm64 + Intel, MSI, DEB, SHA256SUMS). Semua job CI hijau. |
| Kenapa bukan 3.500.12 | Installer Windows (MSI) membatasi angka tengah ≤ 255. Pemilik repo memilih tag `3.5.0-alpha.12` dengan judul rilis "v3.500.12 Alpha Dev". |
| Figma | [tahoeApp](https://www.figma.com/design/W3FBycV5KKVCD5RWHDk3kV/tahoeApp), file key `W3FBycV5KKVCD5RWHDk3kV` |
| Permintaan terakhir pemilik | "Tampilan aplikasi semuanya harus sama seperti di Figma." Baru 5 layar HP yang selesai (lihat §5). |

## 2. Aturan wajib (dari pemilik repo, jangan dilanggar)

1. **`:launcher` (aplikasi TV) = Android Views murni (Java).** Jangan tambahkan Jetpack Compose, jangan pakai blur real-time, tetap `minSdk 26`. Target perangkat: TV stick RAM 1 GB.
2. **Logika non-Android masuk `:core`** beserta unit test di `core/src/test/...`.
3. **Patuhi `architecture.json`.** URL dan log selalu lewat `DiagLog.redact`. Jangan commit `secrets.properties`, jangan menyentuh atau membocorkan kredensial internal.
4. **Screenshot selalu dalam mode demo.** Desktop `--demo`; HP (build debug) `--es demo <lang>` (lihat §3).
5. **Jangan menduplikasi fitur yang sudah ada di `main`.** Baca file terkait dulu sebelum mengedit.
6. **Audit keamanan `git diff` sebelum commit**, lalu **commit dan push satu per satu per modul** ke `origin/main` dengan urutan `:core` → `:companion` → `:desktop` → `:launcher` → `website/` & `server/` → docs/CHANGELOG. **Jangan digabung sekaligus.**
7. **Atribusi:** setiap commit diakhiri `Co-Authored-By: bensoncloud <bensoncloud@bot.cue.im>`. Teks PR, issue dan catatan rilis tidak menyebut nama asisten AI.
8. **Kirim apa pun ke luar (komentar issue, rilis, tag) hanya bila pemilik memintanya.**
9. **Teks UI selalu lewat `I18n`** (`core/.../I18n.kt`, tabel EN/ID/ZH) **plus** `core/src/main/resources/i18n/ar.json` (Arab, RTL). Kunci baru wajib ada di keempatnya; `I18nTest` mengeceknya.
10. **Gerak** mengikuti [emilkowalski/skills](https://github.com/emilkowalski/skills): ease-out `0.23,1,0.32,1`, drawer `0.32,0.72,0,1`, ease-in-out `0.77,0,0.175,1`; durasi 120/220/300/340/700 ms; tidak pernah ease-in; "Kurangi gerakan" = fade saja. Konstanta ada di `Brand.kt` (`MOTION_*`, `EASE_*`).

## 3. Build, tes, dan menjalankan aplikasi

Mac ini tidak punya Java atau Node di PATH. Pakai JBR dari Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=~/Library/Android/sdk
```

Pemeriksaan lengkap sebelum commit atau rilis (sama dengan CI):

```bash
./gradlew -PpublicBuild :core:test :desktop:test :launcher:lintDebug :companion:lintDebug :launcher:assembleRelease :companion:assembleRelease
```

Catatan penting:
- **Baris `e: ... Module was compiled with an incompatible version of Kotlin ... expected 2.2.0` itu BUKAN error build.** Itu keluaran `:launcher:lintVitalRelease` (analyzer lint) yang ikut jalan saat `:companion:installDebug`. Lihat baris `BUILD SUCCESSFUL/FAILED`. Kalau ragu, jalankan `./gradlew :companion:compileDebugKotlin`.
- Kalau Gradle aneh, `./gradlew --stop` lalu ulangi.
- Server Node (`server/`) dites dengan `cd server && npm test` (Node ≥ 22). Node tidak terpasang di Mac ini; CI juga belum menjalankan tes server.
- Website: `sh website/build.sh && python3 -m http.server -d website/_site 8080`. `BrandTokensTest` di `:core` ikut mengecek token di `website/assets/style.css`.

**Emulator** (tanpa jaringan, jadi pakai mode demo):

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_10_Pro_XL -gpu host -no-snapshot-save -no-audio &
./gradlew :companion:installDebug
adb shell am force-stop tech.kousozo.tahoetv.dev
adb shell am start -W -n tech.kousozo.tahoetv.dev/tech.kousozo.tahoetv.MainActivity --es demo id
adb exec-out screencap -p > shot.png
```

- AVD TV: `Television_1080p`. Paket debug HP: `tech.kousozo.tahoetv.dev`.
- Layar emulator 1344×2992 px. Ketuk dock: Beranda ≈ (200, 2778), Live TV (436, 2778), Remote (672, 2720), Watchlist (900, 2778), Profil (1145, 2778).
- Mode demo HP memuat `Demo.channels()`, `Demo.epg()` dan `Demo.viewer()` dari `:core`. Tidak ada yang disimpan; 2 saluran pertama jadi favorit di memori.
- Emulator lambat; kalau muncul "System UI isn't responding", ketuk **Wait**.

## 4. Peta kode yang paling sering disentuh

| Modul | File | Isi |
| --- | --- | --- |
| `:core` | `Brand.kt`, `docs/brand/tokens.json` | Warna, ukuran, gerak (sumber kebenaran token) |
| `:core` | `Theme.kt` | Palet Gelap/Terang/Kontras Tinggi |
| `:core` | `I18n.kt`, `resources/i18n/ar.json` | Semua teks UI |
| `:core` | `Demo.kt` | Data demo untuk screenshot |
| `:core` | `PairRequests.kt`, `TvLink.kt` | Pairing tanpa kode (#180) |
| `:companion` (HP, Compose) | `Ui.kt` | Komponen Figma: `Chip`, `PillButton` (Primary), `GlassPill`, `RoundGlass` (Icon Button 48 dp), `LiveBadge`, `ProgressBar`, `Backdrop` |
| `:companion` | `MainActivity.kt` | Beranda: hero, `ChannelArt`, `ChannelCard`, `CardShelf`, kartu sambung TV |
| `:companion` | `Live.kt` | Tab Live TV, `BrowseSections`, `Channels` (loader + demo), `Nav` |
| `:companion` | `Profile.kt` | Gerak, `Dock`, Watchlist (`WatchRow`), Profil, halaman `Nav.page`, `NoCodePair` |
| `:companion` | `Remote.kt` | Remote: `TvCardTile`, `LabeledKey`, `PillRocker`, `PinPairDialog`, D-pad |
| `:launcher` (TV, Java Views) | `Ui.java`, `Sheets.java`, `Live.java`, `LivePreview.java`, `Companion*.java` | Fokus, sheet, pratinjau langsung, server HTTP pairing |
| `:desktop` (Compose Desktop) | `Live.kt`, `Main.kt` | Klik sekali = pratinjau, klik lagi/dua kali = tonton |
| `website/` | `index.html`, `watch.html`, `assets/*.js`, `style.css` | Landing dan web player `/watch` |
| `server/` | `src/app.mjs` | API publik (`/api/public/channels/preview` kini punya field `stream`) |

## 5. Sudah selesai (sudah di `main`)

- **#178** Token brand dan gerak di `:core` + `tokens.json` + CSS situs.
- **#179 HP:** dock 5 tab + Remote bulat, Watchlist, Profil + subhalaman, Notifikasi, Rekap, Offline, onboarding 3 langkah. **Batch terakhir: 5 tab utama dicocokkan ke Figma:**

  | Figma node | Layar | Status |
  | --- | --- | --- |
  | `10:2`, `24:258` | Home slide 1/2 | ✅ sama strukturnya |
  | `10:125` | Live TV | ✅ |
  | `19:133` | Remote | ✅ |
  | `19:285` | Watchlist | ✅ (tanpa chip Acara/Pengingat, lihat §7) |
  | `19:384` | Profil | ✅ (kartu "Versi & tentang" belum dicek) |
- **#180** Remote multi-TV tanpa kode: HP minta, TV menampilkan sheet persetujuan, satu kali OK. Juga ada pemilih TV di Remote.
- **#181 TV:** pratinjau langsung di Jadwal TV, cincin fokus, ukuran teks, sheet detail saluran, cari saluran.
- **#182 Desktop:** klik sekali = pratinjau, klik lagi/dua kali = tonton.
- **#183 Web:** landing baru (4 bahasa + RTL) dan web player `/watch` (hls.js 1.5.20 dari jsDelivr, native HLS di iPhone).

## 6. Pekerjaan berikutnya (urut prioritas)

Alur kerja per layar: ambil `get_design_context` untuk node Figma → sesuaikan ke komponen yang sudah ada (§4), jangan menyalin React/Tailwind → screenshot mode demo → bandingkan dengan Figma → commit per modul.

### 6.1 HP: 18 layar sisa (`:companion`, halaman Figma `7:48`)

| Node | Layar | Catatan |
| --- | --- | --- |
| `19:511` | Pengaturan | `SettingsPage` di `Profile.kt` (RegionCard + AppearanceCard) |
| `23:218` / `23:251` / `23:301` | Onboarding 1 Bahasa · 2 Wilayah · 3 Siap | `Welcome.kt` (alur QUICK) |
| `23:341` | Cari | Saat ini tombol cari di Beranda membuka Live TV; Figma punya halaman Cari sendiri |
| `23:400` / `11:66` | Player potret / lanskap | `Live.kt` `PlayerStage`, `NowPlayingInfo` |
| `11:121` | Detail saluran | `ChannelScreen.kt` |
| `23:459` | Riwayat tontonan | `HistoryPage` |
| `23:515` | Hubungkan ke TV | Sebaiknya jadi `Nav.page="connect"`, lalu kartu sambung-dengan-kode di bawah Beranda dipindah ke sini |
| `23:588` / `23:625` / `23:683` | Laporkan masalah · Pusat bantuan · Kontributor | |
| `30:714` / `30:761` / `30:805` | Notifikasi · Rekap bulanan · Offline | Sudah ada, tinggal dicocokkan |
| `30:823` | Beranda tema terang | Cek palet Terang di `Theme.kt` |

### 6.2 TV (`:launcher`, Java Views, halaman Figma `7:49`, 1920×1080)

`12:2`, `20:41`, `20:146`, `20:251` Home fokus 1–4 · `12:108` Jadwal & Live · `21:78` Player · `27:81` Siapa yang menonton? · `27:104` Onboarding · `27:132` Pusat Kontrol · `27:186` Detail saluran (sheet) · `28:83` Cari · `28:180` Aplikasi · `28:224` Pengaturan.

- Ukuran Figma dalam px 1080p. Launcher mengukur dalam **dp pada kanvas 960×540**, jadi bagi ukuran Figma dengan 2. Ukuran teks TV di `Brand.kt` sengaja tidak diubah.
- Pratinjau di hero Home TV: pakai `LivePreview.java` yang sudah ada (mati di perangkat LIGHT, saluran DRM/DASH, atau bila dimatikan).
- Tanpa blur real-time: pakai `GlassDrawable` / warna transparan.

### 6.3 Desktop (`:desktop`, halaman Figma `7:50`, 1440×900)

`13:2`, `22:20`, `22:199`, `22:378` Home dipilih 1–4 · `22:557` Player · `30:77` Live TV grid · `30:200` Multi-view 2×2 · `30:236` Rekaman · `30:301` Pengaturan. Screenshot dengan `./gradlew :desktop:run --args="--demo=id"`.

### 6.4 Web (halaman Figma `7:51`, frame `13:183`)

Landing sudah dibuat ulang, tapi belum dicocokkan per piksel. Perbedaan yang terlihat dari metadata Figma:
- Hero rata tengah dengan judul "Kamu yang pilih mau nonton apa."; kode sekarang dua kolom dengan mockup TV + HP.
- Carousel berisi 7 tile 176 px dengan tinggi bergelombang.
- "Saluran dari 200+ negara" dengan 4 kartu kategori 265×340.
- 3 kartu unduh (TV, HP, Desktop).
- Footer 3 kolom: Produk / Komunitas / Bantuan.

### 6.5 Lain-lain yang tertunda

1. **Deploy `/watch`:** server API (`server/`) perlu di-deploy ulang, lalu CSP di nginx produksi diperbarui sesuai `website/nginx.conf` (workflow hanya meng-upload file situs).
2. **Uji perangkat nyata #180:** HP + TV di Wi-Fi yang sama; sheet persetujuan harus muncul di TV, termasuk di APK TV Standalone.
3. **Ganti foto stok Unsplash di Figma** dengan artwork berlisensi sebelum rilis stabil.
4. Tes server (`npm test`) belum ada di CI. Pertimbangkan job Node di `.github/workflows/pr.yml`.
5. Komentar progres di issue #179–#183 hanya bila pemilik meminta.

## 7. Celah yang diketahui

- **Tidak ada artwork acara/saluran** di playlist. Figma memakai foto, sedangkan kode memakai `ChannelArt` (gradien warna logo + logo di kotak putih). Jangan memakai URL aset Figma di kode (kedaluwarsa 7 hari).
- **Watchlist:** chip "Acara" dan "Pengingat" di Figma butuh fitur simpan acara/pengingat yang belum ada. Perlu desain data (sinkron HP↔TV lewat `Viewer` atau pesan baru), lihat §9 di `REDESIGN-4.md`.
- **Tombol mic di Remote** memakai pengenal suara HP lalu `TvLink.ask(...)`, karena TV belum punya tombol suara.
- **Pengaturan TV** (ikon Tune) sekarang dibuka dengan mengetuk nama TV di panel Remote.
- **Chip "Semua"** di Beranda selalu terpilih; chip lain membuka Live TV dengan filter.

## 8. Definisi selesai per layar

- Struktur, jarak, ukuran huruf dan warna cocok dengan frame Figma (tema Gelap) dan tidak rusak di Terang/Kontras Tinggi.
- Teks baru ada di EN/ID/ZH + `ar.json`; tampilan Arab dicerminkan kecuali video/seekbar.
- Target sentuh HP ≥ 48 dp; teks TV ≥ 19 px di 1080p; tombol TV ≥ 74 px.
- Pemeriksaan lengkap §3 lulus; ada screenshot mode demo sebelum/sesudah.
- Satu commit per modul, `CHANGELOG.md` di bagian versi teratas diperbarui (Bahasa Indonesia, gaya yang sama).
