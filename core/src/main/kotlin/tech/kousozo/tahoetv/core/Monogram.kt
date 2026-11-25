package tech.kousozo.tahoetv.core

/** The letters shown in place of a channel logo that is missing or not loaded yet. Several channels of one broadcaster
 *  ("TVRI", "TVRI Sport", "TVRI World") used to all show "TVR"; now the words tell them apart: TVR, TS, TW. */
object Monogram {
    @JvmStatic fun of(name: String?): String {
        var words = (name ?: "").trim().split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotEmpty() }
        if (words.size > 1 && words[0].lowercase() in setOf("the", "a")) words = words.drop(1) // "The Indonesia Channel" -> IC
        return when {
            words.isEmpty() -> ""
            words.size == 1 -> words[0].take(3).uppercase()            // TVRI -> TVR, MN+ -> MN+
            else -> words.take(3).joinToString("") { it.take(1) }.uppercase() // TVRI Sport -> TS, Cartoon Network -> CN
        }
    }
}
