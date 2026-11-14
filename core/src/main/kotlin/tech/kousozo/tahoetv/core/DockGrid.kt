package tech.kousozo.tahoetv.core

/**
 * Pure, framework-free layout and focus geometry for the TV launcher dock and launchpad grid.
 * Extracted from Home.java so grid math, simple-mode capping, and bounds clamping can be unit-tested.
 */
object DockGrid {
    const val MIN_COLS = 4
    const val MAX_COLS = 7

    /** Returns the active grid column count, clamped to 4..7 and capped at 4 in simple mode. */
    @JvmStatic
    fun effectiveCols(rawCols: Int, simpleMode: Boolean): Int {
        val clamped = rawCols.coerceIn(MIN_COLS, MAX_COLS)
        return if (simpleMode) minOf(clamped, MIN_COLS) else clamped
    }

    /** Returns how many Launchpad rows are needed for [itemCount] apps at [cols] columns. */
    @JvmStatic
    fun padRows(itemCount: Int, cols: Int): Int {
        if (itemCount <= 0) return 0
        val c = cols.coerceAtLeast(1)
        return (itemCount + c - 1) / c
    }

    /** Clamps focused row index across chips (-2), hero (-1), dock (0), and launchpad rows (1..[padRowCount]). */
    @JvmStatic
    fun clampRow(row: Int, dockSize: Int, padRowCount: Int): Int {
        var r = minOf(row, padRowCount)
        if (r == 0 && dockSize <= 0) r = if (padRowCount > 0) 1 else -1
        return r.coerceAtLeast(-2)
    }

    /** Clamps focused column index into [0, rowCount - 1], returning 0 if the row is empty. */
    @JvmStatic
    fun clampCol(col: Int, rowCount: Int): Int {
        if (rowCount <= 0) return 0
        return col.coerceIn(0, rowCount - 1)
    }

    /** Steps horizontally by [dc] within [0, rowCount - 1] without wrapping or falling through. */
    @JvmStatic
    fun moveHorizontal(col: Int, dc: Int, rowCount: Int): Int {
        val next = col + dc
        return if (next in 0 until rowCount) next else col
    }

    /** Computes the next row for a vertical step [dr], skipping empty dock/hero rows, or returns [row] if blocked. */
    @JvmStatic
    fun nextVerticalRow(row: Int, dr: Int, dockSize: Int, heroCount: Int, padRowCount: Int, chipCount: Int): Int {
        if (dr == 0) return row
        var target = row + dr
        while ((target == 0 && dockSize <= 0) || (target == -1 && heroCount <= 0)) {
            target += dr
        }
        if (target < -2 || target > padRowCount) return row
        if (target == -2 && chipCount <= 0) return row
        return target
    }

    /** Resolves and clamps the target column for [targetRow], restoring remembered chip/dock positions. */
    @JvmStatic
    fun targetColForRow(targetRow: Int, currentCol: Int, chipCol: Int, dockCol: Int, targetRowCount: Int): Int {
        val preferred = when (targetRow) {
            -2 -> chipCol
            -1 -> 0
            0 -> dockCol
            else -> currentCol
        }
        return clampCol(preferred, targetRowCount)
    }

    /** Returns the 0-based index slice `[start..endInclusive]` for row [rowIndex] of [total] items. */
    @JvmStatic
    fun sliceBounds(total: Int, rowIndex: Int, cols: Int): IntRange {
        if (total <= 0 || rowIndex < 0) return IntRange.EMPTY
        val c = cols.coerceAtLeast(1)
        val start = rowIndex * c
        if (start >= total) return IntRange.EMPTY
        return start until minOf(total, start + c)
    }
}
