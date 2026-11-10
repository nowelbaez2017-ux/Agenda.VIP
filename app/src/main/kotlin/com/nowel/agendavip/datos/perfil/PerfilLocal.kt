package com.nowel.agendavip.datos.perfil

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal

/**
 * Conserva en este teléfono el perfil de la cuenta activa o el de la agenda sin cuenta.
 * Recibe el contexto, que permite acceder a los recursos privados de Android.
 * Perfil y Agenda lo utilizan. EspacioPersonal separa los datos de cada persona; no usa Internet.
 */
class PerfilLocal(contexto: Context) {

    // SharedPreferences es un pequeño archivo privado que Android mantiene entre aperturas.
    private val espacio = EspacioPersonal(contexto)

    private val preferencias
        get() = espacio.preferencias("perfil_local")

    /**
     * Entrega el nombre completo a la agenda y a la cabecera del perfil.
     * Si todavía no se guardaron los campos separados, devuelve el nombre antiguo sin modificarlo.
     */
    val nombre: String
        get() {
            if (!preferencias.contains("nombres")) {
                return preferencias.getString(
                    "nombre",
                    "",
                ).orEmpty()
            }

            return listOf(
                nombres,
                apellidos,
            ).filter { it.isNotBlank() }
                .joinToString(" ")
        }

    /**
     * Entrega al formulario el contenido de Nombre.
     * Como migración, es decir, adaptación de datos antiguos, conserva aquí el nombre anterior
     * completo. No intenta adivinar qué palabras son nombres y cuáles son apellidos.
     */
    val nombres: String
        get() = preferencias.getString(
            "nombres",
            null,
        ) ?: preferencias.getString(
            "nombre",
            "",
        ).orEmpty()

    /** Entrega al formulario el apellido guardado, o texto vacío si todavía no se ha escrito. */
    val apellidos: String
        get() = preferencias.getString(
            "apellidos",
            "",
        ).orEmpty()

    /** Entrega al formulario el correo local, o texto vacío cuando se dejó este dato opcional vacío. */
    val correo: String
        get() = preferencias.getString(
            "correo",
            "",
        ).orEmpty()

    /** Entrega al formulario el celular local, sin iniciar llamadas ni enviar mensajes. */
    val celular: String
        get() = preferencias.getString(
            "celular",
            "",
        ).orEmpty()

    /**
     * Entrega a las pantallas la URI de la foto, o null cuando no existe.
     * Una URI es la dirección con la que Android permite abrir un archivo elegido por la persona.
     */
    val fotoUri: String?
        get() = preferencias.getString(
            "foto_uri",
            null,
        )

    /**
     * Recibe Nombre, Apellido, Correo y Celular, ya revisados por el formulario de perfil.
     * Quita los espacios de los extremos y guarda todos los campos juntos para la próxima apertura.
     * ControladorPerfil la llama al pulsar Guardar cambios; no devuelve un dato.
     */
    fun guardarDatos(
        nombres: String,
        apellidos: String,
        correo: String,
        celular: String,
    ) {
        val nombresLimpios = nombres.trim()
        val apellidosLimpios = apellidos.trim()
        val nombreCompleto = listOf(
            nombresLimpios,
            apellidosLimpios,
        ).filter { it.isNotBlank() }
            .joinToString(" ")

        // También se conserva la clave antigua para que las pantallas anteriores
        // puedan seguir leyendo el mismo nombre completo durante la actualización.
        preferencias.edit()
            .putString(
                "nombres",
                nombresLimpios,
            )
            .putString(
                "apellidos",
                apellidosLimpios,
            )
            .putString(
                "correo",
                correo.trim(),
            )
            .putString(
                "celular",
                celular.trim(),
            )
            .putString(
                "nombre",
                nombreCompleto,
            )
            .apply()
    }

    /**
     * Recibe la dirección autorizada de una foto, o null para quitarla del perfil.
     * ControladorPerfil la llama después de elegir o confirmar que se quite una imagen.
     * No devuelve un dato; las demás pantallas consultan después fotoUri para mostrarla.
     */
    fun guardarFoto(uri: String?) {
        preferencias.edit()
            .putString(
                "foto_uri",
                uri,
            )
            .apply()
    }

    /**
     * No recibe argumentos: utiliza el nombre completo guardado para construir el saludo.
     * Devuelve «Hola» con su primera palabra, o solo «Hola» cuando todavía no hay nombre.
     * La pantalla principal de la agenda la llama para personalizar el encabezado.
     */
    fun saludo(): String {
        val primeraPalabra = nombre.trim().substringBefore(' ')

        return if (primeraPalabra.isBlank()) {
            "Hola"
        } else {
            "Hola, $primeraPalabra"
        }
    }
}
