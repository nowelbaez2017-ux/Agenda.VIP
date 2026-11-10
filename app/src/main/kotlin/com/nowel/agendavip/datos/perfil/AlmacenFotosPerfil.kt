package com.nowel.agendavip.datos.perfil

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Conserva una copia privada de la foto elegida en la galería.
 * Recibe Android y recuerda la cuenta actual. ControladorPerfil lo utiliza en segundo plano,
 * es decir, fuera del trabajo que dibuja la pantalla, para no detener los botones al copiar.
 * Así la foto no depende de que la galería conceda un permiso permanente sobre el original.
 */
internal class AlmacenFotosPerfil(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val espacio = EspacioPersonal(this.contexto)

    /**
     * Recibe la URI de la imagen elegida y devuelve el archivo privado que acaba de guardar.
     * Una URI es la dirección de un contenido autorizado por Android.
     * El controlador convierte el archivo devuelto en la dirección que guarda PerfilLocal.
     * Si se cancela, falla la copia o no es una imagen legible, elimina solo la copia nueva;
     * la foto anterior y el archivo original de la galería permanecen intactos.
     */
    fun copiar(uri: Uri): File {
        comprobarVigencia()
        require(uri.scheme in setOf("content", "file")) {
            "La galería no entregó una imagen local que se pueda abrir."
        }

        val carpeta = File(contexto.filesDir, "fotos_perfil/${espacio.clave}")

        if (!carpeta.isDirectory && !carpeta.mkdirs()) {
            throw IOException("No se pudo preparar la carpeta privada de fotos.")
        }

        val temporal = File.createTempFile("foto_", ".temporal", carpeta)
        val definitiva = File(carpeta, "${UUID.randomUUID()}.imagen")
        var guardada = false

        try {
            val entrada = contexto.contentResolver.openInputStream(uri)
                ?: throw IOException("La galería no permitió abrir esta foto.")

            entrada.use { origen ->
                FileOutputStream(temporal).use { destino ->
                    val bloque = ByteArray(16 * 1024)
                    var total = 0L

                    while (true) {
                        comprobarVigencia()
                        val cantidad = origen.read(bloque)

                        if (cantidad == -1) {
                            break
                        }

                        total += cantidad

                        // Evita copiar un archivo desmesurado cuando solo se necesita un avatar.
                        if (total > 32L * 1024 * 1024) {
                            throw IOException("La foto supera 32 MB. Elige una imagen más pequeña.")
                        }

                        destino.write(bloque, 0, cantidad)
                    }

                    destino.fd.sync()
                }
            }

            // Solo lee el tamaño de la imagen, sin cargar todos sus píxeles en memoria.
            val lectura = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(temporal.absolutePath, lectura)

            if (lectura.outWidth <= 0 || lectura.outHeight <= 0) {
                throw IOException("No se pudo leer esa imagen. Elige otra foto de la galería.")
            }

            comprobarVigencia()

            if (!temporal.renameTo(definitiva)) {
                throw IOException("No se pudo guardar la foto en este teléfono.")
            }

            comprobarVigencia()
            guardada = true
            return definitiva
        } finally {
            temporal.delete()

            if (!guardada) {
                definitiva.delete()
            }
        }
    }

    /**
     * copiar la llama antes y durante la lectura para atender una cancelación o cambio de cuenta.
     * No recibe ni devuelve datos: interrumpe la copia sin sustituir el perfil si ya no corresponde.
     */
    private fun comprobarVigencia() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("Se canceló el cambio de foto.")
        }

        espacio.exigirVigente()
    }
}