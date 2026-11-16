package tech.kousozo.tahoetv.core

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Advanced Kids profile policy, watch-time tracker, schedule gate, and custom channel allow/block rules (#54).
 */
object KidsPolicy {
    enum class BlockReason {
        NONE,
        OUTSIDE_SCHEDULE,
        DAILY_LIMIT,
    }

    data class Config(
        val dailyLimitMinutes: Int = 0,          // 0 = unlimited
        val scheduleEnabled: Boolean = false,
        val allowedStartMin: Int = 360,          // 06:00
        val allowedEndMin: Int = 1200,           // 20:00
        val extraChannelIds: Set<String> = emptySet(),
        val blockedChannelIds: Set<String> = emptySet(),
    ) {
        fun isOutsideSchedule(minuteOfDay: Int): Boolean {
            if (!scheduleEnabled) return false
            val m = ((minuteOfDay % 1440) + 1440) % 1440
            val s = allowedStartMin.coerceIn(0, 1439)
            val e = allowedEndMin.coerceIn(0, 1440)
            if (s == e) return false
            return if (s < e) {
                m < s || m >= e
            } else {
                // Overnight window (e.g. 20:00 .. 06:00 allowed)
                m in e until s
            }
        }

        fun isChannelAllowed(isKidsOrDoc: Boolean, channelId: String?, channelName: String? = null): Boolean {
            val keys = buildList {
                if (!channelId.isNullOrBlank()) add(normalizeKey(channelId))
                if (!channelName.isNullOrBlank()) add(normalizeKey(channelName))
            }
            if (keys.any { it in blockedChannelIds }) return false
            if (keys.any { it in extraChannelIds }) return true
            return isKidsOrDoc
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("dailyLimitMinutes", dailyLimitMinutes.coerceAtLeast(0))
            put("scheduleEnabled", scheduleEnabled)
            put("allowedStartMin", allowedStartMin.coerceIn(0, 1439))
            put("allowedEndMin", allowedEndMin.coerceIn(0, 1440))
            put("extraChannelIds", JSONArray(extraChannelIds.sorted()))
            put("blockedChannelIds", JSONArray(blockedChannelIds.sorted()))
        }

        fun toJsonString(): String = toJson().toString()

        companion object {
            @JvmField val DEFAULT = Config()

            @JvmStatic
            fun normalizeKey(raw: String): String =
                raw.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

            @JvmStatic
            fun fromJson(o: JSONObject?): Config {
                if (o == null) return DEFAULT
                val extra = LinkedHashSet<String>()
                o.optJSONArray("extraChannelIds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val k = normalizeKey(arr.optString(i))
                        if (k.isNotEmpty()) extra += k
                    }
                }
                val blocked = LinkedHashSet<String>()
                o.optJSONArray("blockedChannelIds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val k = normalizeKey(arr.optString(i))
                        if (k.isNotEmpty()) blocked += k
                    }
                }
                return Config(
                    dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0).coerceIn(0, 1440),
                    scheduleEnabled = o.optBoolean("scheduleEnabled", false),
                    allowedStartMin = o.optInt("allowedStartMin", 360).coerceIn(0, 1439),
                    allowedEndMin = o.optInt("allowedEndMin", 1200).coerceIn(0, 1440),
                    extraChannelIds = extra,
                    blockedChannelIds = blocked,
                )
            }

            @JvmStatic
            fun fromJsonString(raw: String?): Config {
                if (raw.isNullOrBlank()) return DEFAULT
                return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(DEFAULT)
            }
        }
    }

    data class EvalResult(
        val allowed: Boolean,
        val reason: BlockReason,
        val usedSeconds: Int,
        val remainingSeconds: Int,
        val warnFiveMinutes: Boolean,
    )

    /** Mutable per-device Kids daily usage state, serialized to JSON in SharedPreferences / local file. */
    class UsageState(
        var date: String = todayKey(),
        var watchedSeconds: Int = 0,
        var bonusSeconds: Int = 0,
        var warnedDate: String = "",
        val byChannel: LinkedHashMap<String, Int> = LinkedHashMap(),
        val byApp: LinkedHashMap<String, Int> = LinkedHashMap(),
        val historyMinutes: LinkedHashMap<String, Int> = LinkedHashMap(),
    ) {
        @Synchronized
        @JvmOverloads
        fun ensureDay(today: String = todayKey()) {
            if (date == today) return
            if (date.isNotBlank() && watchedSeconds > 0) {
                historyMinutes[date] = maxOf(historyMinutes[date] ?: 0, (watchedSeconds + 30) / 60)
                while (historyMinutes.size > 7) {
                    val firstKey = historyMinutes.keys.firstOrNull() ?: break
                    historyMinutes.remove(firstKey)
                }
            }
            date = today
            watchedSeconds = 0
            bonusSeconds = 0
            warnedDate = ""
            byChannel.clear()
            byApp.clear()
        }

        @Synchronized
        @JvmOverloads
        fun addChannelSeconds(channelName: String, sec: Int, today: String = todayKey()) {
            if (sec <= 0) return
            ensureDay(today)
            val add = sec.coerceAtMost(3600)
            watchedSeconds += add
            val key = channelName.trim().ifBlank { "Live TV" }
            byChannel[key] = (byChannel[key] ?: 0) + add
        }

        @Synchronized
        @JvmOverloads
        fun addAppSeconds(appLabel: String, sec: Int, today: String = todayKey()) {
            if (sec <= 0) return
            ensureDay(today)
            val add = sec.coerceAtMost(3600)
            watchedSeconds += add
            val key = appLabel.trim().ifBlank { "App" }
            byApp[key] = (byApp[key] ?: 0) + add
        }

        @Synchronized
        @JvmOverloads
        fun grantBonusMinutes(minutes: Int, today: String = todayKey()) {
            if (minutes <= 0) return
            ensureDay(today)
            bonusSeconds += minutes * 60
        }

        @Synchronized
        @JvmOverloads
        fun resetToday(today: String = todayKey()) {
            date = today
            watchedSeconds = 0
            bonusSeconds = 0
            warnedDate = ""
            byChannel.clear()
            byApp.clear()
        }

        @Synchronized
        @JvmOverloads
        fun markWarned(today: String = todayKey()) {
            ensureDay(today)
            warnedDate = today
        }

        @Synchronized
        @JvmOverloads
        fun evaluate(cfg: Config, minuteOfDay: Int = currentMinuteOfDay(), today: String = todayKey()): EvalResult {
            ensureDay(today)
            if (cfg.isOutsideSchedule(minuteOfDay)) {
                return EvalResult(
                    allowed = false,
                    reason = BlockReason.OUTSIDE_SCHEDULE,
                    usedSeconds = watchedSeconds,
                    remainingSeconds = 0,
                    warnFiveMinutes = false,
                )
            }
            if (cfg.dailyLimitMinutes <= 0) {
                return EvalResult(
                    allowed = true,
                    reason = BlockReason.NONE,
                    usedSeconds = watchedSeconds,
                    remainingSeconds = Int.MAX_VALUE,
                    warnFiveMinutes = false,
                )
            }
            val capSec = cfg.dailyLimitMinutes * 60 + bonusSeconds
            val left = (capSec - watchedSeconds).coerceAtLeast(0)
            if (left <= 0) {
                return EvalResult(
                    allowed = false,
                    reason = BlockReason.DAILY_LIMIT,
                    usedSeconds = watchedSeconds,
                    remainingSeconds = 0,
                    warnFiveMinutes = false,
                )
            }
            val warn = left in 1..300 && warnedDate != today
            return EvalResult(
                allowed = true,
                reason = BlockReason.NONE,
                usedSeconds = watchedSeconds,
                remainingSeconds = left,
                warnFiveMinutes = warn,
            )
        }

        @Synchronized
        @JvmOverloads
        fun reportJson(cfg: Config, minuteOfDay: Int = currentMinuteOfDay(), today: String = todayKey()): JSONObject {
            val ev = evaluate(cfg, minuteOfDay, today)
            val chArr = JSONArray()
            byChannel.entries.sortedByDescending { it.value }.take(8).forEach { (k, s) ->
                chArr.put(JSONObject().put("name", k).put("minutes", maxOf(1, (s + 30) / 60)))
            }
            val appArr = JSONArray()
            byApp.entries.sortedByDescending { it.value }.take(8).forEach { (k, s) ->
                appArr.put(JSONObject().put("name", k).put("minutes", maxOf(1, (s + 30) / 60)))
            }
            val histArr = JSONArray()
            historyMinutes.forEach { (d, m) ->
                histArr.put(JSONObject().put("date", d).put("minutes", m))
            }
            return JSONObject().apply {
                put("date", date)
                put("watchedMinutes", (watchedSeconds + 30) / 60)
                put("dailyLimitMinutes", cfg.dailyLimitMinutes)
                put("remainingMinutes", if (cfg.dailyLimitMinutes <= 0) -1 else (ev.remainingSeconds + 59) / 60)
                put("blockedReason", ev.reason.name)
                put("channels", chArr)
                put("apps", appArr)
                put("history", histArr)
            }
        }

        @Synchronized
        fun toJsonString(): String = JSONObject().apply {
            put("date", date)
            put("watchedSeconds", watchedSeconds)
            put("bonusSeconds", bonusSeconds)
            put("warnedDate", warnedDate)
            put("byChannel", JSONObject().also { o -> byChannel.forEach { (k, v) -> o.put(k, v) } })
            put("byApp", JSONObject().also { o -> byApp.forEach { (k, v) -> o.put(k, v) } })
            put("historyMinutes", JSONObject().also { o -> historyMinutes.forEach { (k, v) -> o.put(k, v) } })
        }.toString()

        companion object {
            @JvmStatic
            @JvmOverloads
            fun fromJson(raw: String?, today: String = todayKey()): UsageState {
                if (raw.isNullOrBlank()) return UsageState(date = today)
                return runCatching {
                    val o = JSONObject(raw)
                    val ch = LinkedHashMap<String, Int>()
                    o.optJSONObject("byChannel")?.let { m -> m.keys().forEach { k -> ch[k] = m.optInt(k, 0).coerceAtLeast(0) } }
                    val app = LinkedHashMap<String, Int>()
                    o.optJSONObject("byApp")?.let { m -> m.keys().forEach { k -> app[k] = m.optInt(k, 0).coerceAtLeast(0) } }
                    val hist = LinkedHashMap<String, Int>()
                    o.optJSONObject("historyMinutes")?.let { m -> m.keys().forEach { k -> hist[k] = m.optInt(k, 0).coerceAtLeast(0) } }
                    UsageState(
                        date = o.optString("date", today),
                        watchedSeconds = o.optInt("watchedSeconds", 0).coerceAtLeast(0),
                        bonusSeconds = o.optInt("bonusSeconds", 0).coerceAtLeast(0),
                        warnedDate = o.optString("warnedDate", ""),
                        byChannel = ch,
                        byApp = app,
                        historyMinutes = hist,
                    ).apply { ensureDay(today) }
                }.getOrDefault(UsageState(date = today))
            }
        }
    }

    @JvmStatic
    fun todayKey(): String = LocalDate.now().toString()

    @JvmStatic
    fun currentMinuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    @JvmStatic
    fun formatClockMinutes(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }
}
