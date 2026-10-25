package autofruit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SolverTest {
    private val none = IntArray(0)

    @Test
    fun completesTripleFirst() {
        val plan = assertNotNull(Solver().solve(intArrayOf(0, 1, 1), arrayOf(none, none, none), listOf(0, 0)))
        assertEquals(0, plan.moves.first())
    }

    @Test
    fun takesThreeOfAKindTogether() {
        // Three free 2s and one 3; the hand already holds a 3 and a 4.
        val plan = assertNotNull(Solver().solve(intArrayOf(2, 2, 2, 3), arrayOf(none, none, none, none), listOf(3, 4)))
        assertEquals(setOf(0, 1, 2), plan.moves.take(3).toSet())
    }

    @Test
    fun digsForTheMissingTile() {
        // Hand: 0,0,1,1,2,2 (one slot left). Tile 0 (type 3) covers tile 1 (type 0).
        // Taking tile 2 (type 4) would fill the hand; uncovering the 0 is the only way.
        val types = intArrayOf(3, 0, 4)
        val above = arrayOf(none, intArrayOf(0), none)
        val plan = assertNotNull(Solver(capacity = 8).solve(types, above, listOf(0, 0, 1, 1, 2, 2)))
        assertEquals(listOf(0, 1), plan.moves.take(2))
    }

    @Test
    fun avoidsFillingTheHand() {
        // Hand has 5 different fruits. Tile 0 (type 5) would leave 6 in hand with no way out;
        // tile 1 (type 0) uncovers two more 0s to finish a triple.
        val types = intArrayOf(5, 0, 0, 7)
        val above = arrayOf(none, none, intArrayOf(1), intArrayOf(1))
        val plan = assertNotNull(Solver().solve(types, above, listOf(0, 1, 2, 3, 4)))
        assertEquals(1, plan.moves.first())
        assertEquals(2, plan.moves[1])
    }

    @Test
    fun playsWholeScreenshots() {
        for (name in listOf("level1", "level2", "level11")) {
            val (bi, img) = TestImages.load("$name.jpg")
            val bot = Bot()
            assertTrue(bot.decide(img, 0) is Decision.Wait)
            val t0 = System.nanoTime()
            val d = bot.decide(img, 1)
            println("$name: ${d::class.simpleName} ${(d as? Decision.Tap)?.let { "(${it.x},${it.y}) plan=${it.plan.map { t -> t.type }}" } ?: ""} in ${(System.nanoTime() - t0) / 1_000_000}ms")
            if (d is Decision.Tap) TestImages.debug(bi, d.frame, "$name-plan", d.plan.map { d.frame.board.indexOf(it) })
            if (name != "level11") assertTrue(d is Decision.Tap)
        }
    }
}
