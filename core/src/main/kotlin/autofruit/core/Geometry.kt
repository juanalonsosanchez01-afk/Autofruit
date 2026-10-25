package autofruit.core

/** Packed ARGB image, the format both Android Bitmaps and java.awt images use. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size >= width * height) { "pixel buffer too small" }
    }

    fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/** Half-open integer rectangle [left, right) x [top, bottom). */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = if (width <= 0 || height <= 0) 0 else width * height

    fun intersect(o: Rect) = Rect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))
    fun inset(dx: Int, dy: Int) = Rect(left + dx, top + dy, right - dx, bottom - dy)
    fun scale(f: Int) = Rect(left * f, top * f, right * f, bottom * f)
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    override fun toString() = "[$left,$top ${width}x$height]"
}

internal fun red(c: Int) = (c shr 16) and 0xFF
internal fun green(c: Int) = (c shr 8) and 0xFF
internal fun blue(c: Int) = c and 0xFF
