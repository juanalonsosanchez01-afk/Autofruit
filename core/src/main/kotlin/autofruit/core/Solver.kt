package autofruit.core

/**
 * Plans which tile to pick next.
 *
 * The board is modelled from what is visible: lit tiles can be picked, covered
 * tiles become pickable once every tile overlapping them from above is gone, and
 * covered tiles whose fruit could be recognised from the part that peeks out are
 * planned with like any other. A beam search explores move sequences up to
 * [maxDepth] deep, scoring each position by completed triples, hand pressure and
 * how many fruits still have a way to be completed. Only the first move of the best
 * sequence is played; the board is read again after every move.
 */
class Solver(
    private val capacity: Int = 7,
    private val beamWidth: Int = 250,
    private val maxDepth: Int = 30,
    private val timeBudgetMs: Long = 600,
) {
    class Plan(val moves: List<Int>, val score: Double)

    private class Node(
        val parent: Node?,
        val move: Int,
        val removed: BooleanArray,
        val blocked: IntArray,
        val tray: IntArray,
        val total: Int,
        val cleared: Int,
        val removedCount: Int,
        val hash: Long,
    ) {
        var score = 0.0
        fun path(): List<Int> {
            val out = ArrayList<Int>()
            var n: Node? = this
            while (n?.parent != null) { out += n.move; n = n.parent }
            return out.reversed()
        }
    }

    /**
     * [types] is the fruit of each board tile (-1 when unknown), [above] lists for
     * each tile the tiles lying on top of it, [tray] the fruits already in hand.
     * Tiles listed in [avoid] are not picked first (e.g. taps that did nothing).
     */
    fun solve(types: IntArray, above: Array<IntArray>, tray: List<Int>, avoid: Set<Int> = emptySet()): Plan? {
        val n = types.size
        val nTypes = maxOf(types.maxOrNull() ?: -1, tray.maxOrNull() ?: -1) + 1
        val below = Array(n) { ArrayList<Int>() }
        for (i in 0 until n) for (a in above[i]) below[a] += i
        val belowArr = Array(n) { below[it].toIntArray() }

        val trayCounts = IntArray(nTypes)
        for (t in tray) if (t >= 0) trayCounts[t]++
        val root = Node(null, -1, BooleanArray(n), IntArray(n) { above[it].size }, trayCounts,
            tray.size, 0, 0, 0L)

        // Completing a triple can never hurt: it frees two slots and only ever
        // uncovers tiles, so it is played straight away.
        for (i in 0 until n) {
            if (root.blocked[i] == 0 && types[i] >= 0 && trayCounts[types[i]] == 2 && i !in avoid)
                return Plan(listOf(i), Double.MAX_VALUE)
        }

        val deadline = System.currentTimeMillis() + timeBudgetMs
        var beam = listOf(root)
        var best: Node? = null
        val seen = HashSet<Long>()
        for (depth in 1..maxDepth) {
            val next = ArrayList<Node>()
            for (node in beam) {
                for (i in 0 until n) {
                    if (node.removed[i] || node.blocked[i] != 0 || types[i] < 0) continue
                    if (depth == 1 && i in avoid) continue
                    val child = play(node, i, types[i], belowArr) ?: continue
                    if (!seen.add(child.hash * 31 + child.tray.contentHashCode())) continue
                    child.score = evaluate(child, types)
                    next += child
                }
            }
            if (next.isEmpty()) break
            next.sortByDescending { it.score }
            beam = if (next.size > beamWidth) next.subList(0, beamWidth) else next
            if (best == null || beam[0].score > best.score) best = beam[0]
            if (System.currentTimeMillis() > deadline) break
        }
        if (best == null) {
            // Every move overflows the hand; pick anything rather than stall.
            val i = (0 until n).firstOrNull { root.blocked[it] == 0 && types[it] >= 0 } ?: return null
            return Plan(listOf(i), Double.NEGATIVE_INFINITY)
        }
        return Plan(best.path(), best.score)
    }

    private fun play(node: Node, i: Int, type: Int, below: Array<IntArray>): Node? {
        val tray = node.tray.copyOf()
        var total = node.total + 1
        var cleared = node.cleared
        tray[type]++
        if (tray[type] == 3) {
            tray[type] = 0
            total -= 3
            cleared += 3
        }
        if (total >= capacity) return null
        val removed = node.removed.copyOf()
        removed[i] = true
        val blocked = node.blocked.copyOf()
        for (b in below[i]) blocked[b]--
        return Node(node, i, removed, blocked, tray, total, cleared, node.removedCount + 1,
            node.hash xor ZOBRIST.get(i))
    }

    private fun evaluate(s: Node, types: IntArray): Double {
        val nTypes = s.tray.size
        val free = IntArray(nTypes)
        val left = IntArray(nTypes)
        var freeKnown = 0
        var freeUnknown = 0
        for (i in types.indices) {
            if (s.removed[i]) continue
            val t = types[i]
            if (t >= 0) left[t]++
            if (s.blocked[i] == 0) {
                if (t >= 0) { free[t]++; freeKnown++ } else freeUnknown++
            }
        }
        var v = 1000.0 * (s.cleared / 3) - TRAY_PENALTY[s.total]
        for (t in 0 until nTypes) {
            val c = s.tray[t]
            if (c == 0) continue
            v += when {
                free[t] >= 3 - c -> 120.0 * c
                left[t] >= 3 - c -> 30.0 * c
                else -> -60.0 * c
            }
        }
        // Pairs on the board that could be taken together also help.
        for (t in 0 until nTypes) if (s.tray[t] == 0 && free[t] >= 3) v += 40.0
        return v + 8.0 * freeKnown + 12.0 * freeUnknown + 3.0 * s.removedCount
    }

    companion object {
        private val TRAY_PENALTY = doubleArrayOf(0.0, 10.0, 40.0, 100.0, 220.0, 450.0, 900.0, 5000.0, 5000.0, 5000.0)

        private object ZOBRIST {
            private val keys = java.util.Random(7).let { r -> LongArray(4096) { r.nextLong() } }
            fun get(i: Int) = keys[i % keys.size] * (1 + i / keys.size)
        }
    }
}
