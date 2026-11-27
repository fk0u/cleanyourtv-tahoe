# Tahoe TV untuk LG webOS

Aplikasi web (HTML/JS tanpa dependensi, ES5) yang mengutamakan TV langsung. Aturan saluran sama dengan `:core`
(parser M3U/OTT, penggabungan sumber, filter siaran gratis untuk sumber bawaan, pemisahan Film & Series).

## Isi
- Saluran per kategori, logo, favorit (tombol merah), cari, saluran terakhir diputar lagi saat dibuka.
- Pemutar HLS bawaan TV; sumber cadangan dicoba otomatis; CH +/-, Atas/Bawah, dan tombol angka.
- Film & Series (pixeldrain dan file di grup film/series) dengan poster dari judul.
- Playlist sendiri di Pengaturan. Bahasa Indonesia atau Inggris mengikuti TV.

## Pasang ke TV
1. Di TV, pasang aplikasi **Developer Mode** (LG Content Store), masuk dengan akun developer LG,
   nyalakan *Dev Mode Status* dan *Key Server*.
2. Di komputer yang satu jaringan:

```bash
webos/install-tv.sh <alamat IP TV>
```

Sesi Developer Mode punya masa berlaku; perpanjang di aplikasi Developer Mode, kalau habis aplikasinya terhapus.

## Pengembangan
- Tes: `node --test webos/test/*.test.js`
- Pratinjau di browser: sajikan folder `webos/app` (konfigurasi `webos` di `.claude/launch.json`). Browser menerapkan
  CORS, jadi sebagian playlist hanya termuat di TV.
- Paket `.ipk`: `webos/package.sh` (versi mengikuti `gradle.properties`).

## Belum ada
Jadwal acara (EPG), siaran DRM, header Referer/User-Agent khusus (aplikasi web tidak bisa mengirimnya; sumber seperti itu
dicoba terakhir), sinkron akun/ponsel, dan pembaruan otomatis.
