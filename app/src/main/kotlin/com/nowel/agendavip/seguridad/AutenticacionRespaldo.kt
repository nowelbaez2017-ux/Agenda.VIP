package com.nowel.agendavip.seguridad

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.Executor
import javax.crypto.Cipher

/**
 * Conecta el cuadro de huella de Android con la llave de la copia de seguridad.
 * Android verifica la huella registrada y permite usar el cifrador una sola vez.
 * Un cifrador es la pieza que convierte los datos en contenido ilegible o los vuelve a abrir.
 */
internal class AutenticacionRespaldo(
    private val actividad: Activity,
    private val alFallar: (String) -> Unit
) {

    private val principal = Executor { accion -> actividad.runOnUiThread(accion) }
    private var solicitud: CancellationSignal? = null
    private var cerrada = false

    /**
     * La pantalla de respaldo pregunta si puede mostrar el cuadro de huella.
     * Devuelve una explicación para el usuario o null si puede continuar.
     */
    fun motivoNoDisponible(): String? {
        if (Build.VERSION.SDK_INT < 28) {
            return "Este Android requiere la opción Copia para otro teléfono."
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val sistema = actividad.getSystemService(BiometricManager::class.java)
            return when (sistema.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> null
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Registra una huella en los ajustes de seguridad del teléfono."
                else -> "La huella segura no está disponible. Puedes crear una copia para otro teléfono."
            }
        }

        // Android 9 y 10 comprueban la disponibilidad cuando se abre el cuadro.
        return null
    }

    /**
     * Recibe el título que verá la persona, el cifrador preparado y la acción autorizada.
     * Crear o restaurar llama aquí antes de tocar los datos. Solo el resultado de Android
     * entrega el cifrador a la acción que lo necesita.
     */
    fun solicitar(titulo: String, cifrador: Cipher, alAutorizar: (Cipher) -> Unit) {
        if (cerrada || solicitud != null || Build.VERSION.SDK_INT < 28) {
            return
        }

        val cancelacion = CancellationSignal()
        solicitud = cancelacion

        try {
            val constructor = BiometricPrompt.Builder(actividad)
                .setTitle(titulo)
                .setSubtitle("Confirma con la huella registrada en este teléfono")
                .setNegativeButton("Cancelar", principal) { _, _ ->
                    if (solicitud === cancelacion) {
                        solicitud = null
                        cancelacion.cancel()
                        alFallar("Cancelaste la comprobación de huella.")
                    }
                }

            if (Build.VERSION.SDK_INT >= 30) {
                constructor.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }

            constructor.build().authenticate(
                BiometricPrompt.CryptoObject(cifrador),
                cancelacion,
                principal,
                object : BiometricPrompt.AuthenticationCallback() {

                    /** Android entrega el cifrador autorizado y la pantalla puede usarlo. */
                    override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                        if (cerrada || solicitud !== cancelacion) {
                            return
                        }
                        solicitud = null

                        val autorizado = resultado.cryptoObject?.cipher
                        if (autorizado == null) {
                            alFallar("Android no autorizó el acceso a la copia de seguridad.")
                            return
                        }
                        alAutorizar(autorizado)
                    }

                    /** Android indica por qué se cerró el cuadro. El archivo queda sin usar. */
                    override fun onAuthenticationError(codigo: Int, mensaje: CharSequence?) {
                        if (!cerrada && solicitud === cancelacion) {
                            solicitud = null
                            alFallar(mensaje?.toString() ?: "No se confirmó la huella.")
                        }
                    }
                }
            )
        } catch (_: RuntimeException) {
            solicitud = null
            alFallar("No se pudo abrir el cuadro de huella de Android.")
        }
    }

    /** La pantalla la llama al cerrarse para cancelar el cuadro y descartar sus respuestas. */
    fun cerrar() {
        cerrada = true
        solicitud?.cancel()
        solicitud = null
    }
}