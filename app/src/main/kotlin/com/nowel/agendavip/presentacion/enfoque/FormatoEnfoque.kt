package com.nowel.agendavip.presentacion.enfoque

import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reúne las etiquetas de Enfoque para que la lista y sus detalles expliquen lo mismo. */
internal object FormatoEnfoque {

    /** Recibe el estado guardado y devuelve una etiqueta comprensible para las tarjetas y detalles. */
    fun estado(estado: EstadoEnfoque): String = when (estado) {
        EstadoEnfoque.PENDIENTE -> "Aviso programado"
        EstadoEnfoque.ESPERANDO_INICIO -> "Es hora de comenzar"
        EstadoEnfoque.EN_CURSO -> "Sesión en marcha"
        EstadoEnfoque.PAUSADA -> "Sesión pausada"
        EstadoEnfoque.ESPERANDO_RESPUESTA -> "¿Terminaste o continuamos?"
        EstadoEnfoque.COMPLETADA -> "Completada"
        EstadoEnfoque.CANCELADA -> "Cancelada"
    }

    /** Recibe milisegundos restantes y devuelve minutos y segundos; nunca muestra tiempo negativo. */
    fun contador(milisegundos: Long): String {
        val segundos = ((milisegundos.coerceAtLeast(0L) + 999L) / 1000L)
        val horas = segundos / 3600L
        val minutos = (segundos % 3600L) / 60L
        val resto = segundos % 60L

        return if (horas > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", horas, minutos, resto)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutos, resto)
        }
    }

    /** Recibe una hora guardada y devuelve fecha y hora de doce horas según la zona del teléfono. */
    fun fechaHora(momento: Long): String = Instant.ofEpochMilli(momento)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM · h:mm a", Locale("es", "DO")))
}