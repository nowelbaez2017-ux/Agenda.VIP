package com.nowel.agendavip.presentacion.enfoque

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.dominio.enfoque.TipoEventoEnfoque
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.presentacion.musica.ActividadSelectorMusica
import com.nowel.agendavip.recordatorios.enfoque.GestorEnfoque
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

/**
 * Crea o modifica una actividad propia de Enfoque antes de que empiece.
 * La hora del aviso y la duración son distintos: el aviso invita a empezar, pero el contador
 * solo comienza al elegir Comenzar. No crea una tarea normal ni la coloca en Inicio.
 */
class ActividadEditarEnfoque : Activity() {

    private val proteccion = ProtectorSesion(this)
    private lateinit var trabajador: TrabajadorEnfoque
    private lateinit var repositorio: RepositorioEnfoque
    private lateinit var gestor: GestorEnfoque
    private lateinit var titulo: EditText
    private lateinit var fecha: EditText
    private lateinit var hora: EditText
    private lateinit var periodo: RadioGroup
    private lateinit var duracion: EditText
    private lateinit var guardar: Button
    private var id = ""
    private var editando = false
    private var musicaUri: String? = null
    private var musicaNombre = ""
    private var firmaInicial = ""
    private var guardando = false
    private var listaParaGuardar = false
    private var guardada = false
    private var borradorRestaurado = false

    /**
     * Android entrega el estado al abrir o girar. Prepara campos y conserva un código único
     * para que repetir Guardar después de una recreación no duplique la misma actividad.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!proteccion.permitir(estado)) {
            return
        }

        setContentView(R.layout.enfoque_pantalla_editar)
        VentanaAgenda.preparar(this, findViewById(R.id.enfoque_editar_raiz))
        repositorio = RepositorioEnfoque(this)
        gestor = GestorEnfoque(this)
        trabajador = TrabajadorEnfoque(this, ::mostrarError)
        titulo = findViewById(R.id.enfoque_campo_titulo)
        fecha = findViewById(R.id.enfoque_campo_fecha)
        hora = findViewById(R.id.enfoque_campo_hora)
        periodo = findViewById(R.id.enfoque_periodo)
        duracion = findViewById(R.id.enfoque_campo_duracion)
        guardar = findViewById(R.id.enfoque_guardar)
        editando = intent.hasExtra("enfoque_id")
        id = estado?.getString("enfoque_borrador_id")
            ?: intent.getStringExtra("enfoque_id")
            ?: UUID.randomUUID().toString()
        findViewById<TextView>(R.id.enfoque_editar_titulo).text =
            if (editando) "Editar sesión" else "Nueva sesión"
        findViewById<View>(R.id.enfoque_editar_volver).setOnClickListener { volver() }
        findViewById<View>(R.id.enfoque_elegir_musica).setOnClickListener { abrirMusica() }
        guardar.setOnClickListener { guardarActividad() }
        prepararCampos(estado)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { volver() }
        }
    }

    /**
     * Recibe un borrador anterior, si existe. Mantiene los textos tal como se estaban escribiendo.
     * Una actividad nueva propone un aviso dentro de cinco minutos y una duración de treinta.
     */
    private fun prepararCampos(estado: Bundle?) {
        val propuesta = ZonedDateTime.now().plusMinutes(5).withSecond(0).withNano(0)
        titulo.setText(estado?.getString("enfoque_borrador_titulo").orEmpty())
        fecha.setText(estado?.getString("enfoque_borrador_fecha") ?: propuesta.format(FECHA))
        hora.setText(estado?.getString("enfoque_borrador_hora") ?: propuesta.format(HORA))
        duracion.setText(estado?.getString("enfoque_borrador_duracion") ?: "30")
        periodo.check(
            estado?.getInt("enfoque_borrador_periodo")
                ?: if (propuesta.hour < 12) R.id.enfoque_periodo_am else R.id.enfoque_periodo_pm,
        )
        musicaUri = estado?.getString("enfoque_borrador_musica_uri")
        musicaNombre = estado?.getString("enfoque_borrador_musica_nombre").orEmpty()
        borradorRestaurado = estado != null
        actualizarMusica()
        firmaInicial = estado?.getString("enfoque_firma_inicial") ?: firma()
        listaParaGuardar = !editando
        guardar.isEnabled = listaParaGuardar
    }

    /** Al volver verifica la cuenta y consulta la actividad si se abrió para editarla. */
    override fun onResume() {
        super.onResume()

        if (!::trabajador.isInitialized || !proteccion.comprobar()) {
            return
        }

        trabajador.activar()
        guardando = false
        guardar.text = "Guardar sesión"

        if (editando) {
            cargarOriginal()
        } else {
            listaParaGuardar = true
            guardar.isEnabled = true
        }
    }

    /**
     * Recupera el estado guardado antes de permitir editar. Un borrador de rotación conserva sus textos;
     * solo la primera apertura rellena los campos con los datos del repositorio.
     */
    private fun cargarOriginal() {
        listaParaGuardar = false
        guardar.isEnabled = false
        trabajador.consultar(
            leer = {
                val dato = repositorio.obtener(id)
                val iniciada = repositorio.eventos().any {
                    it.actividadId == id && it.tipo in setOf(TipoEventoEnfoque.INICIADA, TipoEventoEnfoque.CONTINUADA)
                }
                dato to iniciada
            },
            alRecibir = { resultado ->
                val dato = resultado.first

                if (dato == null) {
                    mostrarError("Esta actividad ya no está disponible.")
                    return@consultar
                }

                if (dato.estado !in setOf(EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO) ||
                    dato.acumuladoTrabajadoMs > 0L || resultado.second
                ) {
                    mostrarError("La sesión ya empezó o terminó. Vuelve a sus detalles para continuar o completarla.")
                    return@consultar
                }

                if (!borradorRestaurado) {
                    val momento = java.time.Instant.ofEpochMilli(dato.avisoEnEpochMs)
                        .atZone(ZoneId.systemDefault())
                    titulo.setText(dato.titulo)
                    fecha.setText(momento.format(FECHA))
                    hora.setText(momento.format(HORA))
                    periodo.check(if (momento.hour < 12) R.id.enfoque_periodo_am else R.id.enfoque_periodo_pm)
                    duracion.setText(dato.duracionMinutos.toString())
                    musicaUri = dato.musicaUri
                    musicaNombre = dato.musicaNombre
                    actualizarMusica()
                    firmaInicial = firma()
                    borradorRestaurado = true
                }

                listaParaGuardar = true
                guardar.isEnabled = !guardando
            },
        )
    }

    /** Conserva el texto crudo y la canción antes de recrear la pantalla; todavía no guarda una sesión. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)

        if (::titulo.isInitialized) {
            estado.putString("enfoque_borrador_id", id)
            estado.putString("enfoque_borrador_titulo", titulo.text.toString())
            estado.putString("enfoque_borrador_fecha", fecha.text.toString())
            estado.putString("enfoque_borrador_hora", hora.text.toString())
            estado.putString("enfoque_borrador_duracion", duracion.text.toString())
            estado.putInt("enfoque_borrador_periodo", periodo.checkedRadioButtonId)
            estado.putString("enfoque_borrador_musica_uri", musicaUri)
            estado.putString("enfoque_borrador_musica_nombre", musicaNombre)
            estado.putString("enfoque_firma_inicial", firmaInicial)
        }

        super.onSaveInstanceState(estado)
    }

    /** Abre la biblioteca musical existente con el título escrito; volver conserva todos los campos. */
    @Suppress("DEPRECATION")
    private fun abrirMusica() {
        if (guardando) {
            return
        }

        startActivityForResult(
            Intent(this, ActividadSelectorMusica::class.java)
                .putExtra("tarea_titulo", titulo.text.toString().ifBlank { "Sesión de enfoque" })
                .putExtra("musica_uri", musicaUri)
                .putExtra("musica_nombre", musicaNombre)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(this)),
            SOLICITUD_MUSICA,
        )
    }

    /** Recibe la canción autorizada o Sin música; cancelar no cambia la selección actual. */
    @Deprecated("Contrato nativo de Android para recibir la canción elegida.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)

        if (solicitud == SOLICITUD_MUSICA && resultado == RESULT_OK && datos != null &&
            ::titulo.isInitialized && proteccion.comprobar()
        ) {
            musicaUri = datos.getStringExtra("musica_uri")
            musicaNombre = datos.getStringExtra("musica_nombre").orEmpty()
            actualizarMusica()
        }
    }

    /** Escribe el nombre de la canción en el botón sin iniciar ninguna reproducción de sonido. */
    private fun actualizarMusica() {
        findViewById<Button>(R.id.enfoque_elegir_musica).text = if (musicaUri.isNullOrBlank()) {
            "Elegir canción · Sin música"
        } else {
            "Cambiar canción · ${musicaNombre.ifBlank { "Canción elegida" }}"
        }
    }

    /** Agrupa los valores ya revisados por el formulario; no se conserva como otro historial. */
    private data class DatosFormulario(
        val titulo: String,
        val avisoEn: Long,
        val duracion: Int,
        val musicaUri: String?,
        val musicaNombre: String,
    )

    /**
     * El botón Guardar llama aquí. Comprueba los campos y entrega una copia al trabajador compartido.
     * Ese guardado confirmado no depende de que la pantalla permanezca abierta hasta terminar.
     */
    private fun guardarActividad() {
        if (guardando || !listaParaGuardar || !proteccion.comprobar()) {
            return
        }

        val datos = leerFormulario() ?: return
        guardando = true
        guardar.isEnabled = false
        guardar.text = "Guardando…"
        trabajador.cambiar(
            accion = {
                if (editando) {
                    gestor.editar(id, datos.titulo, datos.avisoEn, datos.duracion, datos.musicaUri, datos.musicaNombre)
                } else {
                    gestor.crear(datos.titulo, datos.avisoEn, datos.duracion, datos.musicaUri, datos.musicaNombre, id)
                }
            },
            alTerminar = {
                guardada = true
                informar("Sesión guardada en Enfoque. El contador comenzará cuando elijas Comenzar.")
                finish()
            },
        )
    }

    /**
     * Lee la fecha real, la hora de doce horas y los minutos. Devuelve datos válidos o null.
     * Si hay un error conserva lo escrito y señala el campo que la persona debe corregir.
     */
    private fun leerFormulario(): DatosFormulario? {
        listOf(titulo, fecha, hora, duracion).forEach { it.error = null }
        val nombre = titulo.text.toString().trim()

        if (nombre.isBlank()) {
            titulo.error = "Escribe en qué vas a trabajar."
            titulo.requestFocus()
            return null
        }

        val dia = try {
            LocalDate.parse(fecha.text.toString().trim(), FECHA)
        } catch (_: Exception) {
            fecha.error = "Escribe una fecha real como 28/10/2026."
            fecha.requestFocus()
            return null
        }
        val partes = Regex("^(0?[1-9]|1[0-2])(?::([0-5][0-9]))?$")
            .matchEntire(hora.text.toString().trim())

        if (partes == null || periodo.checkedRadioButtonId !in setOf(R.id.enfoque_periodo_am, R.id.enfoque_periodo_pm)) {
            hora.error = "Escribe una hora entre 1 y 12, como 5:30, y elige a. m. o p. m."
            hora.requestFocus()
            return null
        }

        val horaDoce = partes.groupValues[1].toInt()
        val minutosHora = partes.groupValues[2].ifBlank { "0" }.toInt()
        val horaCompleta = horaDoce % 12 + if (periodo.checkedRadioButtonId == R.id.enfoque_periodo_pm) 12 else 0
        val momento = dia.atTime(LocalTime.of(horaCompleta, minutosHora))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (momento <= System.currentTimeMillis()) {
            hora.error = "Elige una fecha y hora futuras para el aviso."
            hora.requestFocus()
            return null
        }

        val minutos = duracion.text.toString().toIntOrNull()

        if (minutos == null || minutos !in 1..480) {
            duracion.error = "Escribe de 1 a 480 minutos de trabajo."
            duracion.requestFocus()
            return null
        }

        return DatosFormulario(nombre, momento, minutos, musicaUri, musicaNombre)
    }

    /** Forma una firma de los campos para preguntar antes de descartar un borrador que cambió. */
    private fun firma(): String = listOf(
        titulo.text.toString(),
        fecha.text.toString(),
        hora.text.toString(),
        periodo.checkedRadioButtonId.toString(),
        duracion.text.toString(),
        musicaUri.orEmpty(),
    ).joinToString("\u0001")

    /** La flecha y Atrás preguntan antes de descartar un borrador; un guardado ya aceptado termina aparte. */
    private fun volver() {
        if (!::titulo.isInitialized || guardada || guardando || firma() == firmaInicial) {
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("¿Salir sin guardar?")
                .setMessage("Se descartarán los cambios que todavía no hayas guardado.")
                .setPositiveButton("Salir") { _, _ -> finish() }
                .setNegativeButton("Seguir editando", null)
                .show()
        }
    }

    /** Atiende el botón Atrás en versiones que todavía no usan el gesto registrado en onCreate. */
    @Deprecated("Android 13 o posterior utiliza el callback de onCreate.")
    override fun onBackPressed() {
        volver()
    }

    /** Explica un error sin vaciar el formulario y vuelve a habilitar Guardar si es posible. */
    private fun mostrarError(mensaje: String) {
        guardando = false
        if (::guardar.isInitialized) {
            guardar.isEnabled = listaParaGuardar
            guardar.text = "Guardar sesión"
        }
        informar(mensaje)
    }

    /** Recibe un mensaje breve y lo muestra con un aviso temporal de Android. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    /** Al ocultarse cancela la lectura de pantalla, pero no el guardado confirmado ni sus alarmas. */
    override fun onStop() {
        if (::trabajador.isInitialized) {
            trabajador.pausar()
        }
        super.onStop()
    }

    /** Al destruirse libera únicamente el lector de la pantalla. */
    override fun onDestroy() {
        if (::trabajador.isInitialized) {
            trabajador.cerrar()
        }
        super.onDestroy()
    }

    private companion object {
        val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.ROOT)
        const val SOLICITUD_MUSICA = 931
    }
}