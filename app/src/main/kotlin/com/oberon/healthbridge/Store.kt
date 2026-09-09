package com.oberon.healthbridge

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Dove l'app lascia l'ultima risposta.
 *
 * Sotto `getExternalFilesDir`, cioe'
 * `/sdcard/Android/data/com.oberon.healthbridge/files`: l'unico posto che non
 * chiede permessi di archiviazione e che la shell di adb legge lo stesso.
 */
class Store(context: Context) {

    val root: File = context.getExternalFilesDir(null)
        ?: File(context.filesDir, "external")

    private val prefs = context.getSharedPreferences("healthbridge", Context.MODE_PRIVATE)

    val statusFile = File(root, "status.json")

    /** Sale a ogni comando: chi guarda `status.json` sa quando e' cambiato. */
    fun nextSeq(): Long {
        val next = prefs.getLong("seq", 0L) + 1L
        prefs.edit().putLong("seq", next).apply()
        return next
    }

    fun seq(): Long = prefs.getLong("seq", 0L)

    /**
     * Il segnalibro del registro delle modifiche: sopravvive fra un comando e
     * l'altro perche' e' l'unico modo di chiedere cosa e' comparso da quando si
     * era guardato.
     */
    var changesToken: String?
        get() = prefs.getString("changes_token", null)
        set(value) = prefs.edit().putString("changes_token", value).apply()

    var changesAt: Long
        get() = prefs.getLong("changes_at", 0L)
        set(value) = prefs.edit().putLong("changes_at", value).apply()

    /** Scrittura in due tempi: chi legge trova il file vecchio o quello nuovo, mai mezzo. */
    fun writeStatus(status: JSONObject) {
        val temp = File(root, "status.json.tmp")
        temp.writeText(status.toString(2))
        temp.renameTo(statusFile)
    }

    fun readStatus(): String =
        if (statusFile.exists()) statusFile.readText() else """{"state":"none"}"""
}
