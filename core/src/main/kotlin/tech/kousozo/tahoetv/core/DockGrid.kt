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

    /** Clamps focused column index into [0, rowCount - 1], returning 0 if the row is empty. */
    @JvmStatic
    fun clampCol(col: Int, rowCount: Int): Int {
        if (rowCount <= 0) return 0
        return col.coerceIn(0, rowCount - 1)
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
