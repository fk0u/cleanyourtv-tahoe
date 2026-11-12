# Roadmap

[← README](../README.md) · Status terkini selalu ada di [issue berlabel roadmap](https://github.com/fk0u/cleanyourtv-tahoe/issues?q=is%3Aopen+label%3Aroadmap) dan [semua issue terbuka](https://github.com/fk0u/cleanyourtv-tahoe/issues).

## Sudah dirilis

| Versi | Isi utama |
|---|---|
| **3.0.1** (stabil) | Profil Anak + PIN, rekam acara di desktop, deteksi siaran rekaman/basi, loader ala Apple TV, tunda siaran, QR pairing, dekoder FFmpeg, Kirim log, reset PIN dari HP |
| **3.0.2** (di `main`, belum dirilis) | Desktop: semua saluran dicek (sebelumnya ±¾ tidak pernah dicek), playlist gzip dibuka dengan benar |

Detail per rilis: [CHANGELOG.md](../CHANGELOG.md).

## Berikutnya

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

### Platform baru
- [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) Tahoe sebagai app mandiri (TV box tanpa root/ADB)
- [#40](https://github.com/fk0u/cleanyourtv-tahoe/issues/40) Google TV dan webOS (Tizen nanti)

## Arsip rencana

- [ROADMAP-3.0.md](ROADMAP-3.0.md): rencana 2.9.3 → 3.0.0 (sebagian besar sudah dirilis).
- [../PLAN.md](../PLAN.md): rencana awal v2 (gaya tvOS 26).
