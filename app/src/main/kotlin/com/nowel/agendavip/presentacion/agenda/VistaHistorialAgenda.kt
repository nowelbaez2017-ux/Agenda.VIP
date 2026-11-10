package com.nowel.agendavip.presentacion.agenda

import android.app.DatePickerDialog
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.FormatoAgenda
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Crea los controles pequeños y las filas de consulta de la sección Agenda.
 * Recibe contexto para dibujar y abrirDetalles para responder al toque de una fila.
 * ActividadAgenda la usa después de leer las tareas; esta clase no lee ni guarda datos.
 */
internal class VistaHistorialAgenda(
    private val contexto: Context,
    private val abrirDetalles: (String, LocalDate) -> Unit,
    private val abrirEnfoque: (String) -> Unit,
) {

    /**
     * Recibe fecha y período actuales, más las acciones para cambiar cada uno.
     * Devuelve una columna con Día/Semana/Mes, flechas, rango, Hoy e Ir a fecha.
     * La actividad la coloca antes de las filas. El calendario de Android solo aparece
     * al tocar Ir a fecha y sirve para ubicarse, sin sustituir la lista cronológica.
     */
    fun controles(
        fecha: LocalDate,
        periodo: PeriodoAgenda,
        cambiarPeriodo: (PeriodoAgenda) -> Unit,
        cambiarFecha: (LocalDate) -> Unit
    ): LinearLayout {
        val controles = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
        }
        val filtros = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val opciones = listOf(
            PeriodoAgenda.DIA to R.string.agenda_filtro_dia,
            PeriodoAgenda.SEMANA to R.string.agenda_filtro_semana,
            PeriodoAgenda.MES to R.string.agenda_filtro_mes
        )

        for ((indice, opcion) in opciones.withIndex()) {
            val (valor, texto) = opcion
            filtros.addView(boton(contexto, contexto.getString(texto)) {
                cambiarPeriodo(valor)
            }.apply {
                isSelected = periodo == valor
                typeface = Typeface.create(
                    "serif",
                    if (isSelected) Typeface.BOLD else Typeface.NORMAL
                )
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (indice > 0) {
                        marginStart = ComponentesAgenda.dp(contexto, 8)
                    }
                }
            })
        }

        controles.addView(filtros)
        val navegacion = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ComponentesAgenda.dp(contexto, 6), 0, 0)
        }
        navegacion.addView(boton(contexto, "‹") {
            cambiarFecha(periodo.desplazar(fecha, -1))
        }.apply {
            textSize = 28f
            background = contexto.getDrawable(R.drawable.agenda_fondo_fila)
            contentDescription = contexto.getString(R.string.agenda_periodo_anterior)
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 48), -2)
        })
        navegacion.addView(texto(periodo.etiqueta(fecha), 16f, titulo = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        navegacion.addView(boton(contexto, "›") {
            cambiarFecha(periodo.desplazar(fecha, 1))
        }.apply {
            textSize = 28f
            background = contexto.getDrawable(R.drawable.agenda_fondo_fila)
            contentDescription = contexto.getString(R.string.agenda_periodo_siguiente)
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 48), -2)
        })
        controles.addView(navegacion)

        val ubicacion = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        ubicacion.addView(boton(contexto, contexto.getString(R.string.agenda_hoy)) {
            cambiarFecha(LocalDate.now())
        }.apply {
            background = contexto.getDrawable(R.drawable.agenda_fondo_fila)
        })
        ubicacion.addView(boton(contexto, contexto.getString(R.string.agenda_ir_fecha)) {
            DatePickerDialog(
                contexto,
                { _, ano, mes, dia ->
                    cambiarFecha(LocalDate.of(ano, mes + 1, dia))
                },
                fecha.year,
                fecha.monthValue - 1,
                fecha.dayOfMonth
            ).show()
        }.apply {
            background = contexto.getDrawable(R.drawable.agenda_fondo_fila)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = ComponentesAgenda.dp(contexto, 12)
            }
        })
        controles.addView(ubicacion)
        controles.addView(separador())
        return controles
    }

    /**
     * Recibe el contenedor, las filas y las acciones para continuar y terminar el dibujo.
     * ActividadAgenda la llama al terminar la lectura. Solo crea un encabezado cuando
     * hay tareas en ese día; una lista vacía muestra ayuda sin inventar fechas ni tareas.
     * Dibuja bloques pequeños para dejar disponibles los controles. No cambia estados.
     */
    fun mostrarFilas(
        contenedor: LinearLayout,
        filas: List<FilaHistorialAgenda>,
        consultaVigente: () -> Boolean,
        alTerminar: () -> Unit
    ) {
        if (!consultaVigente()) {
            return
        }

        if (filas.isEmpty()) {
            contenedor.addView(texto(contexto.getString(R.string.agenda_historial_vacio), 21f, titulo = true).apply {
                setPadding(0, ComponentesAgenda.dp(contexto, 24), 0, ComponentesAgenda.dp(contexto, 8))
            })
            contenedor.addView(texto(
                contexto.getString(R.string.agenda_historial_vacio_ayuda),
                15f,
                R.color.texto_secundario
            ))
            alTerminar()
            return
        }

        agregarBloque(contenedor, filas, 0, null, consultaVigente, alTerminar)
    }

    /**
     * Recibe el contenedor, las filas, la posición inicial y la última fecha ya dibujada.
     * consultaVigente comprueba que la persona siga mirando esta consulta; alTerminar
     * avisa a la actividad para recuperar el desplazamiento cuando toda la lista esté lista.
     * mostrarFilas y esta misma función la llaman para añadir como máximo 18 filas por turno,
     * dejando que Android atienda los botones entre bloques. No devuelve un resultado.
     */
    private fun agregarBloque(
        contenedor: LinearLayout,
        filas: List<FilaHistorialAgenda>,
        desde: Int,
        ultimaFecha: LocalDate?,
        consultaVigente: () -> Boolean,
        alTerminar: () -> Unit
    ) {
        if (!consultaVigente()) {
            return
        }

        var fechaAnterior = ultimaFecha
        val hasta = minOf(desde + 18, filas.size)
        val idioma = Locale("es", "DO")
        val formato = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", idioma)

        for (indice in desde until hasta) {
            val fila = filas[indice]

            if (fechaAnterior != fila.fecha) {
                val fecha = fila.fecha.format(formato).replaceFirstChar { it.titlecase(idioma) }
                contenedor.addView(texto(fecha, 15f, titulo = true).apply {
                    setPadding(0, ComponentesAgenda.dp(contexto, 20), 0, ComponentesAgenda.dp(contexto, 8))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isAccessibilityHeading = true
                    }
                })
                fechaAnterior = fila.fecha
            }

            contenedor.addView(crearFila(fila))
            contenedor.addView(separador())
        }

        if (hasta < filas.size) {
            contenedor.post {
                agregarBloque(contenedor, filas, hasta, fechaAnterior, consultaVigente, alTerminar)
            }
        } else {
            alTerminar()
        }
    }

    /**
     * Recibe una fila del historial y devuelve su vista según su origen.
     * Los recordatorios abren sus detalles habituales; Enfoque abre su propia actividad.
     * Esta distinción evita que una fila de Enfoque cambie una tarea del almacén normal.
     */
    private fun crearFila(dato: FilaHistorialAgenda): LinearLayout {
        return when (dato) {
            is FilaHistorialAgenda.Recordatorio -> crearFilaRecordatorio(dato)
            is FilaHistorialAgenda.Enfoque -> crearFilaEnfoque(dato)
        }
    }

    /**
     * Recibe el resumen de una actividad de Enfoque en una fecha y devuelve una fila de consulta.
     * Usa el último hecho de ese día para su resultado; no toma el estado actual de otro día.
     * Tiempo terminado pide respuesta: únicamente el hecho Completada se dibuja como completado.
     */
    private fun crearFilaEnfoque(dato: FilaHistorialAgenda.Enfoque): LinearLayout {
        val ultimo = dato.eventos.lastOrNull()
        val estado = ultimo?.let { etiquetaEvento(it.tipo.name) } ?: "Programada"
        val completada = ultimo?.tipo?.name == "COMPLETADA"
        val color = when {
            completada -> R.color.agenda_completada
            ultimo == null || ultimo.tipo.name == "CANCELADA" -> R.color.texto_secundario
            else -> R.color.agenda_pendiente
        }
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, ComponentesAgenda.dp(contexto, 16), 0, ComponentesAgenda.dp(contexto, 16))
            background = contexto.getDrawable(R.drawable.agenda_fondo_fila)
            minimumHeight = ComponentesAgenda.dp(contexto, 76)
            isFocusable = true
            contentDescription = "Enfoque: ${dato.titulo}, ${FormatoAgenda.hora(dato.hora)}, $estado. Abrir detalles"
            setOnClickListener {
                abrirEnfoque(dato.actividadId)
            }
        }
        fila.addView(texto(FormatoAgenda.hora(dato.hora), 17f, titulo = true).apply {
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 72), -2)
        })
        fila.addView(texto(if (completada) "✓" else "○", 25f, color).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 34), -2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val informacion = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = ComponentesAgenda.dp(contexto, 4)
            }
            addView(texto("Enfoque · ${dato.titulo}", 18f, titulo = true))
            addView(texto(estado, 14f, color).apply {
                setPadding(0, ComponentesAgenda.dp(contexto, 3), 0, 0)
            })
        }
        val detalle = ultimo?.detalle?.takeIf { it.isNotBlank() }
            ?: if (ultimo == null) "Duración prevista: ${dato.duracionMinutos} min." else ""

        if (detalle.isNotBlank()) {
            informacion.addView(texto(detalle, 13f, R.color.texto_secundario))
        }

        if (ultimo != null) {
            informacion.addView(texto(
                "Tiempo trabajado acumulado hasta ese momento: ${formatearTiempoTrabajado(ultimo.trabajadoAcumuladoMs)}",
                12f,
                R.color.texto_secundario,
            ))
        }

        if (dato.eventos.size > 1) {
            val recorrido = dato.eventos.map { etiquetaEvento(it.tipo.name) }.distinct().joinToString(" · ")
            informacion.addView(texto(recorrido, 12f, R.color.texto_secundario))
        }

        fila.addView(informacion)
        fila.addView(texto("›", 23f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 24), -2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return fila
    }

    /**
     * Recibe el nombre guardado de un hecho y devuelve una etiqueta sencilla en español.
     * crearFilaEnfoque la usa tanto para el resultado del día como para sus pasos registrados.
     */
    private fun etiquetaEvento(tipo: String): String {
        return when (tipo) {
            "CREADA" -> "Creada"
            "EDITADA" -> "Actividad editada"
            "AVISO_INICIO" -> "Aviso para comenzar"
            "INICIADA" -> "Sesión iniciada"
            "PAUSADA" -> "Sesión pausada"
            "CONTINUADA" -> "Sesión continuada"
            "POSPUESTA" -> "Recordatorio pospuesto"
            "TIEMPO_TERMINADO" -> "Tiempo terminado · Falta tu respuesta"
            "COMPLETADA" -> "Completada"
            "CANCELADA" -> "Cancelada"
            else -> "Actividad actualizada"
        }
    }

    /**
     * Recibe el tiempo realmente acumulado al registrar un hecho y devuelve horas, minutos y segundos.
     * La fila de Enfoque lo usa como fotografía de ese momento, no como el total actual de otro día.
     * No calcula tiempo a partir de la duración prevista ni afirma que todo el acumulado se hizo ese día.
     */
    private fun formatearTiempoTrabajado(milisegundos: Long): String {
        val total = milisegundos.coerceAtLeast(0L) / 1000L
        val horas = total / 3600L
        val minutos = (total % 3600L) / 60L
        val segundos = total % 60L

        return when {
            horas > 0L -> "$horas h $minutos min"
            minutos > 0L -> "$minutos min $segundos s"
            else -> "$segundos s"
        }
    }

    /**
     * Recibe el recordatorio y su próximo aviso calculado por ConsultaAgenda.
     * Devuelve una fila con hora, nombre y estado.
     * mostrarFilas la usa para construir la lista. Pendiente lleva círculo rojo y Completada
     * lleva marca azul, junto a palabras para reconocerlas sin depender del color.
     * Tocar cualquier parte abre Detalles para esa fecha; no hay interruptores ni creación.
     */
    private fun crearFilaRecordatorio(recordatorio: FilaHistorialAgenda.Recordatorio): LinearLayout {
        val dato = recordatorio.dato
        val completada = dato.estado.completada
        val colorEstado = if (completada) R.color.agenda_completada else R.color.agenda_pendiente
        val estado = if (dato.estado.omitida) {
            "Omitida en esta fecha"
        } else {
            contexto.getString(
                if (completada) R.string.agenda_estado_completada else R.string.agenda_estado_pendiente
            )
        }
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, ComponentesAgenda.dp(contexto, 16), 0, ComponentesAgenda.dp(contexto, 16))
            background = contexto.getDrawable(R.drawable.agenda_fondo_fila)
            minimumHeight = ComponentesAgenda.dp(contexto, 76)
            isFocusable = true
            contentDescription = "${dato.tarea.titulo}, ${FormatoAgenda.hora(dato.tarea.hora)}, $estado. Abrir detalles"
            setOnClickListener {
                if (dato.soloLectura) {
                    mostrarDetalleGuardado(dato)
                } else {
                    abrirDetalles(dato.tarea.id, dato.fecha)
                }
            }
        }
        fila.addView(texto(FormatoAgenda.hora(dato.tarea.hora), 17f, titulo = true).apply {
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 72), -2)
        })
        fila.addView(texto(if (completada) "✓" else "○", 25f, colorEstado).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 34), -2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val informacion = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = ComponentesAgenda.dp(contexto, 4)
            }
            addView(texto(dato.tarea.titulo, 19f, titulo = true))
            addView(texto(estado, 14f, colorEstado).apply {
                setPadding(0, ComponentesAgenda.dp(contexto, 3), 0, 0)
            })
        }
        if (dato.soloLectura) {
            informacion.addView(texto("Historial · Solo consulta", 12f, R.color.texto_secundario))
        }
        val momento = recordatorio.proximoAviso

        if (momento != null) {
            val aviso = Instant.ofEpochMilli(momento).atZone(ZoneId.systemDefault())
            val hora = FormatoAgenda.hora(aviso.toLocalTime())
            val fechaAviso = aviso.toLocalDate()
            val cuando = if (fechaAviso == dato.fecha) {
                hora
            } else {
                "${fechaAviso.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es", "DO")))} · $hora"
            }
            informacion.addView(texto(
                contexto.getString(R.string.agenda_aviso_planificado, cuando),
                12f,
                R.color.texto_secundario
            ).apply {
                setPadding(0, ComponentesAgenda.dp(contexto, 4), 0, 0)
            })
        }

        fila.addView(informacion)
        fila.addView(texto("›", 23f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(contexto, 24), -2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return fila
    }

    /**
     * Recibe una fotografía del historial y abre sus detalles sin botones para editar o eliminar.
     * Las filas antiguas la llaman porque la tarea actual puede tener otro nombre o ya no existir.
     * ScrollView permite desplazar el texto si no cabe; este diálogo no reproduce ni programa avisos.
     */
    private fun mostrarDetalleGuardado(dato: FilaAgenda) {
        val contenido = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            val espacio = ComponentesAgenda.dp(contexto, 20)
            setPadding(espacio, espacio, espacio, espacio)
            setBackgroundColor(contexto.getColor(R.color.marfil))
        }
        contenido.addView(texto(dato.tarea.titulo, 24f, titulo = true))
        contenido.addView(texto(FormatoAgenda.fecha(dato.fecha), 16f))
        contenido.addView(texto(FormatoAgenda.hora(dato.tarea.hora), 22f, titulo = true))
        contenido.addView(texto(FormatoAgenda.repeticion(dato.tarea), 15f))
        contenido.addView(ComponentesAgenda.espacio(contexto, 16))

        val estado = when {
            dato.estado.omitida -> "Omitida en esta fecha"
            dato.estado.completada -> "Completada"
            else -> "Pendiente en el registro"
        }
        contenido.addView(texto(estado, 18f, titulo = true))
        contenido.addView(texto("Avisos activados: ${dato.estado.avisosEmitidos}", 15f))
        dato.estado.ultimaAlerta?.let { momento ->
            contenido.addView(texto("Último aviso activado: ${momentoLegible(momento)}", 15f))
        }
        dato.estado.pospuestaHasta?.let { momento ->
            contenido.addView(texto("Posposición solicitada hasta: ${momentoLegible(momento)}", 15f))
        }
        dato.registradoEn?.let { momento ->
            contenido.addView(texto("Registro guardado: ${momentoLegible(momento)}", 13f, R.color.texto_secundario))
        }

        contenido.addView(ComponentesAgenda.espacio(contexto, 16))
        contenido.addView(texto("Mensaje del aviso", 18f, titulo = true))
        contenido.addView(texto(
            if (dato.tarea.vozActiva) dato.tarea.mensajeVoz.ifBlank { dato.tarea.titulo } else "Voz desactivada",
            15f
        ))
        contenido.addView(texto(
            "Música: ${dato.tarea.descripcionMusica()}",
            15f
        ))
        contenido.addView(ComponentesAgenda.espacio(contexto, 16))
        contenido.addView(texto(
            "Esta información conserva el recordatorio de esa fecha. Para cambiar próximos avisos abre la tarea actual desde Inicio.",
            14f,
            R.color.texto_secundario
        ))

        val desplazamiento = ScrollView(contexto).apply {
            addView(contenido)
        }
        AlertDialog.Builder(contexto)
            .setTitle("Detalle del historial")
            .setView(desplazamiento)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Recibe un momento guardado y devuelve su fecha y hora de doce horas en la zona del teléfono. */
    private fun momentoLegible(momento: Long): String {
        val local = Instant.ofEpochMilli(momento).atZone(ZoneId.systemDefault())

        return "${FormatoAgenda.fecha(local.toLocalDate())}, ${FormatoAgenda.hora(local.toLocalTime())}"
    }

    /**
     * Recibe texto, tamaño y color, y devuelve un texto con la letra serif de la opción 1.
     * Las funciones de esta clase la llaman para títulos, fechas, horas y estados.
     * titulo decide si se usa negrita; el tamaño respeta la letra elegida en el teléfono.
     */
    private fun texto(
        valor: String,
        tamano: Float,
        color: Int = R.color.ciruela,
        titulo: Boolean = false
    ): TextView {
        return ComponentesAgenda.texto(contexto, valor, tamano, color, titulo).apply {
            typeface = Typeface.create("serif", if (titulo) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * No recibe datos y devuelve una línea fina para separar filas sin dibujar tarjetas.
     * controles y mostrarFilas la añaden para conservar el aspecto limpio del historial.
     */
    private fun separador(): View {
        return View(contexto).apply {
            setBackgroundColor(contexto.getColor(R.color.borde_campo))
            layoutParams = LinearLayout.LayoutParams(-1, ComponentesAgenda.dp(contexto, 1))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    companion object {
        /**
         * Recibe contexto, etiqueta y acción; devuelve un botón pequeño con letra serif.
         * controles lo usa en sus barras y ActividadAgenda en Nueva tarea y el aviso de permisos.
         * Su alto mínimo facilita tocarlo y puede crecer si la persona usa letras grandes.
         */
        fun boton(contexto: Context, etiqueta: String, accion: () -> Unit): Button {
            return Button(contexto).apply {
                text = etiqueta
                textSize = 15f
                typeface = Typeface.create("serif", Typeface.NORMAL)
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = ComponentesAgenda.dp(contexto, 48)
                minimumHeight = ComponentesAgenda.dp(contexto, 48)
                setTextColor(contexto.getColor(R.color.ciruela))
                background = contexto.getDrawable(R.drawable.agenda_fondo_filtro)
                backgroundTintList = null
                setPadding(
                    ComponentesAgenda.dp(contexto, 12),
                    ComponentesAgenda.dp(contexto, 8),
                    ComponentesAgenda.dp(contexto, 12),
                    ComponentesAgenda.dp(contexto, 8)
                )
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                setOnClickListener {
                    accion()
                }
            }
        }
    }
}



