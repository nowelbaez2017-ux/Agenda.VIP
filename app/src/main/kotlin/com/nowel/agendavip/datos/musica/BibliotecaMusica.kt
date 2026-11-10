package com.nowel.agendavip.datos.musica

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size

/**
 * Busca canciones y sus imágenes mediante [contenido], el acceso que Android da a los archivos.
 * MediaStore es el catálogo de fotos, vídeos y audios que Android mantiene en el teléfono.
 * La pantalla que permite elegir música usa este catálogo o un archivo elegido por la persona.
 *
 * Las lecturas se hacen en trabajos separados del que dibuja los botones. Cada lectura puede
 * recibir una señal de cancelación para abandonar archivos que la pantalla ya no necesita.
 */
class BibliotecaMusica(private val contenido: ContentResolver) {

    /**
     * Recibe [cancelacion], una señal con la que el selector puede interrumpir una búsqueda.
     * Devuelve los audios permitidos sin ordenarlos: el selector prepara su orden una sola vez.
     * La pantalla de música la llama al abrir o actualizar la biblioteca.
     * Conserva el nombre real del archivo y la unidad de almacenamiento de cada audio.
     */
    @Suppress("DEPRECATION")
    fun leerCanciones(cancelacion: CancellationSignal): List<CancionLocal> {
        cancelacion.throwIfCanceled()

        val columnaCarpeta = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            MediaStore.Audio.Media.DATA
        }

        val coleccion = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val columnas = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            columnaCarpeta,
        )

        if (Build.VERSION.SDK_INT >= 29) {
            columnas += MediaStore.MediaColumns.VOLUME_NAME
        }

        val condiciones = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 29) {
            condiciones += "${MediaStore.MediaColumns.IS_PENDING} = 0"
        }

        if (Build.VERSION.SDK_INT >= 30) {
            condiciones += "${MediaStore.MediaColumns.IS_TRASHED} = 0"
        }

        val canciones = mutableListOf<CancionLocal>()
        contenido.query(
            coleccion,
            columnas.toTypedArray(),
            condiciones.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            null,
            null,
            cancelacion,
        )?.use { cursor ->
            // El cursor permite recorrer una por una las filas que devuelve el catálogo.
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titulo = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val archivo = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artista = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val duracion = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val carpeta = cursor.getColumnIndexOrThrow(columnaCarpeta)
            val unidad = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                cancelacion.throwIfCanceled()

                val nombreArchivo = cursor.getString(archivo).orEmpty()
                val nombre = cursor.getString(titulo).orEmpty().ifBlank {
                    nombreArchivo
                }
                val volumen = if (unidad >= 0) {
                    cursor.getString(unidad).orEmpty()
                } else {
                    ""
                }

                // Cada unidad conserva su propia dirección, incluso si dos carpetas se llaman igual.
                val coleccionAudio = if (Build.VERSION.SDK_INT >= 29 && volumen.isNotBlank()) {
                    MediaStore.Audio.Media.getContentUri(volumen)
                } else {
                    coleccion
                }

                canciones += CancionLocal(
                    uri = ContentUris.withAppendedId(coleccionAudio, cursor.getLong(id)),
                    nombre = nombre,
                    artista = cursor.getString(artista).orEmpty()
                        .takeUnless { it == "<unknown>" }.orEmpty(),
                    duracionMilisegundos = cursor.getLong(duracion).coerceAtLeast(0),
                    carpeta = normalizarCarpeta(cursor.getString(carpeta).orEmpty(), volumen),
                    nombreArchivo = nombreArchivo,
                )
            }
        }

        cancelacion.throwIfCanceled()
        return canciones
    }

    /**
     * Recibe [uri], la dirección que Android dio al audio, y una [cancelacion] opcional.
     * Lee nombre, artista y duración y devuelve una canción que el selector puede mostrar.
     * La importación y la recuperación del audio anterior la llaman en un trabajo separado.
     * Un archivo inaccesible produce un error; cancelar impide continuar leyendo sus datos.
     */
    fun leerDocumento(
        uri: Uri,
        cancelacion: CancellationSignal? = null,
    ): CancionLocal {
        cancelacion?.throwIfCanceled()

        var nombreArchivo = ""
        contenido.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
            cancelacion,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                nombreArchivo = cursor.getString(0).orEmpty()
            }
        }

        cancelacion?.throwIfCanceled()

        var nombre = nombreArchivo
        var artista = ""
        var duracion = 0L
        // Este lector consulta los datos incluidos dentro del audio sin reproducirlo.
        val lector = MediaMetadataRetriever()

        try {
            contenido.openAssetFileDescriptor(uri, "r", cancelacion)?.use { descriptor ->
                cancelacion?.throwIfCanceled()

                if (descriptor.declaredLength >= 0) {
                    lector.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                } else {
                    lector.setDataSource(descriptor.fileDescriptor)
                }

                cancelacion?.throwIfCanceled()

                nombre = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: nombreArchivo
                artista = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                duracion = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                cancelacion?.throwIfCanceled()
            } ?: throw IllegalStateException("El documento no está disponible")
        } finally {
            lector.release()
        }

        return CancionLocal(
            uri = uri,
            nombre = nombre,
            artista = artista,
            duracionMilisegundos = duracion.coerceAtLeast(0),
            carpeta = "",
            nombreArchivo = nombreArchivo,
        )
    }

    /**
     * Recibe la dirección [uri] y una [cancelacion] opcional; devuelve una imagen pequeña o null.
     * CargadorCaratulas la llama para las filas visibles. Primero pide la miniatura a Android
     * y, si no existe, busca una imagen incluida en el audio con un máximo de 128 píxeles por lado.
     * Una cancelación se propaga al cargador y nunca inicia el segundo intento de lectura.
     */
    fun leerCaratula(
        uri: Uri,
        cancelacion: CancellationSignal? = null,
    ): Bitmap? {
        cancelacion?.throwIfCanceled()

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val imagen = contenido.loadThumbnail(uri, Size(128, 128), cancelacion)
                cancelacion?.throwIfCanceled()
                return imagen
            } catch (_: Exception) {
                cancelacion?.throwIfCanceled()
                // Algunos orígenes no ofrecen miniatura; solo entonces se busca dentro del audio.
            }
        }

        cancelacion?.throwIfCanceled()
        val lector = MediaMetadataRetriever()

        return try {
            contenido.openAssetFileDescriptor(uri, "r", cancelacion)?.use { descriptor ->
                cancelacion?.throwIfCanceled()

                if (descriptor.declaredLength >= 0) {
                    lector.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                } else {
                    lector.setDataSource(descriptor.fileDescriptor)
                }

                cancelacion?.throwIfCanceled()
                val imagen = lector.embeddedPicture ?: return null
                cancelacion?.throwIfCanceled()

                val opciones = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(imagen, 0, imagen.size, opciones)

                var muestra = 1
                while (opciones.outWidth / muestra > 128 || opciones.outHeight / muestra > 128) {
                    muestra *= 2
                }

                cancelacion?.throwIfCanceled()
                opciones.inSampleSize = muestra
                opciones.inJustDecodeBounds = false

                val caratula = BitmapFactory.decodeByteArray(imagen, 0, imagen.size, opciones)
                cancelacion?.throwIfCanceled()
                caratula
            }
        } catch (_: Exception) {
            cancelacion?.throwIfCanceled()
            null
        } finally {
            lector.release()
        }
    }

    /**
     * Recibe la carpeta [valor] y el [volumen] de almacenamiento; devuelve su ruta para el selector.
     * leerCanciones la llama para distinguir la tarjeta SD de carpetas iguales de la memoria principal.
     * Las versiones anteriores de Android conservan el recorte de direcciones que ya usaba la lista.
     */
    private fun normalizarCarpeta(valor: String, volumen: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val ruta = valor.trim('/')

            if (volumen.isBlank() || volumen == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                return ruta
            }

            return listOf("Tarjeta SD ($volumen)", ruta)
                .filter { it.isNotBlank() }
                .joinToString("/")
        }

        val directorio = valor.substringBeforeLast('/', "")
        return when {
            directorio.startsWith("/storage/emulated/0/") -> directorio.removePrefix("/storage/emulated/0/")
            directorio == "/storage/emulated/0" -> ""
            directorio.startsWith("/sdcard/") -> directorio.removePrefix("/sdcard/")
            directorio.startsWith("/storage/") -> directorio.removePrefix("/storage/").substringAfter('/', "")
            else -> directorio.substringAfterLast('/', "")
        }.trim('/')
    }
}