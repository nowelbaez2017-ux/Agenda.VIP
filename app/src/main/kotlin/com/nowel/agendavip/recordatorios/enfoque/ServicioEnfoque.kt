package com.nowel.agendavip.recordatorios.enfoque

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.nowel.agendavip.datos.asistente.ModelosLocales
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.ConversacionEnfoque
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.dominio.enfoque.AccionVozEnfoque
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.dominio.enfoque.InterpreteVozEnfoque
import com.nowel.agendavip.recordatorios.ProteccionEntregaAviso
import com.nowel.agendavip.voz.asistente.OidoWhisperLocal
import com.nowel.agendavip.voz.asistente.VozConversacionLocal
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Lee los avisos de Enfoque y permite responder sin tocar el teléfono si se activó antes el modo voz.
 * El usuario lo activa con una pantalla visible y permiso de micrófono. Una notificación permite pararlo.
 * Solo abre el micrófono después de sus preguntas, nunca mientras habla la app o suena la canción.
 * AlarmManager lleva el tiempo de trabajo por separado: detener este servicio no completa la actividad.
 */
class ServicioEnfoque : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private val trabajador = Executors.newSingleThreadExecutor()
    @Volatile private var version = 0
    @Volatile private var propietario: String? = null
    @Volatile private var actividad: ActividadEnfoque? = null
    @Volatile private var actividadId: String? = null
    @Volatile private var modoVoz = false
    @Volatile private var destruido = false
    @Volatile private var aplicandoOrden = false
    private var reproductor: MediaPlayer? = null
    private var musicaId: String? = null
    private var musicaTerminadaClave: String? = null
    private var focoMusica: AudioFocusRequest? = null
    private var tonoRespaldo: ToneGenerator? = null
    private var focoTono: AudioFocusRequest? = null
    private var finTono: Runnable? = null
    @Volatile private var oido: OidoWhisperLocal? = null
    private var voz: VozConversacionLocal? = null
    private var propuesta: PropuestaVoz? = null
    private var esperandoMinutos: AccionVozEnfoque? = null
    private var intentosFallidos = 0
    private var bloqueo: PowerManager.WakeLock? = null

    @Volatile
    private var estadoVisible = "Respuestas por voz desactivadas"

    /** Una propuesta captura el estado que se leyó; confirmar no cambia una versión posterior. */
    private data class PropuestaVoz(val accion: AccionVozEnfoque, val minutos: Int?, val actualizadaEn: Long)

    /** El trabajador devuelve texto y la siguiente pregunta; las vistas y la voz se actualizan después. */
    private data class RespuestaVoz(
        val texto: String,
        val propuesta: PropuestaVoz? = null,
        val pedirMinutos: AccionVozEnfoque? = null,
        val finalizada: Boolean = false,
        val esperarRespuesta: Boolean = true,
        val actualizada: ActividadEnfoque? = null,
        val entendida: Boolean = true,
    )

    private val vigilar = object : Runnable {
        /** Consulta rápidamente si acabó la frase. El cálculo de Whisper queda fuera de la pantalla. */
        override fun run() {
            if (!modoVoz || destruido) {
                return
            }
            if (oido?.terminoCaptura == true) {
                transcribir()
            } else {
                principal.postDelayed(this, 250L)
            }
        }
    }

    /** Android crea el servicio una vez. Se conserva una referencia débil para conocer su estado real. */
    override fun onCreate() {
        super.onCreate()
        actual = WeakReference(this)
        // Las preguntas del recordatorio deben usar el mismo volumen que sus alarmas.
        voz = VozConversacionLocal(this, AudioAttributes.USAGE_ALARM)
    }

    /**
     * Recibe la acción y valida al dueño. Muestra el servicio antes de leer archivos o esperar turnos.
     * El tipo micrófono solo se activa por una petición explícita de la pantalla visible.
     */
    override fun onStartCommand(datos: Intent?, flags: Int, startId: Int): Int {
        val proteccion = datos?.getLongExtra(ProteccionEntregaAviso.EXTRA_PROTECCION, 0L) ?: 0L

        var lecturaDelegada = false

        try {
            val dueno = datos?.getStringExtra(EspacioPersonal.EXTRA_ESPACIO)
            val id = datos?.getStringExtra("enfoque_id")
            if (dueno == null || id == null || EspacioPersonal.claveActual(this) != dueno) {
                if (actividad == null) {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            if (datos.action == ACCION_APAGAR_VOZ) {
                if (actividadId == id && propietario == dueno) {
                    desactivarVoz()
                } else if (actividad == null) {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            val activar = datos.action == ACCION_ACTIVAR_VOZ
            destruido = false
            val misma = propietario == dueno && actividadId == id
            if (!misma) {
                pararTurno()
                pararMusica()
                modoVoz = false
                actividad = null
                musicaTerminadaClave = null
            }
            propietario = dueno
            actividadId = id
            if (activar) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    guardarError(this, "Permite el micrófono para activar respuestas por voz.", dueno)
                    terminarServicio()
                    return START_NOT_STICKY
                }
                modoVoz = true
            }
            if (!promover()) {
                modoVoz = false
                terminarServicio()
                return START_NOT_STICKY
            }
            val motivo = datos.getStringExtra("motivo") ?: "ACTUALIZAR"
            if (activar || motivo in setOf("INICIO", "FIN", "ACTUALIZAR")) {
                pararTurno()
                pararMusica()
            }
            val turno = version
            trabajador.execute {
                try {
                    comprobar(turno, dueno)
                    if (activar) {
                        check(ModelosLocales(applicationContext).instalado()) {
                            "Prepara primero el modelo de voz sin conexión."
                        }
                    }
                    val guardada = RepositorioEnfoque(applicationContext).obtener(id)
                        ?: throw IllegalStateException("La actividad ya no está disponible.")
                    entregarProtegido(proteccion) {
                        if (!vigente(turno, dueno)) {
                            return@entregarProtegido
                        }
                        actividad = guardada
                        if (guardada.estado in setOf(EstadoEnfoque.COMPLETADA, EstadoEnfoque.CANCELADA)) {
                            terminarServicio()
                        } else if (activar || motivo == "INICIO" || motivo == "FIN") {
                            intentosFallidos = 0
                            preguntar(guardada)
                        } else {
                            actualizarSonido(guardada)
                        }
                    }
                } catch (error: Exception) {
                    entregarProtegido(proteccion) {
                        if (vigente(turno, dueno)) {
                            guardarError(applicationContext, error.message ?: "No se pudo preparar el aviso de Enfoque.", dueno)
                            terminarServicio()
                        }
                    }
                }
            }
            lecturaDelegada = true
            return START_NOT_STICKY
        } finally {
            if (!lecturaDelegada) {
                ProteccionEntregaAviso.liberar(proteccion)
            }
        }
    }

    /** Entrega el resultado al hilo principal y libera la protección del receptor incluso si se descarta. */
    private fun entregarProtegido(proteccion: Long, accion: () -> Unit) {
        principal.post {
            try {
                accion()
            } finally {
                ProteccionEntregaAviso.liberar(proteccion)
            }
        }
    }

    /**
     * El arranque la llama sin datos para mostrar inmediatamente una notificación de servicio.
     * Devuelve false si Android rechaza el permiso; se conserva el aviso normal y la actividad pendiente.
     */
    private fun promover(): Boolean {
        return try {
            val dato = actividad
            val aviso = if (dato == null) NotificacionesEnfoque.construirArranque(this, modoVoz)
            else NotificacionesEnfoque.construirServicio(this, dato, propietario.orEmpty(), modoVoz)
            if (Build.VERSION.SDK_INT >= 29) {
                var tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (modoVoz && Build.VERSION.SDK_INT >= 30) {
                    tipos = tipos or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NotificacionesEnfoque.ID_SERVICIO, aviso, tipos)
            } else {
                startForeground(NotificacionesEnfoque.ID_SERVICIO, aviso)
            }
            true
        } catch (error: RuntimeException) {
            guardarError(this, "Android no permitió iniciar el sonido o el micrófono: ${error.javaClass.simpleName}. Activa el modo voz con la pantalla abierta.", propietario)
            false
        }
    }

    /** Recibe los datos actuales y formula la pregunta adecuada; nunca arranca el contador por sí sola. */
    private fun preguntar(dato: ActividadEnfoque) {
        propuesta = null
        esperandoMinutos = null
        val texto = when (dato.estado) {
            EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO -> {
                propuesta = PropuestaVoz(AccionVozEnfoque.COMENZAR, null, dato.actualizadaEn)
                "Recuerda que tienes que ${dato.titulo}. ¿Comenzamos? Di sí para empezar, o recuérdamelo en treinta minutos, una hora o dos horas."
            }
            EstadoEnfoque.ESPERANDO_RESPUESTA -> "Terminó el tiempo de ${dato.titulo}. Dime, ¿ya terminaste o continuamos?"
            EstadoEnfoque.PAUSADA -> "${dato.titulo} está en pausa. Puedes decir continuamos, o ya terminé."
            else -> "Estás trabajando en ${dato.titulo}. Puedes decir pausar o ya terminé."
        }
        hablar(texto, true)
    }

    /**
     * Recibe una frase y si se espera respuesta. La guarda como texto y la lee con una voz local.
     * Mantiene activo el procesador solo durante este intercambio, con un límite de dos minutos.
     */
    private fun hablar(texto: String, responder: Boolean) {
        pararMusica()
        tomarBloqueo()
        estadoVisible = texto
        val id = actividad?.id ?: return
        val dueno = propietario ?: return
        registrar(id, "Agenda.VIP", texto, dueno)
        promover()
        val turno = version
        try {
            voz?.hablar(texto) { exito ->
                if (vigente(turno, dueno)) {
                    if (!exito) {
                        guardarError(this, "La respuesta está en el historial. Instala una voz española sin conexión para escucharla.", dueno)
                        reproducirRespaldo(turno, dueno)
                    } else if (responder && modoVoz) {
                        principal.postDelayed({
                            if (vigente(turno, dueno)) {
                                escuchar()
                            }
                        }, 400L)
                    } else {
                        terminarRespuesta()
                    }
                }
            }
        } catch (_: RuntimeException) {
            guardarError(this, "No se pudo leer la respuesta. Consulta los mensajes de Enfoque.", dueno)
            reproducirRespaldo(turno, dueno)
        }
    }

    /**
     * Recibe el turno y dueño del aviso cuando Android no consigue leer el mensaje.
     * Produce un tono del teléfono durante un segundo y medio, sin Internet ni archivos externos.
     * Después conserva la actividad pendiente. No escucha encima del tono ni inicia el contador.
     * El volumen propio de cero se respeta: silencio elegido por la persona sigue siendo silencio.
     */
    private fun reproducirRespaldo(turno: Int, dueno: String) {
        pararTono()
        voz?.detener()

        if (!vigente(turno, dueno)) {
            return
        }

        try {
            val volumen = (PreferenciasAgenda(this).volumenAvisos * 100).toInt()

            if (volumen == 0) {
                terminarRespuesta()
                return
            }

            val atributos = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val solicitud = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(atributos)
                .setOnAudioFocusChangeListener({ cambio ->
                    if (cambio < 0 && vigente(turno, dueno)) {
                        pararTono()
                        terminarRespuesta()
                    }
                }, principal)
                .build()
            focoTono = solicitud
            val audio = getSystemService(AudioManager::class.java)

            if (audio.requestAudioFocus(solicitud) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                pararTono()
                terminarRespuesta()
                return
            }

            val tono = ToneGenerator(AudioManager.STREAM_ALARM, volumen)
            tonoRespaldo = tono
            check(tono.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1500)) {
                "El teléfono no pudo reproducir el tono de respaldo."
            }
            estadoVisible = "Sonó el tono de respaldo. El mensaje está en el historial."
            finTono = Runnable {
                pararTono()

                if (vigente(turno, dueno)) {
                    terminarRespuesta()
                }
            }.also {
                principal.postDelayed(it, 1600L)
            }
        } catch (_: RuntimeException) {
            pararTono()
            guardarError(this, "No se pudo leer el mensaje ni reproducir el tono. Abre el aviso para consultar la actividad.", dueno)
            terminarRespuesta()
        }
    }

    /** Retira el tono, su espera y la prioridad de audio al terminar o cancelar el aviso. */
    private fun pararTono() {
        finTono?.let(principal::removeCallbacks)
        finTono = null

        try {
            tonoRespaldo?.release()
        } catch (_: RuntimeException) {
            // Android pudo haber liberado ya el sonido.
        }
        tonoRespaldo = null
        focoTono?.let {
            try {
                getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
            } catch (_: RuntimeException) {
                // La prioridad pudo retirarse antes de cancelar este tono.
            }
        }
        focoTono = null
    }

    /** Abre una escucha corta después de la pregunta. Se requiere que el modo manos libres siga activo. */
    private fun escuchar() {
        if (!modoVoz) {
            quedarEnEspera()
            return
        }
        pararMusica()
        voz?.detener()
        estadoVisible = "Te escucho…"
        val turno = version
        val dueno = propietario ?: return
        trabajador.execute {
            var escucha: OidoWhisperLocal? = null
            try {
                comprobar(turno, dueno)
                val actual = oido ?: OidoWhisperLocal(applicationContext).also { oido = it }
                escucha = actual
                comprobar(turno, dueno)
                actual.iniciar()
                if (!vigente(turno, dueno)) {
                    actual.cancelar()
                    if (destruido) {
                        actual.cerrar()
                    }
                    return@execute
                }
                principal.post {
                    if (vigente(turno, dueno) && modoVoz) {
                        principal.post(vigilar)
                    } else {
                        actual.cancelar()
                    }
                }
            } catch (error: Exception) {
                escucha?.cancelar()
                if (destruido) {
                    escucha?.cerrar()
                }
                errorEscucha(error, turno, dueno)
            }
        }
    }

    /** El silencio activa la transcripción; luego prepara una decisión que se confirma hablando. */
    private fun transcribir() {
        principal.removeCallbacks(vigilar)
        estadoVisible = "Transcribiendo tu respuesta sin Internet…"
        val turno = version
        val dueno = propietario ?: return
        val id = actividad?.id ?: return
        val anterior = propuesta
        val pedirMinutos = esperandoMinutos
        trabajador.execute {
            try {
                comprobar(turno, dueno)
                val texto = oido?.terminarYTranscribir().orEmpty()
                comprobar(turno, dueno)
                registrarDirecto(id, "Tú", texto, dueno)
                val resultado = interpretarRespuesta(texto, id, turno, dueno, anterior, pedirMinutos)
                principal.post {
                    if (vigente(turno, dueno)) {
                        resultado.actualizada?.let { actividad = it }
                        propuesta = resultado.propuesta
                        esperandoMinutos = resultado.pedirMinutos
                        intentosFallidos = if (resultado.entendida) 0 else intentosFallidos + 1
                        if (resultado.finalizada) {
                            modoVoz = false
                        }
                        hablar(resultado.texto, resultado.esperarRespuesta && !resultado.finalizada && intentosFallidos < 2)
                    }
                }
            } catch (error: Exception) {
                errorEscucha(error, turno, dueno)
            }
        }
    }

    /**
     * Recibe una transcripción, actividad y propuesta anterior. Devuelve la siguiente pregunta o resultado.
     * Solo una confirmación de la propuesta vigente puede ejecutar un cambio; «sí» no completa por defecto.
     * Una escritura ya confirmada termina aunque se oculte la pantalla durante el guardado.
     */
    private fun interpretarRespuesta(
        texto: String,
        id: String,
        turno: Int,
        dueno: String,
        anterior: PropuestaVoz?,
        pedirMinutos: AccionVozEnfoque?,
    ): RespuestaVoz = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobar(turno, dueno)
        val dato = RepositorioEnfoque(this).obtener(id)
            ?: throw IllegalStateException("La actividad ya no está disponible.")
        val orden = InterpreteVozEnfoque.interpretar(texto)
        if (orden.accion == AccionVozEnfoque.NEGAR) {
            return@synchronized RespuestaVoz(
                "De acuerdo, no cambiaré nada. Puedes volver a usar Responder con voz o los botones del aviso.",
                esperarRespuesta = false,
            )
        }
        if (orden.accion == AccionVozEnfoque.CONFIRMAR && anterior != null) {
            check(anterior.actualizadaEn == dato.actualizadaEn) {
                "La actividad cambió desde la pregunta. Vuelve a abrir Responder con voz para consultar su estado."
            }
            // El gestor notifica sus cambios al servicio. Durante esta decisión coordinamos aquí
            // la respuesta hablada para que esa notificación no interrumpa la confirmación.
            aplicandoOrden = true
            val nueva = try {
                val gestor = GestorEnfoque(this)
                when (anterior.accion) {
                    AccionVozEnfoque.COMENZAR -> gestor.iniciar(id)
                    AccionVozEnfoque.POSPONER -> gestor.posponer(id, anterior.minutos ?: 30)
                    AccionVozEnfoque.COMPLETAR -> gestor.completar(id)
                    AccionVozEnfoque.CONTINUAR -> gestor.continuar(id, anterior.minutos)
                    AccionVozEnfoque.PAUSAR -> gestor.pausar(id)
                    else -> throw IllegalStateException("La decisión no está disponible.")
                }
            } finally {
                aplicandoOrden = false
            }
            val mensaje = when (anterior.accion) {
                AccionVozEnfoque.COMPLETAR -> "Listo. Marcaste ${nueva.titulo} como completada."
                AccionVozEnfoque.POSPONER -> "De acuerdo. Te lo recordaré dentro de ${anterior.minutos} minutos."
                AccionVozEnfoque.PAUSAR -> "La sesión quedó en pausa. El descanso no cuenta como tiempo de trabajo."
                else -> "Comenzamos. Ya está contando el tiempo de ${nueva.titulo}."
            }
            return@synchronized RespuestaVoz(
                mensaje,
                finalizada = nueva.estado == EstadoEnfoque.COMPLETADA,
                esperarRespuesta = false,
                actualizada = nueva,
            )
        }
        val accion = if (orden.accion == AccionVozEnfoque.INDICAR_MINUTOS && pedirMinutos != null) pedirMinutos else orden.accion
        val minutos = orden.minutos
        if (accion in setOf(AccionVozEnfoque.POSPONER, AccionVozEnfoque.CONTINUAR) && minutos == null &&
            !(accion == AccionVozEnfoque.CONTINUAR && dato.estado == EstadoEnfoque.PAUSADA)
        ) {
            return@synchronized RespuestaVoz(
                if (accion == AccionVozEnfoque.POSPONER) "¿Dentro de cuántos minutos quieres el recordatorio? Di treinta minutos, una hora o dos horas."
                else "¿Cuántos minutos más necesitas? Por ejemplo, quince minutos.",
                pedirMinutos = accion,
            )
        }
        if (accion == AccionVozEnfoque.CONTINUAR && minutos != null && minutos !in 1..480) {
            return@synchronized RespuestaVoz(
                "Elige entre uno y cuatrocientos ochenta minutos para continuar.",
                pedirMinutos = accion,
                entendida = false,
            )
        }
        val confirmacion = when (accion) {
            AccionVozEnfoque.COMENZAR -> "¿Confirmas comenzar ${dato.titulo} ahora?"
            AccionVozEnfoque.POSPONER -> "¿Confirmas que te lo recuerde dentro de $minutos minutos?"
            AccionVozEnfoque.COMPLETAR -> "¿Confirmas que ya terminaste ${dato.titulo} y quieres marcarla como completada?"
            AccionVozEnfoque.CONTINUAR -> if (minutos == null) "¿Confirmas reanudar el tiempo pendiente?" else "¿Confirmas continuar otros $minutos minutos?"
            AccionVozEnfoque.PAUSAR -> "¿Confirmas pausar la sesión?"
            else -> return@synchronized RespuestaVoz(
                "No entendí una decisión. Di comenzar, ya terminé, continuamos quince minutos, o recuérdamelo en una hora.",
                entendida = false,
            )
        }
        RespuestaVoz(confirmacion, PropuestaVoz(accion, minutos, dato.actualizadaEn))
    }

    /**
     * Recibe un fallo de micrófono o lectura. Después de dos intentos se deja de escuchar hasta el próximo aviso.
     * La actividad permanece pendiente y los botones de la notificación continúan disponibles.
     */
    private fun errorEscucha(error: Exception, turno: Int, dueno: String) {
        principal.post {
            if (vigente(turno, dueno)) {
                intentosFallidos += 1
                guardarError(this, error.message ?: "No se pudo interpretar la respuesta.", dueno)
                if (intentosFallidos < 2) {
                    hablar("No pude distinguir tu respuesta. Repítela cerca del teléfono.", true)
                } else {
                    propuesta = null
                    esperandoMinutos = null
                    terminarRespuesta()
                }
            }
        }
    }

    /** Actualiza la música según la sesión. La canción se reproduce completa una vez y termina con la sesión. */
    private fun actualizarSonido(dato: ActividadEnfoque) {
        actividad = dato
        val clave = "${dato.id}|${dato.finEnEpochMs}"
        if (dato.estado != EstadoEnfoque.EN_CURSO) {
            pararMusica()
        } else if (!dato.musicaUri.isNullOrBlank() && musicaId != clave && musicaTerminadaClave != clave) {
            pararMusica()
            val nuevo = MediaPlayer()
            reproductor = nuevo
            musicaId = clave
            try {
                val atributos = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val solicitud = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(atributos)
                    .setOnAudioFocusChangeListener({ cambio ->
                        if (cambio < 0 && reproductor === nuevo) {
                            musicaTerminadaClave = clave
                            pararMusica()
                            quedarEnEspera()
                        }
                    }, principal)
                    .build()
                focoMusica = solicitud
                check(getSystemService(AudioManager::class.java).requestAudioFocus(solicitud) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                nuevo.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                nuevo.setAudioAttributes(atributos)
                nuevo.setDataSource(this, Uri.parse(dato.musicaUri))
                nuevo.isLooping = false
                nuevo.setOnPreparedListener {
                    if (reproductor === it && actividad?.estado == EstadoEnfoque.EN_CURSO) {
                        try {
                            val volumen = PreferenciasAgenda(this).volumenAvisos
                            it.setVolume(volumen, volumen)
                            it.start()
                        } catch (_: RuntimeException) {
                            musicaTerminadaClave = clave
                            pararMusica()
                            quedarEnEspera()
                        }
                    }
                }
                nuevo.setOnCompletionListener {
                    if (reproductor === it) {
                        musicaTerminadaClave = clave
                        pararMusica()
                        quedarEnEspera()
                    }
                }
                nuevo.setOnErrorListener { medio, _, _ ->
                    if (reproductor === medio) {
                        musicaTerminadaClave = clave
                        pararMusica()
                        guardarError(this, "No se pudo reproducir la canción elegida para Enfoque.", propietario)
                        quedarEnEspera()
                    }
                    true
                }
                nuevo.prepareAsync()
            } catch (_: Exception) {
                musicaTerminadaClave = clave
                pararMusica()
                guardarError(this, "La canción no está disponible. El contador de trabajo sigue activo.", propietario)
            }
        }
        quedarEnEspera()
    }

    /** Al terminar de hablar vuelve a la música del bloque o espera el siguiente aviso. */
    private fun terminarRespuesta() {
        val dato = actividad
        if (dato?.estado == EstadoEnfoque.EN_CURSO) {
            actualizarSonido(dato)
        } else {
            quedarEnEspera()
        }
    }

    /** Al terminar una pregunta mantiene solo el modo elegido o la música; no deja una captura abierta. */
    private fun quedarEnEspera() {
        soltarBloqueo()
        if (!modoVoz && reproductor == null) {
            terminarServicio()
        } else {
            estadoVisible = if (modoVoz) "Respuestas por voz activadas. Escucharé después del próximo aviso de esta actividad." else "Música de Enfoque en reproducción."
            promover()
        }
    }

    /** Descarta respuestas viejas; la captura se cancela sin interrumpir un guardado ya confirmado. */
    private fun pararTurno() {
        version += 1
        principal.removeCallbacks(vigilar)
        pararTono()
        // Una decisión confirmada termina su guardado. El resto descarta versiones antiguas.
        oido?.cancelar()
        voz?.detener()
        propuesta = null
        esperandoMinutos = null
    }

    /** Apaga solo las respuestas por voz; la duración de trabajo guardada permanece intacta. */
    private fun desactivarVoz() {
        modoVoz = false
        pararTurno()
        estadoVisible = "Respuestas por voz desactivadas"
        quedarEnEspera()
    }

    /** Libera la canción al pausar, terminar o antes de escuchar para que el micrófono no la transcriba. */
    private fun pararMusica() {
        val anterior = reproductor
        reproductor = null
        musicaId = null
        try {
            anterior?.release()
        } catch (_: RuntimeException) {
            // Android pudo cerrar ya el archivo de audio.
        }
        focoMusica?.let {
            try {
                getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
            } catch (_: RuntimeException) {
                // Android pudo retirar antes la prioridad de sonido.
            }
        }
        focoMusica = null
    }

    /** Mantiene despierto el procesador durante voz y transcripción, con salida de seguridad de dos minutos. */
    private fun tomarBloqueo() {
        soltarBloqueo()
        try {
            bloqueo = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voz_enfoque")
                .apply { acquire(120_000L) }
        } catch (_: RuntimeException) {
            bloqueo = null
        }
    }

    /** Devuelve el permiso temporal de procesador al terminar el intercambio o cerrar el servicio. */
    private fun soltarBloqueo() {
        try {
            bloqueo?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
            // Su tiempo máximo pudo terminar antes de esta llamada.
        }
        bloqueo = null
    }

    /** Recibe texto y dueño; los conserva juntos antes de encolar la escritura fuera de pantalla. */
    private fun registrar(id: String, rol: String, texto: String, dueno: String) {
        trabajador.execute { registrarDirecto(id, rol, texto, dueno) }
    }

    /** Guarda solo texto y comprueba al dueño en el mismo turno que los cambios de sesión. */
    private fun registrarDirecto(id: String, rol: String, texto: String, dueno: String) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            if (EspacioPersonal.claveActual(this) != dueno) {
                return
            }
            try {
                ConversacionEnfoque(this).guardar(id, rol, texto)
            } catch (error: Exception) {
                guardarError(this, error.message ?: "No se pudo guardar el texto de voz.", dueno)
            }
        }
    }

    /** Devuelve si un resultado todavía pertenece al turno y a la cuenta que lo solicitó. */
    private fun vigente(turno: Int, dueno: String): Boolean = !destruido && version == turno && propietario == dueno &&
        EspacioPersonal.claveActual(this) == dueno

    /** El trabajador la llama antes de aplicar una respuesta para descartar una escucha cancelada. */
    private fun comprobar(turno: Int, dueno: String) {
        check(!Thread.currentThread().isInterrupted && vigente(turno, dueno)) {
            "La respuesta se canceló o la cuenta cambió."
        }
    }

    /** Cierra únicamente sonido y micrófono; no modifica ni completa una actividad de Enfoque. */
    private fun terminarServicio() {
        modoVoz = false
        pararTurno()
        pararMusica()
        soltarBloqueo()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Este servicio recibe acciones mediante Intent y no ofrece una conexión permanente a otras apps. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Al destruirlo se liberan micrófono, modelo, voz y audio, incluso si Android lo cerró por memoria. */
    override fun onDestroy() {
        destruido = true
        modoVoz = false
        pararTurno()
        pararMusica()
        soltarBloqueo()
        oido?.cerrar()
        voz?.cerrar()
        trabajador.shutdown()
        if (actual?.get() === this) {
            actual = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACCION_APAGAR_VOZ = "com.nowel.agendavip.ENFOQUE_APAGAR_VOZ"
        private const val ACCION_ACTIVAR_VOZ = "com.nowel.agendavip.ENFOQUE_ACTIVAR_VOZ"
        private const val ACCION_AVISO = "com.nowel.agendavip.ENFOQUE_AVISO"
        @Volatile private var actual: WeakReference<ServicioEnfoque>? = null

        /** La pantalla visible llama aquí después del permiso para activar la escucha de esta actividad. */
        fun activarVoz(contexto: Context, id: String) {
            arrancar(contexto, id, ACCION_ACTIVAR_VOZ, "RESPONDER")
        }

        /** El receptor solicita leer la pregunta inicial o final; por sí solo no activa un micrófono. */
        fun emitir(contexto: Context, id: String, motivo: String) {
            arrancar(contexto, id, ACCION_AVISO, motivo)
        }

        /**
         * El gestor avisa después de cambiar una sesión. Reutiliza el servicio activo o inicia música
         * solo si la actividad está en curso y tiene una canción seleccionada.
         */
        fun actualizarSesion(contexto: Context, id: String) {
            val servicio = actual?.get()
            val dueno = EspacioPersonal.claveActual(contexto)
            if (servicio != null && servicio.propietario == dueno && servicio.actividadId == id) {
                // Una confirmación por voz actualizará sonido y datos al terminar de guardarse.
                if (!servicio.aplicandoOrden && !servicio.destruido) {
                    arrancar(contexto, id, ACCION_AVISO, "ACTUALIZAR")
                }
            } else {
                val dato = RepositorioEnfoque(contexto).obtener(id)
                if (dato?.estado == EstadoEnfoque.EN_CURSO && !dato.musicaUri.isNullOrBlank()) {
                    arrancar(contexto, id, ACCION_AVISO, "ACTUALIZAR")
                }
            }
        }

        /** Completar o cancelar detiene únicamente el servicio de la misma cuenta y actividad. */
        fun detener(contexto: Context, id: String) {
            val servicio = actual?.get() ?: return
            val dueno = EspacioPersonal.claveActual(contexto)
            val turno = servicio.version
            if (servicio.propietario == dueno && servicio.actividadId == id && !servicio.aplicandoOrden) {
                servicio.principal.post {
                    if (servicio.version == turno && servicio.propietario == dueno && servicio.actividadId == id) {
                        servicio.terminarServicio()
                    }
                }
            }
        }

        /** Cerrar sesión retira sonido y escucha del dueño anterior sin borrar sus datos. */
        fun detenerTodo(contexto: Context) {
            val servicio = actual?.get() ?: return
            val dueno = EspacioPersonal.claveActual(contexto)
            if (servicio.propietario == dueno) {
                servicio.principal.post {
                    if (servicio.propietario == dueno) {
                        servicio.terminarServicio()
                    }
                }
            }
        }

        /** Apaga las respuestas por voz conservando el contador y la música, si la hay. */
        fun apagarVoz(contexto: Context, id: String) {
            val servicio = actual?.get() ?: return
            val dueno = EspacioPersonal.claveActual(contexto)
            if (servicio.propietario == dueno && servicio.actividadId == id) {
                servicio.principal.post {
                    if (servicio.propietario == dueno && servicio.actividadId == id) {
                        servicio.desactivarVoz()
                    }
                }
            }
        }

        /** Devuelve estado real del servicio en memoria; una preferencia antigua nunca simula micrófono activo. */
        fun modoVozActivo(contexto: Context, id: String): Boolean = actual?.get()?.let {
            !it.destruido && it.modoVoz && it.actividadId == id && it.propietario == EspacioPersonal.claveActual(contexto)
        } == true

        /** Entrega el estado breve de escucha, transcripción o espera que puede mostrar la pantalla. */
        fun estadoVoz(contexto: Context, id: String): String = actual?.get()?.takeIf {
            !it.destruido && it.actividadId == id && it.propietario == EspacioPersonal.claveActual(contexto)
        }?.estadoVisible ?: "Respuestas por voz desactivadas"

        /** Conserva un fallo de sonido o micrófono en el espacio actual para que se pueda consultar. */
        fun guardarError(contexto: Context, mensaje: String, dueno: String? = EspacioPersonal.claveActual(contexto)) {
            synchronized(EspacioPersonal.cerrojoSesion) {
                if (dueno == null || EspacioPersonal.claveActual(contexto) != dueno) {
                    return
                }
                try {
                    EspacioPersonal(contexto).preferencias("voz_enfoque").edit()
                        .putString("ultimo_error", mensaje.take(500)).apply()
                } catch (_: RuntimeException) {
                    // La sesión pudo cerrarse mientras Android rechazaba el inicio.
                }
            }
        }

        /** Crea una petición con dueño y protección corta. Un rechazo de sonido no revierte el trabajo guardado. */
        private fun arrancar(contexto: Context, id: String, accion: String, motivo: String) {
            val dueno = EspacioPersonal.claveActual(contexto) ?: return
            val proteccion = ProteccionEntregaAviso.tomar(contexto)
            try {
                contexto.applicationContext.startForegroundService(
                    Intent(contexto, ServicioEnfoque::class.java)
                        .setAction(accion)
                        .putExtra(EspacioPersonal.EXTRA_ESPACIO, dueno)
                        .putExtra("enfoque_id", id)
                        .putExtra("motivo", motivo)
                        .putExtra(ProteccionEntregaAviso.EXTRA_PROTECCION, proteccion),
                )
            } catch (error: RuntimeException) {
                ProteccionEntregaAviso.liberar(proteccion)
                guardarError(contexto, "Android no permitió iniciar Enfoque en segundo plano: ${error.javaClass.simpleName}. Usa los botones del aviso y revisa los permisos.", dueno)
            }
        }
    }
}
