package autofruit.core

import kotlin.math.abs

/**
 * Tells whether the play area looks the same as in the previous screenshot.
 * Tiles flying to the hand, triples vanishing and tiles lighting up all change
 * pixels, so a move is only planned on a screenshot that matches the one before.
 */
class Stillness(private val step: Int = 6) {
    private var prev: IntArray? = null
    private var prevArea: Rect? = null

    /** Last measured share of sample points that changed, for diagnostics. */
    var lastChange = 0f
        private set

    fun reset() {
        prev = null
    }

    /** Samples [area] (minus [ignore]) and compares it with the previous call. */
    fun isStill(img: RgbImage, area: Rect, ignore: List<Rect>): Boolean {
        val cols = (area.width + step - 1) / step
        val rows = (area.height + step - 1) / step
        val cur = IntArray(cols * rows)
        for (r in 0 until rows) {
            val y = area.top + r * step
            for (c in 0 until cols) {
                val x = area.left + c * step
                cur[r * cols + c] = if (ignore.any { it.contains(x, y) }) -1 else {
                    val p = img.get(x, y)
                    (red(p) + 2 * green(p) + blue(p)) / 4
                }
            }
        }
        val before = prev
        val still = before != null && prevArea == area && before.size == cur.size && run {
            var changed = 0
            for (i in cur.indices) {
                if (cur[i] < 0 || before[i] < 0) continue
                if (abs(cur[i] - before[i]) > THRESHOLD) changed++
            }
            lastChange = changed.toFloat() / cur.size
            changed <= maxOf(2, (cur.size * MAX_CHANGED).toInt())
        }
        prev = cur
        prevArea = area
        return still
    }

    companion object {
        /** Brightness difference that counts as a changed pixel. */
        const val THRESHOLD = 40

        /** Share of changed sample points tolerated (compression noise in recordings). */
        const val MAX_CHANGED = 0.002f
    }
}
