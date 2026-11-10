package com.nowel.agendavip.recordatorios

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mantiene despierto el procesador durante el paso del receptor al servicio de sonido.
 * Android despierta el teléfono para entregar la alarma, pero ese permiso no cubre por sí
 * solo todo el trabajo que se hace después en otra vía. Esta protección tiene un máximo
 * de veinte segundos y se libera antes en cuanto el servicio termina de prepararse.
 */
internal object ProteccionEntregaAviso {

    const val EXTRA_PROTECCION = "proteccion_entrega_aviso"
    private val siguiente = AtomicLong(0L)
    private val pendientes = ConcurrentHashMap<Long, PowerManager.WakeLock>()
    private val principal = Handler(Looper.getMainLooper())

    /**
     * Recibe Android y devuelve un código que identifica una protección temporal.
     * El receptor y el inicio del servicio la llaman antes de devolver el control a Android.
     * Si no se puede obtener, devuelve cero; el permiso ya concedido a la alarma sigue siendo útil.
     */
    fun tomar(contexto: Context): Long {
        val codigo = siguiente.incrementAndGet()

        return try {
            val bloqueo = contexto.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${contexto.packageName}:entrega_aviso")
            bloqueo.setReferenceCounted(false)
            bloqueo.acquire(20_000L)
            pendientes[codigo] = bloqueo

            // También retira el registro si Android nunca llega a crear el servicio solicitado.
            principal.postDelayed({ liberar(codigo) }, 20_000L)
            codigo
        } catch (_: RuntimeException) {
            0L
        }
    }

    /**
     * Recibe el código de la protección y la suelta si todavía existe.
     * El final del trabajo, el servicio y el tiempo máximo pueden llamarla sin liberar dos veces.
     */
    fun liberar(codigo: Long) {
        val bloqueo = pendientes.remove(codigo) ?: return

        try {
            if (bloqueo.isHeld) {
                bloqueo.release()
            }
        } catch (_: RuntimeException) {
            // El límite de Android pudo liberar el bloqueo justo antes de esta llamada.
        }
    }
}