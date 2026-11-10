package com.nowel.agendavip.verificacion

import android.app.Activity
import android.app.Instrumentation
import android.view.WindowManager
import com.nowel.agendavip.presentacion.acceso.ActividadDesbloqueo
import com.nowel.agendavip.presentacion.tareas.ActividadEditarTarea
import com.nowel.agendavip.seguridad.AplicacionAgenda
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nowel.agendavip.datos.cuentas.EliminadorCuenta
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.datos.uso.RepositorioLimitesUso
import com.nowel.agendavip.dominio.agenda.EstadoOcurrencia
import com.nowel.agendavip.dominio.uso.LimiteAplicacion
import com.nowel.agendavip.seguridad.BloqueoLocal
import java.io.File
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import com.nowel.agendavip.datos.uso.LectorUsoDiario
import com.nowel.agendavip.recordatorios.uso.NotificacionesLimitesUso
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.EditText
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.asistente.ActividadModelosLocales
import com.nowel.agendavip.presentacion.cuenta.ActividadCuenta
import com.nowel.agendavip.presentacion.cuenta.ActividadRespaldo
import com.nowel.agendavip.presentacion.cuenta.ActividadSeguridad
import com.nowel.agendavip.presentacion.uso.ActividadLimitesUso
import com.nowel.agendavip.recordatorios.uso.ServicioLimitesUso
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.dominio.enfoque.AccionVozEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.dominio.enfoque.InterpreteVozEnfoque
import com.nowel.agendavip.dominio.enfoque.TipoEventoEnfoque
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import com.nowel.agendavip.recordatorios.NotificacionesAviso
import com.nowel.agendavip.presentacion.tareas.ActividadDetalleTarea
import com.nowel.agendavip.recordatorios.enfoque.GestorEnfoque
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/**
 * Comprueba operaciones reales dentro de Android, sin bibliotecas de pruebas adicionales.
 * Instrumentation es una entrada especial que Android ejecuta únicamente al solicitar una prueba.
 * Este archivo pertenece a androidTest: no forma parte de la aplicación que recibe el usuario.
 * Crea cuentas de prueba propias, conserva sus resultados y restaura la sesión anterior al terminar.
 * Nunca borra cuentas, tareas ni preferencias de la persona que utiliza la app.
 */
class VerificacionAgenda : Instrumentation() {

    private lateinit var aplicacion: Context
    private var argumentos = Bundle()
    private val informe = StringBuilder()
    private val cuentasCreadas = mutableListOf<CuentaLocal>()
    private var aprobadas = 0
    private var fallidas = 0

    /** Android entrega las opciones de esta ejecución; start pone la revisión en su hilo de trabajo. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        argumentos = arguments ?: Bundle()
        start()
    }

    /**
     * Ejecuta las comprobaciones y entrega un informe a quien inició la prueba.
     * La restauración de sesión ocurre aunque falle un caso; su resultado también se informa.
     */
    override fun onStart() {
        super.onStart()
        aplicacion = targetContext.applicationContext
        val original = EspacioPersonal.claveActual(aplicacion)
        var sesionGestionadaPorReinicio = false

        try {
            if (argumentos.getString("solo") == "reinicio_descanso_preparar") {
                sesionGestionadaPorReinicio = ComprobacionReinicioDescanso(
                    this, aplicacion, ::probar, cuentasCreadas,
                ).preparar()
            } else if (argumentos.getString("solo") == "reinicio_descanso_comprobar") {
                sesionGestionadaPorReinicio = true
                ComprobacionReinicioDescanso(
                    this, aplicacion, ::probar, cuentasCreadas,
                ).comprobarYLimpiar()
            } else if (argumentos.getString("solo") == "reglas_descanso") {
                ComprobacionReglasDescanso(this, aplicacion, ::probar, cuentasCreadas).ejecutar()
            } else if (argumentos.getString("solo") == "pantallas_descanso") {
                ComprobacionPantallasDescanso(this, aplicacion, ::probar, cuentasCreadas).ejecutar()
            } else if (argumentos.getString("solo") == "bloqueo") {
                verificarBloqueoVisual()
            } else if (argumentos.getString("solo") == "uso_real") {
                verificarUsoReal()
            } else if (argumentos.getString("solo") == "pantallas_cierre") {
                verificarPantallasCierre()
            } else if (argumentos.getString("solo") == "cierre") {
                verificarCierreLocal()
            } else if (argumentos.getString("solo") == "permiso_musica") {
                ComprobacionPermisoMusical(
                    this,
                    aplicacion,
                    ::probar,
                    cuentasCreadas,
                    permitir = argumentos.getString("respuesta_permiso") == "permitir",
                ).ejecutar()
            } else if (argumentos.getString("solo") == "avisos_musica") {
                ComprobacionAvisosMusicales(
                    this,
                    aplicacion,
                    ::probar,
                    cuentasCreadas,
                ).ejecutar()
            } else if (argumentos.getString("solo") == "musica") {
                ComprobacionMusica(
                    this,
                    aplicacion,
                    ::probar,
                    cuentasCreadas,
                ).ejecutar()
            } else if (argumentos.getString("solo") == "reprogramacion") {
                verificarReprogramacion()
            } else {
                verificarInterprete()
                probar("La biblioteca nativa de voz se puede cargar") {
                    System.loadLibrary("agenda_voz_local")
                }
                verificarCuentasYAgenda()
            }
        } catch (error: Throwable) {
            registrarFallo("La preparación impidió continuar los demás casos", error)
        } finally {
            if (!sesionGestionadaPorReinicio) {
                probar("Se detienen los avisos de las cuentas de prueba y se restaura la sesión original") {
                    restaurarSesion(original)
                }
            }
        }

        val resumen = "\nAprobadas: $aprobadas. Fallidas: $fallidas.\n"
        informe.append(resumen)
        val resultado = Bundle().apply {
            putString("stream", informe.toString())
            putInt("aprobadas", aprobadas)
            putInt("fallidas", fallidas)
            putString("cuentas_prueba", cuentasCreadas.joinToString { it.usuario })
            putString("sesion_restaurada", EspacioPersonal.claveActual(aplicacion).orEmpty())
        }
        finish(if (fallidas == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, resultado)
    }






    /**
     * Revisa el bloqueo visual y la contraseña alternativa con una cuenta QA propia.
     * onStart la llama con solo=bloqueo. La opción se prepara directamente solo para esa cuenta:
     * no se configura ni cambia el PIN del emulador, y esta prueba no certifica la biometría.
     * La autorización se obtiene enviando la contraseña al diálogo real, nunca simulando un éxito.
     */
    private fun verificarBloqueoVisual() {
        val sufijo = UUID.randomUUID().toString().take(8)
        val clave = "Clave de bloqueo QA $sufijo 2026"
        lateinit var cuenta: CuentaLocal

        if (!probar("Se prepara una cuenta exclusiva para probar el bloqueo visual") {
                cuenta = RepositorioCuentas(aplicacion).crear(
                    "Bloqueo visual",
                    "qa_bloqueo_$sufijo",
                    clave.toCharArray()
                )
                cuentasCreadas.add(cuenta)
                CoordinadorSesion(aplicacion).entrar(cuenta)
                check(!BloqueoLocal.estaActivado(aplicacion))
            }) {
            return
        }

        val opcion = aplicacion.getSharedPreferences(
            EspacioPersonal.nombrePreferencias("seguridad_local", cuenta.id),
            Context.MODE_PRIVATE
        )
        val editor = startActivitySync(
            Intent(aplicacion, ActividadEditarTarea::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
        )
        val monitor = addMonitor(ActividadDesbloqueo::class.java.name, null, false)
        var desbloqueo: Activity? = null
        val borrador = "Borrador privado que debe conservarse $sufijo"

        try {
            esperarTextoPantalla(editor, "Nueva tarea")
            runOnMainSync {
                editor.findViewById<EditText>(R.id.tarea_titulo_input).setText(borrador)
            }

            val cubierta = probar("Volver al Editor abre el desbloqueo real y oculta su borrador") {
                // Esta preparación evita cambiar el PIN global para una comprobación de contraseña local.
                check(opcion.edit().putBoolean("bloqueo_activado", true).commit())
                BloqueoLocal.bloquear()
                runOnMainSync {
                    (aplicacion as AplicacionAgenda).actualizarVentanas()
                    editor.moveTaskToBack(true)
                }
                waitForIdleSync()
                runOnMainSync {
                    aplicacion.startActivity(
                        Intent(aplicacion, ActividadEditarTarea::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
                    )
                }
                desbloqueo = waitForMonitorWithTimeout(monitor, 5_000L)
                val pantallaProtegida = checkNotNull(desbloqueo) {
                    "No se abrió la pantalla de desbloqueo al volver al Editor."
                }
                esperarTextoPantalla(pantallaProtegida, "Tu agenda está protegida")
                runOnMainSync {
                    check(!editor.isDestroyed && !editor.isFinishing)
                    check(editor.window.decorView.visibility == View.INVISIBLE)
                    check(editor.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    check(editor.findViewById<EditText>(R.id.tarea_titulo_input).text.toString() == borrador)
                }
                check(BloqueoLocal.necesitaDesbloqueo(aplicacion))
                check(EspacioPersonal.claveActual(aplicacion) == cuenta.id)
            }

            if (cubierta) {
                probar("La contraseña real autoriza y devuelve el mismo borrador sin guardarlo como tarea") {
                    val pantallaProtegida = checkNotNull(desbloqueo)
                    runOnMainSync {
                        check(!pantallaProtegida.isDestroyed && !pantallaProtegida.isFinishing) {
                            "La prueba recibió una pantalla de desbloqueo que ya se cerró."
                        }
                        val raiz = pantallaProtegida.findViewById<View>(android.R.id.content)
                        val boton = checkNotNull(buscarTextoPantalla(raiz, "Usar contraseña de Agenda.VIP"))
                        check(boton.performClick()) { "La prueba no encontró un botón con acción de contraseña." }
                        informar("BLOQUEO_DIAGNOSTICO: foco=${pantallaProtegida.hasWindowFocus()}, visible=${pantallaProtegida.window.decorView.visibility}, cerrada=${pantallaProtegida.isFinishing}.")
                        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { ventana ->
                            informar("BLOQUEO_VENTANA: ${ventana.javaClass.simpleName}, visible=${ventana.visibility}, foco=${ventana.hasWindowFocus()}, campo=${buscarEntradaBloqueo(ventana) != null}, dialogo=${buscarTextoPantalla(ventana, "Contraseña de Agenda.VIP") != null}.")
                        }
                    }
                    // WindowInspector consulta las ventanas de este mismo proceso, incluido el diálogo.
                    // Se usa solo en estas pruebas de Android 16, sin servicios de accesibilidad externos.
                    check(android.os.Build.VERSION.SDK_INT >= 29)
                    var campo: EditText? = null
                    esperarCondicion("No apareció el campo del diálogo de contraseña.") {
                        runOnMainSync {
                            campo = android.view.inspector.WindowInspector.getGlobalWindowViews()
                                .filter { buscarTextoPantalla(it, "Contraseña de Agenda.VIP") != null }
                                .mapNotNull { buscarEntradaBloqueo(it) }
                                .lastOrNull()
                        }
                        campo != null
                    }
                    runOnMainSync {
                        val entrada = checkNotNull(campo)
                        entrada.setText(clave)
                        val confirmar = checkNotNull(buscarTextoPantalla(entrada.rootView, "Desbloquear")) {
                            "No apareció el botón de confirmación del diálogo."
                        }
                        confirmar.performClick()
                    }


                    esperarCondicionLarga("La contraseña correcta no devolvió la pantalla protegida.") {
                        var visible = false
                        runOnMainSync {
                            visible = editor.window.decorView.visibility == View.VISIBLE &&
                                !editor.isFinishing && !editor.isDestroyed
                        }
                        !BloqueoLocal.necesitaDesbloqueo(aplicacion) && visible
                    }
                    runOnMainSync {
                        check(editor.findViewById<EditText>(R.id.tarea_titulo_input).text.toString() == borrador)
                    }
                    check(RepositorioTareas(aplicacion).listar().isEmpty())
                    check(EspacioPersonal.claveActual(aplicacion) == cuenta.id)
                }
            }
        } finally {
            // Solo se restaura la opción de esta cuenta ficticia; las demás conservan su bloqueo.
            opcion.edit().putBoolean("bloqueo_activado", false).commit()
            removeMonitor(monitor)
            runOnMainSync {
                desbloqueo?.finish()
                editor.finish()
                (aplicacion as AplicacionAgenda).actualizarVentanas()
            }
            waitForIdleSync()
            BloqueoLocal.bloquear()
        }
    }

    /**
     * Recibe una ventana propia de la prueba y devuelve su campo de texto, si tiene uno.
     * Solo busca el control: la contraseña todavía debe pasar por el botón y repositorio reales.
     */
    private fun buscarEntradaBloqueo(vista: View): EditText? {
        if (vista is EditText) {
            return vista
        }
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                val encontrada = buscarEntradaBloqueo(vista.getChildAt(indice))
                if (encontrada != null) {
                    return encontrada
                }
            }
        }
        return null
    }

    /**
     * Comprueba el servicio y los eventos reales de uso de Configuración en el emulador.
     * onStart la llama con solo=uso_real. Quien ejecuta debe conceder antes Acceso de uso
     * y mantener la pantalla encendida: esta prueba no concede permisos ni ejecuta comandos.
     * No simula minutos ni entrega notificaciones; espera los turnos normales del servicio.
     */
    @Suppress("DEPRECATION")
    private fun verificarUsoReal() {
        val sufijo = UUID.randomUUID().toString().take(8)
        lateinit var cuenta: CuentaLocal
        val lector = LectorUsoDiario(aplicacion)
        val sistema = aplicacion.getSystemService(NotificationManager::class.java)
        val abrirAjustes = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var paqueteAjustes = ""

        if (!probar("Se prepara una cuenta QA con permisos ya concedidos para observar uso real") {
                check(lector.tienePermiso()) { "Concede Acceso de uso antes de ejecutar uso_real." }
                check(ServicioLimitesUso.permiteNotificaciones(aplicacion)) {
                    "Concede notificaciones antes de ejecutar uso_real."
                }
                paqueteAjustes = checkNotNull(aplicacion.packageManager.resolveActivity(abrirAjustes, 0)) {
                    "El emulador no ofrece una pantalla de Configuración."
                }.activityInfo.packageName
                cuenta = RepositorioCuentas(aplicacion).crear(
                    "Uso real",
                    "qa_uso_real_$sufijo",
                    "Clave exclusiva de uso $sufijo 2026".toCharArray()
                )
                cuentasCreadas.add(cuenta)
                CoordinadorSesion(aplicacion).entrar(cuenta)
                PreferenciasAgenda(aplicacion).guardarVolumen(0f)
            }) {
            return
        }

        val repositorio = RepositorioLimitesUso(aplicacion)
        val usoAnterior = lector.consultar(setOf(paqueteAjustes))
        val milisegundosAnteriores = usoAnterior.milisegundosPorPaquete[paqueteAjustes] ?: 0L
        // La aplicación Configuración puede haber sido usada antes. El siguiente minuto entero
        // exige uso nuevo y evita aprobar el aviso con minutos de una comprobación anterior.
        val minutosLimite = (milisegundosAnteriores / 60_000L + 1L).toInt().coerceAtMost(1440)
        val objetivo = LimiteAplicacion(
            paquete = paqueteAjustes,
            nombre = "Configuración de prueba",
            minutosDiarios = minutosLimite,
            mensaje = "Terminó el minuto de comprobación del uso real.",
            hablar = false
        )
        repositorio.guardar(objetivo)
        val etiqueta = "${cuenta.id}|uso|$paqueteAjustes"
        val pantalla = startActivitySync(
            Intent(aplicacion, ActividadLimitesUso::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
        )
        var inicioCorrecto = false
        var avisoRecibido = false
        var momentoPublicacion = 0L

        try {
            esperarTextoPantalla(pantalla, "Límites de uso")
            inicioCorrecto = probar("La pantalla visible inicia el servicio con notificación permanente y Pausar") {
                runOnMainSync { ServicioLimitesUso.iniciar(pantalla) }
                esperarCondicion("No arrancó la supervisión con su notificación.") {
                    ServicioLimitesUso.estaActivo(aplicacion) && sistema.activeNotifications.any {
                        it.id == NotificacionesLimitesUso.ID_SERVICIO &&
                            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
                            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
                            it.notification.actions.orEmpty().any { accion -> accion.title.toString() == "Pausar" }
                    }
                }
            }

            if (inicioCorrecto) {
                avisoRecibido = probar("El tiempo real en Configuración alcanza el límite y publica su aviso") {
                    informar("USO_REAL: se abre Configuración; su uso previo era $milisegundosAnteriores ms y el límite nuevo es $minutosLimite minutos. Se espera la consulta normal del servicio, sin simular entrega.")
                    runOnMainSync { pantalla.startActivity(abrirAjustes) }
                    val limite = SystemClock.elapsedRealtime() + 95_000L
                    var siguienteInforme = SystemClock.elapsedRealtime() + 20_000L

                    while (SystemClock.elapsedRealtime() < limite &&
                        sistema.activeNotifications.none { it.tag == etiqueta && it.id == 1 }) {
                        SystemClock.sleep(250L)
                        if (SystemClock.elapsedRealtime() >= siguienteInforme) {
                            informar("USO_REAL: continúa la espera de eventos reales y del próximo turno de supervisión.")
                            siguienteInforme += 20_000L
                        }
                    }

                    val aviso = checkNotNull(sistema.activeNotifications.firstOrNull { it.tag == etiqueta && it.id == 1 }) {
                        "No llegó el aviso de uso dentro de 95 segundos. Revisa permiso, pantalla encendida y eventos de Android."
                    }
                    val actual = lector.consultar(setOf(paqueteAjustes))
                    check(actual.fecha == usoAnterior.fecha) { "La prueba cruzó medianoche; repítela en el mismo día." }
                    check((actual.milisegundosPorPaquete[paqueteAjustes] ?: 0L) >= minutosLimite * 60_000L)
                    check(repositorio.yaAvisado(paqueteAjustes, actual.fecha))
                    check(aviso.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == objetivo.mensaje)
                    momentoPublicacion = aviso.postTime
                }

                if (avisoRecibido) {
                    probar("El siguiente turno de supervisión no vuelve a publicar el mismo aviso diario") {
                        informar("USO_REAL: se espera otro turno de 31 segundos para comprobar que el aviso diario no se repite.")
                        val limite = SystemClock.elapsedRealtime() + 31_000L
                        while (SystemClock.elapsedRealtime() < limite) {
                            SystemClock.sleep(250L)
                            check(ServicioLimitesUso.estaActivo(aplicacion))
                        }
                        val avisos = sistema.activeNotifications.filter { it.tag == etiqueta && it.id == 1 }
                        check(avisos.size == 1 && avisos.single().postTime == momentoPublicacion)
                    }
                }

                probar("La acción Pausar de la notificación detiene la supervisión y retira el aviso permanente") {
                    val aviso = checkNotNull(sistema.activeNotifications.firstOrNull {
                        it.id == NotificacionesLimitesUso.ID_SERVICIO
                    })
                    val pausar = checkNotNull(aviso.notification.actions.orEmpty().firstOrNull {
                        it.title.toString() == "Pausar"
                    })
                    pausar.actionIntent.send()
                    esperarCondicion("Pausar no terminó el servicio.") {
                        !ServicioLimitesUso.estaActivo(aplicacion) && sistema.activeNotifications.none {
                            it.id == NotificacionesLimitesUso.ID_SERVICIO
                        }
                    }
                }
            }
        } finally {
            runOnMainSync {
                ServicioLimitesUso.detener(aplicacion)
                pantalla.finish()
            }
            waitForIdleSync()
        }
    }

    /**
     * Abre las pantallas nuevas con una cuenta ficticia y revisa contenido y navegación.
     * onStart la llama con solo=pantallas_cierre. No toca permisos, bloqueo, modelos ni micrófono.
     * El saludo escrito usa volumen cero de esa cuenta; no cambia el sonido general del teléfono.
     * Estas comprobaciones no certifican biometría, voz real ni las restricciones de cada fabricante.
     */
    private fun verificarPantallasCierre() {
        val sufijo = UUID.randomUUID().toString().take(8)
        lateinit var cuenta: CuentaLocal

        if (!probar("Se prepara una cuenta exclusiva para recorrer las pantallas nuevas") {
                cuenta = RepositorioCuentas(aplicacion).crear(
                    "Pantallas de cierre",
                    "qa_pantallas_$sufijo",
                    "Clave de pantallas $sufijo 2026".toCharArray()
                )
                cuentasCreadas.add(cuenta)
                CoordinadorSesion(aplicacion).entrar(cuenta)
                PreferenciasAgenda(aplicacion).guardarVolumen(0f)
                check(!BloqueoLocal.estaActivado(aplicacion))
                check(!ServicioLimitesUso.estaActivo(aplicacion))
            }) {
            return
        }

        // Los títulos y los botones comprueban que cada pantalla se dibujó, no solo que existe la clase.
        val pantallas = listOf(
            Triple(ActividadSeguridad::class.java, "Seguridad y privacidad", "Activar bloqueo"),
            Triple(ActividadRespaldo::class.java, "Copia de seguridad", "Crear copia con huella"),
            Triple(ActividadLimitesUso::class.java, "Límites de uso", "Añadir aplicación"),
            Triple(ActividadModelosLocales::class.java, "Voz sin conexión para Enfoque", "Seleccionar archivo descargado")
        )

        pantallas.forEach { (clase, titulo, control) ->
            probar("La pantalla $titulo abre y muestra sus controles sin activarlos") {
                val pantalla = startActivitySync(
                    Intent(aplicacion, clase)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
                )
                try {
                    esperarTextoPantalla(pantalla, titulo)
                    esperarTextoPantalla(pantalla, control)
                    check(!pantalla.isFinishing && !pantalla.isDestroyed)
                    check(EspacioPersonal.claveActual(aplicacion) == cuenta.id)
                    check(!BloqueoLocal.estaActivado(aplicacion))
                    check(!ServicioLimitesUso.estaActivo(aplicacion))
                } finally {
                    runOnMainSync { pantalla.finish() }
                    waitForIdleSync()
                }
            }
        }

        probar("Configuración muestra los ajustes personales y abre Seguridad al tocar su fila") {
            val pantalla = startActivitySync(
                Intent(aplicacion, ActividadCuenta::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
                    .putExtra("seccion", "configuracion")
            )
            var destino: Activity? = null
            val monitor = addMonitor(ActividadSeguridad::class.java.name, null, false)
            try {
                listOf(
                    "Seguridad y acceso",
                    "Copia de seguridad",
                    "Límites de uso",
                    "Descanso nocturno"
                ).forEach { esperarTextoPantalla(pantalla, it) }
                runOnMainSync {
                    val raiz = pantalla.findViewById<View>(android.R.id.content)
                    val titulo = checkNotNull(buscarTextoPantalla(raiz, "Seguridad y acceso"))
                    // El texto está dentro de la fila que recibe el toque.
                    val fila = generateSequence<View>(titulo) { vista -> vista.parent as? View }
                        .first { vista -> vista.isClickable }
                    fila.performClick()
                }
                destino = waitForMonitorWithTimeout(monitor, 5_000L)
                val abierta = checkNotNull(destino) { "El botón Seguridad no abrió su pantalla." }
                esperarTextoPantalla(abierta, "Seguridad y privacidad")
            } finally {
                removeMonitor(monitor)
                runOnMainSync {
                    destino?.finish()
                    pantalla.finish()
                }
                waitForIdleSync()
            }
        }
    }

    /**
     * Recibe una pantalla y un texto esperado; espera hasta tres segundos mientras se dibuja.
     * Lee las vistas en el trabajo principal de Android y no pulsa ningún permiso ni acción.
     */
    private fun esperarTextoPantalla(pantalla: Activity, texto: String) {
        esperarCondicion("No apareció «$texto» en ${pantalla.javaClass.simpleName}.") {
            var encontrado = false
            runOnMainSync {
                val raiz = pantalla.findViewById<View>(android.R.id.content)
                encontrado = buscarTextoPantalla(raiz, texto) != null
            }
            encontrado
        }
    }

    /** Busca un control de texto dentro de los contenedores de una pantalla, sin modificarlo. */
    private fun buscarTextoPantalla(vista: View, texto: String): TextView? {
        if (vista is TextView && vista.text.toString().contains(texto)) {
            return vista
        }
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                val encontrada = buscarTextoPantalla(vista.getChildAt(indice), texto)
                if (encontrada != null) {
                    return encontrada
                }
            }
        }
        return null
    }

    /**
     * Revisa seguridad, respaldo e historial con cuentas y archivos ficticios propios.
     * onStart la llama únicamente con solo=cierre. No graba audio, no inicia supervisión
     * ni comprueba las restricciones de un fabricante; esas comprobaciones necesitan el teléfono.
     * El bloque final del ejecutor restaura siempre la cuenta que estaba abierta antes.
     */
    private fun verificarCierreLocal() {
        val cuentas = RepositorioCuentas(aplicacion)
        val sesion = CoordinadorSesion(aplicacion)
        val sufijo = UUID.randomUUID().toString().take(8)
        val claveA = "Clave exclusiva A $sufijo 2026"
        val claveB = "Clave exclusiva B $sufijo 2026"
        val nuevaA = "Nueva clave exclusiva A $sufijo"
        lateinit var cuentaA: CuentaLocal
        lateinit var cuentaB: CuentaLocal

        if (!probar("Se preparan dos cuentas exclusivas para comprobar el cierre") {
                cuentaA = cuentas.crear("Cierre A", "qa_cierre_a_$sufijo", claveA.toCharArray())
                cuentasCreadas.add(cuentaA)
                cuentaB = cuentas.crear("Cierre B", "qa_cierre_b_$sufijo", claveB.toCharArray())
                cuentasCreadas.add(cuentaB)
                sesion.entrar(cuentaA)
            }) {
            return
        }

        probar("Cambiar la contraseña invalida la anterior y limpia los caracteres recibidos") {
            val anterior = claveA.toCharArray()
            val nueva = nuevaA.toCharArray()
            cuentas.cambiarContrasena(cuentaA.id, anterior, nueva)
            check(anterior.all { it == '\u0000' } && nueva.all { it == '\u0000' })
            check(!cuentas.confirmarContrasena(cuentaA.id, claveA.toCharArray()))
            check(cuentas.confirmarContrasena(cuentaA.id, nuevaA.toCharArray()))
            check(cuentas.confirmarContrasena(cuentaB.id, claveB.toCharArray()))
        }

        probar("La contraseña de otra cuenta no permite cambiar ni eliminar la cuenta abierta") {
            esperarRechazo {
                cuentas.cambiarContrasena(cuentaA.id, claveB.toCharArray(), "No debe guardarse esta clave 2026".toCharArray())
            }
            esperarRechazo { EliminadorCuenta(aplicacion).eliminarActual(claveB.toCharArray()) }
            check(cuentas.obtener(cuentaA.id) != null && cuentas.obtener(cuentaB.id) != null)
            check(cuentas.confirmarContrasena(cuentaA.id, nuevaA.toCharArray()))
            check(EspacioPersonal.claveActual(aplicacion) == cuentaA.id)
        }

        probar("Bloquear la vista conserva la sesión y solo autoriza al dueño actual") {
            check(!BloqueoLocal.estaActivado(aplicacion))
            BloqueoLocal.bloquear()
            check(!BloqueoLocal.necesitaDesbloqueo(aplicacion))
            esperarRechazo { BloqueoLocal.autorizar(aplicacion, cuentaB.id) }
            BloqueoLocal.autorizar(aplicacion, cuentaA.id)
            BloqueoLocal.bloquear()
            check(EspacioPersonal.claveActual(aplicacion) == cuentaA.id)
            check(!BloqueoLocal.estaActivado(aplicacion, cuentaB.id))
        }

        val tareas = RepositorioTareas(aplicacion)
        val ayer = LocalDate.now().minusDays(1)
        val historica = Tarea(
            titulo = "Título histórico $sufijo",
            fecha = ayer,
            hora = LocalTime.of(9, 0),
            vozActiva = false
        )
        probar("Editar y eliminar la tarea activa conserva el título y hora del historial pasado") {
            tareas.guardar(historica)
            tareas.guardarEstado(EstadoOcurrencia(historica.id, ayer, completada = true))
            tareas.guardar(historica.copy(titulo = "Título nuevo $sufijo", hora = LocalTime.of(10, 0)))
            check(tareas.historialEntre(ayer, ayer).any {
                it.tarea.id == historica.id && it.tarea.titulo == historica.titulo &&
                    it.tarea.hora == historica.hora && it.estado.completada
            })
            tareas.eliminar(historica.id)
            check(tareas.obtener(historica.id) == null)
            check(tareas.historialEntre(ayer, ayer).any { it.tarea.titulo == historica.titulo })
        }

        val limites = RepositorioLimitesUso(aplicacion)
        val objetivo = LimiteAplicacion(
            paquete = "com.example.agendaprueba",
            nombre = "Aplicación ficticia",
            minutosDiarios = 20,
            mensaje = "Recuerda tu objetivo de estudio."
        )
        probar("Los objetivos y sus avisos diarios están separados por cuenta") {
            limites.guardar(objetivo)
            limites.marcarAvisado(objetivo.paquete, LocalDate.now())
            check(limites.yaAvisado(objetivo.paquete, LocalDate.now()))
            check(!limites.yaAvisado(objetivo.paquete, LocalDate.now().plusDays(1)))
            try {
                sesion.entrar(cuentaB)
                check(RepositorioLimitesUso(aplicacion).listar().isEmpty())
                check(!RepositorioLimitesUso(aplicacion).yaAvisado(objetivo.paquete, LocalDate.now()))
                esperarRechazo { limites.listar() }
            } finally {
                sesion.entrar(cuentaA)
            }
            check(RepositorioLimitesUso(aplicacion).listar() == listOf(objetivo))
        }

        probar("Una copia con límites corruptos se rechaza antes de guardarla") {
            val valida = """[{"paquete":"com.example.agendaprueba","nombre":"Prueba","minutos":20,"mensaje":"Estudia","hablar":false,"habilitado":true}]"""
            RepositorioLimitesUso.validarCopia(valida)
            esperarRechazo { RepositorioLimitesUso.validarCopia(valida.replace("20", "20.5")) }
            esperarRechazo { RepositorioLimitesUso.validarCopia(valida.replace("20", "0")) }
            esperarRechazo { RepositorioLimitesUso.validarCopia(valida + " texto sobrante") }
            check(RepositorioLimitesUso(aplicacion).listar() == listOf(objetivo))
        }

        val carpeta = File(aplicacion.cacheDir, "qa_cierre_$sufijo")
        check(carpeta.mkdir()) { "No se pudo preparar la carpeta temporal de esta prueba." }
        val archivo = File(carpeta, "respaldo.avip")
        val alterado = File(carpeta, "alterado.avip")
        val claveRespaldo = "Respaldo exclusivo $sufijo 2026"
        val normal = Tarea(
            titulo = "Dato del respaldo $sufijo",
            fecha = LocalDate.now().plusDays(1),
            hora = LocalTime.of(12, 0),
            vozActiva = false
        )
        val preferencias = PreferenciasAgenda(aplicacion)
        var exportada = false

        try {
            exportada = probar("Exportar cifra el contenido y no conserva la contraseña en su arreglo") {
                tareas.guardar(normal)
                preferencias.guardarVolumen(1f)
                preferencias.guardarIntervalo(10)
                val clave = claveRespaldo.toCharArray()
                CopiaSeguridadLocal(aplicacion).exportar(Uri.fromFile(archivo), clave)
                check(clave.all { it == '\u0000' })
                check(archivo.length() > 64)
                check(!archivo.readBytes().toString(Charsets.ISO_8859_1).contains(normal.titulo))
            }

            if (exportada) {
                probar("Contraseña incorrecta o archivo alterado no modifican los datos actuales") {
                    val antes = EspacioPersonal(aplicacion).preferencias("agenda_local").getString("documento_v1", null)
                    esperarRechazo {
                        CopiaSeguridadLocal(aplicacion).preparar(Uri.fromFile(archivo), "Contraseña equivocada 2026".toCharArray())
                    }
                    val bytes = archivo.readBytes()
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 64).toByte()
                    alterado.writeBytes(bytes)
                    esperarRechazo {
                        CopiaSeguridadLocal(aplicacion).preparar(Uri.fromFile(alterado), claveRespaldo.toCharArray())
                    }
                    check(EspacioPersonal(aplicacion).preferencias("agenda_local").getString("documento_v1", null) == antes)
                }

                probar("Restaurar recupera tareas e historial y conserva el volumen como decimal") {
                    val copia = CopiaSeguridadLocal(aplicacion)
                    val preparada = copia.preparar(Uri.fromFile(archivo), claveRespaldo.toCharArray())
                    check(preparada.tareas == 1)
                    tareas.guardar(normal.copy(titulo = "Dato cambiado después de exportar"))
                    preferencias.guardarVolumen(0.25f)
                    preferencias.guardarIntervalo(5)
                    copia.restaurar(preparada)
                    check(RepositorioTareas(aplicacion).obtener(normal.id)?.titulo == normal.titulo)
                    check(PreferenciasAgenda(aplicacion).volumenAvisos == 1f)
                    check(PreferenciasAgenda(aplicacion).intervaloMinutos == 10)
                    check(RepositorioTareas(aplicacion).historialEntre(ayer, ayer).any {
                        it.tarea.id == historica.id && it.tarea.titulo == historica.titulo
                    })
                    check(RepositorioLimitesUso(aplicacion).listar() == listOf(objetivo))
                    check(EspacioPersonal.claveActual(aplicacion) == cuentaA.id)
                    check(cuentas.confirmarContrasena(cuentaA.id, nuevaA.toCharArray()))
                    check(cuentas.obtener(cuentaB.id) != null)
                }
            }
        } finally {
            // Solo se retiran los tres objetos creados aquí; no se enumeran ni borran archivos ajenos.
            archivo.delete()
            alterado.delete()
            carpeta.delete()
        }

        probar("Eliminar una cuenta QA autenticada conserva las otras cuentas y sus datos") {
            val clave = "Cuenta descartable $sufijo 2026"
            val descartable = cuentas.crear("Descartable", "qa_descartar_$sufijo", clave.toCharArray())
            cuentasCreadas.add(descartable)
            try {
                sesion.entrar(descartable)
                EliminadorCuenta(aplicacion).eliminarActual(clave.toCharArray())
                check(cuentas.obtener(descartable.id) == null)
                cuentasCreadas.remove(descartable)
                check(cuentas.obtener(cuentaA.id) != null && cuentas.obtener(cuentaB.id) != null)
            } finally {
                // Si el borrado quedó marcado, la limpieza general no debe intentar abrir esa cuenta.
                if (EliminadorCuenta.estaPendiente(aplicacion, descartable.id)) {
                    cuentasCreadas.remove(descartable)
                }
                sesion.entrar(cuentaA)
            }
            check(RepositorioTareas(aplicacion).obtener(normal.id)?.titulo == normal.titulo)
        }
    }

    /**
     * Revisa únicamente la reprogramación en una cuenta nueva de pruebas.
     * Guarda estados que simulan un aviso recibido: no espera alarmas ni certifica sonido real.
     * onStart la llama con la opción solo=reprogramacion; la sesión original se restaura al salir.
     */
    private fun verificarReprogramacion() {
        val sufijo = UUID.randomUUID().toString().take(8)
        lateinit var cuenta: CuentaLocal

        if (!probar("Se prepara una cuenta exclusiva para reprogramación") {
                cuenta = RepositorioCuentas(aplicacion).crear(
                    "Reprogramación",
                    "qa_reprogramar_$sufijo",
                    "Prueba local $sufijo 2026".toCharArray()
                )
                cuentasCreadas.add(cuenta)
                CoordinadorSesion(aplicacion).entrar(cuenta)
            }) {
            return
        }

        val repositorio = RepositorioTareas(aplicacion)
        val gestor = GestorRecordatorios(aplicacion)
        val sistema = aplicacion.getSystemService(NotificationManager::class.java)
        // Usar mañana evita que una ejecución cercana a medianoche deje la hora en el pasado.
        val base = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val ultimaAlerta = System.currentTimeMillis() - 60_000L

        /**
         * Recibe nombre, repetición y fecha; devuelve una tarea propia con un aviso simulado.
         * Guarda directamente el estado para revisar el cambio de hora sin grabar ni reproducir audio.
         */
        fun crearEmitido(
            nombre: String,
            repeticion: Repeticion = Repeticion.UNA_VEZ,
            fecha: LocalDate = base.toLocalDate()
        ): Tarea {
            val tarea = Tarea(
                titulo = "QA $nombre $sufijo",
                fecha = fecha,
                hora = base.toLocalTime(),
                repeticion = repeticion,
                vozActiva = false
            )
            repositorio.guardar(tarea)
            repositorio.guardarEstado(
                repositorio.estado(tarea.id, fecha).copy(
                    avisosEmitidos = 1,
                    ultimaAlerta = ultimaAlerta
                )
            )
            return tarea
        }

        probar("Editar un aviso pendiente reinicia sus avisos y conserva la última activación") {
            val tarea = crearEmitido("pendiente").copy(hora = base.plusHours(1).toLocalTime())
            gestor.guardar(tarea)
            val estado = repositorio.estado(tarea.id, tarea.fecha)
            check(estado.avisosEmitidos == 0 && estado.ultimaAlerta == ultimaAlerta)
            check(!estado.completada && !estado.seguimientoDetenido && !estado.alarmaEsSeguimiento)
            check(estado.alarmaProgramadaPara == base.plusHours(1).toInstant().toEpochMilli())
        }

        probar("Editar una tarea azul conserva su estado hasta volver a marcarla pendiente") {
            val original = crearEmitido("azul")
            gestor.marcarCompletada(original.id, original.fecha, true)
            val editada = original.copy(hora = base.plusHours(1).toLocalTime())
            gestor.guardar(editada)
            val completada = repositorio.estado(editada.id, editada.fecha)
            check(completada.completada && completada.alarmaProgramadaPara == null)
            check(completada.ultimaAlerta == ultimaAlerta)
            gestor.marcarCompletada(editada.id, editada.fecha, false)
            val pendiente = repositorio.estado(editada.id, editada.fecha)
            check(!pendiente.completada && pendiente.avisosEmitidos == 0)
            check(pendiente.alarmaProgramadaPara == base.plusHours(1).toInstant().toEpochMilli())
        }

        probar("Desmarcar una rutina prepara esa fecha aunque el día siguiente ya esté programado") {
            val tarea = crearEmitido("rutina", Repeticion.DIARIA)
            gestor.marcarCompletada(tarea.id, tarea.fecha, true)
            val siguiente = repositorio.estado(tarea.id, tarea.fecha.plusDays(1)).alarmaProgramadaPara
            check(siguiente == base.plusDays(1).toInstant().toEpochMilli())
            gestor.marcarCompletada(tarea.id, tarea.fecha, false)
            val reabierta = repositorio.estado(tarea.id, tarea.fecha)
            check(reabierta.alarmaProgramadaPara == base.toInstant().toEpochMilli())
            check(reabierta.avisosEmitidos == 0 && reabierta.ultimaAlerta == ultimaAlerta)
            check(repositorio.estado(tarea.id, tarea.fecha.plusDays(1)).alarmaProgramadaPara == siguiente)
        }

        probar("Desmarcar una tarea cuya hora pasó no programa un aviso inmediato") {
            val tarea = crearEmitido("pasada", fecha = LocalDate.now().minusDays(1))
            gestor.marcarCompletada(tarea.id, tarea.fecha, true)
            gestor.marcarCompletada(tarea.id, tarea.fecha, false)
            val estado = repositorio.estado(tarea.id, tarea.fecha)
            check(!estado.completada && estado.alarmaProgramadaPara == null)
            check(estado.avisosEmitidos == 1 && estado.ultimaAlerta == ultimaAlerta)
        }

        probar("El interruptor real de Detalles completa y reabre la tarea de la cuenta activa") {
            val tarea = crearEmitido("interruptor")
            gestor.guardar(tarea.copy(hora = base.plusHours(1).toLocalTime()))
            val intento = Intent(aplicacion, ActividadDetalleTarea::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, cuenta.id)
                .putExtra("tarea_id", tarea.id)
                .putExtra("fecha_ocurrencia", tarea.fecha.toString())
            val pantalla = startActivitySync(intento)

            try {
                waitForIdleSync()
                runOnMainSync {
                    val raiz = pantalla.findViewById<View>(android.R.id.content)
                    val interruptor = checkNotNull(buscarInterruptor(raiz))
                    check(!interruptor.isChecked)
                    interruptor.performClick()
                }
                check(EspacioPersonal.claveActual(aplicacion) == cuenta.id)
                check(repositorio.estado(tarea.id, tarea.fecha).completada)
                waitForIdleSync()
                runOnMainSync {
                    val raiz = pantalla.findViewById<View>(android.R.id.content)
                    val interruptor = checkNotNull(buscarInterruptor(raiz))
                    check(interruptor.isChecked)
                    interruptor.performClick()
                }
                val estado = repositorio.estado(tarea.id, tarea.fecha)
                check(!estado.completada && estado.avisosEmitidos == 0)
                check(estado.alarmaProgramadaPara == base.plusHours(1).toInstant().toEpochMilli())
                check(repositorio.obtener("-1") == null)
            } finally {
                runOnMainSync { pantalla.finish() }
                waitForIdleSync()
            }
        }

        probar("Completar transforma el aviso visible en un registro sin acciones y con su hora original") {
            val tarea = crearEmitido("registro")
            val etiqueta = "${cuenta.id}|${tarea.id}|${tarea.fecha}"
            NotificacionesAviso.mostrar(aplicacion, tarea, tarea.fecha, false, propietario = cuenta.id)
            esperarCondicion("No apareció el aviso inicial con sus acciones.") {
                notificacionDe(sistema, etiqueta)?.actions?.size == 3
            }
            check(notificacionDe(sistema, etiqueta)?.`when` == ultimaAlerta)
            gestor.marcarCompletada(tarea.id, tarea.fecha, true)
            esperarCondicion("El aviso no se convirtió en un registro sin acciones.") {
                val aviso = notificacionDe(sistema, etiqueta)
                aviso != null && aviso.category == Notification.CATEGORY_STATUS &&
                    aviso.actions.isNullOrEmpty() && aviso.`when` == ultimaAlerta
            }
        }

        probar("Eliminar una tarea retira también su notificación de registro") {
            val tarea = crearEmitido("eliminar")
            val etiqueta = "${cuenta.id}|${tarea.id}|${tarea.fecha}"
            NotificacionesAviso.mostrar(aplicacion, tarea, tarea.fecha, false, propietario = cuenta.id)
            esperarCondicion("No apareció el aviso que se iba a eliminar.") {
                notificacionDe(sistema, etiqueta) != null
            }
            gestor.marcarCompletada(tarea.id, tarea.fecha, true)
            esperarCondicion("No apareció el registro que se iba a eliminar.") {
                notificacionDe(sistema, etiqueta)?.category == Notification.CATEGORY_STATUS
            }
            gestor.eliminar(tarea.id, tarea.fecha, true)
            esperarCondicion("La notificación siguió visible después de eliminar.") {
                notificacionDe(sistema, etiqueta) == null
            }
            check(repositorio.obtener(tarea.id) == null)
        }

        probar("Editar no hace reaparecer una notificación que la persona ya descartó") {
            val tarea = crearEmitido("descartada")
            val etiqueta = "${cuenta.id}|${tarea.id}|${tarea.fecha}"
            NotificacionesAviso.mostrar(aplicacion, tarea, tarea.fecha, false, propietario = cuenta.id)
            esperarCondicion("No apareció el aviso que se iba a descartar.") {
                notificacionDe(sistema, etiqueta) != null
            }
            sistema.cancel(etiqueta, 1)
            esperarCondicion("Android todavía no retiró el aviso descartado.") {
                notificacionDe(sistema, etiqueta) == null
            }
            gestor.guardar(tarea.copy(hora = base.plusHours(1).toLocalTime()))
            // Se vigila un margen breve: las notificaciones se actualizan fuera del hilo de la prueba.
            val limite = SystemClock.elapsedRealtime() + 500L
            while (SystemClock.elapsedRealtime() < limite) {
                check(notificacionDe(sistema, etiqueta) == null) {
                    "Editar hizo reaparecer el aviso descartado."
                }
                SystemClock.sleep(50L)
            }
        }
    }

    /**
     * Busca el interruptor real dentro de una pantalla y devuelve el primero, o null si no existe.
     * Recorre los contenedores, que son vistas capaces de reunir otras vistas; no cambia su contenido.
     */
    private fun buscarInterruptor(vista: View): Switch? {
        if (vista is Switch) {
            return vista
        }
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                val encontrado = buscarInterruptor(vista.getChildAt(indice))
                if (encontrado != null) {
                    return encontrado
                }
            }
        }
        return null
    }

    /**
     * Recibe Android y la etiqueta de una tarea; devuelve su notificación visible, o null.
     * Las comprobaciones la usan para verificar la publicación sin abrir ni pulsar avisos personales.
     */
    private fun notificacionDe(sistema: NotificationManager, etiqueta: String): Notification? {
        return sistema.activeNotifications.firstOrNull {
            it.tag == etiqueta && it.id == 1
        }?.notification
    }

    /**
     * Espera como máximo tres segundos a que Android refleje una actualización de notificaciones.
     * Recibe el mensaje de error y la condición; devuelve sin datos o hace fallar ese caso al agotarse.
     */
    private fun esperarCondicion(mensaje: String, condicion: () -> Boolean) {
        val limite = SystemClock.elapsedRealtime() + 3_000L
        while (!condicion() && SystemClock.elapsedRealtime() < limite) {
            SystemClock.sleep(50L)
        }
        check(condicion()) { mensaje }
    }

    /** Espera hasta treinta segundos por una comprobación real de contraseña, sin simular su resultado. */
    private fun esperarCondicionLarga(mensaje: String, condicion: () -> Boolean) {
        val limite = SystemClock.elapsedRealtime() + 30_000L
        while (!condicion()) {
            check(SystemClock.elapsedRealtime() < limite) { mensaje }
            SystemClock.sleep(100L)
        }
    }

    /** Revisa frases y duraciones sin grabar sonido ni cambiar ninguna tarea. */
    private fun verificarInterprete() {
        val ejemplos = listOf(
            Triple("recuérdamelo en treinta minutos", AccionVozEnfoque.POSPONER, 30),
            Triple("recuérdamelo en una hora", AccionVozEnfoque.POSPONER, 60),
            Triple("estoy ocupado, avísame en dos horas", AccionVozEnfoque.POSPONER, 120),
            Triple("continuamos quince minutos", AccionVozEnfoque.CONTINUAR, 15),
            Triple("continuamos una hora y media", AccionVozEnfoque.CONTINUAR, 90),
            Triple("no he terminado", AccionVozEnfoque.CONTINUAR, null),
            Triple("todavía no", AccionVozEnfoque.CONTINUAR, null),
            Triple("ya terminé", AccionVozEnfoque.COMPLETAR, null),
            Triple("pausar", AccionVozEnfoque.PAUSAR, null),
            Triple("sí", AccionVozEnfoque.CONFIRMAR, null),
            Triple("no", AccionVozEnfoque.NEGAR, null),
            Triple("", AccionVozEnfoque.DESCONOCIDA, null),
        )
        ejemplos.forEach { (frase, accion, minutos) ->
            probar("La frase «$frase» interpreta $accion y $minutos minutos") {
                val orden = InterpreteVozEnfoque.interpretar(frase)
                check(orden.accion == accion && orden.minutos == minutos) {
                    "Esperado $accion/$minutos; recibido ${orden.accion}/${orden.minutos}."
                }
            }
        }
    }

    /**
     * Crea dos cuentas exclusivas de esta ejecución y prueba contraseñas, rutinas y aislamiento.
     * Las claves son datos de prueba nuevos; no se leen ni se muestran contraseñas del usuario.
     */
    private fun verificarCuentasYAgenda() {
        val repositorioCuentas = RepositorioCuentas(aplicacion)
        val sufijo = UUID.randomUUID().toString().take(8)
        val clave = "Prueba local $sufijo 2026"
        lateinit var cuentaA: CuentaLocal
        lateinit var cuentaB: CuentaLocal

        val preparadas = probar("Registro de dos cuentas locales separadas") {
            cuentaA = repositorioCuentas.crear("Verificación A", "qa_a_$sufijo", clave.toCharArray())
            cuentasCreadas.add(cuentaA)
            cuentaB = repositorioCuentas.crear("Verificación B", "qa_b_$sufijo", clave.toCharArray())
            cuentasCreadas.add(cuentaB)
            check(cuentaA.id != cuentaB.id)
        }
        if (!preparadas) {
            return
        }

        probar("La contraseña correcta permite entrar, incluso usando mayúsculas en el usuario") {
            val recibida = repositorioCuentas.autenticar(cuentaA.usuario.uppercase(Locale.ROOT), clave.toCharArray())
            check(recibida?.id == cuentaA.id)
        }
        probar("Una contraseña incorrecta no permite entrar") {
            check(repositorioCuentas.autenticar(cuentaA.usuario, "Esta clave es incorrecta".toCharArray()) == null)
        }
        probar("Se rechaza un usuario duplicado sin distinguir mayúsculas") {
            esperarRechazo {
                repositorioCuentas.crear("Duplicada", cuentaA.usuario.uppercase(Locale.ROOT), clave.toCharArray())
            }
        }

        val sesion = CoordinadorSesion(aplicacion)
        sesion.entrar(cuentaA)
        val repositorioA = RepositorioTareas(aplicacion)
        val gestorTareas = GestorRecordatorios(aplicacion)
        val momento = ZonedDateTime.now().plusMinutes(5).withSecond(0).withNano(0)
        val rutina = Tarea(
            titulo = "Rutina de verificación $sufijo",
            fecha = momento.toLocalDate(),
            hora = momento.toLocalTime(),
            repeticion = Repeticion.DIARIA,
            vozActiva = false,
        )

        probar("Una rutina diaria se guarda y tiene un aviso programado") {
            gestorTareas.guardar(rutina)
            check(repositorioA.obtener(rutina.id)?.titulo == rutina.titulo)
            check(repositorioA.estado(rutina.id, rutina.fecha).alarmaProgramadaPara != null)
        }
        probar("Completar una fecha no completa la siguiente fecha de una rutina") {
            gestorTareas.marcarCompletada(rutina.id, rutina.fecha, true)
            check(repositorioA.estado(rutina.id, rutina.fecha).completada)
            check(!repositorioA.estado(rutina.id, rutina.fecha.plusDays(1)).completada)
            check(repositorioA.estado(rutina.id, rutina.fecha).alarmaProgramadaPara == null)
        }
        probar("Una fecha completada puede volver a marcarse pendiente") {
            gestorTareas.marcarCompletada(rutina.id, rutina.fecha, false)
            check(!repositorioA.estado(rutina.id, rutina.fecha).completada)
        }
        probar("Posponer guarda la hora nueva y prepara el aviso de esa fecha") {
            val antes = System.currentTimeMillis()
            gestorTareas.posponer(rutina.id, rutina.fecha, 30)
            val estado = repositorioA.estado(rutina.id, rutina.fecha)
            // Se conserva una copia local para que Kotlin pueda comprobar que no es null.
            // El estado viene del módulo principal y no permite esa comprobación sobre dos lecturas.
            val pospuestaHasta = estado.pospuestaHasta
            check(pospuestaHasta != null && pospuestaHasta >= antes + 30 * 60_000L)
            check(estado.alarmaProgramadaPara == estado.pospuestaHasta)
        }
        probar("Detener seguimiento cancela los avisos de esa fecha") {
            gestorTareas.detenerSeguimiento(rutina.id, rutina.fecha)
            val estado = repositorioA.estado(rutina.id, rutina.fecha)
            check(estado.seguimientoDetenido && estado.alarmaProgramadaPara == null)
        }
        probar("Eliminar solo una fecha conserva la rutina y sus otras fechas") {
            gestorTareas.eliminar(rutina.id, rutina.fecha, false)
            check(repositorioA.obtener(rutina.id) != null)
            check(repositorioA.estado(rutina.id, rutina.fecha).omitida)
            check(!repositorioA.estado(rutina.id, rutina.fecha.plusDays(1)).omitida)
        }
        probar("Las tareas únicas y por días de semana corresponden solo a sus fechas") {
            val hoy = LocalDate.now()
            val unica = Tarea(titulo = "Única", fecha = hoy, hora = LocalTime.NOON)
            check(unica.correspondeA(hoy) && !unica.correspondeA(hoy.plusDays(1)))
            val semanal = unica.copy(repeticion = Repeticion.DIAS_SEMANA, diasSemana = setOf(hoy.dayOfWeek.value))
            check(semanal.correspondeA(hoy) && semanal.correspondeA(hoy.plusDays(7)))
            check(!semanal.correspondeA(hoy.plusDays(1)) && !semanal.correspondeA(hoy.minusDays(7)))
        }

        val repositorioEnfoqueA = RepositorioEnfoque(aplicacion)
        val gestorEnfoqueA = GestorEnfoque(aplicacion)
        val actividad = gestorEnfoqueA.crear("Enfoque de verificación $sufijo", System.currentTimeMillis() + 3_600_000L, 2)
        probar("La segunda cuenta no puede leer las tareas ni Enfoque de la primera") {
            sesion.entrar(cuentaB)
            check(RepositorioTareas(aplicacion).listar().isEmpty())
            check(RepositorioEnfoque(aplicacion).listar().isEmpty())
            esperarRechazo { repositorioA.listar() }
            esperarRechazo { repositorioEnfoqueA.listar() }
        }
        probar("Al volver a la primera cuenta se conservan sus tareas y su sesión de Enfoque") {
            sesion.entrar(cuentaA)
            check(RepositorioTareas(aplicacion).obtener(rutina.id) != null)
            check(RepositorioEnfoque(aplicacion).obtener(actividad.id)?.estado == EstadoEnfoque.PENDIENTE)
        }

        verificarEnfoque(actividad.id)
    }

    /**
     * Usa el gestor real: comenzar, pausar, retomar, posponer y completar.
     * La prueba de un minuto espera la alarma de Android; no llama a la recuperación de pantalla.
     */
    private fun verificarEnfoque(id: String) {
        val gestor = GestorEnfoque(aplicacion)
        val repositorio = RepositorioEnfoque(aplicacion)
        var pendienteTrasPausa = 0L
        var acumuladoTrasPausa = 0L

        probar("Crear Enfoque no inicia el contador ni completa la actividad") {
            val inicial = checkNotNull(repositorio.obtener(id))
            check(inicial.estado == EstadoEnfoque.PENDIENTE && inicial.finEnElapsedMs == null)
        }
        probar("Comenzar pone un bloque en curso y rechaza dos sesiones simultáneas") {
            val iniciada = gestor.iniciar(id)
            check(iniciada.estado == EstadoEnfoque.EN_CURSO && iniciada.finEnElapsedMs != null)
            val otra = gestor.crear("Conflicto de verificación", System.currentTimeMillis() + 3_600_000L, 1)
            esperarRechazo { gestor.iniciar(otra.id) }
            gestor.cancelar(otra.id)
        }
        probar("Pausar conserva minutos pendientes y el tiempo ya trabajado") {
            SystemClock.sleep(800L)
            val pausada = gestor.pausar(id)
            check(pausada.estado == EstadoEnfoque.PAUSADA && pausada.finEnElapsedMs == null)
            check(pausada.restanteMs in 1L until 120_000L && pausada.acumuladoTrabajadoMs > 0L)
            pendienteTrasPausa = pausada.restanteMs
            acumuladoTrasPausa = pausada.acumuladoTrabajadoMs
            SystemClock.sleep(300L)
            check(gestor.restanteMs(pausada) == pendienteTrasPausa)
        }
        probar("Retomar mantiene el tiempo que quedaba en pausa") {
            val retomada = gestor.continuar(id)
            check(retomada.estado == EstadoEnfoque.EN_CURSO && retomada.restanteMs == pendienteTrasPausa)
            check(retomada.acumuladoTrabajadoMs == acumuladoTrasPausa)
        }
        probar("Posponer Enfoque detiene el bloque y conserva el trabajo acumulado") {
            val pospuesta = gestor.posponer(id, 30)
            check(pospuesta.estado == EstadoEnfoque.PENDIENTE && pospuesta.finEnElapsedMs == null)
            check(pospuesta.restanteMs in 1L..pendienteTrasPausa)
            check(pospuesta.acumuladoTrabajadoMs >= acumuladoTrasPausa)
        }
        probar("Completar detiene el reloj y deja un único evento de finalización") {
            gestor.iniciar(id)
            val completada = gestor.completar(id)
            gestor.completar(id)
            check(completada.estado == EstadoEnfoque.COMPLETADA && completada.finEnEpochMs == null)
            check(repositorio.eventos().count { it.actividadId == id && it.tipo == TipoEventoEnfoque.COMPLETADA } == 1)
        }

        if (argumentos.getString("incluir_espera", "true") == "false") {
            informar("OMITIDA: alarma real de un minuto por opción incluir_espera=false.")
            return
        }
        val audioPrueba = argumentos.getString("audio_prueba_uri")
        val momentoNormal = ZonedDateTime.now().plusSeconds(90).withSecond(0).withNano(0)
        val avisoNormal = Tarea(
            titulo = "Alarma normal real de verificación",
            fecha = momentoNormal.toLocalDate(),
            hora = momentoNormal.toLocalTime(),
            vozActiva = false,
            musicaUri = audioPrueba,
            musicaNombre = if (audioPrueba == null) "" else "Audio exclusivo de prueba",
        )
        val gestorNormal = GestorRecordatorios(aplicacion)
        val repositorioNormal = RepositorioTareas(aplicacion)
        gestorNormal.guardar(avisoNormal)
        val temporizada = gestor.crear("Alarma real de un minuto", System.currentTimeMillis() + 3_600_000L, 1)
        gestor.iniciar(temporizada.id)
        informar("FASE_BLOQUEO: Enfoque termina en 60 segundos y el aviso normal está programado para ${momentoNormal.toLocalTime()}. Puedes bloquear ahora el emulador; no se simula ninguna entrega.")
        probar("La alarma real deja Enfoque esperando respuesta y el silencio no lo completa") {
            val limite = SystemClock.elapsedRealtime() + 75_000L
            var estado = repositorio.obtener(temporizada.id)?.estado

            while (estado == EstadoEnfoque.EN_CURSO && SystemClock.elapsedRealtime() < limite) {
                SystemClock.sleep(250L)
                estado = repositorio.obtener(temporizada.id)?.estado
            }
            check(estado == EstadoEnfoque.ESPERANDO_RESPUESTA) {
                "La alarma no llegó en el margen disponible. Estado: $estado. ${gestor.ultimoProblema().orEmpty()}"
            }
            SystemClock.sleep(700L)
            check(repositorio.obtener(temporizada.id)?.estado == EstadoEnfoque.ESPERANDO_RESPUESTA)
            check(repositorio.eventos().count { it.actividadId == temporizada.id && it.tipo == TipoEventoEnfoque.TIEMPO_TERMINADO } == 1)
        }
        probar("Continuar después del final crea otro bloque sin completar automáticamente") {
            val continuada = gestor.continuar(temporizada.id, 1)
            check(continuada.estado == EstadoEnfoque.EN_CURSO && continuada.restanteMs == 60_000L)
            check(continuada.acumuladoTrabajadoMs >= 60_000L)
            check(gestor.completar(temporizada.id).estado == EstadoEnfoque.COMPLETADA)
        }
        probar("El receptor entrega el recordatorio normal sin abrir la agenda") {
            val limite = SystemClock.elapsedRealtime() + 55_000L
            var estado = repositorioNormal.estado(avisoNormal.id, avisoNormal.fecha)

            while (estado.avisosEmitidos == 0 && SystemClock.elapsedRealtime() < limite) {
                SystemClock.sleep(250L)
                estado = repositorioNormal.estado(avisoNormal.id, avisoNormal.fecha)
            }
            check(estado.avisosEmitidos == 1 && estado.ultimaAlerta != null) {
                "El recordatorio normal no fue recibido en el margen disponible. Avisos registrados: ${estado.avisosEmitidos}."
            }
        }
        if (audioPrueba != null) {
            informar("MUSICA_PRUEBA: se conservan trece segundos para que termine el archivo de audio de prueba antes de cancelar sus avisos. Esto no certifica que se haya oído el sonido.")
            SystemClock.sleep(13_000L)
        }
    }

    /**
     * Cancela alarmas exclusivamente dentro de las cuentas creadas aquí y repone la entrada original.
     * Los datos de prueba quedan identificados por sus usuarios qa_; no se borran datos de nadie.
     */
    private fun restaurarSesion(original: String?) {
        val coordinador = CoordinadorSesion(aplicacion)
        var falloLimpieza: Throwable? = null

        try {
            cuentasCreadas.forEach { cuenta ->
                try {
                    coordinador.entrar(cuenta)
                    GestorRecordatorios(aplicacion).cancelarTodo()
                    GestorEnfoque(aplicacion).cancelarAlarmasActuales()
                    // Los horarios QA tampoco deben reaparecer al restaurar la sesión original.
                    val descanso = com.nowel.agendavip.datos.descanso.RepositorioDescanso(aplicacion)
                    descanso.guardarConfiguracion(descanso.configuracion().copy(activado = false))
                    com.nowel.agendavip.recordatorios.descanso.GestorDescanso.actualizar(aplicacion)
                } catch (error: Throwable) {
                    falloLimpieza = falloLimpieza ?: error
                }
            }
        } finally {
            when (original) {
                null -> coordinador.cerrarSesion()
                EspacioPersonal.INVITADO -> coordinador.entrarComoInvitado()
                else -> {
                    val anterior = checkNotNull(RepositorioCuentas(aplicacion).obtener(original)) {
                        "No se encontró la cuenta que estaba abierta antes de las pruebas."
                    }
                    coordinador.entrar(anterior)
                }
            }
        }
        check(EspacioPersonal.claveActual(aplicacion) == original) { "No se restauró la sesión original." }
        falloLimpieza?.let { throw it }
    }

    /** Recibe un caso y su operación; informa de su resultado sin detener los demás casos independientes. */
    private fun probar(nombre: String, operacion: () -> Unit): Boolean {
        return try {
            operacion()
            aprobadas += 1
            informar("OK: $nombre")
            true
        } catch (error: Throwable) {
            registrarFallo(nombre, error)
            false
        }
    }

    /** Exige que una operación inválida lance un rechazo; un éxito inesperado hace fallar el caso. */
    private fun esperarRechazo(operacion: () -> Unit) {
        var rechazada = false

        try {
            operacion()
        } catch (_: IllegalArgumentException) {
            rechazada = true
        } catch (_: IllegalStateException) {
            rechazada = true
        }
        check(rechazada) { "Se esperaba un rechazo, pero la operación se permitió." }
    }

    /** Conserva el nombre del caso y la causa completa para poder corregir un fallo concreto. */
    private fun registrarFallo(nombre: String, error: Throwable) {
        fallidas += 1
        informar("ERROR: $nombre\n${error.stackTraceToString()}")
    }

    /** Envía una línea a la consola de instrumentación y la conserva para el informe final. */
    private fun informar(texto: String) {
        informe.append(texto).append('\n')
        sendStatus(0, Bundle().apply { putString("stream", "$texto\n") })
    }
}
