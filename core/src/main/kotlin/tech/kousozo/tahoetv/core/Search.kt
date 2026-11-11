package tech.kousozo.tahoetv.core

import java.time.Instant

/** One search over channel names, groups and the programme guide ("bola", "berita", a show's title), shared by the
 *  phone and desktop apps. A channel matched by a show says which show and when it's on. */
object Search {
    /** `programme`: the show that matched (null = the channel itself matched). */
    data class Hit(val entry: LocalTv.Entry, val programme: Epg.Programme?)

    /** Hits best first: name starts with the query, name contains it, group/category, show on now, show later today.
     *  Accents and case are ignored; every word of the query must match. */
    fun run(entries: List<LocalTv.Entry>, epg: Epg?, query: String, now: Instant = Instant.now()): List<Hit> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return entries.map { Hit(it, null) }
        fun all(s: String) = fold(s).let { t -> words.all { it in t } }
        val q = fold(query).trim()
        val ranked = entries.mapNotNull { e ->
            val name = fold(e.name)
            when {
                name.startsWith(q) -> 0 to Hit(e, null)
                all(e.name) -> 1 to Hit(e, null)
                all(e.channel.group.orEmpty() + " " + e.category.label) -> 2 to Hit(e, null)
                else -> {
                    val show = epg?.schedule(e.name, now).orEmpty().take(12) // about the next day
                        .firstOrNull { all(it.title) || (it.desc != null && all(it.desc)) } ?: return@mapNotNull null
                    (if (show.start <= now) 3 else 4) to Hit(e, show)
                }
            }
        }
        return ranked.sortedWith(compareBy({ it.first }, { it.second.programme?.start ?: Instant.EPOCH })).map { it.second }
    }

    private val MARKS = Regex("\\p{Mn}+")
    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
}
