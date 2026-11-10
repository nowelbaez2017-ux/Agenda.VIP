package com.nowel.agendavip.dominio.acceso

/**
 * Identifica el campo que la persona debe corregir antes de enviar el formulario.
 * El validador devuelve estos valores y la pantalla de acceso elige el mensaje y el campo
 * donde mostrarlos. Por ejemplo, USUARIO_VACIO indica que falta escribir un usuario.
 * Así las reglas de revisión se pueden entender sin conocer cómo se dibuja la pantalla.
 */
enum class ErrorFormulario {
    NOMBRE_VACIO,
    NOMBRE_LARGO,
    USUARIO_VACIO,
    USUARIO_INVALIDO,
    CONTRASENA_VACIA,
    CONTRASENA_CORTA,
    CONTRASENA_LARGA,
    CONFIRMACION_VACIA,
    CONFIRMACION_DIFERENTE,
}
