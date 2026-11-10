package com.nowel.agendavip.recordatorios.descanso

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import com.nowel.agendavip.voz.asistente.VozConversacionLocal
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Lee un aviso breve con la voz española instalada. No usa micrófono, Internet ni música.
 * Android exige una notificación visible mientras se reproduce audio en segundo plano.
 * Al apagar la pantalla se detiene: el seguimiento no debe despertar a quien ya dejó el celular.
 */
class ServicioVozDescanso : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private var voz: VozConversacionLocal? = null
    private var propietario: String? = null
    private var fecha: LocalDate? = null
    private var hablando = false
    @Volatile
    private var turno = 0
    private val limite = Runnable { stopSelf() }

    private val pantalla = object : BroadcastReceiver() {
        /** Android avisa de la pantalla apagada; termina el audio sin cancelar la rutina nocturna. */
        override fun onReceive(contexto: Context, intento: Intent) {
            stopSelf()
        }
    }

    /** Android crea el lector temporal y permite detenerlo al bloquear el teléfono. */
    override fun onCreate() {
        super.onCreate()
        actual = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pantalla, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pantalla, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    /**
     * Recibe una noche y comprueba que aún corresponda a esta cuenta. El texto se vuelve a
     * calcular ahora para no pronunciar un número de horas que ya haya quedado desactualizado.
     */
    override fun onStartCommand(intento: Intent?, flags: Int, startId: Int): Int {
        if (intento == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val dueno = intento.getStringExtra(EspacioPersonal.EXTRA_ESPACIO)
        val noche = runCatching {
            LocalDate.parse(intento.getStringExtra(GestorDescanso.EXTRA_FECHA))
        }.getOrNull()
        val inicioProgramado = intento.getBooleanExtra(EXTRA_INICIO_PROGRAMADO, false)
        if (hablando && propietario == dueno && fecha == noche && !inicioProgramado) {
            return START_NOT_STICKY
        }
        if (dueno == null || noche == null || EspacioPersonal.claveActual(this) != dueno || !pantallaDisponible(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            synchronized(EspacioPersonal.cerrojoSesion) {
                if (EspacioPersonal.claveActual(this) != dueno) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val repositorio = RepositorioDescanso(this)
                val configuracion = repositorio.configuracion()
                val ahora = ZonedDateTime.now()
                val previo = intento.getBooleanExtra(EXTRA_PREVIO, false)
                val ventana = if (previo) {
                    ReglasDescanso.proximaVentana(configuracion, ahora)
                } else {
                    ReglasDescanso.ventanaActual(configuracion, ahora)
                }
                val estado = repositorio.estado(noche)
                if (!configuracion.activado || !configuracion.vozActiva || ventana?.fechaNoche != noche ||
                    estado.cancelada || estado.pausaHasta > System.currentTimeMillis() ||
                    (previo && (!ahora.isBefore(ventana.inicio) || ahora.isBefore(ventana.inicio.minusMinutes(10)))) ||
                    !repositorio.registrarVoz(
                        noche,
                        System.currentTimeMillis(),
                        intervaloMinimo = if (inicioProgramado) 0L else 300_000L,
                    )) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                propietario = dueno
                fecha = noche
                val nombre = PerfilLocal(this).nombres.trim().substringBefore(' ')
                val mensaje = if (previo) {
                    "${if (nombre.isBlank()) "" else "$nombre, "}en menos de diez minutos empieza tu descanso. Ve terminando lo que estás haciendo."
                } else {
                    ReglasDescanso.mensajeDescanso(configuracion, ahora, nombre)
                }
                val aviso = NotificacionesDescanso.lectura(this, dueno, noche, mensaje)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NotificacionesDescanso.ID_VOZ, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NotificacionesDescanso.ID_VOZ, aviso)
                }
                turno += 1
                val version = turno
                hablando = true
                principal.removeCallbacks(limite)
                principal.postDelayed(limite, 65_000L)
                voz?.cerrar()
                voz = VozConversacionLocal(this, AudioAttributes.USAGE_ALARM) {
                    version == turno && permiteLecturaActual(dueno, noche, previo, configuracion.hashCode())
                }
                voz?.hablar(mensaje) {
                    if (version == turno) {
                        hablando = false
                        stopSelf(startId)
                    }
                }
            }
        } catch (_: RuntimeException) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * El lector la consulta justo antes de convertir texto en sonido, incluso tras una preparación lenta.
     * Recibe cuenta, noche y versión de opciones del mensaje; devuelve false si se cerró la cuenta,
     * cambió el horario, se silenció, se bloqueó la pantalla o terminó esta noche durante la espera.
     */
    private fun permiteLecturaActual(dueno: String, noche: LocalDate, previo: Boolean, firma: Int): Boolean {
        return runCatching {
            if (EspacioPersonal.claveActual(this) != dueno || !pantallaDisponible(this)) {
                return@runCatching false
            }
            val repositorio = RepositorioDescanso(this)
            val opciones = repositorio.configuracion()
            val ahora = ZonedDateTime.now()
            val ventana = if (previo) {
                ReglasDescanso.proximaVentana(opciones, ahora)
            } else {
                ReglasDescanso.ventanaActual(opciones, ahora)
            }
            val estado = repositorio.estado(noche)
            opciones.activado && opciones.vozActiva && opciones.hashCode() == firma &&
                ventana?.fechaNoche == noche && !estado.cancelada &&
                estado.pausaHasta <= System.currentTimeMillis() &&
                (!previo || (ahora.isBefore(ventana.inicio) && !ahora.isBefore(ventana.inicio.minusMinutes(10))))
        }.getOrDefault(false)
    }

    /** Este servicio recibe acciones, no conexiones con pantallas; Android espera null. */
    override fun onBind(intento: Intent?): IBinder? = null

    /** Libera motor de voz, tiempo límite y notificación al terminar o silenciar el mensaje. */
    override fun onDestroy() {
        turno += 1
        hablando = false
        principal.removeCallbacksAndMessages(null)
        voz?.cerrar()
        voz = null
        runCatching { unregisterReceiver(pantalla) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (actual?.get() === this) {
            actual = null
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PREVIO = "descanso_lectura_previa"
        private const val EXTRA_INICIO_PROGRAMADO = "descanso_inicio_programado"
        private var actual: WeakReference<ServicioVozDescanso>? = null

        /**
         * Receptor y capa la llaman para pedir una lectura. Si Android impide arrancar el audio,
         * la notificación y el texto siguen disponibles. No se insiste mientras la pantalla esté apagada.
         * inicioProgramado solo lo envía el receptor tras registrar el comienzo por primera vez:
         * permite leer la hora de descanso aunque un preaviso haya sonado hace menos de cinco minutos.
         */
        fun reproducir(
            contexto: Context,
            propietario: String,
            fecha: LocalDate,
            previo: Boolean = false,
            inicioProgramado: Boolean = false,
        ) {
            if (EspacioPersonal.claveActual(contexto) != propietario || !pantallaDisponible(contexto)) {
                return
            }
            try {
                val configuracion = RepositorioDescanso(contexto).configuracion()
                if (!configuracion.activado || !configuracion.vozActiva) {
                    return
                }
                contexto.startForegroundService(
                    Intent(contexto, ServicioVozDescanso::class.java)
                        .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
                        .putExtra(GestorDescanso.EXTRA_FECHA, fecha.toString())
                        .putExtra(EXTRA_PREVIO, previo)
                        .putExtra(EXTRA_INICIO_PROGRAMADO, inicioProgramado),
                )
            } catch (_: RuntimeException) {
                // Algunas versiones restringen el audio en arranque; queda el aviso visible.
            }
        }

        /**
         * Silenciar o salir la llaman. Cuenta y fecha opcionales impiden que un aviso antiguo
         * detenga la lectura de otra persona o de otra noche. No cambian horarios ni seguimiento.
         */
        fun detener(contexto: Context, propietario: String? = null, fecha: LocalDate? = null) {
            val servicio = actual?.get()
            if (servicio != null) {
                if (propietario != null && servicio.propietario != propietario) {
                    return
                }
                if (fecha != null && servicio.fecha != fecha) {
                    return
                }
                servicio.turno += 1
                servicio.principal.post {
                    servicio.voz?.detener()
                    servicio.stopSelf()
                }
            } else if (propietario == null) {
                contexto.stopService(Intent(contexto, ServicioVozDescanso::class.java))
            }
        }

        /**
         * Devuelve si la pantalla está encendida, desbloqueada y sin una conversación telefónica.
         * AudioManager informa del modo de llamada sin pedir acceso al número ni al registro de llamadas.
         */
        private fun pantallaDisponible(contexto: Context): Boolean {
            val encendida = contexto.getSystemService(PowerManager::class.java).isInteractive
            val bloqueada = contexto.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val modo = contexto.getSystemService(AudioManager::class.java).mode
            return encendida && !bloqueada && modo !in setOf(
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION,
                AudioManager.MODE_RINGTONE,
            )
        }
    }
}
