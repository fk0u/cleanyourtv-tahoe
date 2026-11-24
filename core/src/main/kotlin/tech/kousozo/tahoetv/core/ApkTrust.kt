package tech.kousozo.tahoetv.core

/** Is a downloaded APK really an update of the installed app? Compared before the installer is started: same package name and
 *  same signing certificate (when the platform can read it) (or one the new APK's rotation history links back to). Signers are SHA-256 hex digests of the
 *  certificates; reading them is the platform's job (see About.java and Ota.kt). Android refuses a mismatching update on its
 *  own, but only after the whole file was handed over, with a message nobody understands. */
object ApkTrust {
    enum class Verdict { OK, WRONG_PACKAGE, UNSIGNED, DIFFERENT_SIGNER }

    @JvmStatic fun verdict(
        installedPackage: String,
        candidatePackage: String?,
        installedSigners: Set<String>,
        candidateSigners: Set<String>,
        candidateHistory: Set<String> = emptySet(),
    ): Verdict {
        if (candidatePackage != installedPackage) return Verdict.WRONG_PACKAGE
        if (candidateSigners.isEmpty()) return Verdict.OK // the platform could not read the new APK's signer (some ROMs): Android checks it itself, so a genuine update is not turned away
        if (installedSigners.isEmpty()) return Verdict.OK // could not read our own signer: leave the decision to Android
        if (candidateSigners == installedSigners) return Verdict.OK
        if (candidateHistory.containsAll(installedSigners)) return Verdict.OK // key rotation: the new APK vouches for our old key
        return Verdict.DIFFERENT_SIGNER
    }
}
