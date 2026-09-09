package com.oberon.healthbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Come il telefono si fa trovare senza che nessuno scriva un indirizzo: un IP
 * del DHCP cambia da solo, e con mDNS chi cerca chiede alla rete invece che
 * alla memoria.
 *
 * L'annuncio vive quanto il servizio, cosi' un telefono che non si annuncia e'
 * un telefono che davvero non risponde. **La chiave nel record TXT non ci va**:
 * gli annunci li legge chiunque sia sulla rete.
 */
class Discovery(private val context: Context) {

    private var nsd: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var name: String? = null
        private set

    fun register(port: Int) {
        if (listener != null) return

        val manager = context.getSystemService(NsdManager::class.java) ?: return

        val info = NsdServiceInfo().apply {
            // Il modello nudo, senza «HealthBridge» davanti: il tipo di
            // servizio dice gia' cosa e' questa cosa, e il nome deve dire quale
            // telefono. Il prefisso e' costato caro — la dashboard cercava
            // «moto g24» e qui ci si annunciava «HealthBridge moto g24».
            serviceName = Build.MODEL
            serviceType = TYPE
            setPort(port)
            // Solo la versione del vocabolario: chi legge sa se parla la
            // stessa lingua prima di collegarsi.
            setAttribute("api", API.toString())
        }

        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                // Il sistema puo' aver cambiato il nome per evitare un omonimo
                // sulla stessa rete: quello buono e' questo.
                name = registered.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                name = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                name = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                name = null
            }
        }

        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback) }
            .onSuccess {
                nsd = manager
                listener = callback
            }
    }

    fun unregister() {
        val manager = nsd ?: return
        val callback = listener ?: return

        runCatching { manager.unregisterService(callback) }
        nsd = null
        listener = null
        name = null
    }

    companion object {
        const val TYPE = "_healthbridge._tcp"

        /**
         * La versione del vocabolario delle risposte: sale quando cambiano le
         * chiavi del JSON, non quando cambia l'app. A 2 dal passaggio delle
         * fasi del sonno alle chiavi inglesi (deep, rem, light, awake, other).
         */
        const val API = 2
    }
}
