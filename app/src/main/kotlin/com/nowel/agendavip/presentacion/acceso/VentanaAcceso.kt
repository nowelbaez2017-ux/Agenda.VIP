package com.nowel.agendavip.presentacion.acceso

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ScrollView
import com.nowel.agendavip.R

/**
 * Ajusta el espacio que ocupa el formulario dentro del teléfono.
 *
 * Android muestra una barra con la hora arriba, otra para navegar abajo y un
 * teclado al escribir. Este archivo evita que esas partes tapen los campos.
 * ActividadAcceso lo usa tanto en Iniciar sesión como en Crear cuenta.
 */
object VentanaAcceso {

    /**
     * Configura los colores de las barras de Android y el espacio del teclado.
     * Recibe la pantalla abierta: Activity es el nombre de Android para una pantalla.
     * No devuelve datos; modifica únicamente cómo se presenta esa pantalla.
     *
     * ActividadAcceso llama esta función DESPUÉS de setContentView, que es la
     * instrucción que crea el formulario desde el XML. Ese orden es necesario:
     * antes de crear la pantalla Android todavía puede no tener sus barras listas.
     */
    @Suppress("DEPRECATION")
    fun preparar(actividad: Activity) {
        val ventana = actividad.window

        // decorView es la superficie que Android crea para mostrar toda la pantalla.
        // Pedirla aquí también asegura que exista antes de solicitar sus barras.
        val superficiePantalla = ventana.decorView

        // La barra superior deja ver el fondo de la app; la inferior usa marfil.
        ventana.statusBarColor = Color.TRANSPARENT
        ventana.navigationBarColor = actividad.getColor(R.color.marfil)

        // Al abrir el teclado, el espacio visible se reduce para poder seguir escribiendo.
        ventana.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Build.VERSION indica qué versión de Android tiene este teléfono.
        // Android 11 o posterior ofrece esta forma de elegir el color de los iconos.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ventana.setDecorFitsSystemWindows(false)

            // Iconos oscuros abajo, donde el fondo es claro, y claros arriba, en ciruela.
            ventana.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            // Los Android anteriores consiguen la misma distribución con estas opciones.
            superficiePantalla.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    /**
     * Deja espacio alrededor del formulario para las barras y el teclado.
     * Recibe el ScrollView: el contenedor que permite subir y bajar el formulario.
     * No devuelve datos; cambia el espacio interior de ese contenedor.
     *
     * Android informa cuánto ocupan sus barras. Usamos esas medidas en vez de una
     * altura fija porque cambian según el teléfono y si el teclado está abierto.
     */
    fun aplicarMargenes(desplazamiento: ScrollView) {
        // Guardamos el espacio del diseño para sumarle el que necesita Android.
        val izquierdaInicial = desplazamiento.paddingLeft
        val superiorInicial = desplazamiento.paddingTop
        val derechaInicial = desplazamiento.paddingRight
        val inferiorInicial = desplazamiento.paddingBottom

        // Este bloque se repite cuando Android cambia el tamaño de las barras o del teclado.
        desplazamiento.setOnApplyWindowInsetsListener { vista, margenes ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // WindowInsets contiene las medidas de las zonas que podrían tapar la app.
                // systemBars son las barras de Android; ime es el teclado de escritura.
                val espacio = margenes.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime(),
                )

                vista.setPadding(
                    izquierdaInicial + espacio.left,
                    superiorInicial + espacio.top,
                    derechaInicial + espacio.right,
                    inferiorInicial + espacio.bottom,
                )
            } else {
                // Los teléfonos anteriores a Android 11 entregan las mismas medidas así.
                @Suppress("DEPRECATION")
                vista.setPadding(
                    izquierdaInicial + margenes.systemWindowInsetLeft,
                    superiorInicial + margenes.systemWindowInsetTop,
                    derechaInicial + margenes.systemWindowInsetRight,
                    inferiorInicial + margenes.systemWindowInsetBottom,
                )
            }

            // Devolvemos las medidas recibidas para que Android continúe aplicándolas.
            margenes
        }

        // Solicitamos las medidas actuales para ajustar el formulario recién creado.
        desplazamiento.requestApplyInsets()
    }
}