package autofruit.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object TestImages {
    fun load(name: String): Pair<BufferedImage, RgbImage> {
        val bi = ImageIO.read(TestImages::class.java.getResource("/$name")!!)
        val px = bi.getRGB(0, 0, bi.width, bi.height, null, 0, bi.width)
        return bi to RgbImage(bi.width, bi.height, px)
    }

    private val palette = listOf(
        Color.RED, Color.BLUE, Color.MAGENTA, Color.ORANGE, Color.CYAN, Color.PINK, Color.BLACK,
        Color(128, 0, 128), Color(0, 128, 0), Color(128, 64, 0), Color(0, 64, 128), Color(200, 200, 0),
        Color(255, 0, 128), Color(0, 200, 128), Color(90, 90, 90), Color(160, 0, 0),
    )

    /** Draws detected tiles on top of the screenshot so the result can be eyeballed. */
    fun debug(bi: BufferedImage, frame: Frame, name: String, plan: List<Int> = emptyList()) {
        val dir = System.getProperty("autofruit.debugDir") ?: return
        val out = BufferedImage(bi.width, bi.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(bi, 0, 0, null)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        val all = frame.board + frame.tray
        for ((i, t) in all.withIndex()) {
            val c = if (t.type >= 0) palette[t.type % palette.size] else Color.GRAY
            g.color = c
            g.stroke = BasicStroke(if (t.lit) 5f else 3f)
            val f = t.face
            g.drawRect(f.left, f.top, f.width, f.height)
            g.drawString("${if (t.type >= 0) t.type else "?"}" + if (t.lit) "" else "/${t.layer}", f.left + 6, f.top + 32)
            val step = plan.indexOf(i)
            if (step >= 0) {
                g.color = Color.WHITE
                g.fillOval(f.right - 44, f.bottom - 44, 40, 40)
                g.color = Color.BLACK
                g.drawString("${step + 1}", f.right - 36, f.bottom - 12)
            }
        }
        g.color = Color.RED
        g.drawRect(frame.trayRect.left, frame.trayRect.top, frame.trayRect.width, frame.trayRect.height)
        g.dispose()
        File(dir).mkdirs()
        ImageIO.write(out, "png", File(dir, "$name.png"))
    }
}
