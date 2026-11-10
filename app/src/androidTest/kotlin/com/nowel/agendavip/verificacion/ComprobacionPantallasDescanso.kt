package com.nowel.agendavip.verificacion

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import com.nowel.agendavip.presentacion.descanso.ActividadDescanso
import com.nowel.agendavip.recordatorios.descanso.GestorDescanso
import com.nowel.agendavip.recordatorios.descanso.NotificacionesDescanso
import com.nowel.agendavip.recordatorios.descanso.ServicioAccesibilidadDescanso
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Recorre pantallas y la capa real de accesibilidad únicamente en un emulador Android.
 * UiAutomation permite inspeccionar el sistema durante pruebas. Su opción DONT_SUPPRESS mantiene
 * funcionando los servicios de accesibilidad, que normalmente quedarían suspendidos al probar.
 * Guarda y restaura las opciones anteriores; no puede ejecutarse en el teléfono personal.
 */
class ComprobacionPantallasDescanso(
    private val instrumentacion: Instrumentation,
    private val aplicacion: Context,
    private val probar: (String, () -> Unit) -> Boolean,
    private val cuentasCreadas: MutableList<CuentaLocal>,
) {
    private val pantallas = mutableListOf<Activity>()
    private lateinit var automatizacion: UiAutomation
    private var cuenta: CuentaLocal? = null
    private var serviciosAnteriores: String? = null
    private var accesibilidadAnterior: String? = null
    private var guardadosAjustes = false
    private var pantallaEstabaActiva = true
    private var editor: Activity? = null
    private val paqueteChrome = "com.android.chrome"
    private val componente = "com.nowel.agendavip/.recordatorios.descanso.ServicioAccesibilidadDescanso"
    private val mensajeQa = "Termina lo que estás viendo y prepara tu descanso QA."

    /**
     * El runner la llama con solo=pantallas_descanso. Crea una cuenta propia y prepara Chrome.
     * Las pruebas pulsan botones reales de Agenda. El estado original del sistema se repone siempre.
     */
    fun ejecutar() {
        if (!esEmulador()) {
            probar("Descanso UI: la revisión automática queda limitada al emulador") {
                error("Esta suite modifica ajustes de accesibilidad QA y no se ejecuta en un celular físico.")
            }
            return
        }
        automatizacion = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        pantallaEstabaActiva = aplicacion.getSystemService(PowerManager::class.java).isInteractive
        try {
            if (!preparar()) {
                return
            }
            verificarFormulario()
            if (!habilitarServicio()) {
                return
            }
            verificarCapa()
            verificarSilencio()
            verificarExcepciones()
            verificarInicio()
            verificarPantallaApagada()
            verificarPausa()
            verificarFinPausa()
            verificarCancelacion()
            verificarFinReal()
        } finally {
            limpiar()
        }
    }

    /** Evita modificar ajustes en dispositivos físicos, aunque se inicie la instrumentación por error. */
    private fun esEmulador(): Boolean {
        return Build.VERSION.SDK_INT >= 29 &&
            (Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("/sdk_"))
    }

    /** Crea solo datos QA y comprueba que Chrome y las notificaciones están disponibles en el emulador. */
    private fun preparar(): Boolean {
        return probar("Descanso UI: se prepara una cuenta QA sin modificar permisos personales") {
            check(aplicacion.packageManager.getLaunchIntentForPackage(paqueteChrome) != null) {
                "El emulador no tiene Chrome para comprobar una aplicación elegida real."
            }
            check(aplicacion.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                "Concede previamente las notificaciones de Agenda.VIP en este emulador."
            }
            ejecutarShell("input keyevent 224")
            ejecutarShell("wm dismiss-keyguard")
            val sufijo = UUID.randomUUID().toString().take(8)
            val nueva = RepositorioCuentas(aplicacion).crear(
                "Descanso pantalla QA",
                "qa_descanso_ui_$sufijo",
                "Clave exclusiva de pantalla $sufijo 2026".toCharArray(),
            )
            cuenta = nueva
            cuentasCreadas += nueva
            CoordinadorSesion(aplicacion).entrar(nueva)
        }
    }

    /**
     * Abre la configuración, escribe horas y mensaje, gira de verdad y comprueba el borrador.
     * Después rechaza una hora inválida y guarda la versión válida con dos pulsaciones consecutivas.
     */
    private fun verificarFormulario() {
        probar("Descanso UI: girar conserva el borrador y Guardar valida las horas sin duplicar") {
            val abierta = instrumentacion.startActivitySync(
                Intent(aplicacion, ActividadDescanso::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, checkNotNull(cuenta).id),
            )
            pantallas += abierta
            editor = abierta
            esperar("No terminó de cargar el formulario de Descanso.") {
                enPantalla { abierta.findViewById<View>(R.id.descanso_guardar).isEnabled }
            }
            val orientacion = enPantalla { abierta.resources.configuration.orientation }
            enPantalla {
                abierta.findViewById<EditText>(R.id.descanso_inicio_hora).setText("9:15")
                abierta.findViewById<RadioGroup>(R.id.descanso_inicio_periodo).check(R.id.descanso_inicio_pm)
                abierta.findViewById<EditText>(R.id.descanso_fin_hora).setText("5:45")
                abierta.findViewById<RadioGroup>(R.id.descanso_fin_periodo).check(R.id.descanso_fin_am)
                abierta.findViewById<EditText>(R.id.descanso_mensaje).setText(mensajeQa)
                abierta.findViewById<Switch>(R.id.descanso_activado).isChecked = false
                abierta.findViewById<Switch>(R.id.descanso_restriccion).isChecked = false
                abierta.findViewById<Switch>(R.id.descanso_voz).isChecked = false
            }
            val destino = if (orientacion == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val girada = girar(abierta, destino)
            comprobarBorrador(girada)
            val final = girar(
                girada,
                if (orientacion == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                },
            )
            comprobarBorrador(final)
            enPantalla {
                final.findViewById<EditText>(R.id.descanso_inicio_hora).setText("13:77")
                final.findViewById<View>(R.id.descanso_guardar).performClick()
                check(final.findViewById<EditText>(R.id.descanso_inicio_hora).error != null)
            }
            check(RepositorioDescanso(aplicacion).configuracion() == ConfiguracionDescanso())
            enPantalla {
                final.findViewById<EditText>(R.id.descanso_inicio_hora).setText("9:15")
                final.findViewById<View>(R.id.descanso_guardar).performClick()
                final.findViewById<View>(R.id.descanso_guardar).performClick()
            }
            esperar("Guardar no confirmó el borrador válido.") {
                RepositorioDescanso(aplicacion).configuracion().mensaje == mensajeQa
            }
            val guardada = RepositorioDescanso(aplicacion).configuracion()
            check(guardada.inicio == LocalTime.of(21, 15) && guardada.fin == LocalTime.of(5, 45))
            check(!guardada.activado && !guardada.vozActiva)
            capturar("descanso_config_qa.png")
            enPantalla { final.finish() }
        }
    }

    /** Recibe el editor recreado y comprueba los campos antes de confirmar ningún guardado. */
    private fun comprobarBorrador(pantalla: Activity) {
        esperar("La pantalla recreada no terminó de cargar el borrador.") {
            enPantalla { pantalla.findViewById<View>(R.id.descanso_guardar).isEnabled }
        }
        enPantalla {
            check(pantalla.findViewById<EditText>(R.id.descanso_inicio_hora).text.toString() == "9:15")
            check(pantalla.findViewById<RadioGroup>(R.id.descanso_inicio_periodo).checkedRadioButtonId ==
                R.id.descanso_inicio_pm)
            check(pantalla.findViewById<EditText>(R.id.descanso_fin_hora).text.toString() == "5:45")
            check(pantalla.findViewById<EditText>(R.id.descanso_mensaje).text.toString() == mensajeQa)
            check(!pantalla.findViewById<Switch>(R.id.descanso_activado).isChecked)
        }
    }

    /**
     * Conserva las opciones del emulador y añade únicamente el servicio propio de Agenda.VIP.
     * El permiso se activa mediante la identidad shell de pruebas; la app normal nunca lo concede sola.
     */
    private fun habilitarServicio(): Boolean {
        return probar("Descanso UI: se habilita el servicio sin suprimir otros servicios de accesibilidad") {
            serviciosAnteriores = leerAjuste("enabled_accessibility_services")
            accesibilidadAnterior = leerAjuste("accessibility_enabled")
            guardadosAjustes = true
            val servicios = serviciosAnteriores.orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet()
            servicios.add(componente)
            val listaDeseada = servicios.joinToString(":")
            escribirAjuste("enabled_accessibility_services", listaDeseada)
            check(leerAjuste("enabled_accessibility_services") == listaDeseada) {
                "El comando de prueba no guardó la lista exacta de servicios de accesibilidad."
            }
            escribirAjuste("accessibility_enabled", "1")
            check(leerAjuste("accessibility_enabled") == "1") {
                "Android no conservó activada la accesibilidad después de escribir el ajuste."
            }
            esperar("Android no habilitó el servicio de descanso.", 15_000L) {
                ServicioAccesibilidadDescanso.estaHabilitado(aplicacion)
            }
            configurarNocheActual()
        }
    }

    /** Abre una aplicación real elegida y comprueba el mensaje y la capa añadida por el servicio real. */
    private fun verificarCapa() {
        probar("Descanso UI: Chrome elegido queda cubierto con tiempo disponible y mensaje personal") {
            abrirChrome()
            esperarCapa()
            enPantalla {
                val texto = checkNotNull(buscarVista("descanso_bloqueo"))
                    .findViewById<TextView>(R.id.descanso_bloqueo_mensaje).text.toString()
                check(texto.contains("disponibles") && texto.contains(mensajeQa))
            }
            capturar("descanso_bloqueo_qa.png")
        }
    }

    /** Ejecuta el PendingIntent publicado por el aviso y comprueba que silenciar no cancela la noche. */
    private fun verificarSilencio() {
        probar("Descanso UI: Silenciar de la notificación conserva la restricción nocturna") {
            val fecha = nocheActual()
            val propietario = checkNotNull(cuenta).id
            NotificacionesDescanso.publicar(aplicacion, propietario, fecha, mensajeQa, previo = false)
            val aviso = aplicacion.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { notificacion -> notificacion.tag == "$propietario|descanso|noche" }
            val accion = checkNotNull(aviso).notification.actions
                .firstOrNull { it.title.toString() == "Silenciar aviso" }
            checkNotNull(accion).actionIntent.send()
            esperarCapa()
            check(!RepositorioDescanso(aplicacion).estado(fecha).cancelada)
            check(RepositorioDescanso(aplicacion).configuracion().activado)
        }
    }

    /** Comprueba que Ajustes continúa disponible y que volver a Chrome vuelve a aplicar la capa. */
    private fun verificarExcepciones() {
        probar("Descanso UI: Ajustes queda disponible y Chrome vuelve a restringirse al regresar") {
            aplicacion.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            esperar("La capa no dejó disponibles los ajustes esenciales.") { !hayCapa() }
            abrirChrome()
            esperarCapa()
        }
    }

    /** Pulsa la acción real Ir al inicio y comprueba que no cancela el horario ni la próxima restricción. */
    private fun verificarInicio() {
        probar("Descanso UI: Ir al inicio retira la capa sin cancelar el horario") {
            val paqueteInicio = paqueteDeInicio()
            pulsar("descanso_inicio")
            // Quitar la capa y llegar al escritorio son dos eventos distintos de Android.
            // Se espera al escritorio real para no abrir Chrome mientras HOME sigue pendiente.
            esperar("Ir al inicio no dejó visible el escritorio de Android.") {
                !hayCapa() && automatizacion.rootInActiveWindow?.packageName?.toString() == paqueteInicio
            }
            check(!RepositorioDescanso(aplicacion).estado(nocheActual()).cancelada)
            abrirChrome()
            esperarCapa()
        }
    }

    /** Apaga y despierta el emulador; con pantalla apagada no debe conservar una ventana encima. */
    private fun verificarPantallaApagada() {
        probar("Descanso UI: bloquear retira la capa y al volver a Chrome se aplica de nuevo") {
            ejecutarShell("input keyevent 223")
            esperar("Android no apagó la pantalla del emulador.") {
                !aplicacion.getSystemService(PowerManager::class.java).isInteractive
            }
            esperar("La capa continuó añadida con pantalla apagada.") { !hayCapa() }
            ejecutarShell("input keyevent 224")
            ejecutarShell("wm dismiss-keyguard")
            abrirChrome()
            esperarCapa()
        }
    }

    /** Pulsa Pausar y comprueba una única concesión persistida entre instancias del repositorio. */
    private fun verificarPausa() {
        probar("Descanso UI: Pausar concede cinco minutos una sola vez y retira la capa") {
            val fecha = nocheActual()
            val antes = System.currentTimeMillis()
            pulsar("descanso_pausar")
            esperar("Pausar no retiró la capa.") { !hayCapa() }
            val estado = RepositorioDescanso(aplicacion).estado(fecha)
            check(estado.pausasUsadas == 1)
            check(estado.pausaHasta in (antes + 295_000L)..(System.currentTimeMillis() + 300_000L))
            check(!RepositorioDescanso(aplicacion).pausar(fecha, System.currentTimeMillis()))
        }
    }

    /**
     * Acorta únicamente la pausa de la cuenta QA para comprobar el vencimiento sin esperar cinco minutos.
     * La capa debe volver por el servicio real; esta prueba no crea una ventana falsa.
     */
    private fun verificarFinPausa() {
        probar("Descanso UI: al vencer la pausa regresa la capa y desaparece el botón de otra pausa") {
            modificarNocheQa { dato ->
                dato.put("pausa_hasta", System.currentTimeMillis() - 1_000L)
            }
            GestorDescanso.actualizar(aplicacion)
            abrirChrome()
            esperarCapa()
            enPantalla {
                val pausa = buscarVista("descanso_pausar")
                check(pausa == null || pausa.visibility == View.GONE)
            }
        }
    }

    /** Comprueba que el primer toque solo pregunta y que la confirmación suspende esa noche. */
    private fun verificarCancelacion() {
        probar("Descanso UI: cancelar requiere confirmación y suspende únicamente esa noche") {
            val fecha = nocheActual()
            pulsar("descanso_cancelar")
            check(hayCapa())
            check(!RepositorioDescanso(aplicacion).estado(fecha).cancelada)
            enPantalla {
                check(buscarVista("descanso_confirmar_cancelacion")?.isShown == true)
            }
            pulsar("descanso_confirmar_cancelacion")
            esperar("Confirmar la suspensión no retiró la capa.") { !hayCapa() }
            check(RepositorioDescanso(aplicacion).estado(fecha).cancelada)
            check(!RepositorioDescanso(aplicacion).estado(fecha.plusDays(1)).cancelada)
            abrirChrome()
            SystemClock.sleep(500L)
            check(!hayCapa())
        }
    }

    /**
     * Programa un final en el próximo cambio de minuto y espera su paso real con un máximo de 65 segundos.
     * Comprueba tanto la desaparición automática como que no reaparezca al abrir de nuevo Chrome.
     */
    private fun verificarFinReal() {
        probar("Descanso UI: el final real del horario retira automáticamente la restricción") {
            modificarNocheQa { dato ->
                dato.put("cancelada", false)
                    .put("pausa_hasta", 0L)
                    .put("pausas_usadas", 0)
            }
            val ahora = ZonedDateTime.now()
            val fin = ahora.plusMinutes(1).withSecond(0).withNano(0)
            val repositorio = RepositorioDescanso(aplicacion)
            repositorio.guardarConfiguracion(
                repositorio.configuracion().copy(
                    inicio = ahora.minusMinutes(2).toLocalTime().withSecond(0).withNano(0),
                    fin = fin.toLocalTime(),
                ),
            )
            GestorDescanso.actualizar(aplicacion)
            abrirChrome()
            esperarCapa()
            esperar("El fin del horario no retiró la capa de descanso.", 65_000L) {
                !hayCapa() && !ZonedDateTime.now().isBefore(fin)
            }
            abrirChrome()
            SystemClock.sleep(500L)
            check(!hayCapa())
        }
    }

    /** Prepara una noche QA vigente, con Chrome elegido y voz apagada para no reproducir audio de prueba. */
    private fun configurarNocheActual() {
        val ahora = ZonedDateTime.now()
        RepositorioDescanso(aplicacion).guardarConfiguracion(
            ConfiguracionDescanso(
                activado = true,
                inicio = ahora.minusMinutes(2).toLocalTime().withSecond(0).withNano(0),
                fin = ahora.plusMinutes(30).toLocalTime().withSecond(0).withNano(0),
                aplicaciones = setOf(paqueteChrome),
                mensaje = mensajeQa,
                vozActiva = false,
            ),
        )
        GestorDescanso.actualizar(aplicacion)
    }

    /** Devuelve el día de inicio del horario QA; evita confundir la madrugada con otra noche. */
    private fun nocheActual(): LocalDate {
        val repositorio = RepositorioDescanso(aplicacion)
        return checkNotNull(ReglasDescanso.ventanaActual(repositorio.configuracion(), ZonedDateTime.now()))
            .fechaNoche
    }

    /**
     * Recibe una edición de un registro QA y la confirma sin tocar preferencias de otras cuentas.
     * Se usa solo para acortar esperas y preparar un nuevo escenario de fin de horario.
     */
    private fun modificarNocheQa(editar: (JSONObject) -> Unit) {
        check(EspacioPersonal.claveActual(aplicacion) == checkNotNull(cuenta).id)
        val fecha = nocheActual()
        val preferencias = EspacioPersonal(aplicacion).preferencias(RepositorioDescanso.NOMBRE_ARCHIVO)
        val noches = JSONArray(preferencias.getString("noches", "[]"))
        val dato = (0 until noches.length()).map { noches.getJSONObject(it) }
            .firstOrNull { it.getString("fecha") == fecha.toString() }
        checkNotNull(dato) { "La prueba no encontró la noche QA que debía modificar." }
        editar(dato)
        check(preferencias.edit().putString("noches", noches.toString()).commit())
    }

    /**
     * Consulta qué aplicación muestra el escritorio de este emulador y devuelve su paquete.
     * La consulta shell evita depender de la visibilidad de paquetes de la app probada.
     * Solo se usa para esperar el resultado de HOME; no envía otra orden de ir al inicio.
     */
    private fun paqueteDeInicio(): String {
        val salida = ejecutarShell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
        )
        val componente = salida.lineSequence()
            .map { linea -> linea.trim() }
            .lastOrNull { linea -> linea.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) }
        return checkNotNull(componente) {
            "Android no identificó una aplicación de escritorio para comprobar HOME."
        }.substringBefore('/')
    }

    /** Abre el Chrome instalado del emulador; no navega a Internet ni modifica su configuración. */
    private fun abrirChrome() {
        val intento = checkNotNull(aplicacion.packageManager.getLaunchIntentForPackage(paqueteChrome))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        aplicacion.startActivity(intento)
    }

    /** Espera la ventana real del servicio en el mismo proceso de Agenda.VIP. */
    private fun esperarCapa() {
        esperar("No apareció la capa de descanso sobre Chrome.", 15_000L) { hayCapa() }
    }

    /** Devuelve si nuestra capa sigue añadida; no inspecciona contenido de otras aplicaciones. */
    private fun hayCapa(): Boolean = enPantalla {
        buscarVista("descanso_bloqueo")?.isAttachedToWindow == true
    }

    /** Recibe una etiqueta del control y pulsa su acción real en el hilo que dibuja Android. */
    private fun pulsar(etiqueta: String) {
        enPantalla {
            val boton = checkNotNull(buscarVista(etiqueta)) { "No existe el control $etiqueta." }
            check(boton.visibility == View.VISIBLE && boton.isEnabled)
            boton.performClick()
        }
    }

    /** Busca una etiqueta exclusivamente en ventanas del proceso probado; debe llamarse en pantalla. */
    private fun buscarVista(etiqueta: String): View? {
        return WindowInspector.getGlobalWindowViews().asSequence()
            .mapNotNull { raiz -> buscarEnArbol(raiz, etiqueta) }
            .firstOrNull()
    }

    /** Recorre las vistas propias y devuelve la primera con la etiqueta solicitada. */
    private fun buscarEnArbol(vista: View, etiqueta: String): View? {
        if (vista.tag == etiqueta) {
            return vista
        }
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                buscarEnArbol(vista.getChildAt(indice), etiqueta)?.let { return it }
            }
        }
        return null
    }

    /** Recibe editor y orientación; espera que Android entregue una instancia nueva con su borrador. */
    private fun girar(anterior: Activity, orientacion: Int): Activity {
        val monitor = instrumentacion.addMonitor(ActividadDescanso::class.java.name, null, false)
        try {
            enPantalla { anterior.requestedOrientation = orientacion }
            val nueva = checkNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 10_000L)) {
                "Android no recreó la configuración al girar."
            }
            check(nueva !== anterior)
            pantallas += nueva
            editor = nueva
            instrumentacion.waitForIdleSync()
            return nueva
        } finally {
            instrumentacion.removeMonitor(monitor)
        }
    }

    /** Guarda una captura de prueba en la caché privada para que root inspeccione el resultado visual. */
    private fun capturar(nombre: String) {
        check(nombre in setOf("descanso_config_qa.png", "descanso_bloqueo_qa.png"))
        esperar("La ventana de Descanso no terminó de recibir su tamaño antes de la captura.") {
            enPantalla {
                val vista = vistaParaCaptura(nombre)
                vista != null && vista.isAttachedToWindow && vista.isLaidOut &&
                    vista.width > 0 && vista.height > 0
            }
        }
        val fotograma = CountDownLatch(1)
        enPantalla {
            val vista = checkNotNull(vistaParaCaptura(nombre))
            check(vista.viewTreeObserver.isAlive)
            if (vista.isHardwareAccelerated) {
                vista.viewTreeObserver.registerFrameCommitCallback {
                    fotograma.countDown()
                }
            } else {
                val observador = vista.viewTreeObserver
                val dibujado = object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        // El post termina después de dibujar. La captura verifica además la composición real.
                        vista.post {
                            if (observador.isAlive) {
                                observador.removeOnDrawListener(this)
                            }
                            fotograma.countDown()
                        }
                    }
                }
                observador.addOnDrawListener(dibujado)
            }
            vista.postInvalidateOnAnimation()
        }
        check(fotograma.await(4, TimeUnit.SECONDS)) {
            "Android no confirmó un dibujo terminado para $nombre."
        }
        automatizacion.waitForIdle(300L, 4_000L)
        val imagen = if (nombre == "descanso_bloqueo_qa.png") {
            capturarCapaVisible()
        } else {
            checkNotNull(automatizacion.takeScreenshot())
        }
        try {
            File(aplicacion.cacheDir, nombre).outputStream().use { salida ->
                check(imagen.compress(Bitmap.CompressFormat.PNG, 100, salida))
            }
        } finally {
            imagen.recycle()
        }
    }

    /**
     * Devuelve una captura cuyo borde muestra el fondo ciruela real de la capa, no Chrome todavía debajo.
     * La composición de ventanas puede terminar después de onDraw en ventanas sin aceleración gráfica.
     * Las imágenes anteriores se liberan y un límite de cuatro segundos impide esperas indefinidas.
     */
    private fun capturarCapaVisible(): Bitmap {
        val esperado = aplicacion.resources.getColor(R.color.ciruela, null)
        val limite = SystemClock.elapsedRealtime() + 4_000L
        while (true) {
            val imagen = checkNotNull(automatizacion.takeScreenshot())
            val pixel = imagen.getPixel(minOf(5, imagen.width - 1), imagen.height / 2)
            val coincide = kotlin.math.abs(Color.red(pixel) - Color.red(esperado)) <= 8 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(esperado)) <= 8 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(esperado)) <= 8
            if (coincide) {
                return imagen
            }
            imagen.recycle()
            check(SystemClock.elapsedRealtime() < limite) {
                "La captura no mostró la capa ciruela de Descanso después de terminar su dibujo."
            }
            instrumentacion.waitForIdleSync()
        }
    }

    /** Devuelve la ventana propia que debe terminar de dibujarse antes de capturar; se llama en pantalla. */
    private fun vistaParaCaptura(nombre: String): View? {
        return if (nombre == "descanso_bloqueo_qa.png") {
            buscarVista("descanso_bloqueo")
        } else {
            editor?.window?.decorView
        }
    }

    /** Lee un ajuste del emulador; null indica que el campo no existía y debe eliminarse al restaurar. */
    private fun leerAjuste(clave: String): String? {
        val valor = ejecutarShell("settings get secure $clave").trim()
        return valor.takeUnless { it == "null" || it.isEmpty() }
    }

    /**
     * Escribe o elimina un ajuste conocido; recibe el valor original al restaurar el emulador.
     * executeShellCommand divide argumentos sin interpretar comillas como una consola completa.
     * Por eso se valida un alfabeto cerrado y se entrega el componente directamente, sin comillas.
     */
    private fun escribirAjuste(clave: String, valor: String?) {
        check(clave in setOf("enabled_accessibility_services", "accessibility_enabled"))
        if (valor != null) {
            check(valor.matches(Regex("[A-Za-z0-9_.$:/-]+"))) {
                "El ajuste de accesibilidad contiene caracteres no permitidos para esta prueba."
            }
        }
        val comando = if (valor == null) {
            "settings delete secure $clave"
        } else {
            "settings put secure $clave $valor"
        }
        ejecutarShell(comando)
    }

    /** Ejecuta una orden de prueba acotada en el emulador y devuelve su salida sin dejar descriptores abiertos. */
    private fun ejecutarShell(comando: String): String {
        return ParcelFileDescriptor.AutoCloseInputStream(automatizacion.executeShellCommand(comando)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Ejecuta una operación sobre vistas propias en el hilo principal y devuelve su resultado. */
    private fun <T> enPantalla(accion: () -> T): T {
        var resultado: Result<T>? = null
        instrumentacion.runOnMainSync { resultado = runCatching(accion) }
        return checkNotNull(resultado).getOrThrow()
    }

    /** Espera una condición con límite de tiempo; las pausas cortas dejan procesar los eventos de Android. */
    private fun esperar(mensaje: String, maximo: Long = 12_000L, condicion: () -> Boolean) {
        val limite = SystemClock.elapsedRealtime() + maximo
        while (!condicion()) {
            check(SystemClock.elapsedRealtime() < limite) { mensaje }
            SystemClock.sleep(100L)
        }
    }

    /**
     * Se ejecuta también ante fallos: apaga solo la rutina QA, cierra sus pantallas y repone los ajustes.
     * No restaura la sesión exterior: esa responsabilidad pertenece al runner común.
     */
    private fun limpiar() {
        probar("Descanso UI: se retiran datos activos QA y se restauran los ajustes originales") {
            try {
                if (EspacioPersonal.claveActual(aplicacion) == cuenta?.id && cuenta != null) {
                    val repositorio = RepositorioDescanso(aplicacion)
                    repositorio.guardarConfiguracion(repositorio.configuracion().copy(activado = false))
                    GestorDescanso.actualizar(aplicacion)
                    GestorDescanso.detenerPorCambioSesion(aplicacion)
                }
                enPantalla {
                    pantallas.asReversed().forEach { pantalla ->
                        if (!pantalla.isDestroyed && !pantalla.isFinishing) {
                            pantalla.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            pantalla.finish()
                        }
                    }
                }
            } finally {
                if (guardadosAjustes) {
                    escribirAjuste("enabled_accessibility_services", serviciosAnteriores)
                    escribirAjuste("accessibility_enabled", accesibilidadAnterior)
                    check(leerAjuste("enabled_accessibility_services") == serviciosAnteriores)
                    val restaurado = leerAjuste("accessibility_enabled")
                    check(restaurado == accesibilidadAnterior ||
                        (accesibilidadAnterior == null && restaurado == "0")) {
                        "No se recuperó el estado anterior de accesibilidad del emulador."
                    }
                }
                if (!pantallaEstabaActiva) {
                    ejecutarShell("input keyevent 223")
                } else {
                    ejecutarShell("input keyevent 224")
                    ejecutarShell("wm dismiss-keyguard")
                }
            }
            esperar("No se retiró la capa de descanso al terminar.") { !hayCapa() }
        }
    }
}
