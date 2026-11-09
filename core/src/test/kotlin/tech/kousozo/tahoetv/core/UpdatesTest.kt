package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatesTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updates.newer("2.9.3", "2.9.2"))
        assertTrue(Updates.newer("2.10.0", "2.9.3"))
        assertTrue(Updates.newer("v3.0.0", "2.9.9"))
        assertFalse(Updates.newer("2.9.2", "2.9.2"))
        assertFalse(Updates.newer("2.9.1", "2.9.2"))
        assertFalse(Updates.newer("2.9.3-beta", "2.9.3"))
    }
}
