package com.nowel.agendavip.dominio.descanso

import java.time.LocalDate

/**
 * Recuerda lo ocurrido durante una noche, aunque se reinicie el teléfono o pase medianoche.
 * fecha es el día en que comenzó esa noche. Los tiempos terminados en Hasta o Ultima son
 * milisegundos desde el 1 de enero de 1970, el formato que usa Android al programar alarmas.
 * milisegundosUso suma actividad observada en las aplicaciones elegidas; no mide el sueño.
 */
data class EstadoNocheDescanso(
    val fecha: LocalDate,
    val pausasUsadas: Int = 0,
    val pausaHasta: Long = 0,
    val cancelada: Boolean = false,
    val avisoPrevio: Boolean = false,
    val avisoInicio: Boolean = false,
    val vozUltima: Long = 0,
    val milisegundosUso: Long = 0,
)
