package tech.kousozo.tahoetv.core

/** Sorts an Indonesian playlist (iptv-org) the way people here watch TV: national free-to-air first, then TVRI's
 *  regional stations and local TV, with "near you" picked by province. iptv-org's own groups are too coarse for that. */
object LocalTv {
    enum class Category(val label: String) {
        Near("Dekat kamu"),
        National("Nasional"),
        Sports("Olahraga"),
        Movies("Film & Sinema"),
        Entertainment("Hiburan & Serial"),
        News("Berita"),
        Kids("Anak & Animasi"),
        Documentary("Dokumenter"),
        Music("Musik & Radio"),
        Lifestyle("Gaya Hidup"),
        Religious("Religi"),
        Tvri("TVRI Daerah"),
        Local("TV Daerah"),
        Vod("VOD & Film"),
        Other("Lainnya"),
    }

    /** `alternates`: other streams of the same channel (other sources), tried when `channel` fails. */
    data class Entry(val channel: M3u.Channel, val name: String, val category: Category, val near: Boolean,
                     val partTime: Boolean, val geoBlocked: Boolean, val rank: Int, val alternates: List<M3u.Channel> = emptyList())

    /** National free-to-air in the usual remote-button order; index = rank. */
    /** The main national channels, in this order, always at the top of every list. */
    val PRIORITY = listOf("trans tv", "trans7", "rcti", "mnctv", "gtv", "sctv", "antv", "indosiar", "tvone", "btv", "rtv",
        "cnn indonesia", "cnbc indonesia")

    /** National free-to-air; index = rank (PRIORITY first). */
    private val NATIONAL = PRIORITY + listOf("tvri", "inews", "metro tv", "kompas tv", "net", "moji", "mdtv", "nusantara tv",
        "garuda tv", "beritasatu", "jak tv", "tvri sport", "tvri world")

    /** Different spellings across playlists → one name, so sources merge into one row with backups. */
    private val ALIAS = mapOf("tvri nasional" to "TVRI", "tvri world" to "TVRI World", "tvri sport" to "TVRI Sport", "tv one" to "tvOne",
        "mdtv net" to "MDTV", "rajawali tv" to "RTV", "rrinet" to "RRI Net", "rri net" to "RRI Net", "mnc tv" to "MNCTV", "global tv" to "GTV", "transtv" to "Trans TV", "trans 7" to "Trans7",
        "trans 7 tv" to "Trans7", "kompastv" to "Kompas TV", "metrotv" to "Metro TV", "moji tv" to "Moji", "berita satu" to "BeritaSatu",
        "magna tv" to "Magna Channel", "magna channel" to "Magna Channel", "jek tv" to "Jek TV", "cnn indonesia" to "CNN Indonesia",
        "tvone" to "tvOne", "sctv" to "SCTV", "rcti" to "RCTI", "antv" to "ANTV", "gtv" to "GTV", "inews" to "iNews", "indosiar" to "Indosiar")

    /** TVRI regional names come in English (iptv-org) and Indonesian (others); Indonesian wins. */
    private val TVRI_ID = mapOf("east java" to "Jawa Timur", "central java" to "Jawa Tengah", "west java" to "Jawa Barat",
        "east kalimantan" to "Kalimantan Timur", "west kalimantan" to "Kalimantan Barat", "south kalimantan" to "Kalimantan Selatan",
        "central kalimantan" to "Kalimantan Tengah", "north sulawesi" to "Sulawesi Utara", "south sulawesi" to "Sulawesi Selatan",
        "central sulawesi" to "Sulawesi Tengah", "southeast sulawesi" to "Sulawesi Tenggara", "west sulawesi" to "Sulawesi Barat",
        "north sumatra" to "Sumatera Utara", "west sumatra" to "Sumatera Barat", "south sumatra" to "Sumatera Selatan",
        "east nusa tenggara" to "Nusa Tenggara Timur", "west nusa tenggara" to "Nusa Tenggara Barat", "west papua" to "Papua Barat",
        "bangka belitung" to "Bangka Belitung", "ntb" to "Nusa Tenggara Barat", "ntt" to "Nusa Tenggara Timur",
        "sumatra selatan" to "Sumatera Selatan", "sumatra utara" to "Sumatera Utara", "sumatra barat" to "Sumatera Barat",
        "ambon" to "Maluku", "dki" to "Jakarta", "dki jakarta" to "Jakarta", "kaltim" to "Kalimantan Timur", "jatim" to "Jawa Timur",
        "jateng" to "Jawa Tengah", "jabar" to "Jawa Barat", "diy" to "Yogyakarta", "jogja" to "Yogyakarta")

    /** Foreign / non-Indonesian channels without category: kept, but last. */
    private val FOREIGN = Regex("""\((taiwan)\)|rtm asean|^biznet kids""", RegexOption.IGNORE_CASE)

    /** Province (as geocoding returns it, Indonesian) → name fragments of its TVRI station and local channels. Longest key wins. */
    private val PROVINCE = mapOf(
        "aceh" to listOf("tvri aceh", "aceh"),
        "sumatera utara" to listOf("north sumatra", "efarina"),
        "sumatera barat" to listOf("west sumatra", "padang tv"),
        "kepulauan riau" to listOf("batam tv"),
        "riau" to listOf("tvri riau", "riau tv"),
        "jambi" to listOf("jambi", "bungo tv"),
        "sumatera selatan" to listOf("south sumatra", "sriwijaya tv"),
        "bangka belitung" to listOf("bangka"),
        "bengkulu" to listOf("bengkulu"),
        "lampung" to listOf("lampung"),
        "jakarta" to listOf("jakarta", "jak tv"),
        "jawa barat" to listOf("west java", "bandung", "cianjur", "parahyangan", "tasikmalaya", "banjar tv"),
        "banten" to listOf("banten"),
        "jawa tengah" to listOf("central java", "semarang", "tvku", "usm tv", "simpang5"),
        "yogyakarta" to listOf("yogyakarta", "jogja", "rbtv"),
        "jawa timur" to listOf("east java", "jtv", "dhoho", "blitar", "caruban", "kediri", "kilisuci", "malang", "bojonegoro", "suara surabaya"),
        "bali" to listOf("bali"),
        "nusa tenggara barat" to listOf("west nusa tenggara", "selaparang", "sangaji"),
        "nusa tenggara timur" to listOf("east nusa tenggara", "kupang", "atambua"),
        "kalimantan barat" to listOf("west kalimantan"),
        "kalimantan tengah" to listOf("central kalimantan", "sampit", "huma betang"),
        "kalimantan selatan" to listOf("south kalimantan", "tabalong"),
        "kalimantan timur" to listOf("east kalimantan", "balikpapan"),
        "sulawesi utara" to listOf("north sulawesi", "kawanua"),
        "gorontalo" to listOf("gorontalo"),
        "sulawesi tengah" to listOf("central sulawesi"),
        "sulawesi selatan" to listOf("south sulawesi", "celebes"),
        "sulawesi tenggara" to listOf("southeast sulawesi", "sultra"),
        "sulawesi barat" to listOf("west sulawesi"),
        "maluku" to listOf("tvri maluku"),
        "papua barat" to listOf("west papua"),
        "papua" to listOf("tvri papua"),
    ).entries.sortedByDescending { it.key.length }

    /** Keywords for the user's province ("Kalimantan Timur", "Provinsi Jawa Timur", "Daerah Istimewa Yogyakarta"…). */
    fun nearKeywords(province: String?): List<String> {
        val p = province?.lowercase() ?: return emptyList()
        val hit = PROVINCE.firstOrNull { it.key in p } ?: return emptyList()
        return hit.value + "tvri ${hit.key}" // Indonesian TVRI names ("TVRI Kalimantan Timur")
    }

    /** "TVRI East Java (720p) [Not 24/7]" → "TVRI Jawa Timur"; "RCTI HD (R+)" → "RCTI"; "Trans 7 TV" → "Trans7". */
    fun cleanName(raw: String): String {
        var n = raw.replace(Regex("""\s*\[[^]]*]"""), "").replace(Regex("""\s*\([^)]*\)\)?"""), "")
            .replace(Regex("""\s+(HD|FHD|SD)\b""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s+cad$""", RegexOption.IGNORE_CASE), "").trim()
        if (n.startsWith("TVRI ", true)) TVRI_ID[n.substring(5).lowercase()]?.let { n = "TVRI $it" }
        return ALIAS[n.lowercase()] ?: n
    }

    /** Movies and on-demand files: a finished playlist is normal for these, unlike a live channel. */
    fun isVod(c: M3u.Channel): Boolean {
        val group = c.group?.lowercase().orEmpty(); val url = c.url.lowercase(); val l = cleanName(c.name).lowercase()
        return group.contains("vod") || url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi") ||
            url.contains(".mov") || url.contains(".webm") || l.startsWith("vod ") || group.contains("film on demand")
    }

    fun classify(c: M3u.Channel, near: List<String>): Entry {
        val name = cleanName(c.name)
        val l = name.lowercase()
        val group = c.group?.lowercase().orEmpty()
        val url = c.url.lowercase()
        val nat = NATIONAL.indexOf(l).takeIf { it >= 0 }
        val isNear = near.any { it in l }

        val isVod = isVod(c)

        val isSports = group.contains("sport") || group.contains("worldcup") || group.contains("soccer") ||
                group.contains("football") || group.contains("otomotif") || group.contains("motor") ||
                l.contains("bein") || l.contains("spotv") || l.contains("champions tv") || l.contains("premier league") ||
                l.contains("soccer") || l.contains("sportstars") || l.contains("nba") || l.contains("eurosport") ||
                l.contains("fight sports") || l.contains("golf") || l.contains("motogp") || l.contains("f1") ||
                l.contains("mola") || l.contains("k-ball") || l.contains("sport")

        val isMovies = group.contains("movie") || group.contains("cinema") || group.contains("hbo") ||
                l.contains("hbo") || l.contains("cinemax") || l.contains("fox movies") || l.contains("cinema world") ||
                l.contains("celestial") || l.contains("hits movies") || l.contains("zee bioskop") || l.contains("galaxy") ||
                l.contains("flik") || l.contains("my cinema") || l.contains("bioskop") || l.contains("thrill") ||
                l.contains("paramount")

        val isKids = group.contains("kid") || group.contains("animation") || group.contains("anime") ||
                group.contains("family") || l.contains("cartoon network") || l.contains("nickelodeon") ||
                l.contains("nick jr") || l.contains("boomerang") || l.contains("dreamworks") ||
                l.contains("babyfirst") || l.contains("cbeebies") || l.contains("my kidz") ||
                l.contains("moonbug") || l.contains("animax") || l.contains("space toon") || l.contains("daai")

        val isNews = group.contains("news") || group.contains("business") || group.contains("berita") ||
                l.contains("cnn") || l.contains("cnbc") || l.contains("metro tv") || l.contains("tvone") ||
                l.contains("kompas tv") || l.contains("beritasatu") || l.contains("inews") || l.contains("bbc") ||
                l.contains("al jazeera") || l.contains("cna") || l.contains("bloomberg")

        val isDoc = group.contains("documentary") || group.contains("knowledge") || group.contains("education") ||
                l.contains("discovery") || l.contains("nat geo") || l.contains("national geographic") ||
                l.contains("animal planet") || l.contains("history") || l.contains("bbc earth") ||
                l.contains("love nature") || l.contains("crime")

        val isEntertainment = group.contains("entertainment") || group.contains("series") || group.contains("comedy") ||
                group.contains("hiburan") || l.contains("tvn") || l.contains("k-plus") || l.contains("axn") ||
                l.contains("warner") || l.contains("hits") || l.contains("lifetime") || l.contains("one")

        val isMusic = group.contains("music") || group.contains("radio") || l.contains("mtv") || l.contains("trace")

        val isLifestyle = group.contains("lifestyle") || group.contains("cooking") || group.contains("auto") ||
                l.contains("food network") || l.contains("hgtv") || l.contains("afn")

        val isReligious = group.contains("religi") || group.contains("christian") || group.contains("islam") ||
                group.contains("dakwah") || l.contains("rodja") || l.contains("surau") || l.contains("quran") ||
                l.contains("tv9") || l.contains("madani") || l.contains("nabawi")

        val cat = when {
            FOREIGN.containsMatchIn(c.name) -> Category.Other
            isVod -> Category.Vod
            nat != null -> Category.National
            isSports -> Category.Sports
            isMovies -> Category.Movies
            l.startsWith("tvri ") -> Category.Tvri
            isKids -> Category.Kids
            isNews -> Category.News
            isDoc -> Category.Documentary
            isEntertainment -> Category.Entertainment
            isMusic -> Category.Music
            isLifestyle -> Category.Lifestyle
            isReligious -> Category.Religious
            group.isEmpty() || group == "general" || group == "undefined" || "local" in group || "culture" in group -> Category.Local
            else -> Category.Other
        }
        return Entry(c, name, cat, isNear, "Not 24/7" in c.name, "Geo-blocked" in c.name, nat ?: 1000)
    }

    /** Classify, merge duplicates (best stream first: 24/7, then no special headers; the rest become backups),
     *  and sort near → category → rank → name. */
    fun arrange(channels: List<M3u.Channel>, province: String?): List<Entry> {
        val near = nearKeywords(province)
        return channels.map { classify(it, near) }
            .groupBy { "${it.name.lowercase()}|${it.category.name}" }.values.map { dupes ->
                val sorted = dupes.sortedWith(compareBy({ it.partTime }, { it.channel.headers.isNotEmpty() }))
                sorted.first().copy(alternates = sorted.drop(1).map { it.channel })
            }
            .sortedWith(compareBy({ it.rank >= PRIORITY.size }, { !it.near }, { it.category.ordinal }, { it.rank }, { it.partTime }, { it.name.lowercase() }))
    }

    fun inCategory(e: Entry, c: Category) = if (c == Category.Near) e.near else e.category == c
}
