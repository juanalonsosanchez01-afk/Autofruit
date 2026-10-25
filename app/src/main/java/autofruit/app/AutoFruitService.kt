package autofruit.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import autofruit.core.Bot
import autofruit.core.Decision
import autofruit.core.RgbImage
import autofruit.core.TypeLibrary
import java.util.concurrent.Executor

/**
 * Does the playing: takes a screenshot, asks the [Bot] what to do, taps, repeats.
 * Controlled from the floating button and from its permanent notification.
 */
class AutoFruitService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var executor: Executor
    private val main = Handler(Looper.getMainLooper())
    private val library = TypeLibrary.seeded()
    private var bot = Bot(library)

    @Volatile
    private var running = false
    private var generation = 0
    private var pixels = IntArray(0)

    /** Last screenshot that showed the board, kept for the debug capture. */
    @Volatile
    private var lastShot: Bitmap? = null
    @Volatile
    private var lastDecision: Decision? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> toggle()
                ACTION_DEBUG -> debugCapture()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        worker = HandlerThread("autofruit").also { it.start() }
        handler = Handler(worker.looper)
        executor = Executor { handler.post(it) }
        overlay = Overlay(this, prefs, onToggle = { toggle() }, onLongPress = { debugCapture() })
        overlay.show()
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_DEBUG)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Autofruit", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Control del bot"
                setShowBadge(false)
            })
        updateNotification("Listo. Abre el juego y pulsa ▶")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() = setRunning(false)

    override fun onDestroy() {
        running = false
        instance = null
        runCatching { unregisterReceiver(receiver) }
        runCatching { overlay.hide() }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    fun isRunning() = running

    fun toggle() = main.post { setRunning(!running) }

    /** Saves what the bot last saw; works whether it is running or not. */
    fun debugCapture() {
        if (running) {
            saveDebug()
            return
        }
        // Not playing: take a fresh screenshot just for the record, after closing the
        // notification shade in case that is where the request came from.
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val shot = try { toSrgb(result) } finally { result.hardwareBuffer.close() } ?: return
                    val px = IntArray(shot.width * shot.height)
                    shot.getPixels(px, 0, shot.width, 0, 0, shot.width, shot.height)
                    val d = runCatching { Bot(library, prefs.capacity).let { b -> b.decide(RgbImage(shot.width, shot.height, px), 0); b.decide(RgbImage(shot.width, shot.height, px), 1) } }.getOrNull()
                    lastShot?.recycle()
                    lastShot = shot
                    lastDecision = d
                    saveDebug()
                }

                override fun onFailure(errorCode: Int) = status("No se pudo hacer la captura ($errorCode)")
            })
        }, 800)
    }

    fun setRunning(on: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setRunning(on) }
            return
        }
        if (on == running) return
        running = on
        generation++
        overlay.setRunning(on)
        if (on) {
            bot = Bot(library, prefs.capacity)
            status("Jugando…")
            val gen = generation
            handler.post { capture(gen) }
        } else {
            status("Parado")
        }
    }

    private fun status(text: String) {
        main.post {
            overlay.setStatus(text)
            updateNotification(text)
        }
    }

    private fun capture(gen: Int) {
        if (!running || gen != generation) return
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = try {
                    toSrgb(result)
                } finally {
                    result.hardwareBuffer.close()
                }
                if (shot == null) {
                    later(gen, 500)
                    return
                }
                analyse(shot, gen)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed: $errorCode")
                later(gen, 500)
            }
        })
    }

    private fun toSrgb(result: ScreenshotResult): Bitmap? {
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return null
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        if (soft.colorSpace == null || soft.colorSpace == srgb) return soft
        // Wide colour screens hand back e.g. Display P3; the detector expects sRGB values.
        val out = Bitmap.createBitmap(soft.width, soft.height, Bitmap.Config.ARGB_8888, false, srgb)
        Canvas(out).drawBitmap(soft, 0f, 0f, null)
        soft.recycle()
        return out
    }

    private fun analyse(shot: Bitmap, gen: Int) {
        val w = shot.width
        val h = shot.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        shot.getPixels(pixels, 0, w, 0, 0, w, h)
        val decision = try {
            bot.decide(RgbImage(w, h, pixels))
        } catch (e: Exception) {
            Log.e(TAG, "analysis failed", e)
            Decision.Wait("Error: ${e.message}")
        }
        if (frameOf(decision) != null) {
            lastShot?.recycle()
            lastShot = shot
            lastDecision = decision
        } else {
            shot.recycle()
        }
        if (!running || gen != generation) return
        when (decision) {
            is Decision.Tap -> {
                val f = decision.frame
                status("Mano ${f.tray.size}/${prefs.capacity} · ${f.board.size} fichas")
                tap(decision.x.toFloat(), decision.y.toFloat()) { later(gen, prefs.tapDelayMs.toLong()) }
            }
            is Decision.Wait -> {
                status(decision.reason)
                later(gen, 350)
            }
            is Decision.Stop -> {
                status(decision.reason)
                setRunning(false)
            }
        }
    }

    private fun frameOf(d: Decision) = when (d) {
        is Decision.Tap -> d.frame
        is Decision.Wait -> d.frame
        is Decision.Stop -> d.frame
    }

    private fun later(gen: Int, ms: Long) {
        handler.postDelayed({ capture(gen) }, ms)
    }

    private fun tap(x: Float, y: Float, done: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) = done()
        }, handler)
        if (!sent) done()
    }

    private fun saveDebug() {
        handler.post {
            val shot = lastShot
            if (shot == null || shot.isRecycled) {
                status("Aún no he visto el tablero: enciende el bot en el juego")
                return@post
            }
            val saved = DebugSaver.save(this, shot, lastDecision)
            status(if (saved) "Captura guardada en Imágenes/Autofruit" else "No se pudo guardar la captura")
        }
    }

    private fun updateNotification(text: String) {
        val toggleIntent = PendingIntent.getBroadcast(this, 1,
            Intent(ACTION_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val debugIntent = PendingIntent.getBroadcast(this, 2,
            Intent(ACTION_DEBUG).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setContentTitle(if (running) "Autofruit jugando" else "Autofruit parado")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                if (running) "Parar" else "Iniciar", toggleIntent).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_camera),
                "Captura debug", debugIntent).build())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val TAG = "Autofruit"
        private const val CHANNEL = "autofruit"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "autofruit.app.TOGGLE"
        const val ACTION_DEBUG = "autofruit.app.DEBUG"

        @Volatile
        var instance: AutoFruitService? = null
            private set
    }
}
