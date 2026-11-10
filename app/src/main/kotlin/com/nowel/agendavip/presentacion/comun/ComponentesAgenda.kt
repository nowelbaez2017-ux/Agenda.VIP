package com.nowel.agendavip.presentacion.comun

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nowel.agendavip.R

/**
 * Reúne las piezas de pantalla que la agenda necesita crear mientras está abierta.
 * Por ejemplo, permite crear una tarjeta por cada tarea que haya guardada.
 * Una vista es un elemento de pantalla, como un texto, un botón o una tarjeta.
 * Las partes fijas de las pantallas se describen en XML: archivos que indican su distribución.
 */
object ComponentesAgenda {

    /**
     * Convierte una medida en dp a la cantidad de píxeles que necesita este teléfono.
     * Los dp son medidas de diseño que Android adapta a la pantalla del dispositivo.
     *
     * Recibe el contexto, que da acceso a los datos y recursos de Android, y la medida deseada.
     * Devuelve un número entero de píxeles. Las funciones de este archivo lo usan para dar
     * espacio, bordes y tamaño a sus elementos sin depender de la densidad de cada pantalla.
     */
    fun dp(contexto: Context, valor: Int): Int =
        (valor * contexto.resources.displayMetrics.density).toInt()

    /**
     * Crea un elemento de texto con el aspecto que comparten las tarjetas y los menús.
     *
     * Recibe el contexto de Android, lo que debe decir, el tamaño, el color y si será título.
     * El tamaño se expresa en sp: una medida de letra que respeta el ajuste de tamaño
     * que el usuario haya elegido en el teléfono. Si es título, se usa letra serif en negrita.
     *
     * Devuelve el TextView, es decir, el elemento de pantalla que muestra ese texto.
     * Las pantallas de la agenda lo añaden a sus tarjetas, menús y mensajes de lista vacía.
     */
    fun texto(
        contexto: Context,
        contenido: String,
        tamano: Float = 16f,
        color: Int = R.color.ciruela,
        titulo: Boolean = false,
    ): TextView = TextView(contexto).apply {
        text = contenido
        textSize = tamano
        setTextColor(contexto.getColor(color))

        if (titulo) {
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
    }

    /**
     * Crea una tarjeta que agrupa otros elementos de pantalla, uno debajo del otro.
     * Así las tareas y el menú comparten el mismo borde, espacio interior y forma.
     *
     * Recibe el contexto de Android y si la tarjeta debe tener el fondo destacado.
     * Devuelve un LinearLayout: un contenedor al que la pantalla puede añadir textos y botones.
     * Lo usan las pantallas de la agenda al construir tarjetas con información variable.
     */
    fun tarjeta(
        contexto: Context,
        destacada: Boolean = false,
    ): LinearLayout = LinearLayout(contexto).apply {
        orientation = LinearLayout.VERTICAL

        val espacio = dp(contexto, 16)
        setPadding(espacio, espacio, espacio, espacio)

        // Este dibujo forma el fondo de la tarjeta, con esquinas redondas y un borde fino.
        background = GradientDrawable().apply {
            setColor(
                if (destacada) 0xFFF7DFD1.toInt() else contexto.getColor(R.color.fondo_campo),
            )
            cornerRadius = dp(contexto, 18).toFloat()
            setStroke(
                dp(contexto, 1),
                contexto.getColor(R.color.borde_campo),
            )
        }

        // -1 ocupa todo el ancho disponible; -2 ajusta el alto al contenido de la tarjeta.
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(contexto, 12)
        }
    }

    /**
     * Crea un botón para que la persona pueda realizar una acción desde una tarjeta o menú.
     *
     * Recibe el contexto de Android, el texto del botón, si llevará el color principal
     * y la acción que se ejecutará al tocarlo. Esa acción es una función que entrega
     * la pantalla que está creando el botón.
     *
     * Devuelve el Button listo para añadir a la pantalla. Las pantallas de la agenda
     * deciden qué hace cada botón; esta función se encarga de su aspecto y de conectar el toque.
     */
    fun boton(
        contexto: Context,
        etiqueta: String,
        principal: Boolean = false,
        accion: () -> Unit,
    ): Button = Button(contexto).apply {
        text = etiqueta
        isAllCaps = false

        // textSize usa sp para respetar el tamaño de letra elegido por el usuario.
        textSize = 15f
        minHeight = dp(contexto, 48)
        setTextColor(
            contexto.getColor(if (principal) R.color.marfil else R.color.ciruela),
        )

        background = GradientDrawable().apply {
            setColor(
                contexto.getColor(if (principal) R.color.ciruela else R.color.fondo_campo),
            )
            cornerRadius = dp(contexto, 12).toFloat()

            if (!principal) {
                setStroke(
                    dp(contexto, 1),
                    contexto.getColor(R.color.borde_campo),
                )
            }
        }

        // Se deja visible el fondo que acabamos de dibujar para este botón.
        backgroundTintList = null
        setPadding(
            dp(contexto, 14),
            dp(contexto, 10),
            dp(contexto, 14),
            dp(contexto, 10),
        )

        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(contexto, 8)
        }

        setOnClickListener {
            accion()
        }
    }

    /**
     * Crea un elemento vacío que sirve para separar dos partes de una pantalla.
     * Recibe el contexto de Android y el alto deseado en dp, la medida que Android
     * adapta a la pantalla del teléfono.
     *
     * Devuelve una vista vacía con ese alto. Las pantallas de la agenda la colocan
     * entre sus secciones. Se excluye de la lectura de accesibilidad porque no contiene información.
     */
    fun espacio(contexto: Context, alto: Int): View = View(contexto).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(contexto, alto))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
