# Redesign Tahoe TV 4.0: rencana desain

> Status: **desain selesai di Figma, belum diimplementasikan.**
> Epic: #184 · Figma: [tahoeApp](https://www.figma.com/design/W3FBycV5KKVCD5RWHDk3kV/tahoeApp) · Lanjutan dari #41 (UI/UX lanjutan) dan #71 (internasional & RTL).

## 1. Tujuan

1. Satu sistem desain untuk **HP, Android TV, Desktop, dan Web**. Navigasi dan komponen sama, ukurannya menyesuaikan perangkat.
2. **Nyaman untuk lansia** (teks besar, kontras tinggi, satu tindakan utama per layar) **dan segar untuk Gen Z** (foto penuh layar, kartu besar, pratinjau langsung, transisi singkat).
3. **Siaran langsung terasa hidup**: saluran yang difokus (TV) atau dipilih (desktop) langsung diputar di hero.
4. **Satu HP bisa mengontrol banyak TV** di Wi-Fi yang sama tanpa kode. QR/PIN hanya dipakai kalau TV ada di jaringan lain.

## 2. Struktur file Figma

| Halaman | Isi |
| --- | --- |
| 🗺 Sitemap & Flows | Semua halaman per platform + prinsip gerak/aksesibilitas |
| 🎨 Foundations | Token warna (Gelap / Terang / Kontras Tinggi), tipografi, image library + kredit |
| 🧩 Components | Button, Live Badge, Chip, Icon Button, Progress Bar, Channel Card, Poster Card, Nav Item, **Dock**, **Settings Row**, **Mini Player** |
| 📱 Mobile | 23 layar + prototipe |
| 📺 Android TV | 14 layar (termasuk 4 keadaan fokus Beranda) + prototipe remote |
| 💻 Desktop | 9 layar (termasuk 4 keadaan pilih Beranda) + prototipe |
| 🌐 Web | Landing page |

## 3. Fondasi

### 3.1 Warna (variabel `Tahoe / Color`)

| Token | Gelap | Terang | Kontras tinggi | Catatan |
| --- | --- | --- | --- | --- |
| `bg/canvas` | `#0D0F14` | `#F6F7F9` | `#000000` | Latar aplikasi |
| `bg/night` | `#07080D` | `#07080D` | `#000000` | Panggung video (selalu gelap) |
| `bg/surface` | `#191C23` | `#FFFFFF` | `#000000` | Kartu |
| `bg/surface-hi` | `#242833` | `#ECEFF4` | `#1A1A1A` | Kartu terangkat |
| `text/primary` | `#F7F8FA` | `#0B0C10` | `#FFFFFF` | |
| `text/secondary` | 80% putih | 80% hitam | `#FFFFFF` | Naik dari 70%: lebih terbaca untuk lansia |
| `text/tertiary` | 62% putih | 62% hitam | 95% putih | Naik dari 45% |
| `border/stroke` | 20% putih | 16% hitam | `#FFFFFF` | |
| `accent/primary` | `#5CD0FF` | `#0062A3` | `#FFD60A` | Cyan "danau", warna utama brand |
| `accent/warm` | `#FF8A5C` | `#C2410C` | `#FFB000` | **Baru**, aksen hangat untuk sorotan/notifikasi/rekap |
| `focus/ring` | `#FFFFFF` | `#0B0C10` | `#FFD60A` | **Baru**, cincin fokus TV 6 px + cahaya cyan |
| `status/live` | `#FF453A` | `#D92D20` | `#FF453A` | |
| `status/success` | `#30D158` | `#1E9E44` | `#30D158` | |
| `status/favorite` | `#FFD60A` | `#B38600` | `#FFD60A` | |

Aturan: teks di atas foto selalu diberi scrim gelap (gradien) dan exposure foto diturunkan ±0,4–0,55. Panggung video tetap `bg/night` di semua tema.

### 3.2 Tipografi (Outfit)

| Gaya | HP/Desktop | Gaya TV (1080p) |
| --- | --- | --- |
| Display | 56 / 40, tracking −2% | TV/Display 96 |
| Title | 32, −2% | TV/Title 46 |
| Headline | 20, −1% | TV/Headline 30 |
| Body | 16 | TV/Body 24 |
| Label | 14, +1% | TV/Caption 19 |
| Caption | 12, +1% | TV/Eyebrow 16 (huruf besar, +10%) |

Minimum: teks TV **19 px** di 1080p, tombol TV **tinggi 74 px**, target sentuh HP **48 dp**. Angka yang berubah memakai `tabular-nums`.

### 3.3 Gerak (mengikuti [emilkowalski/skills](https://github.com/emilkowalski/skills))

| Konteks | Kurva | Durasi |
| --- | --- | --- |
| Masuk/keluar elemen | ease-out `cubic-bezier(0.23, 1, 0.32, 1)` | 200–300 ms |
| Pindah halaman / sheet | `cubic-bezier(0.32, 0.72, 0, 1)` | 340 ms masuk, 280 ms keluar |
| Bergerak di layar (hero otomatis) | ease-in-out `cubic-bezier(0.77, 0, 0.175, 1)` | 700 ms, setiap 4 dtk |
| Fokus TV / pindah pilihan | ease-out | 200–220 ms, tanpa pantulan |
| Tekan tombol | `scale(0.97)` | 120 ms |
| Kurangi gerakan | hanya fade | 200 ms |

Aksi yang sering (navigasi fokus, ganti tab) dibuat cepat atau tanpa animasi. Tidak ada `ease-in`, dan tidak ada animasi yang dimulai dari `scale(0)`.

## 4. Navigasi & arsitektur informasi

**Dock HP** (mengambang): Beranda · Live TV · **Remote** (tombol bulat cyan di tengah, menonjol) · Watchlist · Profil.

**Profil** berisi: kartu profil, statistik tonton → Rekap, Riwayat tontonan, Hubungkan ke TV, Laporkan masalah, Pusat bantuan, Beri bintang di GitHub, Kontributor, Pengaturan, versi/Tentang.

**TV**: nav pill atas (Beranda, Live TV, Jadwal, Aplikasi, Rekaman) + Pusat Kontrol (tombol Menu).
**Desktop**: rail kiri (Beranda, Live TV, Rekaman, Remote, Cast, Pengaturan) + panel Ringkasan kanan.

## 5. Inventaris halaman

### 📱 HP & tablet
- Masuk pertama: Onboarding 1 Bahasa · 2 Wilayah · 3 Siap
- Beranda (hero bergeser otomatis, 2 slide) · Beranda tema terang · Cari · Notifikasi · Rekap bulanan
- Live TV (kategori) · Detail saluran · Player potret · Player lanskap
- Remote multi-TV · Hubungkan ke TV
- Watchlist & pengingat
- Profil · Riwayat · Laporkan masalah · Pusat bantuan · Kontributor · Pengaturan
- Keadaan: Offline

### 📺 Android TV
- Siapa yang menonton? · Onboarding TV
- Beranda · fokus 1–4 (pratinjau langsung) · Pusat Kontrol
- Jadwal & Live · Detail saluran (sheet) · Player layar penuh
- Cari (keyboard layar) · Aplikasi · Pengaturan (ukuran teks Normal/Besar/Sangat besar, Mode sederhana)

### 💻 Desktop
- Beranda · dipilih 1–4 · Player
- Live TV (grid) · Multi-view 2×2
- Rekaman · Pengaturan

### 🌐 Web
- Landing (hero, carousel, saluran per kategori, unduh per perangkat, footer)

## 6. Interaksi kunci

| Perangkat | Interaksi | Hasil |
| --- | --- | --- |
| TV | ← → pada rak "Lanjutkan menonton" | Hero langsung memutar saluran yang difokus (suara pelan, label "Pratinjau langsung · <saluran>") |
| TV | OK | Player layar penuh; Esc/Back kembali |
| TV | M / S / I / G / A | Pusat Kontrol / Cari / Detail / Jadwal / Aplikasi |
| Desktop | **Klik sekali** baris saluran | Baris dipilih, hero + mini-player + Ringkasan berganti (pratinjau) |
| Desktop | **Klik lagi / klik dua kali** baris terpilih | Buka siaran di Player |
| HP | Hero | Bergeser otomatis tiap 4 dtk |
| HP | Ketuk kartu / mini-player | Player potret; ikon layar penuh → lanskap |
| HP | Player | Usap kiri/kanan ganti saluran; cubit = Pas ↔ Zoom |

### Remote multi-TV (HP)
- Aplikasi HP mencari TV ber-Tahoe di Wi-Fi yang sama (mDNS/NSD) dan menampilkannya sebagai kartu: **Terhubung**, **Ketuk untuk kontrol**, **Belum ada Tahoe → Pasang**.
- Satu HP menyimpan banyak TV dan bisa berpindah dengan satu ketukan, **tanpa kode** di jaringan yang sama.
- Kalau TV ada di jaringan lain: **Scan QR** atau **PIN 6 digit** dari TV (Pusat Kontrol → HP).
- Remote: power, mic, D-pad + OK, Kembali/Beranda/Menu/Ketik, VOL/CH, mute.

## 7. Aksesibilitas

- Tiga mode warna (Gelap, Terang, Kontras Tinggi) + Kurangi gerakan + ukuran teks (TV).
- Cincin fokus selalu terlihat di TV (putih 6 px + cahaya).
- Semua tombol ikon punya label; target ≥ 48 dp (HP) / ≥ 74 px (TV).
- RTL (Arab): tata letak dicerminkan kecuali video, seekbar, dan transport.
- Copy pakai bahasa awam dan satu tindakan utama per layar.

## 8. Rencana implementasi

| Fase | Modul | Pekerjaan | Issue |
| --- | --- | --- | --- |
| 1 | `:core` | Sinkronkan `Brand.kt` + `tokens.json` dengan palet baru (`accent/warm`, `focus/ring`, teks 80%/62%), tipe TV baru, konstanta motion (kurva & durasi) | #178 |
| 2 | `:companion` | Dock 5 tab + Remote bulat, Watchlist, Profil + subhalaman, Pengaturan baru, hero otomatis, Notifikasi, Rekap, Offline, onboarding baru | #179 |
| 2 | `:companion` + `:launcher` + `:core` | Remote multi-TV: penemuan otomatis di Wi-Fi, daftar TV tersimpan, pindah TV, cadangan QR/PIN, pasang ke TV | #180 |
| 3 | `:launcher` | Pratinjau langsung saat fokus di Beranda, teks/tombol TV besar, Pusat Kontrol, Cari, Aplikasi, Pengaturan (ukuran teks, Mode sederhana), profil | #181 |
| 3 | `:desktop` | Klik sekali = pratinjau, klik lagi/dua kali = tonton; grid, multi-view, rekaman, pengaturan | #182 |
| 4 | `website/` | Landing baru sesuai Figma | #183 |

### Kriteria selesai (per fase)
- Layar cocok dengan frame Figma (tema Gelap) dan tidak rusak di tema Terang/Kontras Tinggi.
- Teks TV ≥ 19 px di 1080p; tombol ≥ 74 px; target HP ≥ 48 dp.
- Transisi memakai kurva & durasi tabel 3.3; "Kurangi gerakan" mengubahnya jadi fade.
- Uji di perangkat nyata: TV stick RAM 1 GB (tanpa blur real-time di `:launcher`), HP Android 8+, desktop Mac/Windows.
- `./gradlew :core:test :desktop:test :launcher:lintDebug :companion:lintDebug` lulus.

## 9. Pertanyaan terbuka
- Pratinjau di hero TV: putar stream asli (lebih berat) atau thumbnail EPG + animasi? Perlu uji di TV RAM 1 GB.
- Watchlist & pengingat: sinkron HP↔TV lewat protokol yang sudah ada (`Viewer`) atau tipe pesan baru?
- Artwork: ganti foto stok Unsplash dengan artwork saluran berlisensi atau gambar AI sebelum rilis.

## 10. Kredit gambar
Foto contoh dari Unsplash (Unsplash License): news `JxzV1k-sfCU`, stadium `DOicNPBVSHs`, family `HHsq_3FF2W4`, lake `vZ1JAXUO3-0`, city `Uz-LsnjxJ6U`, wok `rWNcrwqF0nI`, concert `NYrVisodQ2M`, jungle `MA9sLbbIE3g`. Hanya untuk desain.
