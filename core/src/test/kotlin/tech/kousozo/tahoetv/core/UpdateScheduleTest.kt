package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateScheduleTest {
    private val day = UpdateSchedule.INTERVAL_MS

    @Test fun neverCheckedIsDue() = assertTrue(UpdateSchedule.due(0, 1_000))

    @Test fun waitsAFullDay() {
        assertFalse(UpdateSchedule.due(1_000, 1_000 + day - 1))
        assertTrue(UpdateSchedule.due(1_000, 1_000 + day))
    }

    @Test fun clockMovedBackIsDue() = assertTrue(UpdateSchedule.due(10_000_000, 5_000_000))

    @Test fun badgeOnlyWhileNewer() {
        assertTrue(UpdateSchedule.available("3.7.1", "3.7.0"))
        assertFalse(UpdateSchedule.available("3.7.0", "3.7.0")) // installed: badge goes away
        assertFalse(UpdateSchedule.available("", "3.7.0"))
        assertFalse(UpdateSchedule.available(null, "3.7.0"))
        assertTrue(UpdateSchedule.available("3.7.0", "3.7.0-beta.1"))
    }
}
