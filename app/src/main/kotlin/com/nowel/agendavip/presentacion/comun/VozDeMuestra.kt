package com.nowel.agendavip.presentacion.comun

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.voz.VocesLocales

/**
 * Permite escuchar una frase de prueba con una voz en español instalada en el teléfono.
 * El motor de voz es el programa de Android que convierte un texto en sonido hablado.
 * Esta clase elige una voz que puede funcionar sin conexión.
 *
 * Al crearla, la pantalla entrega un contexto para acceder a Android y la función avisar,
 * que mostrará un mensaje si no se puede preparar o reproducir la voz.
 * La pantalla usa hablar para escuchar la muestra y cerrar cuando deja de necesitarla.
 */
class VozDeMuestra(contexto: Context, private val avisar: (String) -> Unit) {

    private val contextoLocal = contexto.applicationContext

    // El hilo principal es el trabajo donde Android atiende la pantalla y sus botones.
    // Los avisos del motor vuelven aquí para coordinar sus cambios con la pantalla.
    private val principal = Handler(Looper.getMainLooper())
    private val audio = contextoLocal.getSystemService(AudioManager::class.java)

    private var motor: TextToSpeech? = null
    private var preparado = false
    private var cerrada = false
    private var textoPendiente: String? = null
    private var solicitudFoco: AudioFocusRequest? = null

    /**
     * Recibe el texto que se quiere escuchar al tocar el botón de muestra de voz.
     * La pantalla que ofrece esa muestra llama a esta función.
     * No devuelve un valor: prepara el motor si hace falta y después reproduce el texto.
     *
     * La preparación de Android puede tardar. Se guarda la frase mientras termina,
     * de modo que la persona no necesite tocar otra vez el botón para escucharla.
     */
    fun hablar(texto: String) {
        if (cerrada || texto.isBlank()) {
            return
        }

        textoPendiente = texto

        if (preparado) {
            pronunciarPendiente()
        } else if (motor == null) {
            motor = TextToSpeech(contextoLocal) { resultado ->
                // Android entrega el resultado de preparación cuando el motor está listo.
                principal.post {
                    prepararMotor(resultado)
                }
            }
        }
    }

    /**
     * Comprueba si Android pudo iniciar el motor y selecciona una voz en español instalada.
     * Recibe el número de resultado que Android entregó al preparar el motor.
     * No devuelve un valor: deja la voz lista y reproduce la frase pendiente, o avisa del problema.
     * El bloque de respuesta creado en hablar la llama cuando termina la preparación.
     */
    private fun prepararMotor(resultado: Int) {
        if (cerrada) {
            return
        }

        val sintetizador = motor ?: return

        if (resultado != TextToSpeech.SUCCESS) {
            avisar("No se pudo preparar la voz del teléfono. Revisa Configuración de voz.")
            textoPendiente = null
            sintetizador.shutdown()
            motor = null

            return
        }

        sintetizador.language = Locale("es", "ES")

        // Se busca una voz española que no dependa de internet y que ya esté instalada.
        val voz = VocesLocales.elegir(
            sintetizador,
            PreferenciasAgenda(contextoLocal).nombreVoz,
        )

        if (voz == null || sintetizador.setVoice(voz) != TextToSpeech.SUCCESS) {
            avisar("Necesitas una voz en español instalada para escuchar avisos sin conexión.")
            textoPendiente = null
            sintetizador.shutdown()
            motor = null

            return
        }

        // Se indica a Android que reproduciremos voz como sonido de una aplicación.
        sintetizador.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )

        // Este grupo de funciones recibe avisos del motor cuando la frase empieza,
        // termina o falla. El motor puede avisar desde otro hilo: un trabajo separado
        // que permite preparar o reproducir la voz sin congelar los botones.
        sintetizador.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            /**
             * Android la llama al comenzar la frase y entrega su identificador en id.
             * Aquí no hace falta cambiar nada al comenzar; no se devuelve ningún valor.
             */
            override fun onStart(id: String?) = Unit

            /**
             * Android la llama al terminar la frase y entrega su identificador en id.
             * No devuelve un valor. Encarga al hilo principal que libere la prioridad de sonido
             * pedida para esta muestra, para que otras aplicaciones sigan usando el audio.
             */
            override fun onDone(id: String?) {
                principal.post {
                    liberarFoco()
                }
            }

            /**
             * Android la llama si falla la reproducción y entrega el identificador de la frase.
             * No devuelve un valor. Libera la prioridad de sonido y avisa a la pantalla
             * si esta muestra todavía no se ha cerrado.
             */
            @Deprecated("Compatibilidad con motores antiguos de Android")
            override fun onError(id: String?) {
                principal.post {
                    liberarFoco()

                    if (!cerrada) {
                        avisar("No se pudo reproducir la muestra de voz.")
                    }
                }
            }
        })

        preparado = true
        pronunciarPendiente()
    }

    /**
     * Reproduce el texto que hablar dejó guardado mientras se preparaba el motor.
     * No recibe parámetros ni devuelve un valor; lee y limpia textoPendiente.
     * La llaman hablar cuando la voz ya está lista y prepararMotor al terminar de prepararla.
     *
     * Antes de hablar solicita el foco de audio: una prioridad temporal que Android
     * organiza entre las aplicaciones que quieren reproducir sonido.
     * Esta función reproduce una muestra; no programa un recordatorio.
     */
    private fun pronunciarPendiente() {
        val texto = textoPendiente ?: return
        textoPendiente = null

        // Se vuelve a leer la opción por si cambió mientras esta pantalla estaba abierta.
        val sintetizador = motor ?: return
        val preferencias = PreferenciasAgenda(contextoLocal)
        val vozElegida = VocesLocales.elegir(sintetizador, preferencias.nombreVoz)
        if (vozElegida == null || sintetizador.setVoice(vozElegida) != TextToSpeech.SUCCESS) {
            avisar("Necesitas una voz en español instalada para escuchar la muestra.")
            return
        }

        liberarFoco()

        val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { cambio ->
                // Un cambio negativo indica que Android ha retirado esa prioridad de sonido.
                if (cambio < 0) {
                    principal.post {
                        detener()
                    }
                }
            }
            .build()

        solicitudFoco = foco

        if (audio.requestAudioFocus(foco) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            liberarFoco()
            avisar("El teléfono no puede reproducir la muestra en este momento.")

            return
        }

        // Este volumen solo multiplica la muestra; no cambia el volumen de otras apps.
        val opcionesVoz = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferencias.volumenAvisos)
        }

        // QUEUE_FLUSH indica que esta frase sustituye a otra que el motor tuviera pendiente.
        if (
            motor?.speak(texto, TextToSpeech.QUEUE_FLUSH, opcionesVoz, "muestra_agenda") ==
            TextToSpeech.ERROR
        ) {
            liberarFoco()
            avisar("No se pudo reproducir la muestra de voz.")
        }
    }

    /**
     * Cancela la frase pendiente y detiene la muestra que pudiera estar sonando.
     * No recibe parámetros ni devuelve un valor.
     * La usan cerrar y la respuesta a la pérdida de prioridad de sonido; también está
     * disponible para que la pantalla detenga la muestra cuando lo necesite.
     */
    fun detener() {
        textoPendiente = null
        motor?.stop()
        liberarFoco()
    }

    /**
     * Devuelve a Android la prioridad de sonido que se pidió para esta muestra.
     * No recibe parámetros ni devuelve un valor; usa la solicitud guardada, si existe.
     * La usan detener, pronunciarPendiente y los avisos de fin o error de reproducción
     * para no conservar esa prioridad cuando la muestra ya no la necesita.
     */
    private fun liberarFoco() {
        solicitudFoco?.let {
            audio.abandonAudioFocusRequest(it)
        }

        solicitudFoco = null
    }

    /**
     * Detiene la muestra y libera el motor de voz cuando ya no se necesita.
     * No recibe parámetros ni devuelve un valor.
     * La Activity, es decir, la pantalla de Android que creó esta muestra, la llama
     * al destruirse para no dejar la voz ni los trabajos pendientes de esta clase activos.
     */
    fun cerrar() {
        cerrada = true
        detener()

        motor?.shutdown()
        motor = null

        principal.removeCallbacksAndMessages(null)
    }
}
