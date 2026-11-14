# Changelog

Catatan rilis Tahoe TV. Bagian versi yang dirilis otomatis menjadi catatan rilis GitHub, dan tampil di aplikasi
(TV: Pusat Kontrol → Tentang · HP: Setelan · Desktop: Setelan → Tentang & pembaruan).

## Unreleased (Belum dirilis)

### Baru
- **Satu sumber versi rilis, gerbang CI, & roadmap Now/Next/Later (#100, #57, #98, #60).** Mengelola versi dan `versionCode` terpusat dari `gradle.properties` (`tahoe.version`/`tahoe.versionCode`) melalui kode `:core` yang dihasilkan otomatis (`TahoeVersion`), memperketat `pr.yml` dengan pemeriksaan `website/build.sh` serta `:launcher:lintDebug` dan `:companion:lintDebug`, menambahkan job `verify` (tes JVM + validasi tag rilis) sebelum build rilis di `desktop.yml`, mengaktifkan Dependabot Gradle, menyelaraskan `website/Dockerfile` dua tahap dengan `website/build.sh`, serta menata ulang `docs/ROADMAP.md` dan `docs/id/ROADMAP.md` dengan pembagian **Now / Next / Later**, ruang lingkup yang tidak dikerjakan, dan ritme rilis bulanan.
- **Antarmuka dengan bahasa awam & ramah penonton (#65).** Menambahkan glosarium EN/ID/中文 di `docs/brand/BRAND.md`, kunci terjemahan status saluran dan badge `WEB` di `I18n`, mengganti teks teknis di desktop (`Server LIVE & Aktif`, `DRM`/`DASH`/`RELAY`, `relay header`), dasbor web IPTV, pesan pemulihan sumber di TV (`LiveTv.java`), serta panduan izin sambungan TV di HP (`Setup.kt`).
- **Dukungan inti multibahasa & multi-negara (#80, #79, #56).** Menghapus normalisasi hardcoded Indonesia di `Epg.norm`, menambah pencocokan EPG berbasis `tvg-id` (`Epg.byId`), memparameterkan bahasa dan wilayah di `Web` serta `LocalTv`, menyambungkan `TvViewer.zone` untuk jadwal shalat di launcher/desktop/HP, dan menambahkan `I18n` tiga bahasa (English, Bahasa Indonesia, 中文) pada `:core`, desktop, serta aplikasi pendamping HP.
- **Mode demo terprogram & panduan privasi screenshot (#99).** Menambahkan `Demo` (`--demo` / `-Dtahoe.demo=true`) di `:core` dan desktop dengan profil netral (`Alex` / `Rina` / `小林`), daftar saluran serta jadwal contoh tanpa menyentuh preferensi pengguna, mengganti pratinjau `og:image` situs web dari foto pribadi ke ikon brand, serta memperbarui aturan kontribusi di `CONTRIBUTING.md`.
- **Penyelarasan brand & token gerak (#91).** Menambahkan `Brand.tagline(lang)` dalam tiga bahasa, memperbarui deskripsi antarmuka tanpa mengacu pada merek dagang sistem operasi pihak ketiga di README dan situs web, mengekspor variabel CSS `--motion-fast`/`--motion-med`/`--motion-slow` serta mengganti palet warisan dengan `--cyan-deep`/`--lake-deep`.
- **ID saluran stabil lintas rotasi URL (#94).** Menambahkan `M3u.Channel.channelId` (`tvg-id` ternormalisasi atau slug nama bersih), dukungan migrasi favorit pada `Viewer` (`schema = 2`), dan ID rute konsisten di server IPTV desktop.
- **Aset web player bundel lokal tanpa CDN (#81).** `hls.min.js` dan `shaka-player.compiled.js` kini dibundel langsung di `:desktop` dan disajikan dari `/assets/` sehingga pemutar web tetap berjalan di jaringan lokal tanpa internet/CDN eksternal.
- **Situs web bahasa 中文 & peningkatan aksesibilitas (#110).** Menambahkan tombol bahasa 中文 (`zh-Hans`) beserta terjemahan lengkap di `website/`, fallback font CJK, navigasi keyboard pada tab fitur (`ArrowLeft`/`ArrowRight`/`Home`/`End`), serta jeda otomatis karusel saat hover atau fokus keyboard.
- **Kebijakan sumber saluran & tata kelola komunitas (#101, #97).** Menambahkan `docs/SOURCES.md` (aturan siaran gratis/tanpa DRM dan kebijakan penghapusan 7 hari dalam tiga bahasa), `MAINTAINERS.md`, `.github/FUNDING.yml`, serta templat PR berbahasa Inggris.

### Perbaikan
- **Sinkronisasi HP ↔ TV tahan putus & penggabungan konfigurasi (#111, #96, #107).** Menambahkan nomor revisi dan penggabungan delta `Viewer.diff`/`applyPatch` (mempertahankan favorit TV ketika wilayah diperbarui dari HP) beserta konfirmasi sinkronisasi setelah onboarding HP, antrean pesan offline terbatas (`TvLink`) dan instans bersama (`TvLink.shared`), penjaga henti reconnect di layanan pendamping (`Sync`), batas tunggu 2 detik `/apps` di TV (`Companion`), serta pembacaan/penulisan snapshot yang aman di server IPTV desktop (`IptvServer`).
- **Penyimpanan cache status saluran yang atomik (#106).** Memulihkan `SourceHealth` per baris bila ada entri rusak dan menyinkronkan penulisan lewat file sementara atomik agar cache status saluran tidak korup saat penulisan paralel.
- **Performa panduan TV, filter daftar saluran, & cache gambar (#108, #109).** Menunda inflasi baris panduan TV di `LiveTv.java` dengan render bertahap (`ROW_WINDOW = 60`, `ROW_STEP = 40`) dan pembaruan satu baris di tempat, mememoisasi pencarian serta penyaringan saluran di HP dan desktop agar tidak terseret pembaruan waktu 30 detik, membatasi cache bitmap jaringan di HP (8 MB `LruCache`) dan desktop (16 MB LRU sinkron), serta menurunkan skala decoding bitmap berlebih (`inSampleSize`) di HP dan TV (`Avatar.java`).
- **Ketahanan pemutar desktop & relay HLS (#75).** Mengalihkan `HlsProxy.open` ke `Web.openFollowingRedirects` agar pengalihan lintas domain dengan header khusus berjalan lancar, menambahkan `HlsProxy.trimDiscontinuity` untuk memangkas segmen pra-rol sebelum `#EXT-X-DISCONTINUITY` yang sering membuat JavaFX macet, dan menambahkan fallback otomatis satu kali ke `WebPlayer` saat siaran live terverifikasi atau kodek tak didukung gagal di JavaFX.
- **Dukungan pengalihan lintas protokol http ↔ https (#105).** Menambahkan `Web.openFollowingRedirects` (hingga 5 hop, mendukung `Location` relatif dan perpindahan skema `http`/`https`) di `:core` untuk `Web`, `HlsProbe`, `Sources`, dan `HlsRecorder`.
- **Pengamanan alur CI rilis dan dependensi aksi (GHSA-cp6v-7p8f-hpvx).** Mengunci semua third-party GitHub Actions ke commit SHA permanen, menambahkan konfigurasi Dependabot mingguan untuk pembaruan Actions otomatis, serta membatasi akses rahasia penandatanganan keystore rilis hanya pada environment rilis (tag versi resmi atau branch main).
- **Pengamanan link HP ↔ TV Companion (GHSA-34j8-5cp5-rcmv).** Membatasi ukuran body endpoint pairing `/pair` maksimal 1 KB sebelum parsing guna mencegah serangan penghabisan memori/penyimpanan TV, mengalihkan pengiriman token autentikasi dari query parameter URL ke header HTTP `Authorization: Bearer` / handshake WebSocket, serta menyediakan fungsi pencabutan token (`revoke`).
- **Pengamanan server IPTV desktop (GHSA-x8v7-r42c-7qqj).** Membatasi CORS hanya untuk origin lokal tepercaya, memvalidasi header Host terhadap alamat lokal komputer guna mencegah serangan DNS rebinding, menerapkan Content-Security-Policy (CSP) serta sanitasi DOM (textContent) pada dasbor pemutar, dan melindungi URL serta kunci DRM pribadi agar tidak terekspos ke halaman web pihak ketiga.
- **Verifikasi ukuran dan checksum pembaruan aplikasi OTA (#45).** Pengunduhan pembaruan di TV, ponsel, dan desktop kini memeriksa integritas ukuran unduhan terhadap header `Content-Length` (menghapus file `.part` dan membatalkan jika koneksi terputus di tengah jalan), mendukung resolusi pengalihan (redirect) URL relatif, memverifikasi hash SHA-256 dari aset rilis, serta menerbitkan `SHA256SUMS` di alur rilis GitHub.
- **Launcher release-signed di installer desktop (#92).** Memperbaiki alur CI build desktop (`desktop.yml`) agar mendekode keystore rilis sebelum mem-bundle `launcher-release.apk` ke dalam installer desktop (DMG & MSI), sehingga APK launcher yang dikirimkan ke TV selalu ditandatangani release key resmi (bukan debug key).
- **Hapus data pribadi pemilik dari nilai default publik (#78).** Menghapus nama pribadi, URL foto avatar, kota, dan koordinat geografis dari nilai bawaan launcher dan aplikasi desktop agar privasi pemilik terjaga dan seluruh data lokasi diatur murni via onboarding pengguna.
- **Legenda tombol warna merah remote (#103).** Memperbaiki legenda di banner OSD Live TV: tombol merah fisik remote kini tertulis jelas sebagai penanda siaran rekaman / "Bukan live" (sesuai aksi tombol merah fisik `PROG_RED`), sedangkan tombol `↩` (Back) tetap menjadi petunjuk untuk keluar siaran.
- **Arah ganti saluran CH+/CH− (#102).** Tombol CHANNEL UP (CH+) di remote fisik TV dan tombol rocker CH atas di remote HP kini menaikkan nomor saluran (saluran berikutnya), dan CHANNEL DOWN (CH−) / rocker bawah menurunkan nomor saluran. Tombol arah D-pad ▲/▼ di TV tetap berfungsi alami sesuai daftar saluran (▲ ke item sebelumnya di atas, ▼ ke item berikutnya di bawah).
- **Penyempurnaan tampilan siaran tidak tersedia (Slate #114).** Teks status OSD dan banner TV disembunyikan agar tidak menutupi slate saat saluran gagal. Alasan kegagalan kini melacak siaran rekaman (`sawRecording`) secara akurat di TV dan desktop. Tata letak disederhanakan agar lebih tenang: pesan utama berukuran besar, dua bahasa lainnya ditampilkan dalam baris statis yang tenang tanpa animasi berkedip, teks status 'Mencoba lagi otomatis…' menggantikan hitungan detik, petunjuk remote TV disajikan sebagai tombol kapsul (pill) dengan glif yang jelas, target sentuh tombol HP diperbesar minimal 48 dp, dan judul laporan masalah disesuaikan dengan bahasa pilihan.
- **Streaming parser panduan acara EPG mencegah kehabisan memori (#104).** Pengunduhan dan pembacaan XMLTV kini dilakukan secara streaming langsung dari koneksi jaringan HTTP (mendukung kompresi gzip) tanpa memuat seluruh teks XML ke memori. Panduan disaring hanya untuk saluran yang ada di daftar putar serta rentang waktu yang relevan (-6 jam sampai +24 jam), deskripsi panjang dipotong maksimal 160 karakter, dan batas aman diterapkan agar perangkat seperti Android TV stick 1 GB dan ponsel tidak mengalami crash out-of-memory saat memuat panduan berukuran besar.

## 3.2.0

### Baru
- **Tampilan "siaran tidak tersedia" (#76).** Saat semua sumber sebuah saluran gagal, TV, HP, desktop, dan Web Player menampilkan slate Tahoe dengan permintaan maaf dalam **tiga bahasa sekaligus** (Indonesia, English, 中文; bahasa pilihanmu paling atas) beserta alasannya. Tahoe terus mencoba lagi otomatis setiap 30 detik (sampai ±10 menit) dan langsung kembali ke siaran begitu ada sumber yang hidup. Tombolnya: **Coba lagi**, **Saluran berikutnya**, dan di HP layar penuh juga **Laporkan saluran**. Di TV: OK untuk coba lagi, ▼ untuk saluran berikutnya.
- **Onboarding lengkap di HP dan desktop (#77).** Saat pertama dibuka (atau lewat **Wilayah & bahasa** → Ubah), Tahoe menanyakan 10 langkah, dimulai dari bahasa:
  1. **Bahasa:** English, Bahasa Indonesia, atau 中文（简体）.
  2. **Negara tempat tinggal.**
  3. **Kota** untuk cuaca dan jadwal shalat (dicari otomatis; boleh dilewati).
  4. **Zona waktu:** otomatis dari kota, atau pilih sendiri. Dipakai jam dan jadwal shalat di desktop (jadwal acara menyusul).
  5. **Jadwal shalat:** tampil atau tidak (bawaan sesuai negara) dan metode perhitungannya (Kemenag, JAKIM, MUIS, MWL, ISNA, Umm al-Qura, Diyanet, dll.). Di TV, mematikannya juga menghentikan adzan.
  6. **Negara saluran:** boleh lebih dari satu.
  7. **Kategori** yang ingin ditonton.
  8. **Favorit awal.**
  9. **Urutan favorit.**
  10. **Ringkasan.**

  Hasilnya langsung dipakai TV Live: kategori pilihan yang tampil (favorit selalu tetap tampil), dan favorit muncul paling depan sesuai urutan.
- **Onboarding juga di TV (#77).** Saat pertama menyala, launcher menanyakan 10 langkah yang sama, dengan baris besar yang mudah dipilih memakai remote. Untuk mengurutkan favorit: OK untuk mengangkat, ▲ ▼ untuk memindah, OK lagi untuk menaruh. Bisa dibuka lagi dari Pusat Kontrol → **Wilayah & bahasa**.
  - Lebih mudah dari HP: pilihan onboarding di HP yang sudah dipasangkan langsung dikirim ke TV, dan TV melewati onboarding-nya sendiri.
  - TV Live di TV memakai negara saluran, kategori, dan urutan favorit pilihan.
  - Jadwal shalat bisa dimatikan dan memakai metode pilihan (sebelumnya selalu Kemenag RI).
  - TV yang sudah diatur sebelumnya (kota dari app HP) tidak ditanya lagi. Teks onboarding tersedia dalam tiga bahasa; teks aplikasi lainnya menyusul (#56).

- **Saluran untuk semua negara (#71, tahap 1).** Saat pertama dibuka, app HP dan desktop menanyakan **bahasa** lalu **negara** (disarankan dari setelan perangkat, nama negara dalam bahasa yang dipilih). Daftar saluran dan jadwal acara mengikuti negara itu: Indonesia tetap dengan sumber pilihannya, negara lain memakai daftar per negara dari iptv-org dan jadwal dari epg.pw. Ganti kapan saja: HP lewat kartu **Wilayah & bahasa** di Beranda, desktop lewat Setelan → Wilayah & bahasa. "Dekat kamu" hanya muncul untuk Indonesia. Teks aplikasi lainnya masih berbahasa Indonesia (terjemahan: #56); TV ikut lewat onboarding di atas.

### Perbaikan
- **Desktop: saluran yang servernya "hidup tapi kosong" tidak lagi ditunggu 20 detik.** Sebagian sumber menjawab dengan halaman HTML (mis. Metro TV) atau playlist utama yang hidup sementara semua kualitasnya ditolak (403/404). Dulu sumber seperti ini tetap ditandai LIVE dan pemutar menunggu sampai habis waktu; sekarang langsung dianggap mati dan pindah ke sumber cadangan, di TV, HP, dan desktop (#75).
- **Kualitas yang mati dilewati.** Server IPTV desktop (Web Player Windows, VLC/TiviMate di LAN) membuang varian kualitas yang tidak menjawab. Pemutar macOS melakukan hal yang sama untuk sumber yang lewat relay, dan otomatis memakai relay bila varian pertama sumber itu mati, sehingga tidak berhenti di varian yang rusak.
- User-Agent bawaan untuk permintaan siaran kini sama di TV, HP, pengecekan sumber, relay desktop, dan server IPTV (`TahoeTV/<versi>`); sumber yang meminta User-Agent sendiri tetap memakai miliknya. Pemutar desktop yang memutar langsung (tanpa relay) masih memakai User-Agent bawaan Java.

### Diketahui
- Tombol **CH+** di remote masih pindah ke nomor saluran sebelumnya (dan CH− ke berikutnya); diperbaiki di rilis berikutnya (#102).
- Saluran dengan audio MP2/AAC-LATM atau iklan sisipan rapat belum bisa diputar di pemutar desktop macOS (#75).
- Teks aplikasi di luar onboarding dan layar "siaran tidak tersedia" masih berbahasa Indonesia (#56).

## 3.1.0

### Perbaikan
- **Windows: siaran tidak bisa ditonton sama sekali.** Pemutar desktop (JavaFX) gagal memutar siaran HLS di Windows 10/11 ("Could not create player!"), jadi tidak ada satu saluran pun yang tayang, dan jendela pemutar bisa macet. Di Windows, saluran sekarang diputar di **Web Player** dalam satu jendela Edge mode aplikasi (tanpa tab dan address bar; bila Edge tidak ada, di browser bawaan) memakai hls.js lewat relay server Tahoe, yang ikut mengirim header yang dibutuhkan sumber dan pindah ke sumber cadangan sendiri. Jendela itu mengikuti app: ganti saluran memakai jendela yang sama, **Stop** menghentikannya, dan tombol ◀ ▶ (atau ↑ ↓) di pemutar mengganti saluran. Saluran DASH/DRM tetap lewat Web Player di dashboard server. macOS tetap memakai pemutar bawaan.
- **Server IPTV desktop berisi 0 saluran.** Server dulu hanya membaca file playlist lokal, sehingga dashboard web, playlist LAN untuk TV/HP/VLC, dan Web Player kosong bagi hampir semua pengguna. Sekarang server memuat daftar yang sama dengan TV Live: ±1.000 saluran bawaan, atau playlist Anda sendiri bila sudah diatur (dimuat ulang saat setelan playlist berubah).
- **Uji mandiri desktop** (`Tahoe TV --selftest`): memeriksa internet, daftar saluran, server, dan pemutar tanpa membuka jendela. Workflow "Desktop selftest" (manual, atau di branch `windows-*`) menjalankannya pada app terpaket di Windows dan macOS, dan gagal bila langkah wajib gagal (pemutaran JavaFX di Windows hanya dicatat sebagai info).

### Baru
- **Kualitas video dan latensi siaran** di HP, TV, dan desktop:
  - **Kualitas:** Otomatis, 1080p, 720p, 480p (batas tertinggi; kalau siaran tidak punya, dipakai yang terdekat di bawahnya).
  - **Latensi:** Rendah (±8 detik di belakang siaran), Standar (±20 detik), atau Stabil (±35 detik, paling sedikit jeda).
  - Tempat mengatur: HP lewat ⚙ di pemutar, TV lewat Pusat Kontrol (◀ ▶), desktop lewat Setelan → TV Live.
  - Di macOS kualitas diterapkan lewat proxy pemutar; latensi diatur otomatis oleh pemutar bawaan.
- **Tampilan baru app HP** (terinspirasi Mochy Music, #60):
  - **Warna:** latar gelap hangat, dan warna aplikasi mengikuti logo saluran yang sedang ditonton.
  - **Navigasi:** tab bar berbentuk pil yang melayang.
  - **Beranda:** hero besar "Lanjut nonton", rak kartu persegi (Terakhir ditonton, Favorit), chip kategori, dan kartu angka.
  - **Mini-player:** siaran tetap jalan saat pindah tab; ⏯, saluran berikutnya, dan ✕ ada di mini-player di atas tab bar.
  - **Halaman saluran:** logo bulat, nama besar, Tonton + ♥, acara yang sedang tayang, jadwal berikutnya, dan status siaran dalam bahasa sehari-hari.
  - **Pemutar:**
    - kontrol besar ⏪30 ⏯ ⏩30 yang tersembunyi sendiri setelah 3 detik (ketuk untuk menampilkan);
    - badge format siaran (mis. "720p · H.264 · AAC");
    - kolom cari berbentuk pil.
- **Proyek internasional (#71):** README, dokumentasi, dan situs [tahoe.kousozo.tech](https://tahoe.kousozo.tech) kini dalam bahasa Inggris dan Indonesia, lengkap dengan screenshot beranimasi dan daftar kontributor. Saluran untuk semua negara serta pilihan bahasa + negara saat setup awal sedang dikerjakan.
- **Saluran favorit di HP:** tekan ♥ di pemutar atau halaman saluran; muncul di Beranda dan sebagai chip "♥ Favorit" di Live.

## 3.0.2

### Keamanan
- **Pairing HP ↔ TV lebih aman:** kode 6 digit kini kedaluwarsa setelah 10 menit, dan beberapa kode salah berturut-turut membuat pairing dikunci sementara (waktunya bertambah tiap kali). Tekan **OK** di layar Hubungkan HP untuk kode baru dan membuka kunci.
- **Server IPTV desktop** (aplikasi dan `tools/iptv_server.py`) hanya meneruskan alamat siaran yang ia tulis sendiri ke playlist, dan dashboard web tidak lagi menampilkan teks dari playlist sebagai HTML.

### Perbaikan
- `tools/iptv_server.py`: setiap respons error (404/403/502) dulu membuat server memutus koneksi tanpa jawaban; sekarang dijawab dengan benar.
- **Desktop: hanya sekitar seperempat saluran yang terdeteksi LIVE.** Pengecekan status semua saluran dihentikan paksa setelah ±10 detik, padahal butuh sekitar setengah menit. Sekitar tiga perempat saluran tidak pernah mendapat status: tertahan di "Memeriksa koneksi…", hilang dari filter LIVE, dan pemutar tidak tahu sumber mana yang hidup. Sebagian hasil juga hilang karena ditulis bersamaan dari banyak thread. Sekarang setiap saluran selesai dicek: di Mac uji, saluran dengan sumber LIVE naik dari 240 menjadi 732 dari 942.
- **Desktop: playlist terkompresi.** Beberapa server siaran, di antaranya CNBC/detik untuk Trans TV dan Trans7, mengirim playlist dalam bentuk terkompresi (gzip) walau tidak diminta. Pemutar TV dan HP membukanya otomatis, tetapi app desktop membacanya mentah. Akibatnya playlist rusak: saluran gagal diputar dan ditandai OFFLINE. Sekarang playlist dibuka dengan benar di pemutar desktop, pengecek status, server IPTV LAN, dan perekam.
- **Header Referer/Origin bertanda kutip** dari playlist (misalnya `"https://20.detik.com/"`) kini dikirim tanpa tanda kutip, sehingga server tidak lagi menolak sumber tersebut.


## 3.0.1

> **Rilis stabil pertama seri 3.0.** Selain yang di bawah, versi ini juga berisi semua isi 3.0.0 (beta): profil Anak dengan PIN, rekaman acara di desktop, perbaikan siaran Trans7 yang berulang dan layar hitam di desktop, loading baru, tunda siaran langsung, dan sambung HP lewat QR.

### Baru
- **Dekoder FFmpeg (TV & HP):** saluran yang suaranya memakai AC-3/Dolby Digital, E-AC-3, DTS, atau MP2 kini tetap bersuara walau dekoder bawaan perangkat tidak mendukungnya. FFmpeg baru dipakai setelah dekoder bawaan menolak, jadi saluran yang sudah lancar tidak berubah. Hanya audio; format video tetap memakai dekoder perangkat. APK hanya berisi pustaka ARM (TV stick dan HP) dan terkompresi: APK TV naik sekitar 1,5 MB, APK HP sekitar 3 MB karena ikut membawa APK TV untuk fitur pasang ke TV.
- **Kirim log:** untuk melaporkan saluran yang gagal diputar atau aplikasi yang tertutup sendiri.
  - HP: Remote → **Kirim log** mengirim log HP sekaligus log TV yang tersambung lewat WhatsApp, email, dan sejenisnya.
  - TV: Pusat Kontrol → **Kirim log** menandai momen masalah di log; HP yang sedang membuka Remote langsung menawarkan pengiriman.
  - Desktop: Setelan → Bantuan → **Simpan & salin log** (file di Downloads + clipboard) dan **Laporkan di GitHub**.
  - Isinya catatan pemutar (saluran, sumber, dekoder, error) dan crash. Alamat siaran dicatat tanpa token, kunci, atau kata sandi.
- **Atur ulang PIN Anak dari HP:** lupa PIN? Di HP yang sudah dipasangkan dengan TV: Remote → **PIN Anak**, masukkan PIN baru dua kali. PIN lama tidak diperlukan; HP yang sudah dipasangkan menjadi buktinya.

### Perbaikan
- Tes server IPTV desktop memakai playlist uji sendiri, tidak lagi gagal saat `koutv.m3u` tidak ada.

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
