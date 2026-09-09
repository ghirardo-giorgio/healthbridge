package com.oberon.healthbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Le letture vere: il battito nel tempo e i numeri della giornata.
 *
 * Il battito lo aggrega questo file e non Health Connect, che qui e'
 * venticinque volte piu' lento (vedi i numeri in `heart`). Gli intervalli senza
 * campioni restano assenti invece che a zero: un buco resta un buco, e chi
 * disegna ci spezza la linea invece di raccontare un arresto cardiaco ogni
 * volta che il braccialetto sta sul comodino.
 *
 * I totali della giornata restano aggregati da Health Connect: li' i record
 * arrivano da piu' app e possono sovrapporsi, e scartare i doppioni giusti e'
 * un lavoro suo.
 */
object Vitals {

    /**
     * Il battito degli ultimi minuti, un punto per intervallo. `raw` salta
     * l'aggregazione: serve a guardare i campioni veri, non a disegnare.
     */
    suspend fun heart(
        client: HealthConnectClient,
        minutes: Long,
        bucketSeconds: Long,
        raw: Boolean,
    ): JSONObject {
        val now = Instant.now()

        // L'inizio si arrotonda al passo: partendo da «adesso meno un'ora» i
        // punti cadrebbero a secondi arbitrari — le 22:37:10, le 22:38:10 — e
        // chi li mette in una griglia al minuto non ne aggancerebbe nessuno.
        val since = Instant.ofEpochSecond(
            (now.epochSecond - minutes * 60) / bucketSeconds * bucketSeconds
        )

        val out = JSONObject()
            .put("state", "ok")
            .put("minutes", minutes)
            .put("now", now.epochSecond)

        if (raw) {
            val records = client.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(since, now),
                    ascendingOrder = false,
                )
            ).records

            val samples = JSONArray()
            var latest: Pair<Long, Long>? = null

            for (record in records) {
                for (sample in record.samples) {
                    samples.put(JSONArray().put(sample.time.epochSecond).put(sample.beatsPerMinute))
                    if (latest == null || sample.time.epochSecond > latest.first)
                        latest = sample.time.epochSecond to sample.beatsPerMinute
                }
            }

            return out
                .put("raw", true)
                .put("count", samples.length())
                .put("samples", samples)
                .putLatest(latest, now)
        }

        // Le medie fatte in casa invece che con `aggregateGroupByDuration`,
        // che su questo telefono e' inspiegabilmente lento. Misurato:
        //
        //     un'ora, aggregata da Health Connect      14 punti    10478 ms
        //     un'ora, campioni grezzi                 382 valori     434 ms
        //     ventiquattro ore, campioni grezzi     25615 valori    4303 ms
        //     ventiquattro ore, aggregate           non finisce in 200 secondi
        //
        // Si puo' fare perche' il battito lo scrive una sola app: dove le
        // origini sono due — i passi — sommare a mano conterebbe due volte cio'
        // che Health Connect sa scartare, e li' resta la sua (vedi `today`).
        val records = client.readRecords(
            ReadRecordsRequest(
                HeartRateRecord::class,
                TimeRangeFilter.between(since, now),
                ascendingOrder = false,
            )
        ).records

        // Una mappa e non un array: gli intervalli senza campioni non devono
        // esistere affatto.
        val sums = HashMap<Long, LongArray>()

        for (record in records) {
            for (sample in record.samples) {
                val slot = (sample.time.epochSecond - since.epochSecond) / bucketSeconds
                val bpm = sample.beatsPerMinute
                val slotSums = sums[slot]

                if (slotSums == null) {
                    sums[slot] = longArrayOf(bpm, 1L, bpm, bpm)
                } else {
                    slotSums[0] += bpm
                    slotSums[1] += 1L
                    if (bpm < slotSums[2]) slotSums[2] = bpm
                    if (bpm > slotSums[3]) slotSums[3] = bpm
                }
            }
        }

        // Quattro numeri per punto e non un oggetto con quattro chiavi: sono
        // le stesse chiavi ripetute centoventi volte.
        val buckets = JSONArray()
        var latest: Pair<Long, Long>? = null

        for (slot in sums.keys.sorted()) {
            val slotSums = sums.getValue(slot)
            val at = since.epochSecond + slot * bucketSeconds

            // Arrotondata e non troncata: una divisione fra interi farebbe di
            // sessantatre battiti e mezzo sessantatre invece di sessantaquattro.
            val avg = (slotSums[0] + slotSums[1] / 2) / slotSums[1]

            buckets.put(
                JSONArray()
                    .put(at)
                    .put(avg)
                    .put(slotSums[2])
                    .put(slotSums[3])
            )

            if (latest == null || at > latest.first) latest = at to avg
        }

        return out
            .put("bucketSeconds", bucketSeconds)
            .put("count", buckets.length())
            .put("buckets", buckets)
            .putLatest(latest, now)
    }

    /**
     * Da mezzanotte a adesso, piu' le cose che hanno un ritmo loro.
     *
     * Ogni voce ha il suo `runCatching` perche' un tipo che il telefono non
     * conosce non deve far fallire il resto. Ogni assenza e' `null`, mai zero:
     * «non lo so» e «zero passi» sono due cose diverse.
     */
    suspend fun today(client: HealthConnectClient): JSONObject {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val day = TimeRangeFilter.between(midnight, now)

        val totals = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_MAX,
                    ),
                    timeRangeFilter = day,
                )
            )
        }.getOrNull()

        return JSONObject()
            .put("state", "ok")
            .put("now", now.epochSecond)
            .put("since", midnight.epochSecond)
            .put("steps", totals?.get(StepsRecord.COUNT_TOTAL) ?: JSONObject.NULL)
            .put("distanceMeters", totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters ?: JSONObject.NULL)
            .put("calories", totals?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: JSONObject.NULL)
            .put("activeCalories", totals?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories ?: JSONObject.NULL)
            .put("bpmAvg", totals?.get(HeartRateRecord.BPM_AVG) ?: JSONObject.NULL)
            .put("bpmMin", totals?.get(HeartRateRecord.BPM_MIN) ?: JSONObject.NULL)
            .put("bpmMax", totals?.get(HeartRateRecord.BPM_MAX) ?: JSONObject.NULL)
            .put("sleep", runCatching { sleep(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("skinTemperature", runCatching { skin(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("oxygen", runCatching { oxygen(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("weight", runCatching { weight(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("hrv", runCatching { hrv(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("restingBpm", runCatching { restingBpm(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("respiratoryRate", runCatching { respiratory(client, now) }.getOrNull() ?: JSONObject.NULL)
            .put("vo2Max", runCatching { vo2max(client, now) }.getOrNull() ?: JSONObject.NULL)
    }

    /**
     * Valori notturni, uno per notte: non ha senso aggregarli, la domanda e'
     * sempre «l'ultimo che c'e', e di quando e'». Le quarantotto ore prendono
     * la notte scorsa anche a travaso in ritardo.
     */
    private suspend fun hrv(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                HeartRateVariabilityRmssdRecord::class,
                TimeRangeFilter.between(now.minusSeconds(48 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        return JSONObject()
            .put("at", record.time.epochSecond)
            .put("rmssdMillis", record.heartRateVariabilityMillis)
    }

    private suspend fun restingBpm(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                RestingHeartRateRecord::class,
                TimeRangeFilter.between(now.minusSeconds(48 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        return JSONObject()
            .put("at", record.time.epochSecond)
            .put("bpm", record.beatsPerMinute)
    }

    private suspend fun respiratory(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                RespiratoryRateRecord::class,
                TimeRangeFilter.between(now.minusSeconds(48 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        return JSONObject()
            .put("at", record.time.epochSecond)
            .put("breathsPerMinute", record.rate)
    }

    /**
     * Il VO2 max sta con il peso: Fitbit lo ricalcola ogni tanto e non ogni
     * notte, e un numero di tre settimane fa e' ancora la risposta giusta
     * purche' arrivi con la sua data.
     */
    private suspend fun vo2max(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                Vo2MaxRecord::class,
                TimeRangeFilter.between(now.minusSeconds(365L * 24 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        return JSONObject()
            .put("at", record.time.epochSecond)
            .put("mlPerMinPerKg", record.vo2MillilitersPerMinuteKilogram)
    }

    /** L'ultimo sonno delle ultime trentasei ore: abbastanza per prendere la notte scorsa. */
    private suspend fun sleep(client: HealthConnectClient, now: Instant): JSONObject? {
        val session = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(now.minusSeconds(36 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        val stages = JSONObject()

        for (stage in session.stages) {
            val key = stageName(stage.stage)
            val seconds = Duration.between(stage.startTime, stage.endTime).seconds
            stages.put(key, stages.optLong(key, 0L) + seconds)
        }

        return JSONObject()
            .put("start", session.startTime.epochSecond)
            .put("end", session.endTime.epochSecond)
            .put("seconds", Duration.between(session.startTime, session.endTime).seconds)
            .put("stages", stages)
    }

    private fun stageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
        else -> "other"
    }

    /**
     * Arriva come scostamento da una linea di base, non come gradi assoluti:
     * riportare il delta senza dirlo farebbe leggere «meno 0,3» come una
     * temperatura corporea.
     */
    private suspend fun skin(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                SkinTemperatureRecord::class,
                TimeRangeFilter.between(now.minusSeconds(48 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        val delta = record.deltas.map { it.delta.inCelsius }

        return JSONObject()
            .put("at", record.endTime.epochSecond)
            .put("baselineCelsius", record.baseline?.inCelsius ?: JSONObject.NULL)
            .put("deltaCelsius", if (delta.isEmpty()) JSONObject.NULL else delta.average())
    }

    private suspend fun oxygen(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                OxygenSaturationRecord::class,
                TimeRangeFilter.between(now.minusSeconds(48 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        return JSONObject()
            .put("at", record.time.epochSecond)
            .put("percent", record.percentage.value)
    }

    /** Senza la sua data una pesata di settimane fa si legge come di stamattina. */
    private suspend fun weight(client: HealthConnectClient, now: Instant): JSONObject? {
        val record = client.readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                TimeRangeFilter.between(now.minusSeconds(365L * 24 * 3600), now),
                ascendingOrder = false,
            )
        ).records.firstOrNull() ?: return null

        return JSONObject()
            .put("at", record.time.epochSecond)
            .put("kilograms", record.weight.inKilograms)
    }

    /** L'ultimo valore e quanto e' vecchio: la domanda vera di chi guarda. */
    private fun JSONObject.putLatest(latest: Pair<Long, Long>?, now: Instant): JSONObject = apply {
        if (latest == null) {
            put("latest", JSONObject.NULL)
            put("lagSeconds", JSONObject.NULL)
        } else {
            put("latest", JSONObject().put("t", latest.first).put("bpm", latest.second))
            put("lagSeconds", now.epochSecond - latest.first)
        }
    }
}
