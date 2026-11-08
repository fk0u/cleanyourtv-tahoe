package tech.kousozo.tahoetv.core

/** Sorts an Indonesian playlist (iptv-org) the way people here watch TV: national free-to-air first, then TVRI's
 *  regional stations and local TV, with "near you" picked by province. iptv-org's own groups are too coarse for that. */
object LocalTv {
    enum class Category(val label: String) {
        Near("Dekat kamu"), National("Nasional"), Tvri("TVRI Daerah"), Local("TV Lokal"),
        News("Berita"), Religious("Religi"), Kids("Anak"), Other("Lainnya"),
    }

    data class Entry(val channel: M3u.Channel, val name: String, val category: Category, val near: Boolean,
                     val partTime: Boolean, val geoBlocked: Boolean, val rank: Int)

    /** National free-to-air in the usual remote-button order; index = rank. */
    private val NATIONAL = listOf("tvri", "rcti", "sctv", "indosiar", "antv", "gtv", "mnctv", "inews", "metro tv", "tvone",
        "kompas tv", "trans tv", "trans7", "net", "rtv", "moji", "mdtv", "nusantara tv", "garuda tv", "btv", "cnn indonesia",
        "cnbc indonesia", "beritasatu", "jak tv", "tvri sport", "tvri world")

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
        return PROVINCE.firstOrNull { it.key in p }?.value.orEmpty()
    }

    /** "TVRI East Java (720p) [Not 24/7]" → "TVRI East Java". */
    fun cleanName(raw: String) = raw.replace(Regex("""\s*\[[^]]*]"""), "").replace(Regex("""\s*\(\d+[pi]\)"""), "")
        .replace(" (Indonesia)", "").replace(Regex("""\s+HD$"""), "").trim()

    fun classify(c: M3u.Channel, near: List<String>): Entry {
        val name = cleanName(c.name)
        val l = name.lowercase()
        val group = c.group?.lowercase().orEmpty()
        val nat = NATIONAL.indexOf(l).takeIf { it >= 0 }
        val isNear = near.any { it in l }
        val cat = when {
            FOREIGN.containsMatchIn(name) -> Category.Other
            nat != null -> Category.National
            l.startsWith("tvri ") -> Category.Tvri
            "religious" in group -> Category.Religious
            "kids" in group -> Category.Kids
            "news" in group || "business" in group || "legislative" in group -> Category.News
            group.isEmpty() || group == "general" || group == "undefined" || "culture" in group || "education" in group -> Category.Local
            else -> Category.Other
        }
        return Entry(c, name, cat, isNear, "Not 24/7" in c.name, "Geo-blocked" in c.name, nat ?: 1000)
    }

    /** Classify, drop duplicate names (keep the better stream: not part-time), and sort near → category → rank → name. */
    fun arrange(channels: List<M3u.Channel>, province: String?): List<Entry> {
        val near = nearKeywords(province)
        return channels.map { classify(it, near) }
            .groupBy { it.name.lowercase() }.values.map { dupes -> dupes.minBy { if (it.partTime) 1 else 0 } }
            .sortedWith(compareBy({ !it.near }, { it.category.ordinal }, { it.rank }, { it.partTime }, { it.name.lowercase() }))
    }

    fun inCategory(e: Entry, c: Category) = if (c == Category.Near) e.near else e.category == c
}
