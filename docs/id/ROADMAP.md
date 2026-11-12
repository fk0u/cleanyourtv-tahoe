# Roadmap

[← README](../../README.id.md) · [English](../ROADMAP.md) · Status terkini selalu ada di [issue berlabel roadmap](https://github.com/fk0u/cleanyourtv-tahoe/issues?q=is%3Aopen+label%3Aroadmap) dan [semua issue terbuka](https://github.com/fk0u/cleanyourtv-tahoe/issues).

## Sudah dirilis

| Versi | Isi utama |
|---|---|
| **3.0.1** | Profil Anak + PIN, rekam acara di desktop, deteksi siaran rekaman/basi, loader ala Apple TV, tunda siaran, QR pairing, dekoder FFmpeg, Kirim log, reset PIN dari HP |
| **3.0.2** | Desktop: semua saluran dicek, playlist gzip, perbaikan keamanan |
| **3.1.0** (stabil) | Live TV Windows lewat web player, pilihan kualitas (Otomatis/1080p/720p/480p) dan latensi di semua perangkat, desain baru app HP |

Detail per rilis: [CHANGELOG.md](../../CHANGELOG.md).

## Berikutnya

### 🌍 Internasional ([#71](https://github.com/fk0u/cleanyourtv-tahoe/issues/71))
- Saluran dan jadwal acara untuk **semua negara** (paket negara iptv-org); Indonesia tetap dengan sumber pilihannya.
- **Setup awal di setiap perangkat:** pilih bahasa dulu, lalu negara/wilayah (disarankan dari sistem, selalu bisa diubah).
- App diterjemahkan (Inggris dulu, [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)), lalu terjemahan komunitas; siap RTL.
- Fitur khusus wilayah (jadwal shalat/adzan, "dekat kamu") jadi opsi per wilayah.

### Perbaikan siaran
- [#32](https://github.com/fk0u/cleanyourtv-tahoe/issues/32) Trans TV (dan kadang Trans7) jeda ±2 detik berulang
- [#33](https://github.com/fk0u/cleanyourtv-tahoe/issues/33) SCTV tidak bisa diakses, Indosiar menayangkan Moji TV

### HP sebagai pusat kendali
- [#34](https://github.com/fk0u/cleanyourtv-tahoe/issues/34) Kontrol pemutar auto-hide
- [#35](https://github.com/fk0u/cleanyourtv-tahoe/issues/35) Pusat pengaturan TV lengkap (dock, wallpaper, profil, akun, PIN)
- [#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36) Setup awal TV tanpa HP (web lokal / scan QR) + scanner QR di app
- [#37](https://github.com/fk0u/cleanyourtv-tahoe/issues/37) Satu HP kelola banyak TV, banyak perangkat ke satu TV

### Performa & tampilan
- [#38](https://github.com/fk0u/cleanyourtv-tahoe/issues/38) Deteksi spesifikasi TV saat init, optimisasi dan caching adaptif
- [#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39) Wallpaper bawaan dari API foto gratis
- [#41](https://github.com/fk0u/cleanyourtv-tahoe/issues/41) UI/UX lanjutan untuk HP, TV, dan desktop

### Bug dari audit (12 Nov 2026)
- [#43](https://github.com/fk0u/cleanyourtv-tahoe/issues/43) Adzan Subuh tidak bunyi saat TV standby; rantai alarm bisa putus
- [#44](https://github.com/fk0u/cleanyourtv-tahoe/issues/44) CHANGELOG & temuan review dokumentasi
- [#45](https://github.com/fk0u/cleanyourtv-tahoe/issues/45) Unduhan pembaruan yang terputus dianggap lengkap
- [#46](https://github.com/fk0u/cleanyourtv-tahoe/issues/46) Pengerasan profil Anak dan privasi data
- Tiga temuan keamanan dilaporkan secara privat (lihat [SECURITY.md](../../SECURITY.md)).

### Profil Anak & adzan
- [#47](https://github.com/fk0u/cleanyourtv-tahoe/issues/47) Verifikasi orang dewasa lewat soal matematika
- [#54](https://github.com/fk0u/cleanyourtv-tahoe/issues/54) Profil Anak lanjutan: batas waktu, jam tayang, laporan
- [#48](https://github.com/fk0u/cleanyourtv-tahoe/issues/48) Adzan lanjutan: lokasi kelurahan, onboarding, nyalakan layar

### Menonton
- [#49](https://github.com/fk0u/cleanyourtv-tahoe/issues/49) Desktop imersif: pemutar menyatu, mini-player, pintasan, EPG timeline
- [#50](https://github.com/fk0u/cleanyourtv-tahoe/issues/50) Multi-view
- [#51](https://github.com/fk0u/cleanyourtv-tahoe/issues/51) Pilih trek audio/subtitle, subtitle ASS/SSA
- [#52](https://github.com/fk0u/cleanyourtv-tahoe/issues/52) Notifikasi dari TV ke HP
- [#53](https://github.com/fk0u/cleanyourtv-tahoe/issues/53) Rekam di TV/HP, rekaman terjadwal tanpa app desktop terbuka

### Kualitas & distribusi
- [#55](https://github.com/fk0u/cleanyourtv-tahoe/issues/55) Tanda tangan & notarisasi app macOS dan Windows
- [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56) Bahasa Inggris di dalam app
- [#57](https://github.com/fk0u/cleanyourtv-tahoe/issues/57) Tes otomatis untuk launcher TV dan app HP, CI untuk PR

### Platform baru
- [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) Tahoe sebagai app mandiri (TV box tanpa root/ADB)
- [#40](https://github.com/fk0u/cleanyourtv-tahoe/issues/40) Google TV dan webOS (Tizen nanti)

## Arsip rencana

- [ROADMAP-3.0.md](../ROADMAP-3.0.md): rencana 2.9.3 → 3.0.0 (sebagian besar sudah dirilis).
- [../PLAN.md](../../PLAN.md): rencana awal v2 (gaya tvOS 26).
