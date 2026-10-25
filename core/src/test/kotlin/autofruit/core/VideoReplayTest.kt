package autofruit.core

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Replays the frames of a screen recording through the bot and logs what it
 * would do. Run with -Dautofruit.frames=<dir of PNGs> (e.g. from
 * `ffmpeg -i video.mp4 -vf fps=10 dir/%04d.png`) and -Dautofruit.fps=10.
 */
class VideoReplayTest {
    @Test
    fun replay() {
        val dir = System.getProperty("autofruit.frames") ?: return
        val fps = (System.getProperty("autofruit.fps") ?: "10").toInt()
        val bot = Bot()
        for ((i, f) in File(dir).listFiles()!!.filter { it.name.endsWith(".png") }.sorted().withIndex()) {
            val bi = ImageIO.read(f)
            val img = RgbImage(bi.width, bi.height, bi.getRGB(0, 0, bi.width, bi.height, null, 0, bi.width))
            val d = bot.decide(img, i * 1000L / fps)
            val frame = when (d) {
                is Decision.Tap -> d.frame
                is Decision.Wait -> d.frame
                is Decision.Stop -> d.frame
            }
            val desc = when (d) {
                is Decision.Tap -> "TAP " + d.taps.joinToString { "${it.type}@(${it.face.centerX},${it.face.centerY})" } +
                    " plan=" + d.plan.map { "${it.type}${if (it.lit) "" else "*"}" }
                is Decision.Wait -> "wait: ${d.reason}"
                is Decision.Stop -> "STOP: ${d.reason}"
            }
            val state = frame?.let { fr -> "hand=${fr.tray.map { it.type }} lit=${fr.board.filter { it.lit }.size} all=${fr.board.size}" } ?: ""
            println("${f.name} $state $desc")
            if (d is Decision.Tap) TestImages.debug(bi, d.frame, "replay-${f.nameWithoutExtension}", d.plan.map { d.frame.board.indexOf(it) })
        }
    }
}
