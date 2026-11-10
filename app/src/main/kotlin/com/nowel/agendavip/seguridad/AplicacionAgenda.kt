package com.nowel.agendavip.seguridad

import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.nowel.agendavip.datos.cuentas.EliminadorCuenta
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.presentacion.acceso.ActividadDesbloqueo
import com.nowel.agendavip.presentacion.cuenta.ActividadSeguridad
import java.util.Collections
import java.util.WeakHashMap

/**
 * Recibe los cambios de pantalla de Android y protege una agenda cuyo bloqueo está activado.
 * Application es la clase que Android crea una vez por proceso, antes de abrir pantallas.
 * Conserva las pantallas cubiertas, incluidos sus borradores; no las reemplaza por un nuevo Inicio.
 */
class AplicacionAgenda : Application(), Application.ActivityLifecycleCallbacks {

    private val iniciadas = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val conocidas = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val cubiertas = WeakHashMap<Activity, Int>()
    private var desbloqueoAbierto = false
    private var recuperacionAbierta = false

    /**
     * Android llama al arrancar el proceso. Recupera guardados interrumpidos antes de usar sus datos.
     * Después registra los cambios de pantalla; este trabajo no activa micrófonos ni modelos de voz.
     */
    override fun onCreate() {
        super.onCreate()
        try {
            CopiaSeguridadLocal.recuperarPendiente(this)
        } catch (error: Exception) {
            EstadoArranque.problema = "No se pudo recuperar una restauración interrumpida."
            android.util.Log.e("AgendaVIP", "Recuperación pendiente: ${error.javaClass.simpleName}")
        }
        EliminadorCuenta(this).recuperarPendientes()
        registerActivityLifecycleCallbacks(this)
    }

    /**
     * Recibe una pantalla recién creada y sus datos temporales. Prepara su privacidad antes de verla.
     * FLAG_SECURE pide a Android ocultar sus capturas y su miniatura si el bloqueo está activado.
     */
    override fun onActivityCreated(actividad: Activity, estado: Bundle?) {
        conocidas.add(actividad)
        prepararPrivacidad(actividad)
    }

    /**
     * Android entrega la pantalla que empieza a mostrarse. La cuenta como visible y comprueba bloqueo.
     * Una pantalla de desbloqueo ya abierta no se vuelve a abrir encima de sí misma.
     */
    override fun onActivityStarted(actividad: Activity) {
        iniciadas.add(actividad)
        com.nowel.agendavip.datos.servidor.ProgramadorConexionPC.mostrar(this)
        comprobarApertura(actividad)
    }

    /** Android entrega la pantalla que vuelve al frente; recupera su contenido solo tras autorizarlo. */
    override fun onActivityResumed(actividad: Activity) {
        comprobarApertura(actividad)
    }

    /**
     * Recibe la pantalla que pierde el primer plano. Cubre su contenido antes de pasar a otra app.
     * La vista sigue creada: al volver se conservan texto, selección musical y cambios sin guardar.
     */
    override fun onActivityPaused(actividad: Activity) {
        if (actividad !is ActividadDesbloqueo && BloqueoLocal.estaActivado(this)) {
            cubrir(actividad)
        }
    }

    /**
     * Recibe la pantalla que dejó de verse. Si no queda ninguna, retira la autorización temporal.
     * Girar el teléfono no cuenta como abandonar la aplicación y no crea un bloqueo adicional.
     */
    override fun onActivityStopped(actividad: Activity) {
        iniciadas.remove(actividad)

        if (iniciadas.isEmpty() && !actividad.isChangingConfigurations) {
            com.nowel.agendavip.datos.servidor.ProgramadorConexionPC.ocultar()
            BloqueoLocal.bloquear()
        }
    }

    /** Los borradores los guarda cada pantalla; este coordinador no copia sus datos personales. */
    override fun onActivitySaveInstanceState(actividad: Activity, estado: Bundle) = Unit

    /** Android entrega una pantalla destruida; elimina sus referencias para no ocupar memoria. */
    override fun onActivityDestroyed(actividad: Activity) {
        iniciadas.remove(actividad)
        conocidas.remove(actividad)
        cubiertas.remove(actividad)
        if (actividad is com.nowel.agendavip.presentacion.acceso.ActividadRecuperacion) {
            recuperacionAbierta = false
        }

        if (actividad is ActividadDesbloqueo && !actividad.isChangingConfigurations) {
            desbloqueoAbierto = false
        }
    }

    /**
     * Seguridad la llama después de cambiar el bloqueo. Actualiza las ventanas ya creadas.
     * No recibe ni devuelve datos; no toca las preferencias ni vuelve a pedir una contraseña.
     */
    fun actualizarVentanas() {
        conocidas.toList().forEach { actividad ->
            prepararPrivacidad(actividad)
        }
    }

    /**
     * Recibe una pantalla y aplica la protección de capturas que corresponde a la elección actual.
     * Seguridad y Desbloqueo siempre se protegen porque pueden mostrar campos privados.
     */
    private fun prepararPrivacidad(actividad: Activity) {
        val protegida = actividad is ActividadSeguridad || actividad is ActividadDesbloqueo ||
            actividad is com.nowel.agendavip.presentacion.servidor.ActividadConexionPC ||
            actividad is com.nowel.agendavip.presentacion.cuenta.ActividadRespaldo ||
            actividad is com.nowel.agendavip.presentacion.acceso.ActividadRecuperacion ||
            BloqueoLocal.estaActivado(this)

        if (protegida) {
            actividad.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            actividad.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (actividad !is ActividadDesbloqueo && BloqueoLocal.necesitaDesbloqueo(this)) {
            cubrir(actividad)
        }
    }

    /**
     * Recibe la pantalla que va a verse; devuelve su contenido o abre el acceso protegido encima.
     * La pantalla de debajo no se termina, por eso sus borradores se recuperan al desbloquear.
     */
    private fun comprobarApertura(actividad: Activity) {
        if (actividad is com.nowel.agendavip.presentacion.acceso.ActividadRecuperacion) {
            recuperacionAbierta = true
            restaurar(actividad)
            return
        }
        if (EstadoArranque.problema != null) {
            cubrir(actividad)
            if (!recuperacionAbierta && !actividad.isFinishing) {
                recuperacionAbierta = true
                actividad.startActivity(Intent(actividad,
                    com.nowel.agendavip.presentacion.acceso.ActividadRecuperacion::class.java))
            }
            return
        }
        prepararPrivacidad(actividad)

        if (actividad is ActividadDesbloqueo) {
            desbloqueoAbierto = true
            return
        }

        if (!BloqueoLocal.necesitaDesbloqueo(this)) {
            restaurar(actividad)
            return
        }

        cubrir(actividad)

        if (desbloqueoAbierto || actividad.isFinishing) {
            return
        }

        desbloqueoAbierto = true

        try {
            actividad.startActivity(
                Intent(actividad, ActividadDesbloqueo::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: RuntimeException) {
            desbloqueoAbierto = false
            AlertDialog.Builder(actividad)
                .setTitle("La agenda está protegida")
                .setMessage("No se pudo abrir el desbloqueo. Cierra la aplicación y vuelve a abrirla.")
                .setPositiveButton("Salir") { _, _ -> actividad.moveTaskToBack(true) }
                .setCancelable(false)
                .show()
        }
    }

    /** Recibe una pantalla y esconde su vista sin borrar campos ni cambiar su tamaño. */
    private fun cubrir(actividad: Activity) {
        val vista = actividad.window.decorView
        if (!cubiertas.containsKey(actividad)) {
            cubiertas[actividad] = vista.visibility
        }
        vista.visibility = View.INVISIBLE
    }

    /** Recibe una pantalla autorizada y devuelve la visibilidad que tenía antes de cubrirla. */
    private fun restaurar(actividad: Activity) {
        val anterior = cubiertas.remove(actividad) ?: return
        actividad.window.decorView.visibility = anterior
    }
}
