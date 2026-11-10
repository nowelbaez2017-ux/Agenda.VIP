package com.nowel.agendavip.datos.servidor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** Reúne el nombre visible de una PC encontrada y su dirección HTTPS local. */
data class ComputadoraPC(
    val nombre: String,
    val direccion: String,
    val servidorId: String? = null,
    val huella: String? = null,
)

/**
 * Busca servidores de Agenda.VIP anunciados por la PC en la misma red.
 * NSD significa descubrimiento de servicios: Android pregunta por un tipo de servicio
 * y recibe su nombre y dirección. Encontrar una PC todavía no autoriza compartir datos.
 */
class DescubridorPC(
    contexto: Context,
    private val alEncontrar: (ComputadoraPC) -> Unit,
    private val alTerminar: (String?) -> Unit,
) {

    private val sistema = contexto.applicationContext.getSystemService(NsdManager::class.java)
    private val principal = Handler(Looper.getMainLooper())
    private val pendientes = ArrayDeque<NsdServiceInfo>()
    private val direcciones = mutableSetOf<String>()
    private var descubrimiento: NsdManager.DiscoveryListener? = null
    private var resolviendo = false
    private var turno = 0

    private val limite = Runnable {
        finalizar(
            if (direcciones.isEmpty()) {
                "No encontramos una PC. Puedes escribir la dirección que muestra su servidor."
            } else {
                null
            },
            avisar = true,
        )
    }

    /**
     * El botón Buscar mi PC la llama. Empieza una búsqueda de diez segundos.
     * Cada equipo encontrado se entrega a alEncontrar y el cierre llega a alTerminar.
     */
    fun buscar() {
        cancelar()
        val consulta = ++turno
        direcciones.clear()

        val oyente = object : NsdManager.DiscoveryListener {

            /** Android confirma que empezó la búsqueda; el temporizador ya controla su duración. */
            override fun onDiscoveryStarted(tipo: String?) = Unit

            /** Entrega un servicio encontrado para resolver su dirección sin mezclar búsquedas antiguas. */
            override fun onServiceFound(servicio: NsdServiceInfo) {
                principal.post {
                    if (turno == consulta && descubrimiento != null &&
                        servicio.serviceType.trimEnd('.') == TIPO.trimEnd('.')
                    ) {
                        pendientes.addLast(servicio)
                        resolverSiguiente(consulta)
                    }
                }
            }

            /** Perder un anuncio no borra una dirección ya entregada; la conexión comprobará si responde. */
            override fun onServiceLost(servicio: NsdServiceInfo) = Unit

            /** Android indica que la búsqueda se detuvo. finalizar ya retiró su estado y temporizador. */
            override fun onDiscoveryStopped(tipo: String?) = Unit

            /** Android no pudo iniciar. Ofrece escribir la dirección sin conceder una vinculación. */
            override fun onStartDiscoveryFailed(tipo: String?, codigo: Int) {
                principal.post {
                    if (turno == consulta) {
                        finalizar("No se pudo buscar en esta red. Escribe la dirección de tu PC.", avisar = true)
                    }
                }
            }

            /** Si Android ya cerró la búsqueda, no se vuelve a intentar detenerla. */
            override fun onStopDiscoveryFailed(tipo: String?, codigo: Int) = Unit
        }

        descubrimiento = oyente
        try {
            sistema.discoverServices(TIPO, NsdManager.PROTOCOL_DNS_SD, oyente)
            principal.postDelayed(limite, 10_000L)
        } catch (_: RuntimeException) {
            finalizar("La búsqueda no está disponible. Escribe la dirección de tu PC.", avisar = true)
        }
    }

    /**
     * Recibe el turno vigente y pide a Android resolver un anuncio cada vez.
     * Resolver significa obtener la dirección y el puerto detrás del nombre publicado.
     * Evitar dos resoluciones simultáneas también admite teléfonos con Android antiguo.
     */
    @Suppress("DEPRECATION")
    private fun resolverSiguiente(consulta: Int) {
        if (turno != consulta || descubrimiento == null || resolviendo || pendientes.isEmpty()) {
            return
        }

        val servicio = pendientes.removeFirst()
        resolviendo = true
        try {
            sistema.resolveService(servicio, object : NsdManager.ResolveListener {

                /** Descarta este anuncio si Android no logró resolverlo y continúa con el siguiente. */
                override fun onResolveFailed(info: NsdServiceInfo?, codigo: Int) {
                    principal.post {
                        if (turno == consulta && descubrimiento != null) {
                            resolviendo = false
                            resolverSiguiente(consulta)
                        }
                    }
                }

                /** Entrega únicamente direcciones privadas de equipos compatibles con esta primera versión. */
                override fun onServiceResolved(info: NsdServiceInfo) {
                    principal.post {
                        if (turno != consulta || descubrimiento == null) {
                            return@post
                        }

                        resolviendo = false
                        val hosts = if (Build.VERSION.SDK_INT >= 34) {
                            info.hostAddresses
                        } else {
                            listOfNotNull(info.host)
                        }
                        val identidad = info.attributes["servidor_id"]?.toString(Charsets.UTF_8)
                        val huella = info.attributes["huella"]?.toString(Charsets.UTF_8)
                        hosts.forEach { host ->
                            val direccion = host.hostAddress.orEmpty()
                            if (ClientePC.esDireccionLocal(direccion) && info.port in 1..65535) {
                                val url = "https://$direccion:${info.port}"
                                if (direcciones.add(url)) {
                                    alEncontrar(ComputadoraPC(info.serviceName, url, identidad, huella))
                                }
                            }
                        }
                        resolverSiguiente(consulta)
                    }
                }
            })
        } catch (_: RuntimeException) {
            resolviendo = false
            resolverSiguiente(consulta)
        }
    }

    /** La pantalla la llama al salir o empezar otra búsqueda. Descarta resultados y retira el temporizador. */
    fun cancelar() {
        finalizar(null, avisar = false)
    }

    /**
     * Recibe la explicación final y si debe avisarse a la pantalla.
     * Cierra la búsqueda de Android y cambia el turno para ignorar resoluciones pendientes.
     */
    private fun finalizar(mensaje: String?, avisar: Boolean) {
        turno += 1
        principal.removeCallbacks(limite)
        pendientes.clear()
        resolviendo = false
        val oyente = descubrimiento
        descubrimiento = null

        if (oyente != null) {
            try {
                sistema.stopServiceDiscovery(oyente)
            } catch (_: RuntimeException) {
                // La búsqueda pudo haber sido cerrada ya por Android al cambiar de red.
            }
        }
        if (avisar) {
            alTerminar(mensaje)
        }
    }

    private companion object {
        const val TIPO = "_agendavip._tcp."
    }
}