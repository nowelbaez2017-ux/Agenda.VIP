package com.nowel.agendavip.datos.respaldo

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Prepara el cifrado de las copias ligadas a este teléfono.
 * Android Keystore conserva una clave para esta instalación dentro del sistema.
 * Respaldo entrega cada operación al cuadro de biometría para que Android autorice su uso.
 */
internal object ClaveRespaldoTelefono {

    private const val ALMACEN = "AndroidKeyStore"
    private const val NOMBRE = "agenda_vip_respaldo_telefono_v1"
    private const val MENSAJE_CLAVE_PERDIDA =
        "Esta copia necesita la instalación y el bloqueo de pantalla con los que se guardó."

    /**
     * Recibe el número único de una copia, o null para cifrar una nueva.
     * Devuelve una operación de cifrado o descifrado para autorizar con la biometría de Android.
     * CopiaSeguridadLocal la llama después de comprobar la sesión.
     */
    @Synchronized
    fun preparar(nonce: ByteArray? = null): Cipher {
        check(Build.VERSION.SDK_INT >= 28) {
            "Este teléfono necesita una copia con contraseña."
        }

        val almacen = KeyStore.getInstance(ALMACEN).apply {
            load(null)
        }
        val clave = (almacen.getKey(NOMBRE, null) as? SecretKey)
            ?: if (nonce == null) generar() else error(MENSAJE_CLAVE_PERDIDA)

        return try {
            iniciar(clave, nonce)
        } catch (error: KeyPermanentlyInvalidatedException) {
            if (nonce != null) {
                throw IllegalStateException(MENSAJE_CLAVE_PERDIDA, error)
            }

            // Quitar el bloqueo de pantalla puede destruir el acceso a la clave.
            // Una copia nueva necesita otra clave cuando Android confirma esa pérdida.
            almacen.deleteEntry(NOMBRE)
            iniciar(generar(), null)
        }
    }

    /**
     * Crea y devuelve la clave de instalación si aún no hay una utilizable.
     * Android exige biometría fuerte en cada operación. Agregar una huella con el
     * bloqueo de pantalla todavía configurado conserva el acceso a las copias.
     */
    @Suppress("DEPRECATION")
    private fun generar(): SecretKey {
        val reglas = KeyGenParameterSpec.Builder(
            NOMBRE,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)

        if (Build.VERSION.SDK_INT >= 30) {
            reglas.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            reglas.setUserAuthenticationValidityDurationSeconds(-1)
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ALMACEN).run {
            init(reglas.build())
            generateKey()
        }
    }

    /**
     * Recibe la clave y, al restaurar, el número único guardado en el archivo.
     * Devuelve el Cipher (la operación que cifra o descifra). Android elige un número
     * nuevo al guardar para que dos respaldos nunca reutilicen el mismo de forma deliberada.
     */
    private fun iniciar(clave: SecretKey, nonce: ByteArray?): Cipher {
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (nonce == null) {
                init(Cipher.ENCRYPT_MODE, clave)
            } else {
                init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(128, nonce))
            }
        }
    }
}
