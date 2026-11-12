# Berkontribusi ke Tahoe TV

[← README](../../README.id.md) · [English](../../CONTRIBUTING.md)

Terima kasih sudah mau membantu! Tahoe TV adalah proyek open source (MIT) milik komunitas dan **siapa pun boleh ikut**. Kontribusi apa pun diterima: laporan bug, saluran yang bermasalah, terjemahan, desain, dokumentasi, atau kode.

## Melapor bug atau saluran bermasalah
1. Cek dulu [issue yang sudah ada](https://github.com/fk0u/cleanyourtv-tahoe/issues).
2. Ambil log dari app: **Kirim log** (HP: Remote · TV: Pusat Kontrol · Desktop: Setelan → Bantuan).
3. Buka [issue baru](https://github.com/fk0u/cleanyourtv-tahoe/issues/new/choose) dan pilih templatnya.

## Menyiapkan proyek
- JDK 17, Android SDK (platform 36, build-tools 34+). Gradle diunduh otomatis.
- `cp secrets.example.properties secrets.properties` (opsional; API key **tidak pernah** di-commit).
- Tes: `./gradlew :core:test :desktop:test`
- Jalankan desktop: `./gradlew :desktop:run`
- APK: `./gradlew :launcher:assembleDebug :companion:assembleDebug`

Struktur dan alur kode: [docs/ARCHITECTURE.md](ARCHITECTURE.md).

## Panduan kode
- **Ringan untuk TV 1 GB RAM:** launcher memakai Android Views (bukan Compose), tanpa blur real-time, tanpa polling berat. Ukur sebelum menambah dependensi.
- **`minSdk 26` (Android 8), diuji di Android 9.** Hindari API yang lebih baru tanpa pengecekan versi (misalnya `InputStream.readNBytes` baru ada di Android 13).
- Logika yang dipakai lebih dari satu app taruh di `:core` (JVM murni, tanpa Android API), lengkap dengan tes.
- Teks UI singkat dan jelas. Teks sedang dipindah ke resource agar bisa diterjemahkan ([#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)); layar baru pakai resource.
- Privasi: jangan mencatat token, kunci, atau data pribadi; URL di log melewati `DiagLog.redact`.

## Terjemahan
- **Dokumentasi:** salin file ke `docs/<kode-bahasa>/` (misalnya `docs/es/INSTALL.md`), terjemahkan, lalu tautkan dari tabel dokumentasi di README.
- **README:** tambahkan `README.<kode-bahasa>.md` dan tautannya di pemilih bahasa di atas setiap README.
- **Situs web:** tambahkan bahasamu di tabel `I18N` pada `website/assets/i18n.js` (salin blok `en`).
- **App:** ikuti [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56) dan [#71](https://github.com/fk0u/cleanyourtv-tahoe/issues/71).

## Mengirim PR
1. Fork, buat branch dari `main`.
2. Satu PR untuk satu perubahan; jelaskan *kenapa*, bukan hanya *apa*.
3. Tambahkan catatan di `CHANGELOG.md` untuk perubahan yang terasa oleh pengguna.
4. Pastikan tes lolos. PR akan direview (termasuk review otomatis).

Issue yang cocok untuk mulai: [`good first issue`](https://github.com/fk0u/cleanyourtv-tahoe/labels/good%20first%20issue) · [`help wanted`](https://github.com/fk0u/cleanyourtv-tahoe/labels/help%20wanted).

## Saluran & sumber
Tahoe hanya memakai siaran **gratis** dari sumber publik dan tidak meng-host siaran apa pun. Jangan usulkan sumber berbayar, hasil curian, atau yang melanggar hak siar.

## Lisensi
Dengan berkontribusi, kamu setuju kontribusimu berlisensi [MIT](../../LICENSE). Semua peserta mengikuti [Kode Etik](../../CODE_OF_CONDUCT.md#bahasa-indonesia).
