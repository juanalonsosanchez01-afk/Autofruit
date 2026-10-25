package autofruit.core

/** What the bot wants to do after looking at a screenshot. */
sealed class Decision {
    /**
     * Tap [taps] in order (centres in screenshot pixels). They are all lit tiles
     * of the current screenshot, so they can be tapped back to back.
     */
    class Tap(val taps: List<Tile>, val frame: Frame, val plan: List<Tile>) : Decision() {
        val tile get() = taps.first()
        val x get() = tile.face.centerX
        val y get() = tile.face.centerY
    }

    /** Nothing to do yet; look again later. */
    class Wait(val reason: String, val frame: Frame? = null) : Decision()

    /** The game is lost (hand full). */
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
    /** Most moves played from one screenshot (only lit tiles, never ones a move uncovers). */
    private val maxBatch: Int = 3,
    /** Pause before playing a board that appears after the previous one disappeared. */
    private val newLevelDelayMs: Long = 5000,
) {
    private val solver = Solver(capacity)
    private val detector = Detector()
    private val classifier = Classifier(library)
    private var lastSignature: String? = null
    private var tappedSignature: String? = null
    private var tappedTile = -1
    private var tappedAt = 0L
    private val ignored = HashSet<Int>()
    private var sawBoard = false
    private var boardGone = false
    private var resumeAt = 0L
    private val stillness = Stillness()
    private var movingSince = -1L
    private var stillCount = 0

    var lastFrame: Frame? = null
        private set

    fun reset() {
        stillness.reset()
        movingSince = -1L
        stillCount = 0
        lastSignature = null
        tappedSignature = null
        ignored.clear()
    }

    /**
     * [now] is a millisecond clock, used to give the game time to react to a tap.
     * [ignore] are screen areas to leave out of the stillness check (the bot's
     * own floating button, whose label changes all the time).
     */
    fun decide(img: RgbImage, now: Long = System.currentTimeMillis(), ignore: List<Rect> = emptyList()): Decision {
        val frame = detector.detect(img)?.takeIf { f -> f.board.isNotEmpty() || f.tray.isNotEmpty() }
        if (frame == null) {
            stillness.reset()
            stillCount = 0
            lastSignature = null
            if (sawBoard) boardGone = true
            return Decision.Wait("No veo el tablero")
        }
        if (boardGone) {
            // The level ended and a new board is showing up; let it settle in.
            boardGone = false
            reset()
            resumeAt = now + newLevelDelayMs
        }
        if (now < resumeAt) return Decision.Wait("Nivel nuevo: empiezo en ${(resumeAt - now + 999) / 1000} s", frame)
        sawBoard = true

        // Only plan (and learn new fruits) on a screenshot identical to the one
        // before: mid-animation the hand and the board are misread.
        val area = Rect(0, (img.height * 0.18f).toInt(), img.width, minOf(img.height, frame.trayRect.bottom))
        val still = stillness.isStill(img, area, ignore)
        classifier.classify(frame, learn = false)
        val sig = signature(frame)
        val sameSig = sig == lastSignature
        lastSignature = sig
        if (still) {
            movingSince = -1L
            stillCount++
        } else {
            stillCount = 0
            if (movingSince < 0) movingSince = now
        }
        // Something on screen that never stops moving must not block play forever;
        // after a while, settle for the tiles being where they were.
        val settled = still || (sameSig && movingSince >= 0 && now - movingSince > MOVING_GIVE_UP_MS)
        if (!sameSig || !settled) return Decision.Wait("Esperando a que el tablero se quede quieto", frame)
        // A fruit the catalogue doesn't know is either new to this level or a tile
        // caught mid-animation (landing in the hand, lighting up). Only learn it once
        // the screen has stayed identical for a while; until then, don't guess.
        val unknown = (frame.tray + frame.board.filter { it.lit }).any { it.type < 0 }
        if (unknown) {
            if (stillCount < 2 && (movingSince < 0 || now - movingSince <= MOVING_GIVE_UP_MS))
                return Decision.Wait("Fruta desconocida: comprobando", frame)
            classifier.learnMissing(frame)
        }
        lastFrame = frame

        if (frame.tray.size >= capacity) return Decision.Stop("La bandeja está llena", frame)
        if (frame.board.none { it.lit }) return Decision.Wait("No hay fichas seleccionables", frame)

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
        val lit = BooleanArray(board.size) { board[it].lit }
        val plan = solver.solve(types, above, frame.tray.map { it.type }, ignored, lit)
            ?: return Decision.Wait("No hay jugada posible", frame)
        // Moves on tiles that are lit right now don't depend on anything a move
        // reveals, so a run of them can be played without looking again.
        val batch = plan.moves.takeWhile { lit[it] }.take(maxBatch).ifEmpty { plan.moves.take(1) }
        tappedSignature = sig
        tappedTile = batch.first()
        tappedAt = now
        return Decision.Tap(batch.map { board[it] }, frame, plan.moves.map { board[it] })
    }

    private fun aboveOf(board: List<Tile>, j: Int): IntArray {
        val tj = board[j]
        if (tj.lit) return IntArray(0)
        val res = ArrayList<Int>()
        for (minOverlap in intArrayOf(tj.face.width / 12, 0)) {
            for ((i, ti) in board.withIndex()) {
                if (i == j || ti.layer >= tj.layer) continue
                val o = ti.footprint.intersect(tj.footprint)
                if (o.width > minOverlap && o.height > minOverlap) res += i
            }
            // A covered tile always has something on top; if a clear overlap was not
            // found, settle for any touching tile rather than calling it free.
            if (res.isNotEmpty()) break
        }
        return res.toIntArray()
    }

    private fun signature(f: Frame): String {
        val q = maxOf(1, f.faceW / 8)
        val lit = f.board.filter { it.lit }.map { "${it.face.centerX / q},${it.face.centerY / q}" }.sorted()
        return lit.joinToString(";") + "|" + f.tray.joinToString(",") { "${it.face.centerX / q}" }
    }

    companion object {
        const val TAP_TIMEOUT_MS = 1500L
        const val MOVING_GIVE_UP_MS = 3000L
    }
}
