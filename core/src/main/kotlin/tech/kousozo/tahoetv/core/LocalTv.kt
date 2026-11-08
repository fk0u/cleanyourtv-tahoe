package tech.kousozo.tahoetv.core

/** Sorts an Indonesian playlist (iptv-org) the way people here watch TV: national free-to-air first, then TVRI's
 *  regional stations and local TV, with "near you" picked by province. iptv-org's own groups are too coarse for that. */
object LocalTv {
    enum class Category(val label: String) {
        Near("Dekat kamu"), National("Nasional"), Tvri("TVRI Daerah"), Local("TV Lokal"),
        News("Berita"), Religious("Religi"), Kids("Anak"), Other("Lainnya"),
    }

    /** `alternates`: other streams of the same channel (other sources), tried when `channel` fails. */
    data class Entry(val channel: M3u.Channel, val name: String, val category: Category, val near: Boolean,
                     val partTime: Boolean, val geoBlocked: Boolean, val rank: Int, val alternates: List<M3u.Channel> = emptyList())

    /** National free-to-air in the usual remote-button order; index = rank. */
    private val NATIONAL = listOf("tvri", "rcti", "sctv", "indosiar", "antv", "gtv", "mnctv", "inews", "metro tv", "tvone",
        "kompas tv", "trans tv", "trans7", "net", "rtv", "moji", "mdtv", "nusantara tv", "garuda tv", "btv", "cnn indonesia",
        "cnbc indonesia", "beritasatu", "jak tv", "tvri sport", "tvri world")

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
        "bangka belitung" to "Bangka Belitung")

    /** Not Indonesian channels, or pay-TV/operator brands: kept, but last. */
    private val FOREIGN = Regex("""\((taiwan)\)|rtm asean|axn|^dens |^biznet |^my (cinema|family)""", RegexOption.IGNORE_CASE)

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

    fun classify(c: M3u.Channel, near: List<String>): Entry {
        val name = cleanName(c.name)
        val l = name.lowercase()
        val group = c.group?.lowercase().orEmpty()
        val nat = NATIONAL.indexOf(l).takeIf { it >= 0 }
        val isNear = near.any { it in l }
        val cat = when {
            FOREIGN.containsMatchIn(c.name) -> Category.Other // raw name: cleanName drops "(Taiwan)"
            nat != null -> Category.National
            l.startsWith("tvri ") -> Category.Tvri
            "religi" in group -> Category.Religious // "Religious", "Religious Channels", "RELIGION"
            "kids" in group -> Category.Kids
            "news" in group || "business" in group || "legislative" in group -> Category.News
            group.isEmpty() || group == "general" || group == "undefined" || "culture" in group || "education" in group -> Category.Local
            else -> Category.Other
        }
        return Entry(c, name, cat, isNear, "Not 24/7" in c.name, "Geo-blocked" in c.name, nat ?: 1000)
    }

    /** Classify, merge duplicates (best stream first: 24/7, then no special headers; the rest become backups),
     *  and sort near → category → rank → name. */
    fun arrange(channels: List<M3u.Channel>, province: String?): List<Entry> {
        val near = nearKeywords(province)
        return channels.map { classify(it, near) }
            .groupBy { it.name.lowercase() }.values.map { dupes ->
                val sorted = dupes.sortedWith(compareBy({ it.partTime }, { it.channel.headers.isNotEmpty() }))
                sorted.first().copy(alternates = sorted.drop(1).map { it.channel })
            }
            .sortedWith(compareBy({ !it.near }, { it.category.ordinal }, { it.rank }, { it.partTime }, { it.name.lowercase() }))
    }

    fun inCategory(e: Entry, c: Category) = if (c == Category.Near) e.near else e.category == c
}
