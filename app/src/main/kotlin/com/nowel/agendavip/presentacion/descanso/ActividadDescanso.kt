package com.nowel.agendavip.presentacion.descanso

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.presentacion.comun.VozDeMuestra
import com.nowel.agendavip.recordatorios.descanso.GestorDescanso
import com.nowel.agendavip.recordatorios.descanso.ServicioAccesibilidadDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Configura una rutina nocturna voluntaria, independiente de las tareas y de Enfoque.
 * Muestra horarios de doce horas, días, aplicaciones elegidas y un mensaje personal.
 * Guarda en el espacio de la cuenta y pide cada autorización mediante los ajustes de Android.
 */
class ActividadDescanso : Activity() {

    private val proteccion = ProtectorSesion(this)
    private val trabajador = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    // Android conecta el contexto después de construir la actividad. La voz necesita ese
    // contexto, por eso se crea en onCreate y no al declarar esta propiedad.
    private lateinit var vozMuestra: VozDeMuestra
    private lateinit var repositorio: RepositorioDescanso
    private lateinit var formulario: FormularioDescanso
    private var propietario = ""
    private var guardando = false
    private var dialogo: AlertDialog? = null
    private var aplicacionesDisponibles = emptyList<AplicacionDescanso>()

    /**
     * Android abre esta pantalla desde Configuración o desde el aviso de descanso.
     * Recibe el borrador anterior, comprueba su cuenta y prepara los botones antes de leer datos.
     * No activa restricciones por abrir la pantalla; la persona decide qué guardar y autorizar.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (!proteccion.permitir(estado)) {
            return
        }

        vozMuestra = VozDeMuestra(this) { informar(it) }
        propietario = EspacioPersonal.claveActual(this).orEmpty()
        repositorio = RepositorioDescanso(this)
        setContentView(R.layout.descanso_configuracion)
        VentanaAgenda.preparar(this, findViewById(R.id.descanso_raiz))
        findViewById<TextView>(R.id.tarea_pantalla_titulo).setText(R.string.descanso_titulo)
        findViewById<View>(R.id.tarea_volver).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.tarea_editar).visibility = View.GONE
        findViewById<View>(R.id.tarea_eliminar).visibility = View.GONE
        findViewById<View>(R.id.descanso_guardar).isEnabled = false
        findViewById<View>(R.id.descanso_formulario).visibility = View.GONE

        findViewById<View>(R.id.descanso_guardar).setOnClickListener {
            guardar()
        }
        findViewById<View>(R.id.descanso_aplicaciones).setOnClickListener {
            elegirAplicaciones()
        }
        findViewById<View>(R.id.descanso_accesibilidad).setOnClickListener {
            explicarAccesibilidad()
        }
        findViewById<View>(R.id.descanso_notificaciones).setOnClickListener {
            permitirNotificaciones()
        }
        findViewById<View>(R.id.descanso_alarmas).setOnClickListener {
            abrirAjuste(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                "Busca Alarmas y recordatorios en los ajustes de Agenda.VIP.",
            )
        }
        findViewById<View>(R.id.descanso_vista_previa).setOnClickListener {
            mostrarVistaPrevia()
        }

        cargar(estado)
    }

    /** Android llama al volver de Ajustes; actualiza los permisos sin reemplazar lo escrito. */
    override fun onResume() {
        super.onResume()
        if (::repositorio.isInitialized && proteccion.comprobar()) {
            actualizarPermisos()
            actualizarHistorial()
        }
    }

    /** Guarda el dueño y los controles temporales al girar, incluidos los cambios aún sin guardar. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        if (::formulario.isInitialized) {
            formulario.guardarEstado(estado)
        }
        super.onSaveInstanceState(estado)
    }

    /**
     * Detiene una muestra y las consultas de esta pantalla al salir definitivamente.
     * No cancela la rutina guardada: el sistema de descanso tiene su propio funcionamiento.
     */
    override fun onDestroy() {
        dialogo?.dismiss()
        if (::vozMuestra.isInitialized) {
            vozMuestra.cerrar()
        }
        trabajador.shutdownNow()
        principal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Detiene la muestra cuando la pantalla deja de verse, para no hablar dentro de Ajustes. */
    override fun onStop() {
        if (::vozMuestra.isInitialized) {
            vozMuestra.detener()
        }
        super.onStop()
    }

    /**
     * Lee la configuración y el catálogo en un trabajo separado para mantener los botones ágiles.
     * Recibe el borrador de Android. Solo dibuja el resultado si la misma persona sigue dentro.
     */
    private fun cargar(estado: Bundle?) {
        trabajador.execute {
            try {
                val configuracion = repositorio.configuracion()
                val catalogo = CatalogoAplicacionesDescanso(this).listar()
                principal.post {
                    if (vigente()) {
                        aplicacionesDisponibles = catalogo
                        val borrador = estado?.takeIf { it.containsKey("descanso_activo") }
                        formulario = FormularioDescanso(this, configuracion, borrador)
                        findViewById<View>(R.id.descanso_formulario).visibility = View.VISIBLE
                        findViewById<View>(R.id.descanso_guardar).isEnabled = true
                        actualizarAplicaciones()
                        actualizarPermisos()
                        findViewById<TextView>(R.id.descanso_estado_carga).visibility = View.GONE
                    }
                }
            } catch (error: Exception) {
                principal.post {
                    if (vigente()) {
                        findViewById<TextView>(R.id.descanso_estado_carga).text =
                            error.message ?: "No se pudo leer la rutina. Tus datos se conservaron."
                    }
                }
            }
        }
    }

    /**
     * El botón Guardar valida el borrador y confirma sus datos antes de programar avisos.
     * Desactiva el botón durante el guardado para impedir duplicados; los errores conservan campos.
     * Los permisos se explican por separado y no se conceden al guardar la configuración.
     */
    private fun guardar() {
        if (guardando || !::formulario.isInitialized || !vigente()) {
            return
        }

        val configuracion = leerFormulario() ?: return
        guardando = true
        findViewById<View>(R.id.descanso_guardar).isEnabled = false

        trabajador.execute {
            try {
                repositorio.guardarConfiguracion(configuracion)
                GestorDescanso.actualizar(this)
                principal.post {
                    if (vigente()) {
                        guardando = false
                        findViewById<View>(R.id.descanso_guardar).isEnabled = true
                        actualizarPermisos()
                        actualizarHistorial()
                        informar(
                            if (!configuracion.activado) {
                                "Rutina guardada y desactivada."
                            } else if (configuracion.restriccionActiva &&
                                !ServicioAccesibilidadDescanso.estaHabilitado(this)) {
                                "Rutina guardada. Autoriza Accesibilidad para restringir aplicaciones."
                            } else {
                                "Rutina de descanso guardada."
                            },
                        )
                    }
                }
            } catch (error: Exception) {
                principal.post {
                    if (vigente()) {
                        guardando = false
                        findViewById<View>(R.id.descanso_guardar).isEnabled = true
                        informar(error.message ?: "No se pudo guardar. Vuelve a intentarlo.")
                    }
                }
            }
        }
    }

    /** Lee el borrador para Guardar o Vista previa; muestra el error y devuelve null si falta corregirlo. */
    private fun leerFormulario(): ConfiguracionDescanso? = try {
        formulario.leer()
    } catch (error: IllegalArgumentException) {
        informar(error.message ?: "Revisa los campos de la rutina.")
        null
    }

    /**
     * Explica el permiso cuando se toca Autorizar restricción. Abrir Accesibilidad lleva al
     * interruptor de Android. Información de la app ayuda si Android deniega el acceso.
     * Ambas opciones abren Ajustes y dejan la decisión en manos del usuario.
     */
    private fun explicarAccesibilidad() {
        mostrarDialogo(
            AlertDialog.Builder(this)
                .setTitle(R.string.descanso_dialogo_accesibilidad_titulo)
                .setMessage(R.string.descanso_dialogo_accesibilidad_texto)
                .setNegativeButton(R.string.descanso_dialogo_accesibilidad_cancelar, null)
                .setNeutralButton(R.string.descanso_dialogo_accesibilidad_info) { _, _ ->
                    abrirAjuste(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null),
                        ),
                        getString(R.string.descanso_dialogo_accesibilidad_ruta_info),
                    )
                }
                .setPositiveButton(R.string.descanso_dialogo_accesibilidad_abrir) { _, _ ->
                    abrirAjuste(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        getString(R.string.descanso_dialogo_accesibilidad_ruta),
                    )
                }
                .create(),
        )
    }

    /**
     * Al abrir la pantalla o regresar de Ajustes, consulta los permisos que Android concedió.
     * Muestra el estado de la restricción junto a las apps y solo deja visibles los botones
     * de los avisos pendientes. Actualiza la pantalla sin tocar el borrador de la rutina.
     */
    private fun actualizarPermisos() {
        if (!vigente()) {
            return
        }

        val accesibilidad = ServicioAccesibilidadDescanso.estaHabilitado(this)
        val notificaciones = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val alarmas = Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        val estadoAccesibilidad = if (accesibilidad) {
            R.string.descanso_restriccion_permitida
        } else {
            R.string.descanso_restriccion_pendiente
        }
        findViewById<TextView>(R.id.descanso_estado_permisos).setText(estadoAccesibilidad)

        val accionAccesibilidad = if (accesibilidad) {
            R.string.descanso_revisar_accesibilidad
        } else {
            R.string.descanso_accesibilidad
        }
        findViewById<Button>(R.id.descanso_accesibilidad).setText(accionAccesibilidad)

        findViewById<View>(R.id.descanso_permisos_avisos).visibility =
            if (notificaciones && alarmas) View.GONE else View.VISIBLE
        findViewById<View>(R.id.descanso_notificaciones).visibility =
            if (notificaciones) View.GONE else View.VISIBLE
        findViewById<View>(R.id.descanso_alarmas).visibility =
            if (alarmas) View.GONE else View.VISIBLE
    }

    /** Solicita notificaciones en Android 13 o abre sus ajustes en versiones anteriores. */
    private fun permitirNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 921)
        } else {
            abrirAjuste(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                "Busca Notificaciones en la información de Agenda.VIP.",
            )
        }
    }

    /** Android entrega el resultado del permiso; vuelve a mostrar su estado sin borrar el borrador. */
    override fun onRequestPermissionsResult(solicitud: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(solicitud, permisos, resultados)
        if (solicitud == 921 && vigente()) {
            actualizarPermisos()
        }
    }

    /** Recibe un ajuste concreto y lo abre; si el teléfono no lo ofrece, explica dónde buscarlo. */
    private fun abrirAjuste(intento: Intent, alternativa: String) {
        try {
            startActivity(intento)
        } catch (_: ActivityNotFoundException) {
            informar(alternativa)
        } catch (_: SecurityException) {
            informar(alternativa)
        }
    }

    /**
     * Abre una lista con búsqueda y casillas. Solo Aceptar copia la selección al borrador.
     * Las aplicaciones antes elegidas que ya no aparecen conservan su nombre de paquete.
     * Así el usuario puede quitarlas explícitamente en vez de perderlas al abrir el selector.
     */
    private fun elegirAplicaciones() {
        if (!::formulario.isInitialized) {
            return
        }

        val elegidas = formulario.aplicacionesElegidas().toMutableSet()
        val conocidas = aplicacionesDisponibles.map { it.paquete }.toSet()
        val todas = aplicacionesDisponibles + elegidas.filterNot { it in conocidas }.map {
            AplicacionDescanso(it, "$it · no disponible")
        }

        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadDescanso, 16)
            setPadding(margen, 0, margen, 0)
        }
        val busqueda = EditText(this).apply {
            hint = "Buscar aplicación"
            setSingleLine()
        }
        val lista = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }
        var filtradas = todas
        val adaptador = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            todas.map { it.nombre }.toMutableList(),
        )
        lista.adapter = adaptador
        columna.addView(busqueda)
        columna.addView(lista, LinearLayout.LayoutParams(-1, ComponentesAgenda.dp(this, 320)))

        // Reaplica las casillas al cambiar la búsqueda, sin perder las elecciones que quedaron ocultas.
        fun marcarSeleccionVisible() {
            lista.clearChoices()
            filtradas.forEachIndexed { indice, aplicacion ->
                lista.setItemChecked(indice, aplicacion.paquete in elegidas)
            }
        }

        lista.setOnItemClickListener { _, _, posicion, _ ->
            val paquete = filtradas[posicion].paquete
            if (lista.isItemChecked(posicion)) {
                elegidas.add(paquete)
            } else {
                elegidas.remove(paquete)
            }
        }
        busqueda.addTextChangedListener(object : TextWatcher {
            /** El cambio todavía no ocurrió; este paso no necesita modificar la selección. */
            override fun beforeTextChanged(texto: CharSequence?, inicio: Int, cantidad: Int, despues: Int) = Unit

            /** Filtra nombres y paquetes ya cargados, conservando las casillas fuera de la búsqueda. */
            override fun onTextChanged(texto: CharSequence?, inicio: Int, antes: Int, cantidad: Int) {
                val consulta = texto?.toString().orEmpty().trim()
                filtradas = todas.filter {
                    it.nombre.contains(consulta, ignoreCase = true) ||
                        it.paquete.contains(consulta, ignoreCase = true)
                }
                adaptador.clear()
                adaptador.addAll(filtradas.map { it.nombre })
                adaptador.notifyDataSetChanged()
                marcarSeleccionVisible()
            }

            /** El filtrado ya se completó; no hay otro cambio que realizar después de escribir. */
            override fun afterTextChanged(texto: Editable?) = Unit
        })

        mostrarDialogo(
            AlertDialog.Builder(this)
                .setTitle("Aplicaciones distractoras")
                .setView(columna)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aceptar") { _, _ ->
                    formulario.cambiarAplicaciones(elegidas)
                    actualizarAplicaciones()
                }
                .create(),
        )
        marcarSeleccionVisible()
    }

    /** Muestra nombres y cantidad elegidos; no guarda hasta que se pulse Guardar rutina. */
    private fun actualizarAplicaciones() {
        val elegidas = formulario.aplicacionesElegidas()
        val nombres = aplicacionesDisponibles.associate { it.paquete to it.nombre }
        findViewById<TextView>(R.id.descanso_resumen_aplicaciones).text = if (elegidas.isEmpty()) {
            "Ninguna app seleccionada."
        } else {
            "${elegidas.size} seleccionadas: " + elegidas.joinToString(
                limit = 6,
                truncated = "…",
            ) { nombres[it] ?: "$it (no disponible)" }
        }
    }

    /**
     * Calcula un ejemplo con el borrador y la hora actual; no modifica ni activa la rutina.
     * El tiempo indicado es el disponible hasta levantarse, no una medición de sueño real.
     * Escuchar usa la voz instalada de Android para leer el mensaje de Descanso.
     */
    private fun mostrarVistaPrevia() {
        if (!::formulario.isInitialized) {
            return
        }
        val configuracion = leerFormulario() ?: return
        val nombre = runCatching { PerfilLocal(this).nombres }.getOrDefault("")
        val texto = ReglasDescanso.mensajeDescanso(configuracion, ZonedDateTime.now(), nombre)

        mostrarDialogo(
            AlertDialog.Builder(this)
                .setTitle("Vista previa · Hora de descansar")
                .setMessage(texto + "\n\nEste ejemplo no activa ni cancela tu rutina.")
                .setNegativeButton("Cerrar", null)
                .setPositiveButton("Escuchar mensaje") { _, _ ->
                    vozMuestra.hablar(texto)
                }
                .create(),
        )
    }

    /**
     * Lee las últimas noches en otro trabajo y muestra solo tiempo en aplicaciones elegidas.
     * El tiempo es aproximado y depende del servicio autorizado; cero no significa que dormiste.
     */
    private fun actualizarHistorial() {
        if (!vigente()) {
            return
        }
        trabajador.execute {
            try {
                val noches = repositorio.historial().sortedByDescending { it.fecha }.take(7)
                val formato = DateTimeFormatter.ofPattern("dd MMM", Locale("es", "ES"))
                val texto = if (noches.isEmpty()) {
                    "Aquí verás el uso aproximado de las aplicaciones elegidas durante tus próximas noches."
                } else {
                    noches.joinToString("\n\n") { noche ->
                        val tiempo = if (noche.milisegundosUso in 1..59_999) {
                            "menos de 1 min"
                        } else {
                            "${noche.milisegundosUso / 60_000} min"
                        }
                        val decision = if (noche.cancelada) " · cancelada por esa noche" else ""
                        "${noche.fecha.format(formato)} · $tiempo en apps elegidas$decision"
                    }
                }
                principal.post {
                    if (vigente()) {
                        findViewById<TextView>(R.id.descanso_historial).text = texto
                    }
                }
            } catch (_: Exception) {
                principal.post {
                    if (vigente()) {
                        findViewById<TextView>(R.id.descanso_historial).text =
                            "No se pudo consultar el historial. Sus datos se conservaron."
                    }
                }
            }
        }
    }

    /** Conserva un solo diálogo; los resultados antiguos no abren ventanas en otra cuenta. */
    private fun mostrarDialogo(nuevo: AlertDialog) {
        if (!vigente()) {
            return
        }
        dialogo?.dismiss()
        dialogo = nuevo
        nuevo.show()
    }

    /** Devuelve si la pantalla sigue abierta y pertenece a la cuenta que la creó. */
    private fun vigente(): Boolean = !isFinishing && !isDestroyed &&
        EspacioPersonal.claveActual(this) == propietario

    /** Recibe una explicación breve y la muestra sin interrumpir ni descartar el formulario. */
    private fun informar(mensaje: String) {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
    }
}
