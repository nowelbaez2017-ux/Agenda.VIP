package com.nowel.agendavip.dominio.agenda

/**
 * Conserva cómo se llamaba un recordatorio, su horario y su estado en una fecha concreta.
 * Es una fotografía de datos, no una alarma nueva: la consulta de Agenda la muestra sin editarla.
 * El repositorio la guarda antes de cambiar o borrar una tarea y al registrar decisiones o avisos.
 * Dos horarios distintos del mismo día pueden conservarse por separado si el recordatorio se editó.
 * registradoEn contiene el momento en que se guardó esta fotografía, en milisegundos para Android.
 */
data class RegistroHistorialTarea(
    val tarea: Tarea,
    val estado: EstadoOcurrencia,
    val registradoEn: Long
)

/**
 * Recibe dos versiones de una tarea y devuelve si tienen el mismo nombre y calendario.
 * El historial la usa para distinguir una edición del horario de un simple cambio de música o voz.
 * No compara el estado: completar o posponer actualiza el registro de esa misma versión.
 */
fun mismaVersionDeAgenda(primera: Tarea, segunda: Tarea): Boolean {
    return primera.id == segunda.id && primera.titulo == segunda.titulo &&
        primera.fecha == segunda.fecha && primera.hora == segunda.hora &&
        primera.repeticion == segunda.repeticion && primera.diasSemana == segunda.diasSemana
}
