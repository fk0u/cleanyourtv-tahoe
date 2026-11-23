package tech.kousozo.tahoetv.core

import kotlin.test.*

class AccountTextTest {
    @Test fun englishAndIndonesianHaveTheSameKeysAndNoBlanks() {
        assertEquals(AccountText.tables["en"]!!.keys, AccountText.tables["id"]!!.keys)
        AccountText.tables.values.forEach { t -> t.forEach { (k, v) -> assertTrue(v.isNotBlank(), k) } }
        assertEquals(AccountText.tables["en"]!!.filterValues { "%s" in it }.keys, AccountText.tables["id"]!!.filterValues { "%s" in it }.keys)
    }
    @Test fun unknownLanguageFallsBackToEnglish() {
        assertEquals("Sign in", AccountText.get("fr", "signin"))
        assertEquals("Signed in as a@b.cc", AccountText.get("en", "signed_as", "a@b.cc"))
    }
}
