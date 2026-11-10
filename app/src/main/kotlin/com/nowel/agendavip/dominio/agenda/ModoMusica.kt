package com.nowel.agendavip.dominio.agenda

/**
 * Guarda la decisión musical del recordatorio, separada de la dirección de una canción.
 * ALEATORIA pide buscar una canción del teléfono cuando llega el aviso.
 * SIN_MUSICA pide no reproducir canciones; conserva la voz y el tono normal de respaldo.
 * SELECCIONADA utiliza el archivo que la persona confirmó en la biblioteca.
 *
 * Tarea conserva esta opción y el formulario, el repositorio y el servicio de avisos la consultan.
 * Así no elegir todavía una canción deja de confundirse con haber elegido «Sin música».
 */
enum class ModoMusica {
    ALEATORIA,
    SIN_MUSICA,
    SELECCIONADA
}
