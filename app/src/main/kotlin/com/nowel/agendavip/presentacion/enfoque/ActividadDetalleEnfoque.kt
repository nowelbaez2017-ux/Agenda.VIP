package com.nowel.agendavip.presentacion.enfoque

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.dominio.enfoque.TipoEventoEnfoque
import com.nowel.agendavip.presentacion.asistente.ActividadModelosLocales
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.recordatorios.enfoque.GestorEnfoque
import com.nowel.agendavip.recordatorios.enfoque.ServicioEnfoque

/**
 * Muestra una actividad de Enfoque y las acciones válidas para la etapa en que se encuentra.
 * El aviso no inicia la cuenta atrás: Comenzar lo hace. Al terminar tampoco se completa sola;
 * la persona decide Ya terminé o Continuamos. Android conserva sus alarmas fuera de esta pantalla.
 */
class ActividadDetalleEnfoque : Activity() {

    private val proteccion = ProtectorSesion(this)
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var trabajo: TrabajadorEnfoque
    private lateinit var repositorio: RepositorioEnfoque
    private lateinit var gestor: GestorEnfoque
    private lateinit var contenido: LinearLayout
    private var id = ""
    private var actual: ActividadEnfoque? = null
    private var yaIniciada = false
    private var contador: TextView? = null
    private var visible = false
    private var cargando = false
    private var actuando = false
    private var segundos = 0
    private var dialogo: AlertDialog? = null

    private val refrescar = object : Runnable {
        /**
         * Android llama al reloj mientras se ven los detalles. Cambia el contador solo cuando
         * se ve un número distinto y consulta cada dos segundos los cambios de alarmas
         * o botones de notificación.
         */
        override fun run() {
            if (!visible) {
                return
            }

            val dato = actual
            val vista = contador
            if (dato != null && vista != null) {
                val texto = FormatoEnfoque.contador(gestor.restanteMs(dato))
                if (!TextUtils.equals(vista.text, texto)) {
                    vista.text = texto
                }
            }
            segundos += 1

            if (segundos % 2 == 0) {
                consultar()
            }

            principal.postDelayed(this, 1000L)
        }
    }

    /** Android entrega la apertura. Comprueba el dueño y prepara una barra superior fija. */
    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)

        if (!proteccion.permitir(guardado)) {
            return
        }

        setContentView(R.layout.enfoque_pantalla_detalle)
        VentanaAgenda.preparar(this, findViewById(R.id.enfoque_detalle_raiz))
        contenido = findViewById(R.id.enfoque_detalle_contenido)
        repositorio = RepositorioEnfoque(this)
        gestor = GestorEnfoque(this)
        trabajo = TrabajadorEnfoque(this, ::mostrarError)
        id = intent.getStringExtra("enfoque_id").orEmpty()
        findViewById<View>(R.id.enfoque_detalle_volver).setOnClickListener { finish() }
        findViewById<View>(R.id.enfoque_detalle_editar).setOnClickListener { editar() }
        findViewById<View>(R.id.enfoque_detalle_eliminar).setOnClickListener { confirmarEliminar() }
        cambiarCabecera(habilitada = false)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { finish() }
        }
    }

    /** Al volver relee la actividad, sin volver a iniciar una sesión que ya estaba en curso. */
    override fun onResume() {
        super.onResume()

        if (!::trabajo.isInitialized || !proteccion.comprobar()) {
            return
        }

        visible = true
        cargando = false
        actuando = false
        trabajo.activar()
        consultar()
        principal.removeCallbacks(refrescar)
        principal.postDelayed(refrescar, 1000L)
    }

    /**
     * Recupera el estado actual en segundo plano. Si el tiempo terminó, pide al gestor
     * sincronizar ese final de forma idempotente, es decir, sin repetir un final ya registrado.
     */
    private fun consultar() {
        if (!visible || cargando || actuando) {
            return
        }

        cargando = true
        trabajo.consultarSincronizado(
            preparar = { gestor.actualizarVencidas() },
            leer = {
                val dato = repositorio.obtener(id)
                val iniciada = repositorio.eventos().any {
                    it.actividadId == id && it.tipo in setOf(TipoEventoEnfoque.INICIADA, TipoEventoEnfoque.CONTINUADA)
                }
                dato to iniciada
            },
            alRecibir = { resultado ->
                cargando = false
                val dato = resultado.first
                val cambioPermisoEditar = yaIniciada != resultado.second
                yaIniciada = resultado.second

                if (dato == null) {
                    contenido.isEnabled = true
                    contenido.removeAllViews()
                    contenido.addView(ComponentesAgenda.texto(this, "Esta actividad ya no está disponible.", 18f))
                    cambiarCabecera(false)
                    actual = null
                    return@consultarSincronizado
                }

                if (dato != actual || contenido.childCount == 0 || cambioPermisoEditar) {
                    actual = dato
                    dibujar(dato)
                }
            },
        )
    }

    /**
     * Recibe la actividad guardada y dibuja las decisiones que tienen sentido en esa etapa.
     * El contador solo representa el tiempo del gestor; no decide por sí mismo si la tarea se hizo.
     */
    private fun dibujar(dato: ActividadEnfoque) {
        contenido.isEnabled = true
        contenido.removeAllViews()
        contador = null
        contenido.addView(ComponentesAgenda.texto(this, dato.titulo, 28f, titulo = true))
        contenido.addView(ComponentesAgenda.espacio(this, 8))
        contenido.addView(ComponentesAgenda.texto(this, FormatoEnfoque.estado(dato.estado), 16f, R.color.texto_secundario))
        contenido.addView(ComponentesAgenda.texto(this, "Aviso: ${FormatoEnfoque.fechaHora(dato.avisoEnEpochMs)}", 15f))
        contenido.addView(ComponentesAgenda.texto(this, "Tiempo elegido: ${dato.duracionMinutos} minutos", 15f))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Tiempo dedicado registrado: ${FormatoEnfoque.contador(dato.acumuladoTrabajadoMs)}",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 20))

        when (dato.estado) {
            EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO -> {
                contenido.addView(ComponentesAgenda.texto(this, "¿Comenzamos?", 24f, titulo = true))
                contenido.addView(ComponentesAgenda.texto(
                    this,
                    "Cuando elijas Comenzar empezará tu tiempo de trabajo. Si estás ocupado, puedes pedir otro aviso.",
                    16f,
                ))
                accion("Comenzar", principal = true) { gestor.iniciar(id) }
                accionPosponer()
                mostrarVoz()
            }
            EstadoEnfoque.EN_CURSO -> {
                mostrarContador(dato, "Tiempo restante")
                accion("Pausar sesión") { gestor.pausar(id) }
                accion("Ya terminé", principal = true) { gestor.completar(id) }
                accionPosponer()
                mostrarVoz()
            }
            EstadoEnfoque.PAUSADA -> {
                mostrarContador(dato, "Tiempo que te queda")
                accion("Reanudar", principal = true) { gestor.continuar(id) }
                accion("Ya terminé") { gestor.completar(id) }
                accionPosponer()
                mostrarVoz()
            }
            EstadoEnfoque.ESPERANDO_RESPUESTA -> {
                contenido.addView(ComponentesAgenda.texto(this, "Dime, ¿ya terminaste o continuamos?", 26f, titulo = true))
                contenido.addView(ComponentesAgenda.texto(
                    this,
                    "El bloque de tiempo terminó. La actividad seguirá pendiente hasta que tú decidas.",
                    16f,
                ))
                accion("Ya terminé", principal = true) { gestor.completar(id) }
                contenido.addView(ComponentesAgenda.boton(this, "Continuamos") {
                    pedirMinutos("¿Cuántos minutos más?", dato.duracionMinutos, 1..480) { minutos ->
                        ejecutarAccion { gestor.continuar(id, minutos) }
                    }
                })
                mostrarVoz()
            }
            EstadoEnfoque.COMPLETADA -> {
                contenido.addView(ComponentesAgenda.texto(this, "Actividad completada", 25f, R.color.agenda_completada, true))
                contenido.addView(ComponentesAgenda.texto(this, "Su resultado y el tiempo dedicado quedan en el historial de Agenda.", 16f))
            }
            EstadoEnfoque.CANCELADA -> {
                contenido.addView(ComponentesAgenda.texto(this, "Actividad cancelada", 23f, titulo = true))
                contenido.addView(ComponentesAgenda.texto(this, "Sus avisos se detuvieron. El historial conserva lo que ocurrió.", 16f))
            }
        }

        if (!dato.musicaUri.isNullOrBlank()) {
            contenido.addView(ComponentesAgenda.espacio(this, 16))
            contenido.addView(ComponentesAgenda.texto(this, "Música: ${dato.musicaNombre.ifBlank { "Canción elegida" }}", 14f))
        }
        cambiarCabecera(habilitada = !actuando)
    }

    /** Recibe la actividad y la etiqueta; crea el único número que el reloj actualiza cada segundo. */
    private fun mostrarContador(dato: ActividadEnfoque, etiqueta: String) {
        contenido.addView(ComponentesAgenda.texto(this, etiqueta, 15f, R.color.texto_secundario))
        val reloj = ComponentesAgenda.texto(this, FormatoEnfoque.contador(gestor.restanteMs(dato)), 44f, titulo = true)
        contador = reloj.takeIf { dato.estado == EstadoEnfoque.EN_CURSO }
        contenido.addView(reloj)
    }

    /** Recibe texto y acción del gestor y añade un botón con la misma protección contra doble toque. */
    private fun accion(etiqueta: String, principal: Boolean = false, operacion: () -> ActividadEnfoque) {
        contenido.addView(ComponentesAgenda.boton(this, etiqueta, principal) {
            ejecutarAccion(operacion)
        })
    }

    /** Añade el botón que ofrece tiempos rápidos, sin obligar a escribir una hora nueva. */
    private fun accionPosponer() {
        contenido.addView(ComponentesAgenda.boton(this, "Recordármelo después") {
            elegirPosposicion()
        })
    }

    /**
     * Ofrece 30 minutos, una hora y dos horas; Otro permite escribir una cantidad distinta.
     * El gestor conserva el trabajo acumulado y los minutos restantes si la sesión estaba empezada.
     */
    private fun elegirPosposicion() {
        if (actuando) {
            return
        }

        dialogo = AlertDialog.Builder(this)
            .setTitle("¿Cuándo te lo recuerdo?")
            .setItems(arrayOf("En 30 minutos", "En una hora", "En dos horas", "Otro tiempo")) { _, opcion ->
                val minutos = listOf(30, 60, 120).getOrNull(opcion)

                if (minutos != null) {
                    ejecutarAccion { gestor.posponer(id, minutos) }
                } else {
                    pedirMinutos("Minutos hasta el próximo aviso", 30, 1..1440) { cantidad ->
                        ejecutarAccion { gestor.posponer(id, cantidad) }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Recibe título, valor inicial, límites y acción. Deja escribir minutos sin un selector largo.
     * Una entrada inválida mantiene el diálogo y señala cómo corregirlo; todavía no cambia la sesión.
     */
    private fun pedirMinutos(titulo: String, inicial: Int, rango: IntRange, aceptar: (Int) -> Unit) {
        if (actuando) {
            return
        }

        val campo = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(inicial.toString())
            selectAll()
            hint = "De ${rango.first} a ${rango.last}"
            setPadding(24, 20, 24, 20)
        }
        val cuadro = AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(campo)
            .setPositiveButton("Aceptar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        cuadro.setOnShowListener {
            cuadro.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val numero = campo.text.toString().toIntOrNull()

                if (numero == null || numero !in rango) {
                    campo.error = "Escribe un número entre ${rango.first} y ${rango.last}."
                } else {
                    cuadro.dismiss()
                    aceptar(numero)
                }
            }
        }
        dialogo = cuadro
        cuadro.show()
    }

    /**
     * Abre la pantalla de respuestas por voz de esta misma actividad, sin cambiar su estado.
     * Esa pantalla explica cómo activar el micrófono antes de bloquear el teléfono.
     */
    private fun mostrarVoz() {
        contenido.addView(ComponentesAgenda.espacio(this, 16))
        contenido.addView(ComponentesAgenda.texto(this, "También puedes responder hablando", 20f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Activa las respuestas por voz antes de bloquear el teléfono. Ese modo necesita el modelo de voz instalado y muestra su estado en una notificación.",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.boton(this, "Respuestas por voz") {
            abrirPantalla(ActividadRespuestaEnfoque::class.java)
        })
        contenido.addView(ComponentesAgenda.boton(this, "Preparar voz sin conexión") {
            ServicioEnfoque.apagarVoz(this, id)
            abrirPantalla(ActividadModelosLocales::class.java)
        })
    }

    /**
     * Recibe la pantalla elegida por un botón de voz. Le entrega la actividad actual
     * y su dueño para que Android no abra los datos bajo otra cuenta.
     */
    private fun abrirPantalla(clase: Class<out Activity>) {
        try {
            startActivity(
                Intent(this, clase)
                    .putExtra("enfoque_id", id)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(this)),
            )
        } catch (_: ActivityNotFoundException) {
            informar("No se pudo abrir este apartado. Revisa que esté incluido en esta versión de la app.")
        }
    }

    /**
     * Recibe una acción ya elegida y la guarda en el trabajador compartido.
     * Mientras termina desactiva las decisiones para no iniciar dos veces la misma sesión.
     */
    private fun ejecutarAccion(operacion: () -> ActividadEnfoque) {
        if (actuando || !proteccion.comprobar()) {
            return
        }

        actuando = true
        cambiarCabecera(false)
        habilitar(contenido, false)
        trabajo.cambiar(
            accion = operacion,
            alTerminar = { dato ->
                actuando = false
                if (actual?.estado == EstadoEnfoque.EN_CURSO || dato.estado == EstadoEnfoque.EN_CURSO) {
                    yaIniciada = true
                }
                actual = dato
                dibujar(dato)
            },
        )
    }

    /** Recibe un contenedor y activa o desactiva sus controles mientras se confirma un cambio. */
    private fun habilitar(vista: View, disponible: Boolean) {
        vista.isEnabled = disponible

        if (vista is android.view.ViewGroup) {
            for (indice in 0 until vista.childCount) {
                habilitar(vista.getChildAt(indice), disponible)
            }
        }
    }

    /** Mantiene Editar disponible solo antes de comenzar; Eliminar conserva el historial de cancelación. */
    private fun cambiarCabecera(habilitada: Boolean) {
        val dato = actual
        findViewById<View>(R.id.enfoque_detalle_editar).isEnabled = habilitada && puedeEditar(dato)
        findViewById<View>(R.id.enfoque_detalle_eliminar).isEnabled = habilitada &&
            dato != null && dato.estado != EstadoEnfoque.CANCELADA
    }

    /**
     * Recibe una actividad y devuelve si todavía se pueden cambiar sus datos iniciales.
     * Posponer una sesión empezada no permite borrar su tiempo de trabajo cambiando la duración.
     */
    private fun puedeEditar(dato: ActividadEnfoque?): Boolean = dato != null && !yaIniciada &&
        dato.acumuladoTrabajadoMs == 0L &&
        dato.estado in setOf(EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO)

    /** El botón superior Editar abre el mismo formulario, ahora con el código existente. */
    private fun editar() {
        if (!actuando && puedeEditar(actual)) {
            startActivity(
                Intent(this, ActividadEditarEnfoque::class.java)
                    .putExtra("enfoque_id", id)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(this)),
            )
        }
    }

    /** Pide confirmar la retirada de la actividad; sus eventos anteriores se conservan en Agenda. */
    private fun confirmarEliminar() {
        if (actuando || actual == null) {
            return
        }

        dialogo = AlertDialog.Builder(this)
            .setTitle("¿Eliminar esta actividad de Enfoque?")
            .setMessage("Se detendrán sus avisos y su sesión. El historial de lo que ya hiciste se conservará en Agenda.")
            .setPositiveButton("Eliminar") { _, _ ->
                ejecutarAccion { gestor.cancelar(id) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Explica el fallo y vuelve a consultar el estado real, sin marcar una tarea como completada por error. */
    private fun mostrarError(mensaje: String) {
        cargando = false
        actuando = false
        actual?.let { dibujar(it) }
        informar(mensaje)
    }

    /** Recibe una explicación breve y la muestra con un aviso temporal. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    /** Guarda únicamente el dueño; la cuenta atrás y sus decisiones ya están en el repositorio. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /** Al salir retira consultas y relojes visuales. No pausa ni completa una sesión que estaba en marcha. */
    override fun onStop() {
        visible = false
        principal.removeCallbacksAndMessages(null)
        dialogo?.dismiss()
        dialogo = null
        if (::trabajo.isInitialized) {
            trabajo.pausar()
        }
        super.onStop()
    }

    /** Al destruirse libera solo el lector de pantalla, sin cancelar cambios confirmados. */
    override fun onDestroy() {
        if (::trabajo.isInitialized) {
            trabajo.cerrar()
        }
        super.onDestroy()
    }
}
