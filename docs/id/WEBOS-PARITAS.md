# webOS vs Android TV: audit kesetaraan fitur

Diperbarui 28 November 2026. "Sudah" berarti ada di `webos/`; kolom terakhir menyebut sejauh mana sudah dicoba.
Putaran pertama webOS sudah dicek di LG 43LM5700 (webOS 4.10). **Fitur di bawah yang ditambahkan sesudahnya baru dicoba
di browser komputer** (Chromium baru), belum di TV.

| Fitur Android TV | webOS | Catatan |
|---|---|---|
| TV langsung: kategori, logo, favorit, sumber cadangan, nomor saluran | Sudah | dasar sudah dicek di TV |
| Beranda (sorotan, lanjutkan menonton, sedang tayang, film) | Sudah | browser |
| Jam, cuaca, waktu salat berikutnya | Sudah | browser; belum ada adzan |
| Jadwal acara (EPG): kini/berikutnya, halaman jadwal, bilah kemajuan | Sudah | 167 dari 999 saluran punya jadwal (data nyata) |
| Pengingat acara | Sudah | hanya saat aplikasi terbuka |
| Cari saluran, acara, film (papan ketik di layar) | Sudah | browser |
| Pemutar: daftar saluran, pilihan sumber, saluran sebelumnya, pengatur tidur | Sudah | browser |
| Layar siaran gagal + nada Tahoe + hitung mundur coba lagi | Sudah | nada diputar di browser; bunyinya di TV belum didengar |
| Film & Series: poster, episode, lanjutkan posisi, tanda selesai | Sudah | posisi, lanjutkan, dan sambung ulang dicek di browser dengan aliran uji |
| Profil Saya / Anak / Tamu, PIN, batas waktu Anak | Sudah | PIN dan batas waktu dicek di browser |
| Onboarding dan Pengaturan (nama, bahasa, kota, negara, kategori, urutan, playlist) | Sudah | browser |
| Bahasa Indonesia, Inggris, Mandarin | Sudah | bahasa Android lain belum |
| Info pembaruan dengan tombol Nanti | Sudah | webOS tidak bisa memasang sendiri; dari komputer |
| Launcher: dock, daftar aplikasi, jadi layar Beranda | Tidak bisa | webOS tidak mengizinkan launcher pihak ketiga |
| Rekaman siaran | Tidak bisa | aplikasi web tidak boleh menulis berkas video |
| Header Referer/User-Agent per siaran, siaran DRM | Belum | pemutar bawaan tidak menerima header dari aplikasi web |
| Remote dari HP, pasangkan lewat QR, setup ADB | Belum | perlu layanan JS webOS (server di TV) |
| Akun Tahoe dan Handoff | Belum | server akun sengaja tanpa CORS; perlu uji di TV apakah aplikasi webOS tetap bisa memanggilnya, kalau tidak server harus diubah |
| Notifikasi dari server, kontrol admin | Belum | |
| Asisten suara, Home Assistant, screensaver foto | Belum | webOS punya screensaver sendiri |
| Berkas unduhan di rilis GitHub (`.ipk`) | Sudah di CI | muncul mulai rilis berikutnya; pasang tetap dari komputer |

## Urutan yang disarankan berikutnya
1. Coba seluruh putaran ini di TV LG (mesin lama: kecepatan jadwal, memori, bunyi, resume film).
2. Akun Tahoe dan Handoff, lalu remote dari HP lewat layanan JS.
