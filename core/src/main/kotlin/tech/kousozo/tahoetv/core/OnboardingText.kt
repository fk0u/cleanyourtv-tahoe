package tech.kousozo.tahoetv.core

/** Text of the onboarding (#77) in every app language, shared by the phone and desktop so both say the same thing.
 *  The rest of the apps moves to resources in #56. "%s" is filled in by the caller. */
object OnboardingText {
    /** Each language in its own name, so people find theirs whatever is selected. */
    val LANGUAGE_NAMES = mapOf("en" to "English", "id" to "Bahasa Indonesia", "zh" to "中文（简体）")

    fun get(lang: String, key: String): String = (T[lang] ?: T.getValue("en"))[key] ?: T.getValue("en")[key] ?: key
    fun category(lang: String, c: LocalTv.Category): String = get(lang, "cat." + c.name)

    private val EN = mapOf(
        "step" to "Step %s",
        "next" to "Continue", "back" to "Back", "skip" to "Skip", "done" to "Start watching", "search" to "Search",
        "lang.title" to "Welcome to Tahoe TV", "lang.sub" to "Choose your language",
        "country.title" to "Where do you live?", "country.sub" to "Used for suggestions, the guide and prayer times",
        "city.title" to "Your city", "city.sub" to "For weather and prayer times. You can skip this.",
        "city.search" to "Search city", "city.none" to "No city set: weather and prayer times stay hidden.",
        "city.empty" to "No city found. Try another spelling.",
        "tz.title" to "Time zone", "tz.sub" to "Clock, TV guide and prayer times use this zone",
        "tz.auto" to "From your city: %s", "tz.device" to "This device: %s",
        "prayer.title" to "Prayer times & adhan", "prayer.sub" to "Show prayer times and play the adhan on the TV",
        "prayer.on" to "Show prayer times", "prayer.method" to "Calculation method",
        "prayer.needcity" to "Set a city first to get prayer times.",
        "countries.title" to "Channels from which countries?", "countries.sub" to "Pick one or more. Free channels from iptv-org.",
        "cats.title" to "What do you like to watch?", "cats.sub" to "Only these categories are shown. Nothing selected = everything.",
        "favs.title" to "Pick your favourites", "favs.sub" to "They come first everywhere. Tap to add or remove.",
        "favs.loading" to "Loading channels…", "favs.failed" to "Channels couldn't be loaded. You can add favourites later.",
        "order.title" to "Arrange your channels", "order.sub" to "Favourites in this order come first. Use ▲ ▼ to move.",
        "order.empty" to "No favourites yet: channels keep the usual order.",
        "summary.title" to "All set", "summary.sub" to "You can change any of this later in Settings.",
        "sum.language" to "Language", "sum.country" to "Country", "sum.city" to "City", "sum.tz" to "Time zone",
        "sum.prayer" to "Prayer times", "sum.channels" to "Channels from", "sum.cats" to "Categories", "sum.favs" to "Favourites",
        "sum.all" to "All", "sum.off" to "Off", "sum.none" to "None",
        "note.home" to "%s includes curated extra sources and regional channels.",
        "note.other" to "Free channels for %s come from iptv-org. Some countries have only a few.",
        "cat.Near" to "Near you", "cat.National" to "National", "cat.Sports" to "Sports", "cat.Movies" to "Movies",
        "cat.Entertainment" to "Entertainment & series", "cat.News" to "News", "cat.Kids" to "Kids & animation",
        "cat.Documentary" to "Documentary", "cat.Music" to "Music & radio", "cat.Lifestyle" to "Lifestyle",
        "cat.Religious" to "Religious", "cat.Tvri" to "TVRI regional", "cat.Local" to "Local TV", "cat.Vod" to "VOD & films", "cat.Other" to "Other",
    )

    private val ID = mapOf(
        "step" to "Langkah %s",
        "next" to "Lanjut", "back" to "Kembali", "skip" to "Lewati", "done" to "Mulai menonton", "search" to "Cari",
        "lang.title" to "Selamat datang di Tahoe TV", "lang.sub" to "Pilih bahasa",
        "country.title" to "Kamu tinggal di mana?", "country.sub" to "Dipakai untuk saran, jadwal acara, dan jadwal shalat",
        "city.title" to "Kotamu", "city.sub" to "Untuk cuaca dan jadwal shalat. Boleh dilewati.",
        "city.search" to "Cari kota", "city.none" to "Kota belum diatur: cuaca dan jadwal shalat disembunyikan.",
        "city.empty" to "Kota tidak ditemukan. Coba ejaan lain.",
        "tz.title" to "Zona waktu", "tz.sub" to "Jam, jadwal acara, dan jadwal shalat memakai zona ini",
        "tz.auto" to "Dari kotamu: %s", "tz.device" to "Perangkat ini: %s",
        "prayer.title" to "Jadwal shalat & adzan", "prayer.sub" to "Tampilkan jadwal shalat dan putar adzan di TV",
        "prayer.on" to "Tampilkan jadwal shalat", "prayer.method" to "Metode perhitungan",
        "prayer.needcity" to "Atur kota dulu untuk mendapat jadwal shalat.",
        "countries.title" to "Saluran dari negara mana saja?", "countries.sub" to "Pilih satu atau lebih. Saluran gratis dari iptv-org.",
        "cats.title" to "Suka nonton apa?", "cats.sub" to "Hanya kategori ini yang ditampilkan. Tidak memilih = semua.",
        "favs.title" to "Pilih favoritmu", "favs.sub" to "Selalu tampil paling depan. Ketuk untuk menambah atau menghapus.",
        "favs.loading" to "Memuat saluran…", "favs.failed" to "Saluran tidak bisa dimuat. Favorit bisa ditambah nanti.",
        "order.title" to "Atur urutan saluran", "order.sub" to "Favorit tampil paling depan sesuai urutan ini. Pakai ▲ ▼ untuk memindah.",
        "order.empty" to "Belum ada favorit: saluran memakai urutan biasa.",
        "summary.title" to "Siap", "summary.sub" to "Semua ini bisa diubah nanti di Setelan.",
        "sum.language" to "Bahasa", "sum.country" to "Negara", "sum.city" to "Kota", "sum.tz" to "Zona waktu",
        "sum.prayer" to "Jadwal shalat", "sum.channels" to "Saluran dari", "sum.cats" to "Kategori", "sum.favs" to "Favorit",
        "sum.all" to "Semua", "sum.off" to "Mati", "sum.none" to "Belum ada",
        "note.home" to "%s punya sumber tambahan pilihan dan saluran daerah.",
        "note.other" to "Saluran gratis untuk %s diambil dari iptv-org. Beberapa negara salurannya sedikit.",
        "cat.Near" to "Dekat kamu", "cat.National" to "Nasional", "cat.Sports" to "Olahraga", "cat.Movies" to "Film & Sinema",
        "cat.Entertainment" to "Hiburan & Serial", "cat.News" to "Berita", "cat.Kids" to "Anak & Animasi",
        "cat.Documentary" to "Dokumenter", "cat.Music" to "Musik & Radio", "cat.Lifestyle" to "Gaya Hidup",
        "cat.Religious" to "Religi", "cat.Tvri" to "TVRI Daerah", "cat.Local" to "TV Daerah", "cat.Vod" to "VOD & Film", "cat.Other" to "Lainnya",
    )

    private val ZH = mapOf(
        "step" to "第 %s 步",
        "next" to "继续", "back" to "返回", "skip" to "跳过", "done" to "开始观看", "search" to "搜索",
        "lang.title" to "欢迎使用 Tahoe TV", "lang.sub" to "选择语言",
        "country.title" to "你住在哪里？", "country.sub" to "用于推荐、节目表和礼拜时间",
        "city.title" to "你的城市", "city.sub" to "用于天气和礼拜时间。可以跳过。",
        "city.search" to "搜索城市", "city.none" to "未设置城市：天气和礼拜时间将隐藏。",
        "city.empty" to "未找到城市，请换一种拼写。",
        "tz.title" to "时区", "tz.sub" to "时钟、节目表和礼拜时间使用此时区",
        "tz.auto" to "根据城市：%s", "tz.device" to "本设备：%s",
        "prayer.title" to "礼拜时间与宣礼", "prayer.sub" to "显示礼拜时间并在电视上播放宣礼",
        "prayer.on" to "显示礼拜时间", "prayer.method" to "计算方法",
        "prayer.needcity" to "请先设置城市以获取礼拜时间。",
        "countries.title" to "想看哪些国家的频道？", "countries.sub" to "可选一个或多个。免费频道来自 iptv-org。",
        "cats.title" to "你喜欢看什么？", "cats.sub" to "只显示所选类别。不选择 = 全部显示。",
        "favs.title" to "选择收藏频道", "favs.sub" to "收藏的频道总是排在最前。点按即可添加或移除。",
        "favs.loading" to "正在加载频道…", "favs.failed" to "无法加载频道。之后也可以添加收藏。",
        "order.title" to "排列频道", "order.sub" to "收藏频道按此顺序排在最前。用 ▲ ▼ 移动。",
        "order.empty" to "还没有收藏：频道保持默认顺序。",
        "summary.title" to "设置完成", "summary.sub" to "以后可以随时在设置中更改。",
        "sum.language" to "语言", "sum.country" to "国家", "sum.city" to "城市", "sum.tz" to "时区",
        "sum.prayer" to "礼拜时间", "sum.channels" to "频道来源", "sum.cats" to "类别", "sum.favs" to "收藏",
        "sum.all" to "全部", "sum.off" to "关闭", "sum.none" to "无",
        "note.home" to "%s 包含精选的额外来源和地方频道。",
        "note.other" to "%s 的免费频道来自 iptv-org。部分国家频道较少。",
        "cat.Near" to "附近", "cat.National" to "全国", "cat.Sports" to "体育", "cat.Movies" to "电影",
        "cat.Entertainment" to "娱乐与剧集", "cat.News" to "新闻", "cat.Kids" to "少儿与动画",
        "cat.Documentary" to "纪录片", "cat.Music" to "音乐与电台", "cat.Lifestyle" to "生活",
        "cat.Religious" to "宗教", "cat.Tvri" to "TVRI 地方台", "cat.Local" to "地方台", "cat.Vod" to "点播与电影", "cat.Other" to "其他",
    )

    private val T = mapOf("en" to EN, "id" to ID, "zh" to ZH)
}
