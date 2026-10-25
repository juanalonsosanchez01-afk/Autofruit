package autofruit.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Setup screen: walks through the permissions and holds the few settings. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var serviceState: TextView
    private lateinit var notifState: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun title(s: String) = col.addView(TextView(this).apply {
            text = s; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, pad, 0, pad / 3)
            setTextColor(Color.BLACK)
        })
        fun para(s: String) = TextView(this).apply { text = s; textSize = 14f; setTextColor(0xFF333333.toInt()) }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }.also { col.addView(it) }

        col.addView(TextView(this).apply {
            text = "Autofruit"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF3E6B1F.toInt())
        })
        para("Juega solo al minijuego de fichas de frutas de Widgetable: mira el tablero (también las fichas tapadas que asoman), planifica varias jugadas por delante y toca la pantalla por ti.")

        title("1. Servicio de accesibilidad")
        para("Permite hacer capturas de pantalla y tocar la pantalla. Busca \"Autofruit\" en Accesibilidad (en Xiaomi: Ajustes → Ajustes adicionales → Accesibilidad → Servicios descargados) y actívalo.")
        serviceState = para("")
        button("Abrir ajustes de accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        para("Si sale \"Ajuste restringido\": abre la información de la app, toca ⋮ (arriba a la derecha) → \"Permitir ajustes restringidos\" y vuelve a intentarlo.")
        button("Abrir información de la app") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        title("2. Notificación permanente")
        para("La notificación permite encender/apagar el bot y guardar capturas de depuración.")
        notifState = para("")
        if (Build.VERSION.SDK_INT >= 33) {
            button("Permitir notificaciones") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }

        title("3. Ajustes")
        para("¿Cuándo se pierde?")
        val group = RadioGroup(this)
        val r7 = RadioButton(this).apply { id = View.generateViewId(); text = "Al llenar los 7 huecos de la bandeja (seguro)" }
        val r8 = RadioButton(this).apply { id = View.generateViewId(); text = "Con más de 7 fichas (cabe una 7ª sin trío)" }
        group.addView(r7); group.addView(r8)
        group.check(if (prefs.capacity == 8) r8.id else r7.id)
        group.setOnCheckedChangeListener { _, id -> prefs.capacity = if (id == r8.id) 8 else 7 }
        col.addView(group)
        val delayLabel = para("")
        fun showDelay() { delayLabel.text = "Pausa tras cada toque: ${prefs.tapDelayMs} ms" }
        showDelay()
        col.addView(SeekBar(this).apply {
            max = 1200
            progress = prefs.tapDelayMs - 300
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { prefs.tapDelayMs = 300 + p; showDelay() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        para("Si el bot toca antes de que la ficha llegue a la bandeja, sube la pausa.")

        title("4. Jugar")
        para("Abre el nivel en Widgetable y pulsa el botón flotante ▶ (o \"Iniciar\" en la notificación). Arrastra el botón para moverlo, pero no lo dejes encima de las fichas. El bot se para solo si la bandeja se llena o el tablero queda vacío.")
        toggle = button("") {
            AutoFruitService.instance?.toggle()
            col.postDelayed({ refresh() }, 200)
        }
        para("¿Falla algo? Pulsa \"Captura debug\" en la notificación cuando el bot se equivoque: se guardan dos imágenes en Imágenes/Autofruit (la captura y lo que ha entendido el bot).")

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = serviceEnabled()
        serviceState.text = if (enabled) "✅ Activado" else "❌ Desactivado"
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notifState.text = if (notif) "✅ Permitidas" else "❌ Sin permiso"
        val service = AutoFruitService.instance
        toggle.isEnabled = service != null
        toggle.text = when {
            service == null -> "Activa primero el servicio"
            service.isRunning() -> "Parar bot"
            else -> "Iniciar bot (cambia al juego enseguida)"
        }
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, AutoFruitService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
