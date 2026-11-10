package com.nowel.agendavip.datos.asistente

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Guarda el archivo Whisper que Enfoque usa para convertir una frase en texto.
 * Recibe el acceso a Android. La pantalla de preparación copia aquí el archivo que
 * la persona selecciona y Enfoque comprueba si está disponible antes de escuchar.
 */
class ModelosLocales(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val carpeta = File(this.contexto.filesDir, "modelos_locales")

    val archivo = File(carpeta, "voz.bin")

    /**
     * Devuelve true si hay un archivo instalado. Enfoque consulta este estado antes de
     * iniciar el micrófono; Whisper comprueba su compatibilidad al cargarlo.
     */
    fun instalado(): Boolean {
        return archivo.isFile && archivo.length() > 0L
    }

    /**
     * Recibe la dirección elegida en Archivos y devuelve el tamaño copiado.
     * La pantalla de preparación la llama fuera del trabajo de dibujo de Android.
     * Primero copia a un temporal y después lo cambia de nombre de forma atómica: se sustituye
     * todo el archivo a la vez. Un fallo conserva el modelo anterior y borra solo el temporal.
     */
    fun importar(uri: Uri): Long {
        check(uri.scheme == "content") {
            "Selecciona el archivo desde el selector del teléfono."
        }

        if (!carpeta.isDirectory && !carpeta.mkdirs()) {
            throw IOException("No se pudo preparar la carpeta de modelos.")
        }

        val temporal = File.createTempFile("importacion_", ".temporal", carpeta)

        try {
            val entrada = contexto.contentResolver.openInputStream(uri)
                ?: throw IOException("No se pudo abrir el archivo seleccionado.")
            var total = 0L

            entrada.use { origen ->
                FileOutputStream(temporal).use { salida ->
                    val bloque = ByteArray(256 * 1024)
                    var siguienteRevision = 0L

                    while (true) {
                        comprobarCancelacion()
                        val cantidad = origen.read(bloque)

                        if (cantidad == -1) {
                            break
                        }

                        total += cantidad
                        check(total <= 2L * 1024 * 1024 * 1024) {
                            "El archivo del modelo de voz supera el límite de 2 GB."
                        }

                        if (total >= siguienteRevision) {
                            check(StatFs(carpeta.absolutePath).availableBytes > cantidad + 32L * 1024 * 1024) {
                                "No queda espacio suficiente para copiar el modelo."
                            }
                            siguienteRevision = total + 8L * 1024 * 1024
                        }

                        salida.write(bloque, 0, cantidad)
                    }

                    salida.fd.sync()
                }
            }

            check(total >= 1024 * 1024) {
                "El archivo no parece un modelo completo. Selecciona el archivo descargado, sin comprimir."
            }

            // Los primeros cuatro bytes identifican el formato que Whisper puede abrir.
            val cabecera = ByteArray(4)
            temporal.inputStream().use { lectura ->
                check(lectura.read(cabecera) == cabecera.size) {
                    "El modelo de voz está incompleto."
                }
            }
            check(ByteBuffer.wrap(cabecera).order(ByteOrder.LITTLE_ENDIAN).int == 0x67676d6c) {
                "La voz necesita un modelo Whisper GGML .bin; un ZIP o un modelo GGUF no sirven aquí."
            }

            comprobarCancelacion()
            Files.move(
                temporal.toPath(),
                archivo.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return total
        } finally {
            temporal.delete()
        }
    }

    /** La copia la llama para parar al salir de la pantalla, conservando el archivo anterior. */
    private fun comprobarCancelacion() {
        check(!Thread.currentThread().isInterrupted) {
            "La copia se canceló. Puedes volver a seleccionar el archivo."
        }
    }
}
