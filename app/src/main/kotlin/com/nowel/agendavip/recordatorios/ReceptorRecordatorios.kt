package com.nowel.agendavip.recordatorios

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Recibe las alarmas y los botones de los avisos del teléfono.
 * Un receptor es una pieza que Android llama al entregar un mensaje, incluso sin una pantalla abierta.
 * Este entrega cada acción al gestor que guarda y actualiza los recordatorios.
 */
class ReceptorRecordatorios : BroadcastReceiver() {

    /**
     * Android la llama con [context], el acceso a sus servicios, y [intent], el mensaje recibido.
     * Extrae la acción, el código de tarea y su fecha para completar, posponer, detener o emitir el aviso.
     * No devuelve datos: pasa el trabajo al ejecutor y este avisa a Android al terminar.
     * Los mensajes incompletos o con acciones desconocidas se ignoran.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val propietario = intent.getStringExtra(EspacioPersonal.EXTRA_ESPACIO) ?: return

        if (EspacioPersonal.claveActual(context) != propietario) {
            return
        }

        val accion = intent.action ?: return

        if (accion !in setOf(ACCION_ALARMA, ACCION_COMPLETAR, ACCION_POSPONER, ACCION_DETENER)) {
            return
        }

        val id = intent.getStringExtra("tarea_id") ?: return
        val fechaTexto = intent.getStringExtra("fecha_ocurrencia") ?: return
        // Conserva el permiso de Android para trabajar hasta que el ejecutor llame a finish.
        val pendiente = goAsync()

        EjecutorRecordatorios.ejecutar(context, pendiente) {
            synchronized(EspacioPersonal.cerrojoSesion) {
                // La cuenta puede haber salido mientras el mensaje esperaba su turno.
                if (EspacioPersonal.claveActual(context) != propietario) {
                    return@synchronized
                }

                val fecha = LocalDate.parse(fechaTexto)
                val gestor = GestorRecordatorios(context)

                when (accion) {
                    ACCION_ALARMA -> gestor.recibirAlarma(
                        id,
                        fecha,
                        intent.getLongExtra("momento_programado", -1L),
                    )

                    ACCION_COMPLETAR -> gestor.marcarCompletada(id, fecha, true)
                    ACCION_POSPONER -> gestor.posponer(id, fecha, 10)
                    ACCION_DETENER -> gestor.detenerSeguimiento(id, fecha)
                }
            }
        }
    }

    companion object {
        const val ACCION_ALARMA = "com.nowel.agendavip.ALARMA"
        const val ACCION_COMPLETAR = "com.nowel.agendavip.COMPLETAR"
        const val ACCION_POSPONER = "com.nowel.agendavip.POSPONER"
        const val ACCION_DETENER = "com.nowel.agendavip.DETENER"
    }
}

/**
 * Ejecuta los mensajes de ambos receptores uno detrás de otro para no procesar un aviso dos veces.
 * Usa un hilo de trabajo, una vía separada de la que dibuja la pantalla y atiende los botones.
 * Así una lectura o escritura de la agenda no deja la pantalla esperando.
 */
internal object EjecutorRecordatorios {

    private val ejecutor = Executors.newSingleThreadExecutor()

    /**
     * Recibe [contexto] para registrar problemas, [pendiente] para avisar a Android al terminar
     * y [bloque], las instrucciones que el receptor necesita ejecutar.
     * Los receptores de alarmas y reinicios la llaman para trabajar por turnos fuera de la pantalla.
     * No devuelve datos; ejecuta las instrucciones, registra cualquier fallo y siempre termina
     * la espera de Android, aunque ocurra un error.
     */
    fun ejecutar(
        contexto: Context,
        pendiente: BroadcastReceiver.PendingResult,
        bloque: () -> Unit
    ) {
        val propietario = EspacioPersonal.claveActual(contexto)
        val proteccion = ProteccionEntregaAviso.tomar(contexto)

        try {
            ejecutor.execute {
                try {
                    bloque()
                } catch (error: Exception) {
                    val mensaje = "No se pudo actualizar un recordatorio. Abre Agenda.VIP para revisar sus datos y permisos."
                    GestorRecordatorios.registrarProblema(contexto, mensaje, propietario)
                    Log.e("AgendaVIP", "Fallo al procesar un recordatorio: ${error.javaClass.simpleName}")
                } finally {
                    ProteccionEntregaAviso.liberar(proteccion)
                    pendiente.finish()
                }
            }
        } catch (error: RuntimeException) {
            ProteccionEntregaAviso.liberar(proteccion)
            pendiente.finish()
            GestorRecordatorios.registrarProblema(
                contexto,
                "Android no permitió procesar un aviso. Abre Agenda.VIP para reprogramarlo.",
                propietario,
            )
        }
    }
}
