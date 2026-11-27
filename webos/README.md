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

Perbandingan dengan Android TV: [docs/id/WEBOS-PARITAS.md](../docs/id/WEBOS-PARITAS.md).

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
1. Di TV, pasang aplikasi **Developer Mode** (LG Content Store), masuk dengan akun developer LG,
   nyalakan *Dev Mode Status* dan *Key Server*.
2. Di komputer yang satu jaringan:

```bash
webos/install-tv.sh <alamat IP TV>
```

Sesi Developer Mode punya masa berlaku; perpanjang di aplikasi Developer Mode, kalau habis aplikasinya terhapus.

Versi pertama sudah dicoba di LG 43LM5700 (webOS 4.10, Chromium 53): saluran, siaran langsung 720p, poster. Pada mesin
lama ini dipakai huruf bawaan TV (font variabel tidak didukung), dan logo punya cadangan karena sebagian host gambar
gagal. Beranda, jadwal, profil, dan fitur baru lainnya baru dicoba di browser.

Kode ada di `app/js/` dan harus tetap ES5 (ada tesnya).

## Pengembangan
- Tes: `node --test webos/test/*.test.js`
- Pratinjau di browser: sajikan folder `webos/app` (konfigurasi `webos` di `.claude/launch.json`). Browser menerapkan
  CORS, jadi sebagian playlist hanya termuat di TV.
- Paket `.ipk`: `webos/package.sh` (versi mengikuti `gradle.properties`).

## Belum ada
Siaran DRM, header Referer/User-Agent khusus (aplikasi web tidak bisa mengirimnya; sumber seperti itu dicoba terakhir),
rekaman, remote dari HP, akun/Handoff, notifikasi server, dan pembaruan otomatis.
