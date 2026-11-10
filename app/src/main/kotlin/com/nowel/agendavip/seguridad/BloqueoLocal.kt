package com.nowel.agendavip.seguridad

import android.app.KeyguardManager
import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal

/**
 * Recuerda la elección de bloqueo de cada cuenta y una autorización temporal en memoria.
 * No guarda huellas, el PIN del teléfono ni contraseñas. Android comprueba esos datos.
 * Las pantallas de seguridad y AplicacionAgenda la usan sin cerrar la sesión ni parar alarmas.
 */
object BloqueoLocal {

    @Volatile
    private var propietarioAutorizado: String? = null

    /**
     * Recibe Android y el dueño consultado; devuelve si pidió proteger la apertura de su agenda.
     * El valor inicial es false: instalar esta versión no activa un bloqueo inesperado.
     */
    fun estaActivado(contexto: Context, propietario: String? = EspacioPersonal.claveActual(contexto)): Boolean {
        if (propietario == null) {
            return false
        }

        return contexto.applicationContext.getSharedPreferences(
            EspacioPersonal.nombrePreferencias("seguridad_local", propietario),
            Context.MODE_PRIVATE
        ).getBoolean("bloqueo_activado", false)
    }

    /**
     * Recibe Android y devuelve si el teléfono tiene PIN, patrón o contraseña configurados.
     * Seguridad la consulta antes de ofrecer la activación; no cambia los ajustes del teléfono.
     */
    fun telefonoProtegido(contexto: Context): Boolean {
        return contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure
    }

    /**
     * Recibe Android y devuelve si la cuenta abierta todavía necesita autorizar esta apertura.
     * La autorización solo vive en memoria: al cerrar el proceso vuelve a ser necesaria.
     */
    fun necesitaDesbloqueo(contexto: Context): Boolean {
        val propietario = EspacioPersonal.claveActual(contexto)

        return estaActivado(contexto, propietario) && propietarioAutorizado != propietario
    }

    /**
     * Recibe Android y el dueño autenticado; permite mostrar sus pantallas en esta apertura.
     * El resultado de Android o la contraseña correcta llegan aquí. Rechaza una cuenta que cambió.
     */
    fun autorizar(contexto: Context, propietario: String) {
        check(EspacioPersonal.claveActual(contexto) == propietario) {
            "La cuenta cambió. Vuelve a abrir la aplicación."
        }
        propietarioAutorizado = propietario
    }

    /**
     * No recibe ni devuelve datos. La aplicación la llama cuando todas sus pantallas se ocultan.
     * Retira únicamente la autorización visual; la cuenta y sus recordatorios siguen activos.
     */
    fun bloquear() {
        propietarioAutorizado = null
    }

    /**
     * Recibe Android, el dueño y si desea activar el bloqueo, después de autenticarse.
     * Seguridad la llama; guarda la elección y permite seguir usando la pantalla actual.
     * Si falla el guardado, informa del problema en lugar de anunciar una protección inexistente.
     */
    fun guardarActivacion(contexto: Context, propietario: String, activar: Boolean) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            check(EspacioPersonal.claveActual(contexto) == propietario) {
                "La cuenta cambió. Vuelve a abrir Seguridad."
            }
            check(!activar || telefonoProtegido(contexto)) {
                "Configura primero un PIN, patrón o contraseña en el teléfono."
            }

            val guardada = contexto.applicationContext.getSharedPreferences(
                EspacioPersonal.nombrePreferencias("seguridad_local", propietario),
                Context.MODE_PRIVATE
            ).edit().putBoolean("bloqueo_activado", activar).commit()

            check(guardada) {
                "No se pudo guardar la elección del bloqueo. Inténtalo de nuevo."
            }
            propietarioAutorizado = if (activar) propietario else null
        }
    }
}
