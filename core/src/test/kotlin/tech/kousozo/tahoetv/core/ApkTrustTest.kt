package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ApkTrustTest {
    private val pkg = "tech.kousozo.tahoetv.launcher"
    private val mine = setOf("aa11")
    private fun v(candPkg: String? = pkg, inst: Set<String> = mine, cand: Set<String> = mine, hist: Set<String> = emptySet()) =
        ApkTrust.verdict(pkg, candPkg, inst, cand, hist)

    @Test fun sameSignerIsTrusted() = assertEquals(ApkTrust.Verdict.OK, v())

    @Test fun otherPackageRefused() {
        assertEquals(ApkTrust.Verdict.WRONG_PACKAGE, v(candPkg = "evil.app"))
        assertEquals(ApkTrust.Verdict.WRONG_PACKAGE, v(candPkg = null))
    }

    @Test fun differentSignerRefused() = assertEquals(ApkTrust.Verdict.DIFFERENT_SIGNER, v(cand = setOf("bb22")))

    @Test fun unreadableCandidateSignerLeavesItToAndroid() = assertEquals(ApkTrust.Verdict.OK, v(cand = emptySet())) // was refused as "unsigned" on ROMs that cannot read an archive's signer

    @Test fun rotationHistoryVouchesForOurOldKey() =
        assertEquals(ApkTrust.Verdict.OK, v(cand = setOf("bb22"), hist = setOf("aa11", "bb22")))

    @Test fun unreadableOwnSignerLeavesItToAndroid() = assertEquals(ApkTrust.Verdict.OK, v(inst = emptySet(), cand = setOf("bb22")))
}
