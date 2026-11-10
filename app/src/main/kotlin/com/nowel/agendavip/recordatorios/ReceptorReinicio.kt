package com.nowel.agendavip.recordatorios

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.recordatorios.enfoque.RecuperadorEnfoque

/**
 * Un receptor es una pieza que Android llama al anunciar un evento, como encender el teléfono.
 * Este receptor recupera los avisos guardados cuando cambian condiciones del sistema.
 * No reproduce el audio durante el arranque: vuelve a pedir a Android que avise a su hora.
 */
class ReceptorReinicio : BroadcastReceiver() {

    /**
     * Android la llama con [context], el acceso a sus servicios, y con [intent], el mensaje del evento.
     * Si hubo un reinicio, actualización, cambio de hora o permiso de alarmas, pide al gestor
     * que vuelva a programar los avisos. Los demás eventos se ignoran.
     * No devuelve datos; entrega el trabajo al ejecutor para no ocupar la vía que atiende la pantalla.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val acciones = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )

        if (intent.action !in acciones) {
            return
        }

        val propietario = EspacioPersonal.claveActual(context) ?: return

        val esArranque = intent.action == Intent.ACTION_BOOT_COMPLETED

        // goAsync pide tiempo a Android para terminar el trabajo después de salir de onReceive.
        val pendiente = goAsync()
        EjecutorRecordatorios.ejecutar(context, pendiente) {
            synchronized(EspacioPersonal.cerrojoSesion) {
                if (EspacioPersonal.claveActual(context) != propietario) {
                    return@synchronized
                }

                try {
                    recuperarAvisos(context, esArranque)
                } finally {
                    // Se encola para la cuenta que recibió el reinicio, aunque falle una tarea normal.
                    RecuperadorEnfoque.solicitar(context, reinicio = esArranque)
                }
            }
        }
    }

    /**
     * Recibe Android y si el evento fue un encendido. onReceive la llama para reinstalar
     * primero los recordatorios normales, incluido el de despertar. Registra el encendido
     * y el final de la recuperación por separado. Descanso conserva su propio recorrido.
     */
    private fun recuperarAvisos(contexto: Context, esArranque: Boolean) {
        try {
            val gestor = GestorRecordatorios(contexto)
            if (esArranque) {
                gestor.registrarArranque(recuperado = false)
            }

            gestor.reprogramarTodo()

            if (esArranque) {
                gestor.registrarArranque(recuperado = true)
            }
        } finally {
            try {
                com.nowel.agendavip.recordatorios.descanso.GestorDescanso.actualizar(contexto)
            } catch (_: RuntimeException) {
                android.util.Log.w("AgendaVIP", "Falta recuperar la rutina de descanso.")
            }
        }
    }
}
