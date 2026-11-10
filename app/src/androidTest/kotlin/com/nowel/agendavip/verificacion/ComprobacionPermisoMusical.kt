package com.nowel.agendavip.verificacion

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.presentacion.tareas.ActividadEditarTarea
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import java.util.UUID

/**
 * Recorre la pregunta real de acceso musical de Android con una cuenta QA propia.
 * Root revoca el permiso antes de instrumentar y lo restaura al terminar; esta clase no lo cambia.
 * VerificacionAgenda entrega el informe y las cuentas cuyos avisos detendrá antes de reponer la sesión.
 * UiAutomation es el acceso de las pruebas a la ventana del sistema: aquí solo pulsa su botón real.
 */
class ComprobacionPermisoMusical(
    private val instrumentacion: Instrumentation,
    private val contexto: Context,
    private val probar: (String, () -> Unit) -> Boolean,
    private val cuentasCreadas: MutableList<CuentaLocal>,
    private val permitir: Boolean = false,
) {
    private val sufijo = UUID.randomUUID().toString().take(8)
    private val titulo = "Permiso musical QA $sufijo"
    private val mensaje = "Mensaje conservado durante el permiso $sufijo"
    private val pantallas = mutableListOf<Activity>()
    private var cuenta: CuentaLocal? = null
    private var editor: Activity? = null
    private var ultimoDiagnosticoPermiso = "Todavía no se inspeccionó la ventana de permisos."

    /**
     * El runner la llama con solo=permiso_musica. No recibe ni devuelve datos.
     * Por omisión deniega; respuesta_permiso=permitir pulsa Permitir en otra ejecución preparada.
     * La cuenta y los textos son QA. Al finalizar cierra pantallas y cancela únicamente sus alarmas.
     */
    fun ejecutar() {
        val automatizacion = instrumentacion.uiAutomation
        val banderasAnteriores = automatizacion.serviceInfo.flags
        try {
            val informacion = automatizacion.serviceInfo
            informacion.flags = informacion.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automatizacion.serviceInfo = informacion

            if (!preparar()) {
                return
            }
            verificarPreguntaYRespuesta()
        } finally {
            probar("Permiso musical: se cierran las pantallas QA y se restauran las opciones de inspección") {
                // Si un fallo dejó la pregunta abierta, cerrarla con Denegar libera el formulario QA.
                botonDelSistema(false)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                enPantalla {
                    pantallas.asReversed().forEach { pantalla ->
                        if (!pantalla.isDestroyed && !pantalla.isFinishing) {
                            pantalla.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            pantalla.finish()
                        }
                    }
                }
                instrumentacion.waitForIdleSync()
                if (cuenta != null && EspacioPersonal.claveActual(contexto) == cuenta?.id) {
                    GestorRecordatorios(contexto).cancelarTodo()
                }
            }
            val informacion = automatizacion.serviceInfo
            informacion.flags = banderasAnteriores
            automatizacion.serviceInfo = informacion
        }
    }

    /**
     * ejecutar la llama para crear una cuenta aislada y abrir el formulario real.
     * Devuelve si se preparó. Exige audio revocado por root y no solicita un permiso todavía.
     */
    private fun preparar(): Boolean {
        return probar("Permiso musical: se prepara una cuenta QA con audio inicialmente denegado") {
            check(Build.VERSION.SDK_INT >= 33) { "Esta comprobación requiere Android 13 o posterior." }
            check(contexto.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                "Revoca READ_MEDIA_AUDIO antes de iniciar la instrumentación."
            }
            val nueva = RepositorioCuentas(contexto).crear(
                "Permiso musical QA",
                "qa_permiso_musica_$sufijo",
                "Clave exclusiva de permiso $sufijo 2026".toCharArray(),
            )
            cuenta = nueva
            cuentasCreadas += nueva
            CoordinadorSesion(contexto).entrar(nueva)
            val abierta = instrumentacion.startActivitySync(
                Intent(contexto, ActividadEditarTarea::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, nueva.id),
            )
            pantallas += abierta
            editor = abierta
            enPantalla {
                abierta.findViewById<EditText>(R.id.tarea_titulo_input).setText(titulo)
                abierta.findViewById<EditText>(R.id.tarea_mensaje_input).setText(mensaje)
            }
        }
    }

    /**
     * ejecutar la llama para guardar, girar durante la pregunta y responder en la ventana del sistema.
     * No recibe ni devuelve datos. Verifica que no haya guardado previo ni duplicación, y que la
     * respuesta real conserve voz, modo aleatorio y textos. No simula callbacks de permisos privados.
     */
    private fun verificarPreguntaYRespuesta() {
        val respuesta = if (permitir) "permitida" else "denegada"
        probar("Permiso musical: girar durante la pregunta y responder $respuesta conserva una sola tarea y su voz") {
            val inicial = checkNotNull(editor)
            enPantalla {
                check(inicial.findViewById<TextView>(R.id.tarea_musica_button).text.toString() ==
                    contexto.getString(R.string.tarea_musica_aleatoria))
                val guardar = inicial.findViewById<View>(R.id.tarea_guardar)
                check(guardar.performClick())
                // Otro toque no debe guardar ni abrir una segunda petición mientras la primera espera.
                check(guardar.performClick())
            }
            esperar("Android no mostró el botón real para responder al permiso musical.") {
                botonDelSistema(permitir) != null
            }
            check(RepositorioTareas(contexto).listar().isEmpty()) {
                "Se guardó antes de responder al permiso."
            }

            girarMientrasPregunta(inicial)
            val actual = checkNotNull(editor)
            enPantalla {
                check(actual.findViewById<EditText>(R.id.tarea_titulo_input).text.toString() == titulo)
                check(actual.findViewById<EditText>(R.id.tarea_mensaje_input).text.toString() == mensaje)
                check(actual.findViewById<TextView>(R.id.tarea_musica_button).text.toString() ==
                    contexto.getString(R.string.tarea_musica_aleatoria))
            }
            check(RepositorioTareas(contexto).listar().isEmpty()) {
                "El giro guardó la tarea mientras Android esperaba la respuesta."
            }
            esperar("La pregunta musical no conservó su botón al girar.") {
                botonDelSistema(permitir)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            esperar("Responder al permiso no reanudó Guardar.") {
                RepositorioTareas(contexto).listar().any { it.titulo == titulo }
            }
            val guardadas = RepositorioTareas(contexto).listar()
            check(guardadas.size == 1) { "La respuesta o el giro duplicaron la tarea." }
            val guardada = guardadas.single()
            check(guardada.titulo == titulo && guardada.mensajeVoz == mensaje)
            check(guardada.vozActiva && guardada.modoMusica == ModoMusica.ALEATORIA && guardada.musicaUri == null)
            val concedido = contexto.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            check(concedido == permitir) { "La respuesta de Android no coincidió con el botón elegido." }
        }
    }

    /**
     * verificarPreguntaYRespuesta recibe aquí la pantalla detrás del permiso para cambiar orientación.
     * Espera la actividad nueva de Android y la conserva para revisar su borrador; no responde al permiso.
     */
    private fun girarMientrasPregunta(anterior: Activity) {
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
            val actual = checkNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 10_000L)) {
                "Android no recreó el formulario durante el giro de la pregunta."
            }
            check(actual !== anterior)
            pantallas += actual
            editor = actual
            instrumentacion.waitForIdleSync()
        } finally {
            instrumentacion.removeMonitor(monitor)
        }
    }

    /**
     * Los casos entregan si quieren Permitir o Denegar y reciben el botón visible de PermissionController.
     * Es la aplicación de Android que muestra permisos. Sus identificadores no dependen del idioma.
     * La aplicación de Google puede conservar recursos con el nombre com.android.permissioncontroller;
     * por eso se compara el final del identificador, sin exigir que su prefijo sea el paquete visible.
     * No acepta ventanas de la app probada ni pulsa mensajes de otra aplicación.
     */
    private fun botonDelSistema(aceptar: Boolean): AccessibilityNodeInfo? {
        val raiz = instrumentacion.uiAutomation.rootInActiveWindow
        if (raiz == null) {
            ultimoDiagnosticoPermiso = "UiAutomation no entregó una ventana activa."
            return null
        }

        val paquete = raiz.packageName?.toString().orEmpty()
        val esControlador = paquete.endsWith("permissioncontroller")
        val nombre = if (aceptar) "permission_allow_button" else "permission_deny_button"
        val pendientes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val identificadores = mutableListOf<String>()
        pendientes.add(raiz)
        var inspeccionados = 0
        var encontrada: AccessibilityNodeInfo? = null

        // Un recorrido limitado evita depender del namespace del recurso o del orden de las vistas.
        // Fuera de PermissionController solo recoge identificadores para explicar un fallo de enfoque.
        while (pendientes.isNotEmpty() && inspeccionados < 600) {
            val nodo = pendientes.removeFirst()
            inspeccionados += 1
            val identificador = nodo.viewIdResourceName.orEmpty()

            if (identificador.isNotBlank() && identificadores.size < 30) {
                identificadores += "$identificador[visible=${nodo.isVisibleToUser},habilitado=${nodo.isEnabled}]"
            }
            if (esControlador && identificador.substringAfterLast('/') == nombre &&
                nodo.isVisibleToUser && nodo.isEnabled
            ) {
                encontrada = nodo
                break
            }

            for (indice in 0 until nodo.childCount) {
                nodo.getChild(indice)?.let(pendientes::addLast)
            }
        }

        ultimoDiagnosticoPermiso = "Ventana=$paquete; clase=${raiz.className}; " +
            "controlador=$esControlador; nodos=$inspeccionados; IDs=${identificadores.joinToString()}."
        return encontrada
    }

    /**
     * esperar la llama únicamente si se agota el margen para añadir información útil al informe.
     * Devuelve paquete e identificadores de la ventana, permiso actual, foco y número de tareas QA.
     * No lee textos de otras ventanas ni modifica permisos o datos durante el diagnóstico.
     */
    private fun diagnosticoDelPermiso(): String {
        val concedido = contexto.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        val tareasQa = if (cuenta?.id == EspacioPersonal.claveActual(contexto)) {
            runCatching { RepositorioTareas(contexto).listar().size.toString() }.getOrDefault("no disponibles")
        } else {
            "sesión cambiada"
        }
        val estadoEditor = enPantalla {
            editor?.let { "foco=${it.hasWindowFocus()},cerrando=${it.isFinishing},destruido=${it.isDestroyed}" }
                ?: "sin editor"
        }
        return "$ultimoDiagnosticoPermiso AudioConcedido=$concedido; tareasQA=$tareasQa; editor=$estadoEditor."
    }

    /** Recibe una operación sobre controles Android y devuelve su resultado en el hilo de comprobación. */
    private fun <T> enPantalla(accion: () -> T): T {
        var resultado: Result<T>? = null
        instrumentacion.runOnMainSync { resultado = runCatching(accion) }
        return checkNotNull(resultado).getOrThrow()
    }

    /** Recibe mensaje y condición; espera eventos con un margen limitado sin imponer pausas largas. */
    private fun esperar(mensaje: String, condicion: () -> Boolean) {
        val limite = SystemClock.elapsedRealtime() + 12_000L
        while (!condicion()) {
            check(SystemClock.elapsedRealtime() < limite) {
                "$mensaje ${diagnosticoDelPermiso()}"
            }
            SystemClock.sleep(50L)
        }
    }
}
