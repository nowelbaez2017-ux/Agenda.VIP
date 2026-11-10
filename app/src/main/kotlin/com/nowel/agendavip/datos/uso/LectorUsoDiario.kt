package com.nowel.agendavip.datos.uso

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.nowel.agendavip.dominio.uso.UsoDiario
import java.time.Instant
import java.time.ZoneId

/**
 * Consulta el registro de uso que Android proporciona con autorización de la persona.
 * Solo suma intervalos de la aplicación en primer plano: música de fondo no cuenta como pantalla.
 * Los eventos se procesan en memoria y no se guardan ni se envían fuera del teléfono.
 */
class LectorUsoDiario(contexto: Context) {

    private val contexto = contexto.applicationContext

    /** Devuelve si Android concedió Acceso de uso; ese acceso se otorga en Ajustes, no en un diálogo común. */
    @Suppress("DEPRECATION")
    fun tienePermiso(): Boolean {
        val permisos = contexto.getSystemService(AppOpsManager::class.java)
        return permisos.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            contexto.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Recibe los paquetes elegidos y el momento actual; devuelve su tiempo aproximado de hoy.
     * Se lee también ayer para reconocer una sesión que comenzó antes de medianoche.
     * Pantalla apagada, bloqueo, cambio de aplicación y reinicio cierran el intervalo anterior.
     * La pantalla y el servicio la llaman desde su trabajador, nunca mientras dibujan controles.
     */
    @Suppress("DEPRECATION")
    fun consultar(paquetes: Set<String>, ahora: Long = System.currentTimeMillis()): UsoDiario {
        check(tienePermiso()) { "Permite Acceso de uso para consultar el tiempo de las aplicaciones." }
        val zona = ZoneId.systemDefault()
        val hoy = Instant.ofEpochMilli(ahora).atZone(zona).toLocalDate()
        val inicio = hoy.atStartOfDay(zona).toInstant().toEpochMilli()
        val desde = hoy.minusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
        val eventos = contexto.getSystemService(UsageStatsManager::class.java)
            .queryEvents(desde, ahora)
            ?: error("Android todavía no permite consultar el uso. Desbloquea el teléfono y vuelve a intentarlo.")
        val resultado = paquetes.associateWith { 0L }.toMutableMap()
        var paqueteActual: String? = null
        var claseActual: String? = null
        var comienzo = desde

        /** Suma solo la parte del intervalo que pertenece a hoy y a una aplicación elegida. */
        fun cerrarIntervalo(hasta: Long) {
            val paquete = paqueteActual
            if (paquete != null && paquete in paquetes) {
                val duracion = (hasta.coerceAtMost(ahora) - comienzo.coerceAtLeast(inicio)).coerceAtLeast(0L)
                resultado[paquete] = (resultado[paquete] ?: 0L) + duracion
            }
        }

        val evento = UsageEvents.Event()
        while (eventos.hasNextEvent()) {
            check(!Thread.currentThread().isInterrupted) { "Se canceló la consulta de uso." }
            eventos.getNextEvent(evento)
            when (evento.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    cerrarIntervalo(evento.timeStamp)
                    paqueteActual = evento.packageName
                    claseActual = evento.className
                    comienzo = evento.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    // Al cambiar de pantalla dentro de una app, Android puede pausar la anterior
                    // después de abrir la nueva. No cerramos la nueva por esa pausa antigua.
                    if (evento.packageName == paqueteActual &&
                        (evento.className == claseActual || evento.className == null)) {
                        cerrarIntervalo(evento.timeStamp)
                        paqueteActual = null
                        claseActual = null
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN,
                UsageEvents.Event.DEVICE_STARTUP -> {
                    cerrarIntervalo(evento.timeStamp)
                    paqueteActual = null
                    claseActual = null
                }
            }
        }
        cerrarIntervalo(ahora)
        return UsoDiario(hoy, resultado)
    }
}
