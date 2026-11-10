package com.nowel.agendavip.dominio.descanso

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Describe un período de descanso con comienzo y final reales, incluida la zona horaria.
 * fechaNoche identifica la misma noche antes y después de las doce de la noche.
 * La usan el programador de alarmas, la pantalla de descanso y el historial.
 */
data class VentanaDescanso(
    val fechaNoche: LocalDate,
    val inicio: ZonedDateTime,
    val fin: ZonedDateTime,
)

/**
 * Hace las cuentas del horario sin depender de una pantalla ni de permisos de Android.
 * Esta separación permite comprobar fechas y mensajes sin activar restricciones.
 */
object ReglasDescanso {

    /**
     * Recibe la configuración y la hora actual con su zona; devuelve el período vigente o null.
     * null significa que Descanso está apagado o que ahora no corresponde a un día y hora elegidos.
     * Se consulta también el día anterior porque una noche puede terminar después de medianoche.
     */
    fun ventanaActual(
        configuracion: ConfiguracionDescanso,
        ahora: ZonedDateTime,
    ): VentanaDescanso? {
        validar(configuracion)

        if (!configuracion.activado) {
            return null
        }

        return listOf(ahora.toLocalDate().minusDays(1), ahora.toLocalDate())
            .mapNotNull { fecha -> crearVentana(configuracion, fecha, ahora) }
            .firstOrNull { ventana ->
                !ahora.isBefore(ventana.inicio) && ahora.isBefore(ventana.fin)
            }
    }

    /**
     * Recibe las opciones y la hora actual; devuelve la noche vigente o la próxima programada.
     * La pantalla puede usarla como vista previa incluso con Descanso apagado.
     * Revisa dos semanas para saltar un intervalo eliminado por un cambio excepcional de horario.
     */
    fun proximaVentana(
        configuracion: ConfiguracionDescanso,
        ahora: ZonedDateTime,
    ): VentanaDescanso {
        validar(configuracion)

        val vigentes = configuracion.copy(activado = true)
        ventanaActual(vigentes, ahora)?.let { ventana ->
            return ventana
        }

        for (desplazamiento in 0L..14L) {
            val fecha = ahora.toLocalDate().plusDays(desplazamiento)
            val ventana = crearVentana(configuracion, fecha, ahora) ?: continue

            if (!ventana.inicio.isBefore(ahora)) {
                return ventana
            }
        }

        error("No se pudo encontrar el próximo horario de descanso.")
    }

    /**
     * Recibe horario, momento actual y nombre opcional; devuelve un mensaje listo para leer.
     * Calcula tiempo disponible hasta levantarse. No afirma que la persona vaya a dormir todo
     * ese tiempo ni garantiza cómo se sentirá al trabajar. La voz y la pantalla usan el mismo texto.
     */
    fun mensajeDescanso(
        configuracion: ConfiguracionDescanso,
        ahora: ZonedDateTime,
        nombre: String = "",
    ): String {
        val ventana = proximaVentana(configuracion, ahora)
        val minutos = Duration.between(ahora, ventana.fin).toMinutes().coerceAtLeast(0)
        val horas = minutos / 60
        val restantes = minutos % 60
        val duracion = when {
            minutos == 0L -> "menos de un minuto"
            horas == 0L -> "$restantes ${if (restantes == 1L) "minuto" else "minutos"}"
            restantes == 0L -> "$horas ${if (horas == 1L) "hora" else "horas"}"
            else -> "$horas ${if (horas == 1L) "hora" else "horas"} y " +
                "$restantes ${if (restantes == 1L) "minuto" else "minutos"}"
        }
        val horaFinal = ventana.fin.format(
            DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("es-DO")),
        )
        val saludo = nombre.trim().takeIf { it.isNotEmpty() }?.let { "$it, " } ?: ""
        val personal = configuracion.mensaje.trim().ifEmpty {
            "Dale espacio al descanso antes de tu próxima jornada."
        }

        return "${saludo}es hora de descansar. Si te acuestas ahora, tienes $duracion " +
            "disponibles hasta las $horaFinal para descansar. $personal"
    }

    /**
     * Recibe un día de comienzo y devuelve su período real, o null si ese día no está elegido.
     * atZone aplica las reglas de la zona del teléfono, incluidos los cambios estacionales.
     * Si un salto del reloj elimina el intervalo completo, lo omite en lugar de inventar otra noche.
     */
    private fun crearVentana(
        configuracion: ConfiguracionDescanso,
        fecha: LocalDate,
        ahora: ZonedDateTime,
    ): VentanaDescanso? {
        if (fecha.dayOfWeek !in configuracion.dias) {
            return null
        }

        val inicio = fecha.atTime(configuracion.inicio).atZone(ahora.zone)
        val fechaFinal = if (configuracion.fin.isAfter(configuracion.inicio)) {
            fecha
        } else {
            fecha.plusDays(1)
        }
        val fin = fechaFinal.atTime(configuracion.fin).atZone(ahora.zone)

        if (!fin.isAfter(inicio)) {
            return null
        }

        return VentanaDescanso(fecha, inicio, fin)
    }

    /**
     * Recibe las opciones antes de calcular o guardar; rechaza horarios ambiguos y datos excesivos.
     * El formulario y el archivo guardado comparten estas reglas, evitando diferencias entre ambos.
     */
    fun validar(configuracion: ConfiguracionDescanso) {
        require(configuracion.inicio != configuracion.fin) {
            "El comienzo y el final del descanso deben tener horas diferentes."
        }
        require(configuracion.inicio.second == 0 && configuracion.inicio.nano == 0 &&
            configuracion.fin.second == 0 && configuracion.fin.nano == 0) {
            "Elige las horas de descanso en horas y minutos."
        }
        require(configuracion.dias.isNotEmpty()) {
            "Selecciona al menos un día para descansar."
        }
        require(configuracion.aplicaciones.size <= 200) {
            "Puedes seleccionar hasta 200 aplicaciones."
        }
        require(configuracion.aplicaciones.all { paquete ->
            paquete.length in 1..300 && paquete.matches(Regex("[a-zA-Z0-9_.]+"))
        }) {
            "La lista de aplicaciones contiene un nombre no válido."
        }
        require(configuracion.mensaje.length <= 500) {
            "El mensaje personal puede tener hasta 500 caracteres."
        }
    }
}
