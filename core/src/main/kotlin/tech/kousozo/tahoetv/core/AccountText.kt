package tech.kousozo.tahoetv.core

/** Strings for the account and display screens on phone, TV and desktop (English and Indonesian; others fall back to English). */
object AccountText {
    private val EN = mapOf(
        "title" to "Account", "sub" to "Sign in to keep your profile, photo and wallpaper on every device.",
        "email" to "E-mail", "password" to "Password", "invite" to "Invite code", "signin" to "Sign in", "register" to "Create account",
        "to_register" to "New here? Create an account", "to_signin" to "Have an account? Sign in", "signout" to "Sign out",
        "signed_as" to "Signed in as %s", "busy" to "One moment…", "synced" to "Synced", "saved" to "Saved to your account",
        "offline" to "Can't reach the server", "off" to "Accounts are not available in this build",
        "photo" to "Profile photo", "wallpaper" to "Wallpaper", "pick_photo" to "Choose a photo", "pick_wall" to "Choose a wallpaper", "remove" to "Remove",
        "display" to "Display", "name" to "Display name", "theme" to "Theme", "t_system" to "System", "t_light" to "Light", "t_dark" to "Dark",
        "accent" to "Accent colour", "text_size" to "Text size", "dim" to "Wallpaper darkness", "clock24" to "24-hour clock", "seconds" to "Show seconds",
        "clock_size" to "Clock size", "s" to "Small", "m" to "Medium", "l" to "Large", "density" to "Layout", "comfortable" to "Comfortable", "compact" to "Compact",
        "motion" to "Reduce motion", "greeting" to "Animated greeting", "on_tv" to "On your TV", "on_phone" to "On this phone",
        "security" to "Security", "change_pw" to "Change password", "old_pw" to "Current password", "new_pw" to "New password",
        "delete" to "Delete account", "delete_note" to "Removes your profile, photo and wallpaper from the server. This cannot be undone.",
        "to_tv" to "Use this account on my TV", "tv_sent" to "Sent to your TV", "tv_fail" to "Couldn't reach the TV",
        "err_upload" to "Upload failed: %s", "err_generic" to "Something went wrong", "confirm_delete" to "Delete my account",
        "storage" to "Your files are stored encrypted on your private Tahoe server.", "server" to "Server address", "server_hint" to "https://your-server",
        "continue_here" to "Continue: %s", "continue_from" to "From %s", "handoff_on" to "Continue on another device", "handoff_sub" to "Your TV, phone and Mac offer to carry on what you were watching",
        "phones" to "Paired phones", "phones_sub" to "Press OK on a phone to remove its access", "phones_none" to "No phone is paired yet", "phones_paired" to "Paired %s", "revoke_all" to "Remove all phones", "revoked" to "Access removed",
    )
    private val ID = mapOf(
        "title" to "Akun", "sub" to "Masuk agar profil, foto, dan wallpaper tersimpan di semua perangkat.",
        "email" to "E-mail", "password" to "Kata sandi", "invite" to "Kode undangan", "signin" to "Masuk", "register" to "Buat akun",
        "to_register" to "Belum punya akun? Daftar", "to_signin" to "Sudah punya akun? Masuk", "signout" to "Keluar",
        "signed_as" to "Masuk sebagai %s", "busy" to "Sebentar…", "synced" to "Tersinkron", "saved" to "Tersimpan ke akun",
        "offline" to "Server tidak terjangkau", "off" to "Akun tidak tersedia di build ini",
        "photo" to "Foto profil", "wallpaper" to "Wallpaper", "pick_photo" to "Pilih foto", "pick_wall" to "Pilih wallpaper", "remove" to "Hapus",
        "display" to "Tampilan", "name" to "Nama tampilan", "theme" to "Tema", "t_system" to "Sistem", "t_light" to "Terang", "t_dark" to "Gelap",
        "accent" to "Warna aksen", "text_size" to "Ukuran teks", "dim" to "Gelap wallpaper", "clock24" to "Jam 24 jam", "seconds" to "Tampilkan detik",
        "clock_size" to "Ukuran jam", "s" to "Kecil", "m" to "Sedang", "l" to "Besar", "density" to "Tata letak", "comfortable" to "Lega", "compact" to "Rapat",
        "motion" to "Kurangi gerakan", "greeting" to "Sapaan beranimasi", "on_tv" to "Di TV kamu", "on_phone" to "Di HP ini",
        "security" to "Keamanan", "change_pw" to "Ganti kata sandi", "old_pw" to "Kata sandi saat ini", "new_pw" to "Kata sandi baru",
        "delete" to "Hapus akun", "delete_note" to "Menghapus profil, foto, dan wallpaper dari server. Tidak bisa dibatalkan.",
        "to_tv" to "Pakai akun ini di TV saya", "tv_sent" to "Terkirim ke TV", "tv_fail" to "TV tidak terjangkau",
        "err_upload" to "Unggah gagal: %s", "err_generic" to "Terjadi kesalahan", "confirm_delete" to "Hapus akun saya",
        "storage" to "Filemu tersimpan terenkripsi di server Tahoe pribadi.", "server" to "Alamat server", "server_hint" to "https://servermu",
        "continue_here" to "Lanjutkan: %s", "continue_from" to "Dari %s", "handoff_on" to "Lanjutkan di perangkat lain", "handoff_sub" to "TV, HP, dan Mac menawarkan melanjutkan tontonanmu",
        "phones" to "HP yang terhubung", "phones_sub" to "Tekan OK pada HP untuk mencabut aksesnya", "phones_none" to "Belum ada HP yang terhubung", "phones_paired" to "Terhubung %s", "revoke_all" to "Cabut semua HP", "revoked" to "Akses dicabut",
    )
    @JvmStatic val tables: Map<String, Map<String, String>> = mapOf("en" to EN, "id" to ID)

    @JvmStatic @JvmOverloads fun get(lang: String, key: String, arg: String? = null): String {
        val t = (tables[lang] ?: EN)[key] ?: EN[key] ?: key
        return if (arg == null) t else t.replace("%s", arg)
    }
}
