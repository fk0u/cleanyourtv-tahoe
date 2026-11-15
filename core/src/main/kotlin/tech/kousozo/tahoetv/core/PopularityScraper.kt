package tech.kousozo.tahoetv.core

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln

/**
 * Scrapes and caches real-world TV channel popularity per country from public internet sources
 * (Wikipedia station lists + Wikimedia 14-day article pageview counts, plus Wikipedia search fallback),
 * seeded with curated major national networks for instant cold-start ordering.
 */
object PopularityScraper {
    private const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000
    private const val NEGATIVE_RETRY_MS = 30L * 60 * 1000

    internal data class CountryCache(
        val scores: Map<String, Double>, // normKey -> score (higher = more popular)
        val rankedNames: List<String>,
        val updatedAt: Long,
        val fromNetwork: Boolean,
    )

    private val memory = ConcurrentHashMap<String, CountryCache>()
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val worker = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "tahoe-popularity").apply { isDaemon = true }
    }

    @Volatile
    private var cacheDir: File? = null

    /** Test hook for deterministic HTTP scraping unit tests. */
    @Volatile
    internal var httpGet: (String) -> String = { url -> Web.get(url, 8_000) }

    private val SEED_RANKINGS: Map<String, List<String>> = mapOf(
        "ID" to listOf(
            "RCTI", "SCTV", "Indosiar", "Trans TV", "Trans7", "MNCTV", "GTV", "ANTV",
            "Metro TV", "tvOne", "Kompas TV", "iNews", "RTV", "NET.", "MDTV", "BTV",
            "CNN Indonesia", "CNBC Indonesia", "TVRI Nasional", "Moji", "Mentari TV",
            "Nusantara TV", "Garuda TV", "Jak TV", "Elshinta TV", "BeritaSatu",
        ),
        "US" to listOf(
            "NBC", "CBS", "ABC", "FOX", "PBS", "CNN", "Fox News", "MSNBC",
            "ESPN", "CNBC", "Bloomberg", "ABC News", "CBS News", "NBC News",
            "C-SPAN", "The Weather Channel", "NASA TV", "FOX Weather",
        ),
        "GB" to listOf(
            "BBC One", "BBC Two", "ITV1", "ITV", "Channel 4", "Channel 5",
            "BBC News", "Sky News", "CBBC", "CBeebies", "BBC Four", "E4", "Film4",
        ),
        "JP" to listOf(
            "NHK", "NHK G", "NHK E", "Nippon TV", "TV Asahi", "TBS",
            "TV Tokyo", "Fuji TV", "Tokyo MX", "NHK World", "BS11", "BS-TBS",
        ),
        "KR" to listOf(
            "KBS1", "KBS2", "MBC", "SBS", "EBS1", "JTBC", "tvN",
            "YTN", "Yonhap News TV", "Arirang TV", "MBN", "TV Chosun", "Channel A",
        ),
        "MY" to listOf(
            "TV1", "TV2", "TV3", "TV9", "8TV", "DidikTV", "Astro Awani", "Bernama TV", "TV AlHijrah",
        ),
        "SG" to listOf(
            "Channel 5", "Channel 8", "CNA", "Channel NewsAsia", "Suria", "Vasantham", "Channel U",
        ),
        "AU" to listOf(
            "ABC", "ABC News", "SBS", "Seven Network", "7News", "Nine Network", "9News", "Network 10",
        ),
        "CA" to listOf(
            "CBC", "CTV", "Global News", "CBC News", "CP24", "Citytv", "TVO",
        ),
        "DE" to listOf(
            "Das Erste", "ARD", "ZDF", "RTL", "Sat.1", "ProSieben", "VOX",
            "tagesschau24", "3sat", "Arte", "n-tv", "Welt", "Kabel Eins",
        ),
        "FR" to listOf(
            "TF1", "France 2", "France 3", "France 5", "M6", "Arte",
            "France 24", "BFMTV", "CNews", "LCI",
        ),
        "NL" to listOf(
            "NPO 1", "NPO 2", "NPO 3", "RTL 4", "SBS6", "NOS", "BNNVARA",
        ),
        "IN" to listOf(
            "DD National", "DD News", "Aaj Tak", "ABP News", "NDTV", "Zee News",
            "India Today", "Sony Entertainment", "Colors TV", "Star Plus", "DD India",
        ),
        "TH" to listOf(
            "Channel 3", "Channel 7", "Thai PBS", "MCOT", "One 31", "Thairath TV", "Workpoint TV",
        ),
        "PH" to listOf(
            "GMA", "ABS-CBN", "TV5", "PTV", "ANC", "Net 25", "UNTV",
        ),
        "VN" to listOf(
            "VTV1", "VTV2", "VTV3", "VTV4", "VTV5", "HTV7", "HTV9", "THVL1",
        ),
        "TW" to listOf(
            "TTV", "CTV", "CTS", "FTV", "PTS", "TVBS", "SET", "EBC News", "CTi",
        ),
        "HK" to listOf(
            "TVB Jade", "TVB Pearl", "ViuTV", "RTHK 31", "RTHK 32", "HOY TV", "Phoenix",
        ),
        "CN" to listOf(
            "CCTV-1", "CCTV-2", "CCTV-4", "CCTV-5", "CCTV-6", "CCTV-13", "CGTN", "Hunan TV", "Dragon TV",
        ),
        "SA" to listOf(
            "Al Ekhbariya", "Al Arabiya", "Saudi TV", "SBC", "MBC 1", "Al Quran Al Kareem", "Al Sunnah Al Nabawiyah",
        ),
        "AE" to listOf(
            "Dubai TV", "Abu Dhabi TV", "Sky News Arabia", "Al Arabiya", "Sama Dubai", "Emarat TV",
        ),
        "BR" to listOf(
            "TV Globo", "SBT", "RecordTV", "Band", "RedeTV!", "TV Cultura", "GloboNews",
        ),
        "ES" to listOf(
            "La 1", "La 2", "Antena 3", "Cuatro", "Telecinco", "laSexta", "24h",
        ),
        "IT" to listOf(
            "Rai 1", "Rai 2", "Rai 3", "Canale 5", "Italia 1", "Rete 4", "La7", "Rai News 24",
        ),
        "TR" to listOf(
            "TRT 1", "TRT Haber", "TRT World", "ATV", "Show TV", "Star TV", "Kanal D", "FOX", "NTV",
        ),
    )

    private val STRIP_NONALNUM = Regex("[^a-z0-9]")

    @JvmStatic
    fun initCache(dir: File) {
        cacheDir = File(dir, "popularity").apply { mkdirs() }
    }

    internal fun resetForTest() {
        memory.clear()
        inFlight.clear()
        httpGet = { url -> Web.get(url, 8_000) }
    }

    internal fun norm(name: String): String =
        LocalTv.cleanName(name).lowercase(Locale.US).replace(STRIP_NONALNUM, "")

    @JvmStatic
    fun topChannelNames(country: String, limit: Int = 5): List<String> {
        val cc = Region.country(country) ?: Region.HOME
        val cached = getOrLoadFromDisk(cc)
        val merged = LinkedHashSet<String>()
        cached?.rankedNames?.forEach { if (it.isNotBlank()) merged += it }
        SEED_RANKINGS[cc]?.forEach { merged += it }
        return merged.take(limit)
    }

    /**
     * Popularity score for a channel within the viewer's selected [countries].
     * Combines internet-scraped ranking/pageviews (weighted toward priority country index 0),
     * curated country seeds, category prominence, and known stream health if any.
     */
    @JvmStatic
    @JvmOverloads
    fun score(channelName: String, countries: List<String>, category: LocalTv.Category? = null): Double {
        val clean = LocalTv.cleanName(channelName)
        val key = norm(clean)
        if (key.isEmpty()) return 0.0
        val list = countries.mapNotNull { Region.country(it) }.distinct().ifEmpty { listOf(Region.HOME) }

        var best = 0.0
        list.forEachIndexed { idx, cc ->
            val countryWeight = 1.0 / (1.0 + idx * 0.25)
            val countryBoost = if (idx == 0) 150.0 else maxOf(0.0, 80.0 - idx * 25.0)
            var matched = lookupCountryScore(cc, key)
            if (matched == 0.0) {
                matched = lookupSeedScore(cc, key)
            }
            val total = if (matched > 0.0) countryBoost + matched * countryWeight else countryBoost * 0.35
            if (total > best) best = total
        }

        // Baseline prominence by editorial channel type so unranked national/news feeds beat miscellaneous fillers
        val catBonus = when (category) {
            LocalTv.Category.National -> 120.0
            LocalTv.Category.Tvri -> 85.0
            LocalTv.Category.News -> 65.0
            LocalTv.Category.Sports -> 55.0
            LocalTv.Category.Entertainment -> 35.0
            LocalTv.Category.Movies -> 30.0
            LocalTv.Category.Local -> 20.0
            LocalTv.Category.Kids -> 15.0
            LocalTv.Category.Religious -> 10.0
            else -> 0.0
        }
        return best + catBonus
    }

    /** Triggers background internet scraping for countries whose cache is missing or expired. */
    @JvmStatic
    @JvmOverloads
    fun prefetchAsync(
        countries: List<String>,
        candidateNames: List<String> = emptyList(),
        onUpdated: (() -> Unit)? = null,
    ) {
        val list = countries.mapNotNull { Region.country(it) }.distinct().ifEmpty { listOf(Region.HOME) }
        val now = System.currentTimeMillis()
        list.forEach { cc ->
            val current = getOrLoadFromDisk(cc)
            val stale = current == null ||
                (!current.fromNetwork && now - current.updatedAt > NEGATIVE_RETRY_MS) ||
                (now - current.updatedAt > CACHE_TTL_MS)
            if (!stale) return@forEach
            val gate = inFlight.computeIfAbsent(cc) { AtomicBoolean(false) }
            if (!gate.compareAndSet(false, true)) return@forEach
            worker.execute {
                try {
                    val scraped = scrapeCountry(cc, candidateNames)
                    if (scraped != null && scraped.scores.isNotEmpty()) {
                        memory[cc] = scraped
                        saveToDisk(cc, scraped)
                        onUpdated?.invoke()
                    } else if (current == null) {
                        val seedOnly = buildSeedCache(cc, fromNetwork = false)
                        memory[cc] = seedOnly
                    }
                } catch (_: Throwable) {
                } finally {
                    gate.set(false)
                }
            }
        }
    }

    /** Synchronously scrapes and caches popularity scores for [country] using public Wikipedia / Wikimedia endpoints. */
    internal fun scrapeCountry(country: String, candidateNames: List<String> = emptyList()): CountryCache? {
        val cc = Region.country(country) ?: return null
        val countryNameEn = Region.name(cc, "en")
        val wikiLang = when (cc) {
            "ID" -> "id"
            else -> "en"
        }

        val articleTitles = LinkedHashSet<String>()
        // 1. Always evaluate curated national networks + loaded playlist names on Wikipedia pageviews
        SEED_RANKINGS[cc]?.forEach { articleTitles += it }
        candidateNames.asSequence().map { LocalTv.cleanName(it) }.filter { it.length in 2..40 }.take(35).forEach {
            articleTitles += it
        }

        // 2. Discover additional TV station articles from Wikipedia "List of television stations in <Country>"
        val listQueries = buildList {
            if (cc == "ID") add("id" to "Daftar_stasiun_televisi_di_Indonesia")
            val encCountry = countryNameEn.replace(' ', '_')
            add("en" to "List_of_television_stations_in_$encCountry")
            add("en" to "List_of_television_networks_in_$encCountry")
            add("en" to "Television_in_$encCountry")
        }
        val tvHints = Regex("(?i)(tv|television|televisi|channel|saluran|news|sports|broadcast|network|radio|rcti|sctv|bbc|nbc|cbs|abc|fox|nhk|kbs|sbs|mbc|zdf|ard|tf1)")
        for ((lang, pageTitle) in listQueries) {
            if (articleTitles.size >= 80) break
            val linksUrl = "https://$lang.wikipedia.org/w/api.php?action=query&titles=${encode(pageTitle)}&prop=links&plnamespace=0&pllimit=150&redirects=1&format=json"
            val body = runCatching { httpGet(linksUrl) }.getOrNull().orEmpty()
            if (body.isBlank()) continue
            parseWikiLinks(body).forEach { title ->
                if (tvHints.containsMatchIn(title) || SEED_RANKINGS[cc].orEmpty().any { it.equals(title, true) }) {
                    articleTitles += title
                }
            }
        }

        val combinedScores = HashMap<String, Double>()
        val nameScores = LinkedHashMap<String, Double>()

        // Apply seed priors first
        SEED_RANKINGS[cc].orEmpty().forEachIndexed { idx, name ->
            val prior = 550.0 - idx * 14.0
            val k = norm(name)
            if (k.isNotEmpty()) {
                combinedScores[k] = maxOf(combinedScores[k] ?: 0.0, prior)
                nameScores[name] = maxOf(nameScores[name] ?: 0.0, prior)
            }
        }

        // 3. Batch-fetch real public 14-day Wikipedia article pageviews (up to 40 titles per request)
        val batches = articleTitles.toList().chunked(40).take(2)
        var anyNetworkViews = false
        for (batch in batches) {
            val joined = batch.joinToString("|") { encode(it) }
            val pvUrl = "https://$wikiLang.wikipedia.org/w/api.php?action=query&titles=$joined&prop=pageviews&pvipdays=14&redirects=1&format=json"
            val json = runCatching { httpGet(pvUrl) }.getOrNull().orEmpty()
            if (json.isBlank()) continue
            val views = parseWikiPageviews(json)
            if (views.isNotEmpty()) anyNetworkViews = true
            views.forEach { (title, viewSum) ->
                // Log-scaled popularity boost (e.g. 50,000 views -> ~460 pts, 500 views -> ~270 pts)
                val logScore = if (viewSum > 0L) 80.0 * ln(viewSum.toDouble() + 1.0) else 0.0
                val cleanTitle = cleanWikiTitle(title)
                val k = norm(cleanTitle)
                if (k.isNotEmpty() && logScore > 0.0) {
                    val existing = combinedScores[k] ?: 0.0
                    combinedScores[k] = existing + logScore
                    nameScores[cleanTitle] = maxOf(nameScores[cleanTitle] ?: 0.0, combinedScores[k] ?: 0.0)
                }
            }
        }

        // 4. Fallback: if wiki pageviews yielded nothing, query Wikipedia search relevance ranking
        if (!anyNetworkViews) {
            val q = encode("$countryNameEn television channel network")
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&srlimit=20&format=json"
            val sJson = runCatching { httpGet(searchUrl) }.getOrNull().orEmpty()
            parseWikiSearchTitles(sJson).forEachIndexed { idx, title ->
                val cleanTitle = cleanWikiTitle(title)
                val k = norm(cleanTitle)
                val sScore = 360.0 - idx * 12.0
                if (k.isNotEmpty() && sScore > 0.0) {
                    combinedScores[k] = maxOf(combinedScores[k] ?: 0.0, sScore)
                    nameScores[cleanTitle] = maxOf(nameScores[cleanTitle] ?: 0.0, sScore)
                }
            }
        }

        if (combinedScores.isEmpty()) return null
        val rankedNames = nameScores.entries.sortedByDescending { it.value }.map { it.key }
        return CountryCache(
            scores = combinedScores,
            rankedNames = rankedNames,
            updatedAt = System.currentTimeMillis(),
            fromNetwork = anyNetworkViews || nameScores.isNotEmpty(),
        )
    }

    internal fun parseWikiLinks(json: String): List<String> = runCatching {
        val root = org.json.JSONObject(json)
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<String>()
        pages.keys().forEach { pid ->
            val links = pages.optJSONObject(pid)?.optJSONArray("links") ?: return@forEach
            for (i in 0 until links.length()) {
                val t = links.optJSONObject(i)?.optString("title").orEmpty()
                if (t.isNotBlank()) out += t
            }
        }
        out
    }.getOrDefault(emptyList())

    internal fun parseWikiPageviews(json: String): Map<String, Long> = runCatching {
        val root = org.json.JSONObject(json)
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        pages.keys().forEach { pid ->
            val page = pages.optJSONObject(pid) ?: return@forEach
            val title = page.optString("title").takeIf { it.isNotBlank() } ?: return@forEach
            val pv = page.optJSONObject("pageviews") ?: return@forEach
            var sum = 0L
            pv.keys().forEach { day ->
                if (!pv.isNull(day)) sum += pv.optLong(day, 0L)
            }
            if (sum > 0L) out[title] = sum
        }
        out
    }.getOrDefault(emptyMap())

    internal fun parseWikiSearchTitles(json: String): List<String> = runCatching {
        val root = org.json.JSONObject(json)
        val arr = root.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun cleanWikiTitle(title: String): String =
        title.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim()

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    private fun lookupCountryScore(cc: String, normChannel: String): Double {
        val cache = getOrLoadFromDisk(cc) ?: return 0.0
        cache.scores[normChannel]?.let { return it }
        var best = 0.0
        cache.scores.forEach { (k, v) ->
            if (k.length >= 3 && (normChannel.startsWith(k) || k.startsWith(normChannel))) {
                val penalized = v * 0.88
                if (penalized > best) best = penalized
            }
        }
        return best
    }

    private fun lookupSeedScore(cc: String, normChannel: String): Double {
        val seeds = SEED_RANKINGS[cc] ?: return 0.0
        seeds.forEachIndexed { idx, s ->
            val ks = norm(s)
            if (ks.isNotEmpty() && (normChannel == ks || normChannel.startsWith(ks))) {
                return 550.0 - idx * 14.0
            }
        }
        return 0.0
    }

    private fun buildSeedCache(cc: String, fromNetwork: Boolean): CountryCache {
        val seeds = SEED_RANKINGS[cc].orEmpty()
        val scores = HashMap<String, Double>()
        seeds.forEachIndexed { idx, s ->
            val k = norm(s)
            if (k.isNotEmpty()) scores[k] = 550.0 - idx * 14.0
        }
        return CountryCache(scores, seeds, System.currentTimeMillis(), fromNetwork)
    }

    private fun resolvedDir(): File? = cacheDir ?: runCatching {
        File(System.getProperty("java.io.tmpdir"), "tahoe-popularity-cache").apply { mkdirs() }
    }.getOrNull()

    private fun getOrLoadFromDisk(cc: String): CountryCache? {
        memory[cc]?.let { return it }
        val file = resolvedDir()?.let { File(it, "$cc.tsv") } ?: return null
        if (!file.isFile) return null
        return runCatching {
            val scores = HashMap<String, Double>()
            val ranked = ArrayList<Pair<String, Double>>()
            var updatedAt = file.lastModified()
            file.forEachLine(Charsets.UTF_8) { line ->
                val p = line.split('\t')
                if (p.size >= 4) {
                    val key = p[0]
                    val raw = p[1]
                    val sc = p[2].toDoubleOrNull() ?: return@forEachLine
                    val ts = p[3].toLongOrNull() ?: updatedAt
                    if (key.isNotEmpty()) {
                        scores[key] = sc
                        ranked += raw to sc
                        updatedAt = maxOf(updatedAt, ts)
                    }
                }
            }
            if (scores.isEmpty()) null
            else CountryCache(
                scores = scores,
                rankedNames = ranked.sortedByDescending { it.second }.map { it.first }.distinct(),
                updatedAt = updatedAt,
                fromNetwork = true,
            ).also { memory[cc] = it }
        }.getOrNull()
    }

    @Synchronized
    private fun saveToDisk(cc: String, cache: CountryCache) {
        val dir = resolvedDir() ?: return
        dir.mkdirs()
        val target = File(dir, "$cc.tsv")
        val tmp = File(dir, "$cc.tsv.tmp")
        runCatching {
            val sb = StringBuilder()
            cache.rankedNames.forEach { name ->
                val k = norm(name)
                val sc = cache.scores[k] ?: return@forEach
                sb.append(k).append('\t')
                    .append(name.replace("\t", " ").replace("\n", " ")).append('\t')
                    .append(sc).append('\t')
                    .append(cache.updatedAt).append('\n')
            }
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            tmp.renameTo(target)
        }
    }
}
