package tech.kousozo.tahoetv.core

import kotlin.random.Random

/** Parental verification via randomized adult arithmetic ("verifikasi orang dewasa", #47). */
object MathGate {
    enum class Mode(val key: String) {
        PIN("pin"),
        MATH("math"),
        PIN_AND_MATH("pin_math");

        fun label(lang: String): String = I18n.get(lang, "kids.gate_$key")

        companion object {
            @JvmStatic
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: PIN
            @JvmStatic
            fun next(m: Mode, dir: Int): Mode {
                val n = entries.size
                return entries[((m.ordinal + dir) % n + n) % n]
            }
        }
    }

    enum class Difficulty(val key: String) {
        EASY("easy"),
        MEDIUM("medium"),
        HARD("hard");

        fun label(lang: String): String = I18n.get(lang, "kids.diff_$key")

        companion object {
            @JvmStatic
            fun of(key: String?): Difficulty = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: MEDIUM
            @JvmStatic
            fun next(d: Difficulty, dir: Int): Difficulty {
                val n = entries.size
                return entries[((d.ordinal + dir) % n + n) % n]
            }
        }
    }

    data class Problem(val question: String, val answer: Int)

    /** Generates an arithmetic challenge that avoids trivial 0/1 operands and never repeats [lastQuestion]. */
    @JvmStatic
    @JvmOverloads
    fun generate(
        difficulty: Difficulty = Difficulty.MEDIUM,
        lastQuestion: String? = null,
        rng: Random = Random.Default,
    ): Problem {
        repeat(16) {
            val p = nextCandidate(difficulty, rng)
            if (p.question != lastQuestion) return p
        }
        return nextCandidate(difficulty, rng)
    }

    private fun nextCandidate(difficulty: Difficulty, rng: Random): Problem = when (difficulty) {
        Difficulty.EASY -> {
            if (rng.nextBoolean()) {
                val a = rng.nextInt(3, 10)
                val b = rng.nextInt(2, 10)
                Problem("$a × $b = ?", a * b)
            } else {
                val a = rng.nextInt(12, 40)
                val b = rng.nextInt(6, 20)
                Problem("$a + $b = ?", a + b)
            }
        }
        Difficulty.MEDIUM -> {
            if (rng.nextBoolean()) {
                val a = rng.nextInt(12, 20)
                val b = rng.nextInt(3, 10)
                Problem("$a × $b = ?", a * b)
            } else {
                val a = rng.nextInt(12, 39)
                val b = rng.nextInt(11, 29)
                val c = rng.nextInt(5, 20)
                Problem("$a + $b + $c = ?", a + b + c)
            }
        }
        Difficulty.HARD -> {
            if (rng.nextBoolean()) {
                val a = rng.nextInt(12, 24)
                val b = rng.nextInt(12, 20)
                Problem("$a × $b = ?", a * b)
            } else {
                val a = rng.nextInt(11, 19)
                val b = rng.nextInt(4, 10)
                val c = rng.nextInt(12, 49)
                Problem("$a × $b + $c = ?", a * b + c)
            }
        }
    }

    @JvmStatic
    fun verify(problem: Problem, input: String?): Boolean {
        val parsed = input?.trim()?.toIntOrNull() ?: return false
        return parsed == problem.answer
    }

    /** Cooldown in seconds after [consecutiveFails] wrong attempts: 2s, then 30s, then 300s (or exponential progression). */
    @JvmStatic
    fun lockoutSeconds(consecutiveFails: Int): Int = when {
        consecutiveFails <= 0 -> 0
        consecutiveFails <= 2 -> 2
        consecutiveFails <= 4 -> 30
        else -> 300
    }
}
