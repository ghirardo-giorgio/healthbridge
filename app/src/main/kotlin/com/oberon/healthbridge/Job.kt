package com.oberon.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Il lavoro vero, staccato da chi lo chiede: chi chiama e' qualunque cosa parli
 * HTTP, e una logica che sapesse di stare dietro a un server sarebbe una
 * trappola.
 *
 * Scrive in `status.json` anche quando la risposta torna al chiamante: e' il
 * modo di rileggere l'ultima lettura senza rifarla, e di vedere cos'e' successo
 * quando una risposta non e' arrivata affatto.
 */
object Job {

    /**
     * @param client il collegamento gia' aperto, se chi chiama ne tiene uno
     *   caldo. Risparmia meno di quanto sembri: misurato sul moto g24, una
     *   lettura costa quasi cinque secondi in entrambi i casi — il prezzo sta
     *   dentro Health Connect, non nell'arrivarci.
     */
    fun run(
        context: Context,
        store: Store,
        action: String,
        params: Map<String, String> = emptyMap(),
        client: HealthConnectClient? = null,
    ): JSONObject {
        val seq = store.nextSeq()
        val started = System.currentTimeMillis()

        store.writeStatus(JSONObject().apply {
            put("seq", seq)
            put("state", "busy")
            put("action", action)
            put("ts", started)
        })

        val status = try {
            when (action) {
                "ping" -> JSONObject().put("state", "ok").put("pong", true)
                else -> withClient(context, store, action, params, client)
            }
        } catch (failure: Throwable) {
            JSONObject()
                .put("state", "error")
                .put("error", failure.message ?: failure.javaClass.simpleName)
        }

        status.put("seq", seq)
        status.put("action", action)
        status.put("ts", started)
        status.put("tookMs", System.currentTimeMillis() - started)
        store.writeStatus(status)

        return status
    }

    /**
     * I comandi che hanno bisogno di Health Connect, con il motivo pronto per
     * quando non c'e': «non disponibile» e «manca il permesso» portano a due
     * gesti diversi.
     */
    private fun withClient(
        context: Context,
        store: Store,
        action: String,
        params: Map<String, String>,
        given: HealthConnectClient?,
    ): JSONObject {
        val client = given ?: HealthAccess.client(context)
            ?: return JSONObject().put("state", "error")
                .put("error", HealthAccess.sdkStatus(context))

        return runBlocking {
            try {
                withTimeout(READ_TIMEOUT_MS) {
                    when (action) {
                        "probe" -> Probe.probe(context, client)
                        "changes" -> Probe.changes(client, store)
                        "heart" -> Vitals.heart(
                            client,
                            minutes = params.number("minutes", 60),
                            bucketSeconds = params.number("bucket", 60),
                            raw = params["raw"].isOn(),
                        )
                        "today" -> Vitals.today(client)
                        else -> JSONObject().put("state", "error")
                            .put(
                                "error",
                                "unknown command: $action (ping | probe | changes | heart | today)"
                            )
                    }
                }
            } catch (slow: TimeoutCancellationException) {
                JSONObject().put("state", "error").put(
                    "error",
                    "Health Connect did not answer within ${READ_TIMEOUT_MS / 1000} seconds: " +
                        "it happens when Android has put the app to sleep"
                )
            }
        }
    }

    /**
     * Una lettura non ha un tempo suo: cinque secondi a telefono sveglio,
     * cinquantadue con lo schermo spento da ore e l'app non esentata da Doze —
     * misurati. Senza un limite resta appesa a tenersi il turno, e le letture
     * vanno una per volta.
     */
    private const val READ_TIMEOUT_MS = 90_000L

    /** I parametri arrivano tutti come stringhe, anche i numeri. */
    private fun Map<String, String>.number(key: String, fallback: Long): Long =
        this[key]?.trim()?.toLongOrNull() ?: fallback

    private fun String?.isOn() = this == "on" || this == "true" || this == "1"
}
