package tech.kousozo.tahoetv.core

import java.util.Locale

/** Country-aware metadata: major city suggestions (for quick picks without typing), default time zones,
 *  regional keyword matching beyond Indonesia, and dynamic featured-channel copy in the chosen language. */
object CountryCatalog {
    data class CityOption(
        val city: String,
        val region: String,
        val lat: Double,
        val lon: Double,
        val zoneId: String,
    ) {
        fun label(): String = if (region.isBlank() || region.equals(city, ignoreCase = true)) city else "$city, $region"
    }

    private val CITIES: Map<String, List<CityOption>> = mapOf(
        "ID" to listOf(
            CityOption("Jakarta", "DKI Jakarta", -6.2088, 106.8456, "Asia/Jakarta"),
            CityOption("Surabaya", "Jawa Timur", -7.2575, 112.7521, "Asia/Jakarta"),
            CityOption("Bandung", "Jawa Barat", -6.9175, 107.6191, "Asia/Jakarta"),
            CityOption("Medan", "Sumatera Utara", 3.5952, 98.6722, "Asia/Jakarta"),
            CityOption("Semarang", "Jawa Tengah", -6.9667, 110.4167, "Asia/Jakarta"),
            CityOption("Makassar", "Sulawesi Selatan", -5.1477, 119.4327, "Asia/Makassar"),
            CityOption("Denpasar", "Bali", -8.6705, 115.2126, "Asia/Makassar"),
            CityOption("Balikpapan", "Kalimantan Timur", -1.2379, 116.8529, "Asia/Makassar"),
            CityOption("Yogyakarta", "DI Yogyakarta", -7.7956, 110.3695, "Asia/Jakarta"),
            CityOption("Jayapura", "Papua", -2.5916, 140.6690, "Asia/Jayapura"),
        ),
        "US" to listOf(
            CityOption("New York", "New York", 40.7128, -74.0060, "America/New_York"),
            CityOption("Los Angeles", "California", 34.0522, -118.2437, "America/Los_Angeles"),
            CityOption("Chicago", "Illinois", 41.8781, -87.6298, "America/Chicago"),
            CityOption("Houston", "Texas", 29.7604, -95.3698, "America/Chicago"),
            CityOption("Seattle", "Washington", 47.6062, -122.3321, "America/Los_Angeles"),
            CityOption("Miami", "Florida", 25.7617, -80.1918, "America/New_York"),
            CityOption("San Francisco", "California", 37.7749, -122.4194, "America/Los_Angeles"),
            CityOption("Atlanta", "Georgia", 33.7490, -84.3880, "America/New_York"),
        ),
        "GB" to listOf(
            CityOption("London", "England", 51.5074, -0.1278, "Europe/London"),
            CityOption("Manchester", "England", 53.4808, -2.2426, "Europe/London"),
            CityOption("Birmingham", "England", 52.4862, -1.8904, "Europe/London"),
            CityOption("Edinburgh", "Scotland", 55.9533, -3.1883, "Europe/London"),
            CityOption("Glasgow", "Scotland", 55.8642, -4.2518, "Europe/London"),
            CityOption("Cardiff", "Wales", 51.4816, -3.1791, "Europe/London"),
        ),
        "JP" to listOf(
            CityOption("Tokyo", "Tokyo", 35.6762, 139.6503, "Asia/Tokyo"),
            CityOption("Osaka", "Osaka", 34.6937, 135.5023, "Asia/Tokyo"),
            CityOption("Nagoya", "Aichi", 35.1815, 136.9066, "Asia/Tokyo"),
            CityOption("Fukuoka", "Fukuoka", 33.5902, 130.4017, "Asia/Tokyo"),
            CityOption("Sapporo", "Hokkaido", 43.0618, 141.3545, "Asia/Tokyo"),
            CityOption("Kyoto", "Kyoto", 35.0116, 135.7681, "Asia/Tokyo"),
        ),
        "KR" to listOf(
            CityOption("Seoul", "Seoul", 37.5665, 126.9780, "Asia/Seoul"),
            CityOption("Busan", "Busan", 35.1796, 129.0756, "Asia/Seoul"),
            CityOption("Incheon", "Incheon", 37.4563, 126.7052, "Asia/Seoul"),
            CityOption("Daegu", "Daegu", 35.8714, 128.6014, "Asia/Seoul"),
            CityOption("Jeju", "Jeju", 33.4996, 126.5312, "Asia/Seoul"),
        ),
        "MY" to listOf(
            CityOption("Kuala Lumpur", "WP Kuala Lumpur", 3.1390, 101.6869, "Asia/Kuala_Lumpur"),
            CityOption("George Town", "Penang", 5.4141, 100.3288, "Asia/Kuala_Lumpur"),
            CityOption("Johor Bahru", "Johor", 1.4927, 103.7414, "Asia/Kuala_Lumpur"),
            CityOption("Kuching", "Sarawak", 1.5533, 110.3592, "Asia/Kuala_Lumpur"),
            CityOption("Kota Kinabalu", "Sabah", 5.9804, 116.0735, "Asia/Kuala_Lumpur"),
        ),
        "SG" to listOf(
            CityOption("Singapore", "Singapore", 1.3521, 103.8198, "Asia/Singapore"),
        ),
        "AU" to listOf(
            CityOption("Sydney", "New South Wales", -33.8688, 151.2093, "Australia/Sydney"),
            CityOption("Melbourne", "Victoria", -37.8136, 144.9631, "Australia/Melbourne"),
            CityOption("Brisbane", "Queensland", -27.4698, 153.0251, "Australia/Brisbane"),
            CityOption("Perth", "Western Australia", -31.9505, 115.8605, "Australia/Perth"),
            CityOption("Adelaide", "South Australia", -34.9285, 138.6007, "Australia/Adelaide"),
        ),
        "CA" to listOf(
            CityOption("Toronto", "Ontario", 43.6532, -79.3832, "America/Toronto"),
            CityOption("Vancouver", "British Columbia", 49.2827, -123.1207, "America/Vancouver"),
            CityOption("Montreal", "Quebec", 45.5017, -73.5673, "America/Toronto"),
            CityOption("Calgary", "Alberta", 51.0447, -114.0719, "America/Edmonton"),
            CityOption("Ottawa", "Ontario", 45.4215, -75.6972, "America/Toronto"),
        ),
        "DE" to listOf(
            CityOption("Berlin", "Berlin", 52.5200, 13.4050, "Europe/Berlin"),
            CityOption("Munich", "Bavaria", 48.1351, 11.5820, "Europe/Berlin"),
            CityOption("Hamburg", "Hamburg", 53.5511, 9.9937, "Europe/Berlin"),
            CityOption("Frankfurt", "Hesse", 50.1109, 8.6821, "Europe/Berlin"),
            CityOption("Cologne", "North Rhine-Westphalia", 50.9375, 6.9603, "Europe/Berlin"),
        ),
        "FR" to listOf(
            CityOption("Paris", "Ile-de-France", 48.8566, 2.3522, "Europe/Paris"),
            CityOption("Marseille", "Provence", 43.2965, 5.3698, "Europe/Paris"),
            CityOption("Lyon", "Auvergne-Rhone-Alpes", 45.7640, 4.8357, "Europe/Paris"),
            CityOption("Toulouse", "Occitanie", 43.6047, 1.4442, "Europe/Paris"),
        ),
        "NL" to listOf(
            CityOption("Amsterdam", "North Holland", 52.3676, 4.9041, "Europe/Amsterdam"),
            CityOption("Rotterdam", "South Holland", 51.9244, 4.4777, "Europe/Amsterdam"),
            CityOption("Utrecht", "Utrecht", 52.0907, 5.1214, "Europe/Amsterdam"),
        ),
        "IN" to listOf(
            CityOption("Mumbai", "Maharashtra", 19.0760, 72.8777, "Asia/Kolkata"),
            CityOption("New Delhi", "Delhi", 28.6139, 77.2090, "Asia/Kolkata"),
            CityOption("Bengaluru", "Karnataka", 12.9716, 77.5946, "Asia/Kolkata"),
            CityOption("Chennai", "Tamil Nadu", 13.0827, 80.2707, "Asia/Kolkata"),
            CityOption("Hyderabad", "Telangana", 17.3850, 78.4867, "Asia/Kolkata"),
        ),
        "TH" to listOf(
            CityOption("Bangkok", "Bangkok", 13.7563, 100.5018, "Asia/Bangkok"),
            CityOption("Chiang Mai", "Chiang Mai", 18.7883, 98.9853, "Asia/Bangkok"),
            CityOption("Phuket", "Phuket", 7.8804, 98.3923, "Asia/Bangkok"),
        ),
        "PH" to listOf(
            CityOption("Manila", "Metro Manila", 14.5995, 120.9842, "Asia/Manila"),
            CityOption("Cebu City", "Cebu", 10.3157, 123.8854, "Asia/Manila"),
            CityOption("Davao City", "Davao", 7.1907, 125.4553, "Asia/Manila"),
        ),
        "VN" to listOf(
            CityOption("Ho Chi Minh City", "Ho Chi Minh", 10.8231, 106.6297, "Asia/Ho_Chi_Minh"),
            CityOption("Hanoi", "Hanoi", 21.0285, 105.8542, "Asia/Ho_Chi_Minh"),
            CityOption("Da Nang", "Da Nang", 16.0544, 108.2022, "Asia/Ho_Chi_Minh"),
        ),
        "TW" to listOf(
            CityOption("Taipei", "Taipei", 25.0330, 121.5654, "Asia/Taipei"),
            CityOption("Kaohsiung", "Kaohsiung", 22.6273, 120.3014, "Asia/Taipei"),
            CityOption("Taichung", "Taichung", 24.1477, 120.6736, "Asia/Taipei"),
        ),
        "HK" to listOf(
            CityOption("Hong Kong", "Hong Kong", 22.3193, 114.1694, "Asia/Hong_Kong"),
        ),
        "CN" to listOf(
            CityOption("Beijing", "Beijing", 39.9042, 116.4074, "Asia/Shanghai"),
            CityOption("Shanghai", "Shanghai", 31.2304, 121.4737, "Asia/Shanghai"),
            CityOption("Guangzhou", "Guangdong", 23.1291, 113.2644, "Asia/Shanghai"),
            CityOption("Shenzhen", "Guangdong", 22.5431, 114.0579, "Asia/Shanghai"),
            CityOption("Chengdu", "Sichuan", 30.5728, 104.0668, "Asia/Shanghai"),
        ),
        "SA" to listOf(
            CityOption("Riyadh", "Riyadh", 24.7136, 46.6753, "Asia/Riyadh"),
            CityOption("Jeddah", "Makkah", 21.4858, 39.1925, "Asia/Riyadh"),
            CityOption("Mecca", "Makkah", 21.3891, 39.8579, "Asia/Riyadh"),
            CityOption("Medina", "Madinah", 24.5247, 39.5692, "Asia/Riyadh"),
        ),
        "AE" to listOf(
            CityOption("Dubai", "Dubai", 25.2048, 55.2708, "Asia/Dubai"),
            CityOption("Abu Dhabi", "Abu Dhabi", 24.4539, 54.3773, "Asia/Dubai"),
            CityOption("Sharjah", "Sharjah", 25.3463, 55.4209, "Asia/Dubai"),
        ),
        "BR" to listOf(
            CityOption("Sao Paulo", "Sao Paulo", -23.5505, -46.6333, "America/Sao_Paulo"),
            CityOption("Rio de Janeiro", "Rio de Janeiro", -22.9068, -43.1729, "America/Sao_Paulo"),
            CityOption("Brasilia", "Distrito Federal", -15.7975, -47.8919, "America/Sao_Paulo"),
        ),
        "MX" to listOf(
            CityOption("Mexico City", "CDMX", 19.4326, -99.1332, "America/Mexico_City"),
            CityOption("Guadalajara", "Jalisco", 20.6597, -103.3496, "America/Mexico_City"),
            CityOption("Monterrey", "Nuevo Leon", 25.6866, -100.3161, "America/Monterrey"),
        ),
        "ES" to listOf(
            CityOption("Madrid", "Madrid", 40.4168, -3.7038, "Europe/Madrid"),
            CityOption("Barcelona", "Catalonia", 41.3874, 2.1686, "Europe/Madrid"),
            CityOption("Valencia", "Valencia", 39.4699, -0.3763, "Europe/Madrid"),
        ),
        "IT" to listOf(
            CityOption("Rome", "Lazio", 41.9028, 12.4964, "Europe/Rome"),
            CityOption("Milan", "Lombardy", 45.4642, 9.1900, "Europe/Rome"),
            CityOption("Naples", "Campania", 40.8518, 14.2681, "Europe/Rome"),
        ),
        "TR" to listOf(
            CityOption("Istanbul", "Istanbul", 41.0082, 28.9784, "Europe/Istanbul"),
            CityOption("Ankara", "Ankara", 39.9334, 32.8597, "Europe/Istanbul"),
            CityOption("Izmir", "Izmir", 38.4237, 27.1428, "Europe/Istanbul"),
        ),
    )

    private val ZONES: Map<String, String> = mapOf(
        "ID" to "Asia/Jakarta",
        "US" to "America/New_York",
        "GB" to "Europe/London",
        "JP" to "Asia/Tokyo",
        "KR" to "Asia/Seoul",
        "MY" to "Asia/Kuala_Lumpur",
        "SG" to "Asia/Singapore",
        "AU" to "Australia/Sydney",
        "NZ" to "Pacific/Auckland",
        "CA" to "America/Toronto",
        "DE" to "Europe/Berlin",
        "FR" to "Europe/Paris",
        "NL" to "Europe/Amsterdam",
        "IN" to "Asia/Kolkata",
        "TH" to "Asia/Bangkok",
        "PH" to "Asia/Manila",
        "VN" to "Asia/Ho_Chi_Minh",
        "TW" to "Asia/Taipei",
        "HK" to "Asia/Hong_Kong",
        "CN" to "Asia/Shanghai",
        "SA" to "Asia/Riyadh",
        "AE" to "Asia/Dubai",
        "QA" to "Asia/Qatar",
        "KW" to "Asia/Kuwait",
        "EG" to "Africa/Cairo",
        "BR" to "America/Sao_Paulo",
        "MX" to "America/Mexico_City",
        "ES" to "Europe/Madrid",
        "IT" to "Europe/Rome",
        "TR" to "Europe/Istanbul",
        "PK" to "Asia/Karachi",
        "BD" to "Asia/Dhaka",
        "RU" to "Europe/Moscow",
    )

    private val GENERIC_PLACE_WORDS = setOf(
        "kota", "kabupaten", "daerah", "provinsi", "city", "state", "province",
        "prefecture", "region", "metro", "new", "north", "south", "east", "west",
        "central", "special", "capital", "district", "the", "of",
    )

    @JvmStatic
    fun suggestedCities(country: String, language: String = "en"): List<CityOption> {
        val cc = Region.country(country) ?: Region.HOME
        return CITIES[cc].orEmpty()
    }

    @JvmStatic
    fun defaultCity(country: String, language: String = "en"): CityOption? =
        suggestedCities(country, language).firstOrNull()

    @JvmStatic
    fun defaultZone(country: String): String {
        val cc = Region.country(country) ?: return java.time.ZoneId.systemDefault().id
        return ZONES[cc] ?: suggestedCities(cc).firstOrNull()?.zoneId ?: java.time.ZoneId.systemDefault().id
    }

    /** City + region keywords for local/regional TV station prioritization in any country.
     *  Keeps [LocalTv.nearKeywords] exact semantics for Indonesia while also supporting city/state matching abroad. */
    @JvmStatic
    fun localKeywords(country: String?, region: String?, city: String?): List<String> {
        val cc = Region.country(country)
        val out = LinkedHashSet<String>()
        if (cc == null || cc.equals(Region.HOME, ignoreCase = true)) {
            out += LocalTv.nearKeywords(region)
        }
        fun addTokens(raw: String?) {
            val clean = raw?.trim()?.lowercase(Locale.US).orEmpty()
            if (clean.length < 3) return
            if (clean !in GENERIC_PLACE_WORDS) out += clean
            clean.split(Regex("[\\s,/\\-]+")).forEach { tok ->
                if (tok.length >= 4 && tok !in GENERIC_PLACE_WORDS) out += tok
            }
        }
        if (cc != null && !cc.equals(Region.HOME, ignoreCase = true)) {
            addTokens(region)
        }
        addTokens(city)
        return out.toList()
    }

    @JvmStatic
    fun featuredNames(country: String, limit: Int = 4): List<String> =
        PopularityScraper.topChannelNames(country, limit)

    @JvmStatic
    fun featuredSubtitle(entries: List<LocalTv.Entry>, country: String, language: String): String {
        val names = if (entries.isNotEmpty()) {
            entries.asSequence()
                .sortedByDescending {
                    PopularityScraper.score(it.name, listOf(country), it.category) +
                        if (it.category == LocalTv.Category.National) 50.0 else 0.0
                }
                .map { it.name }
                .distinct()
                .take(4)
                .toList()
        } else {
            featuredNames(country, 4)
        }
        val joined = if (names.isNotEmpty()) names.joinToString(", ") else Region.name(country, language)
        return I18n.get(language, "live.featured_sub", joined)
    }
}
