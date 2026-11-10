package com.nowel.agendavip.recordatorios

import android.content.Context
import android.util.Log
import com.nowel.agendavip.datos.agenda.ErrorDatosAgenda
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.recordatorios.enfoque.RecuperadorEnfoque
import java.util.concurrent.Executors

/**
 * Recupera las alarmas pendientes sin depender de cuánto tiempo permanezca abierta una pantalla.
 * Un ejecutor es un trabajador separado que atiende sus encargos uno detrás de otro.
 * ActividadAgenda solicita la recuperación al volver; salir o cambiar de sección no la interrumpe.
 * No mantiene el teléfono despierto permanentemente: las alarmas resultantes pertenecen a Android.
 */
object RecuperadorRecordatorios {

    private val ejecutor = Executors.newSingleThreadExecutor()
    private val cerrojoSolicitudes = Any()
    private val pendientes = mutableSetOf<String>()

    /**
     * Recibe Android y solicita recuperar los avisos de la cuenta que está activa en ese instante.
     * onResume de la agenda la llama sin esperar el resultado. Conserva solo applicationContext,
     * que permite usar Android sin retener la pantalla después de cerrarla.
     * Si esa cuenta ya tiene una recuperación esperando o trabajando, no añade otra igual.
     */
    fun solicitar(contexto: Context) {
        val aplicacion = contexto.applicationContext
        val propietario = EspacioPersonal.claveActual(aplicacion) ?: return

        // Abrir la agenda también recupera Enfoque. Cada grupo tiene su propio trabajador:
        // una pantalla cerrada o un dato dañado de tareas no cancela los avisos de Enfoque.
        RecuperadorEnfoque.solicitar(aplicacion)
        val nueva = synchronized(cerrojoSolicitudes) {
            pendientes.add(propietario)
        }

        if (!nueva) {
            return
        }

        val proteccion = ProteccionEntregaAviso.tomar(aplicacion)

        try {
            ejecutor.execute {
                try {
                    synchronized(EspacioPersonal.cerrojoSesion) {
                        // La persona puede haber cerrado sesión mientras esperaba este encargo.
                        if (EspacioPersonal.claveActual(aplicacion) == propietario) {
                            try {
                                GestorRecordatorios(aplicacion).reprogramarTodo()
                            } finally {
                                // Descanso también se recupera aunque falle un recordatorio normal.
                                runCatching {
                                    com.nowel.agendavip.recordatorios.descanso.GestorDescanso.actualizar(aplicacion)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    registrarFallo(aplicacion, propietario, error)
                } finally {
                    ProteccionEntregaAviso.liberar(proteccion)
                    retirarSolicitud(propietario)
                }
            }
        } catch (error: RuntimeException) {
            ProteccionEntregaAviso.liberar(proteccion)
            retirarSolicitud(propietario)
            registrarFallo(aplicacion, propietario, error)
        }
    }

    /**
     * Recibe la cuenta cuyo encargo terminó o no pudo empezar y libera su marca de espera.
     * solicitar la llama al terminar incluso si hubo un error, para permitir otro intento posterior.
     * No modifica la sesión, las tareas ni las alarmas ya entregadas a Android.
     */
    private fun retirarSolicitud(propietario: String) {
        synchronized(cerrojoSolicitudes) {
            pendientes.remove(propietario)
        }
    }

    /**
     * Recibe Android, la cuenta solicitante y el fallo de recuperación.
     * Conserva la explicación de una agenda dañada y registra el problema en el mismo espacio
     * personal, para poder consultarlo en Permisos del teléfono. No convierte datos dañados
     * en una agenda vacía ni los sobrescribe para continuar.
     */
    private fun registrarFallo(contexto: Context, propietario: String, error: Exception) {
        val mensaje = if (error is ErrorDatosAgenda) {
            error.message ?: "No se pudo leer la agenda para recuperar sus avisos. Los datos se conservaron."
        } else {
            "No se pudieron recuperar todos los avisos. Revisa los permisos y el último problema registrado."
        }

        try {
            GestorRecordatorios.registrarProblema(contexto, mensaje, propietario)
        } catch (errorRegistro: Exception) {
            // Si tampoco se puede guardar la explicación, Logcat conserva el tipo de ambos fallos.
            // No se escriben títulos de tareas, nombres personales ni otros datos de la cuenta.
            Log.e(
                "AgendaVIP",
                "Falló la recuperación (${error.javaClass.simpleName}) y su registro (${errorRegistro.javaClass.simpleName}).",
            )
        }
    }
}