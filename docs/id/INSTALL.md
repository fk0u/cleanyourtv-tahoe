# Instalasi & konfigurasi

[← README](../../README.id.md) · [English](../INSTALL.md)

Daftar isi: [Pasang dari HP](#pasang-dari-hp-tanpa-laptop) · [Pasang dari komputer](#pasang-dari-komputer-adb) · [Desktop](#app-desktop) ·
[Kontrol remote](#kontrol-remote) · [HP ↔ TV](#hp--tv) · [Konfigurasi](#konfigurasi) · [API key](#api-key-opsional) ·
[Wallpaper & foto](#wallpaper--foto) · [Profil Anak](#profil-anak) · [Kirim log](#kirim-log) · [Debloat](#debloat) · [Mengembalikan TV](#mengembalikan-tv)

## Pasang dari HP (tanpa laptop)

Cara yang disarankan. Butuh HP Android di Wi-Fi yang sama dengan TV.

1. Di TV: **Settings → Device Preferences → About → tekan "Build" 7×**, lalu **Developer options → USB debugging: ON**.
2. Di HP: pasang [`tahoe-tv.apk`](https://github.com/fk0u/cleanyourtv-tahoe/releases/latest/download/tahoe-tv.apk), buka tab **Pasang**.
3. App memindai Wi-Fi dan menampilkan TV yang ditemukan (port ADB 5555, dan TV yang sudah memakai Tahoe lewat `/info`). Bisa juga ketik IP.
4. Di TV muncul **"Izinkan debugging USB?"**: centang *Selalu izinkan*, lalu **OKE**.
5. Pilih aplikasi bawaan yang dimatikan, isi nama, kota (dicari otomatis), foto, dock, dan API key (opsional), lalu **Pasang ke TV**.
6. Selesai: launcher terpasang dan jadi Home, screensaver aktif, HP otomatis tersambung. Tekan **Matikan debugging di TV**.

Ubah setelan kapan saja dengan menyalakan debugging lagi, lalu buka app. **Pulihkan TV** mengembalikan semuanya ke bawaan.

Di balik layar: ADB lewat Wi-Fi ([dadb](https://github.com/mobile-dev-inc/dadb)) dengan kunci milik HP. Setelan dikirim ke receiver
`tech.kousozo.tahoetv.launcher/tv.clean.home.Config`, yang hanya bisa dipanggil shell ADB.

> TV box tanpa opsi debugging? Lihat [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) (Tahoe sebagai app mandiri) dan
> [#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36) (setup awal lewat web lokal/QR).

## Pasang dari komputer (ADB)

Butuh: Python 3, JDK 17, Android SDK (platform 36, build-tools 34+). Gradle diunduh otomatis oleh wrapper.
Di TV nyalakan USB debugging seperti di atas.

```bash
git clone https://github.com/fk0u/cleanyourtv-tahoe.git && cd cleanyourtv-tahoe
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp secrets.example.properties secrets.properties     # opsional: isi API key
export TV_HOST=192.168.1.50                           # IP TV kamu
./build-and-install.sh                                # build APK (Gradle) + pasang
```

Izin satu kali:

```bash
T=".venv/bin/python tools/tv.py"
$T 'pm grant tech.kousozo.tahoetv.launcher android.permission.ACCESS_FINE_LOCATION'    # nama Wi-Fi
$T 'pm grant tech.kousozo.tahoetv.launcher android.permission.WRITE_SECURE_SETTINGS'   # tombol Apps tetap aktif setelah reboot
$T 'pm grant tech.kousozo.tahoetv.launcher android.permission.RECORD_AUDIO'            # mic untuk asisten
$T 'appops set tech.kousozo.tahoetv.launcher SYSTEM_ALERT_WINDOW allow'                # banner shalat & notifikasi HP
$T 'appops set tech.kousozo.tahoetv.launcher GET_USAGE_STATS allow'                    # titik "baru dipakai", aplikasi terakhir
$T 'cmd package set-home-activity tech.kousozo.tahoetv.launcher/tv.clean.home.Home'
$T 'settings put secure screensaver_components tech.kousozo.tahoetv.launcher/tv.clean.home.Lock'
tools/disable.sh com.google.android.tvlauncher                         # matikan launcher bawaan (bisa dikembalikan)
```

Build publik tanpa key: `./gradlew -PpublicBuild assembleRelease` (key lalu diisi dari app HP).

## App desktop

Unduh dari [Releases](https://github.com/fk0u/cleanyourtv-tahoe/releases/latest): `TahoeTV-macos-arm64.dmg` (Apple Silicon),
`TahoeTV-macos-intel.dmg`, `TahoeTV-windows.msi`, atau `TahoeTV-linux-amd64.deb` (Debian / Ubuntu amd64).

- **macOS:** app belum dinotarisasi Apple. Saat pertama dibuka: klik kanan app → **Buka** → **Buka**.
- **Windows:** SmartScreen bisa memperingatkan; pilih **More info → Run anyway**.
- **Debian / Ubuntu:** pasang lewat terminal dengan `sudo apt install ./TahoeTV-linux-amd64.deb` (dependensi ikut dipasang otomatis), atau buka lewat penginstal paket. TV Live terbuka di mode app Chromium/Chrome jika terpasang, atau browser default.
- Data app: `~/.tahoetv/` (playlist kustom di `~/.tahoetv/playlists/`, log diagnostik `diag.log`, jadwal rekaman). Rekaman di `~/Movies/Tahoe TV` (macOS) atau `~/Videos/Tahoe TV`.
- Server IPTV LAN aktif otomatis (Setelan → Server IPTV), atau dari terminal: `./tools/start-iptv-server.sh`.

Build sendiri: `./gradlew -PpublicBuild :desktop:packageDmg` (`packageMsi` di Windows, `packageDeb` di Debian/Ubuntu dengan `fakeroot dpkg-dev binutils`). Jalankan langsung: `./gradlew :desktop:run`.

## Kontrol remote

| Tombol (remote / gamepad) | Aksi |
|---|---|
| ◀ ▶ ▲ ▼ / stik | Pindah tepat satu langkah: top bar → tombol hero → dock → Launchpad |
| OK / A | Buka · pilih |
| Tahan OK / X | Menu aplikasi: sematkan/lepas, geser, info, uninstall |
| Back / B | Tutup panel → kembali ke dock |
| Apps / Start | Launchpad |
| Menu / Y | Pusat Kontrol |
| Select | Asisten |
| L1 / R1 | Lompat 3 baris di Launchpad |

Di **Live TV**: ▲▼ ganti saluran, angka 0–9 nomor saluran, ⏪⏩ tunda/maju 30 detik, **tahan OK** tandai sumber "bukan live"
(atau pulihkan sumber yang ditandai bila saluran tidak bisa diputar), tombol merah sama dengan tahan OK.

## HP ↔ TV

- **App HP** (`tahoe-tv.apk`): pairing lewat **QR** di TV (Pusat Kontrol → HP) atau kode 6 digit. Isinya remote, Live TV, pasang/atur TV, Kirim log, dan PIN Anak.
- **Web (HP apa pun, tanpa install):** buka alamat yang tampil di TV untuk remote, gamepad, daftar aplikasi, kirim link, tanya asisten, kirim foto, dan unggah suara adzan.
- **Notifikasi & musik HP di TV:** di app HP, izinkan akses notifikasi. TV menampilkan notifikasi dan musik yang diputar, dan bisa play/pause/next dari Pusat Kontrol.
- **Keamanan:** hanya di LAN (port 8765). Setiap HP butuh token dari kode yang hanya bisa dipakai sekali. **Menu** di layar "Hubungkan HP" di TV melupakan semua HP.

## Konfigurasi

Sebagian besar diatur dari app HP. Default build ada di [`launcher/src/main/res/values/config.xml`](../../launcher/src/main/res/values/config.xml):

| Kunci | Default | Fungsi |
|---|---|---|
| `dock_size` | `6` | Slot dock (4–7) |
| `columns` | `5` | Kolom Launchpad |
| `dock_apps` | YouTube, Netflix, BitTV, Spotify, TikTok, Vidio | Isi dock awal (nama paket); yang tidak terpasang dilewati |
| `user_name` | `Tahoe` | Nama di sapaan, profil, dan layar kunci |
| `avatar_url` | (kosong) | Foto profil (di-cache di TV) |
| `city`, `latitude`, `longitude` | (kosong) | Cuaca & jadwal shalat (diatur via onboarding) |
| `wallpaper_minutes` | `10` | Interval ganti wallpaper |

Susunan dock yang diubah lewat menu tahan-OK disimpan di TV per profil.

**Live TV:** saluran diambil dari [iptv-org](https://github.com/iptv-org/iptv), [dhanytv](https://github.com/dhasap/dhanytv) (bagian TV gratis Indonesia), dan
[yositapjani](https://github.com/yositapjani/iptv-indonesia). Playlist sendiri: app HP (URL) atau desktop (Setelan → Tambah file playlist).

## API key (opsional)

Isi di `secrets.properties` (gitignored) atau dari app HP. Kosong = fitur disembunyikan.

| Kunci | Untuk | Dapatkan di |
|---|---|---|
| `gemini.apiKey`, `gemini.model` | Asisten (default `gemini-3.5-flash-lite`) | [Google AI Studio](https://aistudio.google.com/apikey) |
| `youtube.apiKey` | Hero "Trending di YouTube" | Google Cloud → YouTube Data API v3 |
| `tmdb.apiKey` | Hero "Populer di Netflix" | [TMDB](https://www.themoviedb.org/settings/api) |
| `homeassistant.url`, `homeassistant.token` | Panel Rumah | Home Assistant → Profil → Long-lived token |

Pencarian Google (grounding) Gemini memakai kuota tersendiri. Kalau habis (429), asisten otomatis menjawab tanpa pencarian.

## Wallpaper & foto

- **Pribadi** (pemilik): `files/wallpapers` + `files/photos` (foto kiriman HP). **Default**: gradien Tahoe bawaan. Profil Tamu selalu Default.
- Ganti mode: Pusat Kontrol → Wallpaper → ◀ ▶. OK = wallpaper berikutnya.
- Dari komputer:
  ```bash
  sips -s format jpeg -z 1080 1920 foto.jpg --out wallpapers/01.jpg
  .venv/bin/python tools/tv.py --push /sdcard/Android/data/tech.kousozo.tahoetv.launcher/files/wallpapers wallpapers/01.jpg
  ```
- Wallpaper bawaan dari API foto gratis direncanakan di [#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39).

## Profil Anak

1. Di layar **Siapa yang menonton?**, pilih **Anak**. Pertama kali, buat PIN 4 angka (dengan tombol arah).
2. Di profil Anak, Live TV hanya menampilkan saluran Anak & Dokumenter, dan hanya aplikasi yang diizinkan yang muncul.
3. Keluar dari profil Anak (Pusat Kontrol → **Ganti profil**, atau saat TV menyala) meminta PIN.
4. Atur PIN dan aplikasi yang diizinkan dari profil Saya: Pusat Kontrol → **Profil Anak**.
5. **Lupa PIN?** Di HP yang sudah dipasangkan: Remote → **PIN Anak** → masukkan PIN baru.

## Kirim log

Untuk melaporkan saluran yang gagal diputar atau app yang tertutup sendiri:
- **HP:** Remote → **Kirim log** (log HP + TV sekaligus) lewat WhatsApp, email, dan sejenisnya.
- **TV:** Pusat Kontrol → **Kirim log** menandai momen masalah; lalu kirim dari HP.
- **Desktop:** Setelan → Bantuan → **Simpan & salin log**, lalu **Laporkan di GitHub**.

Log berisi catatan pemutar dan crash; alamat siaran dicatat tanpa token, kunci, atau kata sandi.

## Debloat

Daftar: [`debloat-list.txt`](../../debloat-list.txt). Jalankan dalam batch kecil dan cek layar di antaranya:

```bash
tools/disable.sh com.miui.tv.analytics tv.alphonso.alphonso_eula
```

`tools/disable.sh` **menolak** paket inti Android (permission controller, package installer, providers, systemui, settings, dll.).
Sengaja dibiarkan: Assistant, Chromecast, Miracast, update firmware, Play Store/Services, setup wizard.

## Mengembalikan TV

Dari app HP: **Pulihkan TV**. Dari komputer:

```bash
./restore.sh
.venv/bin/python tools/tv.py 'pm enable com.google.android.tvlauncher'
.venv/bin/python tools/tv.py 'pm uninstall tech.kousozo.tahoetv.launcher'
.venv/bin/python tools/tv.py 'settings put secure screensaver_components com.mitv.dream/com.mitv.dream.MiDream'
```
