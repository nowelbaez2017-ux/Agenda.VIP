package com.nowel.agendavip.presentacion.acceso

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.seguridad.EstadoArranque
import java.util.concurrent.Executors

/**
 * Ofrece recuperar una restauración interrumpida sin enseñar datos que estén a medio reemplazar.
 * El bloqueo protege el contenido hasta confirmar que Android volvió a guardar el diario anterior.
 * Nunca propone borrar los datos de la aplicación para resolver un problema de lectura.
 */
class ActividadRecuperacion : Activity() {

    private val trabajador = Executors.newSingleThreadExecutor()
    private lateinit var mensaje: TextView
    private var ocupada = false
    private var cerrada = false

    /** Android crea una pantalla de recuperación sin tareas ni información privada de otras vistas. */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadRecuperacion, 24)
            setPadding(margen, margen, margen, margen)
            setBackgroundColor(getColor(R.color.marfil))
        }
        setContentView(contenido)
        contenido.addView(ComponentesAgenda.texto(this, "Recuperar mi agenda", 28f, titulo = true))
        mensaje = ComponentesAgenda.texto(
            this,
            "Android no pudo terminar de recuperar una restauración. Tus archivos se han conservado. " +
                "Comprueba que el teléfono tenga espacio libre y pulsa Reintentar.",
            17f
        )
        contenido.addView(mensaje)
        contenido.addView(ComponentesAgenda.boton(this, "Reintentar recuperación", true) { reintentar() })
        contenido.addView(ComponentesAgenda.boton(this, "Salir por ahora") { moveTaskToBack(true) })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { moveTaskToBack(true) }
        }
    }

    /**
     * El botón Reintentar ejecuta el diario privado fuera del trabajo que dibuja la pantalla.
     * Al confirmar la recuperación abre de nuevo el acceso; un fallo conserva esta pantalla segura.
     */
    private fun reintentar() {
        if (ocupada) return
        ocupada = true
        mensaje.text = "Recuperando datos…"
        trabajador.execute {
            try {
                CopiaSeguridadLocal.recuperarPendiente(this)
                EstadoArranque.problema = null
                runOnUiThread {
                    if (!cerrada) {
                        startActivity(Intent(this, ActividadAcceso::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    ocupada = false
                    if (!cerrada) mensaje.text = "La recuperación sigue pendiente. Tus archivos no se han borrado. Libera espacio y vuelve a intentarlo."
                }
            }
        }
    }

    /** Atrás deja la app en segundo plano; no permite ver una agenda que aún no se recuperó. */
    @Deprecated("Compatibilidad con versiones anteriores de Android.")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    /** Android retira la pantalla; cancela el trabajo pendiente sin borrar el diario de recuperación. */
    override fun onDestroy() {
        cerrada = true
        trabajador.shutdownNow()
        super.onDestroy()
    }
}
