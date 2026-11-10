package com.nowel.agendavip.recordatorios.enfoque

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.recordatorios.ProteccionEntregaAviso
import java.util.concurrent.Executors

/**
 * Recibe las alarmas de comienzo/final y las acciones explícitas de las notificaciones.
 * Android puede llamarlo con el teléfono bloqueado; el trabajo se hace en un ayudante separado.
 */
class ReceptorEnfoque : BroadcastReceiver() {

    /**
     * Recibe el mensaje de Android, rechaza acciones desconocidas y comprueba el dueño.
     * goAsync conserva el turno del receptor mientras se guarda el cambio. La protección breve
     * mantiene el procesador activo hasta que el servicio obtiene la suya, sin una escucha permanente.
     */
    override fun onReceive(contexto: Context, intento: Intent) {
        val propietario = intento.getStringExtra(EspacioPersonal.EXTRA_ESPACIO) ?: return
        val id = intento.getStringExtra("enfoque_id") ?: return
        val accion = intento.action ?: return

        if (accion !in ACCIONES || EspacioPersonal.claveActual(contexto) != propietario) {
            return
        }

        val pendiente = goAsync()
        val proteccion = ProteccionEntregaAviso.tomar(contexto)
        val aplicacion = contexto.applicationContext

        try {
            ejecutor.execute {
                try {
                    synchronized(EspacioPersonal.cerrojoSesion) {
                        if (EspacioPersonal.claveActual(aplicacion) != propietario) {
                            return@synchronized
                        }
                        val gestor = GestorEnfoque(aplicacion)

                        when (accion) {
                            ACCION_ALARMA -> gestor.recibir(
                                id,
                                intento.getStringExtra("etapa_enfoque").orEmpty(),
                                intento.getLongExtra("momento_enfoque", -1L),
                            )
                            ACCION_INICIAR -> gestor.iniciar(id)
                            ACCION_PAUSAR -> gestor.pausar(id)
                            ACCION_CONTINUAR -> gestor.continuar(id)
                            ACCION_POSPONER -> gestor.posponer(id, intento.getIntExtra("minutos_enfoque", 30))
                            ACCION_COMPLETAR -> gestor.completar(id)
                            ACCION_CANCELAR -> gestor.cancelar(id)
                        }
                    }
                } catch (error: Exception) {
                    try {
                        if (EspacioPersonal.claveActual(aplicacion) == propietario) {
                            EspacioPersonal(aplicacion).preferencias("estado_enfoque").edit()
                                .putString("ultimo_error", error.message ?: "No se pudo atender el aviso de Enfoque.")
                                .apply()
                        }
                    } catch (_: RuntimeException) {
                        // El registro de diagnóstico tampoco debe impedir que termine el receptor.
                    }
                    Log.e("AgendaVIP", "Fallo al atender Enfoque: ${error.javaClass.simpleName}")
                } finally {
                    ProteccionEntregaAviso.liberar(proteccion)
                    pendiente.finish()
                }
            }
        } catch (_: RuntimeException) {
            ProteccionEntregaAviso.liberar(proteccion)
            pendiente.finish()
        }
    }

    companion object {
        const val ACCION_ALARMA = "com.nowel.agendavip.ENFOQUE_ALARMA"
        const val ACCION_INICIAR = "com.nowel.agendavip.ENFOQUE_INICIAR"
        const val ACCION_PAUSAR = "com.nowel.agendavip.ENFOQUE_PAUSAR"
        const val ACCION_CONTINUAR = "com.nowel.agendavip.ENFOQUE_CONTINUAR"
        const val ACCION_POSPONER = "com.nowel.agendavip.ENFOQUE_POSPONER"
        const val ACCION_COMPLETAR = "com.nowel.agendavip.ENFOQUE_COMPLETAR"
        const val ACCION_CANCELAR = "com.nowel.agendavip.ENFOQUE_CANCELAR"
        private val ejecutor = Executors.newSingleThreadExecutor()
        private val ACCIONES = setOf(ACCION_ALARMA, ACCION_INICIAR, ACCION_PAUSAR, ACCION_CONTINUAR, ACCION_POSPONER, ACCION_COMPLETAR, ACCION_CANCELAR)
    }
}