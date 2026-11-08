package tech.kousozo.tahoetv.core

/** Minimal M3U/M3U8 playlist parser for IPTV lists (#EXTINF with tvg-logo / group-title attributes). */
object M3u {
    data class Channel(val name: String, val url: String, val logo: String?, val group: String?)

    /** Public, community-maintained list of freely available Indonesian channels (iptv-org). Users can paste their own. */
    const val DEFAULT = "https://iptv-org.github.io/iptv/countries/id.m3u"

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> info = line
                line.isEmpty() || line.startsWith("#") -> {}
                info != null -> {
                    val attrs = attr.findAll(info.substringBefore(',', info)).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = info.substringAfterLast(',').trim().ifEmpty { attrs["tvg-name"] ?: line }
                    out += Channel(name.replace(Regex("\\s*\\(\\d+p\\).*$"), ""), line, attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        attrs["group-title"]?.takeIf { it.isNotBlank() }?.substringBefore(';'))
                    info = null
                }
            }
        }
        return out.distinctBy { it.url }
    }

    fun load(url: String) = parse(Web.get(url, 20_000))
}
