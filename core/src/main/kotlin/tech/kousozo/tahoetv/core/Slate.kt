package tech.kousozo.tahoetv.core

/** What every player shows in place of a channel that can't play (#76, #114): a calm Tahoe "slate" with the apology in
 *  Indonesian, English and Chinese at once (the viewer's language first), a short reason, and the actions. Players
 *  keep retrying in the background every [RETRY_SECONDS] and switch back to the broadcast as soon as a source works. */
object Slate {
    /** Why it can't play, when the player knows. */
    enum class Reason { Offline, Recording, Unsupported }

    enum class State { RETRYING, WAITING }

    /** Reusable retry state machine for slate across TV, phone, desktop, and web players. */
    class RetryState(
        val maxRetries: Int = MAX_RETRIES,
        val retrySeconds: Int = RETRY_SECONDS
    ) {
        var retries: Int = 0
            private set
        var secondsLeft: Int = retrySeconds
            private set
        var state: State = State.RETRYING
            private set

        val isWaiting: Boolean get() = state == State.WAITING

        /** Ticks 1 second down. Returns true if a retry should be triggered right now. */
        fun tick(): Boolean {
            if (state == State.WAITING) return false
            secondsLeft--
            if (secondsLeft <= 0) {
                retries++
                if (retries >= maxRetries) {
                    state = State.WAITING
                    secondsLeft = 0
                    return false
                } else {
                    secondsLeft = retrySeconds
                    return true
                }
            }
            return false
        }

        fun retryNow() {
            retries = 0
            secondsLeft = retrySeconds
            state = State.RETRYING
        }

        fun reset() {
            retries = 0
            secondsLeft = retrySeconds
            state = State.RETRYING
        }
    }

    /** The three languages the apology is always shown in, in this order unless the viewer reads one of them. */
    val LANGUAGES = listOf("id", "en", "zh")

    const val RETRY_SECONDS = 30
    /** Background retries before the slate just waits for the viewer (about 10 minutes). */
    const val MAX_RETRIES = 20

    private val MESSAGE = mapOf(
        "id" to "Mohon maaf, siaran ini sedang tidak tersedia. Kami terus mencoba sumber lain secara otomatis.",
        "en" to "Sorry, this channel is currently unavailable. We'll keep trying other sources automatically.",
        "zh" to "抱歉，该频道暂时无法播放。我们会自动尝试其他信号源。",
    )

    private val REASON = mapOf(
        Reason.Offline to mapOf("id" to "Server saluran tidak menjawab.", "en" to "The channel's server isn't responding.", "zh" to "频道服务器无响应。", "ar" to "خادم القناة لا يستجيب."),
        Reason.Recording to mapOf("id" to "Sumber yang ada hanya rekaman, bukan siaran langsung.", "en" to "The available sources are recordings, not live.", "zh" to "现有信号源为录像，并非直播。", "ar" to "المصادر المتاحة تسجيلات وليست بثًا مباشرًا."),
        Reason.Unsupported to mapOf("id" to "Format siaran ini belum didukung di perangkat ini.", "en" to "This stream's format isn't supported on this device yet.", "zh" to "此设备暂不支持该直播格式。", "ar" to "صيغة هذا البث غير مدعومة على هذا الجهاز بعد."),
    )

    private val LABEL = mapOf(
        "retry" to mapOf("id" to "Coba lagi", "en" to "Try again", "zh" to "重试", "ar" to "حاول مجددًا"),
        "next" to mapOf("id" to "Saluran berikutnya", "en" to "Next channel", "zh" to "下一个频道", "ar" to "القناة التالية"),
        "report" to mapOf("id" to "Laporkan saluran", "en" to "Report channel", "zh" to "报告频道", "ar" to "أبلغ عن القناة"),
        "retrying" to mapOf("id" to "Mencoba lagi dalam %s detik", "en" to "Trying again in %s s", "zh" to "%s 秒后重试", "ar" to "إعادة المحاولة خلال %s ث"),
        "trying" to mapOf("id" to "Mencoba lagi otomatis…", "en" to "Trying automatically…", "zh" to "自动重试中…", "ar" to "نعيد المحاولة تلقائيًا…"),
        "waiting" to mapOf("id" to "Tekan Coba lagi kapan saja.", "en" to "Press Try again any time.", "zh" to "随时可按重试。", "ar" to "اضغط حاول مجددًا في أي وقت."),
    )

    /** Several ways to say it, per language, so a channel that stays down doesn't repeat one sentence. */
    private val HEADLINES = mapOf(
        "en" to listOf("This channel is taking a break. We're trying other sources for you.", "No signal right now. Tahoe TV keeps searching for a working source.",
            "This broadcast can't be reached at the moment. Hang tight, we'll reconnect automatically.", "Sorry, this channel isn't available. We're retrying in the background."),
        "id" to listOf("Siaran ini sedang istirahat sebentar. Kami mencoba sumber lain untukmu.", "Belum ada sinyal. Tahoe TV terus mencari sumber yang berfungsi.",
            "Siaran ini belum bisa dijangkau. Tunggu sebentar, kami sambungkan otomatis.", "Mohon maaf, saluran ini sedang tidak tersedia. Kami mencoba lagi di latar belakang."),
        "zh" to listOf("该频道暂时休息，我们正在为你尝试其他信号源。", "目前没有信号，Tahoe TV 会持续寻找可用的信号源。",
            "暂时无法连接此直播，请稍候，我们会自动重新连接。", "抱歉，该频道暂时不可用，我们正在后台重试。"),
        "ar" to listOf("هذه القناة في استراحة قصيرة. نجرّب مصادر أخرى لك.", "لا توجد إشارة الآن. يواصل Tahoe TV البحث عن مصدر يعمل.",
            "تعذّر الوصول إلى هذا البث حاليًا. انتظر قليلًا وسنعيد الاتصال تلقائيًا.", "عذرًا، هذه القناة غير متاحة حاليًا. نعيد المحاولة في الخلفية."),
    )
    private val FOLLOW = mapOf(
        "en" to "Follow TahoeTV OFFICIAL on WhatsApp for service updates", "id" to "Ikuti saluran TahoeTV OFFICIAL di WhatsApp untuk kabar layanan",
        "zh" to "在 WhatsApp 关注 TahoeTV OFFICIAL 频道获取服务动态", "ar" to "تابع قناة TahoeTV OFFICIAL على واتساب لأخبار الخدمة",
    )

    /** The announcement in the viewer's language only; [n] (the retry count, say) picks the wording so it changes between tries. */
    @JvmStatic fun headline(viewerLanguage: String?, n: Int): String {
        val l = HEADLINES.getValue(Region.language(viewerLanguage).takeIf { it in HEADLINES } ?: "en")
        return l[Math.floorMod(n, l.size)]
    }
    /** "Follow our WhatsApp channel" in the viewer's language; the link itself is [Brand.WHATSAPP_CHANNEL]. */
    @JvmStatic fun follow(viewerLanguage: String?): String = FOLLOW.getValue(Region.language(viewerLanguage).takeIf { it in FOLLOW } ?: "en")

    /** Languages in display order: the viewer's (if it's one of the three) first. */
    @JvmStatic fun order(viewerLanguage: String?): List<String> {
        val l = Region.language(viewerLanguage)
        return if (l in LANGUAGES) listOf(l) + (LANGUAGES - l) else LANGUAGES
    }

    @JvmStatic fun message(lang: String): String = MESSAGE[lang] ?: MESSAGE.getValue("en")
    @JvmStatic fun reason(lang: String, r: Reason): String = REASON.getValue(r)[lang] ?: REASON.getValue(r).getValue("en")
    /** A button or status label in the viewer's language (English for others). */
    @JvmStatic fun label(viewerLanguage: String?, key: String): String {
        val l = Region.language(viewerLanguage)
        val m = LABEL.getValue(key)
        return m[l] ?: m.getValue("en")
    }

    @JvmStatic fun reportTitle(lang: String?, channel: String): String {
        val prefix = when (Region.language(lang)) {
            "zh" -> "[频道]"
            "en" -> "[Channel]"
            else -> "[Saluran]"
        }
        return "$prefix $channel"
    }

    /** GitHub's "channel problem" form, with the channel name filled in and localised title. */
    @JvmStatic @JvmOverloads fun reportUrl(channel: String, lang: String? = null): String =
        "https://github.com/fk0u/tahoe-tv/issues/new?template=channel.yml&title=" +
        java.net.URLEncoder.encode(reportTitle(lang, channel), "UTF-8")
}
