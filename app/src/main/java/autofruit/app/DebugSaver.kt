package autofruit.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import autofruit.core.Decision
import autofruit.core.Frame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the last screenshot twice to Pictures/Autofruit: untouched (handy for
 * improving the detector) and with what the bot understood drawn on top.
 */
object DebugSaver {
    private val colors = intArrayOf(
        Color.RED, Color.BLUE, Color.MAGENTA, 0xFFFF8800.toInt(), Color.CYAN, 0xFFFF66AA.toInt(), Color.BLACK,
        0xFF880088.toInt(), 0xFF008800.toInt(), 0xFF884400.toInt(), 0xFF004488.toInt(), 0xFFAAAA00.toInt(),
        0xFFFF0088.toInt(), 0xFF00AA88.toInt(), 0xFF555555.toInt(), 0xFFAA0000.toInt(),
    )

    fun save(context: Context, shot: Bitmap, decision: Decision?): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val ok = write(context, shot, "autofruit-$stamp.png")
        val frame = when (decision) {
            is Decision.Tap -> decision.frame
            is Decision.Wait -> decision.frame
            is Decision.Stop -> decision.frame
            null -> null
        } ?: return ok
        val annotated = shot.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(annotated), frame, decision as? Decision.Tap)
        val ok2 = write(context, annotated, "autofruit-$stamp-analisis.png")
        annotated.recycle()
        return ok && ok2
    }

    private fun draw(c: Canvas, frame: Frame, tap: Decision.Tap?) {
        val box = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
        val text = Paint().apply { textSize = frame.faceW / 3.5f; isFakeBoldText = true; isAntiAlias = true }
        val dot = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val plan = tap?.plan ?: emptyList()
        for (t in frame.board + frame.tray) {
            val color = if (t.type >= 0) colors[t.type % colors.size] else Color.GRAY
            box.color = color
            box.strokeWidth = if (t.lit) 6f else 3f
            text.color = color
            val f = t.face
            c.drawRect(f.left.toFloat(), f.top.toFloat(), f.right.toFloat(), f.bottom.toFloat(), box)
            val label = (if (t.type >= 0) "${t.type}" else "?") + if (t.lit) "" else "/${t.layer}"
            c.drawText(label, f.left + 6f, f.top + text.textSize, text)
            val step = plan.indexOf(t)
            if (step >= 0) {
                val r = frame.faceW / 6f
                c.drawCircle(f.right - r - 4, f.bottom - r - 4, r, dot)
                text.color = Color.BLACK
                c.drawText("${step + 1}", f.right - 1.6f * r - 4, f.bottom - 0.6f * r - 4, text)
            }
        }
        box.color = Color.RED
        box.strokeWidth = 3f
        val tr = frame.trayRect
        c.drawRect(tr.left.toFloat(), tr.top.toFloat(), tr.right.toFloat(), tr.bottom.toFloat(), box)
    }

    private fun write(context: Context, bmp: Bitmap, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Autofruit")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.getOrDefault(false)
    }
}
