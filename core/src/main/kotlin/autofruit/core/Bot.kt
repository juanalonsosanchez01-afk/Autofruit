package autofruit.core

/** What the bot wants to do after looking at a screenshot. */
sealed class Decision {
    /** Tap the screen at ([x], [y]) (screenshot pixels). */
    class Tap(val x: Int, val y: Int, val frame: Frame, val tile: Tile, val plan: List<Tile>) : Decision()

    /** Nothing to do yet; look again later. */
    class Wait(val reason: String, val frame: Frame? = null) : Decision()

    /** The game is over (hand full) or the board is cleared. */
    class Stop(val reason: String, val frame: Frame? = null) : Decision()
}

/**
 * Turns screenshots into taps. A move is only made once two consecutive
 * screenshots agree, so tiles flying to the hand or fading in are never acted on.
 */
class Bot(
    val library: TypeLibrary = TypeLibrary.seeded(),
    /** Tiles the hand holds; having this many in hand loses. */
    private val capacity: Int = 7,
) {
    private val solver = Solver(capacity)
    private val detector = Detector()
    private val classifier = Classifier(library)
    private var lastSignature: String? = null
    private var tappedSignature: String? = null
    private var tappedTile = -1
    private var tappedAt = 0L
    private val ignored = HashSet<Int>()

    var lastFrame: Frame? = null
        private set

    fun reset() {
        lastSignature = null
        tappedSignature = null
        ignored.clear()
    }

    /** [now] is a millisecond clock, used to give the game time to react to a tap. */
    fun decide(img: RgbImage, now: Long = System.currentTimeMillis()): Decision {
        val frame = detector.detect(img)
        if (frame == null) {
            lastSignature = null
            return Decision.Wait("No veo el tablero")
        }
        // Only learn new fruits from settled screenshots.
        classifier.classify(frame, learn = false)
        val sig = signature(frame)
        if (sig != lastSignature) {
            lastSignature = sig
            return Decision.Wait("Esperando a que el tablero se quede quieto", frame)
        }
        classifier.classify(frame, learn = true)
        lastFrame = frame

        if (frame.tray.size >= capacity) return Decision.Stop("La bandeja está llena", frame)
        val lit = frame.board.count { it.lit }
        if (lit == 0) {
            if (frame.tray.isEmpty()) return Decision.Stop("Tablero completado", frame)
            return Decision.Wait("No hay fichas seleccionables", frame)
        }

        // If the previous tap did not change anything the tile was probably not
        // selectable after all; try something else.
        if (sig == tappedSignature) {
            if (now - tappedAt < TAP_TIMEOUT_MS) return Decision.Wait("Esperando a que el juego reaccione", frame)
            ignored += tappedTile
        } else {
            ignored.clear()
        }

        val board = frame.board
        val types = IntArray(board.size) { board[it].type }
        val above = Array(board.size) { j -> aboveOf(board, j) }
        val plan = solver.solve(types, above, frame.tray.map { it.type }, ignored)
            ?: return Decision.Wait("No hay jugada posible", frame)
        val first = plan.moves.first()
        tappedSignature = sig
        tappedTile = first
        tappedAt = now
        val t = board[first]
        return Decision.Tap(t.face.centerX, t.face.centerY, frame, t, plan.moves.map { board[it] })
    }

    private fun aboveOf(board: List<Tile>, j: Int): IntArray {
        val tj = board[j]
        val minOverlap = tj.face.width / 12
        val res = ArrayList<Int>()
        for ((i, ti) in board.withIndex()) {
            if (i == j || ti.layer >= tj.layer) continue
            val o = ti.footprint.intersect(tj.footprint)
            if (o.width > minOverlap && o.height > minOverlap) res += i
        }
        return res.toIntArray()
    }

    private fun signature(f: Frame): String {
        val q = maxOf(1, f.faceW / 8)
        val lit = f.board.filter { it.lit }.map { "${it.face.centerX / q},${it.face.centerY / q}" }.sorted()
        return lit.joinToString(";") + "|" + f.tray.size + "|" + f.board.size
    }

    companion object {
        const val TAP_TIMEOUT_MS = 1500L
    }
}
