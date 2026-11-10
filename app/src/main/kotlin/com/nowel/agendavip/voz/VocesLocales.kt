package com.nowel.agendavip.voz

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Reúne las voces españolas que ya están instaladas y funcionan sin conexión.
 * Configuración ofrece estas voces; las muestras y los recordatorios usan la misma elección.
 */
object VocesLocales {

    /**
     * Recibe el motor de voz ya preparado por Android y devuelve sus voces españolas disponibles.
     * Un motor es el programa del teléfono que convierte texto en sonido.
     * Se descartan las voces que necesitan conexión o que aún necesitan instalarse.
     */
    fun disponibles(motor: TextToSpeech): List<Voice> {
        return try {
            motor.voices.orEmpty()
                .filter { voz ->
                    voz.locale.language == "es" &&
                        !voz.isNetworkConnectionRequired &&
                        voz.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                .sortedWith(compareBy<Voice> { it.locale.toLanguageTag() }.thenBy { it.name })
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /**
     * Recibe el motor y el nombre de la voz preferida; devuelve esa voz si sigue instalada.
     * Si no está, elige otra voz española disponible, primero de la región del teléfono.
     * Puede devolver null si falta una voz local; el aviso explicará ese problema.
     */
    fun elegir(motor: TextToSpeech, preferida: String?): Voice? {
        val voces = disponibles(motor)
        return voces.firstOrNull { it.name == preferida }
            ?: voces.firstOrNull { it.locale.country == Locale.getDefault().country }
            ?: voces.firstOrNull()
    }

    /**
     * Recibe una voz y su posición en la lista y devuelve un nombre sencillo para Configuración.
     * Se muestra su región y un número para distinguir voces del mismo idioma.
     * El nombre interno del proveedor se conserva aparte y no aparece en la pantalla.
     */
    fun etiqueta(voz: Voice, posicion: Int): String {
        val region = voz.locale.getDisplayCountry(Locale("es")).ifBlank { "Español" }
        return "$region · Voz ${posicion + 1}"
    }
}