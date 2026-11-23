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
        Other("Lainnya");

        /** Category name in [lang] ("en", "id", "zh"). */
        fun label(lang: String): String = OnboardingText.category(lang, this)
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

    private val CLEAN_BRACKETS = Regex("""\s*\[[^]]*]""")
    private val CLEAN_PARENS = Regex("""\s*\([^)]*\)\)?""")
    private val CLEAN_QUALITY = Regex("""\s+(HD|FHD|SD)\b""", RegexOption.IGNORE_CASE)
    private val CLEAN_CAD = Regex("""\s+cad$""", RegexOption.IGNORE_CASE)
    /** "RCTI R+" is the same RCTI, just carried by the R+ service: a trailing "R+" is not part of the channel's name. */
    private val CLEAN_RPLUS = Regex("""\s+R\+$""", RegexOption.IGNORE_CASE)

    /** "TVRI East Java (720p) [Not 24/7]" → "TVRI Jawa Timur"; "RCTI HD (R+)" → "RCTI"; "Trans 7 TV" → "Trans7". */
    fun cleanName(raw: String): String {
        var n = raw.replace(CLEAN_BRACKETS, "").replace(CLEAN_PARENS, "")
            .replace(CLEAN_QUALITY, "").replace(CLEAN_CAD, "").replace(CLEAN_RPLUS, "").trim()
        if (n.startsWith("TVRI ", true)) TVRI_ID[n.substring(5).lowercase()]?.let { n = "TVRI $it" }
        return ALIAS[n.lowercase()] ?: n
    }

    /** Movies and on-demand files: a finished playlist is normal for these, unlike a live channel. */
    fun isVod(c: M3u.Channel): Boolean {
        val group = c.group?.lowercase().orEmpty(); val url = c.url.substringBefore('?').lowercase(); val l = cleanName(c.name).lowercase()
        return group.contains("vod") || url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi") ||
            url.contains(".mov") || url.contains(".webm") || l.startsWith("vod ") || group.contains("film on demand")
    }

    @JvmOverloads
    fun classify(c: M3u.Channel, near: List<String>, home: Boolean = true): Entry {
        val name = cleanName(c.name)
        val l = name.lowercase()
        val group = c.group?.lowercase().orEmpty()
        val nat = if (home) NATIONAL.indexOf(l).takeIf { it >= 0 } else null
        val isNear = near.isNotEmpty() && near.any { it in l }

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
            home && FOREIGN.containsMatchIn(c.name) -> Category.Other
            isVod -> Category.Vod
            nat != null -> Category.National
            !home && ("national" in group || "public" in group) -> Category.National
            isSports -> Category.Sports
            isMovies -> Category.Movies
            home && l.startsWith("tvri ") -> Category.Tvri
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
    enum class SortMode(val key: String) {
        Popularity("popularity"),
        NameAsc("asc"),
        NameDesc("desc");

        fun label(lang: String): String = I18n.get(lang, when (this) {
            Popularity -> "sort.popularity"
            NameAsc -> "sort.asc"
            NameDesc -> "sort.desc"
        })

        companion object {
            @JvmStatic
            fun fromKey(k: String?): SortMode = when (k?.trim()?.lowercase()) {
                "asc", "a-z", "az" -> NameAsc
                "desc", "z-a", "za" -> NameDesc
                else -> Popularity
            }
        }
    }

    private val MERGE_NOISE = Regex("""\b(fhd|uhd|4k|hd|sd|ld|lq|backup|alt|server\s*\d+|feed\s*\d+|ch\s*\d+|drm|dash|hls)\b""", RegexOption.IGNORE_CASE)
    private val MERGE_NONALNUM = Regex("[^a-z0-9\\s]")
    private val MERGE_SPACE = Regex("\\s+")

    private fun canonicalMergeKey(name: String): String =
        MERGE_NOISE.replace(name.lowercase(), " ")
            .replace(MERGE_NONALNUM, " ")
            .replace(MERGE_SPACE, " ")
            .trim()
            .ifBlank { name.lowercase().trim() }

    private fun packedBigrams(s: String): IntArray {
        if (s.length < 2) return intArrayOf(s.hashCode())
        val raw = IntArray(s.length - 1)
        for (i in 0 until s.length - 1) raw[i] = (s[i].code shl 16) or (s[i + 1].code and 0xFFFF)
        raw.sort()
        var unique = 1
        for (i in 1 until raw.size) if (raw[i] != raw[i - 1]) raw[unique++] = raw[i]
        return if (unique == raw.size) raw else raw.copyOf(unique)
    }

    private fun sortedJaccard(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        var i = 0; var j = 0; var inter = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { inter++; i++; j++ }
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private val BRAND_SLUGS: List<Pair<String, Regex>> = listOf(
        "sctv" to Regex("""(?<![a-z0-9])sctv(?![a-z0-9])"""),
        "indosiar" to Regex("""(?<![a-z0-9])indosiar(?![a-z0-9])"""),
        "moji" to Regex("""(?<![a-z0-9])moji(?![a-z0-9])"""),
        "rcti" to Regex("""(?<![a-z0-9])rcti(?!plus)(?![a-z0-9])"""),
        "mnctv" to Regex("""(?<![a-z0-9])mnctv(?![a-z0-9])"""),
        "gtv" to Regex("""(?<![a-z0-9])(globaltv|gtv)(?![a-z0-9])"""),
        "trans tv" to Regex("""(?<![a-z0-9])transtv(?![a-z0-9])"""),
        "trans7" to Regex("""(?<![a-z0-9])trans7(?![a-z0-9])"""),
        "antv" to Regex("""(?<![a-z0-9])antv(?![a-z0-9])"""),
        "tvone" to Regex("""(?<![a-z0-9])tvone(?![a-z0-9])"""),
    )

    private val BRAND_SLUG_MAP: Map<String, Regex> = BRAND_SLUGS.toMap()

    /** Rejects known mislabeled channel streams (e.g. DensTV h207 carrying Moji tagged as Indosiar, #33). */
    @JvmStatic
    fun isMismatchedSource(channelName: String, url: String): Boolean {
        val norm = cleanName(channelName).lowercase()
        val expected = BRAND_SLUG_MAP[norm] ?: return false
        val u = url.substringBefore('?').lowercase()
        if (norm == "indosiar" && "dens.tv" in u && Regex("""/h207(/|\.|$)""").containsMatchIn(u)) return true
        if (expected.containsMatchIn(u)) return false
        return BRAND_SLUGS.any { it.first != norm && it.second.containsMatchIn(u) }
    }

    /** Legacy dead/stale endpoints from #33 (SCTV timeout IP / stale DensTV feeds) ranked after healthy backups. */
    @JvmStatic
    fun isDeprioritizedSource(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        return "202.150.172.59" in u || ("dens.tv" in u && ("/h217" in u || "/h207" in u))
    }

    /** Ranks stream stability: lower return value means higher stability & minimal buffering. */
    @JvmStatic
    fun streamStabilityRank(channel: M3u.Channel): Double {
        val url = channel.url
        if (SourceHealth.isBanned(url)) return 10_000.0
        if (isDeprioritizedSource(url)) return 5_000.0
        val st = SourceHealth.status(url)
        if (st != null) {
            if (!st.live) return 8_000.0
            val scorePenalty = if (st.score > 0.0) (100.0 - st.score) else 50.0
            val latencyPenalty = (st.ms.coerceIn(1L, 5000L)).toDouble() / 100.0
            val tightPenalty = if (st.tightWindow) 10.0 else 0.0
            return scorePenalty + latencyPenalty + tightPenalty
        }
        val u = url.lowercase()
        var base = 200.0
        if ("cnnindonesia.com" in u || "detik.com" in u || "tvri.go.id" in u || "kompas.tv" in u) base -= 50.0
        if ("pages.dev" in u || "inisemar" in u) base -= 30.0
        if (channel.isDash || channel.manifestType.equals("dash", ignoreCase = true)) base -= 20.0
        if (channel.headers.isNotEmpty()) base -= 10.0
        return base
    }

    @JvmOverloads
    fun arrange(
        channels: List<M3u.Channel>,
        province: String?,
        countries: List<String> = listOf(Region.HOME),
        sortMode: SortMode = SortMode.Popularity,
        city: String? = null,
        viewerCountry: String? = null,
    ): List<Entry> {
        val normCountries = countries.mapNotNull { Region.country(it) }.distinct().ifEmpty { listOf(Region.HOME) }
        val home = Region.HOME in normCountries
        val near = CountryCatalog.localKeywords(viewerCountry ?: normCountries.firstOrNull(), province, city)
        val exactBuckets = channels
            .filterNot { isMismatchedSource(it.name, it.url) }
            .map {
                val e = classify(it, near, home)
                val spaceKey = canonicalMergeKey(e.name).replace(" ", "")
                e to spaceKey
            }.groupBy { "${it.second}|${it.first.category.name}" }
            .values
            .map { dupes ->
                val sorted = dupes.sortedWith(
                    compareBy<Pair<Entry, String>>(
                        { it.first.partTime },
                        { streamStabilityRank(it.first.channel) },
                    )
                )
                val first = sorted.first()
                first.first.copy(alternates = sorted.drop(1).map { it.first.channel }) to first.second
            }

        // Second pass: O(N) bigram inverted-index fuzzy merge for near-duplicate names within the same category.
        val merged = ArrayList<Entry>(exactBuckets.size)
        val mergedKeys = ArrayList<String>(exactBuckets.size)
        val mergedBigrams = ArrayList<IntArray>(exactBuckets.size)
        val mergedSig1 = ArrayList<Long>(exactBuckets.size)
        val mergedSig2 = ArrayList<Long>(exactBuckets.size)
        val indexByCategory = HashMap<Int, HashMap<Int, MutableList<Int>>>()
        val seenStamp = IntArray(exactBuckets.size)
        var stamp = 0

        for ((item, kItem) in exactBuckets) {
            val catOrd = item.category.ordinal
            val itemBg = packedBigrams(kItem)
            var itemSig1 = 0L
            var itemSig2 = 0L
            for (bg in itemBg) {
                itemSig1 = itemSig1 or (1L shl ((bg * -1640531535) ushr 26))
                itemSig2 = itemSig2 or (1L shl ((bg * -2048144777) ushr 26))
            }
            var matchIdx = -1
            if (kItem.length >= 6) {
                val catIdx = indexByCategory[catOrd]
                if (catIdx != null) {
                    stamp++
                    val n = itemBg.size
                    val probeCount = minOf(n, (n / 10) + 1)
                    // Probe the rarest bigrams first so shared common prefixes don't degrade to O(N^2).
                    val bestIdx = IntArray(probeCount)
                    val bestSize = IntArray(probeCount) { Int.MAX_VALUE }
                    var filled = 0
                    for (i in 0 until n) {
                        val sz = catIdx[itemBg[i]]?.size ?: 0
                        if (filled < probeCount) {
                            var pos = filled++
                            while (pos > 0 && bestSize[pos - 1] > sz) {
                                bestSize[pos] = bestSize[pos - 1]
                                bestIdx[pos] = bestIdx[pos - 1]
                                pos--
                            }
                            bestSize[pos] = sz
                            bestIdx[pos] = i
                        } else if (sz < bestSize[probeCount - 1]) {
                            var pos = probeCount - 1
                            while (pos > 0 && bestSize[pos - 1] > sz) {
                                bestSize[pos] = bestSize[pos - 1]
                                bestIdx[pos] = bestIdx[pos - 1]
                                pos--
                            }
                            bestSize[pos] = sz
                            bestIdx[pos] = i
                        }
                        if (filled == probeCount && bestSize[probeCount - 1] == 0) break
                    }
                    var bestSim = 0.899999
                    val invItem1 = itemSig1.inv()
                    val invItem2 = itemSig2.inv()
                    for (p in 0 until probeCount) {
                        val posting = catIdx[itemBg[bestIdx[p]]] ?: continue
                        for (ci in 0 until posting.size) {
                            val cand = posting[ci]
                            if (seenStamp[cand] == stamp) continue
                            seenStamp[cand] = stamp
                            val kEx = mergedKeys[cand]
                            if (minOf(kEx.length, kItem.length) < 6) continue
                            val exBg = mergedBigrams[cand]
                            if (maxOf(exBg.size, itemBg.size) * 9 > minOf(exBg.size, itemBg.size) * 10) continue
                            val exSig1 = mergedSig1[cand]
                            val exSig2 = mergedSig2[cand]
                            val missA = maxOf(
                                java.lang.Long.bitCount(exSig1 and invItem1),
                                java.lang.Long.bitCount(exSig2 and invItem2),
                            )
                            val missB = maxOf(
                                java.lang.Long.bitCount(itemSig1 and exSig1.inv()),
                                java.lang.Long.bitCount(itemSig2 and exSig2.inv()),
                            )
                            val interUpper = minOf(exBg.size - missA, itemBg.size - missB)
                            val unionLower = maxOf(itemBg.size + missA, exBg.size + missB)
                            if (interUpper * 10 < unionLower * 9) continue
                            val sim = sortedJaccard(exBg, itemBg)
                            if (sim >= 0.90 && sim > bestSim) {
                                bestSim = sim
                                matchIdx = cand
                            }
                        }
                    }
                }
            }
            if (matchIdx >= 0) {
                val prev = merged[matchIdx]
                val combined = (listOf(prev.channel) + prev.alternates + listOf(item.channel) + item.alternates)
                    .distinctBy { it.url }
                val sortedStreams = combined.sortedWith(
                    compareBy(
                        { it.name.contains("Not 24/7", ignoreCase = true) },
                        { streamStabilityRank(it) },
                    )
                )
                val bestStream = sortedStreams.first()
                val primary = if (bestStream.url == prev.channel.url) prev else item.copy(channel = bestStream)
                merged[matchIdx] = primary.copy(channel = bestStream, alternates = sortedStreams.drop(1))
            } else {
                val idx = merged.size
                merged += item
                mergedKeys += kItem
                mergedBigrams += itemBg
                mergedSig1 += itemSig1
                mergedSig2 += itemSig2
                if (kItem.length >= 6) {
                    val catIdx = indexByCategory.getOrPut(catOrd) { HashMap() }
                    for (bg in itemBg) catIdx.getOrPut(bg) { ArrayList(2) }.add(idx)
                }
            }
        }
        return sort(merged, sortMode, normCountries)
    }

    private class PopSortKey(
        val e: Entry,
        val cRank: Int,
        val negPop: Double,
        val lower: String,
    )

    private class NameSortKey(
        val e: Entry,
        val cRank: Int,
        val lower: String,
    )

    @JvmStatic
    @JvmOverloads
    fun sort(
        entries: List<Entry>,
        sortMode: SortMode = SortMode.Popularity,
        countries: List<String> = listOf(Region.HOME),
    ): List<Entry> {
        if (entries.size <= 1) return entries.toList()
        val normCountries = countries.mapNotNull { Region.country(it) }.distinct().ifEmpty { listOf(Region.HOME) }
        val countryOrder = normCountries.withIndex().associate { (i, c) -> c to i }
        fun countryRank(cc: String?): Int =
            if (cc.isNullOrBlank()) 0 else countryOrder[cc.uppercase()] ?: normCountries.size
        // Precompute rank/popularity/lowercase once per entry so TimSort comparisons stay O(1).
        return when (sortMode) {
            SortMode.NameAsc -> entries.map { NameSortKey(it, countryRank(it.channel.countryCode), it.name.lowercase()) }
                .sortedWith(compareBy({ it.cRank }, { it.lower }, { it.e.partTime }))
                .map { it.e }
            SortMode.NameDesc -> entries.map { NameSortKey(it, countryRank(it.channel.countryCode), it.name.lowercase()) }
                .sortedWith(
                    compareBy<NameSortKey> { it.cRank }
                        .thenByDescending { it.lower }
                        .thenBy { it.e.partTime }
                )
                .map { it.e }
            SortMode.Popularity -> entries.map {
                PopSortKey(
                    e = it,
                    cRank = countryRank(it.channel.countryCode),
                    negPop = -PopularityScraper.score(it.name, normCountries, it.category),
                    lower = it.name.lowercase(),
                )
            }.sortedWith(
                compareBy<PopSortKey>(
                    { it.cRank },
                    { it.e.rank >= PRIORITY.size },
                    { !it.e.near },
                    { if (it.e.rank < PRIORITY.size) it.e.rank else Int.MAX_VALUE },
                    { it.negPop },
                    { it.e.category.ordinal },
                    { it.e.partTime },
                    { it.e.channel.logo.isNullOrBlank() },
                    { it.lower },
                )
            ).map { it.e }
        }
    }

    fun inCategory(e: Entry, c: Category) = if (c == Category.Near) e.near else e.category == c
}
