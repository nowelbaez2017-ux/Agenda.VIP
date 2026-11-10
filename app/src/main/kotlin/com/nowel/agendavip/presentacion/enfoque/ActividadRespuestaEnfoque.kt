package com.nowel.agendavip.presentacion.enfoque

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.asistente.ModelosLocales
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.ConversacionEnfoque
import com.nowel.agendavip.datos.enfoque.MensajeVozEnfoque
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.presentacion.asistente.ActividadModelosLocales
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.presentacion.cuenta.ActividadCuenta
import com.nowel.agendavip.recordatorios.enfoque.ServicioEnfoque
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Permite elegir respuestas por voz para una sola actividad y leer lo que se ha hablado.
 * La persona activa el micrófono con esta pantalla visible. Después, el servicio conserva
 * ese modo mientras la sesión sigue pendiente y ofrece desactivarlo desde su notificación.
 * Salir de esta pantalla cancela sus consultas; no apaga el modo que la persona eligió.
 */
class ActividadRespuestaEnfoque : Activity() {

    private val proteccion = ProtectorSesion(this)
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var trabajo: TrabajadorEnfoque
    private lateinit var repositorio: RepositorioEnfoque
    private lateinit var conversacion: ConversacionEnfoque
    private lateinit var modelos: ModelosLocales
    private lateinit var espacio: EspacioPersonal
    private lateinit var nombreActividad: TextView
    private lateinit var estadoVoz: TextView
    private lateinit var estadoModelo: TextView
    private lateinit var ultimoError: TextView
    private lateinit var historial: LinearLayout
    private lateinit var activar: Button
    private lateinit var desactivar: Button
    private var id = ""
    private var actual: ActividadEnfoque? = null
    private var mensajes: List<MensajeVozEnfoque>? = null
    private var modeloPreparado = false
    private var reanudada = false
    private var visible = false
    private var cargando = false
    private var activarCuandoVisible = false
    private var esperandoActivacionHasta = 0L
    private var segundos = 0

    /** Agrupa la lectura del trabajador para entregar a la pantalla datos de la misma consulta. */
    private data class DatosPantalla(
        val actividad: ActividadEnfoque?,
        val mensajes: List<MensajeVozEnfoque>,
        val modeloPreparado: Boolean,
        val ultimoError: String?,
    )

    private val refrescar = object : Runnable {
        /** Consulta el estado real del servicio cada segundo y el historial cada dos segundos. */
        override fun run() {
            if (!visible) {
                return
            }

            actualizarEstado()
            segundos += 1

            if (segundos % 2 == 0) {
                consultar()
            }

            principal.postDelayed(this, 1000L)
        }
    }

    /** Android entrega la apertura; comprueba la cuenta antes de crear lectores o mostrar mensajes. */
    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)

        if (!proteccion.permitir(guardado)) {
            return
        }

        id = intent.getStringExtra("enfoque_id").orEmpty()
        repositorio = RepositorioEnfoque(this)
        conversacion = ConversacionEnfoque(this)
        modelos = ModelosLocales(this)
        espacio = EspacioPersonal(this)
        trabajo = TrabajadorEnfoque(this) { mensaje ->
            cargando = false
            informar(mensaje)
        }
        prepararDiseno()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { finish() }
        }
    }

    /**
     * Construye una barra fija y una columna con estado, controles e historial de esta actividad.
     * Las piezas comunes conservan el aspecto ciruela y marfil de las otras pantallas.
     */
    private fun prepararDiseno() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.marfil))
        }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadRespuestaEnfoque, 12)
            setPadding(margen, margen, margen, margen)
            setBackgroundColor(getColor(R.color.ciruela))
        }
        barra.addView(ComponentesAgenda.boton(this, "‹", true) { finish() }.apply {
            textSize = 28f
            contentDescription = "Volver a los detalles"
            layoutParams = LinearLayout.LayoutParams(ComponentesAgenda.dp(this@ActividadRespuestaEnfoque, 48), -2)
        })
        barra.addView(ComponentesAgenda.texto(this, "Respuestas por voz", 23f, R.color.marfil, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        raiz.addView(barra)

        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadRespuestaEnfoque, 20)
            setPadding(margen, margen, margen, margen)
        }
        nombreActividad = ComponentesAgenda.texto(this, "Cargando tu sesión…", 25f, titulo = true)
        contenido.addView(nombreActividad)
        contenido.addView(ComponentesAgenda.espacio(this, 12))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Este modo sigue una actividad a la vez. Actívalo antes de bloquear el teléfono: Agenda escucha una respuesta corta después de sus preguntas y puedes desactivarlo desde la notificación.",
            15f,
        ))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Responde a las preguntas de esta sesión: por ejemplo, «ya terminé», «continuamos quince minutos» o «recuérdamelo en una hora». Te pedirá confirmar la decisión antes de guardarla. Activa este modo con el botón: decir «Hola Agenda» todavía no abre la escucha.",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 16))
        estadoVoz = ComponentesAgenda.texto(this, "Consultando el estado de voz…", 17f, titulo = true)
        contenido.addView(estadoVoz)
        estadoModelo = ComponentesAgenda.texto(this, "Consultando el modelo de voz…", 14f, R.color.texto_secundario)
        contenido.addView(estadoModelo)

        activar = ComponentesAgenda.boton(this, "Activar respuestas por voz", true) { pedirActivacion() }
        activar.isEnabled = false
        contenido.addView(activar)
        desactivar = ComponentesAgenda.boton(this, "Desactivar respuestas por voz") {
            activarCuandoVisible = false
            esperandoActivacionHasta = 0L
            ServicioEnfoque.apagarVoz(this, id)
            actualizarEstado()
        }
        desactivar.isEnabled = false
        contenido.addView(desactivar)
        contenido.addView(ComponentesAgenda.boton(this, "Preparar voz sin conexión") { abrirPreparacion() })
        contenido.addView(ComponentesAgenda.boton(this, "Permisos del teléfono") {
            startActivity(
                Intent(this, ActividadCuenta::class.java)
                    .putExtra("seccion", "permisos")
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, espacio.clave),
            )
        })
        ultimoError = ComponentesAgenda.texto(this, "", 14f, R.color.texto_secundario)
        contenido.addView(ultimoError)
        contenido.addView(ComponentesAgenda.espacio(this, 22))
        contenido.addView(ComponentesAgenda.texto(this, "Lo que hemos hablado", 23f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Aquí se conservan las frases de esta actividad como texto en tu cuenta del teléfono.",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 12))
        historial = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contenido.addView(historial)
        val desplazamiento = ScrollView(this).apply {
            isFillViewport = true
            addView(contenido)
        }
        raiz.addView(desplazamiento, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
        VentanaAgenda.preparar(this, raiz)
    }

    /** Al volver activa solo las consultas y atiende una autorización de micrófono ya solicitada. */
    override fun onResume() {
        super.onResume()

        if (!::trabajo.isInitialized || !proteccion.comprobar()) {
            return
        }

        reanudada = true
        visible = true
        cargando = false
        segundos = 0
        trabajo.activar()
        consultar()
        actualizarEstado()
        activarSiCorresponde()
        principal.removeCallbacks(refrescar)
        principal.postDelayed(refrescar, 1000L)
    }

    /** Android avisa antes de ocultarla; impide iniciar micrófono desde una pantalla que perdió su turno. */
    override fun onPause() {
        reanudada = false
        super.onPause()
    }

    /** Recupera actividad, modelo y conversación fuera del trabajo que Android usa para dibujar. */
    private fun consultar() {
        if (!visible || cargando) {
            return
        }

        cargando = true
        trabajo.consultar(
            leer = {
                DatosPantalla(
                    repositorio.obtener(id),
                    conversacion.listar(id),
                    modelos.instalado(),
                    espacio.preferencias("voz_enfoque").getString("ultimo_error", null),
                )
            },
            alRecibir = { datos ->
                cargando = false
                actual = datos.actividad
                modeloPreparado = datos.modeloPreparado
                nombreActividad.text = datos.actividad?.titulo ?: "Esta actividad ya no está disponible"
                estadoModelo.text = if (modeloPreparado) {
                    "Modelo de voz instalado. Su compatibilidad se comprobará al escuchar."
                } else {
                    "Prepara el modelo de voz para responder sin conexión."
                }
                ultimoError.text = datos.ultimoError?.let { "Último problema de voz: $it" }.orEmpty()

                if (mensajes != datos.mensajes) {
                    mensajes = datos.mensajes
                    mostrarMensajes(datos.mensajes)
                }
                actualizarEstado()
            },
        )
    }

    /**
     * Dibuja el estado que devuelve el servicio, sin confundir un archivo instalado con escucha activa.
     * La activación solicitada tiene una espera breve; si Android la rechaza permite volver a intentarlo.
     * Si la voz está activa, Responder ahora permite repetir la pregunta sin apagar primero el modo.
     */
    private fun actualizarEstado() {
        val activa = ServicioEnfoque.modoVozActivo(this, id)

        if (activa || SystemClock.uptimeMillis() >= esperandoActivacionHasta) {
            esperandoActivacionHasta = 0L
        }
        val pendiente = esperandoActivacionHasta > 0L
        estadoVoz.text = if (pendiente && !activa) {
            "Activación solicitada. Esperando al servicio de voz…"
        } else {
            ServicioEnfoque.estadoVoz(this, id)
        }
        val disponible = actual?.estado?.let {
            it != EstadoEnfoque.COMPLETADA && it != EstadoEnfoque.CANCELADA
        } == true
        activar.isEnabled = reanudada && disponible && modeloPreparado && !pendiente
        desactivar.isEnabled = activa
        activar.text = if (activa) "Responder ahora" else "Activar respuestas por voz"
    }

    /**
     * El botón Activar comprueba el modelo y solicita el permiso de micrófono cuando hace falta.
     * Todavía no graba una frase: el servicio abrirá la escucha después de leer su pregunta.
     */
    private fun pedirActivacion() {
        if (!reanudada || !proteccion.comprobar() || !modeloPreparado) {
            return
        }

        activarCuandoVisible = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), SOLICITUD_MICROFONO)
        } else {
            activarSiCorresponde()
        }
    }

    /**
     * Activa el servicio solo después de una elección y con la pantalla reanudada y permiso concedido.
     * El permiso puede llegar antes de onResume; por eso conserva la elección hasta volver a estar visible.
     */
    private fun activarSiCorresponde() {
        if (!activarCuandoVisible || !reanudada || !proteccion.comprobar() ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        activarCuandoVisible = false
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            informar("Activa las notificaciones para ver el control que permite apagar la voz con el teléfono bloqueado.")
        }
        esperandoActivacionHasta = SystemClock.uptimeMillis() + 7000L
        ServicioEnfoque.activarVoz(this, id)
        actualizarEstado()
    }

    /** Android entrega el permiso solicitado; una negativa conserva los botones manuales de la sesión. */
    override fun onRequestPermissionsResult(solicitud: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(solicitud, permisos, resultados)

        if (solicitud != SOLICITUD_MICROFONO || !::trabajo.isInitialized) {
            return
        }

        if (resultados.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            activarSiCorresponde()
        } else {
            activarCuandoVisible = false
            informar("Puedes permitir el micrófono en Permisos del teléfono cuando quieras usar respuestas por voz.")
        }
    }

    /**
     * Apaga las respuestas de esta actividad antes de preparar o sustituir el modelo de voz.
     * Abre la preparación existente con el dueño y solicita solamente el archivo de voz.
     */
    private fun abrirPreparacion() {
        activarCuandoVisible = false
        esperandoActivacionHasta = 0L
        ServicioEnfoque.apagarVoz(this, id)
        startActivity(
            Intent(this, ActividadModelosLocales::class.java)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, espacio.clave),
        )
    }

    /** Recibe las intervenciones y crea una tarjeta por mensaje; no cambia ninguna decisión de la sesión. */
    private fun mostrarMensajes(datos: List<MensajeVozEnfoque>) {
        historial.removeAllViews()

        if (datos.isEmpty()) {
            historial.addView(ComponentesAgenda.texto(this, "Las preguntas y tus respuestas aparecerán aquí.", 15f))
            return
        }

        datos.forEach { mensaje ->
            val tarjeta = ComponentesAgenda.tarjeta(this, mensaje.rol == "Tú")
            tarjeta.addView(ComponentesAgenda.texto(this, mensaje.rol, 16f, titulo = true))
            tarjeta.addView(ComponentesAgenda.texto(this, mensaje.texto, 16f))
            val hora = Instant.ofEpochMilli(mensaje.momento).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM · h:mm a", Locale("es", "DO")))
            tarjeta.addView(ComponentesAgenda.texto(this, hora, 12f, R.color.texto_secundario))
            historial.addView(tarjeta)
        }
    }

    /** Recibe una explicación y la muestra brevemente sin modificar la conversación guardada. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    /** Conserva el dueño al girar; una recreación no activa por sí misma un micrófono nuevo. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /** Al ocultarse retira consultas y actualizaciones visuales, manteniendo el modo de voz elegido. */
    override fun onStop() {
        visible = false
        principal.removeCallbacksAndMessages(null)
        if (::trabajo.isInitialized) {
            trabajo.pausar()
        }
        super.onStop()
    }

    /** Libera el lector de esta pantalla; el servicio se controla con Desactivar o con su notificación. */
    override fun onDestroy() {
        activarCuandoVisible = false
        if (::trabajo.isInitialized) {
            trabajo.cerrar()
        }
        super.onDestroy()
    }

    private companion object {
        const val SOLICITUD_MICROFONO = 932
    }
}
