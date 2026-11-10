package com.nowel.agendavip.seguridad

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.Executor

/**
 * Pide a Android verificar la huella o la credencial del teléfono, sin leer esos secretos.
 * Recibe la pantalla, las acciones para éxito o error y el título de la comprobación.
 * Seguridad, Desbloqueo y la ayuda de acceso comparten el cuadro gratuito del sistema.
 */
class AutenticacionDispositivo(
    private val actividad: Activity,
    private val alAutorizar: () -> Unit,
    private val alFallar: (String) -> Unit,
    private val titulo: String = "Desbloquear Agenda.VIP",
) {

    private var cancelacion: CancellationSignal? = null
    private var esperandoCredencial = false
    private var cerrada = false
    private val principal = Executor { accion -> actividad.runOnUiThread(accion) }

    /**
     * El botón Desbloquear o la elección Activar la llama sin argumentos.
     * Muestra el cuadro del sistema; en Android antiguo utiliza el PIN, patrón o contraseña.
     */
    @Suppress("DEPRECATION")
    fun solicitar() {
        if (cerrada || esperandoCredencial || cancelacion != null) {
            return
        }
        if (!BloqueoLocal.telefonoProtegido(actividad)) {
            alFallar("Configura un PIN, patrón o contraseña en los ajustes de seguridad del teléfono.")
            return
        }

        if (Build.VERSION.SDK_INT < 28) {
            abrirCredencial()
            return
        }

        val constructor = BiometricPrompt.Builder(actividad)
            .setTitle(titulo)
            .setSubtitle("Usa la huella o la credencial de este teléfono")

        if (Build.VERSION.SDK_INT >= 30) {
            constructor.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            constructor.setDeviceCredentialAllowed(true)
        } else {
            constructor.setNegativeButton("Usar PIN", principal) { _, _ ->
                abrirCredencial()
            }
        }

        val solicitud = CancellationSignal()
        cancelacion = solicitud

        try {
            constructor.build().authenticate(solicitud, principal, object : BiometricPrompt.AuthenticationCallback() {

                /** Android confirma la comprobación; la pantalla decide qué operación autorizar. */
                override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult?) {
                    cancelacion = null
                    if (!cerrada) {
                        alAutorizar()
                    }
                }

                /**
                 * Android entrega la causa de un rechazo. Si falta biometría ofrece la credencial.
                 * Cancelar no concede acceso ni vuelve a abrir el cuadro automáticamente.
                 */
                override fun onAuthenticationError(codigo: Int, mensaje: CharSequence?) {
                    cancelacion = null
                    if (cerrada || esperandoCredencial) {
                        return
                    }
                    if (codigo == 5 || codigo == 10 || codigo == 13) {
                        alFallar("Desbloqueo cancelado. Puedes intentarlo otra vez.")
                    } else {
                        abrirCredencial()
                    }
                }

                /** Una huella no reconocida mantiene abierto el cuadro para otro intento. */
                override fun onAuthenticationFailed() = Unit
            })
        } catch (_: RuntimeException) {
            cancelacion = null
            abrirCredencial()
        }
    }

    /**
     * Recibe el código y el resultado que devuelve Android tras mostrar el PIN del teléfono.
     * La pantalla la llama desde onActivityResult; devuelve true si atendió esta solicitud.
     */
    fun recibirResultado(codigo: Int, resultado: Int): Boolean {
        if (codigo != SOLICITUD_CREDENCIAL) {
            return false
        }
        esperandoCredencial = false

        if (!cerrada) {
            if (resultado == Activity.RESULT_OK) {
                alAutorizar()
            } else {
                alFallar("No se confirmó la credencial del teléfono.")
            }
        }
        return true
    }

    /**
     * Abre el PIN, patrón o contraseña que Android ya tiene configurados.
     * La biometría la llama como alternativa; nunca se solicita escribir el PIN dentro de Agenda.VIP.
     */
    @Suppress("DEPRECATION")
    private fun abrirCredencial() {
        if (cerrada || esperandoCredencial) {
            return
        }

        val sistema = actividad.getSystemService(KeyguardManager::class.java)
        val solicitud = sistema.createConfirmDeviceCredentialIntent(
            "Agenda.VIP",
            "Confirma tu identidad para continuar"
        )

        if (solicitud == null) {
            alFallar("Configura un PIN, patrón o contraseña en los ajustes del teléfono.")
            return
        }

        esperandoCredencial = true
        cancelacion?.cancel()
        cancelacion = null

        try {
            actividad.startActivityForResult(solicitud, SOLICITUD_CREDENCIAL)
        } catch (_: RuntimeException) {
            esperandoCredencial = false
            alFallar("No se pudo abrir la comprobación del teléfono. Inténtalo de nuevo.")
        }
    }

    /** La pantalla la llama al destruirse; cancela la escucha biométrica y descarta sus respuestas. */
    fun cerrar() {
        cerrada = true
        cancelacion?.cancel()
        cancelacion = null
    }

    private companion object {
        const val SOLICITUD_CREDENCIAL = 4109
    }
}
