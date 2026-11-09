# Changelog

Catatan rilis Tahoe TV. Bagian versi yang dirilis otomatis menjadi catatan rilis GitHub, dan tampil di aplikasi
(TV: Pusat Kontrol → Tentang · HP: Setelan · Desktop: Setelan → Tentang & pembaruan).

## 2.9.3

### Lebih cepat
- **Saluran langsung tayang.** Saat aplikasi dibuka, semua server IPTV dicek di latar (hasilnya disimpan 15 menit). Saat pindah saluran, sumber yang sudah diketahui hidup dan paling cepat langsung diputar, tanpa menunggu sumber mati gagal dulu. Saluran yang sedang ditonton dan tetangganya dicek lebih dulu.
- **Animasi saat memuat** di TV, HP, dan desktop: logo saluran dengan cincin yang berputar, bukan layar hitam.

### Remote HP lengkap
- Tombol **power**: TV masuk standby. (Menyalakan lagi tetap pakai remote TV, karena banyak TV mematikan Wi-Fi saat standby.)
- **Slider volume** dengan angka % yang mengikuti volume TV.
- **Nomor saluran** 0–9 (juga dari remote TV): ketik nomor, Live TV pindah setelah 1,5 detik.
- **Ketik ke TV**: teks dari keyboard HP masuk ke kolom yang dipilih di TV (misalnya pencarian YouTube).
- Remote HP menampilkan saluran yang sedang diputar di TV.

### Pembaruan dalam aplikasi
- **OTA**: TV, HP, dan desktop memberi tahu jika ada versi baru dan bisa memasangnya langsung (TV: Pusat Kontrol → Tentang).
- **Tentang** & **catatan rilis** di semua aplikasi.

## 2.9.2
- Live TV tidak lagi bolak-balik "Saluran ini sedang tidak bisa diputar": sumber yang tersendat sebentar disambung ulang.
- Remote HP berfungsi di semua aplikasi, dan menyambung ulang otomatis.
- APK ditandatangani dengan satu kunci rilis, jadi versi berikutnya bisa diperbarui langsung.
- Brand baru: logo, ikon, banner TV, font Outfit.

## 2.9.1
- Identitas brand dan design system (token bersama di `core/Brand.kt`, `docs/brand/`).

## 2.9.0
- Akses saluran multi-platform (Android TV, HP, Mac, Windows) dan installer lengkap.
