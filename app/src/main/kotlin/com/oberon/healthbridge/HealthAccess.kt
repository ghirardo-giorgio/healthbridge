package com.oberon.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * Il collegamento a Health Connect e l'elenco di cio' che si ha il diritto di
 * chiedere: sta da solo perche' e' l'unico pezzo che cambierebbe passando
 * all'API di piattaforma.
 *
 * I tipi sono quelli che l'app Fitbit scrive davvero. Gli ultimi quattro sono
 * comparsi il 25 agosto 2026, concedendo a mano i WRITE_* corrispondenti: da
 * quel giorno in avanti, perche' lo storico non viene travasato all'indietro.
 */
object HealthAccess {

    /** I tipi che si leggono, in un posto solo perche' li usano probe e changes. */
    val types: List<KClass<out Record>> = listOf(
        HeartRateRecord::class,
        StepsRecord::class,
        SleepSessionRecord::class,
        DistanceRecord::class,
        TotalCaloriesBurnedRecord::class,
        ActiveCaloriesBurnedRecord::class,
        SkinTemperatureRecord::class,
        OxygenSaturationRecord::class,
        WeightRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        RestingHeartRateRecord::class,
        RespiratoryRateRecord::class,
        Vo2MaxRecord::class,
    )

    val wanted: Set<String> = types.map { HealthPermission.getReadPermission(it) }.toSet()

    /**
     * Tutto quello che si chiede all'utente in una volta sola, ricavato da qui
     * e non da una lista scritta altrove: due elenchi della stessa cosa
     * divergono sempre, e quattro permessi sono rimasti negati per settimane
     * senza che niente lo dicesse.
     *
     * Il permesso di leggere in background non e' un tipo di dato, ma senza il
     * servizio smetterebbe di rispondere a schermo spento.
     */
    val all: Set<String> = wanted + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /**
     * Non `getOrCreate` e basta: dove Health Connect non c'e' o e' vecchio,
     * quella lancia, e la ragione va detta a chi legge il JSON.
     */
    fun client(context: Context): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        else null

    fun sdkStatus(context: Context): String =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                "Health Connect is installed but needs an update"
            else -> "Health Connect is not available on this phone"
        }

    /** Il nome corto di un tipo, quello che finisce nelle chiavi del JSON. */
    fun label(type: KClass<out Record>): String =
        type.simpleName.orEmpty().removeSuffix("Record").replaceFirstChar { it.lowercase() }
}
