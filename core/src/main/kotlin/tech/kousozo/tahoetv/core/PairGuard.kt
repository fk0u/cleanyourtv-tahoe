package tech.kousozo.tahoetv.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** The 6-digit pairing code shown on the TV, guarded against guessing from the LAN.
 *  - The code expires after [ttlMs] (a new one is made when it's read or checked after that).
 *  - [maxFails] wrong codes in a row → a new code and a lockout that doubles each time (up to [maxLockMs]).
 *    The lock is global, not per IP: on a LAN anyone can change their address. Pressing OK on the TV ([newCode])
 *    lifts it, since only someone at the TV can do that.
 *  - Codes are compared in constant time.
 *  Before this, the code stayed the same until a phone paired and wrong guesses were unlimited: all 1 000 000
 *  codes could be tried in minutes. Now ~5 guesses per lockout window, so brute force takes years. */
class PairGuard @JvmOverloads constructor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60_000L,
    private val maxFails: Int = 5,
    private val firstLockMs: Long = 30_000L,
    private val maxLockMs: Long = 15 * 60_000L,
) {
    sealed class Result {
        object Ok : Result()
        object Wrong : Result()
        /** Too many wrong codes: try again in [seconds]. */
        data class Locked(val seconds: Long) : Result()
    }

    private val rnd = SecureRandom()
    private var code = ""
    private var issuedAt = 0L
    private var fails = 0
    private var lockedUntil = 0L
    private var nextLockMs = firstLockMs

    init { newCode() }

    /** A fresh code; also lifts a lockout (only someone at the TV can ask for one). */
    @Synchronized fun newCode(): String {
        code = String.format(Locale.US, "%06d", rnd.nextInt(1_000_000))
        issuedAt = clock(); fails = 0; lockedUntil = 0L; nextLockMs = firstLockMs
        return code
    }

    /** The code to show (renewed when expired). */
    @Synchronized fun current(): String {
        if (clock() - issuedAt >= ttlMs) newCode()
        return code
    }

    @Synchronized fun check(given: String?): Result {
        val now = clock()
        if (now < lockedUntil) return Result.Locked((lockedUntil - now + 999) / 1000)
        if (now - issuedAt >= ttlMs) { // expired: nobody can pair with it any more
            code = String.format(Locale.US, "%06d", rnd.nextInt(1_000_000)); issuedAt = now
        }
        val ok = given != null && given.length == code.length &&
            MessageDigest.isEqual(given.toByteArray(Charsets.US_ASCII), code.toByteArray(Charsets.US_ASCII))
        if (ok) { newCode(); return Result.Ok } // single use
        if (++fails >= maxFails) {
            code = String.format(Locale.US, "%06d", rnd.nextInt(1_000_000)); issuedAt = now; fails = 0
            lockedUntil = now + nextLockMs
            nextLockMs = minOf(nextLockMs * 2, maxLockMs)
            return Result.Locked((lockedUntil - now + 999) / 1000)
        }
        return Result.Wrong
    }
}
