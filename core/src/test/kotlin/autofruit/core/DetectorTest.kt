package autofruit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DetectorTest {
    private fun run(name: String): Frame {
        val (bi, img) = TestImages.load("$name.jpg")
        val t0 = System.nanoTime()
        val frame = assertNotNull(Detector().detect(img), "no board found in $name")
        val lib = TypeLibrary()
        Classifier(lib).classify(frame)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("$name: ${frame.board.count { it.lit }} lit, ${frame.board.count { !it.lit }} covered, " +
            "tray=${frame.tray.map { it.type }}, face=${frame.faceW}x${frame.faceH}, types=${lib.size}, ${ms}ms")
        for (t in frame.board.sortedWith(compareBy({ it.layer }, { it.face.top }, { it.face.left })))
            println("  L${t.layer} ${t.face} type=${t.type} vis=${"%.2f".format(t.visible)} ink=${t.descriptor.inkCount}" +
                if (t.lit) "" else lib.ranked(t.descriptor).take(3).joinToString(prefix = " ") { (i, m) -> "$i:${"%.1f".format(m.distance)}/${m.commonInk}" })
        val lit = (frame.board.filter { it.lit } + frame.tray)
        val sameMax = lit.flatMap { a -> lit.filter { it !== a && it.type == a.type }.map { compare(a.descriptor, it.descriptor).distance } }.maxOrNull()
        val diffMin = lit.flatMap { a -> lit.filter { it.type != a.type }.map { compare(a.descriptor, it.descriptor).distance } }.minOrNull()
        println("  lit same-type max=$sameMax  different-type min=$diffMin")
        TestImages.debug(bi, frame, name)
        return frame
    }

    @Test
    fun level1() {
        val f = run("level1")
        assertEquals(9, f.board.count { it.lit })
        assertEquals(9, f.board.count { !it.lit })
        assertEquals(0, f.tray.size)
    }

    @Test
    fun level2() {
        val f = run("level2")
        assertEquals(15, f.board.count { it.lit })
        assertEquals(0, f.tray.size)
    }

    @Test
    fun level11() {
        val f = run("level11")
        assertEquals(10, f.board.count { it.lit })
        assertEquals(6, f.tray.size)
        val t = f.tray.map { it.type }
        // banana, chili, apple, bok choy, bok choy, mushroom
        assertEquals(t[3], t[4])
        assertEquals(5, t.toSet().size)
    }
}
