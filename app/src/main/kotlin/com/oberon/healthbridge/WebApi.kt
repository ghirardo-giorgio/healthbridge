package com.oberon.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Cosa risponde il telefono a chi bussa.
 *
 * Le risposte sono in inglese, chiavi e prosa: chi scrive uno script non deve
 * indovinare in che lingua gli tornera' un errore. A parlare la lingua di chi
 * guarda sono la schermata dell'app e il pannello, ognuno per conto suo.
 *
 * Il pannello di `assets/` e' costruito su queste rotte e su nient'altro: se
 * un giorno gli servisse qualcosa che un `curl` non puo' ottenere, vorrebbe
 * dire che manca una rotta.
 */
class WebApi(
    private val context: Context,
    private val store: Store,
    private val access: Access,
    private val schedule: Schedule,
    private val engine: Engine,
) {

    /** Cio' che il server chiede a chi lo tiene acceso, e nient'altro. */
    interface Engine {
        /** Qualcuno ha chiesto qualcosa: il conto alla rovescia del riposo riparte. */
        fun touch()

        /** Il collegamento a Health Connect, aperto se non lo era. */
        fun client(): HealthConnectClient?

        fun rest()

        val resting: Boolean
    }

    /**
     * Una lettura alla volta, con una fila che ha una fine.
     *
     * `Job` scrive `status.json` e fa salire `seq`, quindi la fila ci vuole. Ma
     * senza un limite d'attesa si autoalimenta: una lettura lenta — cinquanta
     * secondi invece di cinque, a telefono addormentato — manda in timeout chi
     * aspetta, che riprova e si rimette in coda dietro alla vecchia. Equo di
     * proposito, se no la richiesta piu' sfortunata non entra mai.
     */
    private val lock = ReentrantLock(true)

    fun route(request: HttpServer.Request): HttpServer.Response {
        // Il preflight arriva senza chiave per definizione: il browser lo manda
        // prima di sapere se la richiesta e' lecita, e rispondergli 403 vuol
        // dire rifiutare la domanda vera che sarebbe arrivata dopo.
        if (request.method == "OPTIONS") return preflight()

        if (!access.allows(request)) {
            if (request.path == "/" && request.method == "GET") return locked()
            return HttpServer.Response.denied("missing or wrong key")
        }

        engine.touch()

        return when {
            request.path == "/" -> page()
            request.path == "/app.css" -> asset("app.css", "text/css; charset=utf-8")
            request.path == "/app.js" -> asset("app.js", "application/javascript; charset=utf-8")

            request.path == "/api" -> HttpServer.Response.json(vocabulary().toString())
            request.path == "/api/ping" -> HttpServer.Response.json(ping().toString())
            request.path == "/api/status" -> HttpServer.Response.json(store.readStatus())
            request.path == "/api/schedule" -> HttpServer.Response.json(schedule.describe().toString())

            request.path == "/api/sleep" && request.method == "POST" -> {
                engine.rest()
                HttpServer.Response.json("""{"state":"ok","lifecycle":"resting"}""")
            }

            request.path in READS -> read(request.path.removePrefix("/api/"), request.query)

            else -> HttpServer.Response.notFound()
        }
    }

    // -- le letture ---------------------------------------------------------

    /**
     * Una domanda a Health Connect, con lo stato HTTP che corrisponde: chi
     * scrive uno script guarda quello prima del corpo, e un errore restituito
     * con 200 verra' scambiato per un dato buono almeno una volta.
     */
    private fun read(action: String, params: Map<String, String>): HttpServer.Response {
        if (!lock.tryLock(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            return HttpServer.Response.json(
                """{"state":"error","error":"a read is already running, try again shortly"}""",
                "503 Service Unavailable",
            )
        }

        val status = try {
            Job.run(context, store, action, params, engine.client())
        } finally {
            lock.unlock()
        }

        val code = if (status.optString("state") == "ok") "200 OK" else "503 Service Unavailable"
        return HttpServer.Response.json(status.toString(), code)
    }

    // -- chi siamo ----------------------------------------------------------

    private fun ping(): JSONObject = JSONObject()
        .put("state", "ok")
        .put("pong", true)
        .put("api", Discovery.API)
        .put("lifecycle", if (engine.resting) "resting" else "active")

    /**
     * L'elenco di cio' che si sa fare, servito dall'app stessa: e' anche cio'
     * su cui il pannello costruisce la sua sezione «come usare l'API».
     */
    private fun vocabulary(): JSONObject = JSONObject().apply {
        put("state", "ok")
        put("api", Discovery.API)
        put("name", "HealthBridge")
        put(
            "auth",
            "the key goes in ?t=, in the \"${Access.COOKIE}\" cookie, " +
                "or in the X-Healthbridge-Token header"
        )
        put("routes", JSONArray().apply {
            put(route("GET", "/api", "this list"))
            put(route("GET", "/api/ping", "anybody there, API version and lifecycle state"))
            put(
                route(
                    "GET", "/api/heart",
                    "heart rate, one point per bucket",
                    "minutes (60), bucket (60), raw (on for single samples)",
                )
            )
            put(route("GET", "/api/today", "steps, calories, sleep and the rest of the day"))
            put(route("GET", "/api/probe", "what is in Health Connect, who wrote it, how fresh it is"))
            put(route("GET", "/api/changes", "what has appeared since you last asked"))
            put(route("GET", "/api/status", "the last answer, without reading again"))
            put(route("GET", "/api/schedule", "when the phone goes quiet and when it comes back"))
            put(route("POST", "/api/sleep", "send it to rest now instead of waiting for idleness"))
        })
        put(
            "note",
            "the freshest sample is usually 15-30 minutes old: the Fitbit app hands data " +
                "to Health Connect in batches. Always read lagSeconds before calling it \"now\"."
        )
    }

    private fun route(
        method: String,
        path: String,
        what: String,
        params: String? = null,
    ): JSONObject = JSONObject().apply {
        put("method", method)
        put("path", path)
        put("what", what)
        params?.let { put("params", it) }
    }

    // -- le pagine ----------------------------------------------------------

    private fun page(): HttpServer.Response {
        val html = runCatching {
            context.assets.open("index.html").use { it.readBytes() }
        }.getOrNull()
            ?: return HttpServer.Response.text("panel missing", "500 Internal Server Error")

        // La chiave arriva nell'indirizzo, che e' comodo per il QR e pessimo
        // per la cronologia del browser: appena e' servita diventa un cookie e
        // l'indirizzo torna pulito, cosi' un aggiornamento di pagina funziona
        // senza portarsi dietro il segreto nella barra.
        return HttpServer.Response(
            type = "text/html; charset=utf-8",
            body = html,
            headers = listOf(
                "Set-Cookie" to
                    "${Access.COOKIE}=${access.token}; Path=/; Max-Age=31536000; SameSite=Strict",
                "Cache-Control" to "no-store",
            ),
        )
    }

    private fun asset(name: String, type: String): HttpServer.Response {
        val bytes = runCatching {
            context.assets.open(name).use { it.readBytes() }
        }.getOrNull() ?: return HttpServer.Response.notFound()

        return HttpServer.Response.bytes(bytes, type)
    }

    /**
     * La pagina per chi arriva senza chiave: un 403 muto lascerebbe indovinare
     * fra «indirizzo sbagliato», «app rotta» e «mi manca qualcosa», che sono
     * tre gesti diversi.
     */
    private fun locked(): HttpServer.Response = HttpServer.Response(
        status = "401 Unauthorized",
        type = "text/html; charset=utf-8",
        body = """
            <!doctype html>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>HealthBridge</title>
            <style>
              body { background:#0E1116; color:#E8EDF4; font:16px/1.6 system-ui, sans-serif;
                     margin:0; min-height:100vh; display:grid; place-items:center; padding:24px; }
              div { max-width:32rem; }
              h1 { font-size:1.4rem; margin:0 0 .6rem; }
              p { color:#8D9AAC; margin:.4rem 0; }
              code { background:#222933; border-radius:6px; padding:.15em .4em; font-size:.9em; }
            </style>
            <div>
              <h1>The key is required</h1>
              <p>This is HealthBridge, but I don't know who you are.</p>
              <p>Open the app on your phone and scan the QR code, or add
                 <code>?t=YOUR_KEY</code> to this address.</p>
            </div>
        """.trimIndent().toByteArray(Charsets.UTF_8),
        headers = listOf("Cache-Control" to "no-store"),
    )

    private fun preflight(): HttpServer.Response = HttpServer.Response(
        status = "204 No Content",
        headers = listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "X-Healthbridge-Token",
            "Access-Control-Max-Age" to "86400",
        ),
    )

    companion object {
        /**
         * Piu' lungo di una lettura normale — cinque secondi — cosi' due
         * pannelli aperti insieme non si danno fastidio; molto piu' corto del
         * limite di `Job`, se no la fila torna quella di prima con un nome
         * nuovo.
         */
        private const val QUEUE_WAIT_MS = 20_000L

        private val READS = setOf(
            "/api/heart",
            "/api/today",
            "/api/probe",
            "/api/changes",
        )
    }
}
