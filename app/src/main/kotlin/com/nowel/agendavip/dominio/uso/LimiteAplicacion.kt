package com.nowel.agendavip.dominio.uso

/**
 * Guarda el objetivo diario que la persona eligió para una aplicación.
 * paquete es el identificador de Android, por ejemplo com.google.android.youtube.
 * La pantalla lo edita y el servicio lo compara con los minutos de uso del día.
 */
data class LimiteAplicacion(
    val paquete: String,
    val nombre: String,
    val minutosDiarios: Int,
    val mensaje: String = "",
    val hablar: Boolean = false,
    val habilitado: Boolean = true
) {

    /** Devuelve el mensaje personal o una frase sencilla cuando no se escribió uno. */
    fun textoAviso(): String {
        return mensaje.ifBlank {
            "Llegaste a tu límite de " + minutosDiarios + " minutos en " + nombre +
                ". Recuerda las metas que elegiste para hoy."
        }
    }
}

/**
 * Resultado temporal de consultar Android: milisegundos de uso por aplicación y fecha.
 * No contiene mensajes, pantallas, búsquedas ni archivos de otras aplicaciones.
 */
data class UsoDiario(
    val fecha: java.time.LocalDate,
    val milisegundosPorPaquete: Map<String, Long>
)
