package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecurityTest {
    @Test fun rightCodePairsOnceThenChanges() {
        val g = PairGuard()
        val c = g.current()
        assertIs<PairGuard.Result.Ok>(g.check(c))
        assertIs<PairGuard.Result.Wrong>(g.check(c)) // single use
    }

    @Test fun guessingLocksAndTheLockGrows() {
        var now = 0L
        val g = PairGuard(clock = { now })
        val real = g.current()
        val wrong = if (real == "000000") "000001" else "000000"
        repeat(4) { assertIs<PairGuard.Result.Wrong>(g.check(wrong)) }
        assertEquals(PairGuard.Result.Locked(30), g.check(wrong))       // 5th wrong → 30 s
        assertIs<PairGuard.Result.Locked>(g.check(g.current()))           // even the right code waits
        now += 30_000
        repeat(4) { g.check(wrong) }
        assertEquals(PairGuard.Result.Locked(60), g.check(wrong))       // doubles
        assertNotEquals(real, g.current())                                // and the code changed
    }

    @Test fun okOnTheTvLiftsTheLock() {
        var now = 0L
        val g = PairGuard(clock = { now })
        repeat(5) { g.check("x") }
        val fresh = g.newCode()
        assertIs<PairGuard.Result.Ok>(g.check(fresh))
    }

    @Test fun codeExpires() {
        var now = 0L
        val g = PairGuard(clock = { now }, ttlMs = 1000)
        val c = g.current()
        now = 1000
        assertIs<PairGuard.Result.Wrong>(g.check(c))
        assertNotEquals(c, g.current())
    }

    @Test fun bruteForceIsImpractical() {
        // with 5 guesses per lockout and lockouts capped at 15 min, a day allows only a few hundred guesses
        var now = 0L
        val g = PairGuard(clock = { now })
        var guesses = 0
        while (now < 24 * 3600_000L) {
            when (val r = g.check("%06d".format(guesses % 1_000_000))) {
                is PairGuard.Result.Locked -> now += r.seconds * 1000
                else -> {}
            }
            guesses++
        }
        assertTrue(guesses < 1500, "guesses in a day: $guesses")
    }

    @Test fun signedUrlsOnly() {
        val s = UrlSigner()
        val sig = s.sign("rcti", "https://cdn.example/a.ts")
        assertTrue(s.verify("rcti", "https://cdn.example/a.ts", sig))
        assertFalse(s.verify("rcti", "http://192.168.1.1/admin", sig))   // another URL
        assertFalse(s.verify("sctv", "https://cdn.example/a.ts", sig))    // another channel
        assertFalse(s.verify("rcti", "https://cdn.example/a.ts", null))
        assertFalse(UrlSigner().verify("rcti", "https://cdn.example/a.ts", sig)) // another process's key
    }
}
