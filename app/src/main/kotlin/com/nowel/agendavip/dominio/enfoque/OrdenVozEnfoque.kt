package com.nowel.agendavip.dominio.enfoque

import java.text.Normalizer
import java.util.Locale

/** Las decisiones admitidas por voz afectan únicamente a la actividad de Enfoque seleccionada. */
enum class AccionVozEnfoque {
    CONFIRMAR,
    NEGAR,
    COMENZAR,
    POSPONER,
    COMPLETAR,
    CONTINUAR,
    PAUSAR,
    INDICAR_MINUTOS,
    DESCONOCIDA,
}

/** Reúne lo que se entendió. El servicio lo repite y pide confirmación antes de cambiar datos. */
data class OrdenVozEnfoque(val accion: AccionVozEnfoque, val minutos: Int? = null)

/**
 * Interpreta respuestas breves sobre una sesión, después de transcribirlas con Whisper.
 * No sustituye al modelo que escucha ni intenta conversar de cualquier tema.
 * Admite distintas maneras de comenzar, posponer, continuar y completar; nunca ejecuta una acción.
 */
object InterpreteVozEnfoque {

    private val unidades = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3,
        "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8,
        "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13,
        "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17,
        "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20, "veintiun" to 21,
        "veintiuno" to 21, "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24,
        "veinticinco" to 25, "veintiseis" to 26, "veintisiete" to 27,
        "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30, "cuarenta" to 40,
        "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80,
        "noventa" to 90, "cien" to 100, "ciento" to 100, "doscientos" to 200,
        "trescientos" to 300, "cuatrocientos" to 400,
    )

    /**
     * Recibe lo transcrito y devuelve una intención con sus minutos, si se identificaron.
     * El servicio usa DESCONOCIDA para preguntar de nuevo; no adivina una decisión por silencio.
     * Una respuesta que menciona posponer o continuar se trata antes que un «sí» dentro de ella.
     */
    fun interpretar(frase: String): OrdenVozEnfoque {
        val texto = Normalizer.normalize(frase.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val minutos = extraerMinutos(texto)

        return when {
            Regex("\\b(pospon|recuerd|avisame|avisa|mas tarde|despues|ocupad)").containsMatchIn(texto) ->
                OrdenVozEnfoque(AccionVozEnfoque.POSPONER, minutos)
            Regex("\\b(no he termin|no termine|no he complet|todavia no|aun no)").containsMatchIn(texto) ->
                OrdenVozEnfoque(AccionVozEnfoque.CONTINUAR, minutos)
            Regex("\\b(continu|seguir|sigamos|seguimos|mas tiempo|necesito mas|otro rato)").containsMatchIn(texto) ->
                OrdenVozEnfoque(AccionVozEnfoque.CONTINUAR, minutos)
            Regex("\\b(paus|descans)").containsMatchIn(texto) ->
                OrdenVozEnfoque(AccionVozEnfoque.PAUSAR)
            Regex("\\b(termin|complet|hecho|lista|listo|lo hice|ya esta)").containsMatchIn(texto) ->
                OrdenVozEnfoque(AccionVozEnfoque.COMPLETAR)
            Regex("\\b(empez|comenz|inici|arranc|vamos a darle)").containsMatchIn(texto) ->
                OrdenVozEnfoque(AccionVozEnfoque.COMENZAR)
            texto in setOf("si", "si por favor", "confirmo", "confirmar", "correcto", "de acuerdo", "vale", "hazlo", "adelante") ->
                OrdenVozEnfoque(AccionVozEnfoque.CONFIRMAR)
            texto in setOf("no", "no gracias", "cancelar", "cancela", "cancelalo", "no hagas eso") ->
                OrdenVozEnfoque(AccionVozEnfoque.NEGAR)
            minutos != null -> OrdenVozEnfoque(AccionVozEnfoque.INDICAR_MINUTOS, minutos)
            else -> OrdenVozEnfoque(AccionVozEnfoque.DESCONOCIDA)
        }
    }

    /**
     * Recibe palabras ya normalizadas y devuelve minutos. Admite cifras, palabras, media hora y horas.
     * Para evitar interpretar una hora del reloj como duración, rechaza frases «a las ocho».
     * El resultado se repite a la persona y debe confirmarse; nunca se aplica solo por reconocer un número.
     */
    private fun extraerMinutos(texto: String): Int? {
        if (Regex("\\ba las?\\b").containsMatchIn(texto)) {
            return null
        }
        if (texto.contains("media hora")) {
            return 30
        }
        if (texto.contains("cuarto de hora")) {
            return 15
        }
        val palabras = texto.split(' ')
        var total = 0L
        var encontro = false

        palabras.forEachIndexed { indice, palabra ->
            if (palabra in setOf("minuto", "minutos", "hora", "horas")) {
                val anteriores = palabras.take(indice).takeLastWhile {
                    it == "y" || it in unidades || it.toIntOrNull() != null
                }.filter { it != "y" }
                val cantidad = anteriores.mapNotNull { it.toLongOrNull() ?: unidades[it]?.toLong() }.sum()
                if (cantidad > 0) {
                    total += if (palabra.startsWith("hora")) cantidad * 60 else cantidad
                    encontro = true
                }
            }
        }

        if (encontro && Regex("horas? y media").containsMatchIn(texto)) {
            total += 30
        }

        return total.takeIf { encontro && it in 1L..1440L }?.toInt()
    }
}