package com.nowel.agendavip.presentacion.musica

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Permite escuchar hasta treinta segundos antes de elegir la música de una tarea.
 * ActividadSelectorMusica crea este objeto y consulta sus datos para dibujar los controles.
 * Recibe el contexto, que da acceso a los servicios de Android, y dos funciones de aviso:
 * alCambiar actualiza la pantalla y alFallar muestra que no se pudo escuchar el archivo.
 *
 * Un hilo es un trabajo separado: aquí abre y maneja el reproductor para no congelar
 * los controles. El hilo principal es el que puede actualizar lo que aparece en pantalla.
 * Una URI es la dirección que Android utiliza para encontrar un archivo autorizado.
 */
class PreescuchaMusica(
    contexto: Context,
    private val alCambiar: () -> Unit,
    private val alFallar: () -> Unit,
) {
    // La pantalla puede leer estos datos, pero solo esta clase puede modificarlos.
    var uriActual: Uri? = null
        private set
    var reproduciendo = false
        private set
    var preparando = false
        private set
    var segundos = 0
        private set

    private val contextoAplicacion = contexto.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private val hiloAudio = HandlerThread("Preescucha local").apply {
        start()
    }
    private val colaAudio = Handler(hiloAudio.looper)

    // Cada muestra tiene un número de turno. Al cambiarlo, los trabajos anteriores
    // dejan de poder actualizar la pantalla o volver a poner música por accidente.
    private val generacion = AtomicInteger(0)
    private val sonido = contexto.getSystemService(AudioManager::class.java)
    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // El foco de audio es el permiso temporal para sonar junto a otras aplicaciones.
    // Android llama esta función de aviso si cambia ese permiso; perderlo detiene la muestra.
    private val solicitudFoco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(atributos)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(
            { cambio ->
                if (cambio != AudioManager.AUDIOFOCUS_GAIN) {
                    detener()
                }
            },
            principal,
        )
        .build()

    private var reproductor: MediaPlayer? = null
    private var generacionReproductor = 0

    // Solo lo lee y modifica el trabajo de audio. Recuerda el segundo ya enviado a pantalla
    // para revisar el límite cada cuarto de segundo sin repetir cuatro veces el mismo aviso.
    private var ultimoSegundoPublicado = 0
    private var cerrada = false

    private val actualizarTiempo = object : Runnable {
        /**
         * La cola de audio llama esta función para revisar el avance de la muestra.
         * No recibe argumentos ni devuelve un dato: publica los segundos o termina el audio.
         * Revisa cada cuarto de segundo el límite de treinta, pero solo avisa al cambiar el segundo.
         */
        override fun run() {
            val actual = reproductor ?: return
            val turno = generacionReproductor

            if (turno != generacion.get()) {
                return
            }

            try {
                val tiempo = actual.currentPosition / 1000

                if (tiempo >= 30) {
                    terminar(turno, fallo = false)
                } else {
                    if (tiempo != ultimoSegundoPublicado) {
                        ultimoSegundoPublicado = tiempo

                        publicar(turno) {
                            segundos = tiempo
                            alCambiar()
                        }
                    }

                    colaAudio.postDelayed(this, 250)
                }
            } catch (_: IllegalStateException) {
                terminar(turno, fallo = true)
            }
        }
    }

    /**
     * La pantalla llama esta función al tocar reproducir, pausar o reanudar.
     * Recibe la dirección del audio y no devuelve un dato: cambia el estado de la muestra.
     * Si es el mismo archivo, pausa o continúa; si es otro, detiene el anterior y lo prepara.
     */
    fun alternar(uri: Uri) {
        if (cerrada) {
            return
        }

        if (uriActual == uri) {
            if (preparando) {
                return
            }

            val turno = generacion.get()

            if (reproduciendo) {
                reproduciendo = false
                alCambiar()

                // El botón cambia enseguida; el reproductor se pausa en su trabajo separado.
                colaAudio.post {
                    if (turno == generacion.get()) {
                        try {
                            reproductor?.pause()
                            colaAudio.removeCallbacks(actualizarTiempo)
                            sonido.abandonAudioFocusRequest(solicitudFoco)
                        } catch (_: IllegalStateException) {
                            terminar(turno, fallo = true)
                        }
                    }
                }
            } else {
                colaAudio.post {
                    iniciar(turno)
                }
            }

            return
        }

        detener()
        uriActual = uri
        preparando = true
        val turno = generacion.get()
        alCambiar()

        colaAudio.post {
            preparar(uri, turno)
        }
    }

    /**
     * alternar la programa en el trabajo de audio para abrir un archivo sin congelar botones.
     * Recibe su dirección y su número de turno; no devuelve un dato.
     * MediaPlayer es el reproductor de Android y avisa cuando está listo, termina o falla.
     */
    private fun preparar(uri: Uri, turno: Int) {
        if (turno != generacion.get()) {
            return
        }

        liberarReproductor()
        val nuevo = MediaPlayer()
        reproductor = nuevo
        generacionReproductor = turno

        try {
            nuevo.setAudioAttributes(atributos)

            // Estas funciones de aviso también se llaman callbacks: Android las ejecuta
            // cuando termina una operación. Se comprueba que todavía correspondan al audio actual.
            nuevo.setOnPreparedListener { preparado ->
                if (reproductor === preparado && turno == generacion.get()) {
                    iniciar(turno)
                }
            }

            nuevo.setOnCompletionListener { terminado ->
                if (reproductor === terminado) {
                    terminar(turno, fallo = false)
                }
            }

            nuevo.setOnErrorListener { fallido, _, _ ->
                if (reproductor === fallido) {
                    terminar(turno, fallo = true)
                }

                true
            }

            nuevo.setDataSource(contextoAplicacion, uri)

            if (turno == generacion.get()) {
                nuevo.prepareAsync()
            } else {
                liberarReproductor()
            }
        } catch (_: Exception) {
            terminar(turno, fallo = true)
        }
    }

    /**
     * alternar la llama al reanudar y preparar la llama cuando Android termina de abrir el audio.
     * Recibe el número de turno y no devuelve un dato: pide el permiso temporal de audio e inicia.
     * Si el permiso falla, termina la muestra y avisa a la pantalla.
     */
    private fun iniciar(turno: Int) {
        if (turno != generacion.get()) {
            return
        }

        val actual = reproductor ?: return

        if (sonido.requestAudioFocus(solicitudFoco) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            terminar(turno, fallo = true)
            return
        }

        try {
            actual.start()

            publicar(turno) {
                preparando = false
                reproduciendo = true
                alCambiar()
            }

            colaAudio.removeCallbacks(actualizarTiempo)
            colaAudio.post(actualizarTiempo)
        } catch (_: IllegalStateException) {
            terminar(turno, fallo = true)
        }
    }

    /**
     * actualizarTiempo, iniciar y terminar la usan para cambiar datos visibles de forma segura.
     * Recibe el turno y la acción que debe ejecutarse en el trabajo principal; no devuelve un dato.
     * Solo ejecuta esa acción si la pantalla sigue abierta y el audio todavía es el actual.
     */
    private fun publicar(turno: Int, accion: () -> Unit) {
        principal.post {
            if (!cerrada && turno == generacion.get()) {
                accion()
            }
        }
    }

    /**
     * La revisión del tiempo y las respuestas del reproductor la llaman al terminar o fallar.
     * Recibe el turno y si hubo un fallo; no devuelve un dato.
     * Libera el audio correspondiente y comunica el estado detenido a la pantalla.
     */
    private fun terminar(turno: Int, fallo: Boolean) {
        if (generacionReproductor == turno) {
            liberarReproductor()
        }

        publicar(turno) {
            uriActual = null
            reproduciendo = false
            preparando = false
            segundos = 0
            alCambiar()

            if (fallo) {
                alFallar()
            }
        }
    }

    /**
     * preparar, terminar, detener y cerrar la usan dentro del trabajo de audio.
     * No recibe argumentos ni devuelve un dato: elimina avisos pendientes y libera el reproductor.
     * Quitar primero sus funciones de aviso evita que un audio cerrado siga enviando respuestas.
     */
    private fun liberarReproductor() {
        colaAudio.removeCallbacks(actualizarTiempo)
        val anterior = reproductor
        reproductor = null
        ultimoSegundoPublicado = 0
        anterior?.setOnPreparedListener(null)
        anterior?.setOnCompletionListener(null)
        anterior?.setOnErrorListener(null)
        anterior?.release()
        sonido.abandonAudioFocusRequest(solicitudFoco)
    }

    /**
     * La pantalla la llama al salir; alternar y el aviso de foco también pueden llamarla.
     * No recibe argumentos ni devuelve un dato: marca la muestra como detenida y libera su audio.
     * Cambiar el turno impide que una apertura antigua vuelva a activar la reproducción.
     */
    fun detener() {
        if (cerrada) {
            return
        }

        generacion.incrementAndGet()
        uriActual = null
        reproduciendo = false
        preparando = false
        segundos = 0

        colaAudio.post {
            liberarReproductor()
        }

        alCambiar()
    }

    /**
     * ActividadSelectorMusica la llama cuando Android destruye la pantalla.
     * No recibe argumentos ni devuelve un dato: detiene el audio y finaliza su trabajo separado.
     * También retira actualizaciones pendientes para que no intenten dibujar una pantalla cerrada.
     */
    fun cerrar() {
        if (cerrada) {
            return
        }

        detener()
        cerrada = true
        principal.removeCallbacksAndMessages(null)

        colaAudio.post {
            liberarReproductor()
            hiloAudio.quitSafely()
        }
    }
}
