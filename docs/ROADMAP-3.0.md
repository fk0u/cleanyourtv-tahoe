> Arsip: sebagian besar rencana ini sudah dirilis di 2.9.3–3.0.1. Rencana terbaru: [ROADMAP.md](ROADMAP.md).

# Rencana Tahoe TV 2.9.3 → 3.0.0

Dua rilis, bukan satu. **2.9.3** membereskan hal yang paling bikin pengguna kesal (nunggu siaran, layar hitam,
remote kurang) dan memasang **OTA**, supaya **3.0.0** (personalisasi, adzan, desktop baru) bisa langsung
sampai ke semua TV tanpa pasang manual lagi. Sejak 2.9.2 APK sudah memakai satu kunci rilis, jadi OTA aman dipasang.

| | 2.9.3 — "Cepat & lengkap" | 3.0.0 — "Punyamu" |
|---|---|---|
| Fokus | Waktu tunggu, remote, OTA | Personalisasi, adzan, desktop |
| Perkiraan | ±1 minggu | ±2–3 minggu |
| Risiko | Rendah (perubahan di jalur yang sudah ada) | Sedang (onboarding baru, audio, desain ulang) |

---

## 2.9.3

### 1. Siaran langsung jalan: cek server di awal, putar sumber yang hidup
**Sekarang:** TV & HP mencoba sumber 1 → tunggu gagal (sampai 20 dtk) → sumber 2 → … Cek status semua server
(`Sources.checkStatus`) hanya ada di desktop.

**Rencana:**
- `core/SourceHealth.kt` (baru, dipakai TV, HP, desktop): saat aplikasi dibuka, cek semua URL saluran di latar
  (32 paralel, timeout 3,5 dtk, yang sudah ada di `Sources.check`). Hasil disimpan: `url → hidup/mati, latensi, waktu cek`.
- Cache ke disk (TTL 15 menit), jadi buka aplikasi berikutnya langsung pakai hasil terakhir sambil cek ulang di latar.
- Saat pindah saluran: urutkan sumber **hidup dulu, latensi terkecil dulu**. Contoh: sumber 1 & 2 mati, 3 hidup → langsung putar 3, tanpa menunggu 1 dan 2 gagal.
- Setelah saluran tayang: cek ulang sumber saluran itu saja di latar; kalau sumber aktif putus, pindah ke cadangan yang sudah diketahui hidup (logika sambung ulang 2.9.2 tetap).
- Prefetch: saluran sebelum/sesudah yang sedang ditonton dicek lebih sering, supaya ▲▼ juga instan.
- Daftar saluran menampilkan titik hidup/mati (seperti desktop), dan saluran yang semua sumbernya mati diturunkan ke bawah.

**Selesai jika:** waktu dari tekan saluran sampai gambar muncul < 3 dtk untuk saluran yang hidup (diukur di Mi TV), dan tidak ada lagi "Mencoba sumber cadangan…" untuk sumber yang sudah diketahui mati.

### 2. Animasi saat memuat, bukan layar hitam
- TV: logo saluran besar + nama + nomor di tengah, cincin/pulse Cyan yang bergerak, teks status kecil ("Menyambung ke sumber 3…"), latar blur dari poster/logo. Hilang dengan fade saat frame pertama tampil.
- HP & desktop: komponen yang sama (`Brand` motion: 260 ms masuk, pulse 1,2 dtk).
- Saat "Menyambung ulang…" (2.9.2): animasi yang sama, gambar terakhir tetap terlihat redup di belakang.
- Hormati "kurangi gerakan" (TV: animasi berhenti jadi titik statis).

### 3. Remote HP bisa semuanya
Sudah ada: D-pad/OK (semua aplikasi sejak 2.9.2), back, home, volume, mute, CH, media, Live TV, aplikasi, setelan, tanya asisten.

Tambahan:
| Fitur | Cara di TV |
|---|---|
| Ketik teks (cari di YouTube/Netflix) | Keyboard HP → `ACTION_SET_TEXT` ke kolom yang fokus (accessibility) |
| Tombol angka 0–9 (pindah saluran langsung) | Live TV menerima nomor saluran |
| Power / standby / bangunkan | `GLOBAL_ACTION_LOCK_SCREEN` / keyevent lewat ADB jika ada |
| Slider volume + angka % | `setStreamVolume` langsung, status volume dikirim balik ke HP |
| Info, subtitle, rasio layar di Live TV | pesan baru ke `LiveTv` |
| Touchpad (geser = gerakkan fokus, ketuk = OK) | sudah ada di D-pad, dijadikan mode layar penuh |
| Status balik ke HP | TV mengirim: aplikasi di depan, volume, saluran yang diputar |

**Selesai jika:** semua tombol di remote HP punya efek di TV, diuji di Live TV, YouTube, Netflix, Setelan.

### 4. OTA, changelog, About
- **Cek pembaruan:** tiap aplikasi membaca `releases/latest` dari GitHub (sekali sehari + tombol "Cek sekarang").
- **TV:** unduh `tahoe-launcher.apk`, cek tanda tangan sama dengan yang terpasang, lalu pasang lewat `PackageInstaller` (minta izin "Instal aplikasi tak dikenal" sekali). Kalau HP tersambung lewat ADB, HP bisa memasangnya diam-diam.
- **HP:** sama, untuk `tahoe-tv.apk`.
- **Desktop:** unduh `.dmg`/`.msi` yang cocok (arm64/intel/windows) lalu buka.
- **Changelog di aplikasi:** isi catatan rilis GitHub ditampilkan saat versi baru pertama kali dibuka ("Yang baru di 2.9.3") dan di halaman About.
- **About:** versi, kanal, tanggal build, link GitHub, lisensi font/library, tombol "Cek pembaruan", "Kirim log".
- CI: catatan rilis diambil dari `CHANGELOG.md` (satu sumber) supaya GitHub dan aplikasi sama.

---

## 3.0.0

### 5. Onboarding adzan
Layar pertama saat instalasi (TV, atau dari HP saat pasang):
1. "Nyalakan pengingat & adzan?" Ya / Nanti.
2. Lokasi: **Provinsi → Kota/Kabupaten → Kecamatan → Kelurahan**, dengan pencarian. Dari kelurahan diambil koordinat, lalu jadwal dihitung dengan metode Kemenag RI (Aladhan, `method=20`, lat/long), lebih tepat daripada nama kota saja.
3. Pilih suara adzan (bawaan / unggah sendiri), dan dengar contoh.
4. Per waktu shalat: adzan, notifikasi saja, atau mati (sudah ada di panel, dipindah ke onboarding juga).

Data wilayah: daftar wilayah Kemendagri (kode BPS) dibundel sebagai JSON terkompresi (±1–2 MB), jadi tidak perlu internet.

### 6. Suara adzan asli bawaan
- Dua rekaman: **adzan Subuh** (dengan *ash-shalātu khairum minan-naum*) dan **adzan biasa**, dibundel di APK (Opus/AAC ~1–1,5 MB masing-masing).
- **Perlu dipastikan dulu:** rekaman dengan lisensi bebas (domain publik / CC0 / izin tertulis). Lihat "Keputusan" di bawah.
- Unggah sendiri tetap ada (sudah ada `/upload?kind=adzan`), sekarang dari HP, desktop, atau USB.

### 7. Adzan tetap bunyi saat TV standby / sleep
**Bisa:** saat TV *standby* (layar mati, Android masih hidup). Alarm sudah `setExactAndAllowWhileIdle` + pulih setelah reboot. Tambahan:
- Saat alarm: ambil `WakeLock`, nyalakan layar (`setTurnScreenOn`/`FLAG_TURN_SCREEN_ON`), paksa audio keluar di speaker TV (stream `ALARM`, volume adzan sendiri), tampilkan layar adzan, lalu kembali standby setelah selesai (opsional).
- Masuk daftar pengecualian penghemat baterai/"deep sleep" (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `deviceidle whitelist` lewat ADB saat setup).
- Uji di Mi TV: standby 1 jam, standby semalam, setelah mati listrik.

**Tidak bisa:** saat TV benar-benar mati (dicabut / *power off* penuh), karena tidak ada software yang berjalan. Di Setelan dijelaskan jujur: "Adzan bunyi saat TV standby, bukan saat dimatikan total." Pilihan tambahan: HP Companion ikut membunyikan adzan sebagai cadangan.

### 8. Personalisasi dari mana saja (TV, HP, desktop, browser)
Satu "profil tampilan" yang disimpan di TV dan bisa diubah dari perangkat mana pun (lewat koneksi yang sudah dipairing):
- **Nama & avatar:** avatar bisa **diunggah** dari galeri HP / file desktop (bukan link lagi; endpoint `/upload?kind=avatar` baru, dipotong bulat & dikompres di HP).
- **Wallpaper:** unggah sendiri (sudah ada), atau **wallpaper acak bawaan** dari sumber gratis, ganti tiap hari / tiap buka / manual, dengan tombol "Ganti sekarang" dan "Simpan yang ini". Kredit fotografer tampil kecil di pojok.
- **Tampilan:** aksen (Cyan bawaan + 5 pilihan), ukuran teks, jam 12/24, widget di beranda (cuaca, shalat, jadwal TV, now playing) bisa disusun ulang, urutan dock.
- Perubahan langsung terlihat di TV (WebSocket), dan tersimpan per profil.

### 9. Desktop: lebih modern, interaktif, immersif
- Beranda gaya "hero": saluran yang sedang tayang sebagai latar besar (preview muted), baris "Lanjut tonton", "Sedang ramai", kategori.
- Pemutar menyatu di jendela utama (bukan jendela terpisah), mode bioskop, picture-in-picture, pintasan keyboard (↑↓ saluran, M bisu, F layar penuh, / cari).
- Grid saluran dengan hover preview, transisi bersama (kartu → pemutar), animasi muat dari poin 2.
- Panduan acara (EPG) bergaya timeline yang bisa digeser.
- Panel "Remote TV" di desktop (semua fitur poin 3) untuk mengendalikan TV dari laptop.

### 10. Dokumentasi
- `README.md` ditulis ulang: apa itu Tahoe TV, tangkapan layar baru, unduh per platform, cara pasang di TV (USB / dari HP), pairing, FAQ (kenapa saluran mati, adzan saat standby, cara update).
- `CHANGELOG.md` (baru, sumber catatan rilis & changelog di aplikasi).
- `docs/`: panduan pengguna (TV, HP, desktop), panduan rilis (kunci tanda tangan, secret CI, OTA), `BRAND.md` diperbarui.
- `HANDOFF_TESTING.md` jadi checklist uji per rilis (termasuk uji di Mi TV).

---

## Keputusan yang perlu kamu ambil

1. **Rekaman adzan:** pakai rekaman berlisensi bebas yang saya carikan (perlu kamu dengar & setujui), atau kamu punya rekaman sendiri / izin dari muadzin tertentu?
2. **Wallpaper acak:** Unsplash (foto terbaik, butuh *access key* gratis, batas 50 request/jam) atau Picsum/Wikimedia (tanpa key, pilihan foto lebih sedikit)?
3. **Pasang OTA di TV:** cukup lewat penginstal Android (muncul satu layar konfirmasi tiap update), atau juga diam-diam lewat HP/ADB jika tersambung?
4. **Nomor versi:** setuju dipecah 2.9.3 lalu 3.0.0, atau semuanya sekaligus jadi 3.0.0?

## Urutan kerja

1. 2.9.3: SourceHealth + cache → animasi muat → remote lengkap → OTA/changelog/About → uji di Mi TV → rilis.
2. 3.0.0: data wilayah + onboarding adzan → audio adzan + standby → profil tampilan & unggah avatar/wallpaper → desktop baru → dokumentasi → uji → rilis lewat OTA.
