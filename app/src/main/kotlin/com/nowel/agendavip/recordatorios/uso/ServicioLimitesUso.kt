package com.nowel.agendavip.recordatorios.uso

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.uso.LectorUsoDiario
import com.nowel.agendavip.datos.uso.RepositorioLimitesUso
import com.nowel.agendavip.dominio.uso.LimiteAplicacion
import com.nowel.agendavip.voz.asistente.VozConversacionLocal
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Supervisa objetivos únicamente después de pulsar Activar en una pantalla visible.
 * Un servicio en primer plano es un trabajo de Android que siempre muestra una notificación.
 * Consulta cada treinta segundos sin mantener despierto el teléfono ni abrir el micrófono.
 * No se reinicia solo tras un cierre o un encendido: la persona vuelve a activarlo si lo necesita.
 */
class ServicioLimitesUso : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private val trabajador = Executors.newSingleThreadExecutor()
    private val mensajes = ArrayDeque<String>()
    private var voz: VozConversacionLocal? = null
    private var hablando = false
    private var propietario: String? = null

    @Volatile
    private var detenido = false

    private val revisar = object : Runnable {
        /** Programa una consulta en el trabajador y deja el siguiente turno para dentro de treinta segundos. */
        override fun run() {
            if (detenido) {
                return
            }
            trabajador.execute {
                consultar()
            }
        }
    }

    /** Android crea el servicio; se guarda una referencia débil que no impide liberar su memoria. */
    override fun onCreate() {
        super.onCreate()
        actual = WeakReference(this)
        voz = VozConversacionLocal(this)
    }

    /**
     * Android recibe Activar o Pausar. Comprueba dueño y permisos antes de observar el uso.
     * Devuelve START_NOT_STICKY para no reanudar una supervisión sin otra decisión de la persona.
     */
    override fun onStartCommand(datos: Intent?, flags: Int, startId: Int): Int {
        val dueno = datos?.getStringExtra(EspacioPersonal.EXTRA_ESPACIO)
        if (datos?.action == ACCION_PAUSAR) {
            if (dueno == propietario) {
                detener(this)
            } else if (propietario == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (dueno == null || EspacioPersonal.claveActual(this) != dueno ||
            !LectorUsoDiario(this).tienePermiso() || !permiteNotificaciones(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        propietario = dueno
        detenido = false
        try {
            val aviso = NotificacionesLimitesUso.servicio(this, dueno, "Revisando las aplicaciones que elegiste.")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NotificacionesLimitesUso.ID_SERVICIO,
                    aviso,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificacionesLimitesUso.ID_SERVICIO, aviso)
            }
        } catch (_: RuntimeException) {
            detener(this)
            return START_NOT_STICKY
        }
        principal.removeCallbacks(revisar)
        principal.post(revisar)
        return START_NOT_STICKY
    }

    /**
     * Lee solo las aplicaciones configuradas y compara su uso de hoy con cada objetivo.
     * El cálculo ocurre fuera de la pantalla; antes de publicar se vuelve a comprobar la cuenta.
     */
    private fun consultar() {
        val dueno = propietario ?: return
        try {
            if (!vigente(dueno) || !LectorUsoDiario(this).tienePermiso() || !permiteNotificaciones(this)) {
                principal.post { detener(this) }
                return
            }
            val repositorio = RepositorioLimitesUso(this)
            val limites = repositorio.listar().filter { it.habilitado }
            if (limites.isEmpty()) {
                principal.post { detener(this) }
                return
            }
            val uso = LectorUsoDiario(this).consultar(limites.map { it.paquete }.toSet())
            val alcanzados = limites.filter {
                (uso.milisegundosPorPaquete[it.paquete] ?: 0L) >= it.minutosDiarios * 60_000L
            }
            principal.post {
                if (!vigente(dueno)) {
                    return@post
                }
                try {
                    synchronized(EspacioPersonal.cerrojoSesion) {
                        if (vigente(dueno) && uso.fecha == java.time.LocalDate.now()) {
                            val actuales = repositorio.listar().associateBy { it.paquete }
                            alcanzados.forEach { limite ->
                                // Una edición o pausa pudo ocurrir mientras Android leía los eventos.
                                if (actuales[limite.paquete] == limite &&
                                    !repositorio.yaAvisado(limite.paquete, uso.fecha)) {
                                    avisar(repositorio, dueno, limite, uso.fecha)
                                }
                            }
                            mostrarEstado(dueno, "Supervisando tus límites diarios. Puedes pausar cuando quieras.")
                        }
                    }
                } catch (_: RuntimeException) {
                    mostrarEstado(dueno, "No se pudieron guardar todos los avisos. Abre Límites para revisarlos.")
                }
            }
        } catch (_: Exception) {
            // No se sustituye un fallo de consulta por cero minutos ni se borra ningún límite.
            principal.post {
                if (vigente(dueno)) {
                    mostrarEstado(dueno, "No se pudo actualizar el uso. Abre Límites para revisar el permiso.")
                }
            }
        } finally {
            principal.post {
                if (vigente(dueno)) {
                    principal.removeCallbacks(revisar)
                    principal.postDelayed(revisar, 30_000L)
                }
            }
        }
    }

    /** Actualiza el estado visible y tolera que Android retire permisos mientras se prepara el mensaje. */
    private fun mostrarEstado(dueno: String, mensaje: String) {
        if (!vigente(dueno)) {
            return
        }
        try {
            getSystemService(NotificationManager::class.java).notify(
                NotificacionesLimitesUso.ID_SERVICIO,
                NotificacionesLimitesUso.servicio(this, dueno, mensaje)
            )
        } catch (_: RuntimeException) {
            detener(this)
        }
    }

    /** Publica y registra un aviso diario; la lectura de voz es opcional y no requiere una API. */
    private fun avisar(
        repositorio: RepositorioLimitesUso,
        dueno: String,
        limite: LimiteAplicacion,
        fecha: java.time.LocalDate
    ) {
        try {
            NotificacionesLimitesUso.limite(this, dueno, limite)
            repositorio.marcarAvisado(limite.paquete, fecha)
            if (limite.hablar) {
                mensajes.add(limite.textoAviso())
                leerSiguiente()
            }
        } catch (_: RuntimeException) {
            // Un cambio de permiso durante la entrega no debe cerrar la aplicación.
        }
    }

    /** Lee los mensajes de uno en uno para que dos límites simultáneos no se corten entre sí. */
    private fun leerSiguiente() {
        val dueno = propietario ?: return
        if (hablando || mensajes.isEmpty() || !vigente(dueno)) {
            return
        }
        hablando = true
        voz?.hablar(mensajes.removeFirst()) {
            hablando = false
            if (vigente(dueno)) {
                leerSiguiente()
            }
        }
    }

    /** Comprueba que el trabajo todavía pertenece a la misma cuenta y no se haya pausado. */
    private fun vigente(dueno: String): Boolean {
        return !detenido && propietario == dueno && EspacioPersonal.claveActual(this) == dueno
    }

    /** Android no necesita una conexión de pantalla con este servicio; solo recibe Activar y Pausar. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Retira consultas, voz y notificaciones de esta sesión cuando Android detiene el servicio. */
    override fun onDestroy() {
        detenido = true
        principal.removeCallbacksAndMessages(null)
        trabajador.shutdownNow()
        mensajes.clear()
        voz?.cerrar()
        voz = null
        propietario?.let {
            NotificacionesLimitesUso.cancelarAvisos(this, it)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (actual?.get() === this) {
            actual = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACCION_PAUSAR = "com.nowel.agendavip.USO_PAUSAR"
        private var actual: WeakReference<ServicioLimitesUso>? = null

        /** La pantalla visible la llama después de obtener Acceso de uso y notificaciones. */
        fun iniciar(contexto: Context) {
            val dueno = EspacioPersonal.claveActual(contexto)
                ?: error("Entra a tu cuenta antes de activar los límites.")
            check(LectorUsoDiario(contexto).tienePermiso()) { "Primero permite Acceso de uso." }
            check(permiteNotificaciones(contexto)) { "Permite las notificaciones para ver y pausar la supervisión." }
            check(RepositorioLimitesUso(contexto).listar().any { it.habilitado }) {
                "Añade al menos una aplicación con su límite activado."
            }
            contexto.startForegroundService(
                Intent(contexto, ServicioLimitesUso::class.java)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, dueno)
            )
        }

        /**
         * Pausar, salir de la cuenta y bloquear la agenda la llaman para detener la supervisión.
         * La marca inmediata impide publicar respuestas que ya estaban calculándose.
         */
        fun detener(contexto: Context) {
            val servicio = actual?.get()
            if (servicio != null) {
                servicio.detenido = true
                servicio.principal.post {
                    if (servicio.detenido) {
                        servicio.voz?.detener()
                        servicio.mensajes.clear()
                        servicio.stopSelf()
                    }
                }
            }
            contexto.stopService(Intent(contexto, ServicioLimitesUso::class.java))
        }

        /** Devuelve el estado real en memoria; una opción guardada nunca simula un servicio activo. */
        fun estaActivo(contexto: Context): Boolean {
            val servicio = actual?.get() ?: return false
            val dueno = EspacioPersonal.claveActual(contexto) ?: return false
            return servicio.vigente(dueno)
        }

        /** Comprueba la autorización de notificaciones antes de comenzar o continuar observando. */
        fun permiteNotificaciones(contexto: Context): Boolean {
            return (Build.VERSION.SDK_INT < 33 ||
                contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                contexto.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
    }
}
