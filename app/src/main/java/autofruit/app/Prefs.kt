package autofruit.app

import android.content.Context

/** User settings shared by the activity and the service. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("autofruit", Context.MODE_PRIVATE)

    /** Tiles the hand can hold; filling it completely loses the game. */
    var capacity: Int
        get() = sp.getInt("capacity", 7)
        set(v) = sp.edit().putInt("capacity", v).apply()

    /** Pause after each tap so the tile can fly to the hand. */
    var tapDelayMs: Int
        get() = sp.getInt("tapDelayMs", 250)
        set(v) = sp.edit().putInt("tapDelayMs", v).apply()

    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()
}
