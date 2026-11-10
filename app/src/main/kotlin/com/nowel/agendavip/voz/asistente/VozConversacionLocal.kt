package com.nowel.agendavip.voz.asistente

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.voz.VocesLocales

/**
 * Lee preguntas y avisos con la voz del teléfono y comunica cuándo termina.
 * Recibe Android. Los servicios de Enfoque, Descanso y Límites de uso llaman a esta clase.
 * Usa exclusivamente una voz española instalada que no necesite Internet.
 * [usoAudio] indica qué volumen se usa: conversación multimedia o preguntas de alarma.
 * ServicioEnfoque elige alarma para que sus preguntas respeten el volumen de alarmas del teléfono.
 */
class VozConversacionLocal(
    contexto: Context,
    private val usoAudio: Int = AudioAttributes.USAGE_MEDIA,
    // Descanso entrega una comprobación para descartar una frase si la cuenta o la noche cambió.
    // Las demás llamadas pueden omitir la comprobación y usar el valor inicial true.
    private val permitirHablar: () -> Boolean = { true },
) {

    private val contexto = contexto.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private val audio = this.contexto.getSystemService(AudioManager::class.java)
    private var motor: TextToSpeech? = null
    private var preparado = false
    private var cerrada = false
    private var turno = 0
    private var textoPendiente: String? = null
    private var respuesta: ((Boolean) -> Unit)? = null
    private var foco: AudioFocusRequest? = null
    private var limite: Runnable? = null

    /**
     * Recibe la frase y una respuesta que indica true si se pudo leer hasta el final.
     * Enfoque la usa antes de reabrir el micrófono para no transcribir la voz de Android.
     * La lectura se limita a 600 caracteres.
     */
    fun hablar(texto: String, alTerminar: (Boolean) -> Unit) {
        detener()

        if (cerrada) {
            alTerminar(false)
            return
        }

        textoPendiente = texto.take(600)
        respuesta = alTerminar
        val version = turno
        limite = Runnable {
            if (turno == version) {
                terminar(false)
            }
        }.also {
            principal.postDelayed(it, 60_000L)
        }

        if (preparado) {
            pronunciar()
        } else if (motor == null) {
            motor = TextToSpeech(contexto) { resultado ->
                principal.post {
                    if (cerrada) {
                        return@post
                    }
                    preparado = resultado == TextToSpeech.SUCCESS

                    if (preparado) {
                        pronunciar()
                    } else {
                        terminar(false)
                        try {
                            motor?.shutdown()
                        } catch (_: RuntimeException) {
                            // Android pudo haber desconectado ya el motor de voz.
                        }
                        motor = null
                    }
                }
            }
        }
    }

    /**
     * hablar y la preparación de Android la llaman para leer la frase pendiente.
     * No recibe ni devuelve datos: al acabar se notificará a la respuesta registrada en hablar.
     */
    private fun pronunciar() {
        if (!permitirHablar()) {
            terminar(false)
            return
        }
        val texto = textoPendiente ?: return
        val lector = motor ?: return
        val version = turno

        try {
            val preferencias = PreferenciasAgenda(contexto)
            val voz = VocesLocales.elegir(lector, preferencias.nombreVoz)

            if (voz == null || lector.setVoice(voz) != TextToSpeech.SUCCESS) {
                terminar(false)
                return
            }

            val atributos = AudioAttributes.Builder()
                .setUsage(usoAudio)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            lector.setAudioAttributes(atributos)
            val solicitud = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(atributos)
                .setOnAudioFocusChangeListener({ cambio ->
                    if (cambio < 0 && version == turno) {
                        terminar(false)
                    }
                }, principal)
                .build()
            foco = solicitud

            if (audio.requestAudioFocus(solicitud) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                terminar(false)
                return
            }

            lector.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                /** Android avisa al empezar. El servicio espera el final antes de seguir. */
                override fun onStart(id: String?) = Unit

                /** Android informa del final; el turno ignora una lectura antigua ya cancelada. */
                override fun onDone(id: String?) {
                    principal.post {
                        if (version == turno && id == "voz_$version") {
                            terminar(true)
                        }
                    }
                }

                /** Un fallo informa al servicio de que la lectura quedó incompleta. */
                @Deprecated("Compatibilidad con los motores de voz de Android.")
                override fun onError(id: String?) {
                    principal.post {
                        if (version == turno) {
                            terminar(false)
                        }
                    }
                }
            })
            textoPendiente = null
            val opciones = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferencias.volumenAvisos)
            }

            // El cambio de cuenta espera este mismo turno. Así una preparación de voz
            // tardía no pronuncia datos de una persona que ya cerró su sesión.
            synchronized(com.nowel.agendavip.datos.cuentas.EspacioPersonal.cerrojoSesion) {
                if (!permitirHablar() ||
                    lector.speak(texto, TextToSpeech.QUEUE_FLUSH, opciones, "voz_$version") == TextToSpeech.ERROR) {
                    terminar(false)
                }
            }
        } catch (_: RuntimeException) {
            terminar(false)
        }
    }

    /**
     * Recibe el resultado de lectura, suelta el sonido y entrega la respuesta una sola vez.
     * Los avisos del motor y el tiempo máximo llaman aquí; no modifica mensajes ni tareas.
     */
    private fun terminar(exito: Boolean) {
        val avisar = respuesta
        respuesta = null
        detener()
        avisar?.invoke(exito)
    }

    /**
     * Los servicios la llaman para cancelar la lectura y devolver la prioridad de sonido.
     * Retira la respuesta pendiente para que cancelar no reabra el micrófono.
     */
    fun detener() {
        turno += 1
        respuesta = null
        textoPendiente = null
        limite?.let(principal::removeCallbacks)
        limite = null
        try {
            motor?.stop()
        } catch (_: RuntimeException) {
            // Un motor desconectado no debe cerrar la pantalla ni el servicio de avisos.
        }
        foco?.let { solicitud ->
            try {
                audio.abandonAudioFocusRequest(solicitud)
            } catch (_: RuntimeException) {
                // Android pudo retirar ya la prioridad de sonido.
            }
        }
        foco = null
    }

    /** Al terminar el servicio libera el lector de Android y descarta respuestas tardías. */
    fun cerrar() {
        cerrada = true
        detener()
        try {
            motor?.shutdown()
        } catch (_: RuntimeException) {
            // Siempre se retira la referencia aunque el motor ya se haya cerrado.
        }
        motor = null
        principal.removeCallbacksAndMessages(null)
    }
}
