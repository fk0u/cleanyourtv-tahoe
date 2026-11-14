# Roadmap

[← README](../../README.id.md) · [English](../ROADMAP.md) · Status terkini selalu ada di [milestone](https://github.com/fk0u/cleanyourtv-tahoe/milestones) dan [semua issue terbuka](https://github.com/fk0u/cleanyourtv-tahoe/issues).

**Ritme rilis:** Tahoe menargetkan rilis fitur **bulanan** dari `main` (mengikuti satu sumber versi di `gradle.properties`), dengan rilis tambalan cepat (`x.y.z`) untuk perbaikan keamanan, keandalan, atau siaran.

## Sudah dirilis

| Versi | Isi utama |
|---|---|
| **3.0.1** | Profil Anak + PIN, rekam acara di desktop, deteksi siaran rekaman/basi, loader ala Apple TV, tunda siaran, QR pairing, dekoder FFmpeg, Kirim log, reset PIN dari HP |
| **3.0.2** | Desktop: semua saluran dicek, playlist gzip, perbaikan keamanan |
| **3.1.0** | Live TV Windows lewat web player, pilihan kualitas (Otomatis/1080p/720p/480p) dan latensi di semua perangkat, desain baru app HP |
| **3.2.0** | Onboarding lengkap di HP, desktop, dan TV, saluran semua negara, tampilan siaran tidak tersedia tiga bahasa, build debug berdampingan, README global |

Detail per rilis: [CHANGELOG.md](../../CHANGELOG.md).

## Sekarang (`3.3`–`3.4`: Internasional, Keandalan & Koneksi Sederhana)

- **Internasional & Keandalan (`3.3` di `main`):** saluran dan jadwal multi-negara ([#79](https://github.com/fk0u/cleanyourtv-tahoe/issues/79), [#80](https://github.com/fk0u/cleanyourtv-tahoe/issues/80)), UI EN/ID/中文 ([#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)), mode demo netral ([#99](https://github.com/fk0u/cleanyourtv-tahoe/issues/99)), aset web player bundel lokal ([#81](https://github.com/fk0u/cleanyourtv-tahoe/issues/81)), ID saluran stabil ([#94](https://github.com/fk0u/cleanyourtv-tahoe/issues/94)), kebijakan sumber & penghapusan 7 hari ([#97](https://github.com/fk0u/cleanyourtv-tahoe/issues/97), [#101](https://github.com/fk0u/cleanyourtv-tahoe/issues/101)), serta perbaikan pemutar desktop ([#75](https://github.com/fk0u/cleanyourtv-tahoe/issues/75), [#105](https://github.com/fk0u/cleanyourtv-tahoe/issues/105)).
- **Simple & Connect — Batch 1 (di `main`):** penyimpanan cache status saluran atomik ([#106](https://github.com/fk0u/cleanyourtv-tahoe/issues/106)), sinkronisasi HP↔TV aman & antre offline ([#96](https://github.com/fk0u/cleanyourtv-tahoe/issues/96), [#107](https://github.com/fk0u/cleanyourtv-tahoe/issues/107), [#111](https://github.com/fk0u/cleanyourtv-tahoe/issues/111)), performa panduan TV & cache gambar ([#108](https://github.com/fk0u/cleanyourtv-tahoe/issues/108), [#109](https://github.com/fk0u/cleanyourtv-tahoe/issues/109)), serta bahasa awam di UI ([#65](https://github.com/fk0u/cleanyourtv-tahoe/issues/65)).
- **Kesehatan Engineering (di `main`):** satu sumber versi di `gradle.properties`, gerbang tes sebelum rilis, lint Android di PR, Dependabot Gradle ([#57](https://github.com/fk0u/cleanyourtv-tahoe/issues/57), [#100](https://github.com/fk0u/cleanyourtv-tahoe/issues/100)).
- **Tersisa di `3.4`:**
  - [#90](https://github.com/fk0u/cleanyourtv-tahoe/issues/90) Kontrol ala TV tradisional + Mode Sederhana (angka saluran, CH±, saluran terakhir, teks besar)
  - [#64](https://github.com/fk0u/cleanyourtv-tahoe/issues/64) Aksesibilitas (TalkBack, indikator fokus jelas, target 48 dp, skala huruf)
  - [#93](https://github.com/fk0u/cleanyourtv-tahoe/issues/93) Protokol HP↔TV v2 (pesan bertipe, versi, konfirmasi terima, keep-alive)
  - [#88](https://github.com/fk0u/cleanyourtv-tahoe/issues/88) **Tahoe Connect** — oper & kendalikan pemutaran TV/desktop dari HP
  - Pengujian perangkat fisik TV untuk onboarding ([#85](https://github.com/fk0u/cleanyourtv-tahoe/issues/85)) dan tampilan siaran tidak tersedia ([#87](https://github.com/fk0u/cleanyourtv-tahoe/issues/87))

## Berikutnya (`3.4`–`3.5`: Pusat Kendali HP & Pengalaman Desktop)

- [#34](https://github.com/fk0u/cleanyourtv-tahoe/issues/34) Kontrol pemutar auto-hide & [#35](https://github.com/fk0u/cleanyourtv-tahoe/issues/35) Pusat pengaturan TV lengkap dari HP
- [#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36) Setup awal TV tanpa HP + scanner QR di app & [#37](https://github.com/fk0u/cleanyourtv-tahoe/issues/37) dukung banyak TV / banyak pengendali
- [#49](https://github.com/fk0u/cleanyourtv-tahoe/issues/49) Desktop imersif (pemutar menyatu, mini-player, pintasan keyboard, EPG timeline)
- [#51](https://github.com/fk0u/cleanyourtv-tahoe/issues/51) Pilih trek audio/subtitle (ASS/SSA) · [#52](https://github.com/fk0u/cleanyourtv-tahoe/issues/52) notifikasi TV→HP · [#53](https://github.com/fk0u/cleanyourtv-tahoe/issues/53) rekam terjadwal
- [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) Tahoe sebagai app Android TV mandiri (tanpa ADB/root) · [#55](https://github.com/fk0u/cleanyourtv-tahoe/issues/55) Tanda tangan & notarisasi macOS/Windows · [#95](https://github.com/fk0u/cleanyourtv-tahoe/issues/95) Pecah `Home.java` / `IptvServer.kt`

## Nanti (Diparkir)

- [#50](https://github.com/fk0u/cleanyourtv-tahoe/issues/50) Multi-view · [#38](https://github.com/fk0u/cleanyourtv-tahoe/issues/38) Optimisasi adaptif spesifikasi TV · [#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39) Wallpaper dari API foto gratis
- [#47](https://github.com/fk0u/cleanyourtv-tahoe/issues/47) Verifikasi orang dewasa lewat hitungan · [#54](https://github.com/fk0u/cleanyourtv-tahoe/issues/54) Batas waktu & laporan Anak · [#48](https://github.com/fk0u/cleanyourtv-tahoe/issues/48) Adzan tingkat kelurahan & nyalakan layar
- [#40](https://github.com/fk0u/cleanyourtv-tahoe/issues/40) Google TV & webOS (Tizen nanti) · [#61](https://github.com/fk0u/cleanyourtv-tahoe/issues/61) · [#62](https://github.com/fk0u/cleanyourtv-tahoe/issues/62)
- Keandalan sumber hulu: [#32](https://github.com/fk0u/cleanyourtv-tahoe/issues/32) · [#33](https://github.com/fk0u/cleanyourtv-tahoe/issues/33)

## Yang tidak kami lakukan

- **Tidak menyediakan restream TV berbayar ilegal atau agregator bajakan.** Hanya siaran gratis resmi/terbuka atau daftar saluran lokal milik pengguna sendiri (lihat [SOURCES.md](../SOURCES.md)).
- **Tidak membobol DRM.** Saluran ber-DRM disaring secara otomatis, bukan dilewati paksa.
- **Tidak ada iklan, pelacakan telemetri, atau akun wajib** untuk menonton TV gratis di rumah.

## Arsip rencana

- [ROADMAP-3.0.md](../ROADMAP-3.0.md): rencana 2.9.3 → 3.0.0 (sebagian besar sudah dirilis).
- [../PLAN.md](../../PLAN.md): rencana awal v2 (gaya tvOS 26).
