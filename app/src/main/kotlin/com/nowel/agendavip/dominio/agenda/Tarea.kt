package com.nowel.agendavip.dominio.agenda

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Indica si una tarea sucede una sola vez, todos los días o en ciertos días de la semana.
 * El formulario guarda la opción elegida y el calendario la usa para decidir en qué fechas mostrarla.
 */
enum class Repeticion {
    UNA_VEZ,
    DIARIA,
    DIAS_SEMANA
}

/**
 * Reúne lo que la persona escribe al crear una tarea y cómo quiere recibir sus avisos.
 * Una rutina es una tarea que se repite; cada fecha concreta de esa rutina se llama ocurrencia.
 * [EstadoOcurrencia] guarda por separado lo ocurrido en cada fecha, como completarla o posponerla.
 *
 * [diasSemana] usa números del 1 al 7: lunes es 1 y domingo es 7.
 * [limiteAvisos] incluye el primer aviso y los recordatorios posteriores del mismo día.
 * [musicaUri] guarda la URI, es decir, la dirección que Android da al archivo de música elegido.
 * [modoMusica] distingue una elección aleatoria, una canción concreta y la decisión de no usar música.
 * El formulario crea estos datos; el calendario, los detalles y los recordatorios los consultan.
 */
data class Tarea(
    val id: String = UUID.randomUUID().toString(),
    val titulo: String,
    val fecha: LocalDate,
    val hora: LocalTime,
    val repeticion: Repeticion = Repeticion.UNA_VEZ,
    val diasSemana: Set<Int> = emptySet(),
    val vozActiva: Boolean = true,
    val mensajeVoz: String = "",
    val musicaUri: String? = null,
    val musicaNombre: String = "",
    val seguimientoActivo: Boolean = false,
    val intervaloMinutos: Int = 5,
    val limiteAvisos: Int = 3,
    val mensajeSeguimiento: String = "",
    val modoMusica: ModoMusica = if (musicaUri.isNullOrBlank()) {
        ModoMusica.ALEATORIA
    } else {
        ModoMusica.SELECCIONADA
    }
) {

    /**
     * Devuelve el texto que se mostrará y se leerá en voz alta para esta tarea.
     * Recibe [esSeguimiento]: true elige el mensaje de un aviso posterior al primero.
     * Si la persona dejó ese mensaje vacío, devuelve una frase que incluye el título.
     * Lo usan la muestra del formulario, el calendario, los detalles y el sonido del recordatorio
     * para presentar el mismo mensaje en todos esos lugares.
     */
    fun mensajeParaAviso(esSeguimiento: Boolean): String =
        if (esSeguimiento) {
            mensajeSeguimiento.trim().ifEmpty { "Sigue pendiente: $titulo." }
        } else {
            mensajeVoz.trim().ifEmpty { "Es hora de $titulo." }
        }

    /**
     * El gestor de recordatorios la llama para decidir si debe preparar sonido musical.
     * No recibe argumentos y devuelve true si se pidió una canción aleatoria o un archivo concreto.
     * Una dirección vacía no basta para pedir silencio: «Sin música» tiene su propia opción.
     */
    fun tieneMusica(): Boolean {
        return when (modoMusica) {
            ModoMusica.ALEATORIA -> true
            ModoMusica.SIN_MUSICA -> false
            ModoMusica.SELECCIONADA -> !musicaUri.isNullOrBlank()
        }
    }

    /**
     * Las pantallas de detalle e historial la llaman para explicar la decisión musical guardada.
     * No recibe argumentos y devuelve un texto corto, sin abrir ni reproducir canciones.
     * En modo aleatorio no inventa un nombre: el archivo se elige cuando llega el aviso.
     */
    fun descripcionMusica(): String {
        return when (modoMusica) {
            ModoMusica.ALEATORIA -> "Canción aleatoria del teléfono"
            ModoMusica.SIN_MUSICA -> "Sin música"
            ModoMusica.SELECCIONADA -> musicaNombre.ifBlank {
                "Canción seleccionada"
            }
        }
    }

    /**
     * Recibe una [fecha] y devuelve true si la tarea debe aparecer en ese día.
     * Comprueba la fecha inicial y la repetición elegida para evitar días anteriores al comienzo.
     * El calendario y el gestor de recordatorios la llaman al mostrar o programar una tarea.
     */
    fun correspondeA(fecha: LocalDate): Boolean {
        if (fecha.isBefore(this.fecha)) {
            return false
        }

        return when (repeticion) {
            Repeticion.UNA_VEZ -> fecha == this.fecha
            Repeticion.DIARIA -> true
            Repeticion.DIAS_SEMANA -> fecha.dayOfWeek.value in diasSemana
        }
    }
}

/**
 * Guarda cómo va una tarea en una fecha concreta, llamada ocurrencia.
 * Por ejemplo, completar la rutina del lunes no completa la del martes.
 *
 * El gestor de recordatorios actualiza estos datos al completar, detener o posponer un aviso.
 * Los momentos guardados son milisegundos contados desde el 1 de enero de 1970; Android usa
 * esa forma de expresar la hora para recuperar avisos después de reiniciar el teléfono.
 * [alarmaProgramadaPara] también permite reconocer un aviso antiguo y descartarlo.
 * [ultimaAlerta] conserva cuándo Android activó el último aviso de esa fecha; editar la hora
 * no borra ese dato. No confirma que la persona haya escuchado la voz o la música.
 * [avisosEmitidos] cuenta los avisos de la programación actual y puede empezar otra vez en cero.
 */
data class EstadoOcurrencia(
    val tareaId: String,
    val fecha: LocalDate,
    val completada: Boolean = false,
    val omitida: Boolean = false,
    val seguimientoDetenido: Boolean = false,
    val pospuestaHasta: Long? = null,
    val avisosEmitidos: Int = 0,
    val ultimaAlerta: Long? = null,
    val alarmaProgramadaPara: Long? = null,
    val alarmaEsSeguimiento: Boolean = false
)
