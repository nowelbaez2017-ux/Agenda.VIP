package com.nowel.agendavip.servidor

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import com.nowel.agendavip.datos.servidor.SincronizadorPC
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Atiende la revisión periódica que Android permite en segundo plano.
 * JobService es un componente al que el sistema concede tiempo para un trabajo limitado.
 * Las alarmas de la agenda conservan su programación propia aunque este trabajo se retrase.
 */
class TrabajoConexionPC : JobService() {

    private val ejecutor = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private var tarea: Future<*>? = null
    private var turno = 0

    /**
     * Android entrega los datos de su trabajo. Devuelve true porque la red termina después.
     * La cola realiza el intercambio y jobFinished informa al sistema al finalizar.
     */
    override fun onStartJob(parametros: JobParameters): Boolean {
        val actual = ++turno
        tarea = ejecutor.submit {
            try {
                SincronizadorPC.sincronizar(applicationContext)
            } catch (_: Exception) {
                // Mi computadora muestra el resultado guardado. Android volverá a dar otro turno.
            } finally {
                principal.post {
                    if (actual == turno) {
                        tarea = null
                        jobFinished(parametros, false)
                    }
                }
            }
        }
        return true
    }

    /**
     * Android retira el turno, por ejemplo al perder la red.
     * Pide cancelar el intercambio y devuelve true para permitir que se intente más adelante.
     */
    override fun onStopJob(parametros: JobParameters): Boolean {
        turno += 1
        tarea?.cancel(true)
        tarea = null
        return true
    }

    /** Cancela callbacks y libera la cola cuando Android destruye este servicio. */
    override fun onDestroy() {
        turno += 1
        tarea?.cancel(true)
        ejecutor.shutdownNow()
        principal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
