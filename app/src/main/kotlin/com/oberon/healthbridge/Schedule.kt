package com.oberon.healthbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.Calendar

/**
 * Quando il telefono deve smettere di rispondere, e quando ricominciare.
 *
 * A consumare quando non c'e' nessuno non e' il lavoro ma la presenza — il
 * servizio in primo piano, il WiFi sveglio, la porta aperta — e la fascia di
 * silenzio e' l'unica cosa che spegne anche quella. Spegnere non costa
 * storico: Health Connect continua ad accumulare per conto suo, e alla
 * riaccensione una sola lettura rilegge all'indietro tutta la finestra.
 */
class Schedule(context: Context) {

    private val prefs = context.getSharedPreferences("healthbridge", Context.MODE_PRIVATE)

    /** Se la fascia di silenzio e' in vigore. Spenta, il servizio resta su sempre. */
    var quiet: Boolean
        get() = prefs.getBoolean("quiet", true)
        set(value) = prefs.edit().putBoolean("quiet", value).apply()

    /** Minuti dalla mezzanotte: l'una di notte e le sette. */
    var from: Int
        get() = prefs.getInt("quiet_from", 1 * 60)
        set(value) = prefs.edit().putInt("quiet_from", value.coerceIn(0, 24 * 60 - 1)).apply()

    var to: Int
        get() = prefs.getInt("quiet_to", 7 * 60)
        set(value) = prefs.edit().putInt("quiet_to", value.coerceIn(0, 24 * 60 - 1)).apply()

    /**
     * Per inattivita' e non perche' qualcuno lo chieda: un client che muore di
     * colpo non fa in tempo a congedarsi, e un servizio che aspettasse il
     * congedo resterebbe sveglio per sempre.
     */
    var restAfterMinutes: Int
        get() = prefs.getInt("rest_after", 10)
        set(value) = prefs.edit().putInt("rest_after", value.coerceIn(1, 24 * 60)).apply()

    /**
     * Diverso da «e' acceso adesso»: dentro la fascia di silenzio resta vero
     * mentre il servizio e' giu'. E' anche cio' che guarda il BootReceiver.
     */
    var wanted: Boolean
        get() = prefs.getBoolean("wanted", false)
        set(value) = prefs.edit().putBoolean("wanted", value).apply()

    /** Vero se adesso e' ora di tacere. */
    fun silentNow(now: Long = System.currentTimeMillis()): Boolean {
        if (!quiet || from == to) return false

        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        // Una fascia che scavalca la mezzanotte non e' un intervallo:
        // 23:00-07:00 vuol dire «dopo le 23 oppure prima delle 7».
        return if (from < to) minute in from until to else minute >= from || minute < to
    }

    /** Quando cambia il vento: il prossimo istante in cui si tace o si torna a parlare. */
    fun nextBoundary(now: Long = System.currentTimeMillis()): Long? {
        if (!quiet || from == to) return null
        return atMinute(if (silentNow(now)) to else from, now)
    }

    /**
     * Sempre nel futuro: un allarme messo per un istante trascorso scatta
     * subito, e il servizio si rispegnerebbe appena acceso.
     */
    private fun atMinute(minuteOfDay: Int, now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    /**
     * `setAndAllowWhileIdle` e non `setExact`: riaccendersi alle 07:03 non
     * cambia niente, e l'allarme esatto vorrebbe `SCHEDULE_EXACT_ALARM`. Il
     * `AllowWhileIdle` invece serve: in Doze l'allarme aspetterebbe la
     * prossima finestra di manutenzione, che di notte vuol dire ore.
     */
    fun arm(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = intent(context)
        val next = nextBoundary()

        if (next == null || !wanted) {
            alarms.cancel(pending)
            return
        }

        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
    }

    /**
     * Un client che trova il telefono muto alle due di notte deve poter
     * distinguere «dorme fino alle sette» da «e' rotto».
     */
    fun describe(): JSONObject = JSONObject().apply {
        put("quiet", quiet)
        put("from", label(from))
        put("to", label(to))
        put("silentNow", silentNow())
        put("restAfterMinutes", restAfterMinutes)
        nextBoundary()?.let { put("nextChange", it / 1000) }
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ScheduleReceiver::class.java).setAction(ACTION_BOUNDARY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_BOUNDARY = "com.oberon.healthbridge.BOUNDARY"

        fun label(minuteOfDay: Int): String =
            "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

        fun parse(raw: String): Int? {
            val parts = raw.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}

/**
 * Sta nel manifest e non nel servizio apposta: dentro la fascia non c'e' piu'
 * niente di questa app in memoria, e a riaccenderla puo' essere solo qualcosa
 * che il sistema sappia far partire da solo.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val schedule = Schedule(context)

        if (!schedule.wanted) {
            schedule.cancel(context)
            return
        }

        if (schedule.silentNow()) ControlService.stop(context)
        else ControlService.start(context)

        // La sveglia successiva la mette questa, non il servizio: dentro la
        // fascia il servizio non esiste.
        schedule.arm(context)
    }
}

/**
 * Guarda `wanted` e non se il servizio girava: al riavvio non gira mai niente,
 * e la domanda giusta e' se l'utente lo voleva acceso.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val schedule = Schedule(context)
        if (!schedule.wanted) return

        if (!schedule.silentNow()) ControlService.start(context)
        schedule.arm(context)
    }
}
