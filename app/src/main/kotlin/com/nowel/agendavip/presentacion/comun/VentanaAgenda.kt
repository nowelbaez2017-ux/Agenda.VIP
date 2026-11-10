package com.nowel.agendavip.presentacion.comun

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.nowel.agendavip.R

/**
 * Ajusta la ventana de las pantallas de la agenda para respetar las barras del teléfono,
 * la cámara o los recortes de pantalla y el espacio que ocupa el teclado.
 */
object VentanaAgenda {

    /**
     * Prepara el fondo y los espacios de seguridad alrededor del contenido de una pantalla.
     * Recibe la Activity, que representa la pantalla abierta de Android, y su vista raíz:
     * el elemento de pantalla que contiene a todos los demás.
     *
     * No devuelve un valor; modifica la ventana y los espacios interiores de ese contenedor.
     * La pantalla de la agenda la llama después de setContentView, la instrucción que coloca
     * su contenido en pantalla. El encabezado y el pie se mantienen fuera del ScrollView,
     * el contenedor que permite desplazar el contenido, para conservar visibles sus botones.
     */
    @Suppress("DEPRECATION")
    fun preparar(actividad: Activity, raiz: View) {
        val ventana = actividad.window
        val ciruela = actividad.getColor(R.color.ciruela)
        val marfil = actividad.getColor(R.color.marfil)

        // La parte superior deja ver el fondo. La barra inferior usa el color marfil.
        // Al aparecer el teclado, Android ajusta el espacio disponible para el contenido.
        ventana.statusBarColor = android.graphics.Color.TRANSPARENT
        ventana.navigationBarColor = marfil
        ventana.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        raiz.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ciruela, ciruela, marfil, marfil),
        )

        // Android ofrece instrucciones distintas según su versión. En ambos casos
        // se permite dibujar el fondo detrás de las barras y se ajusta el color de sus iconos.
        if (Build.VERSION.SDK_INT >= 30) {
            ventana.setDecorFitsSystemWindows(false)
            ventana.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            ventana.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        // Se guardan los espacios originales una sola vez. Así, cada aviso de Android
        // calcula el espacio desde la misma base y no va sumándolo al ajuste anterior.
        val izquierda = raiz.paddingLeft
        val arriba = raiz.paddingTop
        val derecha = raiz.paddingRight
        val abajo = raiz.paddingBottom

        // Android llama a este bloque cuando cambia el espacio ocupado por sus barras,
        // un recorte del teléfono o el teclado. Una vista es un elemento de pantalla;
        // aquí vista es el contenedor al que se le añaden esos espacios interiores.
        raiz.setOnApplyWindowInsetsListener { vista, margenes ->
            if (Build.VERSION.SDK_INT >= 30) {
                val seguros = margenes.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime(),
                )

                vista.setPadding(
                    izquierda + seguros.left,
                    arriba + seguros.top,
                    derecha + seguros.right,
                    abajo + seguros.bottom,
                )
            } else {
                vista.setPadding(
                    izquierda + margenes.systemWindowInsetLeft,
                    arriba + margenes.systemWindowInsetTop,
                    derecha + margenes.systemWindowInsetRight,
                    abajo + margenes.systemWindowInsetBottom,
                )
            }

            // Se devuelve a Android el mismo aviso de márgenes que recibió este bloque.
            margenes
        }

        // Pide a Android que aplique también los espacios al preparar la pantalla por primera vez.
        raiz.requestApplyInsets()
    }
}
