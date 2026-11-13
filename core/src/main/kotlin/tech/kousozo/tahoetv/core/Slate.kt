package tech.kousozo.tahoetv.core

/** What every player shows in place of a channel that can't play (#76): a calm Tahoe "slate" with the apology in
 *  Indonesian, English and Chinese at once (the viewer's language first), a short reason, and the actions. Players
 *  keep retrying in the background every [RETRY_SECONDS] and switch back to the broadcast as soon as a source works. */
object Slate {
    /** Why it can't play, when the player knows. */
    enum class Reason { Offline, Recording, Unsupported }

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
        Reason.Offline to mapOf("id" to "Server saluran tidak menjawab.", "en" to "The channel's server isn't responding.", "zh" to "频道服务器无响应。"),
        Reason.Recording to mapOf("id" to "Sumber yang ada hanya rekaman, bukan siaran langsung.", "en" to "The available sources are recordings, not live.", "zh" to "现有信号源为录像，并非直播。"),
        Reason.Unsupported to mapOf("id" to "Format siaran ini belum didukung di perangkat ini.", "en" to "This stream's format isn't supported on this device yet.", "zh" to "此设备暂不支持该直播格式。"),
    )

    private val LABEL = mapOf(
        "retry" to mapOf("id" to "Coba lagi", "en" to "Try again", "zh" to "重试"),
        "next" to mapOf("id" to "Saluran berikutnya", "en" to "Next channel", "zh" to "下一个频道"),
        "report" to mapOf("id" to "Laporkan saluran", "en" to "Report channel", "zh" to "报告频道"),
        "retrying" to mapOf("id" to "Mencoba lagi dalam %s detik", "en" to "Trying again in %s s", "zh" to "%s 秒后重试"),
        "trying" to mapOf("id" to "Mencoba lagi…", "en" to "Trying again…", "zh" to "正在重试…"),
        "waiting" to mapOf("id" to "Tekan Coba lagi kapan saja.", "en" to "Press Try again any time.", "zh" to "随时可按重试。"),
    )

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

    /** GitHub's "channel problem" form, with the channel name filled in. */
    @JvmStatic fun reportUrl(channel: String): String =
        "https://github.com/fk0u/cleanyourtv-tahoe/issues/new?template=channel.yml&title=" + java.net.URLEncoder.encode("[Saluran] $channel", "UTF-8")
}
