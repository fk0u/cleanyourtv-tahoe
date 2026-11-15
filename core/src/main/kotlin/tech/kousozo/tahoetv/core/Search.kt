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
            val channelSim = bestFuzzyMatch(q, words, name)
            when {
                name.startsWith(q) -> Triple(0, 1.0, Hit(e, null))
                all(e.name) -> Triple(1, 1.0, Hit(e, null))
                all(e.channel.group.orEmpty() + " " + e.category.label) -> Triple(2, 1.0, Hit(e, null))
                else -> {
                    val show = epg?.schedule(e.name, now, e.channel.tvgId).orEmpty().take(12) // about the next day
                        .firstOrNull { all(it.title) || (it.desc != null && all(it.desc)) }
                    if (show != null) {
                        Triple(if (show.start <= now) 3 else 4, 1.0, Hit(e, show))
                    } else if (q.length >= 3 && channelSim >= 0.68) {
                        Triple(5, channelSim, Hit(e, null))
                    } else {
                        null
                    }
                }
            }
        }
        return ranked.sortedWith(
            compareBy<Triple<Int, Double, Hit>>({ it.first }, { -it.second }, { it.third.programme?.start ?: Instant.EPOCH })
        ).map { it.third }
    }

    private fun bestFuzzyMatch(q: String, words: List<String>, foldedName: String): Double {
        if (q.length < 3 || foldedName.isBlank()) return 0.0
        val whole = maxOf(StreamSelector.fuzzySimilarity(q, foldedName), editSim(q, foldedName))
        val tokens = foldedName.split(Regex("[\\s\\-_]+")).filter { it.length >= 3 }
        if (tokens.isEmpty()) return whole
        val perWord = words.filter { it.length >= 3 }.map { w ->
            tokens.maxOfOrNull { tok -> maxOf(StreamSelector.fuzzySimilarity(w, tok), editSim(w, tok)) } ?: 0.0
        }
        val tokenAvg = if (perWord.isNotEmpty()) perWord.average() else 0.0
        return maxOf(whole, tokenAvg)
    }

    private fun editSim(a: String, b: String): Double {
        val m = a.length; val n = b.length
        val maxLen = maxOf(m, n)
        if (maxLen == 0) return 1.0
        if (kotlin.math.abs(m - n) > 4 || maxLen > 32) return 0.0
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return 1.0 - (prev[n].toDouble() / maxLen)
    }

    private val MARKS = Regex("\\p{Mn}+")
    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
}
