package com.nowel.agendavip.presentacion.agenda

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.presentacion.enfoque.ControladorEnfoque
import com.nowel.agendavip.presentacion.enfoque.ActividadDetalleEnfoque
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.FormatoAgenda
import com.nowel.agendavip.presentacion.comun.FotoPerfil
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.presentacion.comun.VozDeMuestra
import com.nowel.agendavip.presentacion.cuenta.ActividadCuenta
import com.nowel.agendavip.presentacion.tareas.ActividadDetalleTarea
import com.nowel.agendavip.presentacion.tareas.ActividadEditarTarea
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import com.nowel.agendavip.recordatorios.RecuperadorRecordatorios
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Controla Inicio, Agenda y Enfoque usando el diseño de pantalla_agenda.xml.
 * Inicio conserva el resumen y sus tarjetas; Agenda consulta tareas por Día, Semana y Mes;
 * Enfoque tiene actividades y sesiones propias. El menú personal permanece accesible.
 * Una Activity es una pantalla de Android. Las tareas normales y Enfoque tienen almacenes
 * separados; Agenda los consulta sin duplicar sus datos ni mezclar sus decisiones.
 */
class ActividadAgenda : Activity() {

    // Recuerda la cuenta de esta pantalla y bloquea el regreso después de cerrar su sesión.
    private val proteccion = ProtectorSesion(this)

    // lateinit indica que onCreate preparará estas herramientas al abrir la pantalla.
    private lateinit var repositorio: RepositorioTareas
    private lateinit var gestor: GestorRecordatorios
    private lateinit var perfil: PerfilLocal
    private lateinit var contenido: LinearLayout
    private lateinit var voz: VozDeMuestra
    private lateinit var historial: VistaHistorialAgenda
    private lateinit var repositorioEnfoque: RepositorioEnfoque
    private lateinit var controladorEnfoque: ControladorEnfoque
    private var enfoqueVisible = false
    private var seccion = "inicio"
    private var fechaElegida = LocalDate.now()
    private var periodoElegido = PeriodoAgenda.DIA
    private var menu: PopupWindow? = null
    private var visible = false
    private var posicionPendiente: Int? = null
    private var escuchandoPC = false
    private var seccionCabecera: String? = null

    // Guarda la lista visible y los datos que ya se dibujaron para evitar el parpadeo.
    private var listaVisible: LinearLayout? = null
    private var filtroVisible: Pair<LocalDate, PeriodoAgenda>? = null
    private var resultadoMostrado: Pair<ResultadoConsultaAgenda, ZoneId>? = null
    private var avisoPermisos: View? = null
    private var errorConsultaMostrado = false
    private val cambiosPC = object : BroadcastReceiver() {

        /** La sincronización avisa por cada cambio. Agrupa los avisos cercanos de un mismo lote. */
        override fun onReceive(contexto: Context?, intento: Intent?) {
            if (visible && proteccion.comprobar()) {
                principal.removeCallbacks(refrescarPC)
                principal.postDelayed(refrescarPC, 150L)
            }
        }
    }

    // El ejecutor calcula fuera del trabajo principal, que Android usa para dibujar.
    // Las versiones distinguen consultas o visitas anteriores que ya no deben dibujarse.
    private var ejecutor: ExecutorService? = null
    private var consulta: Future<*>? = null
    private var cambiandoEstado = false
    private var versionConsulta = 0
    private var versionPantalla = 0
    private val principal = Handler(Looper.getMainLooper())

    // El aviso de la PC espera un momento por si llegan más cambios del mismo lote.
    private val refrescarPC = Runnable { refrescarDatosVisibles() }
    private val refrescar = object : Runnable {
        /**
         * Android llama este bloque cada minuto mientras la pantalla está visible.
         * No recibe datos ni devuelve un resultado. Relee los estados actuales para recoger
         * cambios de una notificación y el cambio de día, conservando el filtro y la fecha.
         * onStop retira esta acción para que no siga consultando cuando se sale.
         */
        override fun run() {
            if (visible) {
                refrescarDatosVisibles()
                programarRefresco()
            }
        }
    }

    /**
     * Android la llama al abrir esta pantalla y entrega estado, un paquete temporal.
     * Prepara el diseño, los datos y los botones. Recupera sección, fecha, período y
     * desplazamiento después de girar el teléfono. No devuelve un resultado.
     * Si se abrió desde una notificación, también dirige a los detalles de esa tarea.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!proteccion.permitir(estado)) {
            return
        }

        setContentView(R.layout.pantalla_agenda)
        VentanaAgenda.preparar(this, findViewById(R.id.agenda_raiz))
        repositorio = RepositorioTareas(this)
        gestor = GestorRecordatorios(this)
        perfil = PerfilLocal(this)
        voz = VozDeMuestra(this) { mensaje ->
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
        contenido = findViewById(R.id.agenda_contenido)
        repositorioEnfoque = RepositorioEnfoque(this)
        controladorEnfoque = ControladorEnfoque(this, contenido)
        historial = VistaHistorialAgenda(this, ::abrirDetalles, ::abrirDetalleEnfoque)
        seccion = estado?.getString("seccion") ?: "inicio"
        fechaElegida = runCatching {
            LocalDate.parse(estado?.getString("fecha"))
        }.getOrDefault(LocalDate.now())
        periodoElegido = runCatching {
            PeriodoAgenda.valueOf(estado?.getString("periodo") ?: PeriodoAgenda.DIA.name)
        }.getOrDefault(PeriodoAgenda.DIA)
        posicionPendiente = estado?.getInt("desplazamiento")
        findViewById<View>(R.id.agenda_avatar).setOnClickListener {
            mostrarMenuPersonal()
        }
        enlazarNavegacion(R.id.agenda_nav_inicio, "inicio")
        enlazarNavegacion(R.id.agenda_nav_calendario, "agenda")
        enlazarNavegacion(R.id.agenda_nav_enfoque, "enfoque")
        abrirDestinoNotificacion()
    }

    /**
     * Android entrega estado antes de recrear la pantalla, por ejemplo al girar el teléfono.
     * Guarda la sección, la fecha, el período y la posición de la lista; no devuelve datos.
     * Solo recuerda lo que se estaba mirando: las tareas y sus estados ya están guardados
     * en el repositorio y no se copian dentro de este paquete.
     */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)

        if (!::contenido.isInitialized) {
            super.onSaveInstanceState(estado)
            return
        }

        estado.putString("seccion", seccion)
        estado.putString("fecha", fechaElegida.toString())
        estado.putString("periodo", periodoElegido.name)
        estado.putInt(
            "desplazamiento",
            posicionPendiente ?: findViewById<ScrollView>(R.id.agenda_desplazamiento).scrollY
        )
        super.onSaveInstanceState(estado)
    }

    /**
     * Android la llama cada vez que esta pantalla vuelve a verse; no recibe ni devuelve datos.
     * Prepara un ejecutor si hace falta, recupera la foto y solicita los estados actuales.
     * Solicita la recuperación de avisos a un trabajador independiente de las consultas de pantalla,
     * y prepara la actualización de un minuto. Salir no cancela esa recuperación ni cambia el período elegido.
     */
    override fun onResume() {
        super.onResume()

        if (!proteccion.comprobar()) {
            return
        }

        visible = true
        if (!escuchandoPC) {
            val filtro = IntentFilter(com.nowel.agendavip.datos.servidor.SincronizadorPC.ACCION_ACTUALIZACION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(cambiosPC, filtro, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(cambiosPC, filtro)
            }
            escuchandoPC = true
        }

        if (ejecutor == null || ejecutor?.isShutdown == true) {
            ejecutor = Executors.newSingleThreadExecutor()
        }

        FotoPerfil.mostrar(
            this,
            perfil.fotoUri,
            findViewById(R.id.agenda_avatar),
            R.color.melocoton
        )
        RecuperadorRecordatorios.solicitar(this)
        mostrarSeccion()
        principal.removeCallbacks(refrescar)
        programarRefresco()
    }

    /**
     * onResume y el refresco automático la llaman sin argumentos. Programa la siguiente
     * lectura al comienzo del próximo minuto, así también alcanza el cambio de día.
     * No devuelve datos ni cambia la hora de ningún aviso.
     */
    private fun programarRefresco() {
        val hastaSiguienteMinuto = 60_000L - Math.floorMod(System.currentTimeMillis(), 60_000L)
        principal.postDelayed(refrescar, hastaSiguienteMinuto)
    }

    /**
     * Los avisos de PC y el refresco del minuto la llaman para releer la sección visible.
     * Si se está guardando un interruptor de Inicio, el guardado hará su propio refresco.
     * No recibe ni devuelve datos.
     */
    private fun refrescarDatosVisibles() {
        if (visible && !(seccion == "inicio" && cambiandoEstado)) {
            mostrarSeccion()
        }
    }

    /**
     * Android la llama cuando la pantalla deja de verse; no recibe ni devuelve datos.
     * Retira las consultas y deja terminar un cambio de interruptor ya solicitado.
     * También cierra el menú y detiene la muestra de voz. La recuperación de alarmas
     * usa su propio trabajador y continúa.
     */
    override fun onStop() {
        visible = false
        if (escuchandoPC) {
            unregisterReceiver(cambiosPC)
            escuchandoPC = false
        }
        enfoqueVisible = false
        if (::controladorEnfoque.isInitialized) {
            controladorEnfoque.pausar()
        }
        versionConsulta += 1
        versionPantalla += 1
        consulta?.cancel(true)
        ejecutor?.shutdown()
        ejecutor = null
        principal.removeCallbacks(refrescar)
        principal.removeCallbacks(refrescarPC)
        menu?.dismiss()
        if (::voz.isInitialized) {
            voz.detener()
        }

        super.onStop()
    }

    /**
     * Android la llama al retirar definitivamente esta pantalla de la memoria.
     * No recibe ni devuelve datos. Cierra el lector de voz para liberar sus recursos
     * y permite que Android termine su propia limpieza mediante super.onDestroy.
     */
    override fun onDestroy() {
        if (::controladorEnfoque.isInitialized) {
            controladorEnfoque.cerrar()
        }
        if (::voz.isInitialized) {
            voz.cerrar()
        }

        super.onDestroy()
    }

    /**
     * Android entrega nuevo cuando se toca una notificación y esta pantalla ya existía.
     * Guarda esa petición, llamada Intent, y abre sus detalles para la fecha correcta.
     * No devuelve un resultado ni modifica la selección de fecha del historial.
     */
    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)

        if (!proteccion.comprobar() || !proteccion.aceptarIntent(nuevo)) {
            return
        }

        intent = nuevo
        abrirDestinoNotificacion()
    }

    /**
     * onCreate y onNewIntent la llaman sin argumentos para atender una notificación.
     * Lee tarea_id y fecha_ocurrencia de la petición de apertura y abre Detalles.
     * Quita tarea_id después de leerlo para no abrir otra vez al girar o volver.
     * Si la petición no trae una tarea, termina sin cambiar la pantalla.
     */
    private fun abrirDestinoNotificacion() {
        val id = intent.getStringExtra("tarea_id") ?: return
        val fecha = runCatching {
            LocalDate.parse(intent.getStringExtra("fecha_ocurrencia"))
        }.getOrDefault(LocalDate.now())
        intent.removeExtra("tarea_id")
        abrirDetalles(id, fecha)
    }

    /**
     * Recibe el id de un botón inferior y el nombre de su sección de destino.
     * onCreate la llama para Inicio, Agenda y Enfoque. Al tocar el botón cambia la sección
     * y vuelve al principio de su contenido, conservando el filtro y la fecha del historial.
     * No devuelve un resultado ni cambia las tareas guardadas.
     */
    private fun enlazarNavegacion(id: Int, destino: String) {
        findViewById<Button>(id).setOnClickListener {
            seccion = destino
            posicionPendiente = 0
            mostrarSeccion()
        }
    }

    /**
     * La llaman la navegación, los filtros y las actualizaciones. Pide los datos al ejecutor
     * para que la lectura no bloquee los botones. Inicio y Agenda conservan lo que muestran
     * mientras se actualiza la misma fecha y filtro. Cada petición cancela la lectura anterior.
     * forzarRedibujoInicio permite recuperar el interruptor después de guardar su estado.
     */
    private fun mostrarSeccion(forzarRedibujoInicio: Boolean = false) {
        if (!visible) {
            return
        }

        consulta?.cancel(true)
        versionConsulta += 1
        val version = versionConsulta
        val ahora = Instant.now()
        val zona = ZoneId.systemDefault()
        val hoy = ahora.atZone(zona).toLocalDate()
        val esInicio = seccion == "inicio"
        val mostrarHistorial = seccion == "agenda"
        val fechaConsulta = if (mostrarHistorial) fechaElegida else hoy
        val periodoConsulta = if (mostrarHistorial) periodoElegido else PeriodoAgenda.DIA
        val desplazamiento = findViewById<ScrollView>(R.id.agenda_desplazamiento)
        actualizarCabecera(hoy)

        if (seccion == "enfoque") {
            listaVisible = null
            resultadoMostrado = null
            avisoPermisos = null
            if (!enfoqueVisible) {
                enfoqueVisible = true
                val posicion = posicionPendiente ?: desplazamiento.scrollY
                controladorEnfoque.mostrar()
                desplazamiento.scrollTo(0, posicion)
                posicionPendiente = null
            }
            return
        }

        if (enfoqueVisible) {
            controladorEnfoque.pausar()
            enfoqueVisible = false
        }

        val filtro = if (mostrarHistorial) fechaConsulta to periodoConsulta else null
        val lista = listaVisible?.takeIf { filtroVisible == filtro }
            ?: prepararLista(mostrarHistorial).also {
                listaVisible = it
                filtroVisible = filtro
                resultadoMostrado = null
                errorConsultaMostrado = false
            }
        mostrarEstadoPermisos()

        consulta = ejecutor?.submit {
            try {
                if (Thread.currentThread().isInterrupted) {
                    return@submit
                }

                val resultado = ConsultaAgenda(repositorio, repositorioEnfoque).consultar(
                    fechaConsulta,
                    periodoConsulta,
                    hoy,
                    ahora.toEpochMilli(),
                    mostrarHistorial = mostrarHistorial,
                )
                principal.post {
                    if (!visible || version != versionConsulta || (esInicio && cambiandoEstado)) {
                        return@post
                    }

                    errorConsultaMostrado = false
                    val forzarRedibujo = esInicio && forzarRedibujoInicio
                    val resultadoConZona = resultado to zona
                    if (!forzarRedibujo && resultadoMostrado == resultadoConZona) {
                        posicionPendiente?.let { desplazamiento.scrollTo(0, it) }
                        posicionPendiente = null
                        return@post
                    }

                    val posicionAlActualizar = posicionPendiente ?: desplazamiento.scrollY
                    posicionPendiente = posicionAlActualizar
                    resultadoMostrado = null
                    lista.removeAllViews()
                    mostrarResultado(
                        lista,
                        resultado,
                        zona,
                        consultaVigente = {
                            visible && version == versionConsulta
                        },
                        alTerminar = {
                            resultadoMostrado = resultadoConZona
                            desplazamiento.post {
                                if (visible && version == versionConsulta) {
                                    desplazamiento.scrollTo(0, posicionAlActualizar)
                                    posicionPendiente = null
                                }
                            }
                        }
                    )
                }
            } catch (_: CancellationException) {
                // Salir o cambiar de fecha cancela la lectura sin mostrar un error.
            } catch (error: IllegalStateException) {
                mostrarErrorConsulta(error, version, lista)
            } catch (error: IllegalArgumentException) {
                mostrarErrorConsulta(error, version, lista)
            }
        }
    }

    /**
     * Recibe si se va a mostrar el historial y devuelve la lista vacía con su aviso de carga.
     * mostrarSeccion la llama al abrir Inicio por primera vez o al cambiar la consulta de Agenda.
     * Un refresco de la misma vista reutiliza su lista en lugar de llamar esta función.
     */
    private fun prepararLista(mostrarHistorial: Boolean): LinearLayout {
        contenido.removeAllViews()
        avisoPermisos = null

        if (mostrarHistorial) {
            contenido.addView(historial.controles(
                fechaElegida,
                periodoElegido,
                cambiarPeriodo = { periodo ->
                    periodoElegido = periodo
                    posicionPendiente = 0
                    mostrarSeccion()
                },
                cambiarFecha = { fecha ->
                    fechaElegida = fecha
                    posicionPendiente = 0
                    mostrarSeccion()
                }
            ))
        }

        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ComponentesAgenda.texto(
                this@ActividadAgenda,
                getString(R.string.agenda_cargando),
                15f,
                R.color.texto_secundario
            ).apply {
                setPadding(0, ComponentesAgenda.dp(this@ActividadAgenda, 16), 0, 0)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        }
        contenido.addView(lista)
        return lista
    }

    /**
     * Recibe el mismo día que usa la consulta para mostrar la fecha en la parte fija.
     * mostrarSeccion la llama y no devuelve un resultado.
     * Agenda usa un título pequeño y una franja ciruela; Inicio conserva la cabecera curva
     * con saludo y fecha. También destaca el destino inferior que está seleccionado.
     * El avatar queda disponible en las tres secciones para abrir el menú personal.
     */
    private fun actualizarCabecera(hoy: LocalDate) {
        val compacta = seccion != "inicio"
        val saludo = findViewById<TextView>(R.id.agenda_saludo)
        val fecha = findViewById<TextView>(R.id.agenda_fecha_cabecera)

        if (!compacta) {
            val textoSaludo = perfil.saludo()
            val textoFecha = FormatoAgenda.fecha(hoy)
            if (!TextUtils.equals(saludo.text, textoSaludo)) {
                saludo.text = textoSaludo
            }
            if (!TextUtils.equals(fecha.text, textoFecha)) {
                fecha.text = textoFecha
            }
        }
        if (seccionCabecera == seccion) {
            return
        }
        seccionCabecera = seccion

        val cabecera = findViewById<LinearLayout>(R.id.agenda_cabecera)
        val nombre = findViewById<TextView>(R.id.agenda_nombre)
        findViewById<View>(R.id.agenda_logotipo).visibility = if (compacta) View.GONE else View.VISIBLE
        saludo.visibility = if (compacta) View.GONE else View.VISIBLE
        fecha.visibility = if (compacta) View.GONE else View.VISIBLE
        nombre.text = when (seccion) {
            "agenda" -> getString(R.string.agenda_agenda)
            "enfoque" -> getString(R.string.agenda_enfoque)
            else -> getString(R.string.nombre_aplicacion)
        }
        nombre.textSize = if (compacta) 23f else 27f
        nombre.typeface = Typeface.create("serif", if (compacta) Typeface.NORMAL else Typeface.BOLD)
        nombre.setTextColor(getColor(if (compacta) R.color.marfil else R.color.melocoton))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            nombre.isAccessibilityHeading = compacta
        }
        nombre.layoutParams = (nombre.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = ComponentesAgenda.dp(this@ActividadAgenda, if (compacta) 0 else 12)
        }

        if (compacta) {
            cabecera.setBackgroundColor(getColor(R.color.ciruela))
            cabecera.setPadding(
                ComponentesAgenda.dp(this, 20),
                ComponentesAgenda.dp(this, 8),
                ComponentesAgenda.dp(this, 20),
                ComponentesAgenda.dp(this, 8)
            )
        } else {
            cabecera.setBackgroundResource(R.drawable.fondo_cabecera_acceso)
            val espacio = ComponentesAgenda.dp(this, 24)
            cabecera.setPadding(espacio, espacio, espacio, espacio)
        }

        val destinos = listOf(
            R.id.agenda_nav_inicio to "inicio",
            R.id.agenda_nav_calendario to "agenda",
            R.id.agenda_nav_enfoque to "enfoque"
        )

        for ((id, destino) in destinos) {
            findViewById<Button>(id).apply {
                isSelected = seccion == destino
                typeface = Typeface.create("serif", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    /**
     * Recibe el contenedor, el resultado, la zona horaria y las acciones que acompañan el dibujo.
     * mostrarSeccion la llama en el trabajo principal de Android, donde se dibuja la pantalla.
     * En Agenda presenta solo el historial. En Inicio muestra progreso, próximo aviso y
     * tarjetas. Enfoque se dibuja por separado y nunca aparece como una tarea de Inicio.
     */
    private fun mostrarResultado(
        lista: LinearLayout,
        resultado: ResultadoConsultaAgenda,
        zona: ZoneId,
        consultaVigente: () -> Boolean,
        alTerminar: () -> Unit
    ) {
        if (seccion == "agenda") {
            historial.mostrarFilas(lista, resultado.historial, consultaVigente, alTerminar)
            return
        }

        // Enfoque se dibuja con su controlador antes de esta consulta; aquí solo queda Inicio.
        mostrarProgreso(lista, resultado.deHoy)
        mostrarProximoAviso(lista, resultado.proximoAviso, resultado.hoy, zona)
        mostrarFilaAgendaDeHoy(lista)
        lista.addView(ComponentesAgenda.espacio(this, 12))
        val visibles = resultado.deHoy

        if (visibles.isEmpty()) {
            lista.addView(ComponentesAgenda.texto(
                this,
                "No hay tareas para esta fecha.",
                color = R.color.texto_secundario,
            ))
            alTerminar()
            return
        }

        mostrarTarjetasPorBloques(lista, visibles, 0, consultaVigente, alTerminar)
    }

    /**
     * Recibe el contenedor, las tareas de hoy y la posición desde la que debe dibujarlas.
     * consultaVigente indica si esta consulta sigue abierta; alTerminar recupera su posición.
     * mostrarResultado la llama para crear hasta 18 tarjetas por turno y continuar después,
     * dejando responder a los controles con listas largas. No devuelve un resultado y
     * deja de añadir tarjetas si la persona cambia de sección o sale de la pantalla.
     */
    private fun mostrarTarjetasPorBloques(
        lista: LinearLayout,
        filas: List<FilaAgenda>,
        desde: Int,
        consultaVigente: () -> Boolean,
        alTerminar: () -> Unit
    ) {
        if (!consultaVigente()) {
            return
        }

        val hasta = minOf(desde + 18, filas.size)

        for (indice in desde until hasta) {
            mostrarTarjeta(lista, filas[indice])
        }

        if (hasta < filas.size) {
            lista.post {
                mostrarTarjetasPorBloques(lista, filas, hasta, consultaVigente, alTerminar)
            }
        } else {
            alTerminar()
        }
    }

    /**
     * Recibe la lista de Inicio y añade el título a la izquierda y Nueva tarea a la derecha.
     * mostrarResultado la llama después del resumen. El botón conserva un área de toque de
     * al menos 48 dp y queda cerca del pulgar derecho, como pidió Nowel.
     */
    private fun mostrarFilaAgendaDeHoy(lista: LinearLayout) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(ComponentesAgenda.texto(
            this,
            getString(R.string.agenda_de_hoy),
            20f,
            titulo = true,
        ).apply {
            gravity = Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isAccessibilityHeading = true
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        fila.addView(VistaHistorialAgenda.boton(this, getString(R.string.agenda_nueva_tarea)) {
            abrirNuevaTarea()
        }.apply {
            isSelected = true
            textSize = 14f
            typeface = Typeface.create("serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = ComponentesAgenda.dp(this@ActividadAgenda, 12)
            }
        })
        lista.addView(fila)
    }

    /**
     * No recibe ni devuelve datos. El botón Nueva tarea de Inicio la llama.
     * Abre el formulario y deja que sugiera juntos el día y la hora. Así, si el horario
     * sugerido cae después de medianoche, también propone el día siguiente.
     */
    private fun abrirNuevaTarea() {
        startActivity(Intent(this, ActividadEditarTarea::class.java))
    }

    /**
     * Recibe la lista de Inicio y las tareas de hoy con sus estados ya consultados.
     * mostrarResultado la llama para dibujar el texto y la barra de progreso.
     * Cuenta las completadas sin releer el repositorio ni cambiar otras fechas de una rutina.
     * No devuelve un resultado y conserva el resumen que ya tenía Inicio.
     */
    private fun mostrarProgreso(lista: LinearLayout, tareas: List<FilaAgenda>) {
        val hechas = tareas.count { it.estado.completada }
        lista.addView(ComponentesAgenda.texto(this, "Tu día, en orden", 26f, titulo = true))
        lista.addView(ComponentesAgenda.texto(
            this,
            "$hechas de ${tareas.size} tareas completadas",
            color = R.color.texto_secundario
        ))
        lista.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = maxOf(1, tareas.size)
            progress = hechas
            progressTintList = ColorStateList.valueOf(getColor(R.color.ciruela))
            layoutParams = LinearLayout.LayoutParams(-1, ComponentesAgenda.dp(this@ActividadAgenda, 16)).apply {
                topMargin = ComponentesAgenda.dp(this@ActividadAgenda, 12)
                bottomMargin = ComponentesAgenda.dp(this@ActividadAgenda, 20)
            }
        })
    }

    /**
     * Recibe Inicio, el próximo aviso, el día de la consulta y su zona horaria.
     * Muestra una tarjeta pequeña con hora y título.
     * Los dos controles comparten una fila: escuchar tiene un icono y Ver abre los detalles.
     * Cada control conserva 48 dp de área de toque. El título puede ocupar dos líneas y,
     * si es más largo, se consulta completo en Detalles y con el lector de accesibilidad.
     */
    private fun mostrarProximoAviso(
        lista: LinearLayout,
        proximo: FilaAgenda?,
        hoy: LocalDate,
        zona: ZoneId,
    ) {
        val dato = proximo ?: return
        val instante = dato.estado.alarmaProgramadaPara ?: return
        val aviso = Instant.ofEpochMilli(instante).atZone(zona)
        val tarea = dato.tarea
        val cuando = if (aviso.toLocalDate() == hoy) {
            FormatoAgenda.hora(aviso.toLocalTime())
        } else {
            "${aviso.toLocalDate().dayOfMonth}/${aviso.toLocalDate().monthValue} · ${FormatoAgenda.hora(aviso.toLocalTime())}"
        }
        val tarjeta = ComponentesAgenda.tarjeta(this, true)
        val espacio = ComponentesAgenda.dp(this, 12)
        tarjeta.setPadding(espacio, espacio, espacio, espacio)
        tarjeta.addView(ComponentesAgenda.texto(this, "PRÓXIMO · $cuando", 12f))
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(ComponentesAgenda.texto(this, tarea.titulo, 18f, titulo = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(0, 0, ComponentesAgenda.dp(this@ActividadAgenda, 8), 0)
        })

        if (tarea.vozActiva) {
            fila.addView(ImageButton(this).apply {
                setImageResource(R.drawable.tarea_icono_escuchar)
                setColorFilter(getColor(R.color.ciruela))
                background = getDrawable(R.drawable.agenda_fondo_fila)
                contentDescription = "Escuchar mensaje de ${tarea.titulo}"
                val lado = ComponentesAgenda.dp(this@ActividadAgenda, 48)
                layoutParams = LinearLayout.LayoutParams(lado, lado)
                val margen = ComponentesAgenda.dp(this@ActividadAgenda, 12)
                setPadding(margen, margen, margen, margen)
                setOnClickListener {
                    voz.hablar(tarea.mensajeParaAviso(dato.estado.alarmaEsSeguimiento))
                }
            })
        }

        fila.addView(VistaHistorialAgenda.boton(this, "Ver") {
            abrirDetalles(tarea.id, dato.fecha)
        }.apply {
            background = getDrawable(R.drawable.agenda_fondo_fila)
            contentDescription = "Ver recordatorio de ${tarea.titulo}"
            textSize = 13f
            val lado = ComponentesAgenda.dp(this@ActividadAgenda, 48)
            layoutParams = LinearLayout.LayoutParams(lado, -2)
            setPadding(0, ComponentesAgenda.dp(this@ActividadAgenda, 8), 0, ComponentesAgenda.dp(this@ActividadAgenda, 8))
        })
        tarjeta.addView(fila)
        tarjeta.contentDescription = "Próximo recordatorio: ${tarea.titulo}, $cuando"
        lista.addView(tarjeta)
    }

    /**
     * Recibe el contenedor y una tarea de hoy con su estado; no devuelve un resultado.
     * mostrarResultado la llama únicamente en Inicio para conservar sus tarjetas.
     * El cuerpo abre Detalles y el interruptor cambia solo esa fecha de la rutina.
     * El historial usa otras filas de consulta y nunca llama esta función.
     */
    private fun mostrarTarjeta(lista: LinearLayout, dato: FilaAgenda) {
        val tarea = dato.tarea
        val estado = dato.estado
        val tarjeta = ComponentesAgenda.tarjeta(this)
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(ComponentesAgenda.texto(this@ActividadAgenda, FormatoAgenda.hora(tarea.hora), 23f, titulo = true))
            addView(ComponentesAgenda.texto(this@ActividadAgenda, tarea.titulo, 19f))
        }
        fila.addView(textos)
        fila.addView(Switch(this).apply {
            isChecked = estado.completada
            minWidth = ComponentesAgenda.dp(this@ActividadAgenda, 56)
            minHeight = ComponentesAgenda.dp(this@ActividadAgenda, 48)
            contentDescription = "Marcar ${tarea.titulo} como ${if (isChecked) "pendiente" else "completada"}"
            thumbTintList = ColorStateList.valueOf(getColor(R.color.marfil))
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.agenda_completada), getColor(R.color.agenda_pendiente))
            )
            setOnCheckedChangeListener { control, completada ->
                if (completada != estado.completada) {
                    if (cambiandoEstado || dato.fecha != LocalDate.now()) {
                        control.isChecked = estado.completada
                        if (!cambiandoEstado) {
                            mostrarSeccion()
                        }
                    } else {
                        cambiarCompletada(dato, completada, control)
                    }
                }
            }
        })
        tarjeta.addView(fila)
        tarjeta.addView(ComponentesAgenda.texto(
            this,
            if (estado.completada) "✓ Completada" else "○ Pendiente",
            14f,
            if (estado.completada) R.color.agenda_completada else R.color.agenda_pendiente
        ))
        tarjeta.addView(ComponentesAgenda.texto(this, FormatoAgenda.repeticion(tarea), 13f, R.color.texto_secundario))
        estado.pospuestaHasta?.let { instante ->
            val nuevaHora = Instant.ofEpochMilli(instante).atZone(ZoneId.systemDefault()).toLocalTime()
            tarjeta.addView(ComponentesAgenda.texto(this, "Pospuesta hasta ${FormatoAgenda.hora(nuevaHora)}", 13f))
        }
        tarjeta.setOnClickListener {
            abrirDetalles(tarea.id, dato.fecha)
        }
        tarjeta.contentDescription = "Detalles de ${tarea.titulo}"
        lista.addView(tarjeta)
    }

    /**
     * Recibe la tarea del día, el nuevo valor completada y el interruptor que se tocó.
     * mostrarTarjeta la llama en Inicio; el historial no cambia estados.
     * Guarda mediante el gestor en el ejecutor y después actualiza la pantalla principal.
     * Mientras guarda evita enviar cambios repetidos. No devuelve un resultado y, si falla,
     * recupera el valor anterior y explica el problema sin borrar los datos.
     */
    private fun cambiarCompletada(dato: FilaAgenda, completada: Boolean, control: CompoundButton) {
        val trabajo = ejecutor ?: return
        val version = versionPantalla
        cambiandoEstado = true
        control.isEnabled = false
        trabajo.submit {
            try {
                gestor.marcarCompletada(dato.tarea.id, dato.fecha, completada)
                principal.post {
                    cambiandoEstado = false
                    control.isEnabled = true
                    if (visible && proteccion.comprobar()) {
                        mostrarSeccion(forzarRedibujoInicio = true)
                    }
                }
            } catch (error: IllegalStateException) {
                recuperarInterruptor(control, dato.estado.completada, error, version)
            } catch (error: IllegalArgumentException) {
                recuperarInterruptor(control, dato.estado.completada, error, version)
            }
        }
    }

    /**
     * Recibe el interruptor, su valor guardado, el error y la visita que lo produjo.
     * cambiarCompletada la llama desde el ejecutor; vuelve al trabajo principal para
     * recuperar el control y refrescar si Inicio sigue visible. Muestra el mensaje
     * solo en la misma visita que inició el cambio. No devuelve un resultado.
     */
    private fun recuperarInterruptor(
        control: CompoundButton,
        valor: Boolean,
        error: Exception,
        version: Int
    ) {
        principal.post {
            cambiandoEstado = false
            control.isChecked = valor
            control.isEnabled = true
            if (visible && proteccion.comprobar()) {
                if (version == versionPantalla) {
                    mostrarError(error)
                }
                mostrarSeccion()
            }
        }
    }

    /**
     * Recibe un error de lectura, la versión de consulta y el contenedor de su lista.
     * mostrarSeccion la llama desde el ejecutor cuando el repositorio informa un problema.
     * Conserva las tarjetas si había datos, o reemplaza el texto de carga.
     * Muestra una sola explicación mientras siga el mismo fallo. No devuelve datos.
     */
    private fun mostrarErrorConsulta(error: Exception, version: Int, lista: LinearLayout) {
        principal.post {
            if (!visible || version != versionConsulta) {
                return@post
            }

            if (resultadoMostrado == null) {
                lista.removeAllViews()
                lista.addView(ComponentesAgenda.texto(
                    this,
                    getString(R.string.agenda_error_consulta),
                    color = R.color.texto_secundario
                ))
            }
            if (!errorConsultaMostrado) {
                errorConsultaMostrado = true
                mostrarError(error)
            }
        }
    }

    /**
     * Recibe un error de datos y muestra su explicación, sin devolver un resultado.
     * La llaman los errores de consulta y de cambios de estado, siempre en la pantalla.
     * Los datos ilegibles muestran un diálogo; los valores rechazados muestran un aviso breve.
     * Ninguna de esas respuestas sustituye la agenda guardada por una lista vacía.
     */
    private fun mostrarError(error: Exception) {
        if (error is IllegalStateException) {
            AlertDialog.Builder(this)
                .setTitle("No se pudo abrir la agenda")
                .setMessage(error.message ?: "Los datos se conservaron. Vuelve a intentar.")
                .setPositiveButton("Entendido", null)
                .show()
        } else {
            Toast.makeText(
                this,
                error.message ?: "Revisa los datos de la tarea.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * No recibe ni devuelve datos. mostrarSeccion la llama para consultar los permisos de Android.
     * Si falta alguno, añade un aviso breve con Revisar permisos. El botón abre el apartado
     * separado permisos de Cuenta; no entra en Configuración ni concede permisos por sí mismo.
     * El aviso permanece disponible también mientras se consulta el historial.
     */
    private fun mostrarEstadoPermisos() {
        val notificaciones = getSystemService(NotificationManager::class.java).areNotificationsEnabled()

        if (notificaciones && gestor.tieneAlarmasExactas()) {
            avisoPermisos?.let { contenido.removeView(it) }
            avisoPermisos = null
            return
        }
        if (avisoPermisos != null) {
            return
        }

        val aviso = ComponentesAgenda.tarjeta(this, true).apply {
            setPadding(
                ComponentesAgenda.dp(this@ActividadAgenda, 12),
                ComponentesAgenda.dp(this@ActividadAgenda, 8),
                ComponentesAgenda.dp(this@ActividadAgenda, 12),
                ComponentesAgenda.dp(this@ActividadAgenda, 8)
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ComponentesAgenda.texto(
                this@ActividadAgenda,
                getString(R.string.agenda_aviso_permisos),
                13f
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1.2f)
            })
            addView(VistaHistorialAgenda.boton(
                this@ActividadAgenda,
                getString(R.string.agenda_revisar_permisos)
            ) {
                abrirCuenta("permisos")
            }.apply {
                background = getDrawable(R.drawable.agenda_fondo_fila)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, -2, 0.8f).apply {
                    marginStart = ComponentesAgenda.dp(this@ActividadAgenda, 8)
                }
            })
        }
        avisoPermisos = aviso
        contenido.addView(aviso, 0)
    }

    /**
     * El avatar llama a esta función sin argumentos. Abre el panel con la foto y los
     * accesos personales encima de la agenda, sin cambiar la sección que se estaba viendo.
     * PopupWindow es una ventana flotante. Su altura respeta el espacio disponible y
     * el contenido permite desplazarse cuando las letras ocupan más espacio.
     */
    private fun mostrarMenuPersonal() {
        menu?.dismiss()

        val ancla = findViewById<View>(R.id.agenda_avatar)
        val panel = layoutInflater.inflate(R.layout.menu_personal_panel, null)
        val foto = panel.findViewById<ImageView>(R.id.menu_personal_foto)
        FotoPerfil.mostrar(this, perfil.fotoUri, foto)

        val nombre = panel.findViewById<TextView>(R.id.menu_personal_nombre)
        nombre.text = perfil.nombre.ifBlank {
            getString(R.string.menu_personal_nombre_sin_datos)
        }
        panel.findViewById<TextView>(R.id.menu_personal_saludo).text = perfil.saludo()

        val opciones = panel.findViewById<LinearLayout>(R.id.menu_personal_opciones)
        agregarOpcionPersonal(
            opciones,
            R.drawable.icono_usuario,
            R.string.menu_personal_perfil,
            R.string.menu_personal_perfil_detalle,
            "perfil",
        )
        agregarOpcionPersonal(
            opciones,
            R.drawable.menu_personal_icono_ajustes,
            R.string.menu_personal_configuracion,
            R.string.menu_personal_configuracion_detalle,
            "configuracion",
        )
        agregarOpcionPersonal(
            opciones,
            R.drawable.marca_agenda,
            R.string.menu_personal_creditos,
            R.string.menu_personal_creditos_detalle,
            "creditos",
        )

        // El marco mide el área visible de la ventana, incluso cuando se comparte la pantalla.
        val marco = Rect()
        ancla.getWindowVisibleDisplayFrame(marco)
        val ancho = minOf(
            marco.width() - ComponentesAgenda.dp(this, 24),
            ComponentesAgenda.dp(this, 328),
        )
        if (ancho <= 0) {
            return
        }

        val distancia = ComponentesAgenda.dp(this, 6)
        val ventana = PopupWindow(panel, ancho, 0, true).apply {
            setBackgroundDrawable(getDrawable(R.drawable.configuracion_fondo_grupo))
            isOutsideTouchable = true
            elevation = ComponentesAgenda.dp(this@ActividadAgenda, 12).toFloat()
        }
        val altoDisponible = ventana.getMaxAvailableHeight(ancla, distancia)
        if (altoDisponible <= 0) {
            return
        }

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(ancho, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(altoDisponible, View.MeasureSpec.AT_MOST),
        )
        ventana.height = panel.measuredHeight
        ventana.setOnDismissListener {
            menu = null
        }

        menu = ventana
        ventana.showAsDropDown(ancla, 0, distancia, Gravity.END)
    }

    /**
     * Recibe el grupo de filas, el icono, los textos y el apartado que abre esta opción.
     * mostrarMenuPersonal la usa para dar a las tres opciones el mismo diseño que en
     * Configuración. Al tocar cualquier parte de una fila se cierra el menú y se abre
     * su apartado. No devuelve un valor ni modifica los datos del perfil.
     */
    private fun agregarOpcionPersonal(
        opciones: LinearLayout,
        icono: Int,
        titulo: Int,
        detalle: Int,
        seccion: String,
    ) {
        val fila = layoutInflater.inflate(R.layout.configuracion_fila, opciones, false)
        fila.findViewById<ImageView>(R.id.configuracion_fila_icono).setImageResource(icono)
        fila.findViewById<TextView>(R.id.configuracion_fila_titulo).setText(titulo)
        fila.findViewById<TextView>(R.id.configuracion_fila_detalle).apply {
            setText(detalle)
            visibility = View.VISIBLE
        }

        fila.setOnClickListener {
            menu?.dismiss()
            abrirCuenta(seccion)
        }

        opciones.addView(fila)
    }

    /**
     * Recibe el nombre del apartado personal: perfil, configuracion, permisos o creditos.
     * El menú y el aviso contextual de permisos la llaman para abrir ActividadCuenta.
     * Envía ese nombre mediante Intent, una petición de apertura que lee la pantalla destino.
     * No devuelve un resultado ni altera la fecha o el filtro del historial.
     */
    private fun abrirCuenta(seccion: String) {
        startActivity(
            Intent(this, ActividadCuenta::class.java)
                .putExtra("seccion", seccion)
        )
    }

    /**
     * Recibe id, el código de una tarea, y fecha, el día concreto que se está consultando.
     * La llaman las filas del historial, las tarjetas y las notificaciones para abrir Detalles.
     * Envía solo esos dos datos, así Detalles lee la versión actual y aplica sus cambios
     * a esa fecha de la rutina. No devuelve un resultado ni copia la tarea completa.
     */
    private fun abrirDetalles(id: String, fecha: LocalDate) {
        startActivity(
            Intent(this, ActividadDetalleTarea::class.java)
                .putExtra("tarea_id", id)
                .putExtra("fecha_ocurrencia", fecha.toString())
        )
    }

    /**
     * Recibe el código de una actividad del historial de Enfoque y abre su propio detalle.
     * La fila incluye la cuenta activa para impedir que una apertura antigua muestre otra cuenta.
     */
    private fun abrirDetalleEnfoque(id: String) {
        startActivity(
            Intent(this, ActividadDetalleEnfoque::class.java)
                .putExtra("enfoque_id", id)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(this)),
        )
    }
}



