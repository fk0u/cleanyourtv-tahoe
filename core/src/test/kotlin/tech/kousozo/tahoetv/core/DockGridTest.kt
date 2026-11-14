package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockGridTest {
    @Test fun effectiveColsClampsAndCapsInSimpleMode() {
        assertEquals(4, DockGrid.effectiveCols(2, simpleMode = false))
        assertEquals(6, DockGrid.effectiveCols(6, simpleMode = false))
        assertEquals(7, DockGrid.effectiveCols(10, simpleMode = false))
        assertEquals(4, DockGrid.effectiveCols(6, simpleMode = true))
        assertEquals(4, DockGrid.effectiveCols(7, simpleMode = true))
    }

    @Test fun padRowsComputesCeilingRowsSafely() {
        assertEquals(0, DockGrid.padRows(0, 6))
        assertEquals(0, DockGrid.padRows(-3, 6))
        assertEquals(1, DockGrid.padRows(1, 6))
        assertEquals(1, DockGrid.padRows(6, 6))
        assertEquals(2, DockGrid.padRows(7, 6))
        assertEquals(3, DockGrid.padRows(10, 4))
    }

    @Test fun clampColBoundsEmptyAndNonEmptyRows() {
        assertEquals(0, DockGrid.clampCol(3, 0))
        assertEquals(0, DockGrid.clampCol(-1, 5))
        assertEquals(2, DockGrid.clampCol(2, 5))
        assertEquals(4, DockGrid.clampCol(9, 5))
    }

    @Test fun sliceBoundsReturnsExpectedRowIndices() {
        assertEquals(0..5, DockGrid.sliceBounds(14, rowIndex = 0, cols = 6))
        assertEquals(6..11, DockGrid.sliceBounds(14, rowIndex = 1, cols = 6))
        assertEquals(12..13, DockGrid.sliceBounds(14, rowIndex = 2, cols = 6))
        assertTrue(DockGrid.sliceBounds(14, rowIndex = 3, cols = 6).isEmpty())
        assertTrue(DockGrid.sliceBounds(0, rowIndex = 0, cols = 6).isEmpty())
    }
}
