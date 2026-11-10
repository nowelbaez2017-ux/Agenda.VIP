package com.nowel.agendavip.seguridad

/**
 * Impide usar datos a medio recuperar si el teléfono no pudo terminar una restauración.
 * AplicacionAgenda guarda aquí el problema; Recuperación lo retira solo al reponer los datos.
 * Es un estado temporal en memoria, no borra archivos ni cierra la cuenta recordada.
 */
object EstadoArranque {

    @Volatile
    var problema: String? = null
}
