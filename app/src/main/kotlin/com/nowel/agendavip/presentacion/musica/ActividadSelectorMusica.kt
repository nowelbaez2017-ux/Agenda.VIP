package com.nowel.agendavip.presentacion.musica

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.datos.musica.BibliotecaMusica
import com.nowel.agendavip.datos.musica.CancionLocal
import com.nowel.agendavip.datos.musica.IndiceBibliotecaMusica
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.Executors

/**
 * Activity significa pantalla de Android. Esta permite elegir música local para una tarea.
 * La pantalla que la abre entrega el título y la música anterior mediante tarea_titulo,
 * musica_uri y musica_nombre. Una URI es la dirección de un archivo que Android puede abrir.
 * Solo Confirmar devuelve RESULT_OK, la dirección elegida (o null para silencio) y el nombre.
 * Atrás devuelve cancelación. Escuchar una muestra nunca confirma la elección por sí solo.
 */
class ActividadSelectorMusica : Activity() {

    // Recuerda la cuenta de esta pantalla y bloquea el regreso después de cerrar su sesión.
    private val proteccion = ProtectorSesion(this)
    // Estos objetos y controles se preparan al cargar el diseño; lateinit permite asignarlos entonces.
    private lateinit var biblioteca: BibliotecaMusica
    private lateinit var preescucha: PreescuchaMusica
    private lateinit var adaptador: AdaptadorMusica
    private lateinit var busqueda: EditText
    private lateinit var lista: ListView
    private lateinit var estadoLista: TextView
    private lateinit var controlesBiblioteca: View

    // Estos trabajos consultan catálogo y documentos. Las imágenes tienen su cargador independiente.
    // El trabajo principal solo recibe resultados para actualizar los controles de Android.
    private val ejecutor = Executors.newFixedThreadPool(2)
    private val hiloPrincipal = Handler(Looper.getMainLooper())

    // Estas marcas recuerdan qué se está haciendo y evitan repetir lecturas o usar una pantalla cerrada.
    private var cancelacionConsulta: CancellationSignal? = null
    private var destruida = false
    private var cargandoBiblioteca = false
    private var bibliotecaCargada = false
    private var importando = false
    private var permisoSolicitado = false
    private var permisoDenegado = false

    // La biblioteca y los archivos importados se reúnen al dibujar, sin cambiar sus datos originales.
    private var canciones: List<CancionLocal> = emptyList()
    private val importadas = linkedMapOf<String, CancionLocal>()
    private var carpetaActual = ""
    private var enCarpetas = false

    // tieneEleccion distingue «todavía no eligió» de una elección explícita de «Sin música».
    private var uriElegida: String? = null
    private var nombreElegido = ""
    private var tieneEleccion = false

    // La muestra tiene su propio estado: escuchar un audio no cambia la canción elegida.
    private var nombreMuestra = ""
    private var estadoAnteriorMuestra = ""

    // Consultar canciones, preparar búsquedas y leer portadas tienen trabajos distintos.
    // Así una imagen lenta no retrasa escribir, importar ni volver al formulario.
    private val ejecutorFiltro = Executors.newSingleThreadExecutor()
    private var trabajoFiltro: Future<*>? = null
    private var versionBusqueda = 0
    private var versionAudios = 0
    private var versionIndiceTrabajo = -1
    private var indiceTrabajo = IndiceBibliotecaMusica(emptyList())
    private var indicePublicado = indiceTrabajo
    private lateinit var cargadorCaratulas: CargadorCaratulas
    private var propietario: String? = null
    private var visible = false
    private var filtrando = false
    private var desplazandoRapido = false
    private var consultaPendiente = false
    private var observandoBiblioteca = false
    private var validando = false
    private var versionEleccion = 0
    private var cancelacionDocumento: CancellationSignal? = null
    private var cancelacionValidacion: CancellationSignal? = null
    private var uriPendienteRestaurar: String? = null
    private var estadoListaGuardado: Parcelable? = null
    private var claveVistaActual = ""
    private val posiciones = object : LinkedHashMap<String, Parcelable>() {
        /** Conserva las últimas rutas visitadas, sin acumular un estado por cada búsqueda antigua. */
        override fun removeEldestEntry(entrada: MutableMap.MutableEntry<String, Parcelable>?): Boolean {
            return size > 24
        }
    }

    private val buscarTrasEscribir = Runnable { actualizarPantalla() }
    private val actualizarBiblioteca = Runnable {
        if (visible && aceptaResultado() && tienePermisoBiblioteca()) {
            consultarBiblioteca()
        }
    }
    private val observadorBiblioteca = object : ContentObserver(hiloPrincipal) {
        /** Android avisa de altas, cambios o bajas; reúne varios avisos antes de volver a consultar. */
        override fun onChange(cambioPropio: Boolean) {
            hiloPrincipal.removeCallbacks(actualizarBiblioteca)
            hiloPrincipal.postDelayed(actualizarBiblioteca, 300L)
        }
    }

    /**
     * Android la llama al crear esta pantalla para preparar controles y recuperar la elección.
     * Recibe datos temporales guardados, o nada si se abre por primera vez; no devuelve un dato.
     * Conecta biblioteca, filas y preescucha antes de pedir permiso o recuperar un archivo importado.
     */
    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)

        if (!proteccion.permitir(estadoGuardado)) {
            return
        }

        setResult(RESULT_CANCELED)
        propietario = EspacioPersonal.claveActual(this)
        biblioteca = BibliotecaMusica(applicationContext.contentResolver)
        cargadorCaratulas = CargadorCaratulas(biblioteca)
        restaurarEleccion(estadoGuardado)
        setContentView(R.layout.musica_pantalla_selector)
        configurarVentana()

        lista = buscarVista(R.id.musica_lista)
        estadoLista = buscarVista(R.id.musica_estado_lista)
        controlesBiblioteca = buscarVista(R.id.musica_controles)

        // La búsqueda viaja como cabecera y el aviso como pie de la lista para que se desplacen con ella.
        (controlesBiblioteca.parent as ViewGroup).removeView(controlesBiblioteca)
        (estadoLista.parent as ViewGroup).removeView(estadoLista)
        estadoLista.layoutParams = android.widget.AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lista.addHeaderView(controlesBiblioteca, null, false)
        lista.addFooterView(estadoLista, null, false)
        lista.itemsCanFocus = true

        busqueda = buscarVista(R.id.musica_busqueda)
        preescucha = PreescuchaMusica(this, ::actualizarPreescucha) {
            mostrarAviso(R.string.musica_error_muestra)
        }
        adaptador = AdaptadorMusica(
            this,
            ::elegirFila,
            ::escuchar,
            ::cargarCaratula,
            preescucha,
        )
        lista.adapter = adaptador
        conectarControles()
        busqueda.setText(estadoGuardado?.getString("musica_busqueda_guardada").orEmpty())
        actualizarPantalla()

        if (!tienePermisoBiblioteca()) {
            mostrarPermiso(
                if (permisoDenegado) R.string.musica_permiso_denegado else R.string.musica_permiso,
            )

            if (!permisoSolicitado) {
                solicitarPermiso()
            }
        }

        // Primero se busca la selección anterior en el catálogo: si ya está allí no se abre dos veces.
        uriPendienteRestaurar = uriElegida
        estadoListaGuardado = estadoGuardado?.getParcelable("musica_estado_lista")

        prepararRetroceso()
    }

    /**
     * Las funciones de esta pantalla la llaman para encontrar un control por su identificador.
     * Recibe ese identificador y devuelve el control del tipo solicitado, por ejemplo un botón.
     * Busca también en la cabecera de la lista, porque sus controles pueden estar fuera del diseño fijo.
     */
    private fun <T : View> buscarVista(id: Int): T =
        super.findViewById<T>(id) ?: controlesBiblioteca.findViewById(id)

    /**
     * onCreate la llama antes de dibujar para recuperar la elección y la carpeta visitada.
     * Recibe los datos temporales guardados, si existen, y no devuelve un dato.
     * En una primera apertura lee lo enviado por la pantalla anterior; recupera datos, no audio sonando.
     */
    private fun restaurarEleccion(estado: Bundle?) {
        uriElegida = if (estado != null) {
            estado.getString(EXTRA_URI)
        } else {
            intent.getStringExtra(EXTRA_URI)
        }

        tieneEleccion = estado?.getBoolean("musica_tiene_eleccion")
            ?: (intent.hasExtra(EXTRA_URI) || intent.hasExtra(EXTRA_NOMBRE))
        nombreElegido = estado?.getString(EXTRA_NOMBRE)
            ?: intent.getStringExtra(EXTRA_NOMBRE).orEmpty()
        enCarpetas = estado?.getBoolean("musica_en_carpetas") ?: false
        carpetaActual = estado?.getString("musica_carpeta_actual").orEmpty()
        permisoSolicitado = estado?.getBoolean("musica_permiso_solicitado") ?: false
        permisoDenegado = estado?.getBoolean("musica_permiso_denegado") ?: false

        // Los documentos conservan metadatos para que girar no borre artista, duración o nombre de archivo.
        val documentosCompletos = estado?.getParcelableArrayList<Bundle>("musica_documentos")
        if (documentosCompletos != null) {
            documentosCompletos.forEach { datos ->
                val uri = datos.getString("uri") ?: return@forEach
                importadas[uri] = CancionLocal(
                    Uri.parse(uri),
                    datos.getString("nombre").orEmpty(),
                    datos.getString("artista").orEmpty(),
                    datos.getLong("duracion"),
                    datos.getString("carpeta").orEmpty(),
                    datos.getString("archivo").orEmpty()
                )
            }
            return
        }

        // Las dos listas guardadas comparten posiciones: cada dirección recupera el nombre de la misma posición.
        val documentos = estado?.getStringArrayList("musica_importadas_uri").orEmpty()
        val nombres = estado?.getStringArrayList("musica_importadas_nombre").orEmpty()
        documentos.forEachIndexed { indice, uri ->
            importadas[uri] = CancionLocal(
                Uri.parse(uri),
                nombres.getOrElse(indice) { "" },
                "",
                0,
                "",
            )
        }
    }

    /**
     * onCreate la llama después de preparar la lista y el reproductor.
     * No recibe argumentos ni devuelve un dato: indica qué debe pasar al tocar cada control.
     * También escucha la escritura de búsqueda para filtrar lo que ya está cargado.
     */
    private fun conectarControles() {
        val tituloTarea = intent.getStringExtra(EXTRA_TAREA_TITULO).orEmpty()
            .ifBlank { getString(R.string.musica_tarea) }
        buscarVista<TextView>(R.id.musica_para).text = getString(R.string.musica_para, tituloTarea)

        buscarVista<View>(R.id.musica_regresar).setOnClickListener {
            cancelar()
        }

        buscarVista<View>(R.id.musica_tab_canciones).setOnClickListener {
            guardarPosicionActual()
            enCarpetas = false
            actualizarPantalla()
        }

        buscarVista<View>(R.id.musica_tab_carpetas).setOnClickListener {
            guardarPosicionActual()
            enCarpetas = true
            actualizarPantalla()
        }

        buscarVista<View>(R.id.musica_subir).setOnClickListener {
            subirCarpeta()
        }

        buscarVista<View>(R.id.musica_importar).setOnClickListener {
            importarCancion()
        }
        buscarVista<View>(R.id.musica_actualizar).setOnClickListener {
            if (tienePermisoBiblioteca()) consultarBiblioteca() else solicitarPermiso()
        }
        buscarVista<View>(R.id.musica_limpiar_busqueda).setOnClickListener {
            busqueda.text.clear()
            actualizarPantalla()
        }
        lista.setOnScrollListener(object : AbsListView.OnScrollListener {
            /** Durante un desplazamiento rápido se posponen las portadas que enseguida saldrían de vista. */
            override fun onScrollStateChanged(vista: AbsListView?, estado: Int) {
                desplazandoRapido = estado == AbsListView.OnScrollListener.SCROLL_STATE_FLING
                if (desplazandoRapido) {
                    cargadorCaratulas.cancelarPendientes()
                } else {
                    adaptador.actualizarCaratulas(lista)
                }
            }

            /** Android informa qué filas se ven; no se consulta ni se ordena música al mover cada píxel. */
            override fun onScroll(vista: AbsListView?, primera: Int, visibles: Int, total: Int) = Unit
        })

        buscarVista<View>(R.id.musica_boton_permiso).setOnClickListener {
            if (tienePermisoBiblioteca()) {
                consultarBiblioteca()
            } else if (permisoDenegado && !shouldShowRequestPermissionRationale(permisoBiblioteca())) {
                // Si Android ya no muestra la pregunta, se ofrece la página de permisos de la aplicación.
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } else {
                solicitarPermiso()
            }
        }

        buscarVista<View>(R.id.musica_confirmar).setOnClickListener {
            confirmar()
        }

        buscarVista<View>(R.id.musica_pausa_muestra).setOnClickListener {
            preescucha.uriActual?.let {
                preescucha.alternar(it)
            }
        }

        // Android llama estas tres funciones alrededor de cada cambio del texto de búsqueda.
        busqueda.addTextChangedListener(object : TextWatcher {
            /**
             * Android la llama antes de cambiar el texto. Recibe el texto anterior, el inicio del cambio,
             * la cantidad de caracteres que se quitarán y cuántos se añadirán después.
             * No devuelve un dato ni modifica la entrada: el filtrado se hace en onTextChanged.
             */
            override fun beforeTextChanged(
                texto: CharSequence?,
                inicio: Int,
                cantidad: Int,
                despues: Int,
            ) = Unit

            /**
             * Android la llama mientras cambia la búsqueda. Recibe el texto resultante, el inicio,
             * la cantidad anterior de caracteres y la cantidad que ocupa el cambio.
             * No devuelve un dato: actualiza las filas usando la colección que ya está cargada.
             * Así escribir no vuelve a consultar los archivos del teléfono en cada letra.
             */
            override fun onTextChanged(
                texto: CharSequence?,
                inicio: Int,
                antes: Int,
                cantidad: Int,
            ) {
                hiloPrincipal.removeCallbacks(buscarTrasEscribir)
                hiloPrincipal.postDelayed(buscarTrasEscribir, 160L)
                buscarVista<View>(R.id.musica_limpiar_busqueda).visibility =
                    if (texto.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            }

            /**
             * Android la llama al terminar un cambio y entrega el texto editable resultante.
             * No devuelve un dato ni realiza otra acción: onTextChanged ya actualizó la lista.
             */
            override fun afterTextChanged(texto: Editable?) = Unit
        })
    }

    /**
     * onCreate la llama para adaptar el diseño a las barras del sistema y al teclado.
     * No recibe argumentos ni devuelve un dato: ajusta colores y espacios de los controles.
     * Android avisa cuando cambian esos espacios para mantener visible la confirmación.
     */
    @Suppress("DEPRECATION")
    private fun configurarVentana() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }

        window.statusBarColor = getColor(R.color.ciruela)
        window.navigationBarColor = getColor(R.color.marfil)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val raiz = buscarVista<View>(R.id.musica_raiz)
        val cabecera = buscarVista<View>(R.id.musica_cabecera)
        val rellenoSuperior = cabecera.paddingTop

        // Android entrega los espacios ocupados por sus barras, el recorte de pantalla y el teclado.
        // Se aplican como relleno para que los controles no queden tapados.
        raiz.setOnApplyWindowInsetsListener { _, margenes ->
            val izquierda: Int
            val derecha: Int
            val superior: Int
            val inferior: Int

            if (Build.VERSION.SDK_INT >= 30) {
                val barras = margenes.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                izquierda = barras.left
                derecha = barras.right
                superior = barras.top
                inferior = maxOf(barras.bottom, margenes.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                izquierda = margenes.systemWindowInsetLeft
                derecha = margenes.systemWindowInsetRight
                superior = margenes.systemWindowInsetTop
                inferior = margenes.systemWindowInsetBottom
            }

            raiz.setPadding(izquierda, 0, derecha, inferior)
            cabecera.setPadding(
                cabecera.paddingLeft,
                rellenoSuperior + superior,
                cabecera.paddingRight,
                cabecera.paddingBottom,
            )

            margenes
        }

        raiz.requestApplyInsets()
    }

    /**
     * Las funciones de permisos la llaman para saber qué autorización entiende este Android.
     * No recibe argumentos y devuelve el nombre del permiso de audio o de lectura, según la versión.
     */
    private fun permisoBiblioteca(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    /**
     * onCreate, consultarBiblioteca y onResume la llaman antes de leer las canciones del teléfono.
     * No recibe argumentos y devuelve verdadero si Android permite acceder a la biblioteca.
     * Se consulta de nuevo al volver porque la persona puede cambiar el permiso en Ajustes.
     */
    private fun tienePermisoBiblioteca(): Boolean =
        checkSelfPermission(permisoBiblioteca()) == PackageManager.PERMISSION_GRANTED

    /**
     * onCreate o el botón de permiso la llaman para pedir acceso a la biblioteca del teléfono.
     * No recibe argumentos ni devuelve un dato: Android preguntará y luego llamará onRequestPermissionsResult.
     * Se recuerda que ya se preguntó; importar un archivo y elegir silencio siguen disponibles.
     */
    private fun solicitarPermiso() {
        permisoSolicitado = true
        requestPermissions(arrayOf(permisoBiblioteca()), SOLICITUD_PERMISO)
    }

    /**
     * La apertura, la consulta y las respuestas de permisos la llaman si falta acceso o falla la lectura.
     * Recibe el identificador de un texto del proyecto y no devuelve un dato.
     * Hace visible la explicación junto al botón que permite revisar el acceso.
     */
    private fun mostrarPermiso(mensaje: Int) {
        buscarVista<View>(R.id.musica_aviso_permiso).visibility = View.VISIBLE
        buscarVista<TextView>(R.id.musica_texto_permiso).setText(mensaje)
    }

    /**
     * Android la llama después de que la persona responde a una solicitud de permiso.
     * Recibe el número de solicitud, los permisos pedidos y sus resultados; no devuelve un dato.
     * Si se concede acceso consulta la biblioteca; si se deniega retira sus canciones y explica el permiso.
     */
    override fun onRequestPermissionsResult(
        solicitud: Int,
        permisos: Array<out String>,
        resultados: IntArray,
    ) {
        super.onRequestPermissionsResult(solicitud, permisos, resultados)

        if (solicitud != SOLICITUD_PERMISO) {
            return
        }

        if (resultados.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permisoDenegado = false
            consultarBiblioteca()
        } else {
            permisoDenegado = true
            cancelarConsultaBiblioteca()
            detenerObservacion()
            canciones = emptyList()
            bibliotecaCargada = false
            versionAudios += 1
            mostrarPermiso(R.string.musica_permiso_denegado)
            actualizarPantalla()
        }
    }

    /**
     * La apertura y las revisiones de permisos la llaman cuando la biblioteca puede leerse.
     * No recibe argumentos ni devuelve un dato: pide las canciones a BibliotecaMusica y actualiza filas.
     * Usa un trabajo separado, llamado hilo, para leer sin congelar botones ni la búsqueda.
     */
    private fun consultarBiblioteca() {
        if (!aceptaResultado() || !tienePermisoBiblioteca()) {
            return
        }
        if (cargandoBiblioteca) {
            consultaPendiente = true
            return
        }
        cargandoBiblioteca = true
        consultaPendiente = false
        buscarVista<View>(R.id.musica_aviso_permiso).visibility = View.GONE
        val cancelacion = CancellationSignal()
        cancelacionConsulta = cancelacion
        actualizarControles()

        ejecutor.execute {
            val resultado = runCatching { biblioteca.leerCanciones(cancelacion) }
            hiloPrincipal.post {
                if (!aceptaResultado() || cancelacionConsulta !== cancelacion || cancelacion.isCanceled) return@post
                cargandoBiblioteca = false
                cancelacionConsulta = null
                resultado.onSuccess { nuevos ->
                    val anteriores = canciones.mapTo(hashSetOf()) { it.uri.toString() }
                    canciones = nuevos
                    bibliotecaCargada = true
                    versionAudios += 1
                    buscarVista<View>(R.id.musica_aviso_permiso).visibility = View.GONE
                    val seleccion = uriElegida
                    if (seleccion != null && seleccion in anteriores && nuevos.none { it.uri.toString() == seleccion }) {
                        invalidarEleccion(seleccion)
                    }
                }.onFailure {
                    canciones = emptyList()
                    bibliotecaCargada = false
                    versionAudios += 1
                    mostrarPermiso(if (tienePermisoBiblioteca()) R.string.musica_biblioteca_error else R.string.musica_permiso_denegado)
                }
                restaurarAudioAnterior()
                actualizarPantalla()
                if (consultaPendiente && visible) consultarBiblioteca()
            }
        }
    }

    /**
     * El botón Importar la llama para abrir el selector de archivos de Android.
     * No recibe argumentos ni devuelve un dato: solicita un audio local y espera onActivityResult.
     * Si el teléfono no ofrece ese selector muestra una explicación.
     */
    @Suppress("DEPRECATION")
    private fun importarCancion() {
        val solicitud = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

        try {
            startActivityForResult(solicitud, SOLICITUD_DOCUMENTO)
        } catch (_: android.content.ActivityNotFoundException) {
            mostrarAviso(R.string.musica_error_importar)
        }
    }

    /**
     * Android la llama cuando se vuelve del selector que abrió importarCancion.
     * Recibe la solicitud, el resultado y los datos del archivo; no devuelve un dato.
     * Conserva la autorización del audio y pide cargarlo para que pueda usarse después en la tarea.
     */
    @Deprecated("Se mantiene el contrato nativo de Activity sin dependencias adicionales.")
    override fun onActivityResult(
        solicitud: Int,
        resultado: Int,
        datos: Intent?,
    ) {
        super.onActivityResult(solicitud, resultado, datos)

        if (solicitud != SOLICITUD_DOCUMENTO || resultado != RESULT_OK) {
            return
        }

        val uri = datos?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            mostrarAviso(R.string.musica_acceso_temporal)
            return
        }

        // Importar es una decisión nueva: una recuperación anterior no debe cancelarla al volver.
        uriPendienteRestaurar = null
        cargarDocumento(uri, seleccionar = true)
    }

    /**
     * onCreate la llama para recuperar un audio anterior y onActivityResult para importar uno nuevo.
     * Recibe la dirección del archivo y si debe seleccionarlo; no devuelve un dato.
     * Lee en un trabajo separado y solo cambia la elección después de conseguir un audio accesible.
     */
    private fun cargarDocumento(uri: Uri, seleccionar: Boolean) {
        if (!aceptaResultado() || (!seleccionar && importando)) {
            return
        }
        cancelacionDocumento?.cancel()
        val cancelacion = CancellationSignal()
        cancelacionDocumento = cancelacion
        val decision = versionEleccion
        if (seleccionar) importando = true
        actualizarControles()

        ejecutor.execute {
            val resultado = runCatching {
                cancelacion.throwIfCanceled()
                val tipo = contentResolver.getType(uri)
                require(tipo == null || tipo.startsWith("audio/") || tipo == "application/ogg")
                biblioteca.leerDocumento(uri, cancelacion)
            }
            hiloPrincipal.post {
                if (!aceptaResultado() || cancelacionDocumento !== cancelacion || cancelacion.isCanceled) return@post
                cancelacionDocumento = null
                if (seleccionar) importando = false
                resultado.onSuccess { audio ->
                    importadas[uri.toString()] = audio
                    versionAudios += 1
                    // La última elección de la persona prevalece sobre un archivo que terminó de cargar tarde.
                    if (seleccionar && versionEleccion == decision) {
                        uriElegida = uri.toString()
                        nombreElegido = nombreVisible(audio)
                        tieneEleccion = true
                        versionEleccion += 1
                        enCarpetas = false
                        busqueda.text.clear()
                    } else if (uriElegida == uri.toString() && nombreElegido.isBlank()) {
                        nombreElegido = nombreVisible(audio)
                    }
                }.onFailure {
                    if (seleccionar) mostrarAviso(R.string.musica_error_importar)
                    else invalidarEleccion(uri.toString())
                }
                actualizarPantalla()
            }
        }
    }

    /**
     * La navegación, las lecturas terminadas y la búsqueda piden aquí sus filas.
     * Ordenar y filtrar se hacen en otro trabajo; la pantalla solo dibuja el resultado más reciente.
     * Cada cambio invalida la búsqueda anterior para que un resultado lento no sustituya al nuevo.
     */
    private fun actualizarPantalla() {
        if (!::adaptador.isInitialized || !aceptaResultado()) {
            return
        }
        hiloPrincipal.removeCallbacks(buscarTrasEscribir)
        trabajoFiltro?.cancel(true)
        val turno = ++versionBusqueda
        val datos = versionAudios
        val bibliotecaActual = canciones
        val documentos = importadas.values.toList()
        val consulta = busqueda.text.toString()
        val porCarpetas = enCarpetas
        val ruta = carpetaActual

        // Al girar, la biblioteca comienza vacía mientras Android entrega sus canciones.
        // Esa espera no significa que haya desaparecido la carpeta que la persona tenía abierta.
        val conservarRutaPendiente = !bibliotecaCargada && tienePermisoBiblioteca() && ruta.isNotBlank()
        filtrando = true
        actualizarControles()

        trabajoFiltro = ejecutorFiltro.submit {
            try {
                if (versionIndiceTrabajo != datos) {
                    val nuevoIndice = IndiceBibliotecaMusica(bibliotecaActual + documentos)
                    indiceTrabajo = nuevoIndice
                    versionIndiceTrabajo = datos
                }
                val indice = indiceTrabajo
                val resultado = indice.buscar(consulta, porCarpetas, ruta)
                if (Thread.currentThread().isInterrupted) {
                    return@submit
                }

                // FilaMusica es un dato sencillo, no un control de Android. Preparar estas filas
                // aquí evita que miles de canciones ocupen el trabajo que dibuja la pantalla.
                val filas = ArrayList<FilaMusica>(1 + resultado.carpetas.size + resultado.canciones.size)
                filas.add(FilaMusica.SinMusica)
                resultado.carpetas.forEach { carpeta ->
                    filas.add(FilaMusica.Carpeta(carpeta.ruta, carpeta.nombre, carpeta.cantidad))
                }
                resultado.canciones.forEach { cancion ->
                    filas.add(FilaMusica.Cancion(cancion))
                }

                hiloPrincipal.post {
                    if (!aceptaResultado() || turno != versionBusqueda || datos != versionAudios) return@post
                    val rutaResultado = if (conservarRutaPendiente) ruta else resultado.carpeta
                    val clave = "$porCarpetas|$rutaResultado|$consulta"
                    val mantener = if (clave == claveVistaActual) lista.onSaveInstanceState() else posiciones[clave]
                    guardarPosicionActual()
                    claveVistaActual = clave
                    indicePublicado = indice
                    carpetaActual = rutaResultado
                    filtrando = false
                    cargadorCaratulas.cancelarPendientes()
                    adaptador.filas = filas
                    adaptador.uriElegida = uriElegida
                    adaptador.tieneEleccion = tieneEleccion
                    adaptador.notifyDataSetChanged()
                    actualizarControles()
                    lista.post {
                        if (aceptaResultado() && turno == versionBusqueda) {
                            val restaurado = if (!cargandoBiblioteca && !importando) estadoListaGuardado else null
                            val posicion = restaurado ?: mantener
                            if (posicion != null) lista.onRestoreInstanceState(posicion) else lista.setSelection(0)
                            if (restaurado != null) estadoListaGuardado = null
                            adaptador.actualizarCaratulas(lista)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Seguir escribiendo o salir cancela un cálculo anterior sin mostrar un error.
            } catch (_: Exception) {
                hiloPrincipal.post {
                    if (aceptaResultado() && turno == versionBusqueda) {
                        filtrando = false
                        actualizarControles()
                        mostrarAviso(R.string.musica_error_busqueda)
                    }
                }
            }
        }
    }

    /**
     * Refleja carga, ruta y selección sin ordenar ni reconstruir la lista de canciones.
     * Las lecturas y los botones la usan para responder enseguida mientras otro trabajo calcula.
     */
    private fun actualizarControles() {
        if (!::adaptador.isInitialized || destruida) {
            return
        }
        buscarVista<View>(R.id.musica_navegacion).visibility = if (enCarpetas) View.VISIBLE else View.GONE
        buscarVista<View>(R.id.musica_subir).isEnabled = carpetaActual.isNotBlank()
        buscarVista<View>(R.id.musica_subir).alpha = if (carpetaActual.isBlank()) 0.3f else 1f
        buscarVista<TextView>(R.id.musica_ruta).text =
            (listOf(getString(R.string.musica_biblioteca)) + carpetaActual.split('/').filter { it.isNotBlank() }).joinToString(" › ")
        decorarPestana(buscarVista(R.id.musica_tab_canciones), !enCarpetas)
        decorarPestana(buscarVista(R.id.musica_tab_carpetas), enCarpetas)
        buscarVista<TextView>(R.id.musica_elegida).text = when {
            !tieneEleccion -> getString(R.string.musica_elegir)
            uriElegida == null -> getString(R.string.musica_sin)
            else -> nombreElegido.ifBlank { getString(R.string.musica_nombre) }
        }
        buscarVista<TextView>(R.id.musica_resumen).text = if (cargandoBiblioteca || filtrando) {
            getString(R.string.musica_cargando)
        } else {
            resources.getQuantityString(R.plurals.musica_cantidad_biblioteca, indicePublicado.cantidad, indicePublicado.cantidad)
        }
        buscarVista<View>(R.id.musica_confirmar).isEnabled = tieneEleccion && !importando && !validando
        buscarVista<View>(R.id.musica_importar).isEnabled = !importando && !validando
        buscarVista<View>(R.id.musica_actualizar).isEnabled = !cargandoBiblioteca && !validando
        buscarVista<Button>(R.id.musica_boton_permiso).setText(
            if (tienePermisoBiblioteca()) R.string.musica_reintentar else R.string.musica_permitir
        )
        buscarVista<View>(R.id.musica_limpiar_busqueda).visibility =
            if (busqueda.text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        estadoLista.visibility = if (adaptador.count <= 1 || importando || validando) View.VISIBLE else View.GONE
        estadoLista.setText(when {
            validando -> R.string.musica_comprobando
            importando -> R.string.musica_cargar_documento
            cargandoBiblioteca || filtrando -> R.string.musica_cargando
            busqueda.text.isNotBlank() -> R.string.musica_sin_resultados
            else -> R.string.musica_vacio
        })
    }

    /** Guarda la posición de la ruta actual para regresar sin volver al principio de una lista larga. */
    private fun guardarPosicionActual() {
        if (::lista.isInitialized && claveVistaActual.isNotEmpty()) {
            lista.onSaveInstanceState()?.let { posiciones[claveVistaActual] = it }
        }
    }

    /**
     * Recupera el audio anterior desde los datos ya consultados; abre su archivo solo si hace falta.
     * No cambia una elección nueva que la persona haya hecho mientras se leía la biblioteca.
     */
    private fun restaurarAudioAnterior() {
        if (importando) {
            return
        }
        val uri = uriPendienteRestaurar ?: return
        uriPendienteRestaurar = null
        if (uriElegida != uri) {
            return
        }
        val delCatalogo = buscarAudioDelCatalogo(uri)
        val conocido = delCatalogo ?: importadas[uri]
        if (delCatalogo != null) {
            // Las versiones anteriores usaban external; Android también conoce la unidad concreta.
            // Se conserva una sola fila y el formulario recibirá la dirección comprobada al confirmar.
            if (importadas.remove(uri) != null) versionAudios += 1
            uriElegida = delCatalogo.uri.toString()
        }
        if (conocido != null && (delCatalogo != null || conocido.duracionMilisegundos > 0L)) {
            // Un audio del catálogo puede no indicar duración. Eso no obliga a abrir otra
            // copia: al confirmar se comprueba por separado que el archivo se pueda leer.
            if (nombreElegido.isBlank()) {
                nombreElegido = nombreVisible(conocido)
            }
        } else {
            cargarDocumento(Uri.parse(uri), seleccionar = false)
        }
    }

    /**
     * Recibe una dirección anterior y busca el audio sin abrir su archivo otra vez.
     * external es la vista conjunta del catálogo; solo se sustituye por una unidad concreta
     * cuando hay una única coincidencia. Las direcciones de documentos conservan su permiso propio.
     */
    private fun buscarAudioDelCatalogo(direccion: String): CancionLocal? {
        canciones.firstOrNull { it.uri.toString() == direccion }?.let { return it }
        val uri = Uri.parse(direccion)
        val segmentos = uri.pathSegments
        if (uri.authority != "media" || segmentos.size != 4 ||
            segmentos[0] != "external" || segmentos[1] != "audio" || segmentos[2] != "media") {
            return null
        }
        return canciones.filter { audio ->
            audio.uri.authority == "media" && audio.uri.pathSegments.drop(1) == segmentos.drop(1)
        }.singleOrNull()
    }

    /**
     * Una baja del catálogo o una comprobación fallida retira solo la elección temporal del selector.
     * El formulario conserva su canción anterior hasta que la persona confirme otra o Sin música.
     */
    private fun invalidarEleccion(uri: String) {
        if (importadas.remove(uri) != null) versionAudios += 1
        if (uriElegida == uri) {
            uriElegida = null
            nombreElegido = ""
            tieneEleccion = false
            versionEleccion += 1
            mostrarAviso(R.string.musica_no_disponible)
        }
    }

    /** Devuelve si un resultado todavía pertenece a esta pantalla abierta y a la misma cuenta. */
    private fun aceptaResultado(): Boolean {
        return !destruida && !isFinishing && propietario == EspacioPersonal.claveActual(this)
    }

    /** Cancela la consulta anterior; Android recibe su señal para no entregar canciones de otra visita. */
    private fun cancelarConsultaBiblioteca() {
        cancelacionConsulta?.cancel()
        cancelacionConsulta = null
        cargandoBiblioteca = false
        consultaPendiente = false
    }

    /**
     * Mientras el selector está visible escucha cambios de audios en las unidades disponibles.
     * El observador reúne varias altas o bajas; nunca consulta música cuando la pantalla está oculta.
     */
    private fun iniciarObservacion() {
        if (observandoBiblioteca || !tienePermisoBiblioteca()) {
            return
        }
        try {
            val direcciones = linkedSetOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.getExternalVolumeNames(this).forEach { volumen ->
                    direcciones += MediaStore.Audio.Media.getContentUri(volumen)
                }
            }
            direcciones.forEach { contentResolver.registerContentObserver(it, true, observadorBiblioteca) }
            observandoBiblioteca = true
        } catch (_: RuntimeException) {
            contentResolver.unregisterContentObserver(observadorBiblioteca)
            observandoBiblioteca = false
            // Actualizar y volver a esta pantalla siguen disponibles si el proveedor no admite observar.
        }
    }

    /** Retira la observación al salir o perder el permiso para no consumir batería fuera del selector. */
    private fun detenerObservacion() {
        if (observandoBiblioteca) contentResolver.unregisterContentObserver(observadorBiblioteca)
        observandoBiblioteca = false
        hiloPrincipal.removeCallbacks(actualizarBiblioteca)
    }

    /**
     * actualizarPantalla la llama para dibujar las pestañas Canciones y Carpetas.
     * Recibe el botón y si es la pestaña activa; no devuelve un dato.
     * Aplica su estado seleccionado, colores, borde y esquinas para indicar dónde está la persona.
     */
    private fun decorarPestana(boton: Button, seleccionada: Boolean) {
        if (boton.tag == seleccionada) {
            return
        }
        boton.tag = seleccionada
        boton.isSelected = seleccionada
        boton.setTextColor(getColor(if (seleccionada) R.color.marfil else R.color.ciruela))
        boton.background = GradientDrawable().apply {
            setColor(getColor(if (seleccionada) R.color.ciruela else R.color.fondo_campo))
            cornerRadius = 12 * resources.displayMetrics.density
            setStroke(
                (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                getColor(R.color.borde_campo),
            )
        }
    }

    /**
     * El adaptador llama esta función al tocar una fila o su círculo de selección.
     * Recibe esa fila y no devuelve un dato: elige canción o silencio, o abre una carpeta.
     * Entrar en una carpeta conserva la elección anterior; después se vuelve a dibujar la pantalla.
     */
    private fun elegirFila(fila: FilaMusica) {
        if (validando) {
            return
        }
        if (fila is FilaMusica.Carpeta) {
            guardarPosicionActual()
            carpetaActual = fila.ruta
            busqueda.text.clear()
            actualizarPantalla()
            return
        }
        versionEleccion += 1
        if (fila is FilaMusica.Cancion) {
            uriElegida = fila.audio.uri.toString()
            nombreElegido = nombreVisible(fila.audio)
        } else {
            uriElegida = null
            nombreElegido = getString(R.string.musica_sin)
        }
        tieneEleccion = true
        adaptador.uriElegida = uriElegida
        adaptador.tieneEleccion = true
        adaptador.actualizarSeleccion(lista)
        actualizarControles()
    }

    /**
     * El botón de subir y volver la llaman para pasar a la carpeta que contiene la actual.
     * No recibe argumentos ni devuelve un dato: cambia la ruta, limpia la búsqueda y muestra el inicio.
     * Si ya se está en la raíz, no cambia nada.
     */
    private fun subirCarpeta() {
        if (carpetaActual.isEmpty()) {
            return
        }

        guardarPosicionActual()
        carpetaActual = carpetaActual.substringBeforeLast('/', "")
        busqueda.text.clear()
        actualizarPantalla()
    }

    /**
     * La importación, elegirFila y escuchar la llaman para mostrar un nombre comprensible.
     * Recibe una canción y devuelve su nombre, o el texto del proyecto si el archivo no trae uno.
     */
    private fun nombreVisible(audio: CancionLocal): String =
        audio.nombre.ifBlank { getString(R.string.musica_nombre) }

    /**
     * El adaptador llama esta función cuando se toca el botón de escucha de una canción.
     * Recibe esa canción y no devuelve un dato: recuerda su nombre y pasa su dirección a PreescuchaMusica.
     * La muestra puede pausarse o reanudarse sin cambiar la selección del formulario.
     */
    private fun escuchar(audio: CancionLocal) {
        nombreMuestra = nombreVisible(audio)
        preescucha.alternar(audio.uri)
    }

    /**
     * PreescuchaMusica llama esta función al preparar, iniciar, pausar, avanzar o detener una muestra.
     * No recibe argumentos ni devuelve un dato: actualiza tiempo, progreso y controles de escucha.
     * Solo vuelve a dibujar las filas cuando cambia el audio o su estado, no en cada avance del reloj.
     */
    private fun actualizarPreescucha() {
        if (destruida || !::adaptador.isInitialized) {
            return
        }

        val tieneMuestra = preescucha.uriActual != null
        buscarVista<android.widget.ImageButton>(R.id.musica_pausa_muestra).apply {
            visibility = if (tieneMuestra) View.VISIBLE else View.GONE
            isEnabled = !preescucha.preparando
            setImageResource(
                if (preescucha.reproduciendo) R.drawable.musica_icono_pausa else R.drawable.musica_icono_reproducir,
            )
            contentDescription = if (preescucha.reproduciendo) "Pausar muestra" else "Reanudar muestra"
        }

        buscarVista<android.widget.ProgressBar>(R.id.musica_progreso_muestra).apply {
            visibility = if (tieneMuestra) View.VISIBLE else View.GONE
            progress = preescucha.segundos
        }

        // Esta firma resume audio, pausa y preparación. El tiempo queda fuera para no reconstruir
        // todas las filas cada cuarto de segundo mientras la muestra sigue en el mismo estado.
        val firma = "${preescucha.uriActual}:${preescucha.reproduciendo}:${preescucha.preparando}"
        if (firma != estadoAnteriorMuestra) {
            estadoAnteriorMuestra = firma
            adaptador.actualizarEstadoPreescucha(lista)
        }

        buscarVista<TextView>(R.id.musica_estado_muestra).text = when {
            preescucha.preparando -> getString(R.string.musica_preparando)
            preescucha.uriActual == null -> getString(R.string.musica_ayuda_muestra)
            preescucha.reproduciendo -> getString(R.string.musica_muestra, nombreMuestra, preescucha.segundos)
            else -> getString(R.string.musica_pausa, nombreMuestra, preescucha.segundos)
        }
    }

    /**
     * El adaptador la llama al dibujar la imagen de una canción.
     * Recibe la dirección del audio y el control de imagen que debe mostrarla; no devuelve un dato.
     * Reutiliza carátulas guardadas o las lee en un trabajo separado para mantener la lista ágil.
     */
    private fun cargarCaratula(uri: Uri, vista: ImageView) {
        if (aceptaResultado() && visible && !desplazandoRapido) {
            cargadorCaratulas.cargar(uri, vista)
        }
    }

    /**
     * El botón Confirmar llama esta función cuando ya existe una elección y no se está importando.
     * No recibe argumentos ni devuelve un dato directamente: envía nombre y dirección a la pantalla anterior.
     * Marca el resultado como correcto, detiene la muestra y cierra el selector.
     */
    private fun confirmar() {
        if (!tieneEleccion || importando || validando || !aceptaResultado()) {
            return
        }
        val uri = uriElegida
        if (uri == null) {
            devolverEleccion()
            return
        }
        validando = true
        actualizarControles()
        val cancelacion = CancellationSignal()
        cancelacionValidacion = cancelacion
        ejecutor.execute {
            val accesible = runCatching {
                contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r", cancelacion)?.use { }
                    ?: error("El audio ya no está disponible.")
            }.isSuccess
            hiloPrincipal.post {
                if (!aceptaResultado() || cancelacionValidacion !== cancelacion || cancelacion.isCanceled) return@post
                cancelacionValidacion = null
                validando = false
                if (accesible && uriElegida == uri) {
                    devolverEleccion()
                } else {
                    invalidarEleccion(uri)
                    canciones = canciones.filterNot { it.uri.toString() == uri }
                    versionAudios += 1
                    actualizarPantalla()
                }
            }
        }
    }

    /** Devuelve la elección ya comprobada al formulario y detiene la muestra antes de cerrar. */
    private fun devolverEleccion() {
        preescucha.detener()
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_URI, uriElegida)
            putExtra(EXTRA_NOMBRE, if (uriElegida == null) getString(R.string.musica_sin) else nombreElegido)
        })
        finish()
    }

    /**
     * El botón de regreso y volver llaman esta función para abandonar el selector.
     * No recibe argumentos ni devuelve un dato directamente: comunica cancelación a la pantalla anterior.
     * Detiene la muestra y cierra sin enviar la elección que se estaba probando.
     */
    private fun cancelar() {
        preescucha.detener()
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * onCreate la llama para conectar el gesto Atrás desde Android 13.
     * No recibe argumentos ni devuelve un dato: registra la función que Android llamará al volver.
     * Esa función pasa por volver para subir de carpeta antes de cerrar el selector.
     */
    private fun prepararRetroceso() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                volver()
            }
        }
    }

    /**
     * Android anterior a 13 la llama cuando la persona pulsa Atrás.
     * No recibe argumentos ni devuelve un dato: usa volver para conservar la misma navegación.
     */
    @Deprecated("Android 13 o posterior utiliza prepararRetroceso.")
    override fun onBackPressed() {
        volver()
    }

    /**
     * Las dos formas de Atrás de Android llaman esta función.
     * No recibe argumentos ni devuelve un dato: sube una carpeta si hay una abierta,
     * o cancela la selección cuando se está en la raíz o en la pestaña de canciones.
     */
    private fun volver() {
        if (enCarpetas && carpetaActual.isNotBlank()) {
            subirCarpeta()
        } else {
            cancelar()
        }
    }

    /**
     * La importación y el aviso de error de PreescuchaMusica la llaman para explicar un problema.
     * Recibe el identificador del mensaje y no devuelve un dato: lo muestra brevemente.
     * Comprueba que la pantalla siga abierta antes de mostrarlo.
     */
    private fun mostrarAviso(mensaje: Int) {
        if (!destruida) {
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Android la llama cuando esta pantalla vuelve a estar activa.
     * No recibe argumentos ni devuelve un dato: revisa si hay acceso a la biblioteca.
     * Carga canciones pendientes o retira las que ya no pueden leerse tras un cambio en Ajustes.
     */
    override fun onResume() {
        super.onResume()
        if (!proteccion.comprobar() || !::adaptador.isInitialized) {
            return
        }
        visible = true
        desplazandoRapido = false
        if (tienePermisoBiblioteca()) {
            iniciarObservacion()
            consultarBiblioteca()
        } else {
            detenerObservacion()
            if (bibliotecaCargada || canciones.isNotEmpty()) {
                canciones = emptyList()
                bibliotecaCargada = false
                versionAudios += 1
            }
            mostrarPermiso(R.string.musica_permiso_denegado)
            restaurarAudioAnterior()
        }
        actualizarPantalla()
        lista.post { if (aceptaResultado()) adaptador.actualizarCaratulas(lista) }
    }

    /**
     * Android la llama antes de recrear esta pantalla, por ejemplo al girar el teléfono.
     * Recibe el contenedor temporal y no devuelve un dato: guarda elección, búsqueda y navegación.
     * restaurarEleccion los recupera al abrir de nuevo; la muestra de audio vuelve detenida.
     */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)

        if (!::busqueda.isInitialized) {
            super.onSaveInstanceState(estado)
            return
        }

        estado.putParcelable("musica_estado_lista", lista.onSaveInstanceState())
        estado.putString(EXTRA_URI, uriElegida)
        estado.putString(EXTRA_NOMBRE, nombreElegido)
        estado.putBoolean("musica_tiene_eleccion", tieneEleccion)
        estado.putBoolean("musica_en_carpetas", enCarpetas)
        estado.putString("musica_carpeta_actual", carpetaActual)
        estado.putString("musica_busqueda_guardada", busqueda.text.toString())
        estado.putBoolean("musica_permiso_solicitado", permisoSolicitado)
        estado.putBoolean("musica_permiso_denegado", permisoDenegado)
        estado.putParcelableArrayList("musica_documentos", ArrayList(importadas.values.map { audio ->
            Bundle().apply {
                putString("uri", audio.uri.toString())
                putString("nombre", audio.nombre)
                putString("artista", audio.artista)
                putLong("duracion", audio.duracionMilisegundos)
                putString("carpeta", audio.carpeta)
                putString("archivo", audio.nombreArchivo)
            }
        }))
        estado.putStringArrayList("musica_importadas_uri", ArrayList(importadas.keys))
        estado.putStringArrayList(
            "musica_importadas_nombre",
            ArrayList(importadas.values.map { it.nombre }),
        )
        super.onSaveInstanceState(estado)
    }

    /**
     * Android la llama cuando la pantalla deja de verse, por ejemplo al abrir otra aplicación.
     * No recibe argumentos ni devuelve un dato: detiene la muestra para que no siga sonando fuera.
     */
    override fun onStop() {
        visible = false
        versionBusqueda += 1
        trabajoFiltro?.cancel(true)
        hiloPrincipal.removeCallbacks(buscarTrasEscribir)
        cancelarConsultaBiblioteca()
        detenerObservacion()
        if (::cargadorCaratulas.isInitialized) cargadorCaratulas.cancelarPendientes()
        if (::preescucha.isInitialized) preescucha.detener()
        super.onStop()
    }

    /**
     * Android la llama cuando destruye esta pantalla.
     * No recibe argumentos ni devuelve un dato: cancela lecturas, cierra la preescucha y vacía imágenes.
     * Marca la pantalla como destruida para ignorar trabajos que terminen después de su cierre.
     */
    override fun onDestroy() {
        destruida = true
        cancelarConsultaBiblioteca()
        detenerObservacion()
        cancelacionDocumento?.cancel()
        cancelacionValidacion?.cancel()
        trabajoFiltro?.cancel(true)
        if (::preescucha.isInitialized) preescucha.cerrar()
        if (::cargadorCaratulas.isInitialized) cargadorCaratulas.cerrar()
        ejecutor.shutdownNow()
        ejecutorFiltro.shutdownNow()
        hiloPrincipal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Nombres de los datos que comparten ambas pantallas y números que identifican respuestas de Android.
    companion object {
        const val EXTRA_TAREA_TITULO = "tarea_titulo"
        const val EXTRA_URI = "musica_uri"
        const val EXTRA_NOMBRE = "musica_nombre"
        private const val SOLICITUD_PERMISO = 730
        private const val SOLICITUD_DOCUMENTO = 731
    }
}
