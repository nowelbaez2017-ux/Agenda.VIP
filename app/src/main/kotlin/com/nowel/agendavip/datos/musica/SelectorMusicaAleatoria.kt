package com.nowel.agendavip.datos.musica

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import kotlin.random.Random

/**
 * Busca una canción local cuando un recordatorio conserva la opción Música aleatoria.
 * ServicioAviso la utiliza en un trabajo separado del que dibuja la pantalla o habla.
 * Recibe Android mediante [contexto]; no descarga música ni cambia archivos del teléfono.
 * MediaStore es el catálogo de audios que mantiene Android. Solo se leen direcciones:
 * no hace falta abrir todas las canciones ni cargar sus nombres, imágenes o etiquetas.
 */
class SelectorMusicaAleatoria(contexto: Context) {

    private val aplicacion = contexto.applicationContext
    private val contenido = aplicacion.contentResolver

    /**
     * ServicioAviso la llama después de la voz con una señal de [cancelacion].
     * Devuelve la dirección de una canción legible, o null si no encuentra ninguna.
     * Conserva hasta cuatro candidatas al azar, sin guardar la biblioteca entera.
     * Abre únicamente esas candidatas para comprobar que sigan disponibles.
     * Si falta permiso o se cancela, comunica el problema al servicio mediante un error.
     */
    fun elegir(cancelacion: CancellationSignal): Uri? {
        cancelacion.throwIfCanceled()

        if (!tienePermiso()) {
            throw SecurityException("Falta el permiso para leer la biblioteca musical.")
        }

        val candidatas = consultarCandidatas(cancelacion).shuffled()

        candidatas.forEach { uri ->
            cancelacion.throwIfCanceled()

            try {
                val disponible = contenido.openAssetFileDescriptor(uri, "r", cancelacion)?.use { archivo ->
                    cancelacion.throwIfCanceled()
                    archivo.declaredLength != 0L && archivo.fileDescriptor.valid()
                } ?: false

                if (disponible) {
                    cancelacion.throwIfCanceled()
                    return uri
                }
            } catch (_: Exception) {
                // Un archivo puede haberse eliminado o una SD puede haberse retirado.
                // Cancelar nunca debe iniciar otra apertura de archivo.
                cancelacion.throwIfCanceled()
            }
        }

        cancelacion.throwIfCanceled()
        return null
    }

    /**
     * elegir la llama sin argumentos antes de consultar la biblioteca.
     * Devuelve true si Android permite leer audio en esta versión del teléfono.
     * El servicio no puede abrir una pregunta de permisos: la pantalla los pide al guardar.
     */
    private fun tienePermiso(): Boolean {
        val permiso = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return aplicacion.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * elegir entrega una [cancelacion] y recibe como máximo cuatro direcciones distintas.
     * Cada canción tiene la misma oportunidad de entrar en la reserva mientras se recorre
     * el catálogo. Esta forma de elegir al azar conserva una cantidad fija de datos en memoria.
     * Se aceptan audios clasificados como música o guardados en Music; se excluyen timbres,
     * tonos, archivos vacíos, sin duración, aún copiándose o enviados a la papelera.
     * Así la elección automática evita notas de voz y sonidos del sistema. La selección
     * manual de la biblioteca sigue disponible para audios que Android no clasificó como música.
     */
    private fun consultarCandidatas(cancelacion: CancellationSignal): List<Uri> {
        val coleccion = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val columnas = mutableListOf(MediaStore.Audio.Media._ID)

        if (Build.VERSION.SDK_INT >= 29) {
            columnas += MediaStore.MediaColumns.VOLUME_NAME
        }

        val condiciones = mutableListOf(
            "${MediaStore.MediaColumns.SIZE} > 0",
            "${MediaStore.Audio.Media.DURATION} > 0",
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0",
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0",
            "${MediaStore.Audio.Media.IS_ALARM} = 0",
        )

        if (Build.VERSION.SDK_INT >= 29) {
            condiciones += "(${MediaStore.Audio.Media.IS_MUSIC} = 1 OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Music/%')"
            condiciones += "${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else {
            condiciones += "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        }

        if (Build.VERSION.SDK_INT >= 30) {
            condiciones += "${MediaStore.MediaColumns.IS_TRASHED} = 0"
        }

        val candidatas = ArrayList<Uri>(MAXIMO_CANDIDATAS)
        var encontradas = 0L

        contenido.query(
            coleccion,
            columnas.toTypedArray(),
            condiciones.joinToString(" AND "),
            null,
            null,
            cancelacion,
        )?.use { cursor ->
            val columnaId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val columnaVolumen = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                cancelacion.throwIfCanceled()
                encontradas += 1L

                val posicion = if (candidatas.size < MAXIMO_CANDIDATAS) {
                    candidatas.size.toLong()
                } else {
                    Random.nextLong(encontradas)
                }

                if (posicion < MAXIMO_CANDIDATAS) {
                    val volumen = if (columnaVolumen >= 0) {
                        cursor.getString(columnaVolumen).orEmpty()
                    } else {
                        ""
                    }
                    val unidad = if (Build.VERSION.SDK_INT >= 29 && volumen.isNotBlank()) {
                        MediaStore.Audio.Media.getContentUri(volumen)
                    } else {
                        coleccion
                    }
                    val uri = ContentUris.withAppendedId(unidad, cursor.getLong(columnaId))

                    if (posicion == candidatas.size.toLong()) {
                        candidatas.add(uri)
                    } else {
                        candidatas[posicion.toInt()] = uri
                    }
                }
            }
        }

        cancelacion.throwIfCanceled()
        return candidatas
    }

    companion object {
        private const val MAXIMO_CANDIDATAS = 4
    }
}
