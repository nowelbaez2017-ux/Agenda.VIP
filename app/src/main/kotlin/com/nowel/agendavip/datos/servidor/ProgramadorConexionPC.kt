package com.nowel.agendavip.datos.servidor

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.servidor.TrabajoConexionPC

/**
 * Coordina cuándo se intenta hablar con la PC.
 * Mientras usas la app revisa los cambios cada quince segundos. Al salir, Android decide
 * cuándo ejecutar la revisión periódica, sin mantener un servicio ni una notificación permanente.
 */
object ProgramadorConexionPC {

    private const val IDENTIDAD_TRABAJO = 8451
    private val principal = Handler(Looper.getMainLooper())
    private var aplicacion: Context? = null
    private var visible = false
    @Volatile
    private var programada = false

    private val revisar = object : Runnable {

        /** Android ejecuta esta revisión solo mientras alguna pantalla de Agenda.VIP está visible. */
        override fun run() {
            val contexto = aplicacion ?: return
            if (!visible) {
                return
            }

            SincronizadorPC.solicitar(contexto)
            principal.postDelayed(this, 15_000L)
        }
    }

    /**
     * Recibe Android al abrir una pantalla o después de vincular.
     * Si la cuenta tiene conexión, registra una revisión que Android puede conservar tras reiniciar.
     */
    fun preparar(contexto: Context) {
        aplicacion = contexto.applicationContext
        if (programada || EspacioPersonal.claveActual(contexto) == null) {
            return
        }
        val enlace = runCatching { RepositorioConexionPC(contexto).leer() }.getOrNull()
        if (enlace == null) {
            return
        }

        val trabajo = JobInfo.Builder(
            IDENTIDAD_TRABAJO,
            ComponentName(contexto, TrabajoConexionPC::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60_000L, 5 * 60_000L)
            .setPersisted(true)
            .build()

        val sistema = contexto.getSystemService(JobScheduler::class.java)
        programada = runCatching {
            sistema.schedule(trabajo) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }

    /**
     * La aplicación la llama cuando muestra su primera pantalla.
     * Reinicia una sola revisión visible y solicita el intercambio de los cambios pendientes.
     */
    fun mostrar(contexto: Context) {
        preparar(contexto)
        if (visible) {
            return
        }
        visible = true
        principal.removeCallbacks(revisar)
        principal.post(revisar)
    }

    /**
     * Desconectar o cambiar de cuenta la llaman para retirar el trabajo periódico.
     * Preparar lo vuelve a registrar únicamente si la cuenta que entra tiene una PC vinculada.
     */
    fun retirar(contexto: Context) {
        contexto.getSystemService(JobScheduler::class.java).cancel(IDENTIDAD_TRABAJO)
        programada = false
    }

    /** La aplicación la llama al ocultar todas sus pantallas para retirar las consultas frecuentes. */
    fun ocultar() {
        visible = false
        principal.removeCallbacks(revisar)
    }
}
