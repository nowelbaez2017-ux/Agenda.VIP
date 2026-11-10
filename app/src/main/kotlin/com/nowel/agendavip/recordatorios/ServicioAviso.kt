package com.nowel.agendavip.recordatorios

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.datos.musica.SelectorMusicaAleatoria
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.voz.VocesLocales
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Reproduce la voz de una tarea y después su música, incluso sin una pantalla de la app abierta.
 * Un servicio es una pieza que Android mantiene trabajando mientras realiza esa tarea.
 * El gestor de recordatorios lo solicita cuando llega una alarma y la persona también puede
 * iniciarlo al tocar Escuchar en la notificación.
 *
 * Usa voces instaladas y archivos del teléfono. Cada aviso tiene un número, [generacion],
 * para ignorar respuestas de una reproducción anterior que ya se detuvo o fue sustituida.
 */
class ServicioAviso : Service() {

    // Handler lleva el trabajo al hilo principal, la vía donde Android coordina estos cambios.
    private val manejador = Handler(Looper.getMainLooper())
    @Volatile
    private var propietarioActual: String? = null

    @Volatile
    private var invalidado = false

    private var preferenciasAviso: PreferenciasAgenda? = null
    private var tareaActual: Tarea? = null
    private var fechaActual: LocalDate? = null
    @Volatile
    private var generacion = 0
    private var voz: TextToSpeech? = null
    private var vozLeida = false
    private var vozPendiente = false
    private var reproductor: MediaPlayer? = null
    private var solicitudFoco: AudioFocusRequest? = null
    private var bloqueo: PowerManager.WakeLock? = null
    private var temporizador: Runnable? = null
    private lateinit var audio: AudioManager

    // La biblioteca aleatoria se consulta fuera del trabajo que habla y dibuja los avisos.
    // Solo se permite una consulta y una espera. Cancelar retira también el trabajo pendiente.
    private val ejecutorMusica = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),
    )
    private var cancelacionMusica: CancellationSignal? = null
    private var trabajoMusica: Future<*>? = null

    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Android la llama una vez al crear el servicio, sin recibir datos ni devolver un resultado.
     * Prepara el acceso al sonido. Los canales se crean al promover el servicio.
     * Guarda una referencia débil al servicio: permite encontrarlo para detenerlo sin impedir
     * que Android lo retire de memoria cuando ya no se necesita.
     */
    override fun onCreate() {
        super.onCreate()
        servicioActual = WeakReference(this)
        audio = getSystemService(AudioManager::class.java)
    }

    /**
     * Android la llama cuando recibe una petición para iniciar o detener el aviso.
     * [intent] es el mensaje con la tarea, fecha y acción; [flags] aporta información de la entrega
     * y [startId] distingue esta petición para poder cerrarla si no hay nada que reproducir.
     * Una ocurrencia es una fecha concreta de una rutina: se comprueba que siga pendiente ese día.
     *
     * Prepara el aviso visible y el sonido. Devuelve START_NOT_STICKY, que pide a Android
     * no volver a crear el servicio por su cuenta si lo cierra sin una nueva petición.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val proteccion = intent?.getLongExtra(ProteccionEntregaAviso.EXTRA_PROTECCION, 0L) ?: 0L

        try {
            val propietario = intent?.getStringExtra(EspacioPersonal.EXTRA_ESPACIO)

            if (intent?.action == ACCION_ESCUCHAR && tareaActual == null && propietario != null &&
                EspacioPersonal.claveActual(this) == propietario
            ) {
                // Android exige mostrar el servicio en pocos segundos. Se hace antes de esperar
                // a escrituras de la agenda, para que una operación lenta no impida empezar el aviso.
                if (!mostrarArranque()) {
                    finalizarAviso()
                    registrarArranqueFallido(intent)
                    return START_NOT_STICKY
                }
            }

            return synchronized(EspacioPersonal.cerrojoSesion) {
                atenderPeticion(intent, startId)
            }
        } finally {
            ProteccionEntregaAviso.liberar(proteccion)
        }
    }

    /**
     * onStartCommand la llama para mostrar la preparación del aviso sin leer datos del usuario.
     * Devuelve true si Android aceptó el servicio. La tarea se comprueba antes de reproducir audio.
     * Se usa un aviso genérico solo cuando no hay otra tarea sonando, para conservar la anterior.
     */
    private fun mostrarArranque(): Boolean {
        return try {
            val aviso = NotificacionesAviso.construirArranque(this)

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NotificacionesAviso.ID_SERVICIO,
                    aviso,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NotificacionesAviso.ID_SERVICIO, aviso)
            }

            true
        } catch (error: RuntimeException) {
            android.util.Log.w("AgendaVIP", "No se pudo promover el servicio de sonido: ${error.javaClass.simpleName}")
            false
        }
    }

    /**
     * Recibe el aviso cuyo servicio Android rechazó. Primero se cierra el intento de servicio;
     * después este trabajador registra el problema y deja Escuchar en la notificación.
     * Se lee la tarea fuera de la pantalla y solo si sigue pendiente en la misma cuenta.
     * No se reintenta iniciar servicios en un bucle ni se vuelve a emitir una alarma consumida.
     */
    private fun registrarArranqueFallido(datos: Intent?) {
        val propietario = datos?.getStringExtra(EspacioPersonal.EXTRA_ESPACIO) ?: return
        val id = datos.getStringExtra("tarea_id") ?: return
        val fechaTexto = datos.getStringExtra("fecha_ocurrencia") ?: return
        val seguimiento = datos.getBooleanExtra("seguimiento", false)
        val contexto = applicationContext

        ejecutorFallos.execute {
            try {
                synchronized(EspacioPersonal.cerrojoSesion) {
                    if (EspacioPersonal.claveActual(contexto) != propietario) {
                        return@synchronized
                    }

                    val fecha = LocalDate.parse(fechaTexto)
                    val (tarea, estado) = RepositorioTareas(contexto).leerDetalle(id, fecha)
                        ?: return@synchronized

                    if (estado.completada || estado.omitida || estado.seguimientoDetenido ||
                        estado.pospuestaHasta != null || estado.avisosEmitidos == 0
                    ) {
                        return@synchronized
                    }

                    publicarRespaldo(
                        contexto,
                        tarea,
                        fecha,
                        seguimiento,
                        propietario,
                        estado.ultimaAlerta,
                        "Android no permitió preparar el servicio de sonido. Revisa el permiso de alarmas exactas y toca el recordatorio para escucharlo.",
                    )
                }
            } catch (error: Exception) {
                android.util.Log.e("AgendaVIP", "Fallo al registrar el arranque de sonido: ${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Recibe el mensaje y su número de Android; devuelve cómo debe continuar el servicio.
     * onStartCommand la llama dentro del turno de sesión para no iniciar audio mientras se sale.
     * Rechaza mensajes antiguos de otra cuenta antes de leer cualquier tarea.
     */
    private fun atenderPeticion(intent: Intent?, startId: Int): Int {
        if (intent == null) {
            if (tareaActual == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val propietario = intent.getStringExtra(EspacioPersonal.EXTRA_ESPACIO)

        if (propietario == null || EspacioPersonal.claveActual(this) != propietario) {
            if (tareaActual == null || EspacioPersonal.claveActual(this) != propietarioActual) {
                finalizarAviso()
            }
            return START_NOT_STICKY
        }

        val id = intent.getStringExtra("tarea_id")
        val fecha = try {
            intent.getStringExtra("fecha_ocurrencia")?.let(LocalDate::parse)
        } catch (_: RuntimeException) {
            null
        }

        if (id.isNullOrBlank() || fecha == null) {
            if (tareaActual == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (intent.action == ACCION_DETENER_AUDIO) {
            detenerSiCoincide(id, fecha, propietario)

            if (tareaActual == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (intent.action != ACCION_ESCUCHAR) {
            if (tareaActual == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val seguimiento = intent.getBooleanExtra("seguimiento", false)
        val detalle = try {
            RepositorioTareas(this).leerDetalle(id, fecha)?.takeIf { (tarea, estado) ->
                // No se reproduce una petición antigua si ya pospusiste, completaste
                // o cambiaste el horario. La nueva alarma limpia la posposición al llegar.
                tarea.correspondeA(fecha) && !estado.completada && !estado.omitida &&
                    !estado.seguimientoDetenido && estado.pospuestaHasta == null &&
                    estado.avisosEmitidos > 0
            }
        } catch (error: Exception) {
            android.util.Log.w("AgendaVIP", "No se pudo leer el aviso para reproducirlo: ${error.javaClass.simpleName}")
            guardarFalloSonoro(
                this,
                "No se pudieron leer los datos del recordatorio para reproducirlo. Abre Agenda.VIP y revisa la tarea.",
                propietario,
            )
            null
        }

        if (detalle == null) {
            if (tareaActual == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val (tarea, estado) = detalle
        val momentoRecepcion = estado.ultimaAlerta

        generacion += 1
        liberarAudio()
        propietarioActual = propietario
        invalidado = false
        preferenciasAviso = PreferenciasAgenda(this)
        tareaActual = tarea
        fechaActual = fecha
        vozLeida = false
        val token = generacion

        try {
            val notificacion = NotificacionesAviso.construirServicio(this, tarea, fecha, seguimiento, propietario)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NotificacionesAviso.ID_SERVICIO,
                    notificacion,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NotificacionesAviso.ID_SERVICIO, notificacion)
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("AgendaVIP", "No se pudo detallar el servicio de sonido: ${error.javaClass.simpleName}")
            // El aviso enriquecido no es indispensable: se puede reproducir con uno genérico.
            if (!mostrarArranque()) {
                publicarRespaldo(
                    this,
                    tarea,
                    fecha,
                    seguimiento,
                    propietario,
                    momentoRecepcion,
                    "Android no permitió iniciar el servicio de sonido. Toca el recordatorio para escucharlo.",
                )
                finalizarAviso()
                return START_NOT_STICKY
            }
            guardarFalloSonoro(
                this,
                "No se pudieron mostrar los detalles del aviso de reproducción. El servicio usó un aviso genérico.",
                propietario,
            )
        }

        tomarBloqueo()

        val focoConcedido = obtenerFoco {
            publicarRespaldo(
                this,
                tarea,
                fecha,
                seguimiento,
                propietario,
                momentoRecepcion,
                "Android interrumpió el audio del recordatorio. Toca el aviso si quieres volver a escucharlo.",
            )
        }
        if (!focoConcedido) {
            publicarRespaldo(
                this,
                tarea,
                fecha,
                seguimiento,
                propietario,
                momentoRecepcion,
                "Android no concedió prioridad al audio del recordatorio. Toca el aviso para escucharlo.",
            )
            finalizarAviso()
            return START_NOT_STICKY
        }

        NotificacionesAviso.mostrar(
            this,
            tarea,
            fecha,
            seguimiento,
            propietario = propietario,
            momentoRecepcion = momentoRecepcion,
        )

        if (tarea.vozActiva) {
            iniciarVoz(tarea, seguimiento, token)
        } else {
            continuarConMusica(token)
        }

        return START_NOT_STICKY
    }

    /**
     * Android la llama si alguien intenta mantener una conexión directa con este servicio.
     * Recibe esa petición en [intent] y devuelve null porque aquí se usan mensajes de inicio,
     * en lugar de una conexión permanente entre una pantalla y el servicio.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android la llama al cerrar el servicio; no recibe datos ni devuelve un resultado.
     * Invalida respuestas antiguas y suelta voz, reproductor y permisos temporales de sonido
     * para que el teléfono no siga ocupado aunque Android cierre el aviso antes de terminar.
     */
    override fun onDestroy() {
        generacion += 1
        liberarAudio()
        ejecutorMusica.shutdownNow()

        if (servicioActual?.get() === this) {
            servicioActual = null
        }

        super.onDestroy()
    }

    /**
     * Recibe el código [id] y la [fecha] que la persona desea detener.
     * [onStartCommand] y [detener] la llaman para cerrar el audio solo si pertenece a ese día.
     * No devuelve un resultado; si ya está sonando otra tarea o fecha, la deja continuar.
     */
    private fun detenerSiCoincide(id: String, fecha: LocalDate, propietario: String) {
        if (tareaActual?.id == id && fechaActual == fecha && propietarioActual == propietario) {
            finalizarAviso()
        }
    }

    /**
     * Solicita el foco de audio: prioridad temporal para emitir este aviso mientras Android
     * coordina el sonido con otras aplicaciones. [atenderPeticion] recibe true si se concedió.
     * Si se pierde esa prioridad, se cierra el audio antes de publicar el respaldo con [alPerder].
     */
    private fun obtenerFoco(alPerder: () -> Unit): Boolean {
        val turno = generacion
        val solicitud = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(
                { cambio ->
                    // Android puede entregar tarde la respuesta de un permiso de sonido anterior.
                    // Esa respuesta no debe detener el aviso que ya lo sustituyó.
                    if ((cambio == AudioManager.AUDIOFOCUS_LOSS ||
                            cambio == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) && avisoVigente(turno)
                    ) {
                        finalizarAviso()
                        alPerder()
                    }
                },
                manejador
            )
            .build()
        solicitudFoco = solicitud

        return try {
            audio.requestAudioFocus(solicitud) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * El servicio llama este respaldo con los datos ya leídos de la ocurrencia y el [mensaje]
     * del fallo. Muestra la acción para escuchar y guarda el diagnóstico sin duplicar el sonido.
     */
    private fun publicarRespaldo(
        contexto: Context,
        tarea: Tarea,
        fecha: LocalDate,
        seguimiento: Boolean,
        propietario: String,
        momentoRecepcion: Long?,
        mensaje: String,
    ) {
        NotificacionesAviso.mostrar(
            contexto,
            tarea,
            fecha,
            seguimiento,
            requiereToque = true,
            propietario = propietario,
            momentoRecepcion = momentoRecepcion,
        )
        guardarFalloSonoro(contexto, mensaje, propietario)
    }

    /**
     * Guarda [mensaje] en Permisos para la cuenta [propietario]. No crea una segunda
     * notificación: [publicarRespaldo] ya ofrece un aviso con acción para escuchar.
     */
    private fun guardarFalloSonoro(contexto: Context, mensaje: String, propietario: String) {
        try {
            GestorRecordatorios.registrarProblema(contexto, mensaje, propietario, notificar = false)
        } catch (error: RuntimeException) {
            android.util.Log.w("AgendaVIP", "No se pudo guardar el diagnóstico del sonido: ${error.javaClass.simpleName}")
        }
    }

    /**
     * [onStartCommand] la llama sin datos para mantener activo el procesador mientras suena el aviso.
     * No devuelve datos. Este bloqueo cubre hasta ciento sesenta segundos de preparación y voz, incluso
     * si el motor de voz no responde. Durante la canción, MediaPlayer mantiene su propio bloqueo
     * hasta terminar o detenerse, para permitir canciones más largas con la pantalla apagada.
     */
    private fun tomarBloqueo() {
        try {
            bloqueo = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:aviso")
                .apply {
                    acquire(160_000L)
                }
        } catch (_: RuntimeException) {
            bloqueo = null
        }
    }

    /**
     * Recibe la [tarea], si usa el mensaje de [seguimiento] y el [token], número del aviso actual.
     * [onStartCommand] la llama cuando la voz está activada para leer el mensaje de la tarea.
     * Pide una voz española instalada sin conexión y espera a que Android diga que está preparada.
     * No devuelve datos: al terminar o fallar entrega el paso siguiente a [terminarVoz].
     * El número del aviso impide que una respuesta antigua empiece a hablar sobre otra tarea.
     */
    private fun iniciarVoz(tarea: Tarea, seguimiento: Boolean, token: Int) {
        vozPendiente = true
        programarLimite(25_000L) {
            terminarVoz(token, false)
        }

        try {
            voz = TextToSpeech(this) { resultado ->
                manejador.post {
                    if (!avisoVigente(token) || !vozPendiente) {
                        return@post
                    }

                    val motor = voz
                    val vozLocal = try {
                        motor?.let {
                            VocesLocales.elegir(it, preferenciasAviso?.nombreVoz)
                        }
                    } catch (_: RuntimeException) {
                        null
                    }

                    if (resultado != TextToSpeech.SUCCESS || motor == null || vozLocal == null) {
                        GestorRecordatorios.registrarProblema(
                            this,
                            "No se encontró una voz española instalada sin conexión. El aviso usará la música elegida o un tono local.",
                            propietarioActual,
                        )
                        terminarVoz(token, false)
                        return@post
                    }

                    try {
                        if (motor.setVoice(vozLocal) != TextToSpeech.SUCCESS) {
                            terminarVoz(token, false)
                            return@post
                        }

                        motor.setAudioAttributes(atributos)
                        val texto = tarea.mensajeParaAviso(seguimiento)
                        val limite = TextToSpeech.getMaxSpeechInputLength()
                        val mensaje = texto.take(limite)
                        motor.setOnUtteranceProgressListener(
                            escuchaVoz(token, margenDeLectura(mensaje)),
                        )

                        // La voz usa el nivel elegido en Agenda.VIP, respetando el del teléfono.
                        val opcionesVoz = Bundle().apply {
                            putFloat(
                                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                                (preferenciasAviso?.volumenAvisos ?: 1f),
                            )
                        }

                        if (motor.speak(
                            mensaje,
                            TextToSpeech.QUEUE_FLUSH,
                            opcionesVoz,
                            "agenda_$token"
                        ) == TextToSpeech.ERROR) {
                            terminarVoz(token, false)
                        }
                    } catch (_: RuntimeException) {
                        terminarVoz(token, false)
                    }
                }
            }
        } catch (_: RuntimeException) {
            terminarVoz(token, false)
        }
    }

    /**
     * Recibe el [token], número del aviso que va a hablar, y devuelve un grupo de respuestas
     * que Android llamará cuando la voz empiece, termine o falle.
     * [iniciarVoz] registra estas respuestas en el motor y entrega el [margenLectura] en milisegundos.
     * La preparación tiene su propia espera; al empezar, la lectura recibe tiempo para el mensaje.
     * El trabajo se lleva al hilo principal, donde el servicio coordina sus cambios en orden.
     */
    private fun escuchaVoz(token: Int, margenLectura: Long): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            /**
             * El motor la llama al empezar y entrega [utteranceId], el nombre del texto leído.
             * No devuelve datos: sustituye la espera de preparación por el margen de lectura.
             * Así un mensaje largo no se corta porque el motor tardara en estar preparado.
             */
            override fun onStart(utteranceId: String?) {
                manejador.post {
                    if (avisoVigente(token) && vozPendiente) {
                        programarLimite(margenLectura) {
                            terminarVoz(token, false)
                        }
                    }
                }
            }

            /**
             * El motor la llama al terminar y entrega el nombre del texto en [utteranceId].
             * No devuelve datos; avisa a [terminarVoz] de que la lectura fue completa para seguir
             * con la música elegida sin necesitar un tono que sustituya a la voz.
             */
            override fun onDone(utteranceId: String?) {
                manejador.post {
                    terminarVoz(token, true)
                }
            }

            /**
             * El motor la llama si falla la voz y entrega el nombre del texto en [utteranceId].
             * Esta llamada de vuelta del motor, también llamada callback, se conserva por compatibilidad.
             * No devuelve datos; informa a [terminarVoz] del fallo para permitir música o tono.
             */
            @Deprecated("La interfaz Android conserva este callback por compatibilidad.")
            override fun onError(utteranceId: String?) {
                manejador.post {
                    terminarVoz(token, false)
                }
            }

            /**
             * El motor también puede avisar del fallo con [utteranceId] y su número [errorCode].
             * No devuelve datos: usa la misma recuperación que la otra variante de [onError],
             * porque cualquier fallo debe permitir que el aviso continúe con música o tono.
             */
            override fun onError(utteranceId: String?, errorCode: Int) {
                manejador.post {
                    terminarVoz(token, false)
                }
            }
        }

    /**
     * iniciarVoz entrega el [mensaje] que va a leer y recibe su espera máxima en milisegundos.
     * Da más margen a textos largos y mantiene un límite si el motor deja de responder.
     * Esta espera empieza al hablar; los veinticinco segundos de preparación se cuentan aparte.
     * El motor suele terminar antes: onDone continúa inmediatamente con la música o el silencio.
     */
    private fun margenDeLectura(mensaje: String): Long {
        return (10_000L + mensaje.length * 120L).coerceIn(25_000L, 90_000L)
    }

    /**
     * Recibe el [token] del aviso y [exito], que indica si se leyó el mensaje hasta el final.
     * Las respuestas del motor y el límite de espera la llaman para cerrar la voz y pasar a música.
     * No devuelve datos. Ignora avisos anteriores y recuerda el éxito para decidir más tarde
     * si necesita un tono cuando no se eligió una canción.
     */
    private fun terminarVoz(token: Int, exito: Boolean) {
        if (!avisoVigente(token) || !vozPendiente) {
            return
        }

        vozPendiente = false
        cancelarLimite()
        val motor = voz
        voz = null

        try {
            motor?.stop()
            motor?.shutdown()
        } catch (_: RuntimeException) {
            // La aplicación de voz puede haberse desconectado; el audio del teléfono debe poder seguir.
        }

        vozLeida = exito
        continuarConMusica(token)
    }

    /**
     * onStartCommand o terminarVoz entregan el número [token] del aviso que debe continuar.
     * Consulta la decisión guardada: canción elegida, una al azar o ninguna canción.
     * No devuelve datos. La elección aleatoria se prepara al sonar, no al guardar la tarea,
     * para usar la biblioteca actual. Sin canción conserva la voz y el tono de respaldo anterior.
     */
    private fun continuarConMusica(token: Int) {
        if (!avisoVigente(token)) {
            return
        }

        val tarea = tareaActual ?: return

        when (tarea.modoMusica) {
            ModoMusica.ALEATORIA -> {
                buscarCancionAleatoria(token)
            }

            ModoMusica.SIN_MUSICA -> {
                continuarSinCancion(token)
            }

            ModoMusica.SELECCIONADA -> {
                val seleccion = tarea.musicaUri?.trim().orEmpty()

                if (seleccion.isEmpty()) {
                    continuarSinCancion(token)
                    return
                }

                val uri = try {
                    Uri.parse(seleccion).takeIf {
                        it.scheme in setOf("content", "file", "android.resource")
                    }
                } catch (_: RuntimeException) {
                    null
                }

                if (uri == null) {
                    reproducirTono(token)
                } else {
                    reproducirUri(uri, token, false)
                }
            }
        }
    }

    /**
     * continuarConMusica la llama con el número [token] cuando se guardó Música aleatoria.
     * No devuelve un dato: pide una dirección legible a SelectorMusicaAleatoria en otro trabajo.
     * El resultado vuelve al trabajo principal y solo reproduce si sigue siendo el mismo aviso
     * y la misma cuenta. Cancelar o esperar más de doce segundos impide una respuesta tardía.
     */
    private fun buscarCancionAleatoria(token: Int) {
        cancelarBusquedaMusica()
        val cancelacion = CancellationSignal()
        cancelacionMusica = cancelacion

        programarLimite(12_000L) {
            if (cancelacionMusica === cancelacion && avisoVigente(token)) {
                cancelarBusquedaMusica()
                informarMusicaAleatoriaNoDisponible(token)
            }
        }

        try {
            trabajoMusica = ejecutorMusica.submit {
                val resultado = runCatching {
                    SelectorMusicaAleatoria(applicationContext).elegir(cancelacion)
                }

                manejador.post {
                    if (cancelacionMusica !== cancelacion || cancelacion.isCanceled || !avisoVigente(token)) {
                        return@post
                    }

                    cancelarLimite()
                    cancelacionMusica = null
                    trabajoMusica = null
                    val uri = resultado.getOrNull()

                    if (uri == null) {
                        informarMusicaAleatoriaNoDisponible(token)
                    } else {
                        reproducirUri(uri, token, false)
                    }
                }
            }
        } catch (_: RuntimeException) {
            cancelarBusquedaMusica()
            cancelarLimite()
            informarMusicaAleatoriaNoDisponible(token)
        }
    }

    /**
     * buscarCancionAleatoria entrega [token] cuando no encontró una canción o agotó la espera.
     * No devuelve datos: registra cómo revisar el permiso y continúa el aviso sin perder su voz.
     * No vuelve a buscar indefinidamente ni cambia la opción musical que guardó la persona.
     */
    private fun informarMusicaAleatoriaNoDisponible(token: Int) {
        if (!avisoVigente(token)) {
            return
        }

        GestorRecordatorios.registrarProblema(
            this,
            "No se pudo elegir música aleatoria. Revisa el permiso de música y que haya canciones locales disponibles. El recordatorio conserva la voz o su tono de respaldo.",
            propietarioActual,
        )
        continuarSinCancion(token)
    }

    /**
     * continuarConMusica y los fallos de elección aleatoria entregan el [token] del aviso.
     * No devuelve datos. Si la voz ya se leyó termina; si no hubo voz o falló, conserva el tono
     * local de ocho segundos que ya usaba el aviso. Sin música significa sin canción,
     * no desactivar también la voz ni todos los sonidos de aviso de Android.
     */
    private fun continuarSinCancion(token: Int) {
        if (!avisoVigente(token)) {
            return
        }

        if (vozLeida) {
            finalizarAviso()
        } else {
            reproducirTono(token)
        }
    }

    /**
     * liberarAudio y la espera de la biblioteca la llaman sin argumentos para retirar la elección.
     * No devuelve datos: anula la consulta, interrumpe su trabajo y quita esperas ya canceladas.
     * Así completar, posponer, editar o cerrar sesión no puede iniciar una canción más tarde.
     */
    private fun cancelarBusquedaMusica() {
        cancelacionMusica?.cancel()
        cancelacionMusica = null
        trabajoMusica?.cancel(true)
        trabajoMusica = null
        ejecutorMusica.purge()
    }

    /**
     * Recibe el [token] del aviso y busca el tono de alarma que Android ya tiene en el teléfono.
     * [continuarConMusica] y [fallarReproduccion] la llaman cuando hace falta un sonido de respaldo.
     * No devuelve datos: usa el tono de notificación si falta el de alarma y termina si no hay ninguno.
     */
    private fun reproducirTono(token: Int) {
        if (!avisoVigente(token)) {
            return
        }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (uri == null) {
            finalizarAviso()
        } else {
            reproducirUri(uri, token, true)
        }
    }

    /**
     * Recibe [uri], la dirección del audio, el [token] del aviso y [esTono], que distingue el respaldo.
     * [continuarConMusica] y [reproducirTono] la llaman para preparar y reproducir el archivo.
     * La preparación es asíncrona: Android trabaja y llama más tarde cuando puede comenzar,
     * en lugar de dejar esta función esperando con la pantalla ocupada.
     * No devuelve datos. La canción se reproduce una vez hasta terminar; el tono de respaldo
     * dura como máximo ocho segundos. Si falla la preparación, intenta recuperar el aviso.
     */
    private fun reproducirUri(uri: Uri, token: Int, esTono: Boolean) {
        if (!avisoVigente(token)) {
            return
        }

        cancelarLimite()
        val nuevo = MediaPlayer()
        reproductor = nuevo

        try {
            // Mantiene activo el procesador mientras suena el audio, aunque se apague la pantalla.
            // MediaPlayer libera este permiso temporal al terminar o al cerrar el reproductor.
            nuevo.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)

            nuevo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            nuevo.setDataSource(this, uri)
            nuevo.isLooping = esTono
            nuevo.setOnPreparedListener { preparado ->
                if (avisoVigente(token) && reproductor === preparado) {
                    try {
                        // Ya está preparado: el límite de espera del archivo no debe cortar la canción.
                        cancelarLimite()

                        // La canción y el tono de respaldo comparten el nivel propio de los avisos.
                        val nivel = (preferenciasAviso?.volumenAvisos ?: 1f)
                        preparado.setVolume(nivel, nivel)
                        preparado.start()

                        // Solo el tono se repite durante ocho segundos. La canción elegida
                        // termina mediante setOnCompletionListener, sin un corte por tiempo.
                        if (esTono) {
                            programarLimite(8_000L) {
                                finalizarAviso()
                            }
                        }
                    } catch (_: RuntimeException) {
                        fallarReproduccion(token, esTono)
                    }
                }
            }
            nuevo.setOnCompletionListener { terminado ->
                if (avisoVigente(token) && reproductor === terminado) {
                    finalizarAviso()
                }
            }
            nuevo.setOnErrorListener { fallido, _, _ ->
                if (reproductor === fallido) {
                    fallarReproduccion(token, esTono)
                }
                true
            }
            programarLimite(12_000L) {
                fallarReproduccion(token, esTono)
            }
            nuevo.prepareAsync()
        } catch (_: Exception) {
            fallarReproduccion(token, esTono)
        }
    }

    /**
     * Recibe el [token] del aviso y [esTono], que indica si falló una canción o el propio tono.
     * [reproducirUri] la llama ante un error o si el archivo tarda demasiado en prepararse.
     * No devuelve datos: cierra el reproductor y prueba un tono al fallar una canción;
     * si ya era el tono, termina para no repetir el mismo intento sin fin.
     */
    private fun fallarReproduccion(token: Int, esTono: Boolean) {
        if (!avisoVigente(token)) {
            return
        }

        cancelarLimite()
        reproductor?.release()
        reproductor = null

        if (esTono) {
            finalizarAviso()
        } else {
            reproducirTono(token)
        }
    }

    /**
     * Recibe una [duracion] en milisegundos y la [accion] que debe ejecutar al agotarse ese tiempo.
     * La voz, la preparación del archivo y el tono la usan para limitar sus esperas o duración.
     * La canción completa no usa un límite de reproducción; termina cuando Android avisa del final.
     * No devuelve datos: sustituye la espera anterior para que no detenga la etapa siguiente.
     */
    private fun programarLimite(duracion: Long, accion: () -> Unit) {
        cancelarLimite()
        temporizador = Runnable {
            accion()
        }.also {
            manejador.postDelayed(it, duracion)
        }
    }

    /**
     * No recibe datos y quita la acción de tiempo que todavía esté esperando ejecutarse.
     * Las funciones que cambian o cierran voz y música la llaman para anular un límite anterior.
     * No devuelve un resultado; después de esta llamada no queda ese temporizador guardado.
     */
    private fun cancelarLimite() {
        temporizador?.let(manejador::removeCallbacks)
        temporizador = null
    }

    /**
     * No recibe datos y termina el aviso que está sonando, sin devolver un resultado.
     * Las acciones de detener y las respuestas de voz, música o tiempo la llaman al finalizar.
     * Invalida respuestas antiguas, libera el sonido y retira el aviso obligatorio del servicio.
     * La notificación de la tarea queda a cargo del gestor de recordatorios.
     */
    private fun finalizarAviso() {
        generacion += 1
        liberarAudio()
        tareaActual = null
        fechaActual = null
        propietarioActual = null
        preferenciasAviso = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * No recibe datos: cierra voz y reproductor, cancela la espera y devuelve a Android
     * la prioridad de sonido y el permiso temporal para mantener despierto el procesador.
     * [onStartCommand], [onDestroy] y [finalizarAviso] la llaman para que nada quede funcionando
     * después de sustituir o cerrar un aviso. No devuelve un resultado.
     */
    private fun liberarAudio() {
        vozPendiente = false
        cancelarLimite()
        cancelarBusquedaMusica()
        val motor = voz
        voz = null

        try {
            motor?.stop()
            motor?.shutdown()
        } catch (_: RuntimeException) {
            // La aplicación que proporciona la voz puede desconectarse mientras se cierra el aviso.
        }

        try {
            reproductor?.release()
        } catch (_: RuntimeException) {
            // Se continúa cerrando lo demás aunque el reproductor ya haya fallado.
        }

        reproductor = null
        val foco = solicitudFoco
        solicitudFoco = null

        if (foco != null) {
            try {
                audio.abandonAudioFocusRequest(foco)
            } catch (_: RuntimeException) {
                // Android puede haber retirado ya la prioridad temporal de sonido.
            }
        }

        try {
            bloqueo?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
            // El tiempo máximo para mantener activo el procesador puede haber terminado justo ahora.
        }

        bloqueo = null
    }

    /**
     * Las respuestas de voz y música entregan el número del aviso que estaban preparando.
     * Devuelve true solo si aún pertenece a la cuenta activa. Si esa cuenta salió, cierra
     * el audio antes de permitir otra etapa; así una respuesta tardía no reanuda su canción.
     */
    private fun avisoVigente(token: Int): Boolean {
        if (token != generacion) {
            return false
        }

        if (invalidado || propietarioActual == null || EspacioPersonal.claveActual(this) != propietarioActual) {
            finalizarAviso()
            return false
        }

        return true
    }

    companion object {
        private val ejecutorFallos = Executors.newSingleThreadExecutor()

        private const val ACCION_ESCUCHAR = "com.nowel.agendavip.ESCUCHAR_AVISO"
        private const val ACCION_DETENER_AUDIO = "com.nowel.agendavip.DETENER_AUDIO"

        @Volatile
        private var servicioActual: WeakReference<ServicioAviso>? = null

        /**
         * Recibe [context], el código [id], la [fecha] y si es un aviso de [seguimiento].
         * El gestor la llama al recibir una alarma para pedir a Android que inicie este servicio.
         * No devuelve datos; si Android lo rechaza, el gestor ofrece escuchar al tocar el aviso.
         */
        fun iniciar(context: Context, id: String, fecha: LocalDate, seguimiento: Boolean, propietario: String) {
            // El receptor puede terminar antes de que Android ejecute onStartCommand.
            // Se mantiene el procesador activo hasta que el servicio tome su propio bloqueo.
            val proteccion = ProteccionEntregaAviso.tomar(context)

            try {
                context.applicationContext.startForegroundService(
                    intentEscuchar(context, id, fecha, seguimiento, propietario)
                        .putExtra(ProteccionEntregaAviso.EXTRA_PROTECCION, proteccion),
                )
            } catch (error: RuntimeException) {
                ProteccionEntregaAviso.liberar(proteccion)
                throw error
            }
        }

        /**
         * Recibe [context], el código [id] y la [fecha] que se desea silenciar.
         * El gestor la llama al cancelar un aviso; [propietario] distingue cuentas con tareas copiadas.
         * No devuelve datos: invalida inmediatamente las respuestas de esa tarea y día, y pide
         * cerrarlas en el trabajo principal. No crea un servicio ni cancela un aviso nuevo posterior.
         */
        fun detener(
            context: Context,
            id: String,
            fecha: LocalDate,
            propietario: String? = EspacioPersonal.claveActual(context),
        ) {
            if (propietario == null) {
                return
            }
            synchronized(EspacioPersonal.cerrojoSesion) {
                val servicio = servicioActual?.get() ?: return@synchronized

                if (servicio.propietarioActual != propietario || servicio.tareaActual?.id != id ||
                    servicio.fechaActual != fecha
                ) {
                    return@synchronized
                }

                // El audio o la búsqueda pueden responder antes de que se atienda este mensaje.
                // Invalidar ahora impide que ese resultado tardío vuelva a empezar a sonar.
                val turno = servicio.generacion
                servicio.invalidado = true
                servicio.manejador.post {
                    if (servicio.generacion == turno && servicio.invalidado) {
                        servicio.detenerSiCoincide(id, fecha, propietario)
                    }
                }
            }
        }

        /**
         * El coordinador entrega Android y, opcionalmente, el dueño que sale.
         * Invalida inmediatamente sus respuestas pendientes y pide al trabajo principal cerrar
         * el audio. No crea un servicio nuevo ni detiene un aviso nuevo que ya lo haya sustituido.
         */
        fun detenerTodo(
            contexto: Context,
            propietario: String? = EspacioPersonal.claveActual(contexto),
        ) {
            val servicio = servicioActual?.get() ?: return

            if (propietario != null && servicio.propietarioActual != propietario) {
                return
            }

            servicio.invalidado = true
            val generacionAnterior = servicio.generacion

            servicio.manejador.post {
                if (servicio.generacion == generacionAnterior && servicio.invalidado) {
                    servicio.finalizarAviso()
                }
            }
        }

        /**
         * Recibe [context], el código [id], la [fecha] y si se usará el mensaje de [seguimiento].
         * Devuelve un PendingIntent: una petición que Android guarda para ejecutarla más tarde.
         * La notificación lo usa al ofrecer Escuchar para que el toque de la persona solicite el audio.
         */
        fun pendienteEscuchar(
            context: Context,
            id: String,
            fecha: LocalDate,
            seguimiento: Boolean,
            propietario: String,
        ): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            intentEscuchar(context, id, fecha, seguimiento, propietario),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Recibe [context], el código [id], la [fecha] y si es un aviso de [seguimiento].
         * Devuelve un Intent, el mensaje que dice a Android qué servicio iniciar y con qué datos.
         * [iniciar] y [pendienteEscuchar] lo usan para identificar el audio de una tarea y un día,
         * de modo que una petición no se confunda con la de otra fecha.
         */
        private fun intentEscuchar(
            context: Context,
            id: String,
            fecha: LocalDate,
            seguimiento: Boolean,
            propietario: String,
        ): Intent = Intent(context, ServicioAviso::class.java)
            .setAction(ACCION_ESCUCHAR)
            .setData(Uri.parse("agendavip://escuchar/$propietario/${Uri.encode(id)}/$fecha"))
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
            .putExtra("tarea_id", id)
            .putExtra("fecha_ocurrencia", fecha.toString())
            .putExtra("seguimiento", seguimiento)
    }
}
