package com.nowel.agendavip.recordatorios

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.agenda.EstadoOcurrencia
import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.Tarea
import java.time.LocalDate
import java.time.ZoneId

/**
 * Organiza el guardado, la hora de los avisos, sus notificaciones y su sonido.
 * Recibe [context] para acceder a Android y a los datos guardados en el teléfono.
 * AlarmManager es el servicio de Android al que se le pide que avise a una hora determinada.
 * Las pantallas y los receptores usan este gestor para cambiar tareas y programar sus avisos.
 *
 * Cada fecha concreta de una rutina se llama ocurrencia y tiene su propio estado.
 * El máximo de avisos incluye el primero; posponer manualmente permite pedir otro aviso.
 */
class GestorRecordatorios(context: Context) {

    private val contexto = context.applicationContext
    private val espacio = EspacioPersonal(contexto)
    private val repositorio = RepositorioTareas(contexto)
    private val alarmas = contexto.getSystemService(AlarmManager::class.java)

    /**
     * Recibe una [tarea] del formulario, la guarda y adapta sus avisos a los datos nuevos.
     * El formulario de creación o edición la llama al pulsar Guardar; no devuelve un resultado.
     * Mantiene las fechas ya completadas. Si cambió la hora de un aviso futuro, permite
     * comenzar sus avisos de nuevo; también recupera posposiciones y recordatorios pendientes.
     * synchronized hace que los cambios de este gestor esperen su turno y no se mezclen.
     */
    fun guardar(tarea: Tarea) = synchronized(cerrojo) {
        val anterior = repositorio.obtener(tarea.id)
        repositorio.guardar(tarea)
        val ahora = System.currentTimeMillis()
        val hoy = LocalDate.now()

        val inicioDeHoy = hoy.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val cambioHorario = anterior != null &&
            (anterior.hora != tarea.hora || anterior.fecha != tarea.fecha ||
                anterior.repeticion != tarea.repeticion || anterior.diasSemana != tarea.diasSemana)

        repositorio.estadosDe(tarea.id).forEach { estado ->
            // Una fecha antigua sin aviso pendiente es historial. Editar la rutina
            // no debe despertar seguimientos de fechas que ya quedaron atrás.
            if (estado.fecha.isBefore(hoy) && estado.alarmaProgramadaPara == null &&
                estado.pospuestaHasta == null && (estado.ultimaAlerta ?: 0L) < inicioDeHoy
            ) {
                return@forEach
            }

            cancelarOcurrencia(estado.tareaId, estado.fecha)
            var actualizado = estado.copy(alarmaProgramadaPara = null, alarmaEsSeguimiento = false)

            if (!estado.completada && !estado.omitida && tarea.correspondeA(estado.fecha)) {
                if (cambioHorario && !estado.fecha.isBefore(hoy) && instanteLocal(tarea, estado.fecha) > ahora) {
                    actualizado = actualizado.copy(
                        // El contador empieza de nuevo, pero la hora anterior queda como registro.
                        avisosEmitidos = 0,
                        pospuestaHasta = null,
                        seguimientoDetenido = false,
                        alarmaEsSeguimiento = false
                    )
                    repositorio.guardarEstado(actualizado, registrarEnHistorial = false)
                } else if (!estado.seguimientoDetenido && estado.pospuestaHasta != null) {
                    val pospuesta = estado.pospuestaHasta
                    programar(
                        tarea,
                        actualizado,
                        pospuesta,
                        estado.avisosEmitidos > 0,
                        dispararEn = if (pospuesta > ahora) pospuesta else ahora + 2_000L,
                    )
                } else {
                    val siguiente = momentoDeSeguimiento(tarea, estado)
                    val pendiente = actualizado.copy(pospuestaHasta = null)
                    if (siguiente != null) {
                        programar(
                            tarea,
                            pendiente,
                            siguiente,
                            true,
                            dispararEn = if (siguiente > ahora) siguiente else ahora + 2_000L,
                        )
                    } else if (pendiente != estado) {
                        repositorio.guardarEstado(pendiente, registrarEnHistorial = false)
                    }
                }
            } else {
                repositorio.guardarEstado(actualizado.copy(pospuestaHasta = null), registrarEnHistorial = false)
            }
        }

        programarProxima(tarea, hoy, ahora)
    }

    /**
     * Recibe el código [id], la [fecha] y [completada], que indica si se marca o desmarca ese día.
     * El calendario, los detalles y el botón Completar de la notificación la llaman.
     * Guarda el cambio y cancela el sonido y los avisos de esa fecha, sin completar otros días.
     * Al pasar de completada a pendiente, prepara otra vez su hora si todavía no ha llegado.
     * Conserva la hora del último aviso recibido para consultarla después.
     * No devuelve un resultado; si la hora ya pasó, la persona puede editarla o posponerla.
     */
    fun marcarCompletada(id: String, fecha: LocalDate, completada: Boolean) = synchronized(cerrojo) {
        val tarea = repositorio.obtener(id) ?: return@synchronized
        require(tarea.correspondeA(fecha)) {
            "La tarea no corresponde a esta fecha."
        }
        val estado = repositorio.estado(id, fecha)
        if (estado.completada == completada) {
            return@synchronized
        }

        val ahora = System.currentTimeMillis()
        val momento = instanteLocal(tarea, fecha)
        val vuelveAPendiente = estado.completada && !completada && !estado.omitida
        val necesitaNuevoAviso = vuelveAPendiente && momento > ahora

        cancelarOcurrencia(id, fecha)

        val actualizado = estado.copy(
            completada = completada,
            seguimientoDetenido = completada,
            pospuestaHasta = null,
            alarmaProgramadaPara = null,
            alarmaEsSeguimiento = false,
            avisosEmitidos = if (necesitaNuevoAviso) 0 else estado.avisosEmitidos
        )
        repositorio.guardarEstado(actualizado)

        if (necesitaNuevoAviso) {
            // Pedimos esta fecha directamente: una rutina puede tener mañana ya preparado.
            // El aviso de mañana no debe impedir que vuelva a programarse el de hoy.
            programar(tarea, actualizado, momento, false)
        }

        programarProxima(tarea, LocalDate.now(), ahora)
    }

    /**
     * Recibe el código [id], la [fecha] y los [minutos] de espera, diez si no se indica otro número.
     * Los detalles y el botón Posponer de la notificación la llaman para pedir un aviso más tarde.
     * Detiene el aviso actual, guarda la nueva hora y la solicita a Android, aunque se hayan
     * agotado los avisos automáticos. No devuelve un resultado; rechaza fechas completadas u omitidas.
     */
    fun posponer(id: String, fecha: LocalDate, minutos: Int = 10) = synchronized(cerrojo) {
        require(minutos in 1..1440) {
            "La posposición debe estar entre 1 y 1440 minutos."
        }
        val tarea = repositorio.obtener(id) ?: return@synchronized
        require(tarea.correspondeA(fecha)) {
            "La tarea no corresponde a esta fecha."
        }
        val estado = repositorio.estado(id, fecha)
        require(!estado.completada && !estado.omitida) {
            "Esta ocurrencia está completada o eliminada."
        }
        cancelarOcurrencia(id, fecha)
        val momento = System.currentTimeMillis() + minutos * 60_000L
        val esSeguimiento = estado.avisosEmitidos > 0
        val actualizado = estado.copy(
            seguimientoDetenido = false,
            pospuestaHasta = momento,
            alarmaProgramadaPara = momento,
            alarmaEsSeguimiento = esSeguimiento,
        )
        // El historial y la nueva hora se guardan juntos antes de solicitar la alarma.
        repositorio.guardarEstado(actualizado)
        programar(tarea, actualizado, momento, esSeguimiento)
        programarProxima(tarea, LocalDate.now(), System.currentTimeMillis())
    }

    /**
     * Recibe el código [id] y la [fecha] para detener el sonido y los avisos posteriores de ese día.
     * Los detalles y el botón Detener de la notificación la llaman cuando la persona lo pide.
     * Guarda esa decisión y prepara el siguiente día de la rutina; no devuelve un resultado.
     */
    fun detenerSeguimiento(id: String, fecha: LocalDate) = synchronized(cerrojo) {
        val tarea = repositorio.obtener(id) ?: return@synchronized
        val estado = repositorio.estado(id, fecha)
        cancelarOcurrencia(id, fecha)
        repositorio.guardarEstado(
            estado.copy(
                seguimientoDetenido = true,
                pospuestaHasta = null,
                alarmaProgramadaPara = null
            )
        )
        programarProxima(tarea, LocalDate.now(), System.currentTimeMillis())
    }

    /**
     * Recibe el código [id], la [fecha] elegida y [serie], que indica si se borra toda la rutina.
     * La pantalla de detalles la llama al confirmar Eliminar.
     * Si [serie] es false, omite solo ese día; si es true, borra la tarea y todos sus estados.
     * Cancela los avisos y el audio afectados y no devuelve un resultado.
     */
    fun eliminar(id: String, fecha: LocalDate, serie: Boolean) = synchronized(cerrojo) {
        val tarea = repositorio.obtener(id) ?: return@synchronized

        if (serie) {
            repositorio.estadosDe(id).forEach {
                cancelarOcurrencia(id, it.fecha, conservarRegistro = false)
            }
            cancelarOcurrencia(id, fecha, conservarRegistro = false)
            repositorio.eliminar(id)
        } else {
            require(tarea.correspondeA(fecha)) {
                "La tarea no corresponde a esta fecha."
            }
            val estado = repositorio.estado(id, fecha)
            cancelarOcurrencia(id, fecha, conservarRegistro = false)
            repositorio.guardarEstado(
                estado.copy(
                    omitida = true,
                    seguimientoDetenido = true,
                    pospuestaHasta = null,
                    alarmaProgramadaPara = null
                )
            )
            programarProxima(tarea, LocalDate.now(), System.currentTimeMillis())
        }
    }

    /**
     * No recibe datos: lee las tareas guardadas y vuelve a solicitar sus avisos pendientes a Android.
     * La agenda y el receptor de reinicio la llaman al abrir la app o tras cambios del sistema.
     * Los primeros avisos mantienen la hora local de la tarea; los pospuestos y los posteriores
     * mantienen el momento guardado. No devuelve datos ni comienza el sonido durante el arranque.
     */
    fun reprogramarTodo() = synchronized(cerrojo) {
        val ahora = System.currentTimeMillis()
        val hoy = LocalDate.now()

        val lectura = repositorio.leerParaAgenda()
        var fallo: RuntimeException? = null

        for (tarea in lectura.tareas) {
            if (Thread.currentThread().isInterrupted) {
                return@synchronized
            }

            try {
                val estados = lectura.estadosPorTarea[tarea.id].orEmpty().map { estado ->
                    try {
                        recuperarProgramacion(tarea, estado, hoy, ahora)
                    } catch (error: RuntimeException) {
                        // Otra fecha de esta misma rutina aún puede reinstalarse.
                        fallo = error
                        estado
                    }
                }
                programarProxima(tarea, hoy, ahora, estados)
            } catch (error: RuntimeException) {
                // La tarea que falló queda guardada. Las demás aún pueden recuperar su hora.
                fallo = error
                android.util.Log.w("AgendaVIP", "No se recuperó un recordatorio: ${error.javaClass.simpleName}")
            }
        }

        if (fallo != null) {
            throw IllegalStateException("Algunos avisos no se pudieron recuperar.", fallo)
        }
    }

    /**
     * Recibe la tarea, el estado guardado y la fecha y hora de la recuperación. Devuelve
     * el estado vigente para que reprogramarTodo pueda buscar el siguiente aviso sin releerlo.
     * Reinstala las alarmas de Android y retira solo las que ya no corresponden a la tarea.
     */
    private fun recuperarProgramacion(
        tarea: Tarea,
        estado: EstadoOcurrencia,
        hoy: LocalDate,
        ahora: Long,
    ): EstadoOcurrencia {
        val programada = estado.alarmaProgramadaPara
        val pospuesta = estado.pospuestaHasta
        val automatico = estado.alarmaEsSeguimiento && pospuesta == null
        val seguimiento = if (automatico) momentoDeSeguimiento(tarea, estado) else null
        val cancelada = estado.completada || estado.omitida || estado.seguimientoDetenido ||
            !tarea.correspondeA(estado.fecha) || (automatico && seguimiento == null)
        val primerAvisoAnterior = !estado.alarmaEsSeguimiento && pospuesta == null &&
            estado.fecha.isBefore(hoy)

        if (cancelada || primerAvisoAnterior) {
            if (programada == null && pospuesta == null) {
                return estado
            }

            cancelarAlarma(tarea.id, estado.fecha)
            val actualizado = estado.copy(
                alarmaProgramadaPara = null,
                alarmaEsSeguimiento = false,
                pospuestaHasta = null,
            )
            repositorio.guardarEstado(actualizado, registrarEnHistorial = false)
            return actualizado
        }

        val instante = when {
            pospuesta != null -> pospuesta
            automatico -> {
                val siguiente = seguimiento ?: return estado
                if (programada == null) {
                    // Solo reconstruye seguimientos antiguos sin hora si les toca hoy o después.
                    val inicioDeHoy = hoy.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (siguiente < inicioDeHoy) {
                        return estado
                    }
                }
                siguiente
            }
            programada != null -> instanteLocal(tarea, estado.fecha)
            else -> return estado
        }

        // Si ya pasó, se pide un disparo pronto sin invalidar el mensaje que Android
        // podría estar entregando por la hora original.
        return programar(
            tarea,
            estado,
            instante,
            seguimiento = automatico || (pospuesta != null && estado.avisosEmitidos > 0),
            dispararEn = if (instante > ahora) instante else ahora + 2_000L,
        )
    }

    /**
     * El coordinador la llama antes de cerrar o cambiar de cuenta, sin argumentos.
     * Retira alarmas, notificaciones y audio del dueño actual. Conserva tareas y decisiones
     * para que puedan recuperarse al volver a entrar. No devuelve datos ni completa tareas.
     */
    fun cancelarTodo() = synchronized(cerrojo) {
        espacio.exigirVigente()

        repositorio.listar().forEach { tarea ->
            repositorio.estadosDe(tarea.id).forEach { estado ->
                cancelarAlarma(tarea.id, estado.fecha)
            }
        }

        ServicioAviso.detenerTodo(contexto, espacio.clave)
        NotificacionesAviso.cancelarTodo(contexto, espacio.clave)
    }

    /**
     * No recibe datos y devuelve true si esta versión de Android permite pedir una hora exacta.
     * La pantalla de cuenta la consulta para explicar el permiso y [programar] para elegir la alarma.
     * Si devuelve false, Android todavía puede aceptar un aviso aproximado y entregarlo más tarde.
     */
    fun tieneAlarmasExactas(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmas.canScheduleExactAlarms()

    /**
     * No recibe datos y devuelve el último mensaje de problema guardado, o null si no hay ninguno.
     * La pantalla de cuenta la llama para mostrar fallos que pudieron ocurrir sin una pantalla abierta,
     * como no poder programar una alarma o comenzar el sonido.
     */
    fun ultimoProblema(): String? =
        espacio.preferencias("estado_recordatorios")
            .getString("ultimo_error", null)

    /**
     * Devuelve la última hora que se solicitó a Android, o null si todavía no quedó registrada.
     * Permisos la muestra para distinguir una alarma programada de un aviso ya recibido.
     */
    fun ultimaHoraProgramada(): Long? = espacio.preferencias("estado_recordatorios")
        .getLong("ultima_programacion_ms", 0L).takeIf { it > 0L }

    /**
     * ReceptorReinicio la llama al recibir el encendido y al completar la recuperación.
     * Guarda el momento en la cuenta activa para distinguir una entrega de Android de abrir la app.
     */
    internal fun registrarArranque(recuperado: Boolean) {
        val ahora = System.currentTimeMillis()
        val editor = espacio.preferencias("estado_recordatorios").edit()
        if (recuperado) {
            editor.putLong("arranque_recuperado_ms", ahora)
        } else {
            editor.putLong("arranque_recibido_ms", ahora)
            editor.remove("arranque_recuperado_ms")
        }
        editor.apply()
    }

    /** Permisos consulta cuándo se recibió el encendido o cuándo se reprogramaron sus avisos. */
    internal fun ultimoArranque(recuperado: Boolean): Long? {
        val clave = if (recuperado) "arranque_recuperado_ms" else "arranque_recibido_ms"
        return espacio.preferencias("estado_recordatorios")
            .getLong(clave, 0L)
            .takeIf { it > 0L }
    }

    /**
     * Devuelve cuándo Android entregó el último aviso válido a su receptor, o null si aún no consta.
     * Esto indica entrega del sistema; no afirma que la persona haya escuchado el sonido.
     */
    fun ultimaRecepcion(): Long? = espacio.preferencias("estado_recordatorios")
        .getLong("ultima_recepcion_ms", 0L).takeIf { it > 0L }

    /**
     * Recibe el código [id], la [fecha] y el [momento] que tenía programado el aviso.
     * El receptor de recordatorios la llama cuando Android entrega la alarma.
     * Compara ese momento con el guardado para ignorar avisos antiguos, registra el aviso emitido
     * y muestra la notificación; también solicita sonido y el próximo aviso si corresponden.
     * No devuelve datos. Prepara el siguiente día aparte para que detener hoy no cancele mañana.
     */
    internal fun recibirAlarma(id: String, fecha: LocalDate, momento: Long) = synchronized(cerrojo) {
        val (tarea, estado) = repositorio.leerDetalle(id, fecha) ?: return@synchronized

        if (estado.alarmaProgramadaPara != momento || momento <= 0L) {
            return@synchronized
        }

        if (estado.completada || estado.omitida || estado.seguimientoDetenido || !tarea.correspondeA(fecha)) {
            return@synchronized
        }
        val pospuesta = estado.pospuestaHasta
        val esSeguimiento = if (pospuesta != null) estado.avisosEmitidos > 0 else estado.alarmaEsSeguimiento
        val momentoVigente = when {
            pospuesta != null -> pospuesta
            esSeguimiento -> momentoDeSeguimiento(tarea, estado)
            else -> momento
        }
        if (momentoVigente == null) {
            // Si cambió el límite o se desactivó el seguimiento, descarta una entrega antigua.
            repositorio.guardarEstado(
                estado.copy(alarmaProgramadaPara = null, alarmaEsSeguimiento = false),
                registrarEnHistorial = false,
            )
            return@synchronized
        }

        val ahora = System.currentTimeMillis()

        // Una edición interrumpida pudo dejar la petición con el intervalo anterior.
        if (momentoVigente > ahora + 1_000L) {
            programar(tarea, estado, momentoVigente, esSeguimiento)
            return@synchronized
        }

        espacio.preferencias("estado_recordatorios").edit()
            .putLong("ultima_recepcion_ms", ahora)
            .apply()

        val recibido = estado.copy(
            avisosEmitidos = estado.avisosEmitidos + 1,
            ultimaAlerta = ahora,
            pospuestaHasta = null,
        )
        val siguiente = momentoDeSeguimiento(tarea, recibido)
        val emitido = recibido.copy(
            alarmaProgramadaPara = siguiente,
            alarmaEsSeguimiento = siguiente != null,
        )

        // El contador y la siguiente hora quedan juntos en disco. Si se interrumpe
        // la entrega, el reinicio puede reinstalar el aviso sin contar este dos veces.
        repositorio.guardarEstado(emitido)
        var requiereToque = false

        // Pedir el sonido tiene prioridad: Android concede un margen breve al entregar
        // una alarma exacta. Publicar una notificación es una operación independiente.
        if (tarea.vozActiva || tarea.tieneMusica()) {
            try {
                ServicioAviso.iniciar(contexto, id, fecha, esSeguimiento, espacio.clave)
            } catch (error: RuntimeException) {
                requiereToque = true
                registrarProblema(
                    contexto,
                    "Android rechazó el sonido automático. Revisa Alarmas y recordatorios y la batería de Agenda.VIP. ${error.javaClass.simpleName}",
                    espacio.clave,
                    notificar = false,
                )
            }
        }

        // Esta fecha y la siguiente de la rutina se programan por separado.
        // El sonido ya se solicitó, y un fallo en el seguimiento no debe ocultar el aviso.
        if (siguiente != null) {
            try {
                programar(tarea, emitido, siguiente, true)
            } catch (error: RuntimeException) {
                registrarProblema(
                    contexto,
                    "No se pudo programar el seguimiento pendiente. Revisa los permisos de recordatorios.",
                    espacio.clave,
                    notificar = false,
                )
            }
        }

        NotificacionesAviso.mostrar(
            contexto,
            tarea,
            fecha,
            esSeguimiento,
            requiereToque = requiereToque,
            propietario = espacio.clave,
            momentoRecepcion = ahora,
        )

        try {
            programarProxima(tarea, fecha.plusDays(1), ahora)
        } catch (error: RuntimeException) {
            registrarProblema(
                contexto,
                "No se pudo preparar el aviso de la siguiente fecha. Revisa los permisos de recordatorios.",
                espacio.clave,
                notificar = false,
            )
        }
    }

    /**
     * Recibe una tarea y el estado de un día. Devuelve la hora del próximo seguimiento
     * automático, o null si esa fecha ya terminó o agotó su límite. Guardar, recuperar
     * y recibirAlarma usan la misma regla. Posponer manualmente sigue siendo otra acción.
     */
    private fun momentoDeSeguimiento(tarea: Tarea, estado: EstadoOcurrencia): Long? {
        val ultima = estado.ultimaAlerta ?: return null
        if (!tarea.seguimientoActivo || estado.avisosEmitidos !in 1 until tarea.limiteAvisos ||
            estado.completada || estado.omitida || estado.seguimientoDetenido
        ) {
            return null
        }

        return ultima + tarea.intervaloMinutos * 60_000L
    }

    /**
     * Recibe una [tarea], la fecha [desde] la que debe buscar y la hora actual en [ahora].
     * Los cambios de tareas y la recepción de alarmas la llaman para preparar el próximo primer aviso.
     * Busca una fecha permitida, futura y pendiente; si ya hay un primer aviso preparado, lo conserva.
     * Al recuperar un lote, recibe los estados ya leídos para evitar abrir todo el archivo otra vez.
     * No devuelve datos: entrega a [programar] solo la próxima fecha que necesita aviso.
     */
    private fun programarProxima(
        tarea: Tarea,
        desde: LocalDate,
        ahora: Long,
        estados: List<EstadoOcurrencia> = repositorio.estadosDe(tarea.id),
    ) {
        if (estados.any {
            !it.fecha.isBefore(desde) && it.alarmaProgramadaPara != null && !it.alarmaEsSeguimiento &&
                it.pospuestaHasta == null && !it.completada && !it.omitida && !it.seguimientoDetenido
        }) {
            return
        }

        val estadosPorFecha = estados.associateBy { it.fecha }
        var fecha = maxOf(desde, tarea.fecha)
        val intentos = if (tarea.repeticion == Repeticion.UNA_VEZ) {
            1
        } else {
            (estados.size + 1) * 7 + 1
        }

        repeat(intentos) {
            if (tarea.repeticion == Repeticion.UNA_VEZ && fecha != tarea.fecha) {
                return
            }

            if (tarea.correspondeA(fecha)) {
                val estado = estadosPorFecha[fecha] ?: EstadoOcurrencia(tarea.id, fecha)
                val momento = instanteLocal(tarea, fecha)

                if (momento > ahora && !estado.completada && !estado.omitida && !estado.seguimientoDetenido &&
                    estado.alarmaProgramadaPara == null && estado.avisosEmitidos == 0
                ) {
                    programar(tarea, estado, momento, false)
                    return
                }
            }

            fecha = fecha.plusDays(1)
        }
    }

    /**
     * Recibe una [tarea] y una [fecha]; combina ese día con la hora de la tarea y la zona del teléfono.
     * Devuelve el momento en milisegundos desde el 1 de enero de 1970, el número que usa Android
     * para programar alarmas. [guardar], [reprogramarTodo] y [programarProxima] la llaman al calcular
     * un primer aviso; la biblioteca de fechas resuelve los cambios de horario de esa zona.
     */
    private fun instanteLocal(tarea: Tarea, fecha: LocalDate): Long =
        fecha.atTime(tarea.hora)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /**
     * Recibe la [tarea], su [estado], el [momento] del aviso y si es de [seguimiento].
     * Las funciones que guardan, posponen o recuperan tareas la llaman para solicitar la alarma.
     * Guarda la hora si cambió y devuelve el estado que identifica el aviso vigente.
     * dispararEn permite reintentar una hora vencida sin alterar ese identificador.
     * Pide una alarma exacta si está permitida, o aproximada si falta el acceso.
     * Si Android rechaza la programación, guarda la explicación y lanza un error.
     */
    private fun programar(
        tarea: Tarea,
        estado: EstadoOcurrencia,
        momento: Long,
        seguimiento: Boolean,
        dispararEn: Long = momento,
    ): EstadoOcurrencia {
        val actualizado = estado.copy(
            alarmaProgramadaPara = momento,
            alarmaEsSeguimiento = seguimiento
        )
        // Solicitar una alarma futura no significa que el aviso ya haya ocurrido.
        if (actualizado != estado) {
            repositorio.guardarEstado(actualizado, registrarEnHistorial = false)
        }
        val pendiente = pendiente(tarea.id, estado.fecha, momento)

        try {
            if (tieneAlarmasExactas()) {
                try {
                    alarmas.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dispararEn, pendiente)
                } catch (_: SecurityException) {
                    alarmas.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dispararEn, pendiente)
                    registrarProblema(
                        contexto,
                        "El acceso a alarmas exactas cambió. Android puede retrasar los avisos hasta que lo autorices."
                    )
                }
            } else {
                alarmas.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dispararEn, pendiente)
            }
            espacio.preferencias("estado_recordatorios").edit()
                .putLong("ultima_programacion_ms", dispararEn)
                .apply()
        } catch (error: RuntimeException) {
            registrarProblema(
                contexto,
                "No se pudo programar un aviso. Revisa los permisos y vuelve a abrir Agenda.VIP. ${error.javaClass.simpleName}"
            )
            throw IllegalStateException(
                "La tarea se guardó, pero Android no pudo programar su aviso. Revisa los permisos.",
                error
            )
        }

        return actualizado
    }

    /**
     * Recibe el código [id], la [fecha] y el [momento] que tendrá la alarma.
     * [programar] la llama y recibe un PendingIntent: una petición que Android guarda para ejecutar después.
     * Esa petición envía al receptor un Intent, el mensaje que contiene los datos del aviso.
     * La dirección del mensaje distingue tarea y día para poder actualizar o cancelar esa alarma concreta.
     */
    private fun pendiente(id: String, fecha: LocalDate, momento: Long): PendingIntent {
        val intento = Intent(contexto, ReceptorRecordatorios::class.java)
            .setAction(ReceptorRecordatorios.ACCION_ALARMA)
            .setData(
                Uri.Builder()
                    .scheme("agendavip")
                    .authority("alarma")
                    .appendPath(espacio.clave)
                    .appendPath(id)
                    .appendPath(fecha.toString())
                    .build()
            )
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, espacio.clave)
            .putExtra("tarea_id", id)
            .putExtra("fecha_ocurrencia", fecha.toString())
            .putExtra("momento_programado", momento)
        return PendingIntent.getBroadcast(
            contexto,
            0,
            intento,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Recibe el código [id] y la [fecha] de una tarea para retirar su alarma de Android.
     * [cancelarOcurrencia] y [reprogramarTodo] la llaman antes de cancelar o sustituir un aviso.
     * Busca la petición ya existente, sin crear otra, y la cancela si la encuentra.
     * No devuelve datos ni retira por sí sola la notificación o el sonido.
     */
    private fun cancelarAlarma(id: String, fecha: LocalDate) {
        val direcciones = mutableListOf(
            Uri.Builder()
                .scheme("agendavip")
                .authority("alarma")
                .appendPath(espacio.clave)
                .appendPath(id)
                .appendPath(fecha.toString())
                .build(),
        )

        if (espacio.clave == EspacioPersonal.INVITADO) {
            // La versión anterior no incluía dueño; cancela también esa petición al actualizar.
            direcciones.add(
                Uri.Builder()
                    .scheme("agendavip")
                    .authority("alarma")
                    .appendPath(id)
                    .appendPath(fecha.toString())
                    .build(),
            )
        }

        direcciones.forEach { direccion ->
            val intento = Intent(contexto, ReceptorRecordatorios::class.java)
                .setAction(ReceptorRecordatorios.ACCION_ALARMA)
                .setData(direccion)

            val pendiente = PendingIntent.getBroadcast(
                contexto,
                0,
                intento,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )

            if (pendiente != null) {
                alarmas.cancel(pendiente)
                pendiente.cancel()
            }
        }
    }

    /**
     * Recibe el código [id] y la [fecha] para detener la alarma y el sonido de ese día.
     * Guardar, completar, posponer y detener la llaman antes de cambiar la programación.
     * Si el aviso ya se activó, [conservarRegistro] permite dejar su notificación como constancia,
     * sin los botones del aviso anterior. Eliminar pasa false para retirarla por completo.
     * No devuelve datos ni modifica los avisos de otra fecha o de otra cuenta.
     */
    private fun cancelarOcurrencia(
        id: String,
        fecha: LocalDate,
        conservarRegistro: Boolean = true
    ) {
        cancelarAlarma(id, fecha)
        ServicioAviso.detener(contexto, id, fecha, espacio.clave)

        try {
            val detalle = if (conservarRegistro) repositorio.leerDetalle(id, fecha) else null
            val tarea = detalle?.first
            val ultimaAlerta = detalle?.second?.ultimaAlerta

            if (tarea != null && tarea.correspondeA(fecha) && ultimaAlerta != null) {
                NotificacionesAviso.conservarRegistro(contexto, tarea, fecha, ultimaAlerta, espacio.clave)
            } else {
                NotificacionesAviso.cancelar(contexto, id, fecha, espacio.clave)
            }
        } catch (error: RuntimeException) {
            // Un aviso visual antiguo no impide guardar Completar, Detener o Posponer.
            android.util.Log.w("AgendaVIP", "No se pudo actualizar la notificación: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        // Todas las instancias del gestor comparten este turno para no mezclar cambios simultáneos.
        private val cerrojo = EspacioPersonal.cerrojoSesion

        /**
         * Recibe [contexto] para acceder a Android y un [mensaje] que explica un fallo.
         * El gestor, los receptores y el servicio la llaman cuando no consiguen terminar una operación.
         * Guarda el mensaje para que la cuenta pueda consultarlo. Si notificar es false, la
         * llamada conserva solo el diagnóstico porque esa alarma ya tiene su aviso propio.
         * Un canal es un grupo de notificaciones configurable en Ajustes; aquí se usa el de problemas.
         * No devuelve datos y conserva el mensaje aunque no pueda mostrar la notificación.
         */
        internal fun registrarProblema(
            contexto: Context,
            mensaje: String,
            propietario: String? = EspacioPersonal.claveActual(contexto),
            notificar: Boolean = true,
        ) = synchronized(cerrojo) {
            if (propietario == null || EspacioPersonal.claveActual(contexto) != propietario) {
                return@synchronized
            }

            EspacioPersonal(contexto).preferencias("estado_recordatorios")
                .edit()
                .putString("ultimo_error", mensaje)
                .apply()

            if (!notificar) {
                return@synchronized
            }

            if (Build.VERSION.SDK_INT >= 33 &&
                contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return@synchronized
            }

            try {
                val notificaciones = contexto.getSystemService(NotificationManager::class.java)

                if (!notificaciones.areNotificationsEnabled()) {
                    return@synchronized
                }

                notificaciones.createNotificationChannel(
                    NotificationChannel(
                        "agenda_estado",
                        "Estado de los recordatorios",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Problemas al guardar, programar o reproducir los avisos."
                    }
                )
                val abrir = Intent("abrir_agenda")
                    .setClassName(contexto.packageName, "com.nowel.agendavip.presentacion.agenda.ActividadAgenda")
                    .setData(Uri.parse("agendavip://estado/$propietario"))
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
                val pendiente = PendingIntent.getActivity(
                    contexto,
                    0,
                    abrir,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val aviso = Notification.Builder(contexto, "agenda_estado")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Revisa los recordatorios")
                    .setContentText(mensaje)
                    .setStyle(Notification.BigTextStyle().bigText(mensaje))
                    .setContentIntent(pendiente)
                    .setAutoCancel(true)
                    .build()
                notificaciones.notify("$propietario|estado_recordatorios", 1, aviso)
            } catch (_: RuntimeException) {
                // El mensaje sigue guardado aunque Android ya no permita mostrar notificaciones.
            }
        }
    }
}
