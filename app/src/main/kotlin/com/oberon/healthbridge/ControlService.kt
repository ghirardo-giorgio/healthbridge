package com.oberon.healthbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient

/**
 * Il servizio che tiene aperta la porta, in primo piano perche' un server che
 * smette di rispondere appena lo schermo si spegne non e' un server.
 *
 * Tiene anche il collegamento a Health Connect fra una richiesta e l'altra, ma
 * rende gratuito meno di quanto sembri: misurato, una lettura costa quattro
 * secondi e otto sia con il collegamento gia' aperto sia senza. Quei secondi
 * stanno dentro Health Connect; `ping` e `status`, che non lo toccano, costano
 * diciassette millisecondi.
 */
class ControlService : Service(), WebApi.Engine {

    private var server: HttpServer? = null
    private var lock: WifiManager.WifiLock? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var discovery: Discovery? = null

    @Volatile
    private var health: HealthConnectClient? = null

    @Volatile
    private var asleep = false

    private val clock = Handler(Looper.getMainLooper())
    private val toRest = Runnable { rest() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Spento a mano vuol dire spento, non «fino alla prossima sveglia»:
            // se restasse `wanted` il servizio tornerebbe su da solo alle sette
            // del mattino.
            Schedule(this).let { it.wanted = false; it.cancel(this) }
            stopSelf()
            return START_NOT_STICKY
        }

        val schedule = Schedule(this)
        schedule.wanted = true

        // Acceso dentro la fascia di silenzio: non e' un errore, e' solo il
        // momento sbagliato. Si mette la sveglia e si aspetta.
        if (schedule.silentNow()) {
            schedule.arm(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (server?.listening == true) {
            notifyNow()
            return START_STICKY
        }

        val access = Access(this)
        val store = Store(this)
        val api = WebApi(this, store, access, schedule, this)
        val http = HttpServer(access.port) { request -> api.route(request) }

        try {
            http.start()
        } catch (failure: Throwable) {
            error = failure.message ?: getString(R.string.port_failed, access.port)
            stopSelf()
            return START_NOT_STICKY
        }

        server = http
        error = null
        asleep = false

        // Senza questo il WiFi si addormenta con lo schermo e il PC vede la
        // porta sparire dopo qualche minuto di silenzio. E' anche cio' che
        // rende automatico il risveglio dal riposo.
        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager

        lock = wifi
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "healthbridge:server")
            .apply { runCatching { acquire() } }

        // Il WifiLock tiene sveglia la radio ma non il multicast: senza questo
        // secondo lucchetto il telefono continua a rispondere a chi conosce il
        // suo indirizzo ma smette di annunciarsi, e chi lo cerca conclude che
        // non ci sia. Visto succedere con `/api/ping` vivo e `avahi-browse`
        // vuoto.
        multicast = wifi.createMulticastLock("healthbridge:mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        discovery = Discovery(this).apply { register(access.port) }

        // Aperto adesso e non alla prima richiesta, cosi' un guasto si vede
        // nella schermata invece che alla prima lettura di qualcun altro.
        health = HealthAccess.client(this)

        schedule.arm(this)
        idle()
        notifyNow()
        return START_STICKY
    }

    override fun onDestroy() {
        clock.removeCallbacks(toRest)
        discovery?.unregister()
        runCatching { multicast?.release() }
        runCatching { lock?.release() }
        server?.stop()
        server = null
        lock = null
        multicast = null
        discovery = null
        health = null
        live = null
        super.onDestroy()
    }

    // -- il ciclo di vita ---------------------------------------------------

    /**
     * Rimanda il riposo ma non ne fa uscire: svegliarsi a ogni richiesta
     * vorrebbe dire svegliarsi anche per un `ping`, e allora `lifecycle` non
     * direbbe mai «resting» — uno stato che l'atto di guardarlo distrugge.
     */
    override fun touch() {
        idle()
    }

    /**
     * Il collegamento, aperto se non lo era — ed e' qui che si esce dal riposo:
     * il risveglio sta attaccato al bisogno di Health Connect, non all'essere
     * stati interpellati.
     */
    override fun client(): HealthConnectClient? {
        if (asleep) {
            asleep = false
            notifyNow()
        }

        health?.let { return it }

        // Null vuol dire due cose diverse — non l'abbiamo ancora aperto, o
        // Health Connect non c'e' — trattate uguale apposta: il messaggio
        // giusto lo costruisce `Job` guardando `sdkStatus`.
        return HealthAccess.client(this).also { health = it }
    }

    /**
     * Rilascia il collegamento a Health Connect: e' memoria e un binder, non
     * energia — a portare il consumo a zero ci pensa la fascia di silenzio. Il
     * socket resta aperto apposta, ed e' cio' che permette alla prima richiesta
     * di risvegliare tutto da sola.
     */
    override fun rest() {
        clock.removeCallbacks(toRest)
        if (asleep) return

        health = null
        asleep = true
        notifyNow()
    }

    override val resting: Boolean get() = asleep

    private fun idle() {
        val after = Schedule(this).restAfterMinutes.toLong() * 60_000L
        clock.removeCallbacks(toRest)
        clock.postDelayed(toRest, after)
    }

    // -- la notifica --------------------------------------------------------

    private fun notifyNow() {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val address = Net.best()?.let { "$it:${Access(this).port}" }
            ?: getString(R.string.notif_no_network)
        val schedule = Schedule(this)

        val detail = buildString {
            append(address)
            if (asleep) append(" · ").append(getString(R.string.notif_resting))
            if (schedule.quiet) {
                append(" · ")
                append(getString(R.string.notif_quiet_at, Schedule.label(schedule.from)))
            }
        }

        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_stat_healthbridge)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    getString(R.string.notif_stop),
                    stop,
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    companion object {
        private const val CHANNEL = "healthbridge-server"
        private const val NOTIFICATION = 1
        const val ACTION_STOP = "com.oberon.healthbridge.STOP"

        @Volatile
        private var live: ControlService? = null

        @Volatile
        var error: String? = null
            private set

        val running: Boolean get() = live?.server?.listening == true

        val resting: Boolean get() = live?.asleep == true

        /** Il nome con cui il telefono si annuncia, se si sta annunciando. */
        val advertised: String? get() = live?.discovery?.name

        fun start(context: Context) {
            error = null
            context.startForegroundService(Intent(context, ControlService::class.java))
        }

        /**
         * La fascia di silenzio: ferma il servizio senza toccare `wanted`, che
         * e' tutta la differenza con `quit`. La sveglia resta armata e
         * domattina il telefono torna su da solo.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ControlService::class.java)) }
        }

        /**
         * Spegnere davvero. Passa per `startService` con un'azione e non per
         * `stopService` perche' il servizio deve poter azzerare `wanted`: se no
         * un'app spenta a mano tornerebbe su da sola alla prossima sveglia.
         */
        fun quit(context: Context) {
            context.startService(
                Intent(context, ControlService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
