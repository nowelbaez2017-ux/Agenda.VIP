package com.nowel.agendavip.recordatorios.descanso

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import com.nowel.agendavip.dominio.descanso.VentanaDescanso
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Recibe únicamente cambios de ventana para reconocer el nombre de la aplicación visible.
 * No consulta árboles de pantalla, textos, capturas ni mensajes de otras aplicaciones.
 * Cuando una app elegida está dentro del horario nocturno, coloca nuestra pantalla por encima.
 * El usuario activa y desactiva este servicio desde los ajustes de Accesibilidad de Android.
 */
class ServicioAccesibilidadDescanso : AccessibilityService() {

    private val principal = Handler(Looper.getMainLooper())
    private var propietario: String? = null
    private var configuracion: ConfiguracionDescanso? = null
    private var esenciales: Set<String> = emptySet()
    private var ultimoPaquete: String? = null
    private var capa: View? = null
    private var fechaCapa: LocalDate? = null
    private var paqueteCapa: String? = null
    private var fechaUso: LocalDate? = null
    private var duenoUso: String? = null
    private var inicioUso = 0L
    private var conectado = false
    private var receptorRegistrado = false
    private var proximaLectura = 0L

    @Volatile
    private var invalidada = false

    private val reloj = object : Runnable {
        /** Revisa horario y pausa aunque la aplicación de debajo no cambie de pantalla. */
        override fun run() {
            revisar()
        }
    }

    private val pantalla = object : BroadcastReceiver() {
        /**
         * Al apagar se cierra el intervalo de uso y se retira la capa. No se cuenta el sueño.
         * Al desbloquear se espera un cambio de ventana para reconocer la aplicación actual.
         */
        override fun onReceive(contexto: Context, intento: Intent) {
            if (intento.action == Intent.ACTION_SCREEN_OFF) {
                guardarUsoPendiente()
                ultimoPaquete = null
                retirarCapa()
                ServicioVozDescanso.detener(this@ServicioAccesibilidadDescanso)
            }
            revisar()
        }
    }

    /**
     * Android la llama solo después de que la persona haya habilitado el servicio.
     * Guarda una referencia débil, es decir, que no impide liberar el servicio al desactivarlo.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        conectado = true
        actual = WeakReference(this)
        esenciales = ProteccionesDescanso.paquetesEsenciales(this)
        if (!receptorRegistrado) {
            val filtro = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pantalla, filtro, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pantalla, filtro)
            }
            receptorRegistrado = true
        }
        GestorDescanso.reprogramar(this)
        revisar(forzarLectura = true)
    }

    /**
     * Android entrega un cambio de ventana. Solo se usa packageName, el nombre de la app.
     * Se ignoran los eventos que produce nuestra propia capa para que no se oculte a sí misma.
     * Ninguna llamada de esta función pide leer el contenido de la aplicación de debajo.
     */
    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (!conectado || evento?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val paquete = evento.packageName?.toString() ?: return
        val clase = evento.className?.toString().orEmpty()
        if (paquete == packageName && !clase.startsWith(packageName)) {
            return
        }
        guardarUsoPendiente()
        ultimoPaquete = paquete
        revisar(desdeEvento = true)
    }

    /**
     * Comprueba cuenta, horario, pantalla y excepciones antes de permitir una restricción.
     * El reloj, los cambios de ventana y Configuración la llaman; no devuelve un dato.
     * Hablar se permite solo ante una interacción nueva, nunca por una recuperación tras reiniciar.
     */
    private fun revisar(desdeEvento: Boolean = false, forzarLectura: Boolean = false) {
        principal.removeCallbacks(reloj)
        if (!conectado) {
            return
        }
        try {
            val actualDueno = EspacioPersonal.claveActual(this)
            if (invalidada || actualDueno == null || com.nowel.agendavip.seguridad.EstadoArranque.problema != null) {
                guardarUsoPendiente()
                retirarCapa()
                configuracion = null
                propietario = null
                return
            }
            if (actualDueno != propietario || forzarLectura || SystemClock.elapsedRealtime() >= proximaLectura) {
                guardarUsoPendiente()
                if (actualDueno != propietario) {
                    retirarCapa()
                    propietario = actualDueno
                }
                configuracion = RepositorioDescanso(this).configuracion()
                proximaLectura = SystemClock.elapsedRealtime() + 30_000L
            }
            val opciones = configuracion ?: return
            val ahora = ZonedDateTime.now()
            val ventana = if (opciones.activado) ReglasDescanso.ventanaActual(opciones, ahora) else null
            val repositorio = RepositorioDescanso(this)
            val estado = ventana?.let { repositorio.estado(it.fechaNoche) }
            val disponible = pantallaDisponible()
            val paquete = ultimoPaquete
            val elegida = paquete != null && paquete in opciones.aplicaciones &&
                paquete !in esenciales && !ProteccionesDescanso.esComponenteProtegido(paquete)
            val corresponde = ventana != null && estado?.cancelada == false && disponible

            // El uso se mide únicamente en apps elegidas, durante el horario y sin nuestra capa.
            // Una pausa excepcional sigue contando como tiempo de distracción; una noche cancelada no.
            if (corresponde && elegida && capa == null) {
                iniciarMedicion(ventana!!.fechaNoche, actualDueno)
            } else {
                guardarUsoPendiente()
            }
            val restringir = corresponde && elegida && opciones.restriccionActiva &&
                (estado?.pausaHasta ?: 0L) <= System.currentTimeMillis()
            if (!restringir) {
                retirarCapa()
                if (ventana == null || estado?.cancelada == true || !opciones.activado) {
                    NotificacionesDescanso.cancelar(this, actualDueno)
                }
            } else {
                mostrarCapa(ventana!!, opciones, paquete!!, actualDueno, desdeEvento)
            }
        } catch (_: RuntimeException) {
            // Si los datos no son válidos, se conserva el control del celular y el archivo original.
            guardarUsoPendiente()
            retirarCapa()
            configuracion = null
        } finally {
            if (conectado) {
                // Un segundo mientras cubrimos una app o hay una pausa; treinta fuera de ese caso.
                // No se mantiene despierto el dispositivo ni se carga un modelo de inteligencia artificial.
                val demora = if (capa != null || inicioUso != 0L) 1_000L else 30_000L
                principal.postDelayed(reloj, demora)
            }
        }
    }

    /**
     * Recibe la noche vigente y la aplicación elegida. Crea la capa una vez y actualiza su mensaje.
     * Sus botones se ligan a esta cuenta y noche, para no actuar sobre una sesión que cambió.
     */
    private fun mostrarCapa(
        ventana: VentanaDescanso,
        opciones: ConfiguracionDescanso,
        paquete: String,
        dueno: String,
        hablar: Boolean,
    ) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            if (invalidada || EspacioPersonal.claveActual(this) != dueno) {
                retirarCapa()
                return
            }
            if (fechaCapa != ventana.fechaNoche || paqueteCapa != paquete || capa == null) {
                guardarUsoPendiente()
                retirarCapa()
                val vista = LayoutInflater.from(this).inflate(R.layout.descanso_bloqueo, null)
                vista.tag = "descanso_bloqueo"
                val parametros = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        if (com.nowel.agendavip.seguridad.BloqueoLocal.estaActivado(this)) {
                            WindowManager.LayoutParams.FLAG_SECURE
                        } else {
                            0
                        },
                    android.graphics.PixelFormat.OPAQUE,
                ).apply {
                    gravity = Gravity.FILL
                    title = "Descanso de Agenda.VIP"
                }
                vista.findViewById<Button>(R.id.descanso_bloqueo_inicio).tag = "descanso_inicio"
                vista.findViewById<Button>(R.id.descanso_bloqueo_inicio).setOnClickListener {
                    if (vigente(dueno, ventana.fechaNoche)) {
                        guardarUsoPendiente()
                        retirarCapa()
                        ServicioVozDescanso.detener(this, dueno, ventana.fechaNoche)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
                vista.findViewById<Button>(R.id.descanso_bloqueo_silenciar).tag = "descanso_silenciar"
                vista.findViewById<Button>(R.id.descanso_bloqueo_silenciar).setOnClickListener {
                    if (vigente(dueno, ventana.fechaNoche)) {
                        ServicioVozDescanso.detener(this, dueno, ventana.fechaNoche)
                    }
                }
                vista.findViewById<Button>(R.id.descanso_bloqueo_pausa).tag = "descanso_pausar"
                vista.findViewById<Button>(R.id.descanso_bloqueo_pausa).setOnClickListener {
                    pausarDescanso(dueno, ventana.fechaNoche)
                }

                vista.findViewById<Button>(R.id.descanso_bloqueo_cancelar).tag = "descanso_cancelar"
                vista.findViewById<Button>(R.id.descanso_bloqueo_cancelar).setOnClickListener {
                    if (vigente(dueno, ventana.fechaNoche)) {
                        vista.findViewById<View>(R.id.descanso_bloqueo_confirmacion).visibility = View.VISIBLE
                        vista.findViewById<View>(R.id.descanso_bloqueo_cancelar).visibility = View.GONE
                    }
                }
                vista.findViewById<Button>(R.id.descanso_bloqueo_confirmar_cancelacion).tag = "descanso_confirmar_cancelacion"
                vista.findViewById<Button>(R.id.descanso_bloqueo_confirmar_cancelacion).setOnClickListener {
                    confirmarCancelacion(dueno, ventana.fechaNoche)
                }
                vista.findViewById<Button>(R.id.descanso_bloqueo_mantener).setOnClickListener {
                    vista.findViewById<View>(R.id.descanso_bloqueo_confirmacion).visibility = View.GONE
                    vista.findViewById<View>(R.id.descanso_bloqueo_cancelar).visibility = View.VISIBLE
                }
                getSystemService(WindowManager::class.java).addView(vista, parametros)
                capa = vista
                fechaCapa = ventana.fechaNoche
                paqueteCapa = paquete
                vista.findViewById<TextView>(R.id.descanso_bloqueo_aplicacion).text = nombreAplicacion(paquete)
                intentarPausarVideo()
                if (hablar) {
                    ServicioVozDescanso.reproducir(this, dueno, ventana.fechaNoche)
                }
            }
            val vista = capa ?: return
            val nombre = PerfilLocal(this).nombres.trim().substringBefore(' ')
            val mensaje = ReglasDescanso.mensajeDescanso(opciones, ZonedDateTime.now(), nombre)
            val texto = vista.findViewById<TextView>(R.id.descanso_bloqueo_mensaje)
            if (texto.text.toString() != mensaje) {
                texto.text = mensaje
            }
            val estado = RepositorioDescanso(this).estado(ventana.fechaNoche)
            vista.findViewById<View>(R.id.descanso_bloqueo_pausa).visibility =
                if (opciones.pausaPermitida && estado.pausasUsadas == 0) View.VISIBLE else View.GONE
        }
    }

    /**
     * El botón Pausar entrega su cuenta y noche. Confirma la pausa de cinco minutos antes de
     * retirar la capa y programar su regreso. Un error de guardado conserva la pantalla actual.
     */
    private fun pausarDescanso(dueno: String, fecha: LocalDate) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            try {
                if (!vigente(dueno, fecha)) {
                    return
                }
                val repositorio = RepositorioDescanso(this)
                if (!repositorio.pausar(fecha, System.currentTimeMillis())) {
                    return
                }

                retirarCapa()
                ServicioVozDescanso.detener(this, dueno, fecha)
                GestorDescanso.actualizar(this)
            } catch (_: RuntimeException) {
                android.widget.Toast.makeText(
                    this,
                    "No se pudo guardar la pausa. Revisa Descanso desde la aplicación.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Recibe la cuenta y noche del segundo botón de confirmación. Guarda la decisión y retira
     * capa, voz y aviso bajo el turno de sesión; así nunca cancela la noche de una cuenta nueva.
     */
    private fun confirmarCancelacion(dueno: String, fecha: LocalDate) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            try {
                if (!vigente(dueno, fecha)) {
                    return
                }
                RepositorioDescanso(this).cancelarNoche(fecha)
                ServicioVozDescanso.detener(this, dueno, fecha)
                retirarCapa()
                NotificacionesDescanso.cancelar(this, dueno)
                GestorDescanso.actualizar(this)
            } catch (_: RuntimeException) {
                android.widget.Toast.makeText(
                    this,
                    "No se pudo guardar la decisión. Revisa Descanso desde la aplicación.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Pide PAUSA al reproductor activo, como el botón de unos auriculares. No usa PAUSA/REPRODUCIR,
     * porque ese botón podría empezar una canción que estaba detenida. Algunos reproductores
     * no atienden esta petición; la capa no finge que se cerró o se suspendió su aplicación.
     */
    private fun intentarPausarVideo() {
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) {
            return
        }
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: RuntimeException) {
            // Se mantiene el aviso de descanso aunque esa aplicación no acepte el botón multimedia.
        }
    }

    /** Devuelve la etiqueta pública de la app, o su nombre de paquete si fue desinstalada mientras tanto. */
    private fun nombreAplicacion(paquete: String): String {
        return try {
            val datos = packageManager.getApplicationInfo(paquete, 0)
            packageManager.getApplicationLabel(datos).toString()
        } catch (_: RuntimeException) {
            paquete
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            paquete
        }
    }

    /**
     * Recibe cuenta y fecha capturadas por un botón. Devuelve si aún corresponden a la noche activa.
     * Esto impide que un toque tardío cancele el descanso de otra persona o del día siguiente.
     */
    private fun vigente(dueno: String, fecha: LocalDate): Boolean {
        if (invalidada || EspacioPersonal.claveActual(this) != dueno) {
            retirarCapa()
            return false
        }
        val opciones = RepositorioDescanso(this).configuracion()
        return opciones.activado &&
            ReglasDescanso.ventanaActual(opciones, ZonedDateTime.now())?.fechaNoche == fecha
    }

    /** Retira nuestra ventana; deja disponibles el inicio, las llamadas y los ajustes del sistema. */
    private fun retirarCapa() {
        capa?.let { vista ->
            runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(vista) }
        }
        capa = null
        fechaCapa = null
        paqueteCapa = null
    }

    /** Devuelve si el usuario está usando la pantalla y no está atendiendo una llamada. */
    private fun pantallaDisponible(): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        return getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            audio.mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)
    }

    /** Recibe noche y dueño; abre un intervalo corto de medición usando un reloj que no cambia al ajustar la hora. */
    private fun iniciarMedicion(fecha: LocalDate, dueno: String) {
        if (inicioUso == 0L || fechaUso != fecha || duenoUso != dueno) {
            guardarUsoPendiente()
            fechaUso = fecha
            duenoUso = dueno
            inicioUso = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - inicioUso >= 30_000L) {
            guardarUsoPendiente()
            fechaUso = fecha
            duenoUso = dueno
            inicioUso = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Suma el intervalo observado, como máximo un minuto por tramo. No inventa uso mientras el
     * proceso estuvo detenido ni mezcla cuentas. Se llama al cambiar de app, apagar o cada treinta segundos.
     */
    private fun guardarUsoPendiente() {
        synchronized(EspacioPersonal.cerrojoSesion) {
            val inicio = inicioUso
            val fecha = fechaUso
            val dueno = duenoUso
            inicioUso = 0L
            fechaUso = null
            duenoUso = null
            if (inicio == 0L || fecha == null || dueno == null || EspacioPersonal.claveActual(this) != dueno) {
                return
            }
            val cantidad = (SystemClock.elapsedRealtime() - inicio).coerceIn(0L, 60_000L)
            if (cantidad > 0L) {
                runCatching {
                    RepositorioDescanso(this).registrarUso(fecha, cantidad)
                }
            }
        }
    }

    /** Android pide interrumpir la función; se retiran la ventana y la lectura sin cambiar sus preferencias. */
    override fun onInterrupt() {
        guardarUsoPendiente()
        retirarCapa()
        ServicioVozDescanso.detener(this)
    }

    /** Libera receptores, tareas y ventana cuando la persona desactiva Accesibilidad o Android termina el servicio. */
    override fun onDestroy() {
        conectado = false
        guardarUsoPendiente()
        retirarCapa()
        ServicioVozDescanso.detener(this)
        principal.removeCallbacksAndMessages(null)
        if (receptorRegistrado) {
            runCatching { unregisterReceiver(pantalla) }
            receptorRegistrado = false
        }
        if (actual?.get() === this) {
            actual = null
        }
        super.onDestroy()
    }

    companion object {
        private var actual: WeakReference<ServicioAccesibilidadDescanso>? = null

        /** Devuelve si el usuario realmente habilitó este servicio, en lugar de simularlo con un interruptor. */
        fun estaHabilitado(contexto: Context): Boolean {
            val componente = ComponentName(contexto, ServicioAccesibilidadDescanso::class.java)
            val sistema = contexto.getSystemService(AccessibilityManager::class.java)
            return sistema.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { servicio ->
                    val datos = servicio.resolveInfo.serviceInfo
                    ComponentName(datos.packageName, datos.name) == componente
                }
        }

        /**
         * Guardar, reprogramar y terminar pausa la llaman. Entrega la revisión al trabajo de pantalla;
         * no concede accesibilidad ni habla por sí sola después de un reinicio.
         */
        fun refrescar() {
            val servicio = actual?.get() ?: return
            servicio.principal.post {
                servicio.invalidada = false
                servicio.revisar(forzarLectura = true)
            }
        }

        /**
         * Cambiar de cuenta invalida los botones inmediatamente y retira la capa en su siguiente turno.
         * Se descarta el intervalo anterior si la sesión cambió antes de poder guardarlo.
         */
        fun retirarPorCambioSesion() {
            val servicio = actual?.get() ?: return
            servicio.invalidada = true
            servicio.principal.post {
                servicio.guardarUsoPendiente()
                servicio.retirarCapa()
                servicio.ultimoPaquete = null
                servicio.propietario = null
                servicio.configuracion = null
            }
        }
    }
}
