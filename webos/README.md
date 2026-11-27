# Tahoe TV untuk LG webOS

Aplikasi web (HTML/JS tanpa dependensi, ES5) yang mengutamakan TV langsung. Aturan saluran sama dengan `:core`
(parser M3U/OTT, penggabungan sumber, filter siaran gratis untuk sumber bawaan, pemisahan Film & Series).

## Isi
- **Beranda**: sorotan saluran terakhir, lanjutkan menonton, favorit, sedang tayang, film; jam, cuaca, waktu salat.
- **TV Langsung** per kategori, favorit, sumber cadangan otomatis; **Jadwal** (EPG) dengan pengingat; **Cari** saluran,
  acara, dan film.
- **Pemutar**: layar "siaran tidak tersedia" dengan nada Tahoe, daftar saluran, pilihan sumber, saluran sebelumnya,
  pengatur tidur.
- **Film & Series** (pixeldrain dan file di grup film/series): poster, episode, lanjutkan posisi.
- **Profil** Saya, Anak (PIN, batas waktu), Tamu; onboarding; pengaturan; Indonesia, Inggris, Mandarin.
- **Imersif**: saluran yang disorot diputar tanpa suara di latar, poster film jadi latar, halaman dan dialog beranimasi,
  jam dan cuaca di samping, OSD dengan logo, label LIVE, dan jam.
- **Riwayat** tontonan, **rasio layar** (Pas/Penuh/Zoom), pilihan audio dan subtitel bila siarannya punya, kartu cuaca
  (prakiraan 5 hari) dan waktu salat dengan pemberitahuan saat masuk waktunya.

Perbandingan dengan Android TV: [docs/id/WEBOS-PARITAS.md](../docs/id/WEBOS-PARITAS.md).

## Pembaruan otomatis (OTA)
Mulai versi yang berisi `boot.js`, aplikasi memperbarui dirinya sendiri tanpa komputer:
- Sekali sehari (dan dari Pengaturan → Cek pembaruan) aplikasi melihat rilis stabil terbaru di GitHub.
- Skrip dan gaya rilis itu diunduh dari repositori resmi pada tag rilisnya (`webos/app/manifest.json`), tiap berkas dicek
  SHA-256-nya, lalu disimpan. Versi baru berjalan saat aplikasi dibuka lagi, atau langsung lewat "Mulai ulang sekarang".
- Dengan *Pembaruan otomatis* mati, aplikasi bertanya dulu (Perbarui sekarang / Nanti; Nanti = ditanya lagi besok).
- Pengaman: `boot.js` tidak pernah diganti lewat OTA. Pembaruan yang gagal menyala dalam 20 detik, atau macet saat dibuka
  sebelumnya, dibuang dan aplikasi kembali ke versi paket. Pengaturan → *Kembali ke versi paket* melakukan hal yang sama.
- Gambar, huruf, `boot.js`, dan `appinfo.json` hanya berubah lewat paket baru. Bila sebuah rilis membutuhkannya,
  aplikasi memberi tahu untuk memasang `TahoeTV-webOS.ipk` dari komputer.
- Paket sebelum `boot.js` (3.8.8 ke bawah) perlu dipasang sekali lagi dari komputer; sesudah itu pembaruan berjalan sendiri.

## Tombol remote
| Tombol | Di daftar | Saat menonton |
|---|---|---|
| OK | pilih / tonton | info acara (film: jeda) |
| Kembali | ke menu | ke daftar |
| Kiri | ke menu | daftar saluran (film: mundur 10 detik) |
| Kanan | pindah kolom | pilihan: sumber, favorit, tidur, jadwal (film: maju) |
| Atas/Bawah, CH | pindah / pindah halaman | ganti saluran atau episode |
| 0 / Kuning | | saluran sebelumnya |
| 1-9 | | pindah ke nomor saluran |
| Merah | favorit | favorit |

## Pasang ke TV
Mulai rilis berikutnya, `TahoeTV-webOS.ipk` ikut di halaman rilis GitHub. Memasangnya tetap dari komputer dengan Developer
Mode (`ares-install --device <nama> TahoeTV-webOS.ipk`), atau langsung dari kode sumber seperti di bawah.

1. Di TV, pasang aplikasi **Developer Mode** (LG Content Store), masuk dengan akun developer LG,
   nyalakan *Dev Mode Status* dan *Key Server*.
2. Di komputer yang satu jaringan:

```bash
webos/install-tv.sh <alamat IP TV>
```

Sesi Developer Mode punya masa berlaku; perpanjang di aplikasi Developer Mode, kalau habis aplikasinya terhapus.

Versi pertama sudah dicoba di LG 43LM5700 (webOS 4.10, Chromium 53): saluran, siaran langsung 720p, poster. Pada mesin
lama ini dipakai huruf bawaan TV (font variabel tidak didukung), dan logo punya cadangan karena sebagian host gambar
gagal. Beranda, jadwal, cari, pemutar, film, profil, dan pengaturan juga sudah dicek di TV yang sama; rinciannya di
[docs/id/WEBOS-PARITAS.md](../docs/id/WEBOS-PARITAS.md).

Kode ada di `app/js/` dan harus tetap ES5 (ada tesnya).

## Pengembangan
- Tes: `node --test webos/test/*.test.js`
- Pratinjau di browser: sajikan folder `webos/app` (konfigurasi `webos` di `.claude/launch.json`). Browser menerapkan
  CORS, jadi sebagian playlist hanya termuat di TV.
- Paket `.ipk`: `webos/package.sh` (versi mengikuti `gradle.properties`; juga menulis `app/manifest.json` dan baris versi di
  `app/boot.js`). CI membuatnya di setiap push dan menyertakannya di rilis. Jalankan `node webos/tools/manifest.js` setelah
  mengubah berkas aplikasi atau versi, kalau tidak tesnya gagal.
- Berkas skrip baru harus didaftarkan di `webos/tools/manifest.js`.

## Belum ada
Siaran DRM, header Referer/User-Agent khusus (aplikasi web tidak bisa mengirimnya; sumber seperti itu dicoba terakhir),
rekaman, remote dari HP, akun/Handoff, notifikasi server, dan pembaruan otomatis.
