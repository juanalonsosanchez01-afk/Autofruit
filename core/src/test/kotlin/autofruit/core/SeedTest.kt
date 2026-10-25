package autofruit.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SeedTest {
    /** Run with -Dautofruit.writeSeeds=<file> to regenerate the bundled fruit catalogue. */
    @Test
    fun seeds() {
        val target = System.getProperty("autofruit.writeSeeds")
        if (target != null) {
            val lib = TypeLibrary()
            for (name in listOf("level1", "level2", "level11")) {
                val frame = Detector().detect(TestImages.load("$name.jpg").second)!!
                for (t in frame.tray + frame.board.filter { it.lit }) lib.identifyLit(t.descriptor)
            }
            File(target).outputStream().use { lib.write(it) }
            println("wrote ${lib.size} fruit types to $target")
        }
        assertTrue(TypeLibrary.seeded().size >= 10, "bundled fruit catalogue missing")
    }
}
