package com.nowel.agendavip.presentacion.cuenta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.presentacion.comun.VozDeMuestra
import com.nowel.agendavip.presentacion.descanso.ActividadDescanso
import com.nowel.agendavip.presentacion.uso.ActividadLimitesUso
import com.nowel.agendavip.voz.VocesLocales
import kotlin.math.roundToInt

/**
 * ActividadCuenta crea este controlador al abrir Configuración.
 * Recibe la pantalla, la columna donde coloca las tarjetas y las acciones que pertenecen a
 * la cuenta o a los ajustes del teléfono. Usa PreferenciasAgenda para recordar las opciones.
 */
class ControladorConfiguracion(
    private val actividad: Activity,
    private val contenedor: LinearLayout,
    private val abrirPermisos: () -> Unit,
    private val abrirVozDelSistema: () -> Unit,
    private val identidadCuenta: () -> String,
    private val cerrarSesion: () -> Unit,
) {

    private val preferencias = PreferenciasAgenda(actividad)
    private val vista = actividad.layoutInflater.inflate(
        R.layout.cuenta_configuracion,
        contenedor,
        false,
    )
    private val principal = Handler(Looper.getMainLooper())
    private val muestra = VozDeMuestra(actividad) { informar(it) }
    private val resumenSonido: TextView
    private val resumenSeguimiento: TextView
    private val resumenCuenta: TextView

    private var panel: AlertDialog? = null
    private var dialogoOpcion: AlertDialog? = null
    private var motorConsulta: TextToSpeech? = null
    private var cargandoVoces = false
    private var turnoConsulta = 0
    private var volumenPendiente: Int? = null

    init {
        // Al crear el controlador se coloca cada fila en la tarjeta del tema correspondiente.
        resumenSonido = agregarFila(
            R.id.configuracion_grupo_recordatorios,
            R.drawable.tarea_icono_escuchar,
            actividad.getString(R.string.configuracion_sonido),
        ) {
            abrirSonido()
        }
        resumenSeguimiento = agregarFila(
            R.id.configuracion_grupo_recordatorios,
            R.drawable.agenda_icono_calendario,
            actividad.getString(R.string.configuracion_seguimiento),
        ) {
            abrirSeguimiento()
        }
        agregarFila(
            R.id.configuracion_grupo_recordatorios,
            R.drawable.icono_notificacion,
            "Permisos y batería",
            "Entrega de avisos con el teléfono bloqueado",
            abrirPermisos,
        )

        agregarFila(
            R.id.configuracion_grupo_bienestar,
            R.drawable.configuracion_icono_descanso,
            "Descanso nocturno",
            "Horario y aplicaciones restringidas",
        ) {
            actividad.startActivity(Intent(actividad, ActividadDescanso::class.java))
        }
        agregarFila(
            R.id.configuracion_grupo_bienestar,
            R.drawable.agenda_icono_enfoque,
            "Límites de uso",
        ) {
            actividad.startActivity(Intent(actividad, ActividadLimitesUso::class.java))
        }

        agregarFila(
            R.id.configuracion_grupo_privacidad,
            R.drawable.icono_candado,
            "Seguridad y acceso",
        ) {
            actividad.startActivity(Intent(actividad, ActividadSeguridad::class.java))
        }
        agregarFila(
            R.id.configuracion_grupo_privacidad,
            R.drawable.configuracion_icono_respaldo,
            "Copia de seguridad",
            "Guardar o recuperar tus datos",
        ) {
            actividad.startActivity(Intent(actividad, ActividadRespaldo::class.java))
        }

        agregarFila(
            R.id.configuracion_grupo_privacidad,
            R.drawable.configuracion_icono_respaldo,
            actividad.getString(R.string.conexion_pc_titulo),
            "Conexión opcional, tareas y respaldos",
        ) {
            actividad.startActivity(
                Intent(
                    actividad,
                    com.nowel.agendavip.presentacion.servidor.ActividadConexionPC::class.java,
                ),
            )
        }

        resumenCuenta = agregarFila(
            R.id.configuracion_grupo_cuenta,
            R.drawable.icono_usuario,
            "Cerrar sesión",
            accion = cerrarSesion,
        )
    }

    /**
     * Recibe la tarjeta, el dibujo, el texto y lo que debe pasar al tocar una fila.
     * La añade al diseño y devuelve su texto secundario, que mostrar actualiza cuando cambia
     * una preferencia. Las opciones sin detalle dejan ese espacio oculto.
     */
    private fun agregarFila(
        grupo: Int,
        icono: Int,
        titulo: String,
        detalle: String? = null,
        accion: () -> Unit,
    ): TextView {
        val tarjeta = vista.findViewById<LinearLayout>(grupo)
        val fila = actividad.layoutInflater.inflate(R.layout.configuracion_fila, tarjeta, false)
        fila.findViewById<ImageView>(R.id.configuracion_fila_icono).setImageResource(icono)
        fila.findViewById<TextView>(R.id.configuracion_fila_titulo).text = titulo
        fila.setOnClickListener {
            accion()
        }

        val textoDetalle = fila.findViewById<TextView>(R.id.configuracion_fila_detalle)
        if (detalle != null) {
            textoDetalle.text = detalle
            textoDetalle.visibility = View.VISIBLE
        }

        tarjeta.addView(fila)
        return textoDetalle
    }

    /**
     * ActividadCuenta llama aquí al abrir Configuración y al regresar de otra pantalla.
     * Coloca las tarjetas una sola vez y actualiza el usuario, el sonido y el seguimiento.
     */
    fun mostrar() {
        if (vista.parent == null) {
            contenedor.addView(vista)
        }

        val porcentaje = (preferencias.volumenAvisos * 100).roundToInt()
        resumenSonido.text = actividad.getString(
            R.string.configuracion_resumen_sonido,
            preferencias.etiquetaVoz,
            porcentaje,
        )
        resumenSeguimiento.text = if (preferencias.seguimientoPredeterminado) {
            val limite = preferencias.limiteAvisos
            actividad.resources.getQuantityString(
                R.plurals.configuracion_seguimiento_activo,
                limite,
                preferencias.intervaloMinutos,
                limite,
            )
        } else {
            actividad.getString(R.string.configuracion_seguimiento_inactivo)
        }
        resumenCuenta.text = identidadCuenta()
        resumenSonido.visibility = View.VISIBLE
        resumenSeguimiento.visibility = View.VISIBLE
        resumenCuenta.visibility = View.VISIBLE

        val botonVoz = panel?.findViewById<Button>(R.id.cuenta_elegir_voz)
        botonVoz?.isEnabled = !cargandoVoces
        botonVoz?.text = if (cargandoVoces) {
            "Buscando voces…"
        } else {
            actividad.getString(R.string.configuracion_voz_valor, preferencias.etiquetaVoz)
        }

        panel?.findViewById<TextView>(R.id.cuenta_porcentaje_volumen)?.text =
            actividad.getString(R.string.configuracion_volumen_valor, volumenPendiente ?: porcentaje)
        panel?.findViewById<Button>(R.id.cuenta_intervalo)?.text =
            actividad.getString(R.string.configuracion_intervalo_valor, preferencias.intervaloMinutos)
        panel?.findViewById<Button>(R.id.cuenta_limite)?.text =
            actividad.getString(R.string.configuracion_limite_valor, preferencias.limiteAvisos)
    }

    /**
     * Recibe los recursos del título y el diseño. Abre el cuadro y devuelve su contenido para
     * conectar los controles. Voz y Seguimiento usan esta misma forma de abrir y cerrar.
     */
    private fun abrirPanel(titulo: Int, diseno: Int): View {
        pausar()
        val contenido = actividad.layoutInflater.inflate(diseno, null)
        val cuadro = AlertDialog.Builder(actividad)
            .setTitle(titulo)
            .setView(contenido)
            .setPositiveButton("Listo", null)
            .show()

        panel = cuadro
        cuadro.setOnDismissListener {
            if (panel === cuadro) {
                panel = null
                pausar()
            }
        }
        return contenido
    }

    /**
     * La fila Voz y volumen abre este cuadro. La barra guarda el nivel de Agenda.VIP y el
     * botón Escuchar muestra lee una frase con la voz y el nivel elegidos.
     */
    private fun abrirSonido() {
        val contenido = abrirPanel(R.string.configuracion_sonido, R.layout.configuracion_panel_sonido)
        contenido.findViewById<View>(R.id.cuenta_elegir_voz).setOnClickListener {
            elegirVoz()
        }
        contenido.findViewById<View>(R.id.cuenta_escuchar_voz).setOnClickListener {
            muestra.hablar("Así escucharás tus recordatorios en Agenda.VIP.")
        }

        val volumen = contenido.findViewById<SeekBar>(R.id.cuenta_volumen)
        val etiqueta = contenido.findViewById<TextView>(R.id.cuenta_porcentaje_volumen)
        volumen.progress = (preferencias.volumenAvisos * 100).roundToInt()

        volumen.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                private var arrastrando = false

                /**
                 * Android entrega el porcentaje y señala si lo cambió la persona.
                 * Durante el arrastre solo cambia el texto. El teclado y accesibilidad
                 * guardan al momento porque pueden cambiar el valor sin arrastrar.
                 */
                override fun onProgressChanged(barra: SeekBar?, porcentaje: Int, delUsuario: Boolean) {
                    if (!delUsuario) {
                        return
                    }

                    etiqueta.text = actividad.getString(R.string.configuracion_volumen_valor, porcentaje)
                    volumenPendiente = porcentaje

                    if (!arrastrando) {
                        guardarVolumenPendiente()
                    }
                }

                /** Android avisa que empezó el arrastre para guardar solo cuando suelte la barra. */
                override fun onStartTrackingTouch(barra: SeekBar?) {
                    arrastrando = true
                }

                /** Android avisa que terminó el arrastre. Guarda el último valor, si cambió. */
                override fun onStopTrackingTouch(barra: SeekBar?) {
                    arrastrando = false

                    guardarVolumenPendiente()
                }
            },
        )
        mostrar()
    }

    /**
     * Guarda el último porcentaje elegido y descarta el borrador. La barra la llama al
     * terminar un cambio, y pausar la usa si se cierra el cuadro durante el arrastre.
     * Si cambió la sesión, PreferenciasAgenda impide aplicar el borrador en otra cuenta.
     */
    private fun guardarVolumenPendiente() {
        val porcentaje = volumenPendiente ?: return
        volumenPendiente = null

        try {
            preferencias.guardarVolumen(porcentaje / 100f)
            resumenSonido.text = actividad.getString(
                R.string.configuracion_resumen_sonido,
                preferencias.etiquetaVoz,
                porcentaje,
            )
        } catch (_: IllegalStateException) {
            // La cuenta de este cuadro ya no está disponible.
        }
    }

    /**
     * La fila Seguimiento abre este cuadro. El interruptor, el intervalo y el límite actualizan
     * las preferencias que leerá Nueva tarea al abrirse.
     */
    private fun abrirSeguimiento() {
        val contenido = abrirPanel(
            R.string.configuracion_seguimiento,
            R.layout.configuracion_panel_seguimiento,
        )
        val interruptor = contenido.findViewById<Switch>(R.id.cuenta_seguimiento_predeterminado)
        interruptor.isChecked = preferencias.seguimientoPredeterminado
        interruptor.setOnCheckedChangeListener { _, activado ->
            preferencias.guardarSeguimiento(activado)
            mostrar()
        }
        contenido.findViewById<View>(R.id.cuenta_intervalo).setOnClickListener {
            elegirIntervalo()
        }
        contenido.findViewById<View>(R.id.cuenta_limite).setOnClickListener {
            pedirNumero("Límite total de avisos", preferencias.limiteAvisos, 1..20) { cantidad ->
                preferencias.guardarLimite(cantidad)
            }
        }
        mostrar()
    }

    /**
     * El botón Intervalo llama a este cuadro para elegir 5 o 10 minutos, o para escribir otra
     * cantidad. Guarda el valor que se propondrá al crear las próximas tareas.
     */
    private fun elegirIntervalo() {
        dialogoOpcion = AlertDialog.Builder(actividad)
            .setTitle("Intervalo entre avisos")
            .setItems(arrayOf("5 minutos", "10 minutos", "Otro intervalo")) { _, opcion ->
                if (opcion < 2) {
                    preferencias.guardarIntervalo(if (opcion == 0) 5 else 10)
                    mostrar()
                } else {
                    pedirNumero("Minutos entre avisos", preferencias.intervaloMinutos, 1..1440) {
                        preferencias.guardarIntervalo(it)
                    }
                }
            }
            .show()
    }

    /**
     * Recibe un título, valor inicial, rango permitido y la acción para guardar el número.
     * Intervalo y Límite lo usan para permitir escribir. Deja el cuadro abierto y explica
     * el problema si la cantidad no está dentro del rango.
     */
    private fun pedirNumero(
        titulo: String,
        inicial: Int,
        rango: IntRange,
        guardar: (Int) -> Unit,
    ) {
        val campo = EditText(actividad).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(inicial.toString())
            selectAll()
            hint = "De ${rango.first} a ${rango.last}"
        }
        val dialogo = AlertDialog.Builder(actividad)
            .setTitle(titulo)
            .setView(campo)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val numero = campo.text.toString().toIntOrNull()
                if (numero == null || numero !in rango) {
                    campo.error = "Escribe un número entre ${rango.first} y ${rango.last}."
                } else {
                    guardar(numero)
                    dialogo.dismiss()
                    mostrar()
                }
            }
        }

        dialogoOpcion = dialogo
        dialogo.show()
    }

    /**
     * El botón Voz consulta qué voces en español están instaladas y funcionan sin conexión.
     * Android responde después. El número de turno permite descartar esa respuesta si la
     * persona ya cerró el cuadro que la pidió.
     */
    private fun elegirVoz() {
        if (cargandoVoces) {
            return
        }

        cargandoVoces = true
        val turno = ++turnoConsulta
        mostrar()

        motorConsulta = TextToSpeech(actividad.applicationContext) { resultado ->
            principal.post {
                if (turno != turnoConsulta || panel?.isShowing != true) {
                    return@post
                }

                val motor = motorConsulta
                val voces = if (resultado == TextToSpeech.SUCCESS && motor != null) {
                    VocesLocales.disponibles(motor)
                } else {
                    emptyList()
                }
                motor?.shutdown()
                motorConsulta = null
                cargandoVoces = false
                mostrar()

                if (voces.isEmpty()) {
                    dialogoOpcion = AlertDialog.Builder(actividad)
                        .setTitle("Voz del teléfono")
                        .setMessage("Puedes instalar una voz en español sin conexión desde los ajustes del teléfono.")
                        .setPositiveButton("Abrir ajustes") { _, _ -> abrirVozDelSistema() }
                        .setNegativeButton("Ahora no", null)
                        .show()
                    return@post
                }

                val etiquetas = listOf("Español · Automática") + voces.mapIndexed { indice, voz ->
                    VocesLocales.etiqueta(voz, indice)
                }
                var elegida = voces.indexOfFirst { it.name == preferencias.nombreVoz } + 1

                dialogoOpcion = AlertDialog.Builder(actividad)
                    .setTitle("Voz de los recordatorios")
                    .setSingleChoiceItems(etiquetas.toTypedArray(), elegida) { _, posicion ->
                        elegida = posicion
                    }
                    .setPositiveButton("Usar voz") { _, _ ->
                        val nombre = if (elegida == 0) null else voces[elegida - 1].name
                        preferencias.guardarVoz(nombre, etiquetas[elegida])
                        mostrar()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    /**
     * El cierre del cuadro de sonido y pausar llaman aquí. Suelta el lector de voces y marca
     * su respuesta pendiente como antigua para que no abra otro cuadro después de salir.
     */
    private fun detenerConsultaVoz() {
        turnoConsulta += 1
        motorConsulta?.shutdown()
        motorConsulta = null
        cargandoVoces = false
    }

    /**
     * ActividadCuenta llama aquí al pasar a otra pantalla. Cierra los cuadros y detiene
     * las muestras. Los cambios ya guardados se conservan.
     */
    fun pausar() {
        guardarVolumenPendiente()
        dialogoOpcion?.dismiss()
        dialogoOpcion = null
        panel?.dismiss()
        panel = null
        detenerConsultaVoz()
        muestra.detener()
    }

    /**
     * ActividadCuenta llama aquí al destruirse. Libera el lector de muestras y retira
     * las respuestas pendientes de la pantalla.
     */
    fun cerrar() {
        pausar()
        muestra.cerrar()
        principal.removeCallbacksAndMessages(null)
    }

    /** Recibe una explicación breve del lector y la muestra como un mensaje temporal. */
    private fun informar(mensaje: String) {
        Toast.makeText(actividad, mensaje, Toast.LENGTH_LONG).show()
    }
}
