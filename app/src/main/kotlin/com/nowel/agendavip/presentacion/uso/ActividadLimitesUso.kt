package com.nowel.agendavip.presentacion.uso

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.uso.LectorUsoDiario
import com.nowel.agendavip.datos.uso.RepositorioLimitesUso
import com.nowel.agendavip.dominio.uso.LimiteAplicacion
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.recordatorios.uso.ServicioLimitesUso
import java.util.concurrent.Executors

/**
 * Permite elegir aplicaciones, minutos diarios y un mensaje personal opcional.
 * Conserva el diseño ciruela y marfil, y usa la barra fija de las pantallas de detalles.
 * No observa nada por entrar: la supervisión solo empieza al pulsar Activar y conceder permisos.
 */
class ActividadLimitesUso : Activity() {

    private val proteccion = ProtectorSesion(this)
    private val trabajador = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var contenido: LinearLayout
    private lateinit var repositorio: RepositorioLimitesUso
    private var propietario = ""
    private var consulta = 0

    private data class AplicacionElegible(val paquete: String, val nombre: String)

    /**
     * Android abre la pantalla y conecta Volver; el contenido se consulta al hacerse visible.
     * Recibe el estado temporal de la pantalla y rechaza aperturas de otra cuenta.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (!proteccion.permitir(estado)) {
            return
        }
        propietario = EspacioPersonal.claveActual(this).orEmpty()
        repositorio = RepositorioLimitesUso(this)
        setContentView(R.layout.tarea_pantalla_detalle)
        VentanaAgenda.preparar(this, findViewById(R.id.tarea_raiz))
        contenido = findViewById(R.id.tarea_detalle_contenido)
        findViewById<TextView>(R.id.tarea_pantalla_titulo).text = "Límites de uso"
        findViewById<View>(R.id.tarea_volver).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.tarea_editar).visibility = View.GONE
        findViewById<View>(R.id.tarea_eliminar).visibility = View.GONE
    }

    /** Android la llama al volver de Ajustes; relee permisos, objetivos y minutos de hoy. */
    override fun onResume() {
        super.onResume()
        if (proteccion.comprobar() && ::contenido.isInitialized) {
            actualizar()
        }
    }

    /** Recuerda la cuenta al girar para que Android no restaure datos en una sesión diferente. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /** Cancela lecturas de esta pantalla al destruirla; no activa ni detiene supervisiones por su cuenta. */
    override fun onDestroy() {
        consulta += 1
        trabajador.shutdownNow()
        principal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Lee objetivos y uso sin congelar botones; un número de consulta descarta resultados antiguos.
     * Si el permiso falta, muestra objetivos conservados y explica cómo concederlo.
     */
    private fun actualizar() {
        val version = ++consulta
        contenido.removeAllViews()
        contenido.addView(ComponentesAgenda.texto(this, "Cargando tus objetivos…", 18f))
        trabajador.execute {
            try {
                val limites = repositorio.listar()
                val lector = LectorUsoDiario(this)
                val permiso = lector.tienePermiso()
                var avisoConsulta: String? = null
                val uso = if (permiso) {
                    try {
                        lector.consultar(limites.map { it.paquete }.toSet()).milisegundosPorPaquete
                    } catch (_: RuntimeException) {
                        avisoConsulta = "No se pudo consultar el tiempo de hoy. Tus objetivos siguen guardados."
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
                principal.post {
                    if (vigente() && version == consulta) {
                        dibujar(limites, uso, permiso, avisoConsulta)
                    }
                }
            } catch (error: Exception) {
                principal.post {
                    if (vigente() && version == consulta) {
                        contenido.removeAllViews()
                        contenido.addView(ComponentesAgenda.texto(
                            this,
                            error.message ?: "No se pudieron leer tus límites.",
                            17f
                        ))
                    }
                }
            }
        }
    }

    /**
     * Recibe datos ya leídos y dibuja permisos, activación y una tarjeta por aplicación.
     * Los minutos son una estimación de Android; no incluyen el contenido que se ve en esas apps.
     */
    private fun dibujar(
        limites: List<LimiteAplicacion>,
        uso: Map<String, Long>,
        permiso: Boolean,
        avisoConsulta: String?
    ) {
        contenido.removeAllViews()
        contenido.addView(ComponentesAgenda.texto(this, "Tu tiempo, tus metas", 27f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Elige un tiempo diario y el mensaje que quieres recibir. Solo se consulta el tiempo en pantalla de las aplicaciones que añadas.",
            16f,
            R.color.texto_secundario
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 16))

        val estado = ComponentesAgenda.tarjeta(this, destacada = true)
        val activo = ServicioLimitesUso.estaActivo(this)
        estado.addView(ComponentesAgenda.texto(
            this,
            if (activo) "Supervisión activada" else "Supervisión pausada",
            21f,
            titulo = true
        ))
        estado.addView(ComponentesAgenda.texto(
            this,
            "Un aviso por app y día. Los límites vuelven a contar desde medianoche. La revisión se actualiza aproximadamente cada 30 segundos.",
            14f
        ))
        if (!permiso) {
            estado.addView(ComponentesAgenda.boton(this, "Permitir Acceso de uso") {
                abrirAccesoUso()
            })
        }
        if (!ServicioLimitesUso.permiteNotificaciones(this)) {
            estado.addView(ComponentesAgenda.boton(this, "Permitir notificaciones") {
                permitirNotificaciones()
            })
        }
        estado.addView(ComponentesAgenda.boton(
            this,
            if (activo) "Pausar supervisión" else "Activar supervisión",
            principal = true
        ) {
            try {
                if (ServicioLimitesUso.estaActivo(this)) {
                    ServicioLimitesUso.detener(this)
                } else {
                    ServicioLimitesUso.iniciar(this)
                }
                // La apertura del servicio ocurre después del toque; se actualiza su estado al terminar ese turno.
                principal.postDelayed({ if (vigente()) actualizar() }, 500L)
            } catch (error: RuntimeException) {
                informar(error.message ?: "No se pudo activar. Revisa los permisos.")
            }
        })
        contenido.addView(estado)

        contenido.addView(ComponentesAgenda.boton(this, "+ Añadir aplicación", principal = true) {
            elegirAplicacion()
        })
        contenido.addView(ComponentesAgenda.boton(this, "Actualizar minutos de hoy") {
            actualizar()
        })
        avisoConsulta?.let {
            contenido.addView(ComponentesAgenda.texto(this, it, 14f, R.color.texto_secundario))
        }
        if (limites.isEmpty()) {
            contenido.addView(ComponentesAgenda.espacio(this, 20))
            contenido.addView(ComponentesAgenda.texto(
                this,
                "Todavía no elegiste aplicaciones. Puedes empezar con YouTube o tu red social favorita.",
                16f,
                R.color.texto_secundario
            ))
        }
        limites.forEach { limite ->
            contenido.addView(ComponentesAgenda.espacio(this, 12))
            val tarjeta = ComponentesAgenda.tarjeta(this)
            tarjeta.addView(ComponentesAgenda.texto(this, limite.nombre, 20f, titulo = true))
            val milisegundos = uso[limite.paquete]
            val minutos = milisegundos?.div(60_000L)
            val tiempo = if (permiso && minutos != null) minutos.toString() + " min" else "sin lectura"
            tarjeta.addView(ComponentesAgenda.texto(
                this,
                "Hoy: " + tiempo + " · Límite: " + limite.minutosDiarios + " min",
                15f
            ))
            tarjeta.addView(ComponentesAgenda.texto(
                this,
                if (limite.habilitado) "Límite habilitado" else "Este límite está pausado",
                14f,
                R.color.texto_secundario
            ))
            tarjeta.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = ((milisegundos ?: 0L) * 100L / (limite.minutosDiarios * 60_000L))
                    .coerceIn(0L, 100L).toInt()
            })
            tarjeta.addView(ComponentesAgenda.boton(this, "Editar límite y mensaje") {
                editarLimite(AplicacionElegible(limite.paquete, limite.nombre), limite)
            })
            tarjeta.addView(ComponentesAgenda.boton(this, "Quitar límite") {
                confirmarEliminar(limite)
            })
            contenido.addView(tarjeta)
        }
        contenido.addView(ComponentesAgenda.espacio(this, 16))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Esta función avisa; tú decides cuándo salir de otra aplicación. No bloquea apps, no usa Accesibilidad ni lee tus mensajes. Si Android detiene la supervisión, vuelve aquí para activarla.",
            14f,
            R.color.texto_secundario
        ))
    }

    /** Abre la autorización especial de Android; no intenta concederla sin intervención de la persona. */
    private fun abrirAccesoUso() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + packageName)))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: android.content.ActivityNotFoundException) {
                informar("Busca Acceso de uso en los ajustes de tu teléfono.")
            }
        }
    }

    /** Solicita notificaciones en Android 13 o abre sus ajustes si el permiso ya fue decidido. */
    private fun permitirNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 806)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    /** Actualiza la información visible después de que Android entregue la respuesta del permiso. */
    override fun onRequestPermissionsResult(solicitud: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(solicitud, permisos, resultados)
        if (solicitud == 806 && vigente()) {
            actualizar()
        }
    }

    /**
     * Busca aplicaciones con icono de apertura en el lanzador del teléfono.
     * La lista se lee en otro trabajo y no exige autorización para ver todas las apps instaladas.
     */
    @Suppress("DEPRECATION")
    private fun elegirAplicacion() {
        trabajador.execute {
            try {
                val consultaApps = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val aplicaciones = packageManager.queryIntentActivities(consultaApps, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map {
                        AplicacionElegible(
                            it.activityInfo.packageName,
                            it.loadLabel(packageManager).toString().take(200)
                        )
                    }
                    .distinctBy { it.paquete }
                    .sortedBy { it.nombre.lowercase() }
                principal.post {
                    if (vigente()) {
                        mostrarSelector(aplicaciones)
                    }
                }
            } catch (_: RuntimeException) {
                principal.post {
                    if (vigente()) informar("No se pudieron leer las aplicaciones. Vuelve a intentarlo.")
                }
            }
        }
    }

    /** Recibe aplicaciones y muestra una búsqueda sencilla; tocar una abre su editor de límite. */
    private fun mostrarSelector(aplicaciones: List<AplicacionElegible>) {
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadLimitesUso, 20)
            setPadding(margen, margen, margen, 0)
        }
        val busqueda = EditText(this).apply {
            hint = "Buscar aplicación"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val lista = ListView(this)
        var filtradas = aplicaciones
        val adaptador = ArrayAdapter(this, android.R.layout.simple_list_item_1, aplicaciones.map { it.nombre }.toMutableList())
        lista.adapter = adaptador
        columna.addView(busqueda)
        columna.addView(lista, LinearLayout.LayoutParams(-1, ComponentesAgenda.dp(this, 320)))
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Elegir aplicación")
            .setView(columna)
            .setNegativeButton("Cancelar", null)
            .create()
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val elegida = filtradas[posicion]
            dialogo.dismiss()
            try {
                editarLimite(elegida, repositorio.listar().firstOrNull { it.paquete == elegida.paquete })
            } catch (error: RuntimeException) {
                informar(error.message ?: "No se pudo leer ese objetivo.")
            }
        }
        busqueda.addTextChangedListener(object : TextWatcher {
            /** Android avisa antes del cambio; aquí no hace falta modificar nada. */
            override fun beforeTextChanged(texto: CharSequence?, inicio: Int, cantidad: Int, despues: Int) = Unit

            /** Android entrega lo escrito; se filtra la lista ya cargada sin consultar el teléfono otra vez. */
            override fun onTextChanged(texto: CharSequence?, inicio: Int, antes: Int, cantidad: Int) {
                filtradas = aplicaciones.filter { it.nombre.contains(texto?.toString().orEmpty(), ignoreCase = true) }
                adaptador.clear()
                adaptador.addAll(filtradas.map { it.nombre })
                adaptador.notifyDataSetChanged()
            }

            /** Android terminó la escritura; el filtrado ya quedó hecho en onTextChanged. */
            override fun afterTextChanged(texto: Editable?) = Unit
        })
        dialogo.show()
    }

    /**
     * Recibe la aplicación y su objetivo anterior; permite cambiar minutos, mensaje, voz y pausa.
     * Guardar valida los campos y manda el cambio al trabajador; Cancelar conserva lo anterior.
     */
    private fun editarLimite(aplicacion: AplicacionElegible, anterior: LimiteAplicacion?) {
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadLimitesUso, 20)
            setPadding(margen, margen, margen, margen)
        }
        columna.addView(ComponentesAgenda.texto(this, "Minutos diarios", 16f, titulo = true))
        val minutos = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText((anterior?.minutosDiarios ?: 60).toString())
            selectAll()
        }
        columna.addView(minutos)
        columna.addView(ComponentesAgenda.texto(this, "Tu mensaje al alcanzar el límite", 16f, titulo = true))
        val mensaje = EditText(this).apply {
            hint = "Recuerda que hoy quiero avanzar con mis metas."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(400))
            setText(anterior?.mensaje.orEmpty())
            minLines = 2
        }
        columna.addView(mensaje)
        val hablar = Switch(this).apply {
            text = "Leer el mensaje con la voz del teléfono"
            isChecked = anterior?.hablar ?: false
            minHeight = ComponentesAgenda.dp(this@ActividadLimitesUso, 48)
        }
        val habilitado = Switch(this).apply {
            text = "Habilitar este límite"
            isChecked = anterior?.habilitado ?: true
            minHeight = ComponentesAgenda.dp(this@ActividadLimitesUso, 48)
        }
        columna.addView(hablar)
        columna.addView(habilitado)
        val desplazamiento = android.widget.ScrollView(this).apply {
            addView(columna)
        }
        val dialogo = AlertDialog.Builder(this)
            .setTitle(aplicacion.nombre)
            .setView(desplazamiento)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cantidad = minutos.text.toString().toIntOrNull()
                if (cantidad == null || cantidad !in 1..1440) {
                    minutos.error = "Escribe de 1 a 1440 minutos."
                    return@setOnClickListener
                }
                val limite = LimiteAplicacion(
                    paquete = aplicacion.paquete,
                    nombre = aplicacion.nombre,
                    minutosDiarios = cantidad,
                    mensaje = mensaje.text.toString().trim(),
                    hablar = hablar.isChecked,
                    habilitado = habilitado.isChecked
                )
                guardarCambio { repositorio.guardar(limite) }
                dialogo.dismiss()
            }
        }
        dialogo.show()
    }

    /** Confirma que solo se quitará el límite elegido; no desinstala ni cierra esa aplicación. */
    private fun confirmarEliminar(limite: LimiteAplicacion) {
        AlertDialog.Builder(this)
            .setTitle("Quitar límite")
            .setMessage("Se quitará tu objetivo de " + limite.nombre + ". Puedes configurarlo otra vez cuando quieras.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Quitar") { _, _ ->
                guardarCambio { repositorio.eliminar(limite.paquete) }
            }
            .show()
    }

    /** Ejecuta un cambio de objetivos fuera de la pantalla y luego actualiza las tarjetas. */
    private fun guardarCambio(operacion: () -> Unit) {
        trabajador.execute {
            try {
                operacion()
                val quedan = repositorio.listar().any { it.habilitado }
                principal.post {
                    if (vigente()) {
                        if (!quedan) ServicioLimitesUso.detener(this)
                        actualizar()
                    }
                }
            } catch (error: RuntimeException) {
                principal.post {
                    if (vigente()) informar(error.message ?: "No se pudo guardar el cambio.")
                }
            }
        }
    }

    /** Devuelve si la pantalla puede mostrar resultados de su cuenta sin haber sido cerrada. */
    private fun vigente(): Boolean {
        return !isFinishing && !isDestroyed && EspacioPersonal.claveActual(this) == propietario
    }

    /** Muestra una explicación breve sin cambiar los objetivos ni activar permisos. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }
}
