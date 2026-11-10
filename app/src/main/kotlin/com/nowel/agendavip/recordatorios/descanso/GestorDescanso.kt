package com.nowel.agendavip.recordatorios.descanso

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import java.time.ZonedDateTime

/**
 * Pide a Android los avisos propios del descanso: preparación, comienzo, final y fin de pausa.
 * Comparte AlarmManager con las tareas, pero usa direcciones distintas para no sustituirlas.
 * Configuración, el receptor de reinicio y los cambios de cuenta llaman a este objeto.
 */
object GestorDescanso {

    const val PREAVISO = "descanso_preaviso"
    const val INICIO = "descanso_inicio"
    const val FIN = "descanso_fin"
    const val FIN_PAUSA = "descanso_fin_pausa"
    const val SILENCIAR = "descanso_silenciar"
    const val EXTRA_FECHA = "descanso_fecha"
    const val EXTRA_FIRMA = "descanso_firma"

    private const val ARCHIVO_SISTEMA = "descanso_alarmas_sistema"
    private const val CLAVE_DIRECCIONES = "direcciones"

    /**
     * Recibe Android y vuelve a programar solamente los avisos de Descanso de la cuenta activa.
     * Nunca reproduce música ni abre el micrófono. Si se retiró el permiso de hora exacta,
     * deja un aviso aproximado y un mensaje para Configuración, sin cerrar la aplicación.
     */
    fun reprogramar(contexto: Context) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            cancelarProgramadas(contexto)
            val propietario = EspacioPersonal.claveActual(contexto) ?: return@synchronized
            try {
                val repositorio = RepositorioDescanso(contexto)
                val configuracion = repositorio.configuracion()
                if (!configuracion.activado) {
                    NotificacionesDescanso.cancelar(contexto, propietario)
                    return@synchronized
                }
                val ahora = ZonedDateTime.now()
                val ventana = ReglasDescanso.proximaVentana(configuracion, ahora)
                val estado = repositorio.estado(ventana.fechaNoche)
                val firma = configuracion.hashCode()
                val instante = ahora.toInstant().toEpochMilli()
                val principio = ventana.inicio.toInstant().toEpochMilli()
                val termino = ventana.fin.toInstant().toEpochMilli()

                if (principio > instante) {
                    if (!estado.avisoPrevio && !estado.cancelada) {
                        programar(
                            contexto, propietario, PREAVISO, ventana.fechaNoche.toString(),
                            maxOf(principio - 600_000L, instante + 1_000L), firma,
                        )
                    }
                    if (!estado.cancelada) {
                        programar(contexto, propietario, INICIO, ventana.fechaNoche.toString(), principio, firma)
                    }
                }
                // Recuperar una noche ya empezada no vuelve a reproducir su aviso inicial.
                // La capa consulta el horario y puede mostrar el estado vigente sin despertar a nadie.
                if (estado.pausaHasta > instante && estado.pausaHasta < termino && !estado.cancelada) {
                    programar(contexto, propietario, FIN_PAUSA, ventana.fechaNoche.toString(), estado.pausaHasta, firma)
                }
                programar(contexto, propietario, FIN, ventana.fechaNoche.toString(), termino, firma)
                guardarProblema(contexto, null)
                if (!permiteHoraExacta(contexto)) {
                    guardarProblema(contexto, "Permite Alarmas y recordatorios para que Descanso empiece a la hora elegida.")
                }
            } catch (_: RuntimeException) {
                guardarProblema(contexto, "No se pudieron preparar todos los avisos de descanso. Abre Descanso para revisar su configuración.")
            }
        }
    }

    /**
     * La pantalla la llama al guardar. Recibe Android, adapta las alarmas y comunica los cambios
     * al servicio de accesibilidad, si la persona lo había habilitado. No concede ningún permiso.
     */
    fun actualizar(contexto: Context) {
        reprogramar(contexto)
        ServicioAccesibilidadDescanso.refrescar()
    }

    /**
     * Cerrar sesión la llama antes de cambiar de persona. Retira alarmas, capa y voz inmediatamente.
     * No borra los horarios ni el historial: volverán a recuperarse al entrar en su cuenta.
     */
    fun detenerPorCambioSesion(contexto: Context) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            cancelarProgramadas(contexto)
            EspacioPersonal.claveActual(contexto)?.let {
                NotificacionesDescanso.cancelar(contexto, it)
            }
            ServicioAccesibilidadDescanso.retirarPorCambioSesion()
            ServicioVozDescanso.detener(contexto)
        }
    }

    /** Devuelve si Android permite hora exacta; Configuración utiliza el resultado para explicar su estado. */
    fun permiteHoraExacta(contexto: Context): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            contexto.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Devuelve el último problema de programación sin mostrar datos privados de otra persona. */
    fun ultimoProblema(contexto: Context): String? {
        return contexto.getSharedPreferences(ARCHIVO_SISTEMA, Context.MODE_PRIVATE)
            .getString("problema", null)
    }

    /**
     * Recibe tipo, cuenta, fecha y momento del aviso. Su dirección propia impide confundirlo
     * con una tarea o con otra noche. Se recuerda la dirección para poder cancelarla al editar.
     */
    private fun programar(
        contexto: Context,
        propietario: String,
        accion: String,
        fecha: String,
        momento: Long,
        firma: Int,
    ) {
        val direccion = "agendavip://descanso/$propietario/$accion/$fecha"
        val intento = Intent(contexto, ReceptorDescanso::class.java)
            .setAction(accion)
            .setData(Uri.parse(direccion))
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
            .putExtra(EXTRA_FECHA, fecha)
            .putExtra(EXTRA_FIRMA, firma)
        val pendiente = PendingIntent.getBroadcast(
            contexto, 0, intento,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val preferencias = contexto.getSharedPreferences(ARCHIVO_SISTEMA, Context.MODE_PRIVATE)
        val direcciones = preferencias.getStringSet(CLAVE_DIRECCIONES, emptySet()).orEmpty().toMutableSet()
        direcciones.add(direccion)
        check(preferencias.edit().putStringSet(CLAVE_DIRECCIONES, direcciones).commit())
        val alarmas = contexto.getSystemService(AlarmManager::class.java)
        try {
            if (permiteHoraExacta(contexto)) {
                alarmas.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pendiente)
            } else {
                alarmas.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pendiente)
            }
        } catch (_: SecurityException) {
            // El acceso pudo cambiar justo después de comprobarlo.
            alarmas.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pendiente)
        }
    }

    /** Busca solamente nuestras direcciones guardadas y cancela sus avisos; otras alarmas siguen intactas. */
    private fun cancelarProgramadas(contexto: Context) {
        val preferencias = contexto.getSharedPreferences(ARCHIVO_SISTEMA, Context.MODE_PRIVATE)
        val alarmas = contexto.getSystemService(AlarmManager::class.java)
        preferencias.getStringSet(CLAVE_DIRECCIONES, emptySet()).orEmpty().forEach { direccion ->
            val uri = Uri.parse(direccion)
            if (uri.scheme == "agendavip" && uri.host == "descanso" && uri.pathSegments.size == 3) {
                val pendiente = PendingIntent.getBroadcast(
                    contexto, 0,
                    Intent(contexto, ReceptorDescanso::class.java)
                        .setAction(uri.pathSegments[1])
                        .setData(uri),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (pendiente != null) {
                    alarmas.cancel(pendiente)
                    pendiente.cancel()
                }
            }
        }
        preferencias.edit().remove(CLAVE_DIRECCIONES).commit()
    }

    /** Guarda una explicación del fallo para la pantalla; no guarda mensajes de voz personales. */
    private fun guardarProblema(contexto: Context, problema: String?) {
        contexto.getSharedPreferences(ARCHIVO_SISTEMA, Context.MODE_PRIVATE)
            .edit()
            .putString("problema", problema)
            .apply()
    }
}
