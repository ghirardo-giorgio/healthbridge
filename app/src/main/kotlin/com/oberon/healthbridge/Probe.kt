package com.oberon.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Cosa c'e' in Health Connect, e ogni quanto ci arriva roba nuova.
 *
 * Il timestamp di un campione dice quando il battito e' stato misurato al
 * polso, non quando e' comparso qui: a quest'ultima risponde solo il registro
 * delle modifiche, che si porta dietro un segnalibro fra una chiamata e
 * l'altra.
 */
object Probe {

    /** Quanti record per tipo nelle ultime 24 h, e quanto e' fresco il piu' recente. */
    suspend fun probe(context: Context, client: HealthConnectClient): JSONObject {
        val now = Instant.now()
        val since = now.minus(24, ChronoUnit.HOURS)
        val granted = client.permissionController.getGrantedPermissions()

        val types = JSONObject()

        for (type in HealthAccess.types) {
            types.put(HealthAccess.label(type), runCatching {
                describe(client, type, since, now)
            }.getOrElse {
                JSONObject().put("error", it.message ?: it.javaClass.simpleName)
            })
        }

        return JSONObject()
            .put("state", "ok")
            .put("sdk", HealthAccess.sdkStatus(context))
            .put("now", now.epochSecond)
            .put("granted", JSONArray(granted.map { it.substringAfterLast('.') }.sorted()))
            .put("missing", JSONArray(
                (HealthAccess.wanted - granted).map { it.substringAfterLast('.') }.sorted()
            ))
            .put("types", types)
    }

    private suspend fun <T : Record> describe(
        client: HealthConnectClient,
        type: KClass<T>,
        since: Instant,
        now: Instant,
    ): JSONObject {
        // La pagina e' di mille record e il battito ne fa qualche migliaio al
        // giorno: in ordine crescente arriverebbero i mille piu' VECCHI, e il
        // «piu' recente» sarebbe quello di stanotte.
        val page = 1000
        val records = client.readRecords(
            ReadRecordsRequest(
                type,
                TimeRangeFilter.between(since, now),
                ascendingOrder = false,
                pageSize = page,
            )
        ).records

        val origins = records.map { it.metadata.dataOrigin.packageName }.distinct()

        // Per un record a intervallo non e' il suo inizio: un sonno cominciato
        // alle 23 e finito alle 7 e' fresco di stamattina.
        val latest = records.mapNotNull { latestInstant(it) }.maxOrNull()

        return JSONObject()
            .put("count", records.size)
            // Se la pagina e' piena il conto e' un minimo, non un totale.
            .put("truncated", records.size >= page)
            .put("origins", JSONArray(origins))
            .put("latest", latest?.epochSecond ?: JSONObject.NULL)
            // Mai negativo: un record che copre il minuto in corso finisce
            // qualche secondo nel futuro.
            .put(
                "lagSeconds",
                latest?.let { maxOf(0L, now.epochSecond - it.epochSecond) } ?: JSONObject.NULL
            )
            .apply {
                // Il battito e' l'unico a campioni, e un record puo'
                // contenerne trecento.
                if (type == HeartRateRecord::class) {
                    val samples = records.filterIsInstance<HeartRateRecord>().sumOf { it.samples.size }
                    put("samples", samples)
                }
            }
    }

    /** Cosa e' comparso dal giro precedente: la misura vera della cadenza del travaso. */
    suspend fun changes(client: HealthConnectClient, store: Store): JSONObject {
        val now = System.currentTimeMillis()
        val saved = store.changesToken

        if (saved == null) {
            store.changesToken = client.getChangesToken(
                ChangesTokenRequest(recordTypes = HealthAccess.types.toSet())
            )
            store.changesAt = now

            return JSONObject()
                .put("state", "ok")
                .put("started", true)
                .put("note", "bookmark taken: from the next call on it will say what appeared")
        }

        val response = client.getChanges(saved)

        if (response.changesTokenExpired) {
            store.changesToken = client.getChangesToken(
                ChangesTokenRequest(recordTypes = HealthAccess.types.toSet())
            )
            store.changesAt = now

            return JSONObject().put("state", "ok").put("expired", true)
                .put("note", "the bookmark had expired, a new one was taken")
        }

        val upserts = response.changes.filterIsInstance<UpsertionChange>().map { it.record }
        val perType = JSONObject()
        var samples = 0
        var oldest: Instant? = null
        var newest: Instant? = null

        for (record in upserts) {
            val key = HealthAccess.label(record::class)
            perType.put(key, perType.optInt(key, 0) + 1)

            if (record is HeartRateRecord) {
                samples += record.samples.size
                for (sample in record.samples) {
                    if (oldest == null || sample.time < oldest) oldest = sample.time
                    if (newest == null || sample.time > newest) newest = sample.time
                }
            }
        }

        store.changesToken = response.nextChangesToken
        val elapsed = (now - store.changesAt) / 1000
        store.changesAt = now

        return JSONObject()
            .put("state", "ok")
            .put("sinceLastCallSeconds", elapsed)
            .put("inserted", upserts.size)
            .put("perType", perType)
            .put("heartSamples", samples)
            .put("oldestSample", oldest?.epochSecond ?: JSONObject.NULL)
            .put("newestSample", newest?.epochSecond ?: JSONObject.NULL)
            // Il ritardo del travaso in un numero solo.
            .put("travelSeconds", newest?.let { now / 1000 - it.epochSecond } ?: JSONObject.NULL)
    }

    /**
     * Le interfacce che distinguono i record a intervallo da quelli istantanei
     * sono `internal` nella libreria: non si puo' chiedere «sei un
     * intervallo?», si elencano i tipi.
     */
    private fun latestInstant(record: Record): Instant? = when (record) {
        is HeartRateRecord -> record.endTime
        is StepsRecord -> record.endTime
        is SleepSessionRecord -> record.endTime
        is DistanceRecord -> record.endTime
        is TotalCaloriesBurnedRecord -> record.endTime
        is ActiveCaloriesBurnedRecord -> record.endTime
        is SkinTemperatureRecord -> record.endTime
        is OxygenSaturationRecord -> record.time
        is WeightRecord -> record.time
        else -> null
    }
}
