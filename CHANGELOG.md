# Changelog

Catatan rilis Tahoe TV. Bagian versi yang dirilis otomatis menjadi catatan rilis GitHub, dan tampil di aplikasi
(TV: Pusat Kontrol → Tentang · HP: Setelan · Desktop: Setelan → Tentang & pembaruan).

## 3.0.0

> **Beta:** rilis uji coba. Laporkan masalah di GitHub Issues sebelum versi ini dijadikan rilis stabil.

### Perbaikan
- **Siaran rekaman / "siaran kemarin yang berulang"** (misalnya Trans7): sumber yang playlist-nya sudah selesai (`ENDLIST`/VOD) atau segmen terbarunya bertanggal lama kini terdeteksi dan dilewati, di TV, HP, dan desktop, lalu pindah ke sumber cadangan. Sebelumnya cukup "menjawab 200" untuk dianggap LIVE. Jika ada sumber yang tidak terdeteksi otomatis: TV tahan **OK** (atau tombol merah) dan desktop tombol **⚑ Bukan live** untuk melewatinya. Salah tandai? Saat saluran tak bisa diputar, tahan OK lagi (TV) atau **Pulihkan sumber yang ditandai** (desktop).
- **Desktop, layar hitam di Live TV:** pemutar tidak lagi menganggap "sedang memutar" hanya karena ada track suara; gambar hitam terus-menerus atau stream tanpa gambar terdeteksi, sumber berikutnya dicoba, dan jika semua gagal muncul pilihan **Buka di Web Player**.
- **Desktop, tidak bisa pindah saluran:** pemutar lama dilepas di latar sehingga tidak menahan saluran baru, dan satu klik pada kartu saluran langsung memindah saluran saat pemutar terbuka.

### Baru
- **Profil Anak dengan PIN (TV):** kartu ketiga **Anak** di layar "Siapa yang menonton?". Saat pertama dipilih, orang tua membuat PIN 4 angka (diketik dengan tombol arah, karena remote Mi tidak punya tombol angka). Di profil Anak, Live TV dan Jadwal TV hanya menampilkan saluran **Anak & Animasi** dan **Dokumenter**, dan hanya aplikasi yang diizinkan yang muncul (bawaan: Live TV dan YouTube Kids). Asisten, pairing HP, Rumah, Setelan, dan menu aplikasi (sematkan/uninstall) disembunyikan, dan perintah "setelan" dari HP diabaikan. Pindah ke profil lain (Pusat Kontrol → **Ganti profil**, atau saat menyalakan TV) meminta PIN; setelah salah memasukkan PIN ada jeda 2 detik. Atur PIN dan aplikasi yang boleh dibuka di profil Saya: Pusat Kontrol → **Profil Anak**.
- **Rekaman acara (desktop):** di TV Live, tekan **Rekam acara ini** untuk acara yang sedang tayang (sampai selesai, +2 menit), atau ikon ○ di daftar "Berikutnya" untuk menjadwalkan acara nanti (mulai 1 menit lebih awal). Menu baru **Rekaman** berisi daftar terjadwal, sedang merekam, dan selesai, dengan tombol Putar, Hentikan, Batalkan, dan Hapus. File disimpan di `~/Movies/Tahoe TV` (Windows: `Videos`). Perekaman dimulai dari ujung live, mencoba sumber cadangan bila sumber gagal, dan menolak siaran terenkripsi. Rekaman berjalan selama app desktop terbuka.
- **Server IPTV:** `GET /api/recordings` (daftar, untuk perangkat mana pun di LAN) dan `/recordings/<id>` (file rekaman dengan dukungan Range, jadi bisa ditonton dan di-seek dari TV/HP/VLC). Menjadwalkan, menghentikan, dan menghapus rekaman (`POST`/`DELETE /api/recordings`) hanya diterima dari komputer itu sendiri.
- **Loading ala Apple TV / Netflix** di TV, HP, dan desktop: logo (HP: inisial) dan nama saluran dengan spinner 12 garis; tidak ada lagi teks "Mencoba sumber cadangan…".
- **Tunda siaran langsung (TV & HP):** ⏪/⏩ 30 detik di dalam jendela siaran, jeda, dan kembali ke live. Di TV tombol media (Play/Pause, Rewind, Fast Forward) bekerja; label LIVE berubah jadi "⏪ -1 mnt" saat tertinggal.
- **Dekoder cadangan** di TV dan HP: jika dekoder utama menolak sebuah stream, dekoder lain dicoba sebelum sumber dianggap gagal.
- **Server IPTV (desktop dan `tools/iptv_server.py`):** `/api/health` (live / rekaman / basi / mati per saluran), `?live=1` pada playlist dan `/api/channels` untuk menyembunyikan rekaman dan saluran mati, serta cache EPG 6 jam.
- **Sambungkan HP lewat QR:** pindai QR di TV (Pusat Kontrol → HP) dengan kamera HP, pilih Tahoe TV, dan app langsung tersambung dan membuka Remote. Tidak perlu lagi mengetik alamat dan kode.
- **Cari saluran atau acara** (HP & desktop): pencarian kini juga mencocokkan jadwal acara, misalnya "bola", "berita", atau judul acara, dan menampilkan acaranya sedang tayang atau pukul berapa.

## 2.9.5
- **Remote HP tidak lagi force close** saat koneksi ke TV terputus (misalnya TV masuk standby atau Wi-Fi tersendat) ketika tombol ditekan, saat menyambung ulang, atau saat meneruskan notifikasi/musik.
- **Lebih sedikit buffering di Live TV** (TV & HP): siaran diputar sekitar 20 detik di belakang siaran langsung (sebelumnya ~12 detik), jadi potongan video yang lambat dari server (misalnya Trans7) tidak langsung membuat siaran berhenti. Saluran yang servernya hanya menyimpan sedikit video (misalnya Trans TV, 9 detik) tetap bisa sesekali buffering.

## 2.9.4

### Perbaikan
- **Siaran tidak lagi memuat ulang dan mundur ~15 detik** setelah sekitar satu menit menonton (TV, HP, desktop). Saat tersendat, siaran lanjut dari posisi yang sama; buffering biasa diberi waktu 30 detik, dan jeda tidak lagi dianggap macet.
- **HP & tablet:** layar penuh benar-benar penuh, memutar HP ke landscape langsung layar penuh, dan memutar layar tidak lagi kembali ke Beranda.
- **Aksesibilitas HP:** tombol pemutar menyebut fungsinya, pesan memuat/gagal dibacakan TalkBack.

### Adzan
- Saat adzan berbunyi, Live TV, YouTube, dan musik **dijeda**, lalu lanjut setelah doa selesai.
- **Suara adzan asli bawaan**: adzan Subuh khusus untuk waktu Subuh, adzan Masjid Mesir untuk waktu shalat lainnya, lalu **doa sesudah adzan**. File yang diunggah dari HP tetap bisa menggantikannya.
- Panel Shalat & adzan punya dua tombol tes: adzan biasa dan adzan Subuh.

### Live TV
- **Favorit**: tekan ▶ pada saluran di daftar untuk menambah/menghapus. Favorit dan **saluran yang terakhir ditonton** tampil paling atas.
- **Titik hidup/mati** di setiap saluran, dari pengecekan server.
- **Saluran sebelumnya**: tombol LAST di remote TV, atau ⟲ di remote HP.
- **Pengingat acara**: tekan Menu pada saluran di daftar untuk diingatkan saat acara berikutnya mulai. Jika Live TV sedang terbuka, langsung pindah ke saluran itu.

### Timer tidur
- TV masuk standby setelah 15/30/60/90/120 menit, dengan peringatan 1 menit sebelumnya. Atur dari Pusat Kontrol (◀ ▶) atau remote HP.

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
