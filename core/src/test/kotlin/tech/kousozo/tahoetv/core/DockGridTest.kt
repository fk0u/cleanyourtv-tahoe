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

    @Test fun clampRowMovesOffEmptyDock() {
        assertEquals(2, DockGrid.clampRow(row = 5, dockSize = 4, padRowCount = 2))
        assertEquals(1, DockGrid.clampRow(row = 0, dockSize = 0, padRowCount = 2))
        assertEquals(-1, DockGrid.clampRow(row = 0, dockSize = 0, padRowCount = 0))
        assertEquals(-2, DockGrid.clampRow(row = -5, dockSize = 4, padRowCount = 2))
    }

    @Test fun moveHorizontalDoesNotWrapEdges() {
        assertEquals(0, DockGrid.moveHorizontal(col = 0, dc = -1, rowCount = 4))
        assertEquals(1, DockGrid.moveHorizontal(col = 2, dc = -1, rowCount = 4))
        assertEquals(3, DockGrid.moveHorizontal(col = 2, dc = 1, rowCount = 4))
        assertEquals(3, DockGrid.moveHorizontal(col = 3, dc = 1, rowCount = 4))
    }

    @Test fun nextVerticalRowAndTargetColRestorePositions() {
        // Skips empty hero and dock rows when moving from chips (-2) down to launchpad (1)
        assertEquals(1, DockGrid.nextVerticalRow(row = -2, dr = 1, dockSize = 0, heroCount = 0, padRowCount = 2, chipCount = 3))
        // Normal step down from hero (-1) to dock (0)
        assertEquals(0, DockGrid.nextVerticalRow(row = -1, dr = 1, dockSize = 5, heroCount = 1, padRowCount = 2, chipCount = 3))
        // Blocked beyond bottom launchpad row
        assertEquals(2, DockGrid.nextVerticalRow(row = 2, dr = 1, dockSize = 5, heroCount = 1, padRowCount = 2, chipCount = 3))

        assertEquals(2, DockGrid.targetColForRow(targetRow = -2, currentCol = 4, chipCol = 2, dockCol = 3, targetRowCount = 4))
        assertEquals(0, DockGrid.targetColForRow(targetRow = -1, currentCol = 4, chipCol = 2, dockCol = 3, targetRowCount = 1))
        assertEquals(1, DockGrid.targetColForRow(targetRow = 0, currentCol = 0, chipCol = 2, dockCol = 5, targetRowCount = 2))
        assertEquals(2, DockGrid.targetColForRow(targetRow = 1, currentCol = 2, chipCol = 0, dockCol = 3, targetRowCount = 6))
    }
}
