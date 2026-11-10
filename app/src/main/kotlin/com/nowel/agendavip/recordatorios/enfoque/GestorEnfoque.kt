package com.nowel.agendavip.recordatorios.enfoque

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.dominio.enfoque.TipoEventoEnfoque
import java.util.UUID

/**
 * Coordina las actividades propias de Enfoque, su reloj y sus avisos.
 * Recibe Android. Las pantallas y los botones de notificación lo llaman fuera del dibujo.
 * AlarmManager entrega el comienzo y el final aunque ninguna pantalla permanezca abierta.
 * Cada operación comprueba la cuenta: una notificación antigua no cambia datos de otra persona.
 */
class GestorEnfoque(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val espacio = EspacioPersonal(this.contexto)
    private val repositorio = RepositorioEnfoque(this.contexto)
    private val alarmas = this.contexto.getSystemService(AlarmManager::class.java)
    private val arranqueActual = leerArranque()

    /**
     * Recibe título, hora del aviso y minutos previstos. Crear no comienza el tiempo de trabajo.
     * El formulario puede reservar id para que repetir su guardado tras un giro no duplique la actividad.
     * Devuelve lo guardado; si Android rechaza la alarma informa del fallo sin borrar la actividad.
     */
    fun crear(
        titulo: String,
        avisoEnEpochMs: Long,
        duracionMinutos: Int,
        musicaUri: String? = null,
        musicaNombre: String = "",
        id: String = UUID.randomUUID().toString(),
    ): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        val anterior = repositorio.obtener(id)

        if (anterior != null) {
            require(
                anterior.titulo == titulo.trim() && anterior.duracionMinutos == duracionMinutos &&
                    anterior.avisoEnEpochMs == avisoEnEpochMs && anterior.musicaUri == musicaUri &&
                    anterior.musicaNombre == musicaNombre,
            ) {
                "Esta actividad ya está guardada. Revísala en Enfoque antes de crear otra."
            }
            if (anterior.estado == EstadoEnfoque.PENDIENTE) {
                programarInicio(anterior)
            }
            return@synchronized anterior
        }

        validarFormulario(titulo, avisoEnEpochMs, duracionMinutos, musicaUri, musicaNombre)
        val ahora = System.currentTimeMillis()
        val nueva = ActividadEnfoque(
            id = id,
            titulo = titulo.trim(),
            creadaEn = ahora,
            avisoEnEpochMs = avisoEnEpochMs,
            duracionMinutos = duracionMinutos,
            musicaUri = musicaUri,
            musicaNombre = musicaNombre,
        )
        repositorio.guardar(nueva, TipoEventoEnfoque.CREADA, "Aviso programado; el tiempo comenzará cuando elijas Comenzar.")
        programarInicio(nueva)
        nueva
    }

    /**
     * El formulario recibe una actividad todavía no iniciada y cambia sus datos conservando el código.
     * Devuelve la versión nueva. Una actividad en curso o terminada conserva su historial sin reescribirlo.
     */
    fun editar(
        id: String,
        titulo: String,
        avisoEnEpochMs: Long,
        duracionMinutos: Int,
        musicaUri: String? = null,
        musicaNombre: String = "",
    ): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        val anterior = obtener(id)
        require(anterior.estado in setOf(EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO)) {
            "Solo se pueden editar los datos antes de comenzar esta actividad."
        }
        require(repositorio.eventos().none {
            it.actividadId == id && it.tipo in setOf(TipoEventoEnfoque.INICIADA, TipoEventoEnfoque.CONTINUADA)
        }) {
            "Esta actividad ya comenzó. Puedes posponer o continuar sin cambiar su tiempo trabajado."
        }
        validarFormulario(titulo, avisoEnEpochMs, duracionMinutos, musicaUri, musicaNombre)
        val nueva = anterior.copy(
            titulo = titulo.trim(),
            avisoEnEpochMs = avisoEnEpochMs,
            duracionMinutos = duracionMinutos,
            restanteMs = duracionMinutos * 60_000L,
            estado = EstadoEnfoque.PENDIENTE,
            actualizadaEn = System.currentTimeMillis(),
            musicaUri = musicaUri,
            musicaNombre = musicaNombre,
        )
        repositorio.guardar(nueva, TipoEventoEnfoque.EDITADA, "Se cambiaron los datos del aviso antes de comenzar.")
        cancelarAlarma(id, ETAPA_FIN)
        try {
            programarInicio(nueva)
        } finally {
            // El cambio ya quedó guardado. Se detiene cualquier sonido anterior incluso
            // si Android rechaza la nueva alarma; posponer no puede dejar la canción sonando.
            NotificacionesEnfoque.cancelar(contexto, id, espacio.clave)
            ServicioEnfoque.actualizarSesion(contexto, id)
        }
        nueva
    }

    /**
     * Comenzar recibe el código y arranca el tiempo reservado desde este instante, no desde el aviso.
     * Devuelve la actividad en curso. Si otra ya está trabajando pide pausarla antes; una segunda
     * pulsación sobre esta misma actividad no vuelve a comenzar su cuenta atrás.
     */
    fun iniciar(id: String): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        val anterior = obtener(id)

        if (anterior.estado == EstadoEnfoque.EN_CURSO) {
            return@synchronized anterior
        }

        require(anterior.estado in setOf(EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO)) {
            "Para retomar una pausa o continuar otro bloque, usa Continuar."
        }
        comenzarBloque(anterior, anterior.restanteMs.coerceAtLeast(1L), TipoEventoEnfoque.INICIADA)
    }

    /**
     * Pausar recibe el código, guarda el tiempo pendiente y suma solo lo transcurrido del bloque.
     * Devuelve la actividad pausada. No interpreta la pausa como una tarea completada.
     */
    fun pausar(id: String): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        val anterior = obtener(id)

        if (anterior.estado == EstadoEnfoque.PAUSADA) {
            return@synchronized anterior
        }

        require(anterior.estado == EstadoEnfoque.EN_CURSO) { "Esta actividad no está en curso." }
        val restante = restanteMs(anterior)

        if (restante == 0L) {
            return@synchronized finalizarTiempo(anterior)
        }

        val trabajados = anterior.restanteMs - restante
        val nueva = sinReloj(anterior).copy(
            estado = EstadoEnfoque.PAUSADA,
            restanteMs = restante,
            acumuladoTrabajadoMs = anterior.acumuladoTrabajadoMs + trabajados,
            actualizadaEn = System.currentTimeMillis(),
        )
        repositorio.guardar(nueva, TipoEventoEnfoque.PAUSADA, "Sesión pausada. Tiempo pendiente: ${minutosTexto(restante)}.")
        cancelarAlarma(id, ETAPA_FIN)
        NotificacionesEnfoque.mostrar(contexto, nueva, espacio.clave)
        ServicioEnfoque.actualizarSesion(contexto, id)
        nueva
    }

    /**
     * Continuar retoma una pausa, o comienza otro bloque después de la pregunta final.
     * Recibe minutos opcionales; sin ellos retoma lo pendiente o usa la duración original al acabar.
     * Devuelve la actividad activa y no marca como completado un bloque por agotarse su reloj.
     */
    fun continuar(id: String, minutos: Int? = null): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        val anterior = obtener(id)

        if (anterior.estado == EstadoEnfoque.EN_CURSO) {
            return@synchronized anterior
        }

        require(anterior.estado in setOf(EstadoEnfoque.PAUSADA, EstadoEnfoque.ESPERANDO_RESPUESTA)) {
            "Esta actividad no está pausada ni esperando que decidas si continuar."
        }
        require(minutos == null || minutos in 1..480) { "Elige de 1 a 480 minutos." }
        val duracion = when {
            minutos != null -> minutos * 60_000L
            anterior.estado == EstadoEnfoque.PAUSADA -> anterior.restanteMs
            else -> anterior.duracionMinutos * 60_000L
        }
        comenzarBloque(anterior, duracion, TipoEventoEnfoque.CONTINUADA)
    }

    /**
     * Recibe el código y cuántos minutos debe esperar antes de recordar otra vez esta actividad.
     * Si estaba en curso, conserva lo trabajado y los minutos pendientes. La espera no cuenta
     * como trabajo. Después de agotar un bloque se debe elegir primero cuánto continuar.
     */
    fun posponer(id: String, minutos: Int): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        require(minutos in 1..1440) { "Puedes posponer de 1 a 1440 minutos." }
        val anterior = obtener(id)
        require(anterior.estado !in setOf(EstadoEnfoque.COMPLETADA, EstadoEnfoque.CANCELADA)) {
            "Esta actividad ya está cerrada. Su resultado sigue en el historial."
        }
        require(anterior.estado != EstadoEnfoque.ESPERANDO_RESPUESTA) {
            "Si el tiempo ya terminó, elige Continuar y sus minutos antes de pedir otro aviso."
        }
        val restante = if (anterior.estado == EstadoEnfoque.EN_CURSO) restanteMs(anterior) else anterior.restanteMs

        if (restante == 0L) {
            return@synchronized finalizarTiempo(anterior)
        }

        val trabajado = if (anterior.estado == EstadoEnfoque.EN_CURSO) anterior.restanteMs - restante else 0L
        val nueva = sinReloj(anterior).copy(
            estado = EstadoEnfoque.PENDIENTE,
            avisoEnEpochMs = System.currentTimeMillis() + minutos * 60_000L,
            restanteMs = restante,
            acumuladoTrabajadoMs = anterior.acumuladoTrabajadoMs + trabajado,
            actualizadaEn = System.currentTimeMillis(),
        )
        repositorio.guardar(nueva, TipoEventoEnfoque.POSPUESTA, "Se pidió recordar en $minutos minutos. El tiempo pendiente de trabajo se conserva.")
        cancelarAlarma(id, ETAPA_FIN)
        try {
            programarInicio(nueva)
        } finally {
            // El cambio ya quedó guardado. Se detiene cualquier sonido anterior incluso
            // si Android rechaza la nueva alarma; posponer no puede dejar la canción sonando.
            NotificacionesEnfoque.cancelar(contexto, id, espacio.clave)
            ServicioEnfoque.actualizarSesion(contexto, id)
        }
        nueva
    }

    /**
     * Completada recibe una decisión explícita de la persona y detiene el aviso y el reloj.
     * Devuelve lo guardado. Solo esta acción completa la actividad; el final del tiempo no lo hace.
     */
    fun completar(id: String): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        cambiarFinal(id, EstadoEnfoque.COMPLETADA, TipoEventoEnfoque.COMPLETADA)
    }

    /** Cancela una actividad sin borrar sus hechos anteriores; Agenda podrá seguir consultándolos. */
    fun cancelar(id: String): ActividadEnfoque = synchronized(EspacioPersonal.cerrojoSesion) {
        cambiarFinal(id, EstadoEnfoque.CANCELADA, TipoEventoEnfoque.CANCELADA)
    }

    /**
     * Recibe la actividad y devuelve milisegundos pendientes, sin escribir nada.
     * La pantalla la consulta para dibujar el reloj; las alarmas de Android siguen siendo independientes.
     * Usa el reloj de encendido mientras sea el mismo arranque y la hora guardada tras un reinicio.
     */
    fun restanteMs(actividad: ActividadEnfoque): Long {
        if (actividad.estado != EstadoEnfoque.EN_CURSO) {
            return actividad.restanteMs
        }

        val finElapsed = actividad.finEnElapsedMs ?: return 0L
        val comienzoElapsed = finElapsed - actividad.restanteMs
        val mismoArranque = if (arranqueActual >= 0 && actividad.arranqueSistema >= 0) {
            arranqueActual == actividad.arranqueSistema
        } else {
            SystemClock.elapsedRealtime() >= comienzoElapsed
        }
        val restante = if (mismoArranque) {
            finElapsed - SystemClock.elapsedRealtime()
        } else {
            (actividad.finEnEpochMs ?: 0L) - System.currentTimeMillis()
        }
        return restante.coerceIn(0L, actividad.restanteMs)
    }

    /**
     * La pantalla la pide cuando su reloj llega a cero, por si Android entregó una alarma aproximada tarde.
     * Solo transforma sesiones vencidas y es idempotente: repetirla no duplica preguntas ni historial.
     */
    fun actualizarVencidas() = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        repositorio.listar().filter { it.estado == EstadoEnfoque.EN_CURSO && restanteMs(it) == 0L }
            .forEach { finalizarTiempo(it) }
    }

    /**
     * Recupera alarmas tras abrir, reiniciar o cambiar permisos. No reinicia música al encender el teléfono.
     * Recibe si hubo reinicio confirmado; reajusta el reloj monotónico usando el momento final guardado.
     * Sin sesión activa el llamador debe esperar una entrada; una cuenta antigua es rechazada aquí.
     */
    fun reprogramarTodo(reinicio: Boolean = false) = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()

        repositorio.listar().forEach { actividad ->
            when (actividad.estado) {
                EstadoEnfoque.PENDIENTE -> programarInicio(actividad)
                EstadoEnfoque.EN_CURSO -> {
                    val restante = if (reinicio) {
                        ((actividad.finEnEpochMs ?: 0L) - System.currentTimeMillis()).coerceIn(0L, actividad.restanteMs)
                    } else {
                        restanteMs(actividad)
                    }

                    if (restante == 0L) {
                        finalizarTiempo(actividad, emitirVoz = !reinicio)
                    } else {
                        val ajustada = actividad.copy(
                            restanteMs = restante,
                            acumuladoTrabajadoMs = actividad.acumuladoTrabajadoMs + actividad.restanteMs - restante,
                            finEnElapsedMs = SystemClock.elapsedRealtime() + restante,
                            finEnEpochMs = System.currentTimeMillis() + restante,
                            arranqueSistema = arranqueActual,
                        )
                        repositorio.guardar(ajustada)
                        programarFin(ajustada)
                        NotificacionesEnfoque.mostrar(contexto, ajustada, espacio.clave)
                    }
                }
                EstadoEnfoque.ESPERANDO_INICIO, EstadoEnfoque.PAUSADA, EstadoEnfoque.ESPERANDO_RESPUESTA ->
                    NotificacionesEnfoque.mostrar(contexto, actividad, espacio.clave)
                EstadoEnfoque.COMPLETADA, EstadoEnfoque.CANCELADA -> {
                    cancelarAlarma(actividad.id, ETAPA_INICIO)
                    cancelarAlarma(actividad.id, ETAPA_FIN)
                    NotificacionesEnfoque.cancelar(contexto, actividad.id, espacio.clave)
                    ServicioEnfoque.detener(contexto, actividad.id)
                }
            }
        }
    }

    /**
     * Cerrar sesión la llama antes de cambiar de cuenta. Retira sus alarmas y notificaciones,
     * sin completar ni borrar actividades. Al entrar de nuevo se recuperan sus estados guardados.
     */
    fun cancelarAlarmasActuales() = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        repositorio.listar().forEach {
            cancelarAlarma(it.id, ETAPA_INICIO)
            cancelarAlarma(it.id, ETAPA_FIN)
            NotificacionesEnfoque.cancelar(contexto, it.id, espacio.clave)
        }
        ServicioEnfoque.detenerTodo(contexto)
    }

    /** Devuelve si Android permite pedir hora exacta; la interfaz explica el retraso posible si falta. */
    fun tieneAlarmasExactas(): Boolean = Build.VERSION.SDK_INT < 31 || alarmas.canScheduleExactAlarms()

    /** Devuelve el último problema de Enfoque o null; no modifica el archivo ni las actividades. */
    fun ultimoProblema(): String? = espacio.preferencias("estado_enfoque").getString("ultimo_error", null)

    /**
     * El receptor entrega código, etapa y momento programado. Rechaza peticiones antiguas y repetidas.
     * Un aviso de inicio solo pregunta; uno de final deja la actividad esperando una decisión.
     */
    internal fun recibir(id: String, etapa: String, momento: Long) = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarCuenta()
        val actividad = repositorio.obtener(id) ?: return@synchronized

        when (etapa) {
            ETAPA_INICIO -> {
                if (actividad.estado != EstadoEnfoque.PENDIENTE || momento != actividad.avisoEnEpochMs) {
                    return@synchronized
                }
                if (momento > System.currentTimeMillis() + 1000L) {
                    programarInicio(actividad)
                    return@synchronized
                }
                val nueva = actividad.copy(
                    estado = EstadoEnfoque.ESPERANDO_INICIO,
                    actualizadaEn = System.currentTimeMillis(),
                )
                repositorio.guardar(nueva, TipoEventoEnfoque.AVISO_INICIO, "Llegó la hora. Esperando Comenzar o Recordármelo después.")
                NotificacionesEnfoque.mostrar(contexto, nueva, espacio.clave)
                emitir(nueva, ETAPA_INICIO)
            }
            ETAPA_FIN -> {
                if (actividad.estado != EstadoEnfoque.EN_CURSO || momento != actividad.finEnElapsedMs) {
                    return@synchronized
                }
                if (restanteMs(actividad) > 0L) {
                    programarFin(actividad)
                    return@synchronized
                }
                finalizarTiempo(actividad)
            }
        }
    }

    /** Valida datos del formulario antes de guardar; no convierte horas pasadas en mañana a escondidas. */
    private fun validarFormulario(titulo: String, aviso: Long, minutos: Int, musicaUri: String?, musicaNombre: String) {
        require(titulo.isNotBlank() && titulo.trim().length <= 160) { "Escribe una actividad de hasta 160 caracteres." }
        require(aviso > System.currentTimeMillis()) { "Elige una fecha y hora futuras para el aviso." }
        require(minutos in 1..480) { "Elige de 1 a 480 minutos para dedicarle." }
        require(musicaNombre.length <= 300 && (musicaUri == null || musicaUri.length <= 4000)) {
            "La referencia de la canción no es válida."
        }
        require(musicaUri == null || Uri.parse(musicaUri).scheme in setOf("content", "file", "android.resource")) {
            "Elige una canción guardada en el teléfono."
        }
    }

    /** Devuelve una actividad existente de esta cuenta o explica que ya no está disponible. */
    private fun obtener(id: String): ActividadEnfoque = repositorio.obtener(id)
        ?: throw IllegalArgumentException("No se encontró esta actividad en tu cuenta.")

    /** Comienza un bloque desde ahora, comprueba otra sesión activa y prepara el final en Android. */
    private fun comenzarBloque(anterior: ActividadEnfoque, duracion: Long, tipo: TipoEventoEnfoque): ActividadEnfoque {
        require(duracion in 1L..480L * 60_000L) { "No queda tiempo para retomar; elige cuántos minutos continuar." }
        // Una alarma aproximada puede haberse retrasado. Retira el estado en curso de bloques
        // que ya vencieron antes de comprobar el conflicto con una actividad nueva.
        repositorio.listar().filter {
            it.id != anterior.id && it.estado == EstadoEnfoque.EN_CURSO && restanteMs(it) == 0L
        }.forEach {
            finalizarTiempo(it, emitirVoz = false)
        }
        require(repositorio.listar().none { it.id != anterior.id && it.estado == EstadoEnfoque.EN_CURSO }) {
            "Ya hay otra actividad en curso. Paúsala antes de comenzar esta."
        }
        val ahora = System.currentTimeMillis()
        val nueva = anterior.copy(
            estado = EstadoEnfoque.EN_CURSO,
            restanteMs = duracion,
            finEnEpochMs = ahora + duracion,
            finEnElapsedMs = SystemClock.elapsedRealtime() + duracion,
            arranqueSistema = arranqueActual,
            actualizadaEn = ahora,
        )
        repositorio.guardar(nueva, tipo, "Comenzó un bloque de ${minutosTexto(duracion)}.")
        cancelarAlarma(anterior.id, ETAPA_INICIO)
        try {
            programarFin(nueva)
        } catch (error: RuntimeException) {
            // Si no se puede preparar el final, no se deja una sesión con música indefinida.
            // Se conserva el bloque como pausa para retomarlo después de corregir el permiso.
            val pausada = sinReloj(nueva).copy(
                estado = EstadoEnfoque.PAUSADA,
                actualizadaEn = System.currentTimeMillis(),
            )
            try {
                repositorio.guardar(pausada, TipoEventoEnfoque.PAUSADA, "Pausa de seguridad: Android no pudo preparar el aviso de final. Los minutos se conservan.")
                NotificacionesEnfoque.mostrar(contexto, pausada, espacio.clave)
                ServicioEnfoque.actualizarSesion(contexto, pausada.id)
            } catch (fallo: RuntimeException) {
                error.addSuppressed(fallo)
            }
            throw error
        }
        NotificacionesEnfoque.mostrar(contexto, nueva, espacio.clave)
        ServicioEnfoque.actualizarSesion(contexto, nueva.id)
        return nueva
    }

    /** Guarda la decisión final y suma únicamente el tiempo transcurrido si había un bloque abierto. */
    private fun cambiarFinal(id: String, estado: EstadoEnfoque, tipo: TipoEventoEnfoque): ActividadEnfoque {
        comprobarCuenta()
        val anterior = obtener(id)

        if (anterior.estado == estado) {
            return anterior
        }
        require(anterior.estado !in setOf(EstadoEnfoque.COMPLETADA, EstadoEnfoque.CANCELADA)) {
            "Esta actividad ya está cerrada. Su resultado sigue en el historial."
        }
        val pendiente = restanteMs(anterior)
        val trabajado = if (anterior.estado == EstadoEnfoque.EN_CURSO) anterior.restanteMs - pendiente else 0L
        val nueva = sinReloj(anterior).copy(
            estado = estado,
            restanteMs = if (estado == EstadoEnfoque.COMPLETADA) 0L else pendiente,
            acumuladoTrabajadoMs = anterior.acumuladoTrabajadoMs + trabajado,
            actualizadaEn = System.currentTimeMillis(),
        )
        repositorio.guardar(nueva, tipo, "Tiempo de sesión acumulado: ${minutosTexto(nueva.acumuladoTrabajadoMs)}.")
        cancelarAlarma(id, ETAPA_INICIO)
        cancelarAlarma(id, ETAPA_FIN)
        NotificacionesEnfoque.cancelar(contexto, id, espacio.clave)
        ServicioEnfoque.detener(contexto, id)
        return nueva
    }

    /** Agotar el tiempo pregunta qué hacer; no transforma por sí mismo la actividad en completada. */
    private fun finalizarTiempo(anterior: ActividadEnfoque, emitirVoz: Boolean = true): ActividadEnfoque {
        if (anterior.estado != EstadoEnfoque.EN_CURSO) {
            return anterior
        }
        val nueva = sinReloj(anterior).copy(
            estado = EstadoEnfoque.ESPERANDO_RESPUESTA,
            restanteMs = 0L,
            acumuladoTrabajadoMs = anterior.acumuladoTrabajadoMs + anterior.restanteMs,
            actualizadaEn = minOf(System.currentTimeMillis(), anterior.finEnEpochMs ?: System.currentTimeMillis()),
        )
        repositorio.guardar(nueva, TipoEventoEnfoque.TIEMPO_TERMINADO, "Terminó el bloque. Dime, ¿ya terminaste o continuamos?")
        cancelarAlarma(anterior.id, ETAPA_FIN)
        NotificacionesEnfoque.mostrar(contexto, nueva, espacio.clave)
        ServicioEnfoque.actualizarSesion(contexto, nueva.id)

        if (emitirVoz) {
            emitir(nueva, ETAPA_FIN)
        }
        return nueva
    }

    /** Devuelve una copia sin instantes de final; se usa al pausar, posponer o cerrar el bloque. */
    private fun sinReloj(actividad: ActividadEnfoque): ActividadEnfoque = actividad.copy(
        finEnEpochMs = null,
        finEnElapsedMs = null,
        arranqueSistema = -1,
    )

    /** Registra el aviso de comienzo en la hora del calendario; no inicia una cuenta atrás. */
    private fun programarInicio(actividad: ActividadEnfoque) {
        programar(
            actividad,
            ETAPA_INICIO,
            AlarmManager.RTC_WAKEUP,
            maxOf(actividad.avisoEnEpochMs, System.currentTimeMillis() + 1000L),
            actividad.avisoEnEpochMs,
        )
    }

    /** Programa el final con el reloj de encendido para que cambiar la hora no cambie el bloque activo. */
    private fun programarFin(actividad: ActividadEnfoque) {
        val momento = actividad.finEnElapsedMs ?: return
        programar(actividad, ETAPA_FIN, AlarmManager.ELAPSED_REALTIME_WAKEUP, maxOf(momento, SystemClock.elapsedRealtime() + 500L), momento)
    }

    /**
     * Solicita una alarma exacta si está permitida, o aproximada cuando Android no concede ese acceso.
     * Usa la misma petición al reemplazarla: una salida de pantalla nunca cancela antes el aviso vigente.
     */
    private fun programar(actividad: ActividadEnfoque, etapa: String, reloj: Int, cuando: Long, token: Long) {
        val intento = intentoAlarma(actividad.id, etapa)
            .putExtra("momento_enfoque", token)
        val pendiente = PendingIntent.getBroadcast(
            contexto,
            0,
            intento,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            if (tieneAlarmasExactas()) {
                try {
                    alarmas.setExactAndAllowWhileIdle(reloj, cuando, pendiente)
                } catch (_: SecurityException) {
                    alarmas.setAndAllowWhileIdle(reloj, cuando, pendiente)
                    registrarProblema("Cambió el permiso de alarmas exactas. Android puede retrasar los avisos de Enfoque.")
                }
            } else {
                alarmas.setAndAllowWhileIdle(reloj, cuando, pendiente)
                registrarProblema("Falta permitir Alarmas y recordatorios. Android puede retrasar el comienzo o el final de Enfoque.")
            }
        } catch (error: RuntimeException) {
            registrarProblema("La actividad se guardó, pero Android no pudo programar su aviso. Revisa los permisos de alarmas.")
            throw IllegalStateException("La actividad se guardó, pero no se pudo programar el aviso. Revisa Alarmas y recordatorios.", error)
        }
    }

    /** Recibe identidad y etapa; construye una petición que distingue también la cuenta propietaria. */
    private fun intentoAlarma(id: String, etapa: String): Intent = Intent(contexto, ReceptorEnfoque::class.java)
        .setAction(ReceptorEnfoque.ACCION_ALARMA)
        .setData(Uri.parse("agendavip://enfoque/${espacio.clave}/$id/$etapa"))
        .putExtra(EspacioPersonal.EXTRA_ESPACIO, espacio.clave)
        .putExtra("enfoque_id", id)
        .putExtra("etapa_enfoque", etapa)

    /** Retira una petición existente al pausar o cerrar; no crea una alarma nueva solo para cancelarla. */
    private fun cancelarAlarma(id: String, etapa: String) {
        val pendiente = PendingIntent.getBroadcast(
            contexto,
            0,
            intentoAlarma(id, etapa),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmas.cancel(pendiente)
        pendiente.cancel()
    }

    /** Pide voz para el aviso ya publicado. Si el sistema la rechaza conserva la notificación con acciones. */
    private fun emitir(actividad: ActividadEnfoque, motivo: String) {
        try {
            ServicioEnfoque.emitir(contexto, actividad.id, motivo)
        } catch (error: RuntimeException) {
            registrarProblema("Android no permitió iniciar la voz de Enfoque. Abre su aviso para responder. ${error.javaClass.simpleName}")
        }
    }

    /** Guarda una explicación en el espacio actual, sin escribir mensajes en otra cuenta. */
    private fun registrarProblema(mensaje: String) {
        espacio.preferencias("estado_enfoque").edit().putString("ultimo_error", mensaje).apply()
    }

    /** Revisa sesión y cancelación antes de cambiar estado o programar; las consultas no alteran la cuenta. */
    private fun comprobarCuenta() {
        check(!Thread.currentThread().isInterrupted) { "La operación se canceló. Revisa Enfoque antes de repetirla." }
        espacio.exigirVigente()
    }

    /** Devuelve el número de encendido de Android, o -1 si el fabricante no permite leerlo. */
    private fun leerArranque(): Int = try {
        Settings.Global.getInt(contexto.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: RuntimeException) {
        -1
    }

    /** Recibe milisegundos y devuelve minutos legibles; no convierte esa duración en una finalización. */
    private fun minutosTexto(milisegundos: Long): String {
        val minutos = milisegundos / 60_000L
        val segundos = (milisegundos % 60_000L) / 1000L
        return "$minutos min $segundos s"
    }

    companion object {
        const val ETAPA_INICIO = "INICIO"
        const val ETAPA_FIN = "FIN"
    }
}