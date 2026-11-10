package com.nowel.agendavip.recordatorios.descanso

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * Recibe las horas pedidas a Android, aunque ninguna pantalla de Agenda.VIP esté abierta.
 * Valida cuenta, configuración y noche antes de avisar. Los mensajes atrasados se descartan.
 */
class ReceptorDescanso : BroadcastReceiver() {

    /**
     * Android entrega el mensaje. Se pide tiempo para comprobarlo en un trabajador separado,
     * de modo que leer las preferencias no congele la pantalla. El resultado es una notificación.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendiente = goAsync()
        trabajador.execute {
            try {
                atender(context.applicationContext, intent)
            } catch (_: RuntimeException) {
                // Datos dañados o una cuenta cerrada no se sustituyen por opciones inventadas.
            } finally {
                pendiente.finish()
            }
        }
    }

    /**
     * Recibe cuenta y acción del aviso; confirma que pertenecen al horario actual antes de actuar.
     * Silenciar afecta únicamente a la lectura de esa noche y conserva todas sus restricciones.
     */
    private fun atender(contexto: Context, intento: Intent) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            val propietario = intento.getStringExtra(EspacioPersonal.EXTRA_ESPACIO) ?: return
            if (EspacioPersonal.claveActual(contexto) != propietario) {
                return
            }
            val fecha = runCatching {
                LocalDate.parse(intento.getStringExtra(GestorDescanso.EXTRA_FECHA))
            }.getOrNull() ?: return
            val repositorio = RepositorioDescanso(contexto)
            val configuracion = repositorio.configuracion()
            if (!configuracion.activado) {
                return
            }
            val ahora = ZonedDateTime.now()
            val ventana = ReglasDescanso.ventanaActual(configuracion, ahora)
            val siguiente = ReglasDescanso.proximaVentana(configuracion, ahora)
            val estado = repositorio.estado(fecha)
            if (intento.action == GestorDescanso.SILENCIAR) {
                if (ventana?.fechaNoche == fecha || siguiente.fechaNoche == fecha) {
                    ServicioVozDescanso.detener(contexto, propietario, fecha)
                }
                return
            }
            if (intento.getIntExtra(GestorDescanso.EXTRA_FIRMA, Int.MIN_VALUE) != configuracion.hashCode()) {
                return
            }
            if (intento.action == GestorDescanso.FIN) {
                // La capa también comprueba la hora por sí misma si Android retrasa este receptor.
                ServicioAccesibilidadDescanso.refrescar()
                if (ventana == null) {
                    ServicioVozDescanso.detener(contexto, propietario, fecha)
                    NotificacionesDescanso.cancelar(contexto, propietario)
                }
                GestorDescanso.reprogramar(contexto)
                return
            }
            val previo = intento.action == GestorDescanso.PREAVISO
            val valido = if (previo) {
                siguiente.fechaNoche == fecha && ahora.isBefore(siguiente.inicio) &&
                    !ahora.isBefore(siguiente.inicio.minusMinutes(10))
            } else {
                intento.action in setOf(GestorDescanso.INICIO, GestorDescanso.FIN_PAUSA) &&
                    ventana?.fechaNoche == fecha
            }
            if (!valido || estado.cancelada || estado.pausaHasta > System.currentTimeMillis()) {
                GestorDescanso.reprogramar(contexto)
                return
            }
            val primero = if (intento.action == GestorDescanso.FIN_PAUSA) {
                true
            } else {
                repositorio.marcarAviso(fecha, previo)
            }
            if (primero) {
                val nombre = PerfilLocal(contexto).nombres.trim().substringBefore(' ')
                val mensaje = if (previo) {
                    "${if (nombre.isBlank()) "" else "$nombre, "}tu horario de descanso empieza en menos de diez minutos. Ve terminando lo que estás haciendo."
                } else {
                    ReglasDescanso.mensajeDescanso(configuracion, ahora, nombre)
                }
                NotificacionesDescanso.publicar(contexto, propietario, fecha, mensaje, previo)
                ServicioVozDescanso.reproducir(
                    contexto,
                    propietario,
                    fecha,
                    previo,
                    inicioProgramado = intento.action == GestorDescanso.INICIO,
                )
            }
            ServicioAccesibilidadDescanso.refrescar()
            GestorDescanso.reprogramar(contexto)
        }
    }

    companion object {
        private val trabajador = Executors.newSingleThreadExecutor()
    }
}
