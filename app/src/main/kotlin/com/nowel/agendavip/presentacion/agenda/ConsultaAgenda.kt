package com.nowel.agendavip.presentacion.agenda

import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.enfoque.EventoEnfoque
import com.nowel.agendavip.dominio.agenda.EstadoOcurrencia
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.dominio.agenda.mismaVersionDeAgenda
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Nombra los tres tamaños de consulta que ofrecen los botones Día, Semana y Mes.
 * Solo cambia las fechas que se muestran; no modifica las tareas ni sus avisos.
 */
internal enum class PeriodoAgenda {
    DIA,
    SEMANA,
    MES;

    /**
     * Recibe la fecha elegida y devuelve el primer día que debe consultar el historial.
     * ConsultaAgenda la llama al preparar la lista: la semana siempre empieza en lunes
     * y el mes empieza en su día 1, aunque la fecha elegida esté en medio del período.
     */
    fun inicio(fecha: LocalDate): LocalDate {
        return when (this) {
            DIA -> fecha
            SEMANA -> fecha.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            MES -> fecha.withDayOfMonth(1)
        }
    }

    /**
     * Recibe la fecha elegida y devuelve el último día incluido en la consulta.
     * ConsultaAgenda la llama junto con inicio: la semana termina en domingo y el mes
     * usa su último día real, incluyendo febrero y los años de 366 días.
     */
    fun fin(fecha: LocalDate): LocalDate {
        return when (this) {
            DIA -> fecha
            SEMANA -> inicio(fecha).plusDays(6)
            MES -> fecha.with(TemporalAdjusters.lastDayOfMonth())
        }
    }

    /**
     * Recibe la fecha actual y la dirección, -1 para anterior o 1 para siguiente.
     * Devuelve una fecha del período vecino. Las flechas del historial la llaman;
     * para los meses parte del día 1 y así no pierde días al pasar por febrero.
     */
    fun desplazar(fecha: LocalDate, direccion: Long): LocalDate {
        return when (this) {
            DIA -> fecha.plusDays(direccion)
            SEMANA -> fecha.plusWeeks(direccion)
            MES -> fecha.withDayOfMonth(1).plusMonths(direccion)
        }
    }

    /**
     * Recibe la fecha consultada y devuelve un título breve para la barra del historial.
     * ActividadAgenda la llama al dibujar sus controles. Siempre incluye el año para
     * distinguir consultas antiguas; una semana puede cruzar de mes o de año.
     */
    fun etiqueta(fecha: LocalDate): String {
        val idioma = Locale("es", "DO")
        val fechaCorta = DateTimeFormatter.ofPattern("d MMM yyyy", idioma)
        val primerDia = inicio(fecha)
        val ultimoDia = fin(fecha)

        return when (this) {
            DIA -> primerDia.format(fechaCorta)
            MES -> primerDia.format(DateTimeFormatter.ofPattern("MMMM yyyy", idioma))
                .replaceFirstChar { it.titlecase(idioma) }
            SEMANA -> {
                val formatoInicio = when {
                    primerDia.year != ultimoDia.year -> fechaCorta
                    primerDia.month != ultimoDia.month -> DateTimeFormatter.ofPattern("d MMM", idioma)
                    else -> DateTimeFormatter.ofPattern("d", idioma)
                }
                "${primerDia.format(formatoInicio)} – ${ultimoDia.format(fechaCorta)}"
            }
        }
    }
}

/**
 * Une una tarea con el día que se está viendo y su estado actual de ese día.
 * ConsultaAgenda la prepara y ActividadAgenda muestra su hora, título y estado.
 * soloLectura distingue una fotografía del historial de una tarea que todavía se puede cambiar.
 * registradoEn permite explicar cuándo se conservó la fotografía, sin convertirla en otra alarma.
 */
internal data class FilaAgenda(
    val tarea: Tarea,
    val fecha: LocalDate,
    val estado: EstadoOcurrencia,
    val soloLectura: Boolean = false,
    val registradoEn: Long? = null
)

/**
 * Distingue las dos clases de fila que Agenda puede consultar sin mezclar sus almacenes.
 * Una lista cerrada de tipos, llamada sealed class en Kotlin, permite dibujar cada origen
 * con su propio detalle. Inicio sigue usando únicamente FilaAgenda y sus tareas normales.
 */
internal sealed class FilaHistorialAgenda {

    abstract val fecha: LocalDate
    abstract val hora: LocalTime

    /**
     * Guarda una tarea del historial y su aviso si todavía está previsto.
     * Al pasar su hora, la consulta retira el aviso de la fila y la pantalla la actualiza.
     */
    data class Recordatorio(
        val dato: FilaAgenda,
        val proximoAviso: Long? = null,
    ) : FilaHistorialAgenda() {
        override val fecha: LocalDate = dato.fecha
        override val hora: LocalTime = dato.tarea.hora
    }

    /**
     * Resume una actividad de Enfoque en un día. Los eventos son hechos registrados ese día,
     * no el estado actual de la actividad aplicado a fechas anteriores. Si no hay eventos,
     * representa únicamente la hora programada y la duración prevista.
     */
    data class Enfoque(
        val actividadId: String,
        val titulo: String,
        override val fecha: LocalDate,
        override val hora: LocalTime,
        val duracionMinutos: Int,
        val eventos: List<EventoEnfoque>,
    ) : FilaHistorialAgenda()
}

/**
 * Reúne el resultado temporal de una lectura para actualizar la pantalla de una sola vez.
 * Inicio recibe la fecha, las tarjetas del día y el próximo aviso programado.
 * Agenda recibe solo las filas de su historial. El resultado vive en la pantalla.
 */
internal data class ResultadoConsultaAgenda(
    val hoy: LocalDate,
    val deHoy: List<FilaAgenda>,
    val proximoAviso: FilaAgenda?,
    val historial: List<FilaHistorialAgenda>,
)

/**
 * Lee las tareas y prepara sus fechas sin bloquear los botones de la pantalla.
 * Recibe el repositorio existente; ActividadAgenda la usa desde su trabajo secundario,
 * es decir, una tarea de Android que calcula fuera del trabajo que dibuja la pantalla.
 */
internal class ConsultaAgenda(
    private val repositorio: RepositorioTareas,
    private val enfoques: RepositorioEnfoque,
) {

    /**
     * Recibe el día y período elegidos, el día actual y la hora de la consulta.
     * ActividadAgenda la llama para mostrar Inicio o el historial. Inicio prepara solo
     * las tarjetas y el siguiente aviso. El historial reúne las tareas y Enfoque del período.
     */
    fun consultar(
        fecha: LocalDate,
        periodo: PeriodoAgenda,
        hoy: LocalDate,
        ahora: Long,
        mostrarHistorial: Boolean,
    ): ResultadoConsultaAgenda {
        comprobarContinuacion()
        if (!mostrarHistorial) {
            return consultarInicio(hoy, ahora)
        }

        val primerDia = periodo.inicio(fecha)
        val ultimoDia = periodo.fin(fecha)
        val (tareas, estadosPorTarea, fotografias) = repositorio.leerParaAgenda(primerDia, ultimoDia)
        val diasConFotografia = fotografias.mapTo(mutableSetOf()) { it.tarea.id to it.estado.fecha }
        val filas = mutableListOf<FilaAgenda>()

        for (tarea in tareas) {
            comprobarContinuacion()
            val estados = estadosPorTarea[tarea.id].orEmpty().associateBy { it.fecha }
            var dia = primerDia

            while (!dia.isAfter(ultimoDia)) {
                comprobarContinuacion()

                if (tarea.correspondeA(dia)) {
                    val estado = estados[dia] ?: EstadoOcurrencia(tarea.id, dia)

                    // Una edición de hoy no debe cambiar el nombre ni el horario de ayer.
                    // Si ya hay fotografía de ese día, se añadirá después con sus datos originales.
                    val yaEstaEnElPasado = dia.isBefore(hoy)
                    val hayFotografia = (tarea.id to dia) in diasConFotografia
                    if (!estado.omitida && !(yaEstaEnElPasado && hayFotografia)) {
                        filas.add(FilaAgenda(tarea, dia, estado, soloLectura = yaEstaEnElPasado))
                    }
                }

                dia = dia.plusDays(1)
            }
        }

        val historial = mutableListOf<FilaHistorialAgenda>()
        historial.addAll(filas.map { fila ->
            comprobarContinuacion()
            val estado = fila.estado
            val aviso = estado.alarmaProgramadaPara?.takeIf { momento ->
                !fila.soloLectura && momento > ahora && !estado.completada && !estado.seguimientoDetenido
            }
            FilaHistorialAgenda.Recordatorio(fila, aviso)
        })

        // Un registro solo puede coincidir con la fila del mismo id y fecha.
        val filasPorDia = filas.associateBy { it.tarea.id to it.fecha }
        fotografias.forEach { registro ->
            comprobarContinuacion()
            val fila = filasPorDia[registro.tarea.id to registro.estado.fecha]
            val filaActual = fila != null && mismaVersionDeAgenda(fila.tarea, registro.tarea)

            // La misma versión activa ya muestra el estado actual. Las versiones anteriores
            // y las tareas eliminadas permanecen como registros que se consultan sin editar.
            if (!filaActual) {
                historial.add(FilaHistorialAgenda.Recordatorio(FilaAgenda(
                    tarea = registro.tarea,
                    fecha = registro.estado.fecha,
                    estado = registro.estado,
                    soloLectura = true,
                    registradoEn = registro.registradoEn
                )))
            }
        }

        historial.addAll(consultarEnfoque(primerDia, ultimoDia))
        return ResultadoConsultaAgenda(
            hoy = hoy,
            deHoy = emptyList(),
            proximoAviso = null,
            historial = historial.sortedWith(compareBy<FilaHistorialAgenda> { it.fecha }.thenBy { it.hora }),
        )
    }

    /**
     * Recibe el día y la hora usados en la cabecera de Inicio. Devuelve las tarjetas
     * de ese día y el primer aviso futuro. consultar la llama solo en Inicio.
     * Una tarea pospuesta desde ayer puede ser el próximo aviso aunque su tarjeta sea de ayer.
     */
    private fun consultarInicio(hoy: LocalDate, ahora: Long): ResultadoConsultaAgenda {
        val (tareas, estadosPorTarea) = repositorio.leerParaAgenda()
        val deHoy = mutableListOf<FilaAgenda>()
        var proximo: FilaAgenda? = null

        for (tarea in tareas) {
            comprobarContinuacion()
            val estados = estadosPorTarea[tarea.id].orEmpty()

            if (tarea.correspondeA(hoy)) {
                val estadoHoy = estados.firstOrNull { it.fecha == hoy } ?: EstadoOcurrencia(tarea.id, hoy)
                if (!estadoHoy.omitida) {
                    deHoy.add(FilaAgenda(tarea, hoy, estadoHoy))
                }
            }

            for (estado in estados) {
                comprobarContinuacion()
                val instante = estado.alarmaProgramadaPara ?: continue
                if (instante <= ahora || estado.completada || estado.omitida ||
                    estado.seguimientoDetenido || !tarea.correspondeA(estado.fecha)
                ) {
                    continue
                }

                val anterior = proximo?.estado?.alarmaProgramadaPara
                if (anterior == null || instante < anterior) {
                    proximo = FilaAgenda(tarea, estado.fecha, estado)
                }
            }
        }

        return ResultadoConsultaAgenda(
            hoy = hoy,
            deHoy = deHoy.sortedWith(compareBy<FilaAgenda> { it.tarea.hora }.thenBy { it.tarea.titulo }),
            proximoAviso = proximo,
            historial = emptyList(),
        )
    }

    /**
     * Recibe los límites del período y devuelve como máximo una fila de Enfoque por actividad y día.
     * consultar la llama solo para Agenda. Agrupa hechos reales como empezar, posponer o completar.
     * No muestra Creada como otra tarjeta y no repite una actividad programada si ya tiene hechos
     * de ese mismo día. Una actividad completada mañana no aparece completada retroactivamente ayer.
     */
    private fun consultarEnfoque(desde: LocalDate, hasta: LocalDate): List<FilaHistorialAgenda.Enfoque> {
        comprobarContinuacion()
        val zona = ZoneId.systemDefault()
        val actividades = enfoques.listar().associateBy { it.id }
        val eventos = enfoques.eventos().filter { evento ->
            comprobarContinuacion()
            val fecha = Instant.ofEpochMilli(evento.ocurrioEn).atZone(zona).toLocalDate()
            evento.tipo.name !in setOf("CREADA", "RECUPERADA") && fecha in desde..hasta
        }.groupBy { evento ->
            evento.actividadId to Instant.ofEpochMilli(evento.ocurrioEn).atZone(zona).toLocalDate()
        }
        val resultado = linkedMapOf<Pair<String, LocalDate>, FilaHistorialAgenda.Enfoque>()

        eventos.forEach { (clave, hechos) ->
            comprobarContinuacion()
            val ordenados = hechos.sortedBy { it.ocurrioEn }
            val ultimo = ordenados.last()
            val actividad = actividades[clave.first]
            val momento = Instant.ofEpochMilli(ultimo.ocurrioEn).atZone(zona)
            resultado[clave] = FilaHistorialAgenda.Enfoque(
                actividadId = clave.first,
                titulo = ultimo.titulo,
                fecha = clave.second,
                hora = momento.toLocalTime(),
                duracionMinutos = actividad?.duracionMinutos ?: 0,
                eventos = ordenados,
            )
        }

        actividades.values.forEach { actividad ->
            comprobarContinuacion()
            val momento = Instant.ofEpochMilli(actividad.avisoEnEpochMs).atZone(zona)
            val fecha = momento.toLocalDate()
            val clave = actividad.id to fecha

            val terminada = actividad.estado == EstadoEnfoque.COMPLETADA ||
                actividad.estado == EstadoEnfoque.CANCELADA

            // Una actividad terminada se muestra por sus hechos reales, no como si siguiera programada.
            if (!terminada && fecha in desde..hasta && !resultado.containsKey(clave)) {
                resultado[clave] = FilaHistorialAgenda.Enfoque(
                    actividadId = actividad.id,
                    titulo = actividad.titulo,
                    fecha = fecha,
                    hora = momento.toLocalTime(),
                    duracionMinutos = actividad.duracionMinutos,
                    eventos = emptyList(),
                )
            }
        }

        return resultado.values.toList()
    }

    /**
     * No recibe datos ni devuelve un valor: detiene una consulta que ya no se necesita.
     * consultar la llama entre tareas y días. Si la actividad salió o pidió otra fecha,
     * Android interrumpe ese trabajo y aquí se evita seguir calculando el resto del mes.
     */
    private fun comprobarContinuacion() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("La consulta ya no está visible.")
        }
    }
}
