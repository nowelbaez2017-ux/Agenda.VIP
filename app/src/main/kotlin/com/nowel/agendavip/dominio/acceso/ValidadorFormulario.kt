package com.nowel.agendavip.dominio.acceso

/**
 * Revisa lo escrito antes de pedir al repositorio que cree o compruebe una cuenta.
 * Recibe las contraseñas como CharArray, una lista modificable de caracteres que
 * la pantalla puede vaciar después. No guarda datos ni demuestra que exista una cuenta.
 */
object ValidadorFormulario {

    /**
     * El botón Entrar utiliza esta función para revisar usuario y contraseña.
     * Recibe los valores originales y devuelve los errores en orden de los campos.
     * Una lista vacía permite consultar la cuenta; la autenticación real la hace
     * RepositorioCuentas. Los espacios de la contraseña se conservan tal como se escriben.
     */
    fun validarInicioSesion(
        usuario: String,
        contrasena: CharArray,
    ): List<ErrorFormulario> {
        val errores = mutableListOf<ErrorFormulario>()

        if (usuario.isBlank()) {
            errores.add(ErrorFormulario.USUARIO_VACIO)
        } else if (!usuarioPermitido(usuario)) {
            errores.add(ErrorFormulario.USUARIO_INVALIDO)
        }

        // Un punto de código representa un carácter Unicode, incluso si necesita
        // dos posiciones de CharArray. Así se cuentan correctamente los símbolos.
        val longitud = Character.codePointCount(contrasena, 0, contrasena.size)

        when {
            longitud == 0 -> errores.add(ErrorFormulario.CONTRASENA_VACIA)
            longitud < 15 -> errores.add(ErrorFormulario.CONTRASENA_CORTA)
            longitud > 128 -> errores.add(ErrorFormulario.CONTRASENA_LARGA)
        }

        return errores.toList()
    }

    /**
     * El botón Crear cuenta entrega nombre, usuario y las dos contraseñas.
     * Devuelve los problemas del registro, sin transformar ni conservar las claves.
     * Reutiliza la revisión de acceso y añade el nombre obligatorio y la confirmación.
     * contentEquals compara el contenido de las listas, no sus direcciones en memoria.
     */
    fun validarRegistro(
        nombre: String,
        usuario: String,
        contrasena: CharArray,
        confirmacion: CharArray,
    ): List<ErrorFormulario> {
        val errores = mutableListOf<ErrorFormulario>()

        if (nombre.isBlank()) {
            errores.add(ErrorFormulario.NOMBRE_VACIO)
        } else {
            val nombreSinBordes = nombre.trim()
            val longitudNombre = nombreSinBordes.codePointCount(0, nombreSinBordes.length)

            if (longitudNombre > 120 || nombre.length > 480) {
                errores.add(ErrorFormulario.NOMBRE_LARGO)
            }
        }

        errores.addAll(validarInicioSesion(usuario, contrasena))

        if (confirmacion.isEmpty()) {
            errores.add(ErrorFormulario.CONFIRMACION_VACIA)
        } else if (contrasena.isNotEmpty() && !confirmacion.contentEquals(contrasena)) {
            errores.add(ErrorFormulario.CONFIRMACION_DIFERENTE)
        }

        return errores.toList()
    }

    /**
     * validarInicioSesion usa esta regla para aceptar de 3 a 40 caracteres:
     * letras, números, punto, guion bajo o guion. Recibe el usuario sin recortarlo
     * y devuelve true si cumple todo; los espacios nunca forman parte del usuario.
     */
    private fun usuarioPermitido(usuario: String): Boolean {
        val longitud = usuario.codePointCount(0, usuario.length)

        if (longitud !in 3..40) {
            return false
        }

        var posicion = 0

        while (posicion < usuario.length) {
            val caracter = usuario.codePointAt(posicion)
            val esSeparador = caracter == '.'.code ||
                caracter == '_'.code ||
                caracter == '-'.code

            if (!Character.isLetterOrDigit(caracter) && !esSeparador) {
                return false
            }

            posicion += Character.charCount(caracter)
        }

        return true
    }
}
