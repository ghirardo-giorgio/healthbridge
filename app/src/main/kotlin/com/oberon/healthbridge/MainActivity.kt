package com.oberon.healthbridge

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.PermissionController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.runBlocking

/**
 * L'unica schermata dell'app, ed e' una schermata di collegamento: risponde a
 * quattro domande — i permessi ci sono, la porta e' aperta, a che indirizzo, e
 * con che chiave. I dati si guardano dal browser o da chi chiama l'API.
 */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val refresh = Handler(Looper.getMainLooper())

    /**
     * I permessi health gia' concessi.
     *
     * Volatile e non letto al volo perche' chiederlo costa un giro di IPC a
     * Health Connect. Nullo vuol dire «non l'ho ancora chiesto», che e' diverso
     * da «nessuno».
     */
    @Volatile
    private var granted: Set<String>? = null

    private val tick = object : Runnable {
        override fun run() {
            draw()
            refresh.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                isFillViewport = true
                addView(root)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        askNotifications()
        readGranted()
        refresh.post(tick)
    }

    override fun onPause() {
        super.onPause()
        refresh.removeCallbacks(tick)
    }

    // -- i permessi ---------------------------------------------------------

    private fun readGranted() {
        Thread {
            val found = runCatching {
                val client = HealthAccess.client(this) ?: return@runCatching null
                runBlocking { client.permissionController.getGrantedPermissions() }
            }.getOrNull()

            runOnUiThread {
                granted = found ?: emptySet()
                draw()
            }
        }.start()
    }

    /**
     * Passa da `startActivityForResult` perche' questa e' una `Activity` di
     * piattaforma: al contract serve solo costruire l'intent, e quale risposta
     * sia arrivata lo si chiede a Health Connect, non a lui.
     */
    private fun askHealth() {
        val contract = PermissionController.createRequestPermissionResultContract()
        runCatching { startActivityForResult(contract.createIntent(this, HealthAccess.all), 2) }
            .onFailure {
                Toast.makeText(this, R.string.hc_not_responding, Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Negarla non impedisce al server di funzionare: toglie solo il posto da cui
     * si spegne senza riaprire l'app.
     */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        readGranted()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        draw()
    }

    // -- la schermata -------------------------------------------------------

    private fun draw() {
        val access = Access(this)
        val schedule = Schedule(this)
        val running = ControlService.running
        val address = Net.best()
        val url = address?.let { "http://$it:${access.port}/?t=${access.token}" }

        root.removeAllViews()

        root.addView(title(getString(R.string.app_name)))
        root.addView(caption(getString(R.string.tagline)))

        missing().takeIf { it.isNotEmpty() }?.let { root.addView(permissions(it)) }

        root.addView(connection(running, address, url, access, schedule))

        if (running && url != null) {
            root.addView(qr(url))
            root.addView(actions(url))
        }

        root.addView(quiet(schedule, running))
        root.addView(advanced(access, running))

        if (!ignoringBattery()) root.addView(battery())

        ControlService.error?.let { root.addView(failure(it)) }
    }

    private fun connection(
        running: Boolean,
        address: String?,
        url: String?,
        access: Access,
        schedule: Schedule,
    ): View = card().apply {
        val silent = schedule.silentNow()

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot(if (running) GOOD else MUTED))
                addView(
                    TextView(this@MainActivity).apply {
                        text = when {
                            running && ControlService.resting ->
                                getString(R.string.status_listening_resting)
                            running -> getString(R.string.status_listening)
                            silent && schedule.wanted -> getString(
                                R.string.status_paused_until,
                                Schedule.label(schedule.to),
                            )
                            else -> getString(R.string.status_off)
                        }
                        setTextColor(if (running) GOOD else DIM)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(dp(10), 0, 0, 0)
                    }
                )
            }
        )

        addView(
            TextView(this@MainActivity).apply {
                text =
                    if (address == null) getString(R.string.address_none)
                    else "$address:${access.port}"
                setTextColor(if (address == null) WARN else TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(14), 0, dp(4))
            }
        )

        // L'indirizzo intero con la chiave dentro, non la sola chiave: e'
        // quello che serve incollare da qualche altra parte, ed e' la stessa
        // stringa che finisce nel QR e nel pulsante «copia».
        if (url == null) {
            addView(caption(getString(R.string.no_network), inset = false))
        } else {
            addView(
                TextView(this@MainActivity).apply {
                    text = url
                    setTextColor(ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }

        addView(
            primary(getString(if (running) R.string.btn_stop else R.string.btn_start)) {
                if (running) ControlService.quit(this@MainActivity)
                else ControlService.start(this@MainActivity)
                refresh.postDelayed({ draw() }, 400)
            }
        )
    }

    /**
     * Correzione d'errore bassa e nessun margine aggiunto da ZXing: lo si
     * inquadra da venti centimetri su uno schermo acceso, non e' stampato su
     * una scatola che ha viaggiato.
     */
    private fun qr(url: String): View = card().apply {
        val side = dp(240)

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )

        val bitmap = runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, side, side, hints)
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until matrix.width) {
                    for (y in 0 until matrix.height) {
                        setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }
        }.getOrNull()

        if (bitmap == null) {
            addView(caption(getString(R.string.qr_failed), inset = false))
            return@apply
        }

        addView(
            ImageView(this@MainActivity).apply {
                // Interpolare i quadretti li sfoca, e un quadretto sfocato e'
                // un quadretto che la fotocamera dall'altra parte deve
                // indovinare.
                setImageDrawable(
                    BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false }
                )
                layoutParams = LinearLayout.LayoutParams(side, side).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
        )

        addView(
            caption(getString(R.string.qr_hint), inset = false)
                .apply { gravity = Gravity.CENTER }
        )
    }

    private fun actions(url: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }

        addView(
            secondary(getString(R.string.btn_copy_link)) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this@MainActivity, R.string.toast_copied, Toast.LENGTH_SHORT)
                        .show()
                }
            }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6) }
        )

        addView(
            secondary(getString(R.string.btn_open_here)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }
        )
    }

    // -- la fascia di silenzio ----------------------------------------------

    private fun quiet(schedule: Schedule, running: Boolean): View = card().apply {
        addView(heading(getString(R.string.quiet_title)))
        addView(caption(getString(R.string.quiet_body), inset = false))

        addView(
            secondary(
                getString(if (schedule.quiet) R.string.quiet_on else R.string.quiet_always)
            ) {
                schedule.quiet = !schedule.quiet
                schedule.arm(this@MainActivity)
                draw()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) }
            }
        )

        if (!schedule.quiet) return@apply

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dp(10) }

                addView(
                    secondary(
                        getString(R.string.quiet_from, Schedule.label(schedule.from))
                    ) {
                        pickTime(schedule.from) {
                            schedule.from = it
                            schedule.arm(this@MainActivity)
                            if (running && schedule.silentNow()) ControlService.stop(this@MainActivity)
                            draw()
                        }
                    }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6) }
                )

                addView(
                    secondary(
                        getString(R.string.quiet_to, Schedule.label(schedule.to))
                    ) {
                        pickTime(schedule.to) {
                            schedule.to = it
                            schedule.arm(this@MainActivity)
                            if (running && schedule.silentNow()) ControlService.stop(this@MainActivity)
                            draw()
                        }
                    }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }
                )
            }
        )

        addView(label(getString(R.string.rest_after, schedule.restAfterMinutes)))

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dp(10) }

                for (minutes in listOf(5, 10, 30)) {
                    addView(
                        secondary(getString(R.string.minutes_short, minutes)) {
                            schedule.restAfterMinutes = minutes
                            draw()
                        }.apply {
                            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
                        }
                    )
                }
            }
        )
    }

    private fun pickTime(current: Int, chosen: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> chosen(hour * 60 + minute) },
            current / 60,
            current % 60,
            true,
        ).show()
    }

    // -- i permessi mancanti ------------------------------------------------

    private fun missing(): Set<String> {
        val have = granted ?: return emptySet()
        return HealthAccess.all - have
    }

    private fun permissions(absent: Set<String>): View = card(ALARM_BACKGROUND).apply {
        addView(
            heading(
                resources.getQuantityString(R.plurals.perms_missing, absent.size, absent.size)
            )
        )
        addView(caption(getString(R.string.perms_body), inset = false))

        addView(
            label(
                absent.joinToString(", ") {
                    it.substringAfterLast('.').removePrefix("READ_").lowercase()
                }
            )
        )

        addView(
            secondary(getString(R.string.perms_grant)) { askHealth() }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) }
            }
        )
    }

    // -- il resto -----------------------------------------------------------

    private fun advanced(access: Access, running: Boolean): View = card().apply {
        addView(heading(getString(R.string.settings_title)))

        val port = EditText(this@MainActivity).apply {
            setText(access.port.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(FIELD)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }

        addView(label(getString(R.string.port_label)))
        addView(port)

        addView(
            secondary(getString(R.string.port_save)) {
                val asked = port.text.toString().toIntOrNull()
                if (asked == null || asked < 1024 || asked > 65535) {
                    Toast.makeText(this@MainActivity, R.string.port_range, Toast.LENGTH_SHORT)
                        .show()
                    return@secondary
                }
                access.port = asked
                if (running) {
                    ControlService.stop(this@MainActivity)
                    refresh.postDelayed({ ControlService.start(this@MainActivity) }, 500)
                }
                refresh.postDelayed({ draw() }, 900)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(10) }
            }
        )

        addView(
            label(getString(R.string.token_note)).apply { setPadding(0, dp(18), 0, 0) }
        )

        addView(
            secondary(getString(R.string.token_renew)) {
                access.renew()
                draw()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(10) }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) addView(languages())
    }

    /**
     * La lingua della sola app, senza passare dalle impostazioni di sistema.
     *
     * Vuota vuol dire «segui il telefono», ed e' una terza scelta e non
     * l'assenza di scelta: chi cambia lingua al telefono si aspetta che l'app
     * lo segua, e senza questo pulsante non ci sarebbe modo di tornare
     * indietro dopo aver fissato l'italiano una volta.
     */
    private fun languages(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

        addView(label(getString(R.string.language_title)).apply { setPadding(0, dp(18), 0, 0) })

        val chosen = currentLanguage()

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dp(10) }

                val choices = listOf(
                    "" to getString(R.string.language_system),
                    "it" to getString(R.string.language_it),
                    "en" to getString(R.string.language_en),
                )

                for ((tag, name) in choices) {
                    addView(
                        secondary(name) { chooseLanguage(tag) }.apply {
                            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)

                            if (tag == chosen) {
                                setTextColor(BACKGROUND)
                                background = GradientDrawable().apply {
                                    setColor(ACCENT)
                                    cornerRadius = dp(14).toFloat()
                                }
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * Stanno in metodi loro e non dentro un `if` perche' `LocaleManager` esiste
     * da API 33 e il minSdk e' 29: un metodo che non viene mai chiamato non
     * viene nemmeno verificato, e cosi' su un telefono vecchio la classe non si
     * cerca affatto.
     */
    private fun currentLanguage(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
        return applicationLocales().let { if (it.isEmpty) "" else it[0]?.language.orEmpty() }
    }

    private fun applicationLocales(): LocaleList =
        getSystemService(LocaleManager::class.java)?.applicationLocales
            ?: LocaleList.getEmptyLocaleList()

    private fun chooseLanguage(tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (tag == currentLanguage()) return

        getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)

        // La notifica gia' esposta resterebbe nella lingua di prima: il
        // servizio la riscrive da se' se lo si risveglia, e a server spento
        // non c'e' niente da riscrivere.
        if (ControlService.running) ControlService.start(this)
    }

    /**
     * Compare solo finche' serve. Senza, Doze spegne il socket dopo qualche ora
     * di telefono fermo, e il difetto che ne segue e' il peggiore da
     * diagnosticare che ci sia: tutto funziona finche' si guarda.
     */
    private fun battery(): View = card().apply {
        addView(heading(getString(R.string.battery_title)))
        addView(caption(getString(R.string.battery_body), inset = false))

        addView(
            secondary(getString(R.string.battery_exclude)) {
                val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))

                // Alcune interfacce rifiutano la richiesta diretta: li' si apre
                // l'elenco completo, che e' piu' scomodo ma non manca mai.
                runCatching { startActivity(direct) }.recoverCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) }
            }
        )
    }

    private fun ignoringBattery(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    private fun failure(message: String): View = card(ALARM_BACKGROUND).apply {
        addView(heading(getString(R.string.failure_title)))
        addView(caption(message, inset = false))
    }

    // -- mattoni ------------------------------------------------------------

    private fun card(colour: Int = CARD): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(colour)
            cornerRadius = dp(20).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
    }

    private fun title(what: String) = TextView(this).apply {
        text = what
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = -0.02f
    }

    private fun heading(what: String) = TextView(this).apply {
        text = what
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun caption(what: String, inset: Boolean = true) = TextView(this).apply {
        text = what
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(if (inset) 8 else 6), 0, 0)
    }

    private fun label(what: String) = TextView(this).apply {
        text = what
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(14), 0, 0)
    }

    private fun dot(colour: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        background = GradientDrawable().apply {
            setColor(colour)
            shape = GradientDrawable.OVAL
        }
    }

    private fun primary(what: String, action: () -> Unit) = Button(this).apply {
        text = what
        isAllCaps = false
        setTextColor(BACKGROUND)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(ACCENT)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(18) }
        setOnClickListener { action() }
    }

    private fun secondary(what: String, action: () -> Unit) = Button(this).apply {
        text = what
        isAllCaps = false
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(FIELD)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val BACKGROUND = Color.parseColor("#0E1116")
        private val CARD = Color.parseColor("#171C24")
        private val ALARM_BACKGROUND = Color.parseColor("#2A1D1B")
        private val FIELD = Color.parseColor("#222933")
        private val TEXT = Color.parseColor("#E8EDF4")
        private val DIM = Color.parseColor("#8D9AAC")
        private val MUTED = Color.parseColor("#4A5568")
        private val ACCENT = Color.parseColor("#7CC4FF")
        private val GOOD = Color.parseColor("#5FD68B")
        private val WARN = Color.parseColor("#F0A868")
    }
}
