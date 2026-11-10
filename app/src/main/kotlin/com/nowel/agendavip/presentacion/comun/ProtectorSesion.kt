package com.nowel.agendavip.presentacion.comun

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.presentacion.acceso.ActividadAcceso

/**
 * Cada pantalla recuerda a qué persona pertenece mientras permanece abierta.
 * Recibe esa pantalla y comprueba la sesión antes de enseñar o recuperar sus datos.
 * No verifica contraseñas: esa tarea corresponde al registro y al inicio de sesión.
 */
class ProtectorSesion(private val actividad: Activity) {

    private var propietario: String? = null

    /**
     * onCreate la llama antes de crear repositorios. Recibe el estado guardado al girar.
     * Devuelve true solo si la pantalla y la petición pertenecen a quien está dentro.
     * Una notificación de otra cuenta se ignora sin mostrar sus tareas.
     */
    fun permitir(estado: Bundle?): Boolean {
        if (necesitaRecuperacion()) {
            return false
        }
        val actual = EspacioPersonal.claveActual(actividad)

        if (actual == null) {
            volverAlAcceso()
            return false
        }

        propietario = estado?.getString(CLAVE_ESTADO) ?: actual

        if (propietario != actual || !aceptarIntent(actividad.intent)) {
            actividad.finish()
            return false
        }

        return true
    }

    /**
     * onResume la llama al volver a la pantalla. No recibe datos.
     * Devuelve false y abre Acceso si cambió o se cerró la sesión mientras estaba oculta.
     */
    fun comprobar(): Boolean {
        if (necesitaRecuperacion()) {
            return false
        }
        if (propietario == null || EspacioPersonal.claveActual(actividad) != propietario) {
            volverAlAcceso()
            return false
        }

        return true
    }

    /** Recibe una petición nueva y devuelve si su dueño coincide; la Agenda ignora las antiguas. */
    fun aceptarIntent(intento: Intent): Boolean {
        val destino = intento.getStringExtra(EspacioPersonal.EXTRA_ESPACIO)

        val vieneDeAviso = intento.action in setOf("abrir_detalles", "abrir_agenda")

        return if (vieneDeAviso) {
            destino != null && destino == propietario
        } else {
            destino == null || destino == propietario
        }
    }

    /** Android entrega este paquete; recuerda el dueño para no restaurar borradores de otra cuenta. */
    fun guardarEstado(estado: Bundle) {
        estado.putString(CLAVE_ESTADO, propietario)
    }

    /**
     * Las comprobaciones la llaman cuando no se puede continuar. Abre el acceso y retira
     * las pantallas anteriores para que Atrás no muestre una cuenta cuya sesión terminó.
     */
    private fun volverAlAcceso() {
        actividad.startActivity(
            Intent(actividad, ActividadAcceso::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        actividad.finish()
    }

    /**
     * Impide abrir datos parcialmente restaurados si Android no confirmó su recuperación.
     * Permitir y Comprobar la llaman antes de consultar repositorios. Devuelve true si redirigió.
     */
    private fun necesitaRecuperacion(): Boolean {
        if (com.nowel.agendavip.seguridad.EstadoArranque.problema == null) {
            return false
        }
        actividad.startActivity(Intent(actividad,
            com.nowel.agendavip.presentacion.acceso.ActividadRecuperacion::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        actividad.finish()
        return true
    }

    companion object {
        private const val CLAVE_ESTADO = "pantalla_espacio_personal"
    }
}