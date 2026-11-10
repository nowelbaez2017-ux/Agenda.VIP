package com.nowel.agendavip.verificacion

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HeaderViewListAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.musica.BibliotecaMusica
import com.nowel.agendavip.datos.musica.CancionLocal
import com.nowel.agendavip.datos.musica.IndiceBibliotecaMusica
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.presentacion.musica.ActividadSelectorMusica
import com.nowel.agendavip.presentacion.musica.AdaptadorMusica
import com.nowel.agendavip.presentacion.musica.FilaMusica
import com.nowel.agendavip.presentacion.musica.PreescuchaMusica
import com.nowel.agendavip.presentacion.tareas.ActividadEditarTarea
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Revisa el índice y el selector musical mediante entradas públicas de la aplicación.
 * VerificacionAgenda la crea con solo=musica y entrega Android, el informe y sus cuentas QA.
 * Este código no entra en el APK normal. Sus WAV contienen silencio y se borran por URI exacta;
 * nunca se borra una carpeta completa ni se modifica el volumen general del dispositivo.
 */
class ComprobacionMusica(
    private val instrumentacion: Instrumentation,
    private val contexto: Context,
    private val probar: (String, () -> Unit) -> Boolean,
    private val cuentasCreadas: MutableList<CuentaLocal>,
) {
    private val direccionesCreadas = linkedSetOf<Uri>()
    private val cabeceras = mutableMapOf<Activity, View>()
    private val pantallasAbiertas = mutableListOf<Activity>()
    private val sufijo = UUID.randomUUID().toString().take(8)
    private val carpetaQa = "Music/AgendaVIP_QA_$sufijo"

    /**
     * El runner la llama para comprobar primero el índice y después el recorrido real.
     * No recibe argumentos ni devuelve datos. El runner informa y restaura la sesión anterior.
     * La limpieza ocurre también cuando falla una preparación o una pantalla.
     */
    fun ejecutar() {
        verificarBusquedaDelIndice()
        verificarCarpetasDelIndice()
        verificarBibliotecasGrandes()

        try {
            verificarSelectorReal()
        } finally {
            probar("Música: se cierran las pantallas QA y se borran únicamente sus audios") {
                enPantalla {
                    pantallasAbiertas.asReversed().forEach { pantalla ->
                        if (!pantalla.isDestroyed && !pantalla.isFinishing) {
                            pantalla.finish()
                        }
                    }
                }
                instrumentacion.waitForIdleSync()

                var primerError: Throwable? = null
                direccionesCreadas.forEach { uri ->
                    try {
                        contexto.contentResolver.delete(uri, null, null)
                    } catch (error: Throwable) {
                        primerError = primerError ?: error
                    }
                }
                primerError?.let { throw it }
                direccionesCreadas.forEach { uri ->
                    contexto.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                        ?.use { cursor ->
                            check(!cursor.moveToFirst()) { "No se eliminó el audio QA $uri." }
                        }
                }
            }
        }
    }

    /**
     * ejecutar la llama para revisar tildes, archivo real y palabras en cualquier orden.
     * Usa canciones inventadas en memoria; no modifica la biblioteca del teléfono.
     */
    private fun verificarBusquedaDelIndice() {
        probar("Música: la búsqueda combina título, artista y archivo sin depender de tildes ni orden") {
            val cafe = cancionInventada(1, "Árbol de café", "Lúz del Sur", "Music/Álbum", "Recuerdos_final_2026.wav")
            val noche = cancionInventada(2, "Noche azul", "Otra voz", "Music/Álbum", "Lluvia_original.wav")
            val indice = IndiceBibliotecaMusica(listOf(noche, cafe))

            listOf("SUR café LUZ", "final recuerdos", "2026 cafe sur").forEach { consulta ->
                check(indice.buscar(consulta, false).canciones == listOf(cafe)) {
                    "No se encontró el audio por la combinación '$consulta'."
                }
            }
            check(indice.buscar("lluvia otra", false).canciones == listOf(noche))
            check(indice.buscar("cafe lluvia", false).canciones.isEmpty())
            check(indice.buscar("   \t  ", false).canciones == listOf(cafe, noche))
            check(indice.cantidad == 2)
            check(indice.obtener(cafe.uri.toString()) == cafe)
            check(indice.obtener("content://qa_musica/inexistente") == null)
        }
    }

    /**
     * ejecutar la llama para comprobar niveles, recuentos y separación de una SD simulada.
     * Las rutas son datos del índice; no necesita insertar archivos en una tarjeta real.
     */
    private fun verificarCarpetasDelIndice() {
        probar("Música: las carpetas respetan niveles, descendientes y una SD separada") {
            val piano = cancionInventada(10, "Piano", "Luz", "Music/Clásica/Piano", "concierto.wav")
            val violin = cancionInventada(11, "Violín", "Sur", "Music/Clásica/Violín", "recital.wav")
            val directa = cancionInventada(12, "Directa", "Luz", "Music", "directa.wav")
            val sd = cancionInventada(13, "Piano SD", "Luz", "Tarjeta SD (ABCD-1234)/Music/Clásica/Piano", "concierto_sd.wav")
            val indice = IndiceBibliotecaMusica(listOf(sd, violin, directa, piano))
            val raiz = indice.buscar("", true)

            check(raiz.carpeta == "")
            check(raiz.canciones.isEmpty())
            check(raiz.carpetas.associate { it.ruta to it.cantidad } == mapOf(
                "Music" to 3,
                "Tarjeta SD (ABCD-1234)" to 1,
            ))
            val musica = indice.buscar("", true, "Music")
            check(musica.canciones == listOf(directa))
            check(musica.carpetas.single().ruta == "Music/Clásica")
            check(musica.carpetas.single().nombre == "Clásica")
            check(musica.carpetas.single().cantidad == 2)
            check(indice.contieneCarpeta("Music/Clásica/Piano"))
            check(!indice.contieneCarpeta("Music/Clásica/Piano/Inexistente"))
            check(indice.buscar("concierto luz", true, "Music").canciones == listOf(piano))
            check(indice.buscar("sd concierto", true).canciones == listOf(sd))
            check(indice.buscar("", true, "Music/Clásica/Piano").canciones == listOf(piano))
            check(indice.buscar("", true, "Tarjeta SD (ABCD-1234)/Music/Clásica/Piano").canciones == listOf(sd))
        }
    }

    /**
     * ejecutar la llama para contrastar 10 000 y 20 000 audios contra un filtro independiente.
     * Comprueba resultados, orden y listas guardadas en memoria; no impone un tiempo mínimo.
     * Estos datos nunca se añaden a MediaStore, el catálogo de archivos de Android.
     */
    private fun verificarBibliotecasGrandes() {
        listOf(10_000, 20_000).forEach { cantidad ->
            probar("Música: $cantidad audios conservan resultados ordenados y reutilizan listas sin búsqueda") {
                val audios = (0 until cantidad).map { numero ->
                    val codigo = numero.toString().padStart(5, '0')
                    cancionInventada(
                        numero.toLong() + 100,
                        "Pista $codigo",
                        if (numero % 2 == 0) "Lúz del Sur" else "Otra voz",
                        "Music/Colección ${numero % 100}/Álbum ${numero % 10}",
                        "archivo_${codigo}_edición.wav",
                    )
                }.shuffled(Random(601))
                val indice = IndiceBibliotecaMusica(audios)
                val ordenadas = audios.sortedBy { it.nombre }
                val primera = indice.buscar("", false).canciones

                check(indice.cantidad == cantidad)
                check(primera == ordenadas)
                repeat(20) {
                    check(indice.buscar("", false).canciones === primera) {
                        "La búsqueda vacía volvió a crear la lista de $cantidad audios."
                    }
                }
                val carpeta = "Music/Colección 0"
                val nivel = indice.buscar("", true, carpeta)
                repeat(20) {
                    val repetida = indice.buscar("", true, carpeta)
                    check(repetida.canciones === nivel.canciones)
                    check(repetida.carpetas === nivel.carpetas)
                }

                listOf("sur pista 001", "edicion 00010", "voz 00011", "coleccion 25", "inexistente").forEach { consulta ->
                    val palabras = normalizar(consulta).split(Regex("\\s+"))
                    val esperado = ordenadas.filter { audio ->
                        val texto = normalizar("${audio.nombre} ${audio.artista} ${audio.nombreArchivo} ${audio.carpeta}")
                        palabras.all { palabra -> texto.contains(palabra) }
                    }
                    repeat(3) {
                        check(indice.buscar(consulta, false).canciones == esperado) {
                            "La búsqueda '$consulta' cambió resultados u orden con $cantidad audios."
                        }
                    }
                }
            }
        }
    }

    /**
     * ejecutar la llama para preparar una cuenta exclusiva y recorrer los botones del selector.
     * Exige permiso de biblioteca ya concedido y Android 10 o posterior para aislar los WAV QA.
     * Las cuentas quedan a cargo de la restauración del runner; no guarda tareas ni programa avisos.
     */
    private fun verificarSelectorReal() {
        lateinit var primera: CancionLocal
        lateinit var segunda: CancionLocal
        lateinit var cuenta: CuentaLocal

        if (!probar("Música: se prepara una cuenta QA y dos WAV propios sin cambiar permisos ni volumen") {
                check(Build.VERSION.SDK_INT >= 29) { "La prueba con MediaStore requiere Android 10 o posterior." }
                val permiso = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                check(contexto.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED) {
                    "Concede el permiso de biblioteca antes de ejecutar solo=musica."
                }
                cuenta = RepositorioCuentas(contexto).crear(
                    "Selector musical QA",
                    "qa_musica_$sufijo",
                    "Clave musical QA $sufijo 2026".toCharArray(),
                )
                cuentasCreadas.add(cuenta)
                CoordinadorSesion(contexto).entrar(cuenta)
                primera = insertarAudio("Árbol de café $sufijo", "Lúz del Sur", "recuerdos_arbol_cafe_luz_sur_$sufijo.wav", "$carpetaQa/Álbum/Piano")
                segunda = insertarAudio("Noche azul $sufijo", "Otra voz", "lluvia_noche_azul_otra_voz_$sufijo.wav", "$carpetaQa/Álbum/Violín")
            }) {
            return
        }

        val editor = instrumentacion.startActivitySync(
            Intent(contexto, ActividadEditarTarea::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id),
        )
        pantallasAbiertas += editor
        enPantalla {
            editor.findViewById<EditText>(R.id.tarea_titulo_input).setText("Borrador musical QA $sufijo")
        }

        try {
            verificarSeleccionRechazada(editor, primera)
            probar("Música: buscar, seleccionar y preescuchar otra canción conserva la elección al confirmar") {
                conSelector(editor) { selector ->
                    buscar(selector, "sur cafe $sufijo")
                    esperarCanciones(selector, listOf(primera.uri))
                    pulsarFila(selector) { fila -> fila is FilaMusica.Cancion && fila.audio.uri == primera.uri }
                    comprobarEleccion(selector, primera.uri)
                    buscar(selector, "lluvia $sufijo")
                    esperarCanciones(selector, listOf(segunda.uri))
                    val fila = mostrarFila(selector) { dato -> dato is FilaMusica.Cancion && dato.audio.uri == segunda.uri }
                    enPantalla {
                        check(checkNotNull(buscarBotonMuestra(fila)).performClick())
                    }
                    esperar("La preescucha no llegó a reproducirse.") {
                        enPantalla {
                            control<TextView>(selector, R.id.musica_estado_muestra).text.toString().startsWith("Preescucha")
                        }
                    }
                    comprobarEleccion(selector, primera.uri)
                    pulsar(selector, R.id.musica_confirmar)
                    esperarCierre(selector)
                    esperarTexto(editor, R.id.tarea_musica_button, primera.nombre)
                }
            }

            probar("Música: navegar por carpetas respeta niveles y permite subir a la carpeta anterior") {
                conSelector(editor) { selector ->
                    pulsar(selector, R.id.musica_tab_carpetas)
                    entrarCarpeta(selector, "Music")
                    entrarCarpeta(selector, carpetaQa)
                    entrarCarpeta(selector, "$carpetaQa/Álbum")
                    enPantalla {
                        val carpetas = adaptador(selector).filas.filterIsInstance<FilaMusica.Carpeta>()
                        check(carpetas.map { it.nombre }.toSet() == setOf("Piano", "Violín"))
                        check(carpetas.all { it.cantidad == 1 })
                    }
                    entrarCarpeta(selector, primera.carpeta)
                    esperarCanciones(selector, listOf(primera.uri))
                    pulsar(selector, R.id.musica_subir)
                    esperarRuta(selector, "Álbum")
                    pulsar(selector, R.id.musica_regresar)
                    esperarCierre(selector)
                    esperarTexto(editor, R.id.tarea_musica_button, primera.nombre)
                }
            }

            probar("Música: Atrás cancela la elección pendiente y conserva la canción previa del formulario") {
                conSelector(editor) { selector ->
                    buscar(selector, "lluvia $sufijo")
                    esperarCanciones(selector, listOf(segunda.uri))
                    pulsarFila(selector) { fila -> fila is FilaMusica.Cancion && fila.audio.uri == segunda.uri }
                    comprobarEleccion(selector, segunda.uri)
                    instrumentacion.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    esperarCierre(selector)
                    esperarTexto(editor, R.id.tarea_musica_button, primera.nombre)
                }
            }

            probar("Música: confirmar Sin música elimina la canción del borrador y la conserva al reabrir") {
                conSelector(editor) { selector ->
                    pulsarFila(selector) { fila -> fila == FilaMusica.SinMusica }
                    comprobarEleccion(selector, null)
                    pulsar(selector, R.id.musica_confirmar)
                    esperarCierre(selector)
                    esperarTexto(editor, R.id.tarea_musica_button, "Elegir canción · Sin música")
                }
                conSelector(editor) { selector ->
                    comprobarEleccion(selector, null)
                }
            }

            probar("Música: altas y bajas de MediaStore actualizan la pantalla y rechazan el audio eliminado") {
                conSelector(editor) { selector ->
                    buscar(selector, "nueva_$sufijo")
                    esperarCanciones(selector, emptyList())
                    val nueva = insertarAudio("Nueva QA $sufijo", "QA", "nueva_$sufijo.wav", "$carpetaQa/Nuevas")
                    esperarCanciones(selector, listOf(nueva.uri))
                    pulsarFila(selector) { fila -> fila is FilaMusica.Cancion && fila.audio.uri == nueva.uri }
                    comprobarEleccion(selector, nueva.uri)
                    check(direccionesCreadas.contains(nueva.uri))
                    check(contexto.contentResolver.delete(nueva.uri, null, null) == 1)
                    // Ya se eliminó: la limpieza final no debe volver a pedir acceso a esa URI inexistente.
                    direccionesCreadas.remove(nueva.uri)
                    // Confirma enseguida para comprobar también la lectura anterior al aviso del catálogo.
                    enPantalla {
                        control<View>(selector, R.id.musica_confirmar).performClick()
                    }
                    esperarCanciones(selector, emptyList())
                    esperar("La canción eliminada siguió siendo una elección confirmable.") {
                        enPantalla {
                            !adaptador(selector).tieneEleccion &&
                                adaptador(selector).uriElegida == null &&
                                !control<View>(selector, R.id.musica_confirmar).isEnabled
                        }
                    }
                    enPantalla {
                        check(!selector.isFinishing && !selector.isDestroyed) {
                            "La pantalla aceptó una dirección de audio que ya no existe."
                        }
                    }
                    pulsar(selector, R.id.musica_regresar)
                    esperarCierre(selector)
                    esperarTexto(editor, R.id.tarea_musica_button, "Elegir canción · Sin música")
                }
            }

            probar("Música: el selector conserva el borrador y no guarda tareas por sí solo") {
                enPantalla {
                    check(editor.findViewById<EditText>(R.id.tarea_titulo_input).text.toString() == "Borrador musical QA $sufijo")
                }
                check(RepositorioTareas(contexto).listar().isEmpty())
            }
        } finally {
            enPantalla {
                editor.finish()
            }
            instrumentacion.waitForIdleSync()
        }

        verificarGiro(primera, cuenta)
    }

    /**
     * verificarSelectorReal la llama con el editor y un audio QA para revisar una elección rechazada.
     * El adaptador recibe una función sin acción, que representa al selector mientras valida un audio.
     * Comprueba los círculos reales y su restauración al reutilizar filas, sin leer etiquetas privadas.
     * No reproduce sonido y cierra la preescucha creada para esta única comprobación.
     */
    private fun verificarSeleccionRechazada(editor: Activity, audio: CancionLocal) {
        probar("Música: rechazar una selección conserva los círculos y reutilizar filas repara su estado") {
            enPantalla {
                val muestra = PreescuchaMusica(
                    editor,
                    alCambiar = {},
                    alFallar = {},
                )

                try {
                    val datos = AdaptadorMusica(
                        contexto = editor,
                        alElegir = {},
                        alEscuchar = {},
                        cargarCaratula = { _, _ -> },
                        preescucha = muestra,
                    )
                    datos.filas = listOf(FilaMusica.SinMusica, FilaMusica.Cancion(audio))
                    datos.tieneEleccion = true
                    datos.uriElegida = null
                    val lista = ListView(editor)
                    val filaSinMusica = datos.getView(0, null, lista)
                    val filaCancion = datos.getView(1, null, lista)
                    val silencio = checkNotNull(buscarCirculoSeleccion(filaSinMusica))
                    val cancion = checkNotNull(buscarCirculoSeleccion(filaCancion))

                    check(silencio.isChecked && !cancion.isChecked) {
                        "El adaptador no dibujó la elección inicial de Sin música."
                    }
                    check(cancion.performClick()) {
                        "El círculo de la canción no recibió la pulsación de prueba."
                    }
                    check(!cancion.isChecked) {
                        "La canción quedó marcada visualmente aunque el controlador rechazó elegirla."
                    }
                    check(silencio.isChecked && datos.tieneEleccion && datos.uriElegida == null) {
                        "La selección rechazada cambió la elección anterior de Sin música."
                    }

                    // Una vista puede recibir un cambio automático de Android antes de su callback.
                    // Volver a dibujar debe repararlo aunque el adaptador recuerde la misma elección.
                    cancion.isChecked = true
                    datos.getView(1, filaCancion, lista)
                    check(!cancion.isChecked) {
                        "Reutilizar la fila no quitó una marca ajena a la elección guardada."
                    }
                    silencio.isChecked = false
                    datos.getView(0, filaSinMusica, lista)
                    check(silencio.isChecked) {
                        "Reutilizar Sin música no restauró su círculo seleccionado."
                    }
                    check(muestra.uriActual == null && !muestra.preparando && !muestra.reproduciendo) {
                        "La comprobación visual inició una muestra de audio sin solicitarlo."
                    }
                } finally {
                    muestra.cerrar()
                }
            }
        }
    }

    /**
     * verificarSeleccionRechazada la llama con una fila pública; devuelve su círculo de selección.
     * Recorre controles por tipo y no consulta datos privados del adaptador ni reproduce audio.
     */
    private fun buscarCirculoSeleccion(vista: View): RadioButton? {
        if (vista is RadioButton) {
            return vista
        }
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                val circulo = buscarCirculoSeleccion(vista.getChildAt(indice))
                if (circulo != null) {
                    return circulo
                }
            }
        }
        return null
    }
    /**
     * verificarSelectorReal la llama después de cerrar el formulario receptor, con su audio QA.
     * Solicita un giro real y verifica búsqueda, carpeta, pestaña y elección guardadas.
     * La muestra debe volver detenida. Termina la pantalla y libera su orientación solicitada.
     */
    private fun verificarGiro(audio: CancionLocal, cuenta: CuentaLocal) {
        probar("Música: girar conserva búsqueda, carpeta y selección, y detiene la muestra") {
            var selector = instrumentacion.startActivitySync(
                Intent(contexto, ActividadSelectorMusica::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
                    .putExtra(ActividadSelectorMusica.EXTRA_URI, audio.uri.toString())
                    .putExtra(ActividadSelectorMusica.EXTRA_NOMBRE, audio.nombre),
            )
            registrarSelector(selector)

            try {
                esperarAudio(selector, audio.uri)
                pulsar(selector, R.id.musica_tab_carpetas)
                entrarCarpeta(selector, "Music")
                entrarCarpeta(selector, carpetaQa)
                entrarCarpeta(selector, "$carpetaQa/Álbum")
                entrarCarpeta(selector, audio.carpeta)
                buscar(selector, "sur cafe")
                esperarCanciones(selector, listOf(audio.uri))
                val fila = mostrarFila(selector) { dato -> dato is FilaMusica.Cancion && dato.audio.uri == audio.uri }
                enPantalla {
                    check(checkNotNull(buscarBotonMuestra(fila)).performClick())
                }
                esperar("La muestra anterior al giro no llegó a reproducirse.") {
                    enPantalla {
                        control<TextView>(selector, R.id.musica_estado_muestra).text.toString().startsWith("Preescucha")
                    }
                }
                val anterior = selector
                val orientacionActual = enPantalla { anterior.resources.configuration.orientation }
                val solicitada = if (orientacionActual == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                val monitor = instrumentacion.addMonitor(ActividadSelectorMusica::class.java.name, null, false)

                try {
                    enPantalla {
                        anterior.requestedOrientation = solicitada
                    }
                    selector = checkNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 10_000L)) {
                        "Android no recreó el selector al solicitar el giro."
                    }
                    registrarSelector(selector)
                } finally {
                    instrumentacion.removeMonitor(monitor)
                }

                esperarCanciones(selector, listOf(audio.uri))
                comprobarEleccion(selector, audio.uri)
                enPantalla {
                    check(selector !== anterior) {
                        "El giro devolvió la misma instancia del selector y no probó la restauración."
                    }
                    val orientacionRestaurada = selector.resources.configuration.orientation
                    check(orientacionRestaurada != orientacionActual) {
                        "No cambió la orientación: anterior=$orientacionActual, actual=$orientacionRestaurada, solicitada=$solicitada."
                    }
                    val consultaRestaurada = control<EditText>(selector, R.id.musica_busqueda).text.toString()
                    check(consultaRestaurada == "sur cafe") {
                        "El giro cambió la búsqueda: se esperaba 'sur cafe' y se encontró '$consultaRestaurada'."
                    }
                    check(control<View>(selector, R.id.musica_tab_carpetas).isSelected) {
                        "El giro no conservó seleccionada la pestaña Carpetas."
                    }
                    val rutaRestaurada = control<TextView>(selector, R.id.musica_ruta).text.toString()
                    check(rutaRestaurada.endsWith("Piano")) {
                        "El giro perdió la carpeta '${audio.carpeta}': la ruta visible quedó en '$rutaRestaurada'."
                    }
                    val muestraVisible = control<View>(selector, R.id.musica_pausa_muestra).visibility
                    check(muestraVisible == View.GONE) {
                        "El giro conservó el control de muestra visible: visibility=$muestraVisible, se esperaba GONE=${View.GONE}."
                    }
                }
            } finally {
                enPantalla {
                    selector.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    selector.finish()
                }
                instrumentacion.waitForIdleSync()
            }
        }
    }

    /**
     * Los casos la llaman con el editor y la operación que debe recorrer su selector real.
     * No devuelve datos. Toca Música y después Seleccionar música en la ventana de opciones.
     * Cierra el selector también si una comprobación falla.
     */
    private fun conSelector(editor: Activity, operacion: (Activity) -> Unit) {
        val monitor = instrumentacion.addMonitor(ActividadSelectorMusica::class.java.name, null, false)
        var selector: Activity? = null

        try {
            enPantalla {
                check(editor.findViewById<View>(R.id.tarea_musica_button).performClick())
            }

            // La ventana inicial decide entre silencio y biblioteca. WindowInspector permite
            // encontrar las ventanas de esta misma app; button1 es el botón positivo de Android.
            esperar("No apareció Seleccionar música en la ventana de opciones.") {
                enPantalla {
                    val boton = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .mapNotNull { ventana -> ventana.findViewById<TextView>(android.R.id.button1) }
                        .firstOrNull { control ->
                            control.isShown && control.isEnabled &&
                                control.text.toString() == editor.getString(R.string.tarea_opcion_seleccionar_musica)
                        }

                    boton?.performClick() == true
                }
            }

            val abierta = checkNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 10_000L)) {
                "No se abrió el selector desde el formulario."
            }
            selector = abierta
            registrarSelector(abierta)
            operacion(abierta)
        } finally {
            instrumentacion.removeMonitor(monitor)
            selector?.let { pantalla ->
                enPantalla {
                    if (!pantalla.isDestroyed && !pantalla.isFinishing) {
                        pantalla.finish()
                    }
                }
            }
            instrumentacion.waitForIdleSync()
        }
    }

    /**
     * La apertura y el giro la llaman con el selector creado; conserva su cabecera pública.
     * La cabecera puede salir de la pantalla al desplazar la lista, pero conserva sus controles.
     */
    private fun registrarSelector(selector: Activity) {
        pantallasAbiertas += selector
        esperar("El selector no preparó sus controles.") {
            enPantalla {
                selector.findViewById<View>(R.id.musica_controles)?.let { cabecera ->
                    cabeceras[selector] = cabecera
                }
                cabeceras.containsKey(selector)
            }
        }
    }

    /** Recibe pantalla y texto; escribe en el control real y deja que su búsqueda asíncrona responda. */
    private fun buscar(selector: Activity, consulta: String) {
        enPantalla {
            control<EditText>(selector, R.id.musica_busqueda).setText(consulta)
        }
    }

    /** Recibe pantalla y ruta; pulsa la carpeta visible y espera que se muestre su último nivel. */
    private fun entrarCarpeta(selector: Activity, ruta: String) {
        pulsarFila(selector) { fila -> fila is FilaMusica.Carpeta && fila.ruta == ruta }
        esperarRuta(selector, ruta.substringAfterLast('/'))
    }

    /** Recibe pantalla y nombre del nivel; espera que la navegación real termine de mostrarlo. */
    private fun esperarRuta(selector: Activity, ultimoNivel: String) {
        esperar("No se abrió la carpeta $ultimoNivel.") {
            enPantalla {
                control<TextView>(selector, R.id.musica_ruta).text.toString().endsWith(" › $ultimoNivel")
            }
        }
    }

    /** Recibe pantalla y URI; espera que la lectura inicial del catálogo incluya ese audio QA. */
    private fun esperarAudio(selector: Activity, uri: Uri) {
        esperar("La biblioteca no cargó el audio QA $uri.") {
            enPantalla {
                adaptador(selector).filas.filterIsInstance<FilaMusica.Cancion>().any { it.audio.uri == uri }
            }
        }
    }

    /**
     * Recibe pantalla y direcciones esperadas en orden; espera igualdad completa de las canciones.
     * Excluye Sin música y detecta tanto filas sobrantes como respuestas antiguas de otra búsqueda.
     */
    private fun esperarCanciones(selector: Activity, esperadas: List<Uri>) {
        esperar("La lista no mostró exactamente las canciones esperadas: $esperadas.") {
            enPantalla {
                adaptador(selector).filas.filterIsInstance<FilaMusica.Cancion>().map { it.audio.uri } == esperadas
            }
        }
    }

    /** Recibe pantalla y URI opcional; comprueba la elección pública y el texto visible de silencio. */
    private fun comprobarEleccion(selector: Activity, uri: Uri?) {
        enPantalla {
            val datos = adaptador(selector)
            check(datos.tieneEleccion)
            check(datos.uriElegida == uri?.toString())
            if (uri == null) {
                check(control<TextView>(selector, R.id.musica_elegida).text.toString() == "Sin música")
            }
        }
    }

    /**
     * Recibe pantalla y criterio sobre FilaMusica, el dato público de la fila.
     * Lleva su vista real a pantalla y la pulsa sin llamar al controlador privado del selector.
     */
    private fun pulsarFila(selector: Activity, coincide: (FilaMusica) -> Boolean) {
        val fila = mostrarFila(selector, coincide)
        enPantalla {
            check(fila.performClick())
        }
    }

    /**
     * pulsarFila y la preescucha la llaman con el criterio de una fila del adaptador público.
     * Devuelve su View real una vez visible, respetando cabecera y posiciones de ListView.
     */
    private fun mostrarFila(selector: Activity, coincide: (FilaMusica) -> Boolean): View {
        var encontrada: View? = null
        esperar("No apareció la fila musical solicitada.") {
            // El adaptador puede tener datos nuevos antes de que Android dibuje sus filas.
            instrumentacion.waitForIdleSync()
            enPantalla {
                val lista = control<ListView>(selector, R.id.musica_lista)
                val posicion = (0 until lista.adapter.count).firstOrNull { indice ->
                    (lista.adapter.getItem(indice) as? FilaMusica)?.let(coincide) == true
                }
                if (posicion != null) {
                    val desplazamiento = posicion - lista.firstVisiblePosition
                    encontrada = if (desplazamiento in 0 until lista.childCount) {
                        lista.getChildAt(desplazamiento)
                    } else {
                        lista.setSelection(posicion)
                        null
                    }
                }
                encontrada != null
            }
        }
        return checkNotNull(encontrada)
    }

    /** Recibe pantalla y botón real; lo pulsa desde el trabajo principal de Android. */
    private fun pulsar(selector: Activity, identificador: Int) {
        enPantalla {
            val boton = control<View>(selector, identificador)
            check(boton.isEnabled) { "El botón $identificador está deshabilitado." }
            check(boton.performClick())
        }
    }

    /** Recibe la pantalla cerrada por Confirmar o Atrás; espera su finalización real. */
    private fun esperarCierre(selector: Activity) {
        esperar("El selector no se cerró después de la acción.") {
            enPantalla { selector.isFinishing || selector.isDestroyed }
        }
    }

    /** Recibe pantalla, identificador y texto completo; espera el resultado en el formulario real. */
    private fun esperarTexto(pantalla: Activity, identificador: Int, esperado: String) {
        esperar("El formulario no recibió '$esperado'.") {
            enPantalla {
                pantalla.findViewById<TextView>(identificador).text.toString() == esperado
            }
        }
    }

    /** Recibe el selector; devuelve su adaptador público sin la envoltura de cabecera y pie. */
    private fun adaptador(selector: Activity): AdaptadorMusica {
        val datos = control<ListView>(selector, R.id.musica_lista).adapter
        return if (datos is HeaderViewListAdapter) {
            datos.wrappedAdapter as AdaptadorMusica
        } else {
            datos as AdaptadorMusica
        }
    }

    /** Recibe pantalla e identificador; busca un control fijo o de su cabecera desplazable. */
    private fun <T : View> control(selector: Activity, identificador: Int): T {
        return selector.findViewById<T>(identificador)
            ?: checkNotNull(cabeceras[selector]).findViewById<T>(identificador)
            ?: error("No existe el control musical $identificador.")
    }

    /** Recibe una fila real de canción; devuelve su botón de muestra sin leer su etiqueta privada. */
    private fun buscarBotonMuestra(vista: View): ImageButton? {
        if (vista is ImageButton && vista.isClickable && vista.visibility == View.VISIBLE) {
            return vista
        }
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                val boton = buscarBotonMuestra(vista.getChildAt(indice))
                if (boton != null) {
                    return boton
                }
            }
        }
        return null
    }

    /**
     * Las comprobaciones de vistas la llaman con una lectura o pulsación de Android.
     * Devuelve el resultado y traslada errores al hilo de prueba para informar sin cerrar la app.
     */
    private fun <T> enPantalla(operacion: () -> T): T {
        var resultado: Result<T>? = null
        instrumentacion.runOnMainSync {
            resultado = runCatching(operacion)
        }
        return checkNotNull(resultado).getOrThrow()
    }

    /** Recibe mensaje y condición; espera hasta diez segundos por consultas, dibujo o reproductor. */
    private fun esperar(mensaje: String, condicion: () -> Boolean) {
        val limite = SystemClock.elapsedRealtime() + 10_000L
        while (!condicion()) {
            check(SystemClock.elapsedRealtime() < limite) { mensaje }
            SystemClock.sleep(50L)
        }
    }

    /**
     * La preparación y la actualización la llaman para insertar un WAV silencioso propio.
     * Recibe título, artista, archivo y carpeta; devuelve lo leído por BibliotecaMusica.
     * Registra la URI desde la inserción para limpiarla incluso si falla después su escritura.
     */
    private fun insertarAudio(titulo: String, artista: String, archivo: String, carpeta: String): CancionLocal {
        check(Build.VERSION.SDK_INT >= 29)
        val valores = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, archivo)
            put(MediaStore.Audio.Media.TITLE, titulo)
            put(MediaStore.Audio.Media.ARTIST, artista)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$carpeta/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(contexto.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            valores,
        ))
        direccionesCreadas += uri
        contexto.contentResolver.openOutputStream(uri, "w").use { salida ->
            checkNotNull(salida).write(wavSilencioso(titulo, artista))
        }
        contexto.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)

        var audio: CancionLocal? = null
        esperar("MediaStore no terminó de reconocer el WAV QA $archivo.") {
            audio = BibliotecaMusica(contexto.contentResolver).leerCanciones(CancellationSignal())
                .firstOrNull { it.uri == uri && it.nombreArchivo == archivo && it.duracionMilisegundos > 0 }
            audio != null
        }
        return checkNotNull(audio)
    }

    /**
     * insertarAudio la llama para obtener ocho segundos de PCM, muestras de sonido sin compresión.
     * Recibe título y artista; devuelve un WAV con sus datos y muestras de valor cero, silencio.
     * LIST/INFO guarda los nombres dentro del archivo para que Android pueda reconocerlos.
     * Comprueba preparación y reproducción sin cambiar el volumen ni emitir un tono de prueba.
     */
    private fun wavSilencioso(titulo: String, artista: String): ByteArray {
        val frecuencia = 8_000
        val longitud = frecuencia * 8 * 2
        val informacion = fragmentoWav(
            "LIST",
            "INFO".toByteArray(Charsets.US_ASCII) +
                fragmentoWav("INAM", (titulo + '\u0000').toByteArray(Charsets.UTF_8)) +
                fragmentoWav("IART", (artista + '\u0000').toByteArray(Charsets.UTF_8)),
        )
        return ByteBuffer.allocate(44 + longitud + informacion.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + longitud + informacion.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(frecuencia)
            putInt(frecuencia * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put(informacion)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(longitud)
            put(ByteArray(longitud))
        }.array()
    }

    /**
     * wavSilencioso la llama con un código WAV y sus datos; devuelve el bloque con longitud y relleno.
     * El relleno mantiene cada bloque en un número par de bytes, como requiere el formato WAV.
     */
    private fun fragmentoWav(codigo: String, datos: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + datos.size + datos.size % 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(codigo.toByteArray(Charsets.US_ASCII))
            putInt(datos.size)
            put(datos)
        }.array()
    }
    /** Recibe los campos de una canción inventada; devuelve datos aislados para probar el índice. */
    private fun cancionInventada(id: Long, titulo: String, artista: String, carpeta: String, archivo: String): CancionLocal {
        return CancionLocal(
            uri = Uri.parse("content://qa_musica/audio/$id"),
            nombre = titulo,
            artista = artista,
            duracionMilisegundos = 8_000L,
            carpeta = carpeta,
            nombreArchivo = archivo,
        )
    }

    /** Recibe texto del filtro de referencia; devuelve minúsculas sin tildes para comparar resultados. */
    private fun normalizar(texto: String): String {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
    }
}







