package com.nowel.agendavip.presentacion.acceso

import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.ImageButton
import com.nowel.agendavip.R

/**
 * Conecta un campo de contraseña con el botón que permite mostrarla u ocultarla.
 * Recibe el campo de escritura, su botón con el dibujo del ojo y un indicador
 * que dice si el campo es de confirmación de contraseña.
 *
 * No devuelve un valor: prepara el campo oculto y conecta la respuesta al tocar el botón.
 * Las pantallas de acceso la usan en los tres campos de contraseña para que funcionen igual.
 *
 * El dibujo del ojo se elige en estado_ojo_contrasena, un archivo XML que describe
 * qué imagen mostrar según isSelected, el estado guardado en el botón.
 * La descripción del botón permite que Android lea su acción en voz alta si el usuario
 * utiliza una herramienta de accesibilidad para conocer los elementos de la pantalla.
 */
fun configurarVisibilidadContrasena(
    campo: EditText,
    boton: ImageButton,
    esConfirmacion: Boolean = false,
) {
    // Los campos de contraseña y confirmación tienen mensajes distintos, para que
    // la persona pueda reconocer cuál mostrará u ocultará cada botón.
    val descripcionMostrar = if (esConfirmacion) {
        R.string.mostrar_confirmacion
    } else {
        R.string.mostrar_contrasena
    }

    val descripcionOcultar = if (esConfirmacion) {
        R.string.ocultar_confirmacion
    } else {
        R.string.ocultar_contrasena
    }

    // Al abrir la pantalla se oculta el texto y el botón ofrece la acción de mostrarlo.
    campo.transformationMethod = PasswordTransformationMethod.getInstance()
    boton.isSelected = false
    boton.contentDescription = campo.context.getString(descripcionMostrar)

    // Android ejecuta este bloque cada vez que la persona toca el botón del ojo.
    boton.setOnClickListener {
        val mostrar = !boton.isSelected

        // Se recuerda la posición del cursor, o el principio y final del texto seleccionado.
        // coerceIn mantiene esas posiciones dentro de la longitud actual del campo.
        val inicioSeleccion = campo.selectionStart.coerceIn(0, campo.length())
        val finSeleccion = campo.selectionEnd.coerceIn(0, campo.length())

        campo.transformationMethod = if (mostrar) {
            null
        } else {
            PasswordTransformationMethod.getInstance()
        }

        // Se recupera la selección para que cambiar la visibilidad no mueva el cursor.
        campo.setSelection(inicioSeleccion, finSeleccion)
        boton.isSelected = mostrar

        // La descripción siempre anuncia lo que hará el próximo toque del botón.
        boton.contentDescription = campo.context.getString(
            if (mostrar) descripcionOcultar else descripcionMostrar,
        )
    }
}
