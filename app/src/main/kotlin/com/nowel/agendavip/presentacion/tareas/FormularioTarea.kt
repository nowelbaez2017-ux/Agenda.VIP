package com.nowel.agendavip.presentacion.tareas

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.presentacion.comun.FormatoAgenda
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Lee y muestra los campos del diseño tarea_pantalla_formulario.xml.
 * Un borrador son los datos que estás escribiendo y aún no has guardado.
 * Recibe la pantalla, los datos iniciales y lo recordado si giraste el teléfono.
 * También recibe dos acciones: abrir música y escuchar una muestra.
 * Esta clase conserva el borrador; ActividadEditarTarea decide cuándo guardarlo.
 */
class FormularioTarea(
    private val actividad: Activity,
    private val inicial: Tarea,
    estado: Bundle?,
    elegirMusica: () -> Unit,
    escuchar: (String) -> Unit,
) {
    private val titulo = actividad.findViewById<EditText>(R.id.tarea_titulo_input)
    private val fechaEscrita = actividad.findViewById<EditText>(R.id.tarea_fecha_input)
    private val horaEscrita = actividad.findViewById<EditText>(R.id.tarea_hora_input)
    private val periodo = actividad.findViewById<RadioGroup>(R.id.tarea_periodo_group)
    private val mensaje = actividad.findViewById<EditText>(R.id.tarea_mensaje_input)
    private val mensajeSeguimiento = actividad.findViewById<EditText>(
        R.id.tarea_mensaje_seguimiento_input,
    )
    private val voz = actividad.findViewById<Switch>(R.id.tarea_voz_switch)
    private val seguimiento = actividad.findViewById<Switch>(R.id.tarea_seguimiento_switch)
    private val grupo = actividad.findViewById<RadioGroup>(R.id.tarea_intervalo_group)
    private val otroIntervalo = actividad.findViewById<EditText>(R.id.tarea_intervalo_otro_input)
    private val limite = actividad.findViewById<EditText>(R.id.tarea_limite_input)

    // uuuu representa el año completo. STRICT exige una fecha real y rechaza 31/02.
    // Locale.ROOT mantiene los mismos números y separadores en cualquier idioma del teléfono.
    private val formatoFecha = DateTimeFormatter
        .ofPattern("dd/MM/uuuu", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    // Estas dos variables conservan los últimos valores aceptados. Los campos de texto
    // pueden seguir incompletos; solo validarFechaYHora los convierte antes de guardar.
    private var fecha = estado?.getString("fecha_tarea")?.let(LocalDate::parse) ?: inicial.fecha
    private var hora = estado?.getString("hora_tarea")?.let(LocalTime::parse) ?: inicial.hora
    private var repeticion = estado?.getString("repeticion_tarea")
        ?.let(Repeticion::valueOf)
        ?: inicial.repeticion
    private var dias = estado?.getIntArray("dias_tarea")?.toSet() ?: inicial.diasSemana
    private var musicaUri = if (estado?.containsKey("musica_uri") == true) {
        estado.getString("musica_uri")
    } else {
        inicial.musicaUri
    }
    private var musicaNombre = estado?.getString("musica_nombre") ?: inicial.musicaNombre

    // El modo distingue una elección pendiente (aleatoria) de pedir expresamente Sin música.
    // Girar el teléfono conserva esta decisión aunque las dos opciones no tengan una dirección.
    private var modoMusica = estado?.getString("musica_modo")
        ?.let { guardado -> ModoMusica.entries.firstOrNull { it.name == guardado } }
        ?: inicial.modoMusica
    private var fechaHoraDistribuida = false

    // init se ejecuta al crear este formulario: recupera valores y prepara los botones.
    // Los campos val conservan su referencia; las opciones var pueden cambiar al elegirlas.
    init {
        titulo.setText(estado?.getString("titulo_tarea") ?: inicial.titulo)
        fechaEscrita.setText(
            estado?.getString("fecha_escrita_tarea") ?: fecha.format(formatoFecha),
        )
        horaEscrita.setText(
            estado?.getString("hora_escrita_tarea") ?: mostrarHora(hora),
        )
        val periodoInicial = if (hora.hour < 12) {
            R.id.tarea_periodo_am
        } else {
            R.id.tarea_periodo_pm
        }
        periodo.check(estado?.getInt("periodo_tarea", periodoInicial) ?: periodoInicial)
        mensaje.setText(estado?.getString("mensaje_tarea") ?: inicial.mensajeVoz)
        mensajeSeguimiento.setText(
            estado?.getString("mensaje_seguimiento") ?: inicial.mensajeSeguimiento,
        )
        voz.isChecked = estado?.getBoolean("voz_tarea") ?: inicial.vozActiva
        seguimiento.isChecked = estado?.getBoolean("seguimiento_tarea")
            ?: inicial.seguimientoActivo
        limite.setText(estado?.getString("limite_tarea") ?: inicial.limiteAvisos.toString())
        otroIntervalo.setText(
            estado?.getString("otro_intervalo") ?: inicial.intervaloMinutos.toString(),
        )
        val intervalo = estado?.getInt("opcion_intervalo") ?: when (inicial.intervaloMinutos) {
            5 -> R.id.tarea_intervalo_cinco
            10 -> R.id.tarea_intervalo_diez
            else -> R.id.tarea_intervalo_otro
        }
        grupo.check(intervalo)

        actividad.findViewById<View>(R.id.tarea_repetir_button).setOnClickListener {
            elegirRepeticion()
        }
        actividad.findViewById<View>(R.id.tarea_musica_button).setOnClickListener {
            elegirMusica()
        }
        actividad.findViewById<View>(R.id.tarea_musica_aleatoria_button).setOnClickListener {
            restablecerMusicaAleatoria()
        }
        actividad.findViewById<View>(R.id.tarea_escuchar).setOnClickListener {
            val borrador = leer()
            if (borrador.mensajeVoz.isBlank() && borrador.titulo.isBlank()) {
                Toast.makeText(
                    actividad,
                    "Escribe primero el título o un mensaje.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                escuchar(borrador.mensajeParaAviso(false))
            }
        }
        voz.setOnCheckedChangeListener { _, _ ->
            actualizarOpciones()
        }
        seguimiento.setOnCheckedChangeListener { _, _ ->
            actualizarOpciones()
        }
        grupo.setOnCheckedChangeListener { _, _ ->
            actualizarOpciones()
        }

        // Android avisa cuando conoce el ancho disponible. Entonces distribuimos los
        // campos según ese espacio y el tamaño real de letra, sin esconder a. m./p. m.
        val filaFechaHora = actividad.findViewById<LinearLayout>(R.id.tarea_fecha_hora_fila)
        filaFechaHora.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            adaptarFechaYHora()
        }
        actualizarOpciones()
    }

    /**
     * Recoge lo que está escrito o seleccionado en todos los controles.
     * Devuelve una Tarea con título, fecha, hora, música y seguimiento actuales.
     * Todavía no la guarda: Guardar debe llamar primero a validarFechaYHora.
     * Mientras escribes una fecha incompleta conserva la última fecha y hora aceptadas;
     * así abrir música, escuchar voz o comparar cambios nunca intenta interpretar "28/".
     * Si un número de seguimiento está vacío devuelve cero para señalarlo al guardar.
     */
    // copy crea una copia con los cambios: la tarea inicial no se modifica mientras escribes.
    fun leer(): Tarea {
        return inicial.copy(
            titulo = titulo.text.toString().trim(),
            fecha = fecha,
            hora = hora,
            repeticion = repeticion,
            diasSemana = dias,
            vozActiva = voz.isChecked,
            mensajeVoz = mensaje.text.toString().trim(),
            musicaUri = musicaUri,
            musicaNombre = musicaNombre,
            modoMusica = modoMusica,
            seguimientoActivo = seguimiento.isChecked,
            intervaloMinutos = if (!seguimiento.isChecked) {
                inicial.intervaloMinutos
            } else {
                when (grupo.checkedRadioButtonId) {
                    R.id.tarea_intervalo_cinco -> 5
                    R.id.tarea_intervalo_diez -> 10
                    else -> otroIntervalo.text.toString().toIntOrNull() ?: 0
                }
            },
            limiteAvisos = if (seguimiento.isChecked) {
                limite.text.toString().toIntOrNull() ?: 0
            } else {
                inicial.limiteAvisos
            },
            mensajeSeguimiento = mensajeSeguimiento.text.toString().trim(),
        )
    }

    /**
     * Revisa la fecha escrita y la hora antes de permitir Guardar.
     * No recibe argumentos: toma ambos textos y la elección a. m./p. m. de los controles.
     * Devuelve false si falta un dato o no existe esa fecha u hora, y explica el error.
     * Devuelve true cuando ambos son válidos; entonces actualiza los valores de la tarea
     * y escribe la hora completa: "5" pasa a "5:00". No cambia ningún valor si uno falla.
     * ActividadEditarTarea.guardar la llama antes de leer la tarea y programar sus avisos.
     */
    fun validarFechaYHora(): Boolean {
        val fechaRevisada = interpretarFecha(fechaEscrita.text.toString())
        val horaRevisada = interpretarHora(horaEscrita.text.toString())

        fechaEscrita.error = if (fechaRevisada == null) {
            actividad.getString(R.string.tarea_error_fecha)
        } else {
            null
        }
        horaEscrita.error = if (horaRevisada == null) {
            actividad.getString(R.string.tarea_error_hora)
        } else {
            null
        }

        if (fechaRevisada == null) {
            fechaEscrita.requestFocus()
            return false
        }
        if (horaRevisada == null) {
            horaEscrita.requestFocus()
            return false
        }

        fecha = fechaRevisada
        hora = horaRevisada
        fechaEscrita.setText(fecha.format(formatoFecha))
        horaEscrita.setText(mostrarHora(hora))
        return true
    }

    /**
     * Convierte un texto dd/MM/aaaa en una fecha real, o devuelve null si está incompleto.
     * Recibe el texto del campo; null significa que todavía debe corregirse, sin un fallo.
     * validarFechaYHora la llama al guardar. El formato estricto también revisa años
     * bisiestos: los años que tienen 29 de febrero.
     */
    private fun interpretarFecha(texto: String): LocalDate? {
        if (!texto.matches(Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}"))) {
            return null
        }

        return try {
            val resultado = LocalDate.parse(texto, formatoFecha)
            if (resultado.year in 1..9999) {
                resultado
            } else {
                null
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Convierte "5", "5:00" o "5:30" a una hora que entiende el modelo de la tarea.
     * Recibe el texto; devuelve LocalTime, la hora sin fecha, o null si no es válida.
     * Solo acepta horas de 1 a 12 y minutos de 00 a 59. a. m. es antes del mediodía;
     * p. m. es desde el mediodía. Por eso 12 a. m. es 00:00 y 12 p. m. es 12:00.
     * validarFechaYHora la usa al guardar; no abre un reloj ni otro selector.
     */
    private fun interpretarHora(texto: String): LocalTime? {
        if (!texto.matches(Regex("[0-9]{1,2}(:[0-9]{2})?"))) {
            return null
        }

        val partes = texto.split(":")
        val horas = partes[0].toIntOrNull() ?: return null
        val minutos = if (partes.size == 2) {
            partes[1].toIntOrNull() ?: return null
        } else {
            0
        }
        if (horas !in 1..12 || minutos !in 0..59) {
            return null
        }

        val horasDesdeMedianoche = when (periodo.checkedRadioButtonId) {
            R.id.tarea_periodo_am -> horas % 12
            R.id.tarea_periodo_pm -> horas % 12 + 12
            else -> return null
        }
        return LocalTime.of(horasDesdeMedianoche, minutos)
    }

    /**
     * Prepara el texto de una hora para el campo editable de doce horas.
     * Recibe LocalTime y devuelve, por ejemplo, "5:00" o "12:30".
     * La usan el inicio del formulario y validarFechaYHora al completar un guardado válido.
     * a. m./p. m. se elige en su control separado, por eso no se añade a este texto.
     */
    private fun mostrarHora(valor: LocalTime): String {
        val horas = if (valor.hour % 12 == 0) {
            12
        } else {
            valor.hour % 12
        }
        return horas.toString() + ":" + valor.minute.toString().padStart(2, '0')
    }

    /**
     * Recuerda los campos cuando Android necesita recrear la pantalla al girar.
     * Recibe estado, un paquete temporal de datos llamado Bundle, y coloca dentro
     * los textos, la fecha, las opciones y la canción elegida. Conserva incluso un
     * número que todavía está incompleto. No crea una tarea guardada ni una alarma.
     * La llama ActividadEditarTarea desde onSaveInstanceState.
     */
    fun guardarBorrador(estado: Bundle) {
        estado.putString("id_tarea", inicial.id)
        estado.putString("titulo_tarea", titulo.text.toString())
        estado.putString("fecha_tarea", fecha.toString())
        estado.putString("hora_tarea", hora.toString())
        estado.putString("fecha_escrita_tarea", fechaEscrita.text.toString())
        estado.putString("hora_escrita_tarea", horaEscrita.text.toString())
        estado.putInt("periodo_tarea", periodo.checkedRadioButtonId)
        estado.putString("repeticion_tarea", repeticion.name)
        estado.putIntArray("dias_tarea", dias.toIntArray())
        estado.putBoolean("voz_tarea", voz.isChecked)
        estado.putBoolean("seguimiento_tarea", seguimiento.isChecked)
        estado.putString("mensaje_tarea", mensaje.text.toString())
        estado.putString("mensaje_seguimiento", mensajeSeguimiento.text.toString())
        estado.putString("musica_uri", musicaUri)
        estado.putString("musica_nombre", musicaNombre)
        estado.putString("musica_modo", modoMusica.name)
        estado.putString("limite_tarea", limite.text.toString())
        estado.putString("otro_intervalo", otroIntervalo.text.toString())
        estado.putInt("opcion_intervalo", grupo.checkedRadioButtonId)
    }

    /**
     * Devuelve un texto que representa todo lo escrito en el formulario.
     * Aquí firma significa una comparación del contenido, no una firma de una persona.
     * La actividad compara este texto con el del principio para saber si cambiaste algo.
     * Incluye los textos de fecha y hora tal como están, aunque aún estén incompletos,
     * y la elección a. m./p. m. Así también reconoce esos cambios al volver.
     */
    fun firma(): String {
        return listOf(
            leer().toString(),
            titulo.text.toString(),
            fechaEscrita.text.toString(),
            horaEscrita.text.toString(),
            periodo.checkedRadioButtonId.toString(),
            mensaje.text.toString(),
            mensajeSeguimiento.text.toString(),
            limite.text.toString(),
            otroIntervalo.text.toString(),
            grupo.checkedRadioButtonId.toString(),
        ).joinToString("\u001F")
    }

    /**
     * Actualiza la canción del borrador cuando vuelves del selector.
     * Recibe uri, la dirección que Android da al archivo, y nombre, el texto visible.
     * Si uri es null significa que elegiste Sin música. Después cambia la etiqueta
     * del botón para mostrar tu elección. No modifica todavía la tarea guardada.
     */
    fun seleccionarMusica(uri: String?, nombre: String) {
        musicaUri = uri?.takeIf { it.isNotBlank() }
        modoMusica = if (musicaUri == null) {
            ModoMusica.SIN_MUSICA
        } else {
            ModoMusica.SELECCIONADA
        }
        musicaNombre = if (musicaUri == null) {
            ""
        } else {
            nombre
        }
        actualizarOpciones()
    }

    /**
     * El botón Usar música aleatoria la llama cuando antes elegiste una canción o silencio.
     * No recibe datos ni devuelve un resultado. Retira la dirección anterior y vuelve al modo
     * inicial; así el aviso podrá elegir una canción de la biblioteca al sonar.
     * Solo cambia el borrador. El permiso y el guardado se revisan al pulsar Guardar tarea.
     */
    private fun restablecerMusicaAleatoria() {
        modoMusica = ModoMusica.ALEATORIA
        musicaUri = null
        musicaNombre = ""
        actualizarOpciones()
    }

    /**
     * Muestra una explicación junto a un campo que necesita corregirse.
     * Recibe campo, el nombre android:id de la casilla, y mensajeError, la explicación.
     * También lleva el cursor a esa casilla para que puedas escribir directamente.
     * La llama ActividadEditarTarea al revisar los datos antes de guardarlos.
     */
    fun error(
        campo: Int,
        mensajeError: String,
    ) {
        actividad.findViewById<EditText>(campo).apply {
            error = mensajeError
            requestFocus()
        }
    }

    /**
     * Muestra u oculta los grupos de voz y seguimiento según sus interruptores.
     * También actualiza los textos de repetición y canción elegida.
     * Si eliges Otro intervalo deja visible la casilla para escribir los minutos.
     * Conserva los textos manuales de fecha y hora, también al volver de música.
     * No recibe ni devuelve datos. La llaman los controles al cambiar esas opciones.
     */
    private fun actualizarOpciones() {
        actividad.findViewById<View>(R.id.tarea_voz_contenido).visibility = if (voz.isChecked) {
            View.VISIBLE
        } else {
            View.GONE
        }
        actividad.findViewById<View>(R.id.tarea_seguimiento_contenido).visibility =
            if (seguimiento.isChecked) {
                View.VISIBLE
            } else {
                View.GONE
            }
        otroIntervalo.visibility = if (grupo.checkedRadioButtonId == R.id.tarea_intervalo_otro) {
            View.VISIBLE
        } else {
            View.GONE
        }
        actividad.findViewById<Button>(R.id.tarea_repetir_button).text =
            FormatoAgenda.repeticion(leer())
        val botonMusica = actividad.findViewById<Button>(R.id.tarea_musica_button)
        botonMusica.text = when (modoMusica) {
            ModoMusica.ALEATORIA -> actividad.getString(R.string.tarea_musica_aleatoria)
            ModoMusica.SIN_MUSICA -> actividad.getString(R.string.tarea_musica_sin)
            ModoMusica.SELECCIONADA -> musicaNombre.ifBlank {
                actividad.getString(R.string.tarea_musica_seleccionada)
            }
        }
        botonMusica.contentDescription = actividad.getString(
            R.string.tarea_musica_cambiar_descripcion,
            leer().descripcionMusica(),
        )
        actividad.findViewById<View>(R.id.tarea_musica_aleatoria_button).visibility =
            if (modoMusica == ModoMusica.ALEATORIA) View.GONE else View.VISIBLE
        actividad.findViewById<TextView>(R.id.tarea_musica_ayuda).setText(
            when (modoMusica) {
                ModoMusica.ALEATORIA -> R.string.tarea_ayuda_musica
                ModoMusica.SIN_MUSICA -> R.string.tarea_ayuda_musica_sin
                ModoMusica.SELECCIONADA -> R.string.tarea_ayuda_musica_seleccionada
            },
        )
    }

    /**
     * Distribuye Fecha, Hora y a. m./p. m. sin cortar los controles.
     * No recibe ni devuelve datos: consulta el ancho disponible y mide la letra real.
     * En ancho suficiente usa una fila; si falta espacio coloca Fecha encima de Hora.
     * Si tampoco caben Hora y a. m./p. m., separa estos dos controles en otra fila.
     * Android la llama desde el aviso de cambio de tamaño preparado en init.
     */
    private fun adaptarFechaYHora() {
        val fila = actividad.findViewById<LinearLayout>(R.id.tarea_fecha_hora_fila)
        val anchoDisponible = fila.width - fila.paddingLeft - fila.paddingRight
        if (anchoDisponible <= 0) {
            return
        }

        val separacion = (8 * actividad.resources.displayMetrics.density).toInt()
        val fechaColumna = actividad.findViewById<View>(R.id.tarea_fecha_columna)
        val horaBloque = actividad.findViewById<LinearLayout>(R.id.tarea_hora_bloque)
        val horaColumna = actividad.findViewById<View>(R.id.tarea_hora_columna)
        val periodoColumna = actividad.findViewById<View>(R.id.tarea_periodo_columna)
        val am = actividad.findViewById<RadioButton>(R.id.tarea_periodo_am)
        val pm = actividad.findViewById<RadioButton>(R.id.tarea_periodo_pm)

        val anchoFecha = fechaEscrita.paint.measureText("00/00/0000").toInt() +
            fechaEscrita.paddingLeft + fechaEscrita.paddingRight + separacion
        val anchoHora = horaEscrita.paint.measureText("12:59").toInt() +
            horaEscrita.paddingLeft + horaEscrita.paddingRight + separacion
        val anchoAm = maxOf(
            am.minimumWidth,
            am.paint.measureText(am.text.toString()).toInt() + am.paddingLeft + am.paddingRight,
        )
        val anchoPm = maxOf(
            pm.minimumWidth,
            pm.paint.measureText(pm.text.toString()).toInt() + pm.paddingLeft + pm.paddingRight,
        )
        val margenPeriodo = (pm.layoutParams as LinearLayout.LayoutParams).marginStart
        val anchoPeriodo = anchoAm + anchoPm + margenPeriodo
        val todoEnUnaFila = anchoDisponible >= anchoFecha + anchoHora + anchoPeriodo +
            separacion * 2
        val horaEnUnaFila = todoEnUnaFila ||
            anchoDisponible >= anchoHora + anchoPeriodo + separacion
        val orientacionFecha = if (todoEnUnaFila) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }
        val orientacionHora = if (horaEnUnaFila) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }

        // Solo se cambian las medidas al preparar la fila o cambiar su distribución.
        // Así un cambio de medidas no provoca otro ajuste idéntico de forma continua.
        if (!fechaHoraDistribuida ||
            fila.orientation != orientacionFecha ||
            horaBloque.orientation != orientacionHora
        ) {
            fechaHoraDistribuida = true
            fila.orientation = orientacionFecha
            horaBloque.orientation = orientacionHora

            fechaColumna.layoutParams = LinearLayout.LayoutParams(
                if (todoEnUnaFila) anchoFecha else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            horaBloque.layoutParams = LinearLayout.LayoutParams(
                if (todoEnUnaFila) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (todoEnUnaFila) 1f else 0f,
            ).apply {
                marginStart = if (todoEnUnaFila) separacion else 0
                topMargin = if (todoEnUnaFila) 0 else separacion
            }
            horaColumna.layoutParams = LinearLayout.LayoutParams(
                if (horaEnUnaFila) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (horaEnUnaFila) 1f else 0f,
            )
            periodoColumna.layoutParams = LinearLayout.LayoutParams(
                if (horaEnUnaFila) anchoPeriodo else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = if (horaEnUnaFila) separacion else 0
                topMargin = if (horaEnUnaFila) 0 else separacion
            }
        }
    }

    /**
     * Ofrece Solo una vez, Todos los días o Elegir días de la semana.
     * Al elegir una opción actualiza el borrador; la tercera abre elegirDias.
     * No recibe ni devuelve datos; la llama el botón Repetir tarea.
     * Esto decide las fechas de la tarea. El seguimiento es otra opción distinta:
     * sirve para repetir avisos mientras una tarea de ese día siga pendiente.
     */
    private fun elegirRepeticion() {
        AlertDialog.Builder(actividad)
            .setTitle("Repetir tarea")
            .setItems(
                arrayOf("Solo una vez", "Todos los días", "Elegir días de la semana"),
            ) { _, posicion ->
                when (posicion) {
                    0 -> repeticion = Repeticion.UNA_VEZ
                    1 -> repeticion = Repeticion.DIARIA
                    else -> {
                        elegirDias()
                        return@setItems
                    }
                }
                actualizarOpciones()
            }
            .show()
    }

    /**
     * Abre la lista de lunes a domingo para marcar varios días.
     * Trabaja con una copia de la selección, para que Cancelar no cambie nada.
     * Al aceptar exige por lo menos un día y guarda sus números: lunes es 1 y domingo 7.
     * No recibe ni devuelve datos. Después actualiza la etiqueta de repetición.
     * La llama elegirRepeticion al escoger Elegir días de la semana.
     */
    private fun elegirDias() {
        val nombres = arrayOf(
            "Lunes",
            "Martes",
            "Miércoles",
            "Jueves",
            "Viernes",
            "Sábado",
            "Domingo",
        )
        val elegidos = dias.toMutableSet()
        AlertDialog.Builder(actividad)
            .setTitle("Días de la tarea")
            .setMultiChoiceItems(
                nombres,
                BooleanArray(7) { it + 1 in elegidos },
            ) { _, indice, marcado ->
                if (marcado) {
                    elegidos.add(indice + 1)
                } else {
                    elegidos.remove(indice + 1)
                }
            }
            .setPositiveButton("Usar estos días") { _, _ ->
                if (elegidos.isEmpty()) {
                    Toast.makeText(
                        actividad,
                        "Elige al menos un día.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    dias = elegidos
                    repeticion = Repeticion.DIAS_SEMANA
                    actualizarOpciones()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

