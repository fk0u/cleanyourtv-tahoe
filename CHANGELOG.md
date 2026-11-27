# Changelog

Catatan rilis Tahoe TV. Bagian versi yang dirilis otomatis menjadi catatan rilis GitHub, dan tampil di aplikasi
(TV: Pusat Kontrol → Tentang · HP: Setelan · Desktop: Setelan → Tentang & pembaruan).

## Belum dirilis

### Baru
- **webOS setara lebih jauh dengan Android TV:** Beranda, jadwal acara dengan pengingat, cari, panel pemutar (daftar saluran, sumber, pengatur tidur, saluran sebelumnya), layar siaran gagal dengan nada Tahoe, lanjutkan film, profil Anak/Tamu dengan PIN, onboarding, pengaturan, cuaca dan waktu salat, tiga bahasa. Baru dicoba di browser.

## 3.8.7

**Tahoe TV v3.8.7 (Stable)** · pembaruan selalu bisa ditunda, dan Tahoe TV kini ada untuk TV LG (webOS).

### Perbaikan
- **Pembaruan selalu bisa ditunda:** modal pembaruan di TV, HP, dan desktop kini selalu punya tombol Nanti/Tutup (juga Back), termasuk untuk rilis perbaikan dan saat sedang mengunduh. Rilis wajib diingatkan lagi setelah 24 jam; onboarding tidak lagi tertahan.

### Baru
- **Tahoe TV untuk LG webOS** (folder `webos/`): TV langsung dengan kategori, favorit, cari, sumber cadangan otomatis, serta Film & Series berposter. Sudah dicoba di webOS 4.10. Dipasang dari kode sumber lewat Developer Mode (`webos/install-tv.sh`); belum menjadi berkas unduhan rilis ini.

## 3.8.6

**Tahoe TV v3.8.6 (Stable)** · film dan series kini punya bagian sendiri, tidak lagi bercampur dengan TV langsung.

### Baru
- **Film & Series** di TV (Tahoe Live), HP (Profil → Aktivitas) dan desktop (menu samping). Semua yang sumbernya pixeldrain, serta file atau judul rilisan di grup film/series, masuk ke sini dan tidak muncul di Live TV.
- **Poster dari judul:** "Nama.Film.2021.1080p.mkv" dibersihkan menjadi judul dan tahun, lalu posternya dicari (TMDB bila ada kunci; selain itu iTunes, TVMaze, IMDb, Wikipedia). Bila tidak ada kecocokan yang pasti, tampil kartu berisi judul, bukan gambar yang salah.
- **Series:** episode satu serial digabung dalam satu kartu dan diputar berurutan; di TV, Atas/Bawah pindah episode.

### Catatan
- Saluran TV 24 jam di grup "Movies" (mis. AMC, BBC First) tetap di Live TV.
- Layar Film di TV memuat poster saat kartu didekati agar ringan di TV 1 GB.

## 3.8.5

**Tahoe TV v3.8.5 (Stable)** · perbaikan tampilan Android TV yang ditemukan langsung di TV nyata.

### Perbaikan
- **Pemilih profil:** judul "Siapa yang menonton?" tidak lagi terpotong (sebelumnya terbaca "menontor").
- **Beranda:** sapaan tidak lagi tertimpa chip cuaca di baris atas (chip Tanya dan Pembaruan kini hanya ikon). Teks hero lebih mudah dibaca di atas video terang.
- **Tahoe Live:** sapaan mengikuti waktu setiap kali dibuka (sebelumnya bisa tetap "pagi" sampai sore).
- **Pemutar:** nama saluran panjang tampil utuh ("The Indonesia Channel", bukan "The In…"); lencana LIVE dan kategori kini berada di baris di bawah nama.
- **Logo saluran yang belum termuat:** huruf pengganti kini membedakan saluran sejenis (TVRI, TVRI Sport, TVRI World tampil TVR, TS, TW; sebelumnya semuanya "TVR").
- **Tentang & pembaruan:** latarnya nyaris buram sehingga beranda tidak terlihat tembus di belakang teks.

## 3.8.4

**Tahoe TV v3.8.4 (Stable)** · penyesuaian untuk Android TV dan Google TV.

### Perbaikan
- **Google TV: Back.** Saat Tahoe berjalan sebagai aplikasi (Google TV tidak mengizinkan launcher pihak ketiga menjadi layar Beranda), Back di layar utama kini kembali ke Beranda Google TV; sebelumnya tombol itu tertelan.
- **Tombol remote.** Guide dan TV membuka Tahoe Live, Search membuka pencarian.
- **Pembaruan di TV.** Langkah izin pemasangan kini mencoba halaman izin khusus Tahoe, lalu halaman "sumber tidak dikenal", lalu Keamanan, lalu Pengaturan, karena firmware TV berbeda-beda.

### Pengembangan
- Tes asap instalasi-baru di CI (pasang, hapus data, jalankan, monkey di emulator) supaya crash seperti di onboarding 3.8.0 tertangkap sebelum rilis.

## 3.8.3

**Tahoe TV v3.8.3 (Stable)** · perbaikan pembaruan: aplikasi kini memandu pengguna mengaktifkan izin pemasangan sebelum mengunduh.

### Perbaikan
- **Pembaruan gagal di HP dan TV.** Android menolak memasang pembaruan kalau izin "pasang aplikasi tidak dikenal" untuk Tahoe TV belum aktif, dan aplikasi tidak memberi petunjuk. Sekarang, sebelum mengunduh, aplikasi memeriksa izin itu, menjelaskan langkahnya (tiga bahasa), dan membuka halaman pengaturannya; setelah kembali, tekan Perbarui sekali lagi. Pada firmware TV tanpa halaman itu, penjelasannya tetap tampil.
- Catatan rilis di dialog pembaruan tidak lagi berakhir dengan titik kosong.

### Catatan
- Perangkat yang masih memakai 3.8.2 atau lebih lama perlu mengaktifkan izin itu satu kali di Setelan (Aplikasi → Tahoe TV → Pasang aplikasi tidak dikenal) sebelum bisa memperbarui ke versi ini.

## 3.8.2

**Tahoe TV v3.8.2 (Stable)** · perbaikan pembaruan, kontrol media, dan PiP di HP, ditambah tampilan beranda HP yang lebih imersif.

### Perbaikan
- **Pembaruan:** pembaruan resmi tidak lagi ditolak hanya karena sistem tidak bisa membaca tanda tangan APK baru atau memparse berkasnya; Android tetap memeriksa tanda tangan sendiri. Kalau aplikasi yang terpasang memakai kunci tanda tangan lain (build uji coba), pesannya kini jelas: hapus aplikasi lalu pasang rilis resmi. Alasan kegagalan pemasangan tercatat di log.
- **HP: PiP.** Menutup jendela PiP (✕) sekarang menghentikan siaran, tidak lagi terus berjalan.
- **HP: kontrol media.** Saluran yang diputar tampil di bayangan notifikasi dan layar kunci dengan judul acara, nama saluran, dan logo; tombol play/jeda dari sana dan dari headset bekerja, dan TalkBack bisa membacanya.
- **HP: macet 2-3 detik.** Percobaan ulang segmen yang gagal dibuat lebih cepat (0,5 sampai 2,5 detik, hingga 8 kali) agar gangguan kecil dari server publik tidak berujung pada pemuatan ulang stream. Setiap macet kini tercatat di log (lama, jarak dari siaran langsung, bandwidth) supaya penyebabnya terlihat di laporan.

### Baru
- **HP: beranda lebih imersif.** Gambar langsung di hero beranda memenuhi seluruh hero dari tepi ke tepi, tidak lagi sebatas strip 16:9.
- **HP: hemat kuota.** Pratinjau langsung tidak diputar otomatis di jaringan seluler; bisa dinyalakan di Pengaturan ("Pratinjau langsung di data seluler").

## 3.8.1

**Tahoe TV v3.8.1 (Stable)** · perbaikan cepat untuk aplikasi HP, ditambah Handoff (lanjutkan tontonan di perangkat lain).

### Perbaikan
- **HP: crash setelah data aplikasi direset.** Di langkah terakhir onboarding, aplikasi langsung tertutup sendiri pada instalasi baru karena daftar saluran masih kosong. Sekarang layar itu menunggu saluran termuat.
- **HP:** dialog pembaruan menampilkan catatan rilis sebagai teks biasa, tidak lagi dengan tanda Markdown mentah.
- **HP:** baris Akun selalu tampil, sehingga build tanpa alamat server tertanam tetap bisa mengisi alamatnya.

### Baru
- **Lanjutkan di perangkat lain (Handoff):** saat masuk ke akun Tahoe, TV menawarkan "Lanjutkan: <saluran>" untuk apa yang tadi ditonton di HP, dan HP menawarkan kebalikannya. Ada sakelar untuk mematikannya. Data tersimpan terenkripsi di server dan kedaluwarsa setelah 6 jam.
- **TV:** Pengaturan → Network & phone menampilkan daftar HP yang terhubung; OK pada satu HP langsung mencabut aksesnya.
- **Desktop:** Cmd/Ctrl+, membuka Pengaturan dan Cmd/Ctrl+F menuju pencarian saluran.
- **Pengemasan:** penandatanganan macOS dan Windows aktif otomatis begitu secret tersedia, ditambah manifest Homebrew dan winget (`packaging/`).

## 3.8.0

**Tahoe TV v3.8.0 (Stable) · Reaching The New Era of Tahoe TV v4** · rilis besar: Tahoe Live (Media Center) yang imersif, tunnel bawaan agar semua perangkat selalu sinkron, akun Tahoe pribadi, cache dan transisi yang jauh lebih mulus, layar siaran tidak tersedia yang baru, serta pembaruan yang lebih aman, di Android TV, HP, dan desktop.

### Baru & Diperbarui
- **Tahoe Live (Media Center):** beranda imersif di dalam launcher dengan baris saluran, panduan langsung, jam besar, sapaan beranimasi, dan pemutar layar penuh. Beranda Tahoe yang lama tetap ada; layar splash beranimasi muncul saat TV dinyalakan.
- **Tunnel bawaan:** HP, TV, dan desktop tetap tersinkron walau di luar Wi-Fi rumah, lewat relay terenkripsi ujung ke ujung (AES-256-GCM, relay hanya melihat data acak). Aktif secara bawaan, bisa dimatikan di Pengaturan.
- **Akun Tahoe (opsional, server pribadi):** masuk dengan e-mail + kata sandi agar nama, foto profil, wallpaper, dan pilihan tampilan (format jam, detik, ukuran jam, sapaan, tema, ukuran teks, kurangi gerakan) ikut di semua perangkat; perubahan dari TV juga naik ke akun. Kata sandi di-hash scrypt, sesi bertoken ber-hash, percobaan login dibatasi, HTTPS wajib, dan profil serta foto dienkripsi AES-256-GCM di server. Pendaftaran tertutup kecuali ada kode undangan. Lihat `docs/ACCOUNTS.md`.
- **Layar siaran tidak tersedia:** logo Tahoe TV, pesan dalam bahasa pengguna saja (Inggris, Indonesia, Mandarin, Arab) dengan redaksi yang berganti tiap percobaan, alasan gangguan, ajakan mengikuti saluran WhatsApp, dan nada khas Tahoe yang panjang dan lembut (berhenti begitu gambar kembali).
- **Cache dan transisi:** cache disk untuk daftar dan gambar, transisi antarhalaman yang mulus, dan back-buffer anti-buffering tanpa menurunkan kualitas siaran.
- **Pembaruan lebih aman:** layar "Apa yang baru" setelah update, chip pembaruan di beranda, dan APK dicocokkan dengan sertifikat tanda tangan aplikasi sebelum dipasang.
- **Data nyata, bukan tiruan:** riwayat tontonan, kontributor, jumlah bintang, angka katalog di website, dan layar lain kini memakai data sebenarnya; mode demo dihapus.
- **Tampilan:** ikon vektor menggantikan emoji di semua perangkat, kontras mengikuti WCAG, menu samping desktop kini berlabel, Pusat Kontrol TV disederhanakan, dan jadwal, pengaturan, serta layar aplikasi dirapikan.
- **Adzan** diputar pada volume 60 %. Tidak ada lagi siaran atau suara langsung selama onboarding dan pemilihan profil.
- **Ikuti saluran TahoeTV OFFICIAL di WhatsApp:** https://whatsapp.com/channel/0029VbE4YqRBvvsozUIyJ42n
- **Repositori kini bernama `fk0u/tahoe-tv`;** tautan lama tetap diteruskan oleh GitHub.

### Server
- Rute baru `/api/account/*`, `/api/tunnel/*`, dan `/api/public/catalog`; paket deploy di `server/deploy/`.

## 3.7.0

**Tahoe TV v3.7.0 (Stable)** · rilis stabil resmi dengan arsitektur Hybrid Provider yang mengombinasikan server IPTV OTT Semar (`https://plsemar.pages.dev`) dan server siaran bawaan (`val DEFAULT`: iptv-org, dhanytv, windozalmi, yositapjani), sistem penentuan saluran paling stabil & minim buffering otomatis, serta integrasi penuh panduan acara EPG Indonesia terbaru di seluruh platform (Android HP, Android TV, macOS Intel/Apple Silicon, Windows, Linux).

### Baru & Diperbarui
- **Kombinasi Sumber Siaran Hybrid (OTT Semar + Built-in DEFAULT):**
  - Menggabungkan daftar saluran dari server IPTV OTT Semar (`https://plsemar.pages.dev`) dan kumpulan server siaran bawaan (`Sources.DEFAULT`) secara otomatis (*out-of-the-box*).
  - Menyajikan katalog saluran yang jauh lebih lengkap mencakup siaran nasional, berita, olahraga, hiburan, religi, hingga saluran lokal/daerah tanpa ada yang terlewat.
- **Seleksi Cerdas Saluran Paling Stabil & Minim Buffering:**
  - Ketika sebuah saluran tersedia di lebih dari satu server (duplikat antar-sumber), sistem secara cerdas mengevaluasi performa jaringan, latensi, responsivitas CDN edge, dan metrik stabilitas siaran (`StreamSelector` & `SourceHealth`).
  - Stream yang paling stabil, berlatensi rendah, dan minim buffering otomatis ditetapkan sebagai saluran utama (*primary stream*), sedangkan stream lainnya disimpan sebagai cadangan siaga (*alternates*).
  - Peralihan otomatis (*seamless failover*) langsung aktif jika stream utama mengalami gangguan.
- **Server EPG Baru Indonesia Prioritas Utama:**
  - Sumber panduan acara XMLTV `https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml` aktif sebagai prioritas nomor 1 untuk seluruh platform, menghadirkan jadwal siaran real-time yang akurat.

## 3.6.0-beta.4

**Tahoe TV v3.6.0-beta.4 (Beta-Dev)** · penambahan server panduan acara XMLTV EPG baru (`https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml`) sebagai sumber EPG utama (#1 prioritas) secara bawaan (*out-of-the-box*) untuk seluruh platform (Android HP, Android TV, macOS Intel/Apple Silicon, Windows, Linux).

### Baru & Diperbarui
- **Server EPG Indonesia Baru sebagai Prioritas Utama:**
  - Menambahkan repositori EPG `https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml` ke dalam daftar prioritas utama (`Sources.EPG` indeks 0 dan `OttEngine.DEFAULT_PRIMARY_EPG_URL`).
  - Jadwal siaran TV nasional dan lokal Indonesia kini lebih lengkap, akurat, dan terbarui secara otomatis di Beranda, TV Live, dan Mini Player tanpa memerlukan konfigurasi manual dari pengguna.
- **Integrasi Penuh Multi-Platform:**
  - Android TV (`:launcher`), Android Mobile & Tablet (`:companion`), serta Desktop macOS/Windows/Linux (`:desktop`) secara otomatis memprioritaskan server EPG baru ini dengan fallback ke EPG sekunder (DhanyTV & iptv-org EPG) bila terjadi kendala jaringan.
  - Server IPTV lokal (`tools/iptv_server.py`) juga diperbarui untuk menyajikan EPG baru ini secara default.

## 3.6.0-beta.3

**Tahoe TV v3.6.0-beta.3 (Beta-Dev)** · server IPTV OTT Semar (`https://plsemar.pages.dev`) kini aktif secara bawaan (*out-of-the-box*) sebagai penyedia siaran utama di seluruh platform (Android HP, Android TV, macOS, Windows, Linux). Pengguna tidak perlu lagi melakukan pengaturan URL secara manual. Jika server OTT sedang tidak dapat diakses, sistem otomatis beralih ke server siaran cadangan bawaan (*built-in*).

### Baru & Diperbarui
- **Server IPTV OTT Semar Aktif Bawaan (Default Primary Provider):**
  - Seluruh aplikasi di HP, Android TV, dan Desktop kini langsung menggunakan server `https://plsemar.pages.dev` secara bawaan tanpa perlu memasukkan URL atau menyetel konfigurasi awal.
  - Pengguna langsung disajikan 1.100+ siaran langsung berkualitas tinggi dan panduan acara XMLTV EPG secara otomatis saat pertama kali membuka aplikasi.
- **Failover Otomatis Tanpa Gangguan:**
  - Bila server OTT utama tidak dapat diakses, offline, atau mengalami gangguan jaringan, sistem secara otomatis (*seamlessly*) memutar saluran dari server cadangan bawaan (*built-in* seperti iptv-org, dhanytv, windozalmi, dll.) tanpa layar hitam atau penghentian pemutaran.
- **Opsi Kustom Tetap Tersedia:**
  - Pengguna tingkat lanjut tetap dapat memasukkan URL playlist atau server OTT kustom mereka sendiri melalui menu Pengaturan jika diinginkan untuk menggantikan server utama bawaan.

## 3.6.0-beta.2

**Tahoe TV v3.6.0-beta.2 (Beta-Dev)** · perbaikan dan penyempurnaan menyeluruh mesin IPTV OTT Engine (`OttEngine`) multi-format dan multi-platform (Android HP, Android TV, macOS Intel, Windows, Linux) dengan negosiasi User-Agent bertingkat, parser OTT JSON base64, dukungan manifest DASH/headers untuk server `https://plsemar.pages.dev`, serta penambahan kontrol playlist kustom pada aplikasi HP.

### Diperbaiki & Ditingkatkan
- **Dukungan Server IPTV OTT Multi-Format & Negosiasi User-Agent Bertingkat:**
  - **Dukungan Penuh Server OTT (`https://plsemar.pages.dev`):** Mengatasi kegagalan pemuatan playlist di macOS Intel dan ponsel Android yang sebelumnya terkena redirect HTTP 302 ke halaman landing HTML ("Akses Channel lewat OTT Navigator yak").
  - **Negosiasi User-Agent Otomatis:** `OttEngine` kini secara dinamis menegosiasikan header User-Agent bertingkat (`TiviMate/4.7.0`, `OTT Navigator/1.7.5.1`, dan custom UA). Server yang mendeteksi TiviMate akan menyajikan 1.100+ baris playlist `#EXTM3U` lengkap, sedangkan server yang merespons dengan JSON OTT Navigator akan diparsing secara transparan.
  - **Parser OTT Navigator JSON (`M3u.parseOttJson`):** Mendukung parsing struktur JSON khusus OTT Navigator yang menggunakan encoding Base64 (`categories`, `channels`, `provider` dengan atribut `name`, `url`, `icon`, `user_agent`, `referrer`, dan `source_format`).
  - **Injeksi Header & DASH Manifest:** Saluran nasional seperti ANTV (`.mpd` DASH), GTV (`rplus.inisemar.my.id`), dan MNCTV kini otomatis mendapatkan header yang diperlukan (`http-user-agent=android-R+`, `http-referrer=https://www.rctiplus.com/`) sehingga pemutaran video berjalan lancar.
- **Pengaturan Server OTT / Playlist Kustom di Android HP (`:companion`):**
  - Menambahkan menu dan dialog interaktif **Playlist / Server OTT kustom** di Pengaturan › Saluran & wilayah pada aplikasi ponsel.
  - Memungkinkan pengguna memasukkan URL playlist M3U atau server OTT langsung di ponsel, otomatis tersinkronisasi ke `OttEngine` sebagai server siaran utama, dan memuat ulang saluran secara instan.
- **Failover Otomatis & Pembebasan Saluran Terkonfigurasi:**
  - Saluran dari server OTT utama yang dikonfigurasi pengguna tidak lagi disaring oleh aturan filter domain restream bawaan (`pages.dev`, `inisemar`, dll.).
  - Jika server OTT utama offline atau bermasalah, loader dan pemutar otomatis beralih ke saluran built-in cadangan (*graceful fallback*).
- **Desktop (macOS Intel, Windows, Linux):**
  - Sinkronisasi otomatis konfigurasi playlist dengan `OttEngine.setPrimaryServer` pada saat inisialisasi maupun saat setelan playlist diubah pengguna.

## 3.6.0-beta.1

**Tahoe TV v3.6.0-beta.1 (Beta-Dev)** · rilis beta pengembang Redesign 4.0 & Sistem IPTV OTT Engine (`OttEngine`) dengan prioritas server utama dan failover otomatis ke server cadangan. Belum untuk pemakaian sehari-hari; laporkan masalah lewat Profil → Laporkan masalah.

### Baru
- **Sistem IPTV OTT Provider & Failover Otomatis (`OttEngine`).** Dukungan server IPTV/OTT eksternal (termasuk server berorientasi OTT Navigator dan header spesifik) sebagai server siaran utama dengan mekanisme failover cerdas:
  - **Prioritas Server Utama & Auto-Fallback:** Aplikasi secara otomatis memprioritaskan server OTT utama yang dikonfigurasi. Bila server utama sedang tidak dapat diakses, offline, atau kosong, sistem secara mulus beralih (*fallback*) ke saluran cadangan bawaan (*built-in*) tanpa menghentikan pengalaman menonton.
  - **Stream-Level Failover:** Jika stream siaran dari server OTT utama mengalami gangguan jaringan atau galat pemutaran (403, 404, atau terputus), pemutar di HP, TV, dan Desktop otomatis mencari dan beralih ke stream cadangan yang cocok dari pool fallback.
  - **Parser Direktif `#EXTM3U` & Header OTT:** Mendukung ekstraksi otomatis panduan acara EPG XMLTV (`url-tvg`, `x-tvg-url`), penanganan `catchup`/`catchup-source`, dan injeksi header per-provider (`User-Agent: OTTNavigator/...`, Referer) pada seluruh permintaan segmen video.
- **TV: pratinjau Beranda yang selalu menemukan gambar.** Pratinjau mencoba semua sumber saluran (utama, cadangan, lalu saluran berikutnya) bila satu gagal atau diam 7 detik, mulai dalam 0,35 detik, menjaga rasio asli gambar (muat di bingkai, tidak ter-crop atau melar), dan kini juga jalan di TV stick 1 GB pada 360p (sebelumnya mati). Beranda memuat daftar saluran di latar belakang, jadi pratinjau muncul sebelum Live TV pernah dibuka. Di sheet saluran, Rekam dan Favorit menjadi tombol ikon bulat; namanya tampil di bawah saat difokus. Diuji di Mi TV Stick asli (RAM 1 GB).
- **Beranda lebih hidup: pratinjau cepat, rasio benar, gambar nyata.** Pratinjau saluran kini tampil di bingkai 16:9 (tidak lagi ter-crop atau melar), mencoba semua sumber saluran termasuk DASH/DRM sebelum menyerah, mulai dalam 0,25 detik, dan hero menunggu gambarnya siap (dulu hero berganti tiap 4 detik dan membatalkan pratinjau sebelum sempat tampil). Pratinjau berhenti saat digulir dan saat hero di luar layar. Gambar: cache disk untuk logo dan foto, logo dari playlist lalu Wikipedia/web (hanya halaman yang judulnya cocok), dan satu frame nyata per saluran disimpan dari pratinjau lalu dipakai di kartu, poster, dan ubin kategori Jelajahi.
- **Buffering mendadak berkurang.** Pemeriksaan kesehatan stream untuk semua saluran kini menunggu selama ada video yang diputar atau dipratinjau, dan berjalan di prioritas terendah.
- **Hubungkan ke TV dengan radar hidup.** Riak, sapuan radar, debu melayang, ponsel yang "bernapas", TV muncul dengan pegas, dan denyut cahaya di sepanjang sinar ke tiap TV; diam bila "Kurangi gerakan" aktif. Judul baris yang terpotong vertikal diperbaiki.
- **Desktop minimalis.** Tombol sekunder di Live TV, Rekaman, Pasang TV, dan onboarding menjadi ikon bulat dengan tooltip saat kursor diam.
- **Redesign 4.0 · HP lebih minimalis dan mudah diakses.** Tombol teks sekunder diganti tombol ikon bulat 48 dp (Favorit, Info, Bagikan, Pindai QR, Masukkan PIN, Timer tidur, Numpad, Kirim log, PIN anak, Daftar TV, Pengaturan TV, Ganti wilayah, Tentang, Hapus rekap, dan langkah Pasang TV). Aksi utama tiap layar tetap berlabel (Tonton, Lanjut, Coba lagi, Bagikan Kartu Story). Setiap ikon punya nama untuk TalkBack, status (aktif/mati), dan tekan lama menampilkan namanya, jadi ikon tidak membingungkan. Diuji di HP asli dengan build rilis dan data nyata.
- **HP (`:companion`) · Live preview, transisi, Now Playing, & aksesibilitas lengkap (#179).**
  - **Pratinjau langsung (`MobileLivePreview` & `LivePreviewSurface`):** saluran di Hero Beranda dan Detail Saluran memutar pratinjau bisu (maks. 480p, jeda diam 700 ms, timeout 8 detik) dengan tombol nyalakan/matikan suara dan sakelar **Pratinjau langsung** di Pengaturan; otomatis nonaktif pada mode demo, offline, DRM/DASH, Kurangi gerakan, PiP, dan saat pemutaran penuh aktif.
  - **Transisi gerak Redesign 4.0:** seluruh perpindahan tab, halaman dorong (sadar RTL), lembar detail saluran, langkah onboarding (`WelcomeScreen`), accordion FAQ (`HelpCenterPage`), `MiniPlayer`, dan `PushBannerBar` mengikuti kurva `EASE_OUT`/`EASE_DRAWER` dan durasi `Brand.MOTION_*`, serta otomatis beralih ke fade saat **Kurangi gerakan** aktif.
  - **Now Playing & scrubbar interaktif (`LiveSeek`):** `MiniPlayer` dengan progres acara real-time dan indikator status (berputar/jeda/memuat), `NowPlayingInfo` dan daftar saluran/Watchlist menandai saluran yang sedang diputar, serta scrubbar tekan-geser di `PlayerStage` dengan lencana `-mm:ss` dan pil **Kembali ke LIVE**.
  - **Aksesibilitas menyeluruh:** heading TalkBack (`a11yHeading`), penggabungan semantik baris (`mergeDescendants`), status & `liveRegion` sopan, target sentuh minimum 48 dp, perlindungan arah `KeepLtr` pada D-Pad/scrubbar/progres untuk bahasa Arab (RTL), dan garis tepi 2 dp pada mode Kontras Tinggi.
- **TV (`:launcher`) · Pratinjau langsung di Hero Beranda & gerak/fokus Redesign 4.0 (#181).** Hero Beranda kini memutar pratinjau langsung (Java Views murni, memakai `LivePreview.java`) saat ubin Live TV difokus, lengkap dengan cincin fokus (`Ui.focusRing`), kurva `Ui.out()`/`Ui.drawer()` sadar `Ui.dur()`, pelokalan layar pilih profil (`Lock.Profiles`), dan pengumuman TalkBack/D-Pad.
- **Desktop (`:desktop`) · Pintasan keyboard, pratinjau Beranda, & Multi-View (#182).** Tambahan pintasan `Ctrl/Cmd+1..6` untuk pindah halaman, pratinjau pilihan saluran di Beranda (klik sekali pilih, klik lagi/dua kali tonton), batas fokus kontras tinggi 2 dp, serta bingkai cyan `#5CD0FF` dan tombol angka `1`–`4` untuk fokus audio di Multi-View 2×2.
- **Website (`website/`) · Penyelarasan landing Figma (#183).** Hero rata tengah, showcase 7 tile bergelombang, bagian **Saluran dari 200+ negara** dengan 4 kartu kategori, serta 3 kartu unduh (TV, HP, Desktop gabungan) dengan terjemahan lengkap EN/ID/ZH/AR.

## 3.5.0-beta.1

**Tahoe TV v3.5.0-beta.1 (Beta)** · rilis beta pengembang Redesign 4.0 (seluruh layar HP selaras dengan Figma, terverifikasi di perangkat fisik). Belum untuk pemakaian sehari-hari; laporkan masalah lewat Profil → Laporkan masalah.

### Baru
- **Redesign 4.0 · 18 layar HP lanjutan mengikuti Figma (#179).**
  - **Onboarding 3 langkah cepat:** Bahasa → Wilayah (negara & kota beserta lokasi saat ini) → Siap (mozaik saluran miring + tanda konfirmasi), lengkap dengan indikator progres pil dan tombol kembali melingkar.
  - **Halaman baru di HP:** Cari saluran & acara (`SearchPage`), Hubungkan ke TV dengan radar Wi-Fi & QR/PIN (`ConnectTvPage`), Laporkan masalah beserta pilihan jenis gangguan & kirim log (`ReportPage`), Pusat bantuan dengan pencarian & FAQ (`HelpCenterPage`), Kontributor (`ContributorsPage`), Rekap bulanan (`RecapPage`), serta pembaruan Detail saluran (`ChannelScreen`).
  - **Penyempurnaan layar HP lain:** Pengaturan baru (pencarian setelan, kartu Disarankan, 4 kelompok setelan dengan dialog pemilih & ikon disk 40 dp), Riwayat tontonan (filter HP/TV, Hari ini/Kemarin, progres di thumbnail, hapus riwayat), Notifikasi (data demo otomatis), Keadaan offline dengan pintasan Watchlist, pemutar potret/lanskap (`PlayerStage` 5 tombol transport & pratinjau usap, strip saluran lain di `NowPlayingInfo`), `MiniPlayer` baru, Beranda tema terang (`Theme.Mode.LIGHT`), serta dukungan mode demo bahasa Arab (`ar`).

### Perbaikan
- Menghapus blok setelan bahasa saluran, tampilan, dan pembaruan yang terduplikasi di `:desktop` (`SettingsScreen`).
- Menyelaraskan tipografi hero, efek kartu kaca landing, dan versi awal di `website/` dengan token Redesign 4.0.
- Memastikan tampilan offline (`OfflineState`) di Live TV HP tidak lagi tertutup oleh rak pilihan dan kategori.

## 3.5.0-alpha.12

**v3.500.12 Alpha Dev** · rilis uji (prerelease) redesign 4.0. Belum untuk pemakaian sehari-hari; laporkan masalah lewat Profil → Laporkan masalah.

### Baru
- **Redesign 4.0 · HP mengikuti Figma (#179).** Beranda, Live TV, Remote, Watchlist, dan Profil kini sama dengan layar Figma: latar `#0D0F14` tanpa rona, chip/tombol/ikon sesuai komponen Figma, hero 520 dp dengan "Mau nonton apa?" dan label LIVE, kartu saluran 280 × 158 dengan progres acara, poster "Pilihan untukmu" dan grid Kategori di Live TV, kartu unggulan dan daftar ber-thumbnail di Watchlist, serta panel Remote baru (kartu TV, OK cyan, tombol berlabel, pil VOL/CH, mikrofon, dan kartu "TV di jaringan lain?" dengan Scan QR / Masukkan PIN).
- **Redesign 4.0 · token brand & gerak (#178).** Teks sekunder 80% dan tersier 62% (lebih terbaca untuk lansia), garis tepi lebih tegas, cyan diperbarui, aksen hangat jingga (`Brand.WARM`) dan cincin fokus TV, serta konstanta gerak (`MOTION_*`, kurva `EASE_OUT`/`EASE_IN_OUT`/`EASE_DRAWER`, skala tekan 0,97). `tokens.json` dan CSS situs ikut disinkronkan.
- **Redesign 4.0 · HP (#179, bagian 1).** Dock mengambang baru: Beranda · Live TV · **Remote bulat menonjol** · Watchlist · Profil. Tab **Watchlist** (saluran tersimpan + acara yang sedang tayang) dan **Profil** (kartu profil, total tonton bulan ini → rekap, Riwayat tontonan, Hubungkan ke TV, Pasang Tahoe ke TV, Jelajahi kategori, Laporkan masalah, Pusat bantuan, GitHub, Kontributor, Pengaturan). **Pengaturan** pindah dari Beranda ke halaman sendiri. Hero Beranda bergeser otomatis tiap 6 detik (mati saat "Kurangi gerakan"), dan halaman masuk dengan transisi geser.
- **Redesign 4.0 · HP (#179, bagian 2).** **Notifikasi** kini halaman sendiri (dikelompokkan Hari ini / Sebelumnya, ikon per jenis, tombol Periksa pembaruan). Saat saluran gagal dimuat, Beranda dan Live TV menampilkan **keadaan offline** dengan tombol Coba lagi, bukan sekadar catatan satu baris.
- **Redesign 4.0 · Desktop (#182).** Di Live TV (Daftar & Grid), **klik sekali** memilih saluran dan menampilkannya di panel detail sebagai pratinjau; **klik lagi atau klik dua kali** membuka siaran. Pratinjau tidak lagi berganti hanya karena kursor lewat (hover).
- **Redesign 4.0 · HP (#179, bagian 3).** **Onboarding 3 langkah**: Bahasa → Wilayah (negara lalu kota) → Siap, dengan indikator pil, ringkasan "Saluran dari … siap ditonton", dan sakelar jadwal shalat langsung di langkah terakhir. Zona waktu, metode shalat, negara tambahan, kategori, favorit, dan urutan tetap ada di balik tombol **Atur saluran & jadwal shalat**. **Rekap** memakai kartu bergradasi danau → cyan → jingga dengan total waktu tonton besar (gambar Story ikut).
- **Redesign 4.0 · TV (#181, bagian 1).** **Pratinjau langsung** di Jadwal TV: saluran yang difokus diputar pelan (maks. 480p, buffer pendek) di panel detail setelah fokus diam 0,7 detik; mati otomatis di perangkat LIGHT, untuk saluran DRM/DASH, dan bisa dimatikan di Pusat Kontrol. Satu pemutar saja: berhenti saat membuka Live TV layar penuh atau keluar layar. **Cincin fokus** putih 3 dp (kuning saat kontras tinggi) di baris jadwal. Pengaturan **Ukuran teks** Normal / Besar / Sangat besar di Pusat Kontrol.
- **Redesign 4.0 · TV (#181, bagian 2).** **Detail saluran** kini sheet kaca dari sisi kanan (logo besar, acara sekarang, deskripsi, berikutnya) dengan tombol besar **Tonton / Rekam / Favorit** bercincin fokus; Back menutup sheet. **Cari saluran** di Jadwal TV memakai keyboard TV dan menyaring daftar saat mengetik. Teks kartu Pusat Kontrol lebih besar, dan animasi TV memakai kurva ease-out/drawer redesign 4.0.
- **Redesign 4.0 · Remote multi-TV tanpa kode (#180).** HP di Wi-Fi yang sama cukup mengetuk TV yang ditemukan: TV menampilkan "Izinkan … mengontrol TV ini?" dan penonton menekan **OK sekali**, lalu HP langsung terhubung, tanpa mengetik kode. Permintaan hanya diterima dari alamat jaringan lokal, kedaluwarsa dalam 60 detik, satu per HP, maksimal tiga sekaligus, dan token hanya diberikan sekali. Kalau TV menolak atau tidak menjawab, HP kembali ke kode/QR. Di layar Remote ada **pemilih TV**: TV tersimpan berganti dengan satu ketukan, TV baru di Wi-Fi bisa ditambah dari situ.
- **Redesign 4.0 · Website (#183).** Landing baru: hero dengan panggung TV + HP di atas cahaya cyan/jingga, carousel tangkapan layar dengan tombol sebelumnya/berikutnya, kartu unduh berwarna per perangkat, kartu saluran lebih besar, dan footer empat kolom. Bahasa 中文 dan العربية kini menerjemahkan seluruh halaman (tidak lagi kembali ke Inggris), dan tampilan Arab tetap dicerminkan (RTL).
- **Web player `/watch` (#183).** Tonton saluran HLS publik langsung di browser: panggung video 16:9 dengan label LIVE, tombol saluran sebelumnya/berikutnya, daftar saluran dengan pencarian dan kategori, tombol ↑/↓, M (bisu), F (layar penuh), serta tautan langsung `?ch=`. Saluran DRM/DASH tetap tampil dengan tanda "app" dan arahan ke aplikasi. Server hanya mengirim alamat siaran untuk HLS https tanpa DRM yang tidak diblokir admin. Kartu saluran di landing kini langsung membuka player.

## 3.4.1

### Baru
- **Bahasa saluran tambahan di HP (#71):** pilih bahasa di Beranda → kartu *Bahasa saluran tambahan*; saluran dimuat ulang dan pilihan ikut tersinkron ke TV.
- **Tata letak 2 panel di tablet (#41):** pada layar ≥ 840 dp, detail saluran terbuka sebagai panel samping di sebelah daftar.
- **Rekam dari Detail saluran TV (#41):** tombol *Rekam* di dialog detail panduan membuka Live TV dan mulai merekam lokal (maks. 1 jam).
- **Bahasa saluran tambahan di TV (#71):** Pusat Kontrol → *Bahasa saluran tambahan* (pilih dengan remote; tersimpan di `Viewer`, sama seperti HP/Desktop).
- **Terjemahan Arab lengkap:** seluruh 936 string aplikasi (HP, TV, desktop) kini tersedia dalam bahasa Arab, termasuk menu TV, cuaca, jadwal shalat, asisten, dan pembaruan.
- **Snapshot Desktop:** `:desktop:snapshots` kini juga merender Beranda & Setelan dalam tema Terang dan tata letak RTL (`*-light.png`, `*-rtl.png`).

### Perbaikan
- Pesan "DRM/DASH tidak mendukung rekam" di TV kini mengikuti bahasa aplikasi.

## 3.4.0

### Baru
- **Bahasa Arab & kesiapan RTL, paket saluran per bahasa, dan alur terjemahan komunitas (#71).**
  - **Core (`:core`)**: `Region.isRtl`/`I18n.isRtl`, `"ar"` di `Region.LANGUAGES` (onboarding Arab lengkap; string aplikasi dari `resources/i18n/ar.json` dengan fallback Inggris per kunci), `Region.languagePlaylist` (ISO 639-1 → 639-3, `iptv-org/languages/*.m3u`), `Viewer.channelLanguages`, dan `Sources.loadChannels`.
  - **Aplikasi**: `android:supportsRtl` di HP & TV; Compose HP/Desktop mengikuti arah bahasa (video & seekbar tetap LTR); TV memakai `setLayoutDirection`, margin *start/end*, dan chevron *auto-mirrored*; situs web mengatur `dir="rtl"`.
  - **Dokumentasi**: [docs/TRANSLATING.md](docs/TRANSLATING.md); `I18nTest` memeriksa semua bahasa (kunci, teks kosong, placeholder).
- **Tema Gelap/Terang/Ikuti sistem, Kontras tinggi, & Kurangi gerakan (#41).**
  - `Brand` + `tokens.json` punya palet Light & High Contrast; `Theme` di `:core` (dengan uji kontras WCAG). HP & Desktop memakai tema reaktif (pemutar tetap gelap), bilah sistem HP mengikuti tema, dan `ANIMATOR_DURATION_SCALE = 0` dihormati.
  - **HP**: `NavigationRail` di layar ≥ 600 dp, usap kiri/kanan untuk ganti saluran (di luar tepi gestur kembali), cubit untuk Pas ↔ Zoom, target sentuh 48 dp, dan string hardcoded dipindah ke `I18n`.
  - **TV**: toggle Kontras tinggi, Kurangi gerakan, dan Suara klik remote di Pusat Kontrol; dialog **Detail saluran** di panduan (tahan OK / tombol Info: Tonton, Tambah/Hapus favorit).
- **Control Plane terpusat, Notifikasi Push Real-Time, & Pop-up Modal Update (`server/`, `website/`, `:core`, `:launcher`, `:companion`, `:desktop`).**
  - **Server Control Plane & Situs Web (`https://tahoe.kousozo.tech`)**: Menambahkan backend Node.js/SQLite (`server/`) yang melayani statistik instalasi anonim, stream **Push Notification** real-time via Server-Sent Events (`/api/push/stream` & polling `/api/push/poll`), kebijakan **Pop-up Modal Pembaruan** (`/api/config`), serta pembaruan situs web di `website/`.
  - **Pop-up Modal Update & Inbox Notifikasi di Semua Aplikasi**: Menambahkan modal kaca pembaruan otomatis saat ada rilis baru (terverifikasi SHA-256 sebelum pemasangan) serta ikon lonceng & riwayat notifikasi di TV (`:launcher`), HP (`:companion`), dan Desktop (`:desktop` dengan notifikasi sistem macOS/Tray).
- **Pemisahan tegas aplikasi TV vs HP/Tablet & penamaan artefak rilis bahasa awam.**
  - Memisahkan peran dan penamaan artefak rilis APK secara tegas: `TahoeTV-Android-TV.apk` (**Tahoe TV Launcher** — khusus Android TV & Google TV dengan penjagaan runtime `TvGuard`) dan `TahoeTV-Phone-and-Tablet.apk` (**Tahoe TV** — khusus HP & tablet Android untuk setup launcher TV via Wi-Fi, remote TV, serta Live TV potret/PiP), sembari mempertahankan fallback kompatibilitas aset lama di alur pembaruan.
- **Katalog kota global (`CountryCatalog`), popularitas saluran (`PopularityScraper`), filter wilayah, & lokalisasi penuh.**
  - Menambahkan katalog negara/zona waktu/kata kunci lokal (`CountryCatalog`), penyusunan saluran berdasarkan negara prioritas (negara pilihan dimuat lebih dulu), opsi urut **Popularitas**, **A–Z**, dan **Z–A** (`LocalTv.SortMode`), serta lokalisasi menyeluruh termasuk salam beranda dan dialog sistem lintas EN, ID, dan 中文.
- **Kontrol Subtitle (CC) & Track Audio, serta UI Sentuh Potret Penuh untuk HP.**
  - Menambahkan tombol & pintasan `C`/`A` untuk mengganti subtitle (`C.TRACK_TYPE_TEXT` + `Player.Listener.onCues` di launcher) dan bahasa audio (`C.TRACK_TYPE_AUDIO`) di Android TV (`:launcher`), HP (`:companion`), dan web player (`/watch` di `:desktop`).
  - Memperbaiki dukungan layar sentuh potret penuh pada pemilih profil (`Lock.Profiles`), onboarding (`Onboarding`), beranda, dan `LiveTv` agar `Tahoe TV` nyaman dipakai langsung di HP maupun TV.
- **Seleksi server multi-tahap & *fuzzy scoring* (`StreamSelector`) lintas platform.**
  - **Core (`:core`)**: Menambahkan `StreamSelector` dengan alur 3 tahap saat membuka saluran (`rankOnOpen`): (1) verifikasi keaktifan (*liveness*) HLS & DASH, (2) inspeksi paralel latensi, resolusi maksimum, bandwidth, dan dukungan ABR, serta (3) penghitungan skor kelayakan fuzzy (`fuzzyScore`, 0–100) beserta pencocokan nama berbasis bigram (`fuzzySimilarity`). `SourceHealth` kini menyimpan metrik kualitas (`height`, `bandwidth`, `score`) dalam format TSV 7 kolom (kompatibel mundur dengan 4 kolom) dan mengurutkan server live berdasarkan skor terbaik.
  - **Desktop (`:desktop`), HP (`:companion`), & Android TV (`:launcher`)**: Mengintegrasikan `StreamSelector.rankOnOpen` secara asinkron di `PlayerWindow`, `Playback`, dan `LiveTv` agar server finalis dengan kualitas dan latensi terbaik langsung dipilih setiap kali saluran dibuka atau dicoba ulang.
- **Perluasan sumber IPTV, auto-refresh harian, & scraping logo/thumbnail acara (`MediaScraper`).**
  - **Sumber lengkap (`Sources`)**: Membuka sumber penuh `dhasap/dhanytv` (`playlist.m3u`, `ott.m3u`, `epg.xml`), `windozalmi/Playlist_IPTV_Pribadi` (`m3u-ipvt`), dan `iptv-org/index.m3u` untuk wilayah asal (`HOME`), serta menambahkan `Sources.scheduleDailyRefresh` untuk pembaruan daftar putar otomatis setiap 24 jam.
  - **Scraping media (`MediaScraper`)**: Mengambil logo saluran dan thumbnail pratinjau program yang sedang berlangsung secara otomatis dari internet.
- **Pemutaran DASH (`.mpd`) & DRM (`ClearKey`, `Widevine`, `PlayReady`) langsung di dalam aplikasi.**
  - **Core (`:core`)**: Menambahkan `Drm` untuk mendeteksi `.mpd` serta skema/kunci/lisensi DRM dari `#KODIPROP`, `#EXTVLCOPT`, `#EXTHTTP`, dan parameter URL bertanda pipa (`|`), `DashToHls` untuk konversi manifes DASH (`SegmentTemplate`/`SegmentList`/`SegmentBase`) ke HLS fMP4 atau penulisan ulang MPD, serta `CencDecrypt` untuk dekripsi segmen fMP4 `cenc` (AES-128-CTR) dan `cbcs` (AES-128-CBC) ClearKey secara langsung.
  - **Android TV (`:launcher`) & HP (`:companion`)**: Menambahkan konfigurasi `MimeTypes.APPLICATION_MPD` dan `DrmSessionManager` (`LocalMediaDrmCallback` untuk ClearKey inline serta `DefaultDrmSessionManagerProvider` untuk URL lisensi Widevine/PlayReady/ClearKey) langsung di `LiveTv` dan `Playback` (ExoPlayer Media3).
  - **Desktop (`:desktop`)**: Menambahkan konversi dan dekripsi DASH/ClearKey on-the-fly melalui `HlsProxy.wrapDash` agar siaran `.mpd` dan ClearKey diputar langsung di `PlayerWindow` tanpa memaksa keluar ke browser; memperbarui `WebPlayer`, `IptvServer`, dan `ServerRelay` (`/live/{id}.mpd` dan `/live/{id}.m3u8` untuk DASH/DRM via Shaka Player & `DashToHls`).
- **Panel info teknis (*Stats for Nerds*) lintas platform (`:core`, `:launcher`, `:companion`, `:desktop`) (#61).**
  - Menambahkan `PlayerStats` di `:core` (ringkasan statistik dengan host URL tersaring, format salin teks, dan pencatatan ke `DiagLog`), panel interaktif di Android TV (`LiveTv`, pintasan `KEYCODE_INFO` / `I` / tombol OSD), lembar statistik di HP (`Live` / `Playback` lewat badge format & tombol info), serta kartu statistik di pemutar Desktop (`PlayerWindow`, pintasan `I` / tombol info).
- **Preferensi trek audio/subtitle per saluran & ukuran subtitle (`PlayPrefs`, `:launcher`, `:companion`) (#51).**
  - Menyimpan bahasa audio dan subtitle pilihan per saluran (`PlayPrefs.channelTrackKey`) beserta ukuran teks subtitle (`Normal`, `Besar`, `Ekstra besar`) di Pusat Kontrol TV dan setelan pemutar HP.
- **Profil perangkat keras TV & optimisasi adaptif (`DeviceProfile`, `DeviceProfiler`, `:launcher`) (#38).**
  - Mendeteksi kelas perangkat keras TV (`Light`, `Standard`, `Full`) dari RAM, inti CPU, ABI, refresh rate, serta dekoder media untuk menskalakan efek/animasi `Hero`, konkurensi probe `SourceHealth`, mencatat latensi startup/zap, dan menampilkan kartu info perangkat serta override manual di Pusat Kontrol TV (`Sheets`).
- **Gerbang dewasa soal matematika (`MathGate`, `Kids`, `Sheets`) (#47).**
  - Menambahkan mode verifikasi orang dewasa berbasis soal matematika (`PIN`, `Matematika`, atau `PIN + Matematika`), pilihan tingkat kesulitan (`Mudah`, `Sedang`, `Sulit`), panel `Sheets.MathPad`, dan opsi cadangan soal matematika saat lupa PIN.
- **Notifikasi & pengingat acara TV → HP (`Companion`, `Reminder`, `Sync`) (#52).**
  - Mengirim peringatan pengingat acara dari TV ke HP melalui WebSocket (`tv_notif` dengan replay 10 menit untuk klien baru tersambung) dan menampilkannya melalui kanal notifikasi Android khusus pengingat & status pemutaran.
- **Profil Anak lanjutan: batas waktu harian, jadwal tonton, saluran khusus, & laporan (`KidsPolicy`, `:core`, `:launcher`, `:companion`) (#54).**
  - Menambahkan kebijakan lengkap Profil Anak (`KidsPolicy`): batas durasi menonton harian dengan banner peringatan 5 menit sebelum habis, perpanjangan +30 menit oleh orang dewasa, jendela jadwal jam boleh menonton, daftar saluran tambahan & blokir khusus Anak, pemantauan pemakaian app pihak ketiga saat mode Anak aktif, serta ringkasan statistik harian/mingguan lewat endpoint `/kids/report`.
- **Pusat Pengaturan TV Lengkap dari HP & API setelan TV (`TvSettings`, `:core`, `:launcher`, `:companion`) (#35).**
  - Menambahkan endpoint `GET/POST /settings`, `POST /viewer`, dan `GET /kids/report` di `:launcher` dengan penyimpanan kunci API tulis-saja (`write-only`), serta layar `TvSettingsSheet` 4 tab di aplikasi HP (`Tampilan`, `Dock`, `Anak`, dan `Perangkat & Kunci`) untuk mengatur wallpaper, rotasi latar, Mode Sederhana, skala huruf, bahasa, kota, slot dock/aplikasi tersembunyi, profil perangkat keras TV, dan kebijakan Anak dari jarak jauh.
- **Setup Awal TV Tanpa HP (`/setup`) & Pemindai QR Di Dalam Aplikasi (`QrScanner`, `Onboarding`, `:launcher`, `:companion`) (#36).**
  - Menambahkan wizard web `/setup` di `:launcher` yang dapat diakses langsung lewat kode QR dari langkah bahasa onboarding TV (tanpa perlu memasang aplikasi HP), serta pemindai QR berbasis Camera2 + ZXing (`QrCodeScannerDialog` + fallback tempel tautan) langsung di aplikasi HP dan parser payload QR terstruktur (`TvLink.parseQrPayload`).
- **Desktop Imersif: Mini-player, Ketik Angka Saluran, Pintasan Keyboard, Timeline EPG, Laci Saluran, & Grid (`:desktop`) (#49, #41).**
  - Menambahkan mode **Mini-player** mengambang selalu di atas (`P` / tombol mini-player) lengkap dengan drag posisi, lompat saluran cepat via angka keyboard `0–9`, pintasan keyboard di daftar saluran (`/` cari, `↑`/`↓`, `Enter`), mode tampilan **Daftar** / **Grid** / **Timeline EPG 5 jam** dengan indikator waktu berjalan, pengecekan kesehatan saluran periodik tiap 5 menit, bilah navigasi kiri yang bisa diciutkan, serta kartu **Lanjut tonton** dan baris **Sedang ramai** di Beranda (`Home`).
  - Menambahkan laci saluran & EPG terintegrasi `[L]` / `☰` dan tombol bisu `[M]` di jendela pemutar (`PlayerWindow`) serta pratinjau hover langsung (`LIVE hover preview`) di halaman Siaran Langsung (`Live`).
- **Wallpaper Foto Gratis API & Timer Tidur (`WallpaperProvider`, `:core`, `:launcher`, `:companion`) (#39, #35).**
  - Menambahkan sumber wallpaper foto gratis dari Wikimedia Commons Picture of the Day (tanpa API key) serta dukungan opsional Unsplash dan Pexels (kunci tulis-saja di `TvSettingsSheet` & `/setup`), pilihan tema (`Alam`, `Kota & Malam`, `Indonesia`, `Minimalis Gelap`, `Aman Anak`), penegakan filter aman saat Profil Anak aktif, pemilihan resolusi adaptif sesuai profil perangkat, cache LRU 10 foto beserta atribusi fotografer di `Hero`, serta pengaturan Timer Tidur (`sleepMinutes`) dari HP.
- **Rekap Tontonan Bulanan Privat Lokal & Ekspor Story PNG (`WatchRecap`, `:core`, `:launcher`, `:companion`, `:desktop`) (#62).**
  - Menambahkan pelacakan riwayat durasi tonton bulanan yang 100% tersimpan lokal per profil (`WatchRecap`, dapat dimatikan atau dihapus kapan saja) pada Android TV (`Sheets.Recap`), Desktop (`Home`), dan HP (`RecapSheet` dengan ekspor gambar Story PNG 900×1600 via `FileProvider`).
- **Multi-view Desktop 2×2 / 1+1 (`MultiViewWindow`, `:desktop`) (#50).**
  - Menambahkan jendela Multi-view untuk memutar hingga 4 saluran sekaligus dalam tampilan Grid 2×2 atau Belah 1+1 dengan pemilihan saluran per kotak dan perpindahan fokus suara satu klik.
- **Subtitle Bergaya ASS/SSA (`AssSubtitleParser`, `:core`, `:launcher`, `:desktop`) (#51).**
  - Menambahkan parser ASS/SSA beserta konversi WebVTT terposisi (`PlayResX/Y`, gaya warna, tebal/miring, `\an1..9`, `\pos`) yang digunakan pada overlay pemutar Android TV (`LiveTv`) dan pemutar Desktop (`PlayerWindow`, pintasan `C`).
- **Rekam Terjadwal Latar Tanpa GUI, Kontrol LAN, & Rekam Lokal TV (`BackgroundRecorder`, `--recorder`, `LiveTv`, `:desktop`, `:launcher`) (#53, #52).**
  - Menambahkan mode daemon `--recorder` beserta pengelola layanan latar tingkat pengguna (`LaunchAgent` macOS / Task Scheduler Windows) di `RecordingsScreen` agar jadwal DVR tetap berjalan walau jendela app ditutup, notifikasi selesai rekam (`DesktopPush` & banner TV), otorisasi Bearer LAN `/api/recordings`, serta tombol rekam HLS langsung di `LiveTv` (`REC`).
- **Varian APK TV Mandiri Non-Launcher (`standalone`, `:launcher`) (#26).**
  - Menambahkan varian build `standalone` (`TahoeTV-Android-TV-Standalone.apk`) tanpa intent `CATEGORY_HOME` sehingga Tahoe TV dapat dipasang pada TV box tanpa ADB/root sebagai aplikasi biasa tanpa mengambil alih layar Home.
- **Adzan Lanjutan: Pencarian Kecamatan/Kelurahan & Bangunkan Layar Standby (`Web.searchPlaces`, `Prayer.WakeActivity`, `Onboarding`, `:core`, `:launcher`) (#48).**
  - Memperluas pencarian lokasi ke tingkat kecamatan/kelurahan/desa pada `Web.searchPlaces` serta menambahkan toggle **Bangunkan layar saat Adzan** di onboarding dan `Prayer.WakeActivity` agar layar standby menyala sejenak saat waktu sholat tiba.
- **Refaktor Arsitektur & Pemisahan Modul Besar (`:launcher`) (#95).**
  - Memisahkan `HomeDock` dan `HomeSettingsBridge` dari `Home`, `CompanionHttp` dari `Companion`, serta `ParentalSheets` dan `ConnectSheets` dari `Sheets`; memisahkan pengambilan bitmap `Logos` (lapisan data) dari pengikatan tampilan `Ui.bindLogo` (lapisan UI); menghapus kode mati (`Notif.nextPrayer`, `GlassDrawable.glow`); menguraikan siklus ketergantungan pada `Lock`, `Live`, `Onboarding`, dan `LiveTv`; serta mendefinisikan aturan batas lapisan pada `architecture.json`.

### Perbaikan
- **Perbaikan pemuatan saluran & pemutar Desktop macOS/Windows (`Live.kt`, `IptvServer`, `WebPlayer`, `desktop/build.gradle`, `Web.kt`).**
  - Menghilangkan hambatan pemuatan berurutan di belakang `IptvServer.start` pada `Live.kt` sehingga saluran langsung dimuat dari `Sources.loadCountriesPriority` dan dikirimkan ke `IptvServer.seedFromApp` secara asinkron serta menampilkan pesan kosong/offline & tombol muat ulang (`live.retry_load`).
  - Memperbaiki kunci cache `IptvServer.loadedFor` agar hasil unduhan 0 saluran tidak dikunci permanen, menambahkan `registerAdHoc` di `IptvServer` & fallback otomatis di `WebPlayer.open` (mencegah pesan *"Saluran ini tidak ada di daftar server"* di Windows), menambahkan retry 1× pada `IOException` di `Web.get` (`Sources`), serta menyertakan modul runtime `jdk.localedata`, `jdk.charsets`, `jdk.naming.dns`, dan `jdk.zipfs` pada paket `.dmg`/`.msi`/`.deb` (`desktop/build.gradle`).
- **Keandalan & penyortiran siaran lokal Indonesia serta mitigasi stall jendela live rapat (#33, #32).**
  - Menambahkan deteksi aliran salah label (`LocalTv.isMismatchedSource`, misalnya `dens.tv/h207` untuk Indosiar) dan penurunan prioritas sumber usang (`LocalTv.isDeprioritizedSource`), serta optimisasi kunci prakomputasi `LocalTv.sort`, `PopularityScraper`, dan filter bitmask 128-bit pada `LocalTv.arrange`.
  - Mendeteksi jendela live HLS yang sangat rapat pada `HlsProbe.inspectMedia` (`tightLiveWindow`), meneruskan penalti stabilitas di `StreamSelector`/`SourceHealth`, serta menerapkan konfigurasi `DefaultLoadControl` dan `LiveConfiguration` terukur (`PlayPrefs.tuningFor`) di `:launcher` dan `:companion` guna menekan stutter ±2 detik.
- **Keamanan jaringan, SSRF, CSP Nonce, & autentikasi lokal:**
  - Memblokir redirect SSRF dari sumber publik ke alamat jaringan privat/loopback di `ServerRelay` (`Web.openFollowingRedirects` + `blockPrivateNetwork`), memindahkan `/api/connect` LAN di `IptvServer` ke autentikasi Bearer per sesi (`connectToken`), mengganti script inline web dashboard/watch dengan token CSP `nonce-` acak per respons (tanpa `'unsafe-inline'` di `script-src`), menambahkan sesi cookie `HttpOnly` untuk WebSocket companion PWA di `:launcher`, dan menegakkan TLS lewat `network_security_config.xml` untuk domain `tahoe.kousozo.tech`.
- **Stabilitas perangkat nyata & *race condition* pergantian saluran:**
  - Memisahkan executor pemuatan aplikasi lokal (`Net.local`) dari unduhan playlist/EPG (`Net.bg`) sehingga dock launcher TV langsung tampil tanpa tertahan antrean jaringan; mempercepat pergantian saluran dengan `StreamSelector.newZapGeneration()` agar probe lama dibatalkan saat pindah cepat (`zapping`).
- **Status saluran desktop (`Live.kt`) & perbaikan kontrol/fokus remote Android TV:**
  - Memperbaiki `isChannelLive` di desktop agar saluran tidak ditandai `OFFLINE` selama masih ada sumber cadangan yang aktif/live.
  - Memperbaiki fokus Android TV pada modul `:launcher` (`Home`, `LiveTv`, `Live`, `Onboarding`, `Sheets`, `About`, `Weather`, `Prayer`, `Lock`, `RemoteController`) agar tombol `OK`/gamepad/`NUMPAD_ENTER` selalu membuka aplikasi/saluran/pengaturan yang sedang disorot (tidak ter-hijack chip Cuaca di bar atas).

## 3.3.1

### Perbaikan
- **Perbaikan *force close* berulang di aplikasi HP (`:companion`) & rilis Android.** Menonaktifkan minifikasi/obfuskasi R8 pada build rilis Android (`companion/build.gradle`, `launcher/build.gradle`) yang sebelumnya merusak metadata Kotlin 2.4, mengaburkan konstruktor `SetupModel` (`ViewModel`), menghapus kelas API 34 `androidx.graphics.path` serta kelas dasar `androidx.media3.decoder` yang dipanggil JNI FFmpeg, dan menyertakan ABI `x86_64` bersama `armeabi-v7a`/`arm64-v8a` dengan aturan ProGuard penuh (`-dontobfuscate` & `-keep`).
- **Keamanan *main thread*, pencatatan diagnostik, & siklus hidup layanan Android HP.** Memastikan pembaruan status Compose (`Sync.status`) dan callback mDNS (`TvDiscovery`) berjalan aman di *main thread*, membersihkan listener `MediaSessionManager` saat layanan berhenti (`onListenerDisconnected`/`onDestroy`), mencatat kegagalan `enableEdgeToEdge`/`wifiAddress`/NSD ke `DiagLog`, serta menyetel nama paket secara eksplisit pada `PendingIntent` OTA Android 14+.

## 3.3.0

### Baru
- **Dukungan desktop Debian & Ubuntu (`.deb`) (#163).** Menambahkan konfigurasi paket `.deb` (`TahoeTV-linux-amd64.deb`) di `desktop/build.gradle` dan workflow rilis `ubuntu-latest`, dukungan aset `.deb` serta fallback `xdg-open`/Chrome/Chromium `--app` di pemutar web dan pemeriksa pembaruan desktop (`SettingsScreen`, `WebPlayer`), serta kartu unduh & panduan instalasi di situs web, `README`, dan `INSTALL.md`.
- **Tahoe Connect P2–P3, penemuan mDNS otomatis, & manajemen Multi-TV (#88, #37, #158, #160).** Menambahkan penyimpanan `PairedDevices` serta dukungan multi-TV pada aplikasi pendamping HP (`TvDevicesSheet`, ganti nama/hapus perangkat, `TvLink.pairWithInfo`), endpoint manajemen perangkat `/devices` dan `/devices/revoke` di launcher TV (`Companion`), penemuan TV di Wi-Fi via `NsdManager` (`TvDiscovery`), serta endpoint penerima desktop `GET /info` dan `GET/POST /api/connect` berbasis `Protocol` v2 di `IptvServer`.
- **Picture-in-Picture (PiP) HP, gestur ketuk ganda, berbagi saluran, & Lanjut di HP (#63, #88, #159).** Mengaktifkan mode Picture-in-Picture Android pada pemutar Live TV di aplikasi HP (`MainActivity` & `Live`), gestur ketuk ganda untuk mundur/maju 10 detik dan toggle Zoom/Fit, tombol cepat PiP serta Zoom/Fit, tombol berbagi tautan saluran, dan kontrol **Lanjut di HP** (*Continue on Phone*) pada `TvConnectBar` serta `ChannelScreen`.
- **Mode Sederhana, kontrol native TV, & aksesibilitas TalkBack (#90, #64, #34, #156).** Menambahkan setelan `Viewer.simpleMode` di Pusat Kontrol TV untuk membatasi kolom beranda, memperbesar kartu dan tipografi beranda/Live TV, menambahkan overlay nomor saluran besar di tengah layar dengan konfirmasi `OK`/`Enter` instan serta `KEYCODE_DEL`/`KEYCODE_LAST_CHANNEL`, dan melengkapi label TalkBack serta kontrol pemutar HP ramah pembaca layar.
- **Protokol HP ↔ TV v2 & Tahoe Connect P0–P1 (#93, #88, #155).** Menambahkan `Protocol` berversi (ack, keep-alive, dan payload terstruktur) pada `TvLink`, lembar pemilih perangkat target & pil status aktif di aplikasi pendamping HP, serta alih tonton (`watch`/`stop`) dan sinkronisasi status putar.
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
- **Modularisasi `Home.java` & `IptvServer.kt` serta penyelarasan templat issue & dokumentasi (#95, #44, #160, #161, #162).** Memisahkan templat HTML/JS `/watch` dan dasbor `/` ke `ServerPages.kt`, handler stream relay HLS/DASH, EPG cache, dan rekaman DVR ke `ServerRelay.kt`, kendali remote companion, timer tidur, serta tool asisten ke `RemoteController.java`, mengekstrak logika kolom, batas fokus, dan navigasi beranda ke model murni teruji unit (`DockGrid`), menonaktifkan issue kosong dan merapikan templat issue GitHub (`config.yml`, `channel.yml`, `feature_request.yml`), memperjelas alur instalasi pembaruan di `README.md`/`README.id.md`, serta menetapkan target respons 7 hari di `SECURITY.md`.
- **Rantai alarm adzan standby & pengerasan PIN Profil Anak (#43, #46, #154).** Menambah cache jadwal shalat hari ini & esok di `Prayer` agar pergantian tengah malam langsung menjadwalkan Subuh berikutnya lewat alarm `00:05`, retry bertingkat (`5m`/`15m`/`60m`), `goAsync()`, serta `MediaPlayer.setWakeMode`; menerapkan penguncian eksponensial (`2s -> 30s -> 300s`) saat PIN Profil Anak salah dan menonaktifkan cadangan (`android:allowBackup="false"`) pada launcher dan aplikasi HP.
- **Penyelarasan pembaruan dependensi Actions & build (#153).** Memperbarui dependensi GitHub Actions dan pustaka Kotlin/Compose sesuai antrean Dependabot.
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
