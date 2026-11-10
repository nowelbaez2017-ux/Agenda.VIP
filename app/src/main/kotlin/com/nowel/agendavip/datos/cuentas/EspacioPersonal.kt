package com.nowel.agendavip.datos.cuentas

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Separa los archivos de cada cuenta. Recibe el acceso a Android y recuerda quién está dentro.
 * Una instancia pertenece siempre a esa persona: nunca cambia de dueño silenciosamente.
 * Los repositorios la usan para evitar que una pantalla antigua guarde datos en otra cuenta.
 */
class EspacioPersonal(contexto: Context) {

    private val aplicacion = contexto.applicationContext

    val clave: String = claveActual(aplicacion)
        ?: throw IllegalStateException("Entra en una cuenta o elige continuar sin cuenta.")

    /**
     * Recibe el nombre común de un archivo y devuelve el archivo privado de esta persona.
     * Los repositorios llaman aquí al consultar sus datos. Rechaza una sesión que ya cambió.
     */
    fun preferencias(nombreBase: String): SharedPreferences {
        exigirVigente()

        return aplicacion.getSharedPreferences(
            nombrePreferencias(nombreBase, clave),
            Context.MODE_PRIVATE,
        )
    }

    /** Los trabajos pendientes la consultan; devuelve si la misma persona sigue dentro. */
    fun vigente(): Boolean = claveActual(aplicacion) == clave

    /**
     * Los repositorios la llaman antes de leer o guardar. No devuelve datos.
     * Si se cerró la sesión, pide volver al acceso en lugar de usar los archivos de otra persona.
     */
    fun exigirVigente() {
        check(com.nowel.agendavip.seguridad.EstadoArranque.problema == null) {
            "Primero termina la recuperación de la agenda desde la aplicación."
        }
        check(vigente()) {
            "La sesión cambió. Vuelve a entrar para continuar."
        }
    }

    companion object {
        const val INVITADO = "INVITADO"
        const val EXTRA_ESPACIO = "espacio_personal"

        // Cambiar de cuenta y procesar una alarma esperan el mismo turno.
        // Así una alarma no puede quedar programada entre cerrar la cuenta y abrir la siguiente.
        val cerrojoSesion = Any()

        /** Recibe Android y devuelve la cuenta activa, INVITADO o null si no hay entrada. */
        fun claveActual(contexto: Context): String? = SesionLocal(contexto).espacioActivo

        /**
         * Recibe un nombre de archivo y una identidad validada; devuelve su nombre privado.
         * El importador también la usa para copiar una agenda con permiso de la persona.
         * INVITADO conserva el archivo anterior para no perder la agenda que ya existía.
         */
        fun nombrePreferencias(nombreBase: String, clave: String): String {
            require(nombreBase.matches(Regex("[a-z_]+"))) {
                "El nombre del archivo no es válido."
            }

            if (clave == INVITADO) {
                return nombreBase
            }

            val identidad = runCatching { UUID.fromString(clave).toString() }.getOrNull()

            require(identidad == clave) {
                "La identidad de la cuenta no es válida."
            }

            return "${nombreBase}_cuenta_$clave"
        }
    }
}