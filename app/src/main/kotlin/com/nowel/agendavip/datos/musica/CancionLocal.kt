package com.nowel.agendavip.datos.musica

import android.net.Uri

/**
 * Reúne el nombre, artista, duración y carpeta de una canción que Android permite abrir.
 * [uri] es la URI: la dirección que Android da al archivo para poder leerlo o reproducirlo.
 * [duracionMilisegundos] expresa el tiempo del audio; mil milisegundos equivalen a un segundo.
 * [nombreArchivo] conserva el nombre real para buscarlo aunque el título interno sea diferente.
 * La biblioteca crea estos datos y el selector de música los usa para mostrar y elegir canciones.
 */
data class CancionLocal(
    val uri: Uri,
    val nombre: String,
    val artista: String,
    val duracionMilisegundos: Long,
    val carpeta: String,
    val nombreArchivo: String = nombre,
)