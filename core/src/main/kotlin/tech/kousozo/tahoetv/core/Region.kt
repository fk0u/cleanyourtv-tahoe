package tech.kousozo.tahoetv.core

import java.util.Locale

/** Where the viewer is and which language they read: picks the channel list, the guide, and the regional features.
 *  Shared by the TV, phone and desktop first-run setup (#71). Stored by each app as two short codes:
 *  `country` (ISO 3166 alpha-2, e.g. "ID", "US") and `language` (ISO 639-1, e.g. "id", "en"). */
object Region {
    /** App languages with a translation. Others fall back to English. */
    val LANGUAGES = listOf("en", "id")

    /** The country whose channels have extra curated sources and features (prayer times on by default, "near you"). */
    const val HOME = "ID"

    data class Country(val code: String, val name: String, val flag: String)

    /** Every country, named in [language] (the system's own country names), sorted by that name. */
    @JvmStatic fun countries(language: String = "en"): List<Country> {
        val inLang = Locale.forLanguageTag(language)
        val order = java.text.Collator.getInstance(inLang) // "Åland" sorts with A, not after Z
        return Locale.getISOCountries().map { cc -> Country(cc, Locale("", cc).getDisplayCountry(inLang).ifBlank { cc }, flag(cc)) }
            .sortedWith(compareBy(order) { it.name })
    }

    /** The country's name in [language], e.g. name("ID", "en") = "Indonesia". */
    @JvmStatic fun name(country: String, language: String = "en"): String =
        Locale("", country).getDisplayCountry(Locale.forLanguageTag(language)).ifBlank { country }

    /** 🇮🇩 from "ID": two regional-indicator symbols. */
    @JvmStatic fun flag(country: String): String {
        val cc = country.uppercase()
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🏳️"
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    /** A valid country code, or null. */
    @JvmStatic fun country(code: String?): String? = code?.trim()?.uppercase()?.takeIf { it in Locale.getISOCountries() }

    /** A supported app language for [code] (any form: "id", "in", "id-ID", "en_US"), English if we don't have it. */
    @JvmStatic fun language(code: String?): String {
        val l = code?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase().orEmpty()
        val norm = if (l == "in") "id" else l // old Java/Android code for Indonesian
        return norm.takeIf { it in LANGUAGES } ?: "en"
    }

    /** First-run suggestion from the device's own settings (always editable). */
    @JvmStatic fun suggest(locale: Locale = Locale.getDefault()): Pair<String, String> =
        (country(locale.country) ?: HOME) to language(locale.language)

    /** Prayer times / adhan shown without asking: on in Muslim-majority countries, opt-in elsewhere. */
    private val MUSLIM_MAJORITY = setOf(
        "AF", "AL", "DZ", "AZ", "BH", "BD", "BN", "BF", "TD", "KM", "DJ", "EG", "GM", "GN", "ID", "IR", "IQ", "JO", "KZ", "XK", "KW", "KG",
        "LB", "LY", "MY", "MV", "ML", "MR", "MA", "NE", "NG", "OM", "PK", "PS", "QA", "SA", "SN", "SL", "SO", "SD", "SY", "TJ",
        "TN", "TR", "TM", "AE", "UZ", "EH", "YE")
    @JvmStatic fun prayerByDefault(country: String) = country.uppercase() in MUSLIM_MAJORITY

    // ------------------------------------------------------------------ channels & guides per country

    /** iptv-org's playlist of a country's channels. */
    @JvmStatic fun countryPlaylist(country: String) = "https://iptv-org.github.io/iptv/countries/${country.lowercase()}.m3u"

    /** The built-in channel sources for [country]: Indonesia keeps its curated extra sources; everywhere else uses
     *  iptv-org's country list, which [Sources.loadAll] passes through the free-TV filter. */
    @JvmStatic fun sources(country: String): List<Sources.Source> =
        if (country.equals(HOME, true)) Sources.DEFAULT else listOf(Sources.Source("iptv-org", countryPlaylist(country)))

    /** Guides for [country], in order of preference (epg.pw publishes one XMLTV file per country). */
    @JvmStatic fun guides(country: String): List<String> =
        if (country.equals(HOME, true)) Sources.EPG else listOf("https://epg.pw/xmltv/epg_${country.uppercase()}.xml")
}
