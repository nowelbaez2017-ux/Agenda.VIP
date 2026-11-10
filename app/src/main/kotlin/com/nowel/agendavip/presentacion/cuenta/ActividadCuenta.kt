package com.nowel.agendavip.presentacion.cuenta

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import com.nowel.agendavip.recordatorios.RecuperadorRecordatorios
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Abre los apartados del menú personal y conserva una barra superior para regresar.
 * La petición de apertura trae seccion: perfil, configuracion, creditos o permisos.
 * Perfil y Configuración tienen sus propios controladores para no mezclar sus funciones.
 */
class ActividadCuenta : Activity() {

    private val protector = ProtectorSesion(this)
    private var cuentaActiva: ControladorCuentaActiva? = null
    private lateinit var contenido: LinearLayout
    private var seccion = "perfil"
    private var controladorPerfil: ControladorPerfil? = null
    private var controladorConfiguracion: ControladorConfiguracion? = null

    /**
     * Android llama aquí al abrir un apartado personal. Recibe datos temporales si se giró el teléfono.
     * Crea el diseño y prepara únicamente el controlador del apartado solicitado.
     * Los permisos de Android se abren como otra sección, separados de las preferencias de la app.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!protector.permitir(estado)) {
            return
        }

        setContentView(R.layout.pantalla_cuenta_local)
        VentanaAgenda.preparar(this, findViewById(R.id.cuenta_raiz))
        contenido = findViewById(R.id.cuenta_contenido)
        seccion = intent.getStringExtra("seccion") ?: "perfil"
        findViewById<View>(R.id.cuenta_volver).setOnClickListener { volver() }

        when (seccion) {
            "configuracion" -> {
                findViewById<TextView>(R.id.cuenta_titulo).text = "Configuración"
                cuentaActiva = ControladorCuentaActiva(this) {
                    controladorConfiguracion?.mostrar()
                }
                controladorConfiguracion = ControladorConfiguracion(
                    this,
                    contenido,
                    abrirPermisos = {
                        startActivity(Intent(this, ActividadCuenta::class.java).putExtra("seccion", "permisos"))
                    },
                    abrirVozDelSistema = {
                        abrirAjuste(Intent("com.android.settings.TTS_SETTINGS"))
                    },
                    identidadCuenta = {
                        cuentaActiva?.identidad ?: "Cuenta local"
                    },
                    cerrarSesion = {
                        cuentaActiva?.pedirSalida()
                    },
                )
            }
            "creditos" -> findViewById<TextView>(R.id.cuenta_titulo).text = "Créditos"
            "permisos" -> findViewById<TextView>(R.id.cuenta_titulo).text = "Permisos del teléfono"
            else -> {
                seccion = "perfil"
                findViewById<TextView>(R.id.cuenta_titulo).text = "Perfil"
                controladorPerfil = ControladorPerfil(this, contenido, estado)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { volver() }
        }
    }

    /** Android llama al volver; cada apartado actualiza sus datos sin perder los campos del perfil. */
    override fun onResume() {
        super.onResume()

        if (!::contenido.isInitialized || !protector.comprobar()) {
            return
        }

        when (seccion) {
            "configuracion" -> {
                controladorConfiguracion?.mostrar()
                cuentaActiva?.mostrar()
            }
            "creditos" -> mostrarCreditos()
            "permisos" -> {
                RecuperadorRecordatorios.solicitar(this)
                mostrarPermisos()
            }
            else -> controladorPerfil?.mostrar()
        }
    }

    /** Conserva los campos de perfil todavía sin guardar antes de recrear esta pantalla al girar. */
    override fun onSaveInstanceState(estado: Bundle) {
        protector.guardarEstado(estado)
        controladorPerfil?.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /** Android llama al salir; se detiene cualquier muestra que se hubiera pedido en Configuración. */
    override fun onStop() {
        cuentaActiva?.pausar()
        controladorConfiguracion?.pausar()
        super.onStop()
    }

    /** Cierra trabajos propios de los apartados al retirar la pantalla de la memoria. */
    override fun onDestroy() {
        cuentaActiva?.cerrar()
        controladorPerfil?.cerrar()
        controladorConfiguracion?.cerrar()
        super.onDestroy()
    }

    /**
     * Muestra los permisos del sistema solo cuando se solicita este apartado.
     * No son preferencias de Agenda.VIP: Android administra su autorización.
     * Nueva tarea y el aviso de permisos de Inicio pueden abrir esta sección directamente.
     */
    private fun mostrarPermisos() {
        contenido.removeAllViews()
        val gestor = GestorRecordatorios(this)
        val notificaciones = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        contenido.addView(ComponentesAgenda.texto(this, "Opciones administradas por Android", 22f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Estos permisos permiten recibir avisos. Puedes revisarlos también manteniendo pulsado el icono de Agenda.VIP y entrando en Información de la app.",
            15f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Notificaciones: ${if (notificaciones) "activadas" else "desactivadas"}"))
        contenido.addView(ComponentesAgenda.boton(this, "Configurar notificaciones") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 202)
            } else {
                abrirAjuste(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        })
        contenido.addView(ComponentesAgenda.espacio(this, 16))
        contenido.addView(ComponentesAgenda.texto(this, "Hora elegida: ${if (gestor.tieneAlarmasExactas()) "permitida" else "acceso pendiente"}"))
        contenido.addView(ComponentesAgenda.boton(this, "Revisar alarmas y recordatorios") {
            if (Build.VERSION.SDK_INT >= 31) {
                abrirAjuste(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } else informar("Esta versión de Android ya permite programar la hora elegida.")
        })
        if (!gestor.tieneAlarmasExactas()) {
            contenido.addView(ComponentesAgenda.texto(
                this,
                "Falta permitir Alarmas y recordatorios. Los avisos aproximados pueden retrasarse con el teléfono bloqueado y Android puede impedir que comiencen la voz o la música automáticamente.",
                14f,
                R.color.texto_secundario,
            ))
        }
        mostrarActividadEnSegundoPlano()
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Registro de entrega", 20f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Última hora solicitada a Android: ${formatearRegistro(gestor.ultimaHoraProgramada())}\nÚltimo aviso recibido de Android: ${formatearRegistro(gestor.ultimaRecepcion())}",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Último arranque recibido por Agenda.VIP: ${formatearRegistro(gestor.ultimoArranque(recuperado = false))}\n" +
                "Última recuperación tras reiniciar: ${formatearRegistro(gestor.ultimoArranque(recuperado = true))}",
            14f,
            R.color.texto_secundario,
        ))
        gestor.ultimoProblema()?.let { problema ->
            contenido.addView(ComponentesAgenda.espacio(this, 20))
            contenido.addView(ComponentesAgenda.texto(this, "Último problema registrado", 20f, titulo = true))
            contenido.addView(ComponentesAgenda.texto(this, problema, 14f, R.color.texto_secundario))
        }
    }

    /**
     * Permisos la llama para mostrar restricciones que Android permite consultar.
     * Los ajustes de HyperOS no ofrecen aquí un estado público fiable: se abren los detalles
     * de la app y se explica qué revisar, sin afirmar que ya se concedió inicio automático.
     */
    private fun mostrarActividadEnSegundoPlano() {
        val energia = getSystemService(PowerManager::class.java)
        val restringida = Build.VERSION.SDK_INT >= 28 &&
            getSystemService(ActivityManager::class.java).isBackgroundRestricted
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Con el teléfono bloqueado", 20f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            if (restringida) "Android informa que la actividad en segundo plano está restringida."
            else "Android no informa de una restricción explícita de actividad en segundo plano.",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Ahorro de batería del teléfono: ${if (energia.isPowerSaveMode) "activado" else "desactivado"}. Optimización estándar para esta app: ${if (energia.isIgnoringBatteryOptimizations(packageName)) "sin optimización" else "activa"}.",
            14f,
            R.color.texto_secundario,
        ))
        contenido.addView(ComponentesAgenda.boton(this, "Revisar batería y actividad de Agenda.VIP") {
            abrirAjuste(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        contenido.addView(ComponentesAgenda.texto(
            this,
            "En POCO/Xiaomi revisa Batería → Sin restricciones e Inicio automático en segundo plano para Agenda.VIP. Los nombres varían según HyperOS. Bloquear la pantalla no equivale a Cerrar sesión ni a Forzar detención.",
            14f,
            R.color.texto_secundario,
        ))
    }

    /** Recibe una hora registrada y devuelve fecha y hora de 12 horas, o indica que aún no hay registro. */
    private fun formatearRegistro(momento: Long?): String {
        if (momento == null) {
            return "Todavía sin registro en esta versión"
        }

        return Instant.ofEpochMilli(momento).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy · h:mm:ss a", Locale("es")))
    }

    /** Muestra la autoría usando el texto actual del proyecto; no reemplaza la corrección del usuario. */
    private fun mostrarCreditos() {
        contenido.removeAllViews()
        contenido.addView(ComponentesAgenda.texto(this, "Agenda.VIP", 32f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(this, getString(R.string.credito_autor), 22f))
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Una agenda personal para organizar tus días con tus propios mensajes y música del teléfono.",
            17f,
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Voz sin conexión", 22f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "La transcripción utiliza whisper.cpp, un componente con licencia MIT. La voz que lee los avisos pertenece al motor instalado en Android.",
            15f,
        ))
        contenido.addView(ComponentesAgenda.boton(this, "Ver licencia de whisper.cpp") {
            mostrarLicencia("licencias/whisper.txt", "whisper.cpp · licencia MIT")
        })
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Lector de códigos QR", 22f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "La cámara reconoce el QR dentro del teléfono mediante ZXing Android Embedded. Este componente utiliza licencia Apache 2.0.",
            15f,
        ))
        contenido.addView(ComponentesAgenda.boton(this, "Ver licencia del lector QR") {
            mostrarLicencia("licencias/zxing_android_embedded.txt", "ZXing Android Embedded · Apache 2.0")
        })
    }

    /**
     * Recibe un archivo de licencia incluido en la app y el título que se mostrará.
     * Créditos la llama para consultar el texto completo sin depender de una página de Internet.
     * Permite seleccionar el texto y cerrarlo sin salir de Créditos.
     */
    private fun mostrarLicencia(archivo: String, titulo: String) {
        val licencia = assets.open(archivo).bufferedReader().use { it.readText() }
        val texto = ComponentesAgenda.texto(this, licencia, 14f).apply {
            val margen = ComponentesAgenda.dp(this@ActividadCuenta, 20)
            setPadding(margen, margen, margen, margen)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(android.widget.ScrollView(this).apply { addView(texto) })
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /**
     * Recibe una petición de abrir un ajuste del sistema y la entrega a Android.
     * Si el fabricante no ofrece esa pantalla, muestra una explicación en lugar de cerrar la app.
     */
    private fun abrirAjuste(destino: Intent) {
        try {
            startActivity(destino)
        } catch (_: ActivityNotFoundException) {
            informar("Busca este permiso en Configuración de Android, dentro de Agenda.VIP.")
        }
    }

    /** Recibe la foto que devolvió el selector y la entrega al controlador de Perfil, si está abierto. */
    @Deprecated("Contrato nativo de Android para recibir el archivo elegido.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)
        if (protector.comprobar()) {
            controladorPerfil?.recibirResultado(solicitud, resultado, datos)
        }
    }

    /** Actualiza el estado de notificaciones después de responder al permiso solicitado a Android. */
    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, resultados)
        if (codigo == 202 && ::contenido.isInitialized && protector.comprobar()) {
            mostrarPermisos()
            if (resultados.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                informar("Puedes habilitar las notificaciones desde Información de la app en Android.")
            }
        }
    }

    /** Vuelve al apartado anterior y pregunta antes de descartar los datos del perfil aún sin guardar. */
    private fun volver() {
        if (controladorPerfil?.hayCambios() == true) {
            AlertDialog.Builder(this)
                .setTitle("¿Salir sin guardar los cambios?")
                .setPositiveButton("Salir") { _, _ -> finish() }
                .setNegativeButton("Seguir editando", null)
                .show()
        } else finish()
    }

    /** Recibe el botón Atrás en teléfonos anteriores a Android 13 y utiliza el mismo regreso seguro. */
    @Deprecated("Android 13 usa la acción registrada en onCreate.")
    override fun onBackPressed() {
        volver()
    }

    /** Recibe una explicación breve y la muestra sin cambiar los datos del apartado actual. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }
}
