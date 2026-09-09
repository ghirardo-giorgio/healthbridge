package com.oberon.healthbridge

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * L'indirizzo a cui rispondere e la chiave per farsi aprire.
 *
 * La porta la vede chiunque sia collegato al router, e un anno di battito
 * cardiaco servito a chi passa non si riprende indietro. La chiave nasce al
 * primo avvio, sta nelle preferenze e si consegna col QR — per questo puo'
 * permettersi di essere illeggibile.
 */
class Access(context: Context) {

    private val prefs = context.getSharedPreferences("healthbridge", Context.MODE_PRIVATE)

    val token: String
        get() = prefs.getString("token", null) ?: renew()

    var port: Int
        get() = prefs.getInt("port", DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value.coerceIn(1024, 65535)).apply()

    fun renew(): String {
        val bytes = ByteArray(9)
        SecureRandom().nextBytes(bytes)
        // Base32 senza le lettere che si confondono con le cifre: se qualcuno
        // deve leggere la chiave da uno schermo e scriverla su un altro, la
        // differenza fra O e 0 e' mezz'ora persa.
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val fresh = bytes.joinToString("") { alphabet[(it.toInt() and 0xff) % 31].toString() }
        prefs.edit().putString("token", fresh).apply()
        return fresh
    }

    /**
     * Tre modi di presentare la chiave perche' i chiamanti sono di tre specie:
     * il QR la mette nell'indirizzo, il browser nel cookie, chi scrive uno
     * script nell'header — l'unico posto dove non finisce in una cronologia.
     */
    fun allows(request: HttpServer.Request): Boolean {
        val given = request.query["t"]
            ?: request.cookies[COOKIE]
            ?: request.header("x-healthbridge-token")
            ?: return false

        return constantTimeEquals(given, token)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        // 8421 e non 8420: quella e' di MacroCam, e i due telefoni potrebbero
        // essere lo stesso.
        const val DEFAULT_PORT = 8421
        const val COOKIE = "healthbridge"
    }
}

object Net {

    /**
     * Gli indirizzi utili per primi: un telefono ne ha sempre piu' d'uno e
     * quasi nessuno e' raggiungibile dal PC di casa. Quello che serve e' il
     * WiFi, o l'hotspot quando e' il telefono a fare da rete.
     */
    fun addresses(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return emptyList()

        for (nic in interfaces) {
            if (runCatching { !nic.isUp || nic.isLoopback }.getOrDefault(true)) continue

            val name = nic.name.lowercase()
            val rank = when {
                name.startsWith("wlan") -> 0
                name.startsWith("ap") || name.startsWith("swlan") -> 1
                name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis") -> 2
                else -> 3
            }

            for (address in nic.inetAddresses) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                found.add(rank to address.hostAddress.orEmpty())
            }
        }

        return found.sortedBy { it.first }.map { it.second }.filter { it.isNotEmpty() }.distinct()
    }

    fun best(): String? = addresses().firstOrNull()
}
