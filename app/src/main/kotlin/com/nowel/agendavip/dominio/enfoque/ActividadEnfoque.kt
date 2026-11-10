package com.nowel.agendavip.dominio.enfoque

import java.util.UUID

/** Cada estado explica qué puede hacer la persona; agotar el tiempo nunca significa completar. */
enum class EstadoEnfoque {
    PENDIENTE,
    ESPERANDO_INICIO,
    EN_CURSO,
    PAUSADA,
    ESPERANDO_RESPUESTA,
    COMPLETADA,
    CANCELADA,
}

/**
 * Es una actividad propia de Enfoque, separada de los recordatorios normales de Inicio.
 * Las horas Epoch son milisegundos desde 1970. Elapsed es el reloj que cuenta desde el
 * último encendido y no cambia cuando se corrige la hora del teléfono.
 * restanteMs conserva el tiempo al comenzar un bloque; GestorEnfoque calcula lo que queda.
 * acumuladoTrabajadoMs suma los bloques que ya terminaron o se pausaron, sin contar pausas.
 */
data class ActividadEnfoque(
    val id: String = UUID.randomUUID().toString(),
    val titulo: String,
    val creadaEn: Long,
    val avisoEnEpochMs: Long,
    val duracionMinutos: Int,
    val estado: EstadoEnfoque = EstadoEnfoque.PENDIENTE,
    val restanteMs: Long = duracionMinutos * 60_000L,
    val finEnEpochMs: Long? = null,
    val finEnElapsedMs: Long? = null,
    val arranqueSistema: Int = -1,
    val actualizadaEn: Long = creadaEn,
    val acumuladoTrabajadoMs: Long = 0L,
    val musicaUri: String? = null,
    val musicaNombre: String = "",
)

/** Son hechos reales del historial; una actividad programada todavía no es un hecho completado. */
enum class TipoEventoEnfoque {
    CREADA,
    EDITADA,
    AVISO_INICIO,
    INICIADA,
    PAUSADA,
    CONTINUADA,
    POSPUESTA,
    TIEMPO_TERMINADO,
    COMPLETADA,
    CANCELADA,
}

/**
 * Guarda qué ocurrió, cuándo y con qué actividad. Conserva su título en ese momento,
 * para que editar el nombre mañana no cambie lo que se consultó en el historial de ayer.
 */
data class EventoEnfoque(
    val id: String = UUID.randomUUID().toString(),
    val actividadId: String,
    val titulo: String,
    val tipo: TipoEventoEnfoque,
    val ocurrioEn: Long,
    val detalle: String = "",
    val trabajadoAcumuladoMs: Long = 0L,
)