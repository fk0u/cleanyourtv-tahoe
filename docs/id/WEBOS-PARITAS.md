# webOS vs Android TV: audit kesetaraan fitur

Diperbarui 28 November 2026. "Sudah" berarti ada di `webos/`; kolom terakhir menyebut sejauh mana sudah dicoba.
"TV" = dicek di LG 43LM5700 (webOS 4.10, Chromium 53) lewat perintah dari komputer, bukan tombol remote fisik. Di TV itu:
915 saluran, jadwal 141 saluran termuat sekitar 12 detik, tombol di daftar ditanggapi di bawah 40 ms, memori JS 30 MB.

| Fitur Android TV | webOS | Catatan |
|---|---|---|
| TV langsung: kategori, logo, favorit, sumber cadangan, nomor saluran | Sudah | TV |
| Beranda (sorotan, lanjutkan menonton, sedang tayang, film) | Sudah | TV |
| Jam, cuaca, waktu salat berikutnya | Sudah | jam di TV; cuaca dan salat baru di browser; belum ada suara adzan |
| Jadwal acara (EPG): kini/berikutnya, halaman jadwal, bilah kemajuan | Sudah | TV |
| Pengingat acara | Sudah | pasang/hapus di TV, notifikasi di browser; hanya saat aplikasi terbuka |
| Cari saluran, acara, film (papan ketik di layar) | Sudah | TV |
| Pemutar: daftar saluran, pilihan sumber, saluran sebelumnya, pengatur tidur | Sudah | TV (daftar, pilihan, saluran sebelumnya); pengatur tidur belum ditunggu sampai habis |
| Layar siaran gagal + nada Tahoe + hitung mundur coba lagi | Sudah | tampilan dan nada berjalan di TV; bunyinya belum didengar orang |
| Film & Series: poster, episode, lanjutkan posisi, tanda selesai | Sudah | TV: film pixeldrain diputar, dimajukan, dan dilanjutkan; episode serial baru di browser |
| Profil Saya / Anak / Tamu, PIN, batas waktu Anak | Sudah | profil dan PIN di TV; batas waktu Anak baru di browser |
| Onboarding dan Pengaturan (nama, bahasa, kota, negara, kategori, urutan, playlist) | Sudah | TV |
| Bahasa Indonesia, Inggris, Mandarin | Sudah | bahasa Android lain belum |
| Pembaruan otomatis (OTA) dengan tombol Nanti | Sudah, belum diuji | kode diunduh dari tag rilis dan dicek SHA-256; kembali ke versi paket bila gagal; paket ≤ 3.8.8 perlu dipasang sekali lagi dari komputer |
| Riwayat tontonan | Sudah, belum diuji | |
| Pratinjau siaran di latar, poster sebagai latar, animasi | Sudah, belum diuji | bisa dimatikan di Pengaturan bila TV lambat |
| Rasio layar, audio, subtitel | Sudah, belum diuji | audio/subtitel hanya bila browser TV memberi daftar trek |
| Cuaca 5 hari, pemberitahuan waktu salat | Sudah, belum diuji | tanpa suara adzan |
| Launcher: dock, daftar aplikasi, jadi layar Beranda | Tidak bisa | webOS tidak mengizinkan launcher pihak ketiga |
| Rekaman siaran | Tidak bisa | aplikasi web tidak boleh menulis berkas video |
| Header Referer/User-Agent per siaran, siaran DRM | Belum | pemutar bawaan tidak menerima header dari aplikasi web |
| Remote dari HP, pasangkan lewat QR, setup ADB | Belum | perlu layanan JS webOS (server di TV) |
| Akun Tahoe dan Handoff | Belum | server akun sengaja tanpa CORS; perlu uji di TV apakah aplikasi webOS tetap bisa memanggilnya, kalau tidak server harus diubah |
| Notifikasi dari server, kontrol admin | Belum | |
| Asisten suara, Home Assistant, screensaver foto | Belum | webOS punya screensaver sendiri |
| Berkas unduhan di rilis GitHub (`.ipk`) | Sudah di CI | muncul mulai rilis berikutnya; pasang tetap dari komputer |

## Urutan yang disarankan berikutnya
1. Coba dengan remote fisik, dengarkan nada, dan pakai beberapa hari.
2. Akun Tahoe dan Handoff, lalu remote dari HP lewat layanan JS.
