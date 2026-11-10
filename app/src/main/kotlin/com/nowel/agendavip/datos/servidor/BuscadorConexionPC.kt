package com.nowel.agendavip.datos.servidor

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Permite al trabajador de sincronización buscar una PC con el descubridor que ya usa la pantalla.
 * Las respuestas llegan al hilo de Android; el trabajador espera como máximo doce segundos.
 */
internal object BuscadorConexionPC {

    /**
     * Recibe la identidad del servidor conocido y devuelve posibles direcciones locales.
     * Una dirección anunciada todavía debe confirmar el certificado antes de recibir el token.
     */
    fun buscar(contexto: Context, servidorId: String): List<String> {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "La búsqueda de la PC debe ejecutarse fuera de la pantalla."
        }
        val direcciones = mutableSetOf<String>()
        val terminado = CountDownLatch(1)
        val principal = Handler(Looper.getMainLooper())
        val descubridor = DescubridorPC(
            contexto,
            { computadora ->
                // Los anuncios antiguos no incluían la identidad completa. Conservan el prefijo
                // de su UUID en el nombre y luego también tienen que confirmar su certificado.
                val coincide = computadora.servidorId == servidorId ||
                    (computadora.servidorId.isNullOrBlank() &&
                        computadora.nombre.startsWith("AgendaVIP-" + servidorId.take(8)))
                if (coincide) {
                    synchronized(direcciones) {
                        direcciones.add(computadora.direccion)
                    }
                }
            },
            { terminado.countDown() },
        )

        principal.post {
            descubridor.buscar()
        }
        try {
            terminado.await(12, TimeUnit.SECONDS)
            return synchronized(direcciones) { direcciones.toList() }
        } finally {
            principal.post {
                descubridor.cancelar()
            }
        }
    }
}
