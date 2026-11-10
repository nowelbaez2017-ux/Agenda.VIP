package com.nowel.agendavip.presentacion.comun

import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.Tarea
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reúne las formas de escribir fechas, horas y repeticiones que usa la agenda.
 * Las pantallas de inicio, los formularios y los detalles pueden mostrar así los mismos textos.
 */
object FormatoAgenda {

    // Este idioma permite escribir los nombres de días y meses en español de República Dominicana.
    private val idioma = Locale("es", "DO")

    /**
     * Convierte una hora en un texto de doce horas, como "3:05 p. m.".
     * Recibe un LocalTime, que guarda la hora y los minutos sin una fecha.
     * Devuelve el texto que las pantallas de la agenda muestran al usuario.
     * Se usa este formato para mantener la misma presentación aunque Android use otro ajuste.
     */
    fun hora(valor: LocalTime): String {
        // El resto de dividir entre 12 convierte la tarde al reloj de doce horas.
        // Cuando ese resto da cero, se muestra 12 para la medianoche y el mediodía.
        val horas = if (valor.hour % 12 == 0) 12 else valor.hour % 12
        val minutos = valor.minute.toString().padStart(2, '0')
        val periodo = if (valor.hour < 12) "a. m." else "p. m."

        return "$horas:$minutos $periodo"
    }

    /**
     * Escribe una fecha con el nombre del día y del mes para que sea fácil reconocerla.
     * Recibe un LocalDate, que guarda año, mes y día sin incluir una hora.
     * Devuelve un texto como "Lunes, 5 de enero", con la primera letra en mayúscula.
     * Las pantallas de la agenda lo usan para presentar las fechas guardadas.
     */
    fun fecha(valor: LocalDate): String = valor.format(
        DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", idioma),
    ).replaceFirstChar {
        it.titlecase(idioma)
    }

    /**
     * Explica en palabras cada cuánto se repite una tarea.
     * Recibe la tarea completa para leer su tipo de repetición y sus días de la semana.
     * Devuelve el texto que las pantallas de la agenda colocan junto a esa tarea.
     *
     * Los días guardados usan 1 para lunes y 7 para domingo. La lista de nombres comienza
     * en la posición 0; por eso se resta 1 al buscar el nombre de cada día.
     */
    fun repeticion(tarea: Tarea): String = when (tarea.repeticion) {
        Repeticion.UNA_VEZ -> "Solo una vez"

        Repeticion.DIARIA -> "Todos los días"

        Repeticion.DIAS_SEMANA -> tarea.diasSemana.sorted().joinToString(", ") {
            listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")[it - 1]
        }
    }
}
