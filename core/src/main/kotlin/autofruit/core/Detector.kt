package autofruit.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** A tile found on screen. Rectangles are in full resolution screen pixels. */
class Tile(
    val face: Rect,
    /** Face plus outline and the 3D edge below; what the game uses for overlap. */
    val footprint: Rect,
    /** 0 for lit (selectable) tiles; covered tiles count up the deeper they are. */
    val layer: Int,
    /** Fraction of the face that can be seen. */
    val visible: Float,
    val descriptor: Descriptor,
) {
    var type = -1
    val lit get() = layer == 0
}

class Frame(
    val board: List<Tile>,
    /** Tiles sitting in the hand, left to right. */
    val tray: List<Tile>,
    val trayRect: Rect,
    val faceW: Int,
    val faceH: Int,
)

/**
 * Turns a screenshot of the game into tiles: lit ones, covered ones (fitted from
 * the bits that peek out) and the ones already in the hand.
 */
class Detector {

    fun detect(img: RgbImage): Frame? {
        val ds = maxOf(1, (img.width + 270) / 540)
        val w = img.width / ds
        val h = img.height / ds
        val cls = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * ds * img.width
            for (x in 0 until w) cls[y * w + x] = classify(img.pixels[row + x * ds]).toByte()
        }

        val tray = findTray(cls, w, h) ?: return null
        val boardTop = (h * 0.18f).toInt()

        // Lit tiles: connected runs of the cream face colour. Tiles in the hand are
        // drawn smaller than on the board, so each group gets its own face size.
        val allBlobs = blobs(cls, w, h, BF, BooleanArray(w * h), Rect(0, boardTop, w, h))
            .map { Blob(trim(cls, w, it.rect, BF), it.count) }
        val (trayBlobs, boardBlobs) = allBlobs.partition { it.rect.centerY >= tray.top - it.rect.height / 3 }
        val (fw, fh) = faceSize(boardBlobs, w) ?: faceSize(trayBlobs, w) ?: return null
        val (tw, th) = faceSize(trayBlobs, w) ?: (fw to fh)
        val boardLit = litFaces(boardBlobs, fw, fh).filter { it.bottom < tray.top && hasEdgeBelow(cls, w, h, it) }
        val trayFaces = litFaces(trayBlobs, tw, th).filter { it.centerY < tray.bottom + th / 2 }.sortedBy { it.left }

        val o = maxOf(1, (fw * 0.035f).roundToInt())
        val hb = measureBand(cls, w, h, boardLit, fw, fh)
        val geom = Geom(fw, fh, o, hb)

        val covered = BooleanArray(w * h)
        val tiles = ArrayList<Tile>()
        for (f in boardLit) {
            val fp = geom.footprint(f)
            tiles += Tile(f.scale(ds), fp.scale(ds), 0, 1f, Descriptor.sample(img, f.scale(ds), 1f, null))
            mark(covered, w, h, fp, 1)
        }

        peelCovered(img, ds, cls, w, h, Rect(0, boardTop, w, tray.top), geom, covered, tiles)

        val trayTiles = trayFaces.map { Tile(it.scale(ds), it.scale(ds), 0, 1f, Descriptor.sample(img, it.scale(ds), 1f, null)) }
        return Frame(tiles, trayTiles, tray.scale(ds), fw * ds, fh * ds)
    }

    /** Typical face size among blobs that look like a single tile. */
    private fun faceSize(blobs: List<Blob>, w: Int): Pair<Int, Int>? {
        val c = blobs.filter {
            it.rect.width >= w / 20 && it.rect.height >= w / 20 && it.rect.width <= w / 4 && it.rect.height <= w / 4 &&
                it.fill > 0.3f && it.rect.width.toFloat() / it.rect.height in 0.75f..1.35f
        }
        if (c.isEmpty()) return null
        return median(c.map { it.rect.width }) to median(c.map { it.rect.height })
    }

    private fun litFaces(blobs: List<Blob>, fw: Int, fh: Int): List<Rect> {
        val out = ArrayList<Rect>()
        for (b in blobs) {
            val r = b.rect
            val nx = (r.width.toFloat() / fw).roundToInt()
            val ny = (r.height.toFloat() / fh).roundToInt()
            if (nx < 1 || ny < 1 || nx * ny > 8) continue
            if (abs(r.width - nx * fw) > fw * 0.2f + 2 || abs(r.height - ny * fh) > fh * 0.2f + 2) continue
            // Tiles are drawn at slightly different sizes depending on their layer,
            // so a lone tile keeps the size it was found with.
            if (nx == 1 && ny == 1) {
                out += r
                continue
            }
            // Touching tiles can merge into one blob; split them back on the tile grid.
            for (j in 0 until ny) for (i in 0 until nx) {
                val cx = r.left + r.width * (2 * i + 1) / (2 * nx)
                val cy = r.top + r.height * (2 * j + 1) / (2 * ny)
                out += Rect(cx - fw / 2, cy - fh / 2, cx - fw / 2 + fw, cy - fh / 2 + fh)
            }
        }
        return out
    }

    /** Real tiles have the yellow-green 3D edge under the face; buttons and badges don't. */
    private fun hasEdgeBelow(cls: ByteArray, w: Int, h: Int, f: Rect): Boolean {
        var n = 0
        for (y in f.bottom - 1 until minOf(h, f.bottom + 5)) for (x in maxOf(0, f.left) until minOf(w, f.right))
            if (cls[y * w + x].toInt() == BB) n++
        return n >= f.width / 3
    }

    private class Geom(val fw: Int, val fh: Int, val o: Int, val hb: Int) {
        fun footprint(fx: Int, fy: Int) = Rect(fx - o, fy - o, fx + fw + o, fy + fh + hb + o)
        fun footprint(face: Rect) = Rect(face.left - o, face.top - o, face.right + o, face.bottom + hb + o)
        fun scaled(k: Float) = Geom((fw * k).roundToInt(), (fh * k).roundToInt(), o, (hb * k).roundToInt())
    }

    /**
     * Covered tiles are recovered top-down: each round fits a tile rectangle to
     * every grey face fragment, keeps the fits that the pixels fully agree with
     * (everything they overlap is already explained by tiles above), then treats
     * those tiles as known and goes one layer deeper.
     */
    private fun peelCovered(
        img: RgbImage, ds: Int, cls: ByteArray, w: Int, h: Int, area: Rect,
        g: Geom, covered: BooleanArray, out: MutableList<Tile>,
    ) {
        val minPixels = maxOf(6, g.fw * g.fh / 80)
        val sat = Sat(w, h)
        val tolerance = (g.fw * g.fh * 0.04f).toInt() + 4
        var layer = 1
        while (layer < 40) {
            val remaining = blobs(cls, w, h, DF, covered, area).filter { it.count >= minPixels }
            if (remaining.isEmpty()) break
            sat.build(cls, covered)
            val fits = remaining.map { fit(sat, it, g) }
            var accepted = fits.filter { it.bad <= tolerance }
            if (accepted.isEmpty()) accepted = listOf(fits.minBy { it.bad.toFloat() / (it.blob.count + 1) })

            val placed = ArrayList<Fit>()
            for (f in accepted.sortedByDescending { it.blob.count }) {
                // Off-screen fits come from grey things that are not tiles.
                if (f.x < 0 || f.y < 0 || f.x + f.g.fw > w) continue
                val face = Rect(f.x, f.y, f.x + f.g.fw, f.y + f.g.fh)
                if (placed.any { p -> Rect(p.x, p.y, p.x + p.g.fw, p.y + p.g.fh).intersect(face).area * 2 > face.area }) continue
                placed += f
            }
            val hiddenNow = covered.copyOf()
            val hidden = { x: Int, y: Int ->
                val ax = x / ds; val ay = y / ds
                ax !in 0 until w || ay !in 0 until h || hiddenNow[ay * w + ax]
            }
            for (f in placed) {
                val face = Rect(f.x, f.y, f.x + f.g.fw, f.y + f.g.fh)
                var vis = 0
                for (y in maxOf(0, face.top) until minOf(h, face.bottom))
                    for (x in maxOf(0, face.left) until minOf(w, face.right)) if (!hiddenNow[y * w + x]) vis++
                val full = face.scale(ds)
                out += Tile(full, f.g.footprint(f.x, f.y).scale(ds), layer, vis.toFloat() / face.area,
                    Descriptor.sample(img, full, faceGain(img, ds, cls, w, h, face, hiddenNow), hidden))
            }
            for (f in placed) mark(covered, w, h, f.g.footprint(f.x, f.y), 1)
            // The fragment a tile was fitted to is explained even if the fit is off.
            for (f in accepted) mark(covered, w, h, f.blob.rect, 0)
            layer++
        }
    }

    /** How much brighter the lit face colour is than this covered tile's face. */
    private fun faceGain(img: RgbImage, ds: Int, cls: ByteArray, w: Int, h: Int, face: Rect, hidden: BooleanArray): Float {
        var sum = 0L
        var n = 0
        for (y in maxOf(0, face.top) until minOf(h, face.bottom)) for (x in maxOf(0, face.left) until minOf(w, face.right)) {
            val i = y * w + x
            if (hidden[i] || cls[i].toInt() != DF) continue
            val c = img.get(x * ds, y * ds)
            sum += red(c) + green(c) + blue(c)
            n++
        }
        if (n == 0) return 1f / Descriptor.DIM
        return ((Descriptor.FACE_R + Descriptor.FACE_G + Descriptor.FACE_B) * n / sum).coerceIn(1f, 4f)
    }

    private class Fit(val blob: Blob, val g: Geom, val x: Int, val y: Int, val score: Int, val bad: Int)

    /** Tiles on different layers are drawn at slightly different sizes; try a few. */
    private fun fit(s: Sat, b: Blob, g: Geom): Fit =
        SCALES.map { fitSized(s, b, g.scaled(it)) }.maxBy { it.score }

    private fun fitSized(s: Sat, b: Blob, g: Geom): Fit {
        val r = b.rect
        val x0 = minOf(r.right - g.fw, r.left)
        val x1 = maxOf(r.right - g.fw, r.left)
        val y0 = minOf(r.bottom - g.fh, r.top)
        val y1 = maxOf(r.bottom - g.fh, r.top)
        val c = maxOf(1, g.fw / 16)
        val rad = maxOf(1, g.fw / 8)
        val iw = (g.fw * 0.2f).toInt()
        val ih = (g.fh * 0.2f).toInt()
        var best: Fit? = null
        for (fy in y0..y1) for (fx in x0..x1) {
            val faceIn = Rect(fx + c, fy + c, fx + g.fw - c, fy + g.fh - c)
            val icon = Rect(fx + iw, fy + ih, fx + g.fw - iw, fy + g.fh - ih)
            val bandBottom = fy + g.fh + g.hb
            val strips = arrayOf(
                Rect(fx - g.o, fy + rad, fx, bandBottom - rad),
                Rect(fx + g.fw, fy + rad, fx + g.fw + g.o, bandBottom - rad),
                Rect(fx + rad, fy - g.o, fx + g.fw - rad, fy),
                Rect(fx + rad, bandBottom, fx + g.fw - rad, bandBottom + g.o),
            )
            val band = Rect(fx + rad, fy + g.fh + 1, fx + g.fw - rad, bandBottom - 1)

            var pos = s.sum(Sat.P_DF, faceIn) + s.sum(Sat.P_DB, band)
            var bad = s.sum(Sat.P_BAD, faceIn) +
                (s.sum(Sat.P_OUT, faceIn) - s.sum(Sat.P_OUT, icon)) + (s.sum(Sat.P_DB, faceIn) - s.sum(Sat.P_DB, icon)) +
                s.sum(Sat.P_BAD, band) + s.sum(Sat.P_DF, band)
            for (st in strips) {
                pos += 3 * s.sum(Sat.P_OUT, st)
                bad += s.sum(Sat.P_BAD, st) + s.sum(Sat.P_DF, st)
            }
            val score = pos - 3 * bad
            if (best == null || score > best.score) best = Fit(b, g, fx, fy, score, bad)
        }
        return best!!
    }

    /** Shrinks [r] past thin rows/columns that only leak into a neighbour. */
    private fun trim(cls: ByteArray, w: Int, r: Rect, target: Int): Rect {
        fun col(x: Int) = (r.top until r.bottom).count { cls[it * w + x].toInt() == target }
        fun row(y: Int) = (r.left until r.right).count { cls[y * w + it].toInt() == target }
        var l = r.left; var rr = r.right; var t = r.top; var b = r.bottom
        while (rr - l > 2 && col(l) < r.height / 4) l++
        while (rr - l > 2 && col(rr - 1) < r.height / 4) rr--
        while (b - t > 2 && row(t) < r.width / 4) t++
        while (b - t > 2 && row(b - 1) < r.width / 4) b--
        return Rect(l, t, rr, b)
    }

    private fun measureBand(cls: ByteArray, w: Int, h: Int, faces: List<Rect>, fw: Int, fh: Int): Int {
        val bands = faces.mapNotNull { f ->
            val x = f.left + fw / 6
            var y = f.bottom
            // Skip the anti-aliased seam between face and edge.
            if (y < h && cls[y * w + x].toInt() != BB) y++
            var n = 0
            while (y + n < h && cls[(y + n) * w + x].toInt() == BB) n++
            if (n >= 2) n + (y - f.bottom) else null
        }
        return if (bands.isEmpty()) (fh * 0.17f).roundToInt() else median(bands)
    }

    private fun findTray(cls: ByteArray, w: Int, h: Int): Rect? {
        var bestTop = -1; var bestBottom = -1
        var top = -1
        for (y in h / 2 until h) {
            var n = 0
            for (x in 0 until w) if (cls[y * w + x].toInt() == TRAY) n++
            if (n >= w / 20) {
                if (top < 0) top = y
                if (y + 1 - top > bestBottom - bestTop) { bestTop = top; bestBottom = y + 1 }
            } else top = -1
        }
        if (bestTop < 0 || bestBottom - bestTop < h / 60) return null
        var left = w; var right = 0
        for (y in bestTop until bestBottom) for (x in 0 until w) if (cls[y * w + x].toInt() == TRAY) {
            if (x < left) left = x
            if (x > right) right = x
        }
        return Rect(left, bestTop, right + 1, bestBottom)
    }

    class Blob(val rect: Rect, val count: Int) {
        val fill get() = count.toFloat() / rect.area
    }

    private fun blobs(cls: ByteArray, w: Int, h: Int, target: Int, mask: BooleanArray, area: Rect): List<Blob> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val res = ArrayList<Blob>()
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val i = y * w + x
            if (seen[i] || mask[i] || cls[i].toInt() != target) continue
            var sp = 0
            stack[sp++] = i
            seen[i] = true
            var l = x; var r = x; var t = y; var b = y; var n = 0
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w; val py = p / w
                n++
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > b) b = py
                if (px > area.left) push(p - 1, cls, mask, seen, target, stack, sp).let { sp = it }
                if (px < area.right - 1) push(p + 1, cls, mask, seen, target, stack, sp).let { sp = it }
                if (py > area.top) push(p - w, cls, mask, seen, target, stack, sp).let { sp = it }
                if (py < area.bottom - 1) push(p + w, cls, mask, seen, target, stack, sp).let { sp = it }
            }
            res += Blob(Rect(l, t, r + 1, b + 1), n)
        }
        return res
    }

    private fun push(q: Int, cls: ByteArray, mask: BooleanArray, seen: BooleanArray, target: Int, stack: IntArray, sp: Int): Int {
        if (seen[q] || mask[q] || cls[q].toInt() != target) return sp
        seen[q] = true
        stack[sp] = q
        return sp + 1
    }

    private fun mark(m: BooleanArray, w: Int, h: Int, r: Rect, pad: Int) {
        for (y in maxOf(0, r.top - pad) until minOf(h, r.bottom + pad))
            for (x in maxOf(0, r.left - pad) until minOf(w, r.right + pad)) m[y * w + x] = true
    }

    /** Summed-area tables of the pixel classes that are not yet explained. */
    private class Sat(val w: Int, val h: Int) {
        val planes = Array(4) { IntArray((w + 1) * (h + 1)) }

        fun build(cls: ByteArray, masked: BooleanArray) {
            val sw = w + 1
            for (p in planes.indices) {
                val s = planes[p]
                for (y in 0 until h) {
                    var run = 0
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (!masked[i] && plane(cls[i].toInt()) == p) run++
                        s[(y + 1) * sw + x + 1] = s[y * sw + x + 1] + run
                    }
                }
            }
        }

        private fun plane(c: Int) = when (c) {
            Detector.DF -> P_DF
            Detector.DB -> P_DB
            Detector.OUT -> P_OUT
            Detector.BG, Detector.BF, Detector.BB, Detector.TRAY -> P_BAD
            else -> -1
        }

        fun sum(p: Int, r: Rect): Int {
            val l = r.left.coerceIn(0, w); val rr = r.right.coerceIn(0, w)
            val t = r.top.coerceIn(0, h); val b = r.bottom.coerceIn(0, h)
            if (rr <= l || b <= t) return 0
            val s = planes[p]; val sw = w + 1
            return s[b * sw + rr] - s[t * sw + rr] - s[b * sw + l] + s[t * sw + l]
        }

        companion object {
            const val P_DF = 0
            const val P_DB = 1
            const val P_OUT = 2
            const val P_BAD = 3
        }
    }

    companion object {
        const val OTHER = 0
        const val BG = 1
        const val BF = 2
        const val DF = 3
        const val BB = 4
        const val DB = 5
        const val OUT = 6
        const val TRAY = 7

        fun classify(c: Int): Int {
            val r = red(c); val g = green(c); val b = blue(c)
            if (abs(r - 255) <= 22 && abs(g - 255) <= 22 && abs(b - 243) <= 24) return BF
            if (abs(r - 97) <= 12 && abs(g - 112) <= 12 && abs(b - 81) <= 12) return TRAY
            val mx = maxOf(r, maxOf(g, b))
            if (mx <= 60) return OUT
            // Covered tiles are the lit face colour darkened by a depth dependent factor.
            if (r in 55..180 && abs(r - g) <= 7 && b <= r + 3 && b >= r * 0.88f - 6) return DF
            if (g >= 225 && g - r >= 15 && g - b >= 40 && r >= 170) return BG
            if (g > r && r in 145..218 && g in 172..228 && b <= 100) return BB
            if (g in 55..171 && r <= g && r >= g * 0.78f && b <= g * 0.5f) return DB
            if (mx <= 100) return OUT
            return OTHER
        }

        private val SCALES = floatArrayOf(1f, 0.95f, 0.9f)

        private fun median(v: List<Int>) = v.sorted()[v.size / 2]
    }
}
