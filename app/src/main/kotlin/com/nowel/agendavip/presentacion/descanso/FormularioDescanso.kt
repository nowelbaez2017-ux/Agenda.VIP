package com.nowel.agendavip.presentacion.descanso

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import com.nowel.agendavip.R
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

/**
 * Conecta los controles de descanso_configuracion.xml con sus datos, sin guardarlos todavía.
 * Recibe la pantalla, la configuración guardada y el borrador que Android conserva al girar.
 * ActividadDescanso utiliza leer para validar y guardar; guardarEstado conserva lo escrito.
 */
class FormularioDescanso(
    private val actividad: Activity,
    inicial: ConfiguracionDescanso,
    estado: Bundle?,
) {

    private val activado = actividad.findViewById<Switch>(R.id.descanso_activado)
    private val restriccion = actividad.findViewById<Switch>(R.id.descanso_restriccion)
    private val voz = actividad.findViewById<Switch>(R.id.descanso_voz)
    private val pausa = actividad.findViewById<Switch>(R.id.descanso_pausa)
    private val inicio = actividad.findViewById<EditText>(R.id.descanso_inicio_hora)
    private val fin = actividad.findViewById<EditText>(R.id.descanso_fin_hora)
    private val periodoInicio = actividad.findViewById<RadioGroup>(R.id.descanso_inicio_periodo)
    private val periodoFin = actividad.findViewById<RadioGroup>(R.id.descanso_fin_periodo)
    private val mensaje = actividad.findViewById<EditText>(R.id.descanso_mensaje)
    private val dias = linkedMapOf<DayOfWeek, CheckBox>()
    private val aplicaciones = linkedSetOf<String>()

    init {
        activado.isChecked = estado?.getBoolean("descanso_activo") ?: inicial.activado
        restriccion.isChecked = estado?.getBoolean("descanso_restringir") ?: inicial.restriccionActiva
        voz.isChecked = estado?.getBoolean("descanso_hablar") ?: inicial.vozActiva
        pausa.isChecked = estado?.getBoolean("descanso_permitir_pausa") ?: inicial.pausaPermitida
        inicio.setText(estado?.getString("descanso_inicio_texto") ?: mostrarHora(inicial.inicio))
        fin.setText(estado?.getString("descanso_fin_texto") ?: mostrarHora(inicial.fin))
        mensaje.setText(estado?.getString("descanso_mensaje_texto") ?: inicial.mensaje)

        periodoInicio.check(
            estado?.getInt("descanso_inicio_periodo") ?: if (inicial.inicio.hour < 12) {
                R.id.descanso_inicio_am
            } else {
                R.id.descanso_inicio_pm
            },
        )
        periodoFin.check(
            estado?.getInt("descanso_fin_periodo") ?: if (inicial.fin.hour < 12) {
                R.id.descanso_fin_am
            } else {
                R.id.descanso_fin_pm
            },
        )

        val elegidas = estado?.getStringArrayList("descanso_apps") ?: inicial.aplicaciones.toList()
        aplicaciones.addAll(elegidas)
        prepararDias(inicial, estado)
    }

    /**
     * Crea una casilla por día dentro de la columna del XML. Cada día indica cuándo comienza
     * una noche: elegir lunes incluye la madrugada del martes si el horario cruza medianoche.
     * El constructor la llama una vez; los controles se recuperan desde el borrador si existe.
     */
    private fun prepararDias(inicial: ConfiguracionDescanso, estado: Bundle?) {
        val columna = actividad.findViewById<LinearLayout>(R.id.descanso_dias)
        val nombres = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

        var fila: LinearLayout? = null

        DayOfWeek.values().forEachIndexed { indice, dia ->
            if (indice % 2 == 0) {
                fila = LinearLayout(actividad).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                columna.addView(fila)
            }

            val casilla = CheckBox(actividad).apply {
                text = nombres[indice]
                textSize = 16f
                setTextColor(actividad.getColor(R.color.ciruela))
                isChecked = estado?.getBoolean("descanso_dia_${dia.value}") ?: (dia in inicial.dias)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            dias[dia] = casilla
            fila?.addView(casilla)
        }
    }

    /**
     * Entrega una copia de las aplicaciones seleccionadas al selector, sin permitirle modificar
     * el borrador hasta que la persona pulse Aceptar. No consulta el contenido de otras apps.
     */
    fun aplicacionesElegidas(): Set<String> = aplicaciones.toSet()

    /** Recibe la selección aceptada y sustituye únicamente las aplicaciones de este borrador. */
    fun cambiarAplicaciones(elegidas: Set<String>) {
        aplicaciones.clear()
        aplicaciones.addAll(elegidas)
    }

    /**
     * Convierte los controles en una configuración revisada. Guardar y Vista previa la llaman.
     * Si hay un error, lo señala junto al campo y explica cómo corregirlo; no guarda datos.
     * Permite preparar aplicaciones antes de activar la rutina y no exige permisos para escribir.
     */
    fun leer(): ConfiguracionDescanso {
        inicio.error = null
        fin.error = null
        mensaje.error = null

        val horaInicio = leerHora(inicio, periodoInicio.checkedRadioButtonId == R.id.descanso_inicio_pm)
        val horaFin = leerHora(fin, periodoFin.checkedRadioButtonId == R.id.descanso_fin_pm)
        require(horaInicio != horaFin) { "La hora de descanso y la de levantarte deben ser distintas." }

        val diasElegidos = dias.filterValues { it.isChecked }.keys.toSet()
        require(diasElegidos.isNotEmpty()) { "Selecciona al menos un día para comenzar la rutina." }
        require(mensaje.text.length <= 500) { "El mensaje puede tener hasta 500 caracteres." }

        if (activado.isChecked && restriccion.isChecked) {
            require(aplicaciones.isNotEmpty()) {
                "Elige alguna aplicación para restringir o desactiva Restringir aplicaciones."
            }
        }

        return ConfiguracionDescanso(
            activado = activado.isChecked,
            inicio = horaInicio,
            fin = horaFin,
            dias = diasElegidos,
            aplicaciones = aplicaciones.toSet(),
            mensaje = mensaje.text.toString().trim(),
            vozActiva = voz.isChecked,
            restriccionActiva = restriccion.isChecked,
            pausaPermitida = pausa.isChecked,
        )
    }

    /**
     * Recibe un campo y su período. Acepta 5 o 5:30 en formato de doce horas y devuelve LocalTime,
     * la hora de Java sin fecha. Rechaza números fuera de rango en vez de corregirlos a escondidas.
     */
    private fun leerHora(campo: EditText, esTarde: Boolean): LocalTime {
        val partes = campo.text.toString().trim().split(":")
        val hora = partes.getOrNull(0)?.toIntOrNull()
        val minuto = if (partes.size == 1) 0 else partes.getOrNull(1)?.toIntOrNull()

        if (partes.size !in 1..2 || hora == null || hora !in 1..12 || minuto == null || minuto !in 0..59) {
            campo.error = "Escribe una hora como 8:30 y elige a. m. o p. m."
            campo.requestFocus()
            throw IllegalArgumentException("Revisa la hora: usa de 1 a 12 y minutos de 00 a 59.")
        }

        return LocalTime.of(hora % 12 + if (esTarde) 12 else 0, minuto)
    }

    /** Recibe una hora guardada y devuelve el texto de doce horas que aparecerá en su campo. */
    private fun mostrarHora(hora: LocalTime): String = String.format(
        Locale.ROOT,
        "%d:%02d",
        (hora.hour % 12).let { if (it == 0) 12 else it },
        hora.minute,
    )

    /**
     * Recibe el paquete temporal de Android y copia lo escrito aunque aún no sea válido.
     * onSaveInstanceState la llama al girar; volver de Ajustes también conserva estos controles.
     */
    fun guardarEstado(estado: Bundle) {
        estado.putBoolean("descanso_activo", activado.isChecked)
        estado.putBoolean("descanso_restringir", restriccion.isChecked)
        estado.putBoolean("descanso_hablar", voz.isChecked)
        estado.putBoolean("descanso_permitir_pausa", pausa.isChecked)
        estado.putString("descanso_inicio_texto", inicio.text.toString())
        estado.putString("descanso_fin_texto", fin.text.toString())
        estado.putInt("descanso_inicio_periodo", periodoInicio.checkedRadioButtonId)
        estado.putInt("descanso_fin_periodo", periodoFin.checkedRadioButtonId)
        estado.putString("descanso_mensaje_texto", mensaje.text.toString())
        estado.putStringArrayList("descanso_apps", ArrayList(aplicaciones))

        dias.forEach { (dia, casilla) ->
            estado.putBoolean("descanso_dia_${dia.value}", casilla.isChecked)
        }
    }
}
