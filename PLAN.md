# Rencana v2: Tahoe TV (gaya tvOS 26)

Arah baru: **modern minimalis tapi editorial**. Tetap bahasa desain "Tahoe"/Liquid Glass, tapi mengikuti **tvOS 26**,
bukan macOS. Foto/artwork besar sebagai hero, tipografi besar dan tenang, sedikit kaca, banyak ruang kosong.
Setiap fitur punya issue sendiri. Status dan diskusi ada di sana.

## Batasan yang mengikat semua fitur

| Batasan | Dampak |
|---|---|
| Mi TV Stick: **1 GB RAM**, `ro.config.low_ram=true`, Android 9 (SDK 28) | Tidak boleh blur real-time, video latar, atau polling. Notification listener **diblokir** firmware. |
| Tanpa root | Assistant, popup volume, dan Settings sistem tidak bisa diganti. Tidak bisa menyuntik tombol ke aplikasi lain. |
| Build tanpa Gradle/AndroidX | SDK pihak ketiga (Google Home APIs, Play services client lib) butuh migrasi ke Gradle. Diputuskan di issue terkait. |
| Rahasia (API key, OAuth) | Tidak boleh di-commit. Disimpan di `launcher/res/values/secrets.xml` (gitignored) + contoh `secrets.example.xml`. |

## Fase

### Fase 1: Fondasi redesign
| # | Fitur | Inti |
|---|---|---|
| [#2](../../issues/2) | **Design system tvOS 26** (epic) | Token warna/tipografi/kaca, layout editorial, hero full-bleed, motion spec |
| [#3](../../issues/3) | **Dock ala macOS + default 5–6 aplikasi** | Magnifikasi tetangga, indikator "sedang jalan", ukuran dock terpisah dari kolom grid |
| [#4](../../issues/4) | **Transisi buka aplikasi** | Tile membesar → warna dominan → app, via `ActivityOptions` (murah) |

### Fase 2: Beranda yang hidup
| # | Fitur | Inti |
|---|---|---|
| [#5](../../issues/5) | **Hero kontekstual per aplikasi** (YouTube dulu) | Fokus di YouTube → latar berganti ke thumbnail trending, dengan judul dan channel |
| [#6](../../issues/6) | **Cuaca interaktif** | Widget fokusable → panel per jam dan 7 hari, ikon/latar kondisi beranimasi ringan |
| [#7](../../issues/7) | **Jadwal shalat + adzan** | Suara adzan di waktu shalat (bisa diatur per waktu), imsak, tanggal Hijriah, tahan process death |

### Fase 3: Media & game
| # | Fitur | Inti |
|---|---|---|
| [#8](../../issues/8) | **Slideshow Google Photos** | Lewat Photos **Picker API** (QR ke HP untuk memilih album), dipakai di screensaver dan wallpaper |
| [#9](../../issues/9) | **Kontrol gamepad** | Navigasi penuh dengan controller (A/B/X/Y, stik analog), indikator controller tersambung |
| [#10](../../issues/10) | **Hub game: PS5 Remote Play & Steam / cloud** | Baris "Game" yang meluncurkan Chiaki-ng / PS Remote Play, Steam Link, Moonlight, GeForce NOW |

### Fase 4: Suara & AI
| # | Fitur | Inti |
|---|---|---|
| [#11](../../issues/11) | **Asisten AI suara (Gemini)** | Bicara → teks → Gemini API → jawaban + TTS, overlay kaca ala tvOS |
| [#12](../../issues/12) | **Google Home lewat suara** | Kontrol lampu/perangkat rumah. Riset jalur: Home APIs vs Assistant bawaan vs Home Assistant |

### Fase 5: HP ↔ TV
| # | Fitur | Inti |
|---|---|---|
| [#13](../../issues/13) | **Companion HP ↔ TV** (epic) | Server lokal di TV + web client (PWA) + app Android untuk sinkron notifikasi, musik, remote, kirim link/foto |

## Urutan kerja yang disarankan
1. **#2 → #3 → #4**: semua fitur lain menumpang di design system dan dock baru.
2. **#7 dan #6**: nilai harian tinggi dan tanpa kunci API baru.
3. **#5 dan #9/#10**: butuh API key YouTube; gamepad murah.
4. **#13**: membuka notifikasi dan Now Playing yang terblokir di stick (datanya diambil dari HP).
5. **#8, #11, #12**: butuh OAuth/API key Google dan riset terbanyak.

## Definisi selesai (berlaku untuk tiap issue)
- Diuji di perangkat asli dengan screenshot sebelum/sesudah di PR.
- Memori proses launcher tidak naik signifikan (`dumpsys meminfo tv.clean.home`, catat sebelum/sesudah).
- Navigasi D-pad tetap deterministik. Tidak ada fokus yang "hilang".
- README dan `config.xml` diperbarui untuk setiap opsi baru.
