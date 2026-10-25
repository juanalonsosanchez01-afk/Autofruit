package autofruit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Screenshots taken on the phone while the bot was playing. */
class PhoneTest {
    private fun decide(name: String): Decision.Tap {
        val (bi, img) = TestImages.load(name)
        val bot = Bot()
        bot.decide(img, 0)
        val d = bot.decide(img, 1)
        assertTrue(d is Decision.Tap, "$name: $d")
        TestImages.debug(bi, d.frame, name.substringBefore('.') + "-plan", d.plan.map { d.frame.board.indexOf(it) })
        // Nothing new should have been learned: every fruit is in the catalogue.
        assertEquals(12, bot.library.size, "$name learned duplicate fruit types")
        return d
    }

    @Test
    fun finishesATriple() {
        // Hand: avocado, apple, apple, garlic, banana, banana; a lit apple and a lit banana
        // are on the board. Either finishes a triple; anything else loses.
        val d = decide("phone-l2-tray6.png")
        assertEquals(2, d.frame.tray.count { it.type == d.tile.type })
    }

    @Test
    fun handOfFiveTakesAPair() {
        // Hand: avocado, apple, garlic, banana, banana. Taking a lettuce or garlic would
        // leave six in hand with no finish in sight; avocado or apple pairs with the hand.
        val d = decide("phone-l2-tray5.jpg")
        val hand = d.frame.tray.map { it.type }
        assertTrue(d.tile.type == hand[0] || d.tile.type == hand[1], "picked type ${d.tile.type}, hand $hand")
    }

    @Test
    fun onlyTapsLitTiles() {
        // Hand: lettuce, lettuce, garlic. Lit lettuces are on the board: finish the triple,
        // and every tap of the batch must be a lit tile.
        val d = decide("phone-l3.png")
        assertEquals(d.frame.tray[0].type, d.tile.type)
        assertTrue(d.taps.all { it.lit })
    }

    @Test
    fun endOfLevelOne() {
        val d = decide("phone-l1-end.jpg")
        assertEquals(d.frame.tray[0].type, d.tile.type)
    }
}

class PhoneTimingTest {
    @Test
    fun timing() {
        for (name in listOf("phone-l3.png", "level11.jpg")) {
            val img = TestImages.load(name).second
            val bot = Bot()
            repeat(6) { i ->
                val t0 = System.nanoTime()
                val f = Detector().detect(img)!!
                val t1 = System.nanoTime()
                bot.decide(img, i * 10_000L)
                val t2 = System.nanoTime()
                println("$name detect=${(t1 - t0) / 1_000_000}ms decide=${(t2 - t1) / 1_000_000}ms tiles=${f.board.size}")
            }
        }
    }
}

class LevelChangeTest {
    @Test
    fun waitsFiveSecondsForTheNextLevel() {
        val board = TestImages.load("phone-l3.png").second
        val blank = RgbImage(board.width, board.height, IntArray(board.width * board.height) { 0xFFD4F5A8.toInt() })
        val bot = Bot()
        bot.decide(board, 0)
        assertTrue(bot.decide(board, 400) is Decision.Tap)
        assertTrue(bot.decide(blank, 1000) is Decision.Wait)
        assertTrue(bot.decide(board, 2000) is Decision.Wait)
        assertTrue(bot.decide(board, 6000) is Decision.Wait)
        bot.decide(board, 7100)
        assertTrue(bot.decide(board, 7500) is Decision.Tap)
    }
}

/** Frames from a screen recording of the bot playing level 3. */
class VideoFrameTest {
    @Test
    fun boosterButtonsAreNotHandTiles() {
        // Empty hand while the booster buttons below it animate.
        val f = Detector().detect(TestImages.load("video-0045.jpg").second)!!
        assertEquals(0, f.tray.size)
    }

    @Test
    fun doesNotPlayWhileATileIsLanding() {
        // An apple is still landing in the hand (blurred, with sparkles): its fruit
        // can't be read yet, so the bot must wait rather than invent a new fruit.
        val img = TestImages.load("video-0295.jpg").second
        val bot = Bot()
        repeat(2) { i -> assertTrue(bot.decide(img, i * 100L) is Decision.Wait) }
        assertEquals(12, bot.library.size)
    }
}
