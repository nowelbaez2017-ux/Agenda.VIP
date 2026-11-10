package com.nowel.agendavip.verificacion

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.musica.BibliotecaMusica
import com.nowel.agendavip.datos.musica.CancionLocal
import com.nowel.agendavip.datos.musica.SelectorMusicaAleatoria
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.presentacion.tareas.ActividadEditarTarea
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import com.nowel.agendavip.recordatorios.NotificacionesAviso
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Recorre el guardado y la entrega de los modos musicales nuevos con una cuenta QA propia.
 * VerificacionAgenda entrega Android, su función de informe y las cuentas cuyos avisos detendrá.
 * QA significa datos exclusivos de comprobación. Este archivo solo entra en el APK de pruebas.
 * No cambia permisos, volumen general ni canciones personales, y restaura sus archivos temporales.
 */
class ComprobacionAvisosMusicales(
    private val instrumentacion: Instrumentation,
    private val contexto: Context,
    private val probar: (String, () -> Unit) -> Boolean,
    private val cuentasCreadas: MutableList<CuentaLocal>,
) {
    private val sufijo = UUID.randomUUID().toString().take(8)
    private val pantallas = mutableListOf<Activity>()
    private var audioQa: Uri? = null
    private var cuenta: CuentaLocal? = null

    /**
     * El runner la llama con solo=avisos_musica. No recibe ni devuelve datos.
     * Prepara una cuenta y un WAV silencioso; los casos usan los repositorios y botones reales.
     * La limpieza se ejecuta también ante un fallo. El runner repone la sesión que había al entrar.
     */
    fun ejecutar() {
        try {
            if (!preparar()) {
                return
            }

            val audio = leerAudioPropio()
            verificarPersistencia(audio)
            verificarFormatoAnterior()
            verificarRespaldo()
            verificarMensajes()
            verificarEleccionAleatoria()
            verificarEditorAleatorio()
            verificarSilencioYRecreacion()
            verificarAlarmaReal(audio)
        } finally {
            probar("Avisos musicales: se cierran pantallas y se retira únicamente el WAV QA") {
                enPantalla {
                    pantallas.asReversed().forEach { pantalla ->
                        if (!pantalla.isFinishing && !pantalla.isDestroyed) {
                            pantalla.finish()
                        }
                    }
                }
                instrumentacion.waitForIdleSync()

                if (cuenta != null && EspacioPersonal.claveActual(contexto) == cuenta?.id) {
                    GestorRecordatorios(contexto).cancelarTodo()
                }

                audioQa?.let { direccion ->
                    check(contexto.contentResolver.delete(direccion, null, null) == 1) {
                        "No se retiró el WAV creado para esta ejecución."
                    }
                    audioQa = null
                }
            }
        }
    }

    /**
     * ejecutar la llama para crear su cuenta y un archivo en Music con identidad aleatoria.
     * Devuelve si la preparación terminó. No solicita permisos ni utiliza archivos ya existentes.
     */
    private fun preparar(): Boolean {
        return probar("Avisos musicales: se prepara una cuenta independiente y un WAV silencioso") {
            check(Build.VERSION.SDK_INT >= 29) { "Esta suite requiere Android 10 o posterior." }
            val permiso = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            check(contexto.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED) {
                "Concede acceso al audio antes de ejecutar avisos_musica."
            }
            val nueva = RepositorioCuentas(contexto).crear(
                "Avisos musicales QA",
                "qa_avisos_musica_$sufijo",
                "Clave exclusiva de avisos $sufijo 2026".toCharArray(),
            )
            cuenta = nueva
            cuentasCreadas += nueva
            CoordinadorSesion(contexto).entrar(nueva)

            val valores = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "aviso_qa_$sufijo.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AgendaVIP_Avisos_QA_$sufijo/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(contexto.contentResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                valores,
            ))
            audioQa = uri
            contexto.contentResolver.openOutputStream(uri, "w").use { salida ->
                checkNotNull(salida).write(wavSilencioso())
            }
            contexto.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
    }

    /**
     * ejecutar la llama para esperar a que Android reconozca el WAV recién terminado.
     * Devuelve sus datos públicos mediante BibliotecaMusica; no cambia el catálogo personal.
     */
    private fun leerAudioPropio(): CancionLocal {
        var audio: CancionLocal? = null
        esperar("Android no terminó de reconocer el WAV QA.") {
            audio = BibliotecaMusica(contexto.contentResolver).leerCanciones(CancellationSignal())
                .firstOrNull { it.uri == audioQa && it.duracionMilisegundos > 0L }
            audio != null
        }
        return checkNotNull(audio)
    }

    /**
     * ejecutar entrega el WAV propio y comprueba que guardar y volver a leer no confunda modos.
     * No programa alarmas: escribe tres tareas para mañana mediante el repositorio público.
     */
    private fun verificarPersistencia(audio: CancionLocal) {
        probar("Avisos musicales: guardar y releer conserva aleatoria, silencio y canción elegida") {
            val repositorio = RepositorioTareas(contexto)
            val fecha = LocalDate.now().plusDays(1)
            val tareas = listOf(
                Tarea(id = "qa_aleatoria_$sufijo", titulo = "Aleatoria QA", fecha = fecha, hora = LocalTime.NOON),
                Tarea(id = "qa_silencio_$sufijo", titulo = "Silencio QA", fecha = fecha, hora = LocalTime.NOON,
                    modoMusica = ModoMusica.SIN_MUSICA),
                Tarea(id = "qa_elegida_$sufijo", titulo = "Elegida QA", fecha = fecha, hora = LocalTime.NOON,
                    musicaUri = audio.uri.toString(), musicaNombre = audio.nombre,
                    modoMusica = ModoMusica.SELECCIONADA),
            )
            tareas.forEach(repositorio::guardar)
            tareas.forEach { original ->
                check(RepositorioTareas(contexto).obtener(original.id) == original) {
                    "Cambió el modo o la dirección al releer ${original.modoMusica}."
                }
            }
            check(tareas[0].tieneMusica() && !tareas[1].tieneMusica() && tareas[2].tieneMusica())
        }
    }

    /**
     * ejecutar la llama para reproducir el documento que guardaba una versión sin modoMusica.
     * Retira ese campo únicamente en una copia del archivo QA y repone el original al terminar.
     * Verifica una migración real del lector, evitando convertir silencios antiguos en aleatorios.
     */
    private fun verificarFormatoAnterior() {
        probar("Avisos musicales: una agenda antigua conserva silencio y canción seleccionada") {
            val preferencias = EspacioPersonal(contexto).preferencias("agenda_local")
            val original = checkNotNull(preferencias.getString("documento_v1", null))
            try {
                val anterior = JSONObject(original)
                val tareas = anterior.getJSONArray("tareas")
                for (posicion in 0 until tareas.length()) {
                    tareas.getJSONObject(posicion).remove("modoMusica")
                }
                check(preferencias.edit().putString("documento_v1", anterior.toString()).commit())
                val repositorio = RepositorioTareas(contexto)
                check(repositorio.obtener("qa_aleatoria_$sufijo")?.modoMusica == ModoMusica.SIN_MUSICA)
                check(repositorio.obtener("qa_silencio_$sufijo")?.modoMusica == ModoMusica.SIN_MUSICA)
                check(repositorio.obtener("qa_elegida_$sufijo")?.modoMusica == ModoMusica.SELECCIONADA)
                check(repositorio.obtener("qa_elegida_$sufijo")?.musicaUri == audioQa.toString())
            } finally {
                check(preferencias.edit().putString("documento_v1", original).commit())
            }
        }
    }

    /**
     * ejecutar la llama para recorrer exportar, preparar y restaurar con cifrado real.
     * El archivo se crea en la caché privada QA y se elimina por su ruta exacta en finally.
     * Comprueba los modos sin sustituir datos de otras cuentas ni guardar contraseñas de acceso.
     */
    private fun verificarRespaldo() {
        probar("Avisos musicales: el respaldo cifrado restaura los tres modos musicales") {
            val archivo = File.createTempFile("qa_avisos_$sufijo", ".avip", contexto.cacheDir)
            try {
                val repositorio = RepositorioTareas(contexto)
                val anteriores = repositorio.listar().associateBy { it.id }
                val copia = CopiaSeguridadLocal(contexto)
                val clave = "Copia de avisos $sufijo 2026"
                copia.exportar(Uri.fromFile(archivo), clave.toCharArray())
                val preparada = copia.preparar(Uri.fromFile(archivo), clave.toCharArray())
                check(preparada.tareas == anteriores.size)
                val aleatoria = checkNotNull(anteriores["qa_aleatoria_$sufijo"])
                repositorio.guardar(aleatoria.copy(modoMusica = ModoMusica.SIN_MUSICA))
                copia.restaurar(preparada)
                anteriores.values.forEach { tarea ->
                    val restaurada = checkNotNull(RepositorioTareas(contexto).obtener(tarea.id))
                    check(restaurada.modoMusica == tarea.modoMusica && restaurada.musicaUri == tarea.musicaUri)
                }
            } finally {
                check(archivo.delete() || !archivo.exists())
            }
        }
    }

    /**
     * ejecutar la llama para revisar el texto que comparten notificación y lector de voz.
     * No inicia el motor de voz; comprueba el contenido predeterminado y el personalizado.
     */
    private fun verificarMensajes() {
        probar("Avisos musicales: el seguimiento vacío conserva su frase y un mensaje propio la sustituye") {
            val tarea = Tarea(
                titulo = "hacer la tarea de matemáticas",
                fecha = LocalDate.now(),
                hora = LocalTime.NOON,
                mensajeVoz = "Nowel, comienza tus ejercicios.",
                mensajeSeguimiento = "   ",
                seguimientoActivo = true,
            )
            check(tarea.mensajeParaAviso(false) == "Nowel, comienza tus ejercicios.")
            check(tarea.mensajeParaAviso(true) == "Sigue pendiente: hacer la tarea de matemáticas.")
            val personalizada = tarea.copy(mensajeSeguimiento = "  ¿Terminaste los ejercicios?  ")
            check(personalizada.mensajeParaAviso(true) == "¿Terminaste los ejercicios?")
            check(personalizada.mensajeParaAviso(false) == tarea.mensajeParaAviso(false))
        }
    }

    /**
     * ejecutar la llama para pedir música al catálogo real con el permiso ya concedido por root.
     * No supone que toda la música sea QA: acepta cualquier URI permitida y legible del teléfono.
     * La segunda comprobación exige que una cancelación previa termine sin empezar otra búsqueda.
     */
    private fun verificarEleccionAleatoria() {
        probar("Avisos musicales: la elección aleatoria devuelve un audio local del catálogo y legible") {
            val permiso = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            check(contexto.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED)
            val elegida = checkNotNull(SelectorMusicaAleatoria(contexto).elegir(CancellationSignal()))
            check(elegida.scheme == "content" && elegida.authority == "media")
            val visibles = BibliotecaMusica(contexto.contentResolver).leerCanciones(CancellationSignal())
            check(visibles.any { it.uri == elegida })
            contexto.contentResolver.openAssetFileDescriptor(elegida, "r").use { archivo ->
                check(archivo != null && archivo.fileDescriptor.valid() && archivo.declaredLength != 0L)
            }
        }
        probar("Avisos musicales: una búsqueda aleatoria cancelada no devuelve una canción tardía") {
            val cancelacion = CancellationSignal().apply { cancel() }
            var rechazada = false
            try {
                SelectorMusicaAleatoria(contexto).elegir(cancelacion)
            } catch (_: OperationCanceledException) {
                rechazada = true
            }
            check(rechazada)
        }
    }

    /**
     * ejecutar la llama para usar Nueva tarea y cerrar sus opciones sin confirmar silencio.
     * Guarda por el botón real y comprueba el modo persistido. No modifica preferencias personales.
     */
    private fun verificarEditorAleatorio() {
        probar("Avisos musicales: cancelar las opciones conserva aleatoria y Guardar la conserva") {
            val editor = abrirEditor()
            try {
                esperarTexto(editor, R.id.tarea_musica_button, contexto.getString(R.string.tarea_musica_aleatoria))
                enPantalla {
                    editor.findViewById<EditText>(R.id.tarea_titulo_input).setText("Nueva aleatoria $sufijo")
                    editor.findViewById<View>(R.id.tarea_musica_button).performClick()
                }
                esperar("No aparecieron los dos botones de opciones musicales.") {
                    enPantalla {
                        botonDialogo(android.R.id.button1)?.text.toString() == contexto.getString(R.string.tarea_opcion_seleccionar_musica) &&
                            botonDialogo(android.R.id.button2)?.text.toString() == contexto.getString(R.string.musica_sin)
                    }
                }
                instrumentacion.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                esperarTexto(editor, R.id.tarea_musica_button, contexto.getString(R.string.tarea_musica_aleatoria))
                enPantalla { editor.findViewById<View>(R.id.tarea_guardar).performClick() }
                esperar("Guardar no conservó la tarea aleatoria.") {
                    RepositorioTareas(contexto).listar().any {
                        it.titulo == "Nueva aleatoria $sufijo" && it.modoMusica == ModoMusica.ALEATORIA
                    }
                }
            } finally {
                cerrar(editor)
            }
        }
    }

    /**
     * ejecutar la llama para elegir Sin música, girar de verdad y guardar el formulario recuperado.
     * Conserva textos y la elección explícita después del cambio de tamaño de Android.
     */
    private fun verificarSilencioYRecreacion() {
        probar("Avisos musicales: Sin música y los textos sobreviven al giro y se guardan correctamente") {
            var editor = abrirEditor()
            try {
                enPantalla {
                    editor.findViewById<EditText>(R.id.tarea_titulo_input).setText("Sin música y giro $sufijo")
                    editor.findViewById<EditText>(R.id.tarea_mensaje_input).setText("Mensaje conservado $sufijo")
                    editor.findViewById<View>(R.id.tarea_musica_button).performClick()
                }
                esperar("No apareció Sin música en la ventana de opciones.") {
                    enPantalla { botonDialogo(android.R.id.button2)?.performClick() == true }
                }
                esperarTexto(editor, R.id.tarea_musica_button, contexto.getString(R.string.tarea_musica_sin))

                // Volver a Aleatoria debe ser una decisión visible, sin perder título ni mensaje.
                enPantalla {
                    val restablecer = editor.findViewById<View>(R.id.tarea_musica_aleatoria_button)
                    check(restablecer.visibility == View.VISIBLE && restablecer.performClick())
                }
                esperarTexto(editor, R.id.tarea_musica_button, contexto.getString(R.string.tarea_musica_aleatoria))
                enPantalla {
                    check(editor.findViewById<View>(R.id.tarea_musica_aleatoria_button).visibility == View.GONE)
                    check(editor.findViewById<View>(R.id.tarea_musica_button).performClick())
                }
                esperar("No se pudo volver a elegir Sin música después de restaurar Aleatoria.") {
                    enPantalla { botonDialogo(android.R.id.button2)?.performClick() == true }
                }
                esperarTexto(editor, R.id.tarea_musica_button, contexto.getString(R.string.tarea_musica_sin))
                val anterior = editor
                val orientacion = enPantalla { anterior.resources.configuration.orientation }
                val monitor = instrumentacion.addMonitor(ActividadEditarTarea::class.java.name, null, false)
                try {
                    enPantalla {
                        anterior.requestedOrientation = if (orientacion == Configuration.ORIENTATION_LANDSCAPE) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    }
                    editor = checkNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 10_000L)) {
                        "Android no recreó el formulario al girar."
                    }
                    pantallas += editor
                } finally {
                    instrumentacion.removeMonitor(monitor)
                }
                check(editor !== anterior)
                esperarTexto(editor, R.id.tarea_musica_button, contexto.getString(R.string.tarea_musica_sin))
                esperarTexto(editor, R.id.tarea_titulo_input, "Sin música y giro $sufijo")
                esperarTexto(editor, R.id.tarea_mensaje_input, "Mensaje conservado $sufijo")
                enPantalla { editor.findViewById<View>(R.id.tarea_guardar).performClick() }
                esperar("Guardar perdió la elección de Sin música.") {
                    RepositorioTareas(contexto).listar().any {
                        it.titulo == "Sin música y giro $sufijo" && it.modoMusica == ModoMusica.SIN_MUSICA && it.musicaUri == null
                    }
                }
            } finally {
                enPantalla { editor.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                cerrar(editor)
            }
        }
    }

    /**
     * ejecutar entrega el WAV propio para una alarma real próxima con voz desactivada.
     * Comprueba recepción, servicio visible de sonido y cancelación al completar. No certifica
     * audibilidad ni impone un tiempo exacto de reproducción: el WAV contiene solo silencio.
     */
    private fun verificarAlarmaReal(audio: CancionLocal) {
        listOf(ModoMusica.SELECCIONADA, ModoMusica.ALEATORIA).forEach { modo ->
            verificarEntregaDeModo(audio, modo)
        }
    }

    /**
     * verificarAlarmaReal entrega el audio QA y el modo que debe llegar al servicio real.
     * Comprueba tanto el archivo fijo como la búsqueda aleatoria y cancela su aviso al completar.
     * No inspecciona campos privados del servicio ni confunde notificación con sonido audible.
     */
    private fun verificarEntregaDeModo(audio: CancionLocal, modo: ModoMusica) {
        probar("Avisos musicales: la alarma $modo activa el servicio y completar cancela su sonido pendiente") {
            val alarmas = contexto.getSystemService(AlarmManager::class.java)
            check(Build.VERSION.SDK_INT < 31 || alarmas.canScheduleExactAlarms()) {
                "Concede Alarmas y recordatorios al emulador antes de ejecutar este caso."
            }
            val notificaciones = contexto.getSystemService(NotificationManager::class.java)
            check(notificaciones.areNotificationsEnabled()) { "Concede las notificaciones al emulador." }
            val momento = LocalDateTime.now().plusSeconds(5).withNano(0)
            val tarea = Tarea(
                titulo = "Entrega musical $modo QA $sufijo",
                fecha = momento.toLocalDate(),
                hora = momento.toLocalTime(),
                vozActiva = false,
                musicaUri = if (modo == ModoMusica.SELECCIONADA) audio.uri.toString() else null,
                musicaNombre = if (modo == ModoMusica.SELECCIONADA) audio.nombre else "",
                modoMusica = modo,
            )
            val gestor = GestorRecordatorios(contexto)
            try {
                gestor.guardar(tarea)
                esperar("Android no entregó la alarma real dentro del margen de comprobación.", 35_000L) {
                    RepositorioTareas(contexto).estado(tarea.id, tarea.fecha).avisosEmitidos == 1
                }
                esperar("La alarma no mostró el servicio de sonido en primer plano.") {
                    notificaciones.activeNotifications.any { it.id == NotificacionesAviso.ID_SERVICIO }
                }
                gestor.marcarCompletada(tarea.id, tarea.fecha, true)
                esperar("Completar no retiró la notificación del servicio de sonido.") {
                    notificaciones.activeNotifications.none { it.id == NotificacionesAviso.ID_SERVICIO }
                }
                val estado = RepositorioTareas(contexto).estado(tarea.id, tarea.fecha)
                check(estado.completada && estado.seguimientoDetenido && estado.alarmaProgramadaPara == null)
                check(estado.ultimaAlerta != null)
            } finally {
                gestor.marcarCompletada(tarea.id, tarea.fecha, true)
            }
        }
    }

    /** Recibe ningún dato; crea la pantalla real de nueva tarea y devuelve esa actividad QA. */
    private fun abrirEditor(): Activity {
        val editor = instrumentacion.startActivitySync(
            Intent(contexto, ActividadEditarTarea::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, checkNotNull(cuenta).id),
        )
        pantallas += editor
        return editor
    }

    /** Recibe una actividad QA; la cierra si sigue abierta y espera el cierre de Android. */
    private fun cerrar(pantalla: Activity) {
        enPantalla {
            if (!pantalla.isDestroyed && !pantalla.isFinishing) {
                pantalla.finish()
            }
        }
        instrumentacion.waitForIdleSync()
    }

    /** Recibe el identificador de un botón del diálogo y devuelve el visible de esta app. */
    private fun botonDialogo(id: Int): TextView? {
        return WindowInspector.getGlobalWindowViews()
            .mapNotNull { it.findViewById<TextView>(id) }
            .firstOrNull { it.isShown && it.isEnabled }
    }

    /** Recibe pantalla, control y texto; espera que el formulario real muestre ese contenido. */
    private fun esperarTexto(pantalla: Activity, id: Int, esperado: String) {
        esperar("El control $id no mostró '$esperado'.") {
            enPantalla { pantalla.findViewById<TextView>(id)?.text.toString() == esperado }
        }
    }

    /** Recibe trabajo para la pantalla y devuelve su resultado, llevando errores al informe QA. */
    private fun <T> enPantalla(accion: () -> T): T {
        var resultado: Result<T>? = null
        instrumentacion.runOnMainSync { resultado = runCatching(accion) }
        return checkNotNull(resultado).getOrThrow()
    }

    /** Recibe un mensaje, margen y condición; espera eventos reales sin exigir un tiempo exacto. */
    private fun esperar(mensaje: String, margen: Long = 12_000L, condicion: () -> Boolean) {
        val limite = SystemClock.elapsedRealtime() + margen
        while (!condicion()) {
            check(SystemClock.elapsedRealtime() < limite) { mensaje }
            SystemClock.sleep(50L)
        }
    }

    /** Devuelve veinte segundos de WAV PCM silencioso; preparar lo escribe solo en el archivo QA. */
    private fun wavSilencioso(): ByteArray {
        val frecuencia = 8_000
        val cantidad = frecuencia * 20 * 2
        return ByteBuffer.allocate(44 + cantidad).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + cantidad)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(frecuencia)
            putInt(frecuencia * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(cantidad)
        }.array()
    }
}
