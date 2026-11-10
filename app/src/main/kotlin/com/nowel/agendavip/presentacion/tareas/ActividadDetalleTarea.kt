package com.nowel.agendavip.presentacion.tareas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.dominio.agenda.EstadoOcurrencia
import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.FormatoAgenda
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.presentacion.comun.VozDeMuestra
import com.nowel.agendavip.presentacion.musica.PreescuchaMusica
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Muestra todo lo guardado sobre una tarea en una fecha concreta.
 * Por ejemplo, Levantarme puede repetirse todos los días: completar el lunes
 * no completa automáticamente el martes. Por eso recibimos la tarea y su fecha.
 * El diseño tarea_pantalla_detalle.xml mantiene la barra de botones arriba
 * y permite desplazar únicamente la información que aparece debajo.
 */
class ActividadDetalleTarea : Activity() {

    // Recuerda la cuenta de esta pantalla y bloquea el regreso después de cerrar su sesión.
    private val proteccion = ProtectorSesion(this)

    private lateinit var repositorio: RepositorioTareas
    private lateinit var gestor: GestorRecordatorios
    private lateinit var voz: VozDeMuestra
    private lateinit var musica: PreescuchaMusica
    private lateinit var contenido: LinearLayout
    private var botonMuestra: Button? = null
    private var tarea: Tarea? = null
    private var id = ""
    private var fecha = LocalDate.now()

    private var trabajando = false
    private var visible = false
    private var visita = 0
    private var ventanaError: AlertDialog? = null

    /**
     * Android llama esta función al abrir Detalles.
     * Recibe el estado temporal de la pantalla y lee tarea_id y fecha_ocurrencia
     * de la petición de apertura. Conecta Volver, Editar y Eliminar.
     * Prepara las muestras de voz y canción, pero no reproduce nada al entrar.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!proteccion.permitir(estado)) {
            return
        }

        setContentView(R.layout.tarea_pantalla_detalle)
        VentanaAgenda.preparar(this, findViewById(R.id.tarea_raiz))
        repositorio = RepositorioTareas(this)
        gestor = GestorRecordatorios(this)
        contenido = findViewById(R.id.tarea_detalle_contenido)
        id = intent.getStringExtra("tarea_id").orEmpty()
        fecha = runCatching { LocalDate.parse(intent.getStringExtra("fecha_ocurrencia")) }.getOrDefault(LocalDate.now())
        voz = VozDeMuestra(this) { informar(it) }
        musica = PreescuchaMusica(this, { actualizarMuestra() }, { informar("No se pudo abrir esta canción. Puedes elegir otra al editar la tarea.") })
        findViewById<TextView>(R.id.tarea_pantalla_titulo).text = "Detalles"
        findViewById<View>(R.id.tarea_volver).setOnClickListener { finish() }
        findViewById<View>(R.id.tarea_editar).apply {
            visibility = View.VISIBLE
            isEnabled = false
            setOnClickListener { editar() }
        }
        findViewById<View>(R.id.tarea_eliminar).apply {
            visibility = View.VISIBLE
            isEnabled = false
            setOnClickListener { elegirEliminacion() }
        }
    }

    /**
     * Android llama aquí cuando Detalles vuelve a verse, también después de editar.
     * Llama mostrar para releer la tarea guardada y enseñar los cambios actuales.
     * Si se eliminó la tarea o ya no corresponde a esa fecha, la pantalla se cierra.
     */
    override fun onResume() {
        super.onResume()

        if (!proteccion.comprobar()) {
            return
        }

        visible = true
        mostrar()
    }

    /** Android entrega este paquete al girar; recuerda a qué cuenta pertenece la pantalla. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /**
     * Android llama aquí cuando salimos de Detalles o abrimos otra pantalla.
     * Detiene las muestras de voz y música para que no sigan sonando por detrás.
     * Estas muestras son las que se pidieron con los botones Escuchar; los avisos
     * programados de las tareas se administran aparte en GestorRecordatorios.
     */
    override fun onStop() {
        visible = false
        visita += 1
        ventanaError?.dismiss()
        ventanaError = null
        if (::voz.isInitialized) {
            voz.detener()
        }

        if (::musica.isInitialized) {
            musica.detener()
        }

        super.onStop()
    }

    /**
     * Android llama aquí al retirar esta pantalla de la memoria.
     * Cierra las herramientas de voz y música que preparaban las muestras.
     * Un cambio confirmado puede terminar en el trabajador compartido; la pantalla
     * siguiente leerá su resultado cuando se abra.
     */
    override fun onDestroy() {
        if (::voz.isInitialized) {
            voz.cerrar()
        }

        if (::musica.isInitialized) {
            musica.cerrar()
        }

        super.onDestroy()
    }

    /**
     * Al abrir Detalles, volver de Editar o terminar un cambio, solicita la tarea y su estado
     * en el trabajador. No recibe ni devuelve datos. Si la fecha dejó de existir, cierra Detalles.
     */
    private fun mostrar() {
        ejecutar(
            accion = { repositorio.leerDetalle(id, fecha) },
            alTerminar = { detalle ->
                if (detalle == null || !detalle.first.correspondeA(fecha) || detalle.second.omitida) {
                    finish()
                } else {
                    val (actual, estado) = detalle
                    dibujar(actual, estado)
                }
            },
        )
    }

    /**
     * mostrar le entrega la tarea y el estado recién leídos. Dibuja el horario, el interruptor,
     * la voz, la música y el seguimiento. Solo cambia lo visible y no devuelve datos.
     */
    private fun dibujar(actual: Tarea, estado: EstadoOcurrencia) {
        tarea = actual
        habilitarCabecera(true)
        contenido.removeAllViews()
        contenido.addView(ComponentesAgenda.texto(this, actual.titulo, 32f, titulo = true))
        contenido.addView(Switch(this).apply {
            text = if (estado.completada) "✓ Completada en esta fecha" else "○ Pendiente en esta fecha"
            setTextColor(getColor(if (estado.completada) R.color.agenda_completada else R.color.agenda_pendiente))
            isChecked = estado.completada
            minHeight = ComponentesAgenda.dp(this@ActividadDetalleTarea, 56)
            thumbTintList = ColorStateList.valueOf(getColor(R.color.marfil))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.agenda_completada), getColor(R.color.agenda_pendiente)))
            setOnCheckedChangeListener { control, marcada ->
                if (marcada != estado.completada) {
                    // La tarjeta refleja el valor guardado hasta que el gestor confirme el cambio.
                    control.isChecked = estado.completada
                    cambiarTarea {
                        gestor.marcarCompletada(this@ActividadDetalleTarea.id, fecha, marcada)
                    }
                }
            }
        })
        contenido.addView(ComponentesAgenda.tarjeta(this).apply {
            addView(ComponentesAgenda.texto(this@ActividadDetalleTarea, FormatoAgenda.fecha(fecha), 18f))
            addView(ComponentesAgenda.texto(this@ActividadDetalleTarea, FormatoAgenda.hora(actual.hora), 28f, titulo = true))
            addView(ComponentesAgenda.texto(this@ActividadDetalleTarea, FormatoAgenda.repeticion(actual), 16f))

            // Esta hora procede del receptor de Android, aunque después se edite el recordatorio.
            val ultimoAviso = estado.ultimaAlerta
            val textoRegistro = if (ultimoAviso != null) {
                "Último aviso activado: ${formatearMomento(ultimoAviso)}"
            } else {
                "Todavía no se ha activado un aviso para esta fecha."
            }
            addView(ComponentesAgenda.texto(this@ActividadDetalleTarea, textoRegistro, 14f))

            estado.alarmaProgramadaPara?.let { momento ->
                addView(ComponentesAgenda.texto(
                    this@ActividadDetalleTarea,
                    "Próximo aviso programado: ${formatearMomento(momento)}",
                    14f
                ))
            }

            estado.pospuestaHasta?.let {
                val instante = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                addView(ComponentesAgenda.texto(this@ActividadDetalleTarea,
                    "Pospuesta: ${FormatoAgenda.fecha(instante.toLocalDate())}, ${FormatoAgenda.hora(instante.toLocalTime())}", 14f))
            }
        })
        contenido.addView(ComponentesAgenda.texto(this, "Aviso por voz", 24f, titulo = true))
        contenido.addView(ComponentesAgenda.tarjeta(this, true).apply {
            addView(ComponentesAgenda.texto(this@ActividadDetalleTarea,
                if (actual.vozActiva) actual.mensajeParaAviso(false) else "Voz desactivada", 17f))
            if (actual.vozActiva) addView(ComponentesAgenda.boton(this@ActividadDetalleTarea, "Escuchar muestra") {
                musica.detener()
                voz.hablar(actual.mensajeParaAviso(false))
            })
        })
        contenido.addView(ComponentesAgenda.texto(this, "Música", 24f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(this, actual.descripcionMusica()))
        botonMuestra = null
        // En modo aleatorio todavía no existe un archivo fijo para preescuchar.
        // La muestra de una canción solo se ofrece si esa canción fue seleccionada expresamente.
        actual.musicaUri?.takeIf {
            actual.modoMusica == ModoMusica.SELECCIONADA
        }?.let { referencia ->
            botonMuestra = ComponentesAgenda.boton(this, "Escuchar canción") {
                voz.detener()
                musica.alternar(Uri.parse(referencia))
            }
            contenido.addView(botonMuestra)
            contenido.addView(ComponentesAgenda.texto(this, "La muestra dura hasta 30 segundos. El aviso reproduce la canción completa; puedes detenerla desde la notificación.", 13f, R.color.texto_secundario))
        }
        actualizarMuestra()
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Seguimiento", 24f, titulo = true))
        contenido.addView(ComponentesAgenda.tarjeta(this).apply {
            val pantalla = this@ActividadDetalleTarea
            if (!actual.seguimientoActivo) {
                addView(ComponentesAgenda.texto(pantalla, "Seguimiento desactivado", 17f))
                return@apply
            }

            addView(ComponentesAgenda.texto(pantalla, "Intervalo: ${actual.intervaloMinutos} min", 17f))

            // Posponer a mano también puede sumar avisos; el contador refleja el total real.
            val emitidos = estado.avisosEmitidos
            val limite = actual.limiteAvisos
            val progreso = if (emitidos <= limite) {
                "$emitidos de $limite avisos emitidos en esta fecha"
            } else {
                "$emitidos avisos emitidos en esta fecha · Límite automático: $limite"
            }
            addView(ComponentesAgenda.texto(pantalla, progreso, 16f))

            val estadoSeguimiento = when {
                estado.completada -> "La tarea está completada. El seguimiento se detuvo."
                estado.seguimientoDetenido -> "Seguimiento detenido para esta fecha."
                emitidos >= limite -> "Límite alcanzado. No se programarán más avisos automáticos para esta fecha."
                else -> null
            }
            if (estadoSeguimiento != null) {
                addView(ComponentesAgenda.texto(pantalla, estadoSeguimiento, 14f))
            }

            val etiquetaMensaje = when {
                !actual.vozActiva -> "Texto de la notificación"
                actual.mensajeSeguimiento.isBlank() -> "Frase automática en voz alta"
                else -> "Mensaje en voz alta"
            }
            addView(ComponentesAgenda.texto(
                pantalla,
                "$etiquetaMensaje: ${actual.mensajeParaAviso(true)}",
                14f,
            ))
        })
        if (!estado.completada) {
            val yaLlegoLaHora = !fecha.atTime(actual.hora).isAfter(LocalDateTime.now())
            contenido.addView(ComponentesAgenda.boton(this, "Posponer") { elegirPosposicion() }.apply { isEnabled = yaLlegoLaHora })
            contenido.addView(ComponentesAgenda.boton(this,
                if (fecha == LocalDate.now()) "Detener seguimiento hoy" else "Detener seguimiento de esta fecha") {
                cambiarTarea { gestor.detenerSeguimiento(id, fecha) }
            }.apply { isEnabled = actual.seguimientoActivo && estado.avisosEmitidos > 0 && !estado.seguimientoDetenido })
            if (!yaLlegoLaHora) contenido.addView(ComponentesAgenda.texto(this,
                "Podrás posponer el aviso cuando llegue su hora.", 13f, R.color.texto_secundario))
        }
    }

    /**
     * Recibe un [momento] guardado por Android y devuelve su fecha y hora de doce horas.
     * [mostrar] la utiliza para distinguir el último aviso activado del próximo programado.
     * Convierte los milisegundos guardados a la zona horaria del teléfono antes de mostrarlos.
     */
    private fun formatearMomento(momento: Long): String {
        val local = Instant.ofEpochMilli(momento).atZone(ZoneId.systemDefault())

        return "${FormatoAgenda.fecha(local.toLocalDate())}, ${FormatoAgenda.hora(local.toLocalTime())}"
    }

    /**
     * Actualiza el texto del botón que escucha la canción.
     * Según lo que esté haciendo el reproductor muestra Preparando, Pausar o Escuchar.
     * La herramienta PreescuchaMusica llama esta función al cambiar su estado.
     * No selecciona otra canción ni modifica la que está guardada en la tarea.
     */
    private fun actualizarMuestra() {
        botonMuestra?.text = when {
            musica.preparando -> "Preparando muestra…"
            musica.reproduciendo -> "Pausar muestra · ${musica.segundos} s"
            else -> "Escuchar canción"
        }
    }

    /**
     * Abre el formulario con los datos actuales de esta tarea.
     * Si se repite varios días, antes explica que se está editando la rutina completa.
     * Envía su identificador a ActividadEditarTarea para que esa pantalla la lea.
     * Los cambios se guardarán únicamente al pulsar Guardar en el formulario.
     */
    private fun editar() {
        val actual = tarea ?: return
        val abrir = { startActivity(Intent(this, ActividadEditarTarea::class.java).putExtra("tarea_id", id)) }
        if (actual.repeticion == Repeticion.UNA_VEZ) abrir()
        else AlertDialog.Builder(this).setTitle("Editar rutina")
            .setMessage("Los cambios de horario, mensaje y música se aplicarán a la rutina. Las fechas completadas conservarán su estado.")
            .setPositiveButton("Editar") { _, _ -> abrir() }.setNegativeButton("Cancelar", null).show()
    }

    /**
     * Se ejecuta al tocar Eliminar en la barra superior.
     * En una tarea única prepara su eliminación completa. En una rutina pregunta
     * si se quiere quitar Solo esta vez o Toda la rutina.
     * La elección aún no borra nada: confirmarEliminacion pide aceptarla primero.
     */
    private fun elegirEliminacion() {
        val actual = tarea ?: return
        if (actual.repeticion == Repeticion.UNA_VEZ) confirmarEliminacion(true)
        else AlertDialog.Builder(this).setTitle("¿Qué quieres eliminar?")
            .setItems(arrayOf("Solo esta vez", "Toda la rutina")) { _, opcion -> confirmarEliminacion(opcion == 1) }.show()
    }

    /**
     * Muestra la confirmación final antes de borrar.
     * Recibe serie: true elimina la tarea completa y sus fechas; false quita
     * solamente la fecha que estamos viendo y conserva las demás.
     * Si se acepta, GestorRecordatorios elimina lo elegido y cancela esos avisos.
     * Cancelar deja la tarea y sus alarmas como estaban.
     */
    private fun confirmarEliminacion(serie: Boolean) {
        AlertDialog.Builder(this).setTitle(if (serie) "¿Eliminar la tarea?" else "¿Eliminar solo esta vez?")
            .setMessage(if (serie) "Se eliminará su configuración y sus recordatorios." else "Las demás fechas de esta rutina seguirán disponibles.")
            .setPositiveButton("Eliminar") { _, _ ->
                cambiarTarea(alTerminar = { finish() }) {
                    gestor.eliminar(id, fecha, serie)
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /**
     * Abre las opciones de 5 minutos, 10 minutos u Otro intervalo.
     * La llaman los detalles cuando ya llegó la hora de un aviso pendiente.
     * Las opciones conocidas llaman posponer; Otro intervalo abre pedirMinutos.
     * Posponer no marca la tarea como completada.
     */
    private fun elegirPosposicion() {
        if (trabajando) {
            return
        }

        AlertDialog.Builder(this).setTitle("Posponer aviso")
            .setItems(arrayOf("5 minutos", "10 minutos", "Otro intervalo")) { _, opcion ->
                if (opcion < 2) posponer(if (opcion == 0) 5 else 10) else pedirMinutos()
            }.show()
    }

    /**
     * Pide cuántos minutos se quiere retrasar el aviso.
     * Crea una casilla que acepta números y comprueba que estén entre 1 y 1440.
     * Si el valor está mal, deja el cuadro abierto y señala la casilla.
     * Si está bien, cierra el cuadro y llama posponer con la cantidad elegida.
     */
    private fun pedirMinutos() {
        val campo = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Minutos (1 a 1440)"
        }
        val dialogo = AlertDialog.Builder(this).setTitle("¿Cuántos minutos?").setView(campo)
            .setPositiveButton("Posponer", null).setNegativeButton("Cancelar", null).create()
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutos = campo.text.toString().toIntOrNull()
                if (minutos == null || minutos !in 1..1440) campo.error = "Elige entre 1 y 1440 minutos."
                else {
                    dialogo.dismiss()
                    posponer(minutos)
                }
            }
        }
        dialogo.show()
    }

    /**
     * Pide al gestor un nuevo aviso después del tiempo elegido.
     * Recibe minutos, por ejemplo 10, y utiliza el identificador y la fecha
     * de la tarea visible. Detiene sus muestras, guarda la posposición y refresca
     * los detalles para mostrar la nueva hora. La tarea continúa pendiente.
     */
    private fun posponer(minutos: Int) {
        cambiarTarea(
            alTerminar = {
                mostrar()
                informar("Aviso pospuesto $minutos minutos.")
            },
        ) {
            gestor.posponer(id, fecha, minutos)
        }
    }

    /**
     * Recibe un cambio confirmado y qué mostrar al terminar; por defecto vuelve a leer Detalles.
     * Completar, posponer, detener y eliminar la llaman. Detiene las muestras en la pantalla
     * antes de enviar el cambio al trabajador. No devuelve datos.
     */
    private fun cambiarTarea(alTerminar: () -> Unit = ::mostrar, accion: () -> Unit) {
        if (!visible || trabajando || !proteccion.comprobar()) {
            return
        }

        voz.detener()
        musica.detener()
        ejecutar(accion, alFallar = ::mostrar) { alTerminar() }
    }

    /**
     * Recibe una operación sobre la agenda y las acciones de éxito y de error. Solo la
     * operación se ejecuta fuera de la pantalla; el resultado vuelve al hilo que dibuja Android.
     * Si la persona salió y volvió durante la espera, solicita una lectura para esa visita.
     * Conserva los cambios confirmados aunque Detalles se cierre. No devuelve datos.
     */
    private fun <T> ejecutar(
        accion: () -> T,
        alFallar: () -> Unit = {},
        alTerminar: (T) -> Unit,
    ) {
        if (!visible || trabajando) {
            return
        }

        trabajando = true
        habilitarCabecera(false)
        val visitaActual = visita
        trabajador.execute {
            val resultado: Result<T> = try {
                Result.success(accion())
            } catch (error: Exception) {
                Result.failure(error)
            }

            runOnUiThread {
                trabajando = false
                if (!visible || isFinishing || isDestroyed || !proteccion.comprobar()) {
                    return@runOnUiThread
                }

                habilitarCabecera(tarea != null)
                if (visitaActual != visita) {
                    resultado.onFailure(::mostrarError)
                    mostrar()
                    return@runOnUiThread
                }

                resultado.fold(
                    onSuccess = alTerminar,
                    onFailure = { error ->
                        mostrarError(error)
                        alFallar()
                    },
                )
            }
        }
    }

    /**
     * Recibe si Editar y Eliminar pueden tocarse. Ejecutar la llama al iniciar y al terminar
     * una operación; dibujar la llama cuando ya tiene datos para ambos botones.
     */
    private fun habilitarCabecera(disponible: Boolean) {
        findViewById<View>(R.id.tarea_editar).isEnabled = disponible
        findViewById<View>(R.id.tarea_eliminar).isEnabled = disponible
    }

    /**
     * Recibe el problema que informó el trabajador. Ejecutar la llama en la pantalla
     * para explicarlo y conservar lo que la persona estaba leyendo.
     */
    private fun mostrarError(error: Throwable) {
        if (error is IllegalArgumentException) {
            informar(error.message ?: "Revisa la configuración de esta tarea.")
        } else if (ventanaError?.isShowing != true) {
            ventanaError = AlertDialog.Builder(this)
                .setTitle("No se pudo actualizar la tarea")
                .setMessage(error.message ?: "Vuelve a abrir la tarea para revisar su estado.")
                .setPositiveButton("Entendido", null)
                .show()
        }
    }

    /**
     * Muestra una explicación corta encima de la pantalla durante unos segundos.
     * Recibe mensaje, el texto visible, y utiliza el aviso pequeño Toast de Android.
     * Sirve para comunicar una posposición o un problema sencillo sin perder los detalles.
     */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    private companion object {
        // Las instancias comparten el turno de lectura y escritura. Al girar el teléfono,
        // la nueva pantalla lee después de cualquier cambio que ya se hubiera solicitado.
        val trabajador = Executors.newSingleThreadExecutor()
    }
}
