package com.nowel.agendavip.dominio.descanso

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Reúne las decisiones que la persona toma en la pantalla Descanso.
 * LocalTime representa una hora del día sin fecha. DayOfWeek representa un día de la semana.
 * Los días elegidos pertenecen al comienzo de la noche: el lunes incluye su madrugada del martes.
 * Crear este objeto no activa permisos ni empieza a vigilar aplicaciones.
 */
data class ConfiguracionDescanso(
    val activado: Boolean = false,
    val inicio: LocalTime = LocalTime.of(20, 30),
    val fin: LocalTime = LocalTime.of(5, 0),
    val dias: Set<DayOfWeek> = DayOfWeek.values().toSet(),
    val aplicaciones: Set<String> = emptySet(),
    val mensaje: String = "",
    val vozActiva: Boolean = true,
    val restriccionActiva: Boolean = true,
    val pausaPermitida: Boolean = true,
)
