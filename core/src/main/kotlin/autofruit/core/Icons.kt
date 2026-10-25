package autofruit.core

import kotlin.math.sqrt

/**
 * Coarse colour grid of a tile face, used to tell fruits apart. Covered (dimmed)
 * tiles are brightened back before sampling, and cells hidden under other tiles
 * are marked invalid so partially visible tiles can still be compared.
 */
class Descriptor(val r: FloatArray, val g: FloatArray, val b: FloatArray, val valid: BooleanArray) {
    val validCount = valid.count { it }

    /** Cells that show part of the fruit drawing rather than the plain tile face. */
    fun isInk(i: Int) = valid[i] && (FACE_R - r[i]) + (FACE_G - g[i]) + (FACE_B - b[i]) > 45f

    val inkCount = valid.indices.count { isInk(it) }

    companion object {
        const val GRID = 24
        const val FACE_R = 255f
        const val FACE_G = 255f
        const val FACE_B = 243f

        /** Typical darkening of a tile one layer below the top. */
        const val DIM = 0.607f

        /**
         * Samples [face] of [img], multiplying colours by [gain] to undo the
         * darkening of covered tiles. [hidden] says (in full resolution coordinates)
         * which pixels belong to tiles drawn on top.
         */
        fun sample(img: RgbImage, face: Rect, gain: Float, hidden: ((Int, Int) -> Boolean)?): Descriptor {
            val n = GRID * GRID
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            val valid = BooleanArray(n)
            val k = gain
            val step = maxOf(1, face.width / (GRID * 3))
            // Cells overlap their neighbours by half a cell, which smooths the grid
            // and makes it less sensitive to small misalignments.
            for (cy in 0 until GRID) {
                val y0 = maxOf(face.top, face.top + face.height * (2 * cy - 1) / (2 * GRID))
                val y1 = minOf(face.bottom, face.top + face.height * (2 * cy + 3) / (2 * GRID))
                for (cx in 0 until GRID) {
                    val x0 = maxOf(face.left, face.left + face.width * (2 * cx - 1) / (2 * GRID))
                    val x1 = minOf(face.right, face.left + face.width * (2 * cx + 3) / (2 * GRID))
                    var sr = 0f; var sg = 0f; var sb = 0f
                    var seen = 0; var total = 0
                    var y = y0
                    while (y < y1) {
                        var x = x0
                        while (x < x1) {
                            total++
                            if (x in 0 until img.width && y in 0 until img.height && (hidden == null || !hidden(x, y))) {
                                val c = img.get(x, y)
                                sr += minOf(255f, red(c) * k)
                                sg += minOf(255f, green(c) * k)
                                sb += minOf(255f, blue(c) * k)
                                seen++
                            }
                            x += step
                        }
                        y += step
                    }
                    val i = cy * GRID + cx
                    if (total > 0 && seen >= total * 0.6f) {
                        valid[i] = true
                        r[i] = sr / seen; g[i] = sg / seen; b[i] = sb / seen
                    }
                }
            }
            return Descriptor(r, g, b, valid)
        }
    }
}

/** Result of comparing two descriptors on the cells both can see. */
class Match(val distance: Float, val common: Int, val commonInk: Int)

fun compare(a: Descriptor, b: Descriptor): Match {
    // Allow a small misalignment between the two faces.
    var best = compareShifted(a, b, 0, 0)
    for (dy in -2..2) for (dx in -2..2) {
        if (dx == 0 && dy == 0) continue
        val m = compareShifted(a, b, dx, dy)
        if (m.common * 4 >= best.common * 3 && m.distance < best.distance) best = m
    }
    return best
}

private fun compareShifted(a: Descriptor, b: Descriptor, dx: Int, dy: Int): Match {
    val n = Descriptor.GRID
    var sum = 0f
    var common = 0
    var ink = 0
    for (y in maxOf(0, -dy) until minOf(n, n - dy)) for (x in maxOf(0, -dx) until minOf(n, n - dx)) {
        val i = y * n + x
        val j = (y + dy) * n + x + dx
        if (!a.valid[i] || !b.valid[j]) continue
        val dr = a.r[i] - b.r[j]
        val dg = a.g[i] - b.g[j]
        val db = a.b[i] - b.b[j]
        sum += dr * dr + dg * dg + db * db
        common++
        if (a.isInk(i) || b.isInk(j)) ink++
    }
    if (common == 0) return Match(Float.MAX_VALUE, 0, 0)
    return Match(sqrt(sum / (3 * common)), common, ink)
}

/**
 * Remembers what every fruit looks like. Lit tiles (fully visible) teach it new
 * fruits; covered tiles are only matched against what it already knows, so a
 * sliver of a hidden tile can still be identified once that fruit was seen lit.
 */
class TypeLibrary {
    private val protos = mutableListOf<Descriptor>()
    val size get() = protos.size

    /** Type id for a fully visible tile, learning a new type when nothing matches. */
    fun identifyLit(d: Descriptor, learn: Boolean = true): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, p) in protos.withIndex()) {
            val m = compare(d, p)
            if (m.distance < bestD) { bestD = m.distance; best = i }
        }
        if (best >= 0 && bestD <= LIT_SAME) return best
        if (!learn) return -1
        protos += d
        return protos.size - 1
    }

    /** Type id for a partially visible covered tile, or -1 when unsure. */
    fun identifyCovered(d: Descriptor): Int {
        if (d.validCount < MIN_CELLS || d.inkCount < MIN_INK) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for ((i, p) in protos.withIndex()) {
            val m = compare(d, p)
            if (m.commonInk < MIN_INK) continue
            if (m.distance < bestD) { second = bestD; bestD = m.distance; best = i }
            else if (m.distance < second) second = m.distance
        }
        if (best < 0 || bestD > COVERED_SAME) return -1
        // A fragment that fits two fruits about equally well is not trusted.
        if (second < Float.MAX_VALUE && second < bestD * 1.25f) return -1
        return best
    }

    /** Every known type with its distance to [d], closest first (for debugging). */
    fun ranked(d: Descriptor): List<Pair<Int, Match>> =
        protos.mapIndexed { i, p -> i to compare(d, p) }.sortedBy { it.second.distance }

    fun clear() = protos.clear()

    fun write(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(out)
        d.writeInt(Descriptor.GRID)
        d.writeInt(protos.size)
        for (p in protos) for (i in 0 until Descriptor.GRID * Descriptor.GRID) {
            d.writeFloat(p.r[i]); d.writeFloat(p.g[i]); d.writeFloat(p.b[i])
        }
        d.flush()
    }

    /** Adds the fruits stored by [write]. */
    fun read(input: java.io.InputStream) {
        val d = java.io.DataInputStream(input)
        val n = Descriptor.GRID * Descriptor.GRID
        require(d.readInt() == Descriptor.GRID) { "seed grid mismatch" }
        repeat(d.readInt()) {
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            for (i in 0 until n) { r[i] = d.readFloat(); g[i] = d.readFloat(); b[i] = d.readFloat() }
            protos += Descriptor(r, g, b, BooleanArray(n) { true })
        }
    }

    companion object {
        const val LIT_SAME = 20f
        const val COVERED_SAME = 26f

        /** Library preloaded with the fruits seen in the reference screenshots. */
        fun seeded(): TypeLibrary {
            val lib = TypeLibrary()
            TypeLibrary::class.java.getResourceAsStream("/autofruit/seed-types.bin")?.use { lib.read(it) }
            return lib
        }
        const val MIN_CELLS = 24
        const val MIN_INK = 6
    }
}
