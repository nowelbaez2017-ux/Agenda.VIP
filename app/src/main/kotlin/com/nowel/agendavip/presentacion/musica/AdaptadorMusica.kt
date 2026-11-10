package com.nowel.agendavip.presentacion.musica

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.musica.CancionLocal
import java.util.Locale

/**
 * Representa las tres cosas que puede mostrar la lista: silencio, una carpeta o una canción.
 * ActividadSelectorMusica construye estas filas y AdaptadorMusica decide cómo dibuja cada tipo.
 * Las carpetas guardan su ruta, nombre y cantidad; las canciones guardan los datos de su audio.
 */
sealed class FilaMusica {
    object SinMusica : FilaMusica()

    data class Carpeta(
        val ruta: String,
        val nombre: String,
        val cantidad: Int,
    ) : FilaMusica()

    data class Cancion(val audio: CancionLocal) : FilaMusica()
}

/**
 * Un adaptador coloca las canciones y carpetas en las filas que aparecen en pantalla.
 * ActividadSelectorMusica lo crea y le entrega los datos y las funciones que debe llamar
 * al elegir una fila, escuchar una canción o pedir su imagen. No confirma la elección por sí solo.
 * Recibe además el contexto, que permite obtener colores y textos de Android, y la preescucha,
 * cuyo estado indica si cada botón debe mostrar reproducir, pausar o esperar.
 */
class AdaptadorMusica(
    private val contexto: Context,
    private val alElegir: (FilaMusica) -> Unit,
    private val alEscuchar: (CancionLocal) -> Unit,
    private val cargarCaratula: (Uri, ImageView) -> Unit,
    private val preescucha: PreescuchaMusica,
) : BaseAdapter() {
    var filas: List<FilaMusica> = emptyList()
    var uriElegida: String? = null
    var tieneEleccion = false

    // Guarda los controles y la fila que representan. Al reutilizarlos, sus botones consultan
    // la fila nueva y su fondo cambia de color sin crear otro dibujo en cada actualización.
    private class Controles(
        val caratula: ImageView,
        val nombre: TextView,
        val detalle: TextView,
        val reproducir: ImageButton,
        val seleccion: RadioButton,
        val fondo: GradientDrawable,
    ) {
        var fila: FilaMusica? = null
        var seleccionada: Boolean? = null
    }

    /**
     * La lista de Android la llama para saber cuántas filas debe mostrar.
     * No recibe argumentos y devuelve la cantidad de filas que preparó la pantalla.
     */
    override fun getCount(): Int = filas.size

    /**
     * getView y la lista de Android la llaman para obtener los datos de una fila.
     * Recibe su posición y devuelve esa fila ya cargada, sin volver a leer los archivos.
     */
    override fun getItem(posicion: Int): FilaMusica = filas[posicion]

    /**
     * La lista de Android la llama cuando necesita identificar una fila de esta presentación.
     * Recibe su posición y devuelve ese mismo número convertido al tipo de número que pide Android.
     */
    override fun getItemId(posicion: Int): Long = posicion.toLong()

    /**
     * La lista de Android la llama al dibujar una fila o reutilizar una que salió de la pantalla.
     * Recibe la posición, la vista anterior si existe y la lista que contendrá la fila.
     * Devuelve la fila preparada; cambia los datos anteriores y conserva una portada del mismo audio.
     */
    override fun getView(
        posicion: Int,
        vistaAnterior: View?,
        contenedor: ViewGroup,
    ): View {
        val filaVista = (vistaAnterior as? LinearLayout) ?: crearFila()
        val controles = filaVista.tag as Controles
        val fila = getItem(posicion)
        controles.fila = fila

        actualizarSeleccion(controles)
        controles.seleccion.visibility = if (fila is FilaMusica.Carpeta) View.GONE else View.VISIBLE

        controles.reproducir.visibility = if (fila == FilaMusica.SinMusica) View.GONE else View.VISIBLE
        controles.reproducir.isEnabled = true
        controles.reproducir.isClickable = fila is FilaMusica.Cancion
        controles.reproducir.isFocusable = fila is FilaMusica.Cancion
        controles.reproducir.importantForAccessibility = if (fila is FilaMusica.Cancion) {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        // Cada tipo recibe su texto y sus acciones. Las descripciones también permiten
        // que Android explique los botones a una persona que utiliza un lector de pantalla.
        when (fila) {
            FilaMusica.SinMusica -> {
                controles.nombre.setText(R.string.musica_sin)
                controles.detalle.setText(R.string.musica_sin_detalle)
                controles.seleccion.contentDescription = contexto.getString(R.string.musica_sin)
                controles.caratula.tag = null
                controles.caratula.setImageResource(R.drawable.musica_icono_nota)
            }

            is FilaMusica.Carpeta -> {
                controles.nombre.text = fila.nombre
                controles.detalle.text = contexto.resources.getQuantityString(
                    R.plurals.musica_detalle_carpeta,
                    fila.cantidad,
                    fila.cantidad,
                )
                controles.caratula.tag = null
                controles.caratula.setImageResource(R.drawable.musica_icono_carpeta)
                controles.reproducir.setImageResource(R.drawable.musica_icono_chevron)
                filaVista.contentDescription = contexto.getString(
                    R.string.musica_abrir_carpeta,
                    fila.nombre,
                )
            }

            is FilaMusica.Cancion -> {
                val audio = fila.audio
                val nombre = audio.nombre.ifBlank {
                    contexto.getString(R.string.musica_nombre)
                }

                controles.nombre.text = nombre
                controles.detalle.text = "${audio.artista.ifBlank { contexto.getString(R.string.musica_artista) }} · ${duracion(audio.duracionMilisegundos)}"
                controles.seleccion.contentDescription = contexto.getString(
                    R.string.musica_seleccionar,
                    nombre,
                )

                // La etiqueta conserva la dirección que espera el cargador de imágenes.
                // Solo se borra la portada al pasar a otro audio, nunca al cambiar su botón.
                val clave = audio.uri.toString()

                if (controles.caratula.tag != clave) {
                    controles.caratula.tag = clave
                    controles.caratula.setImageResource(R.drawable.musica_icono_nota)
                    cargarCaratula(audio.uri, controles.caratula)
                }

                actualizarBotonPreescucha(controles, audio)
            }
        }

        if (fila !is FilaMusica.Carpeta) {
            filaVista.contentDescription = null
        }

        return filaVista
    }

    /**
     * ActividadSelectorMusica la llama después de cambiar uriElegida y tieneEleccion.
     * Recibe la lista y no devuelve un dato: cambia únicamente fondos y círculos visibles.
     * La etiqueta distingue las filas musicales de la cabecera y el pie de la lista.
     * Las filas que aparezcan después reciben la elección actual desde getView.
     */
    fun actualizarSeleccion(lista: ListView) {
        for (indice in 0 until lista.childCount) {
            val controles = lista.getChildAt(indice).tag as? Controles ?: continue
            actualizarSeleccion(controles)
        }
    }

    /**
     * ActividadSelectorMusica la llama cuando cambia el audio, la pausa o su preparación.
     * Recibe la lista y no devuelve un dato: actualiza solo los botones de canciones visibles.
     * No vuelve a preparar textos, portadas ni selección; getView atiende las filas que aparezcan luego.
     */
    fun actualizarEstadoPreescucha(lista: ListView) {
        for (indice in 0 until lista.childCount) {
            val controles = lista.getChildAt(indice).tag as? Controles ?: continue
            val fila = controles.fila as? FilaMusica.Cancion ?: continue
            actualizarBotonPreescucha(controles, fila.audio)
        }
    }

    /**
     * ActividadSelectorMusica la llama al terminar el desplazamiento o volver a la pantalla.
     * Recibe la lista y no devuelve un dato: solicita las portadas de sus canciones visibles.
     * Permite reintentar lecturas aplazadas sin borrar imágenes ni iconos que ya estén dibujados.
     * El cargador decide si reutiliza una imagen guardada o si todavía necesita leerla.
     */
    fun actualizarCaratulas(lista: ListView) {
        for (indice in 0 until lista.childCount) {
            val controles = lista.getChildAt(indice).tag as? Controles ?: continue
            val fila = controles.fila as? FilaMusica.Cancion ?: continue
            cargarCaratula(fila.audio.uri, controles.caratula)
        }
    }

    /**
     * getView y actualizarSeleccion de la lista la llaman para reflejar una elección.
     * Recibe los controles de una fila y no devuelve un dato: ajusta su círculo y el mismo fondo.
     * El círculo siempre refleja la elección real, incluso si Android lo marcó antes de avisar.
     * Si la selección no cambió, evita volver a asignar colores y borde al mismo fondo.
     */
    private fun actualizarSeleccion(controles: Controles) {
        val fila = controles.fila ?: return
        val seleccionada = when (fila) {
            FilaMusica.SinMusica -> tieneEleccion && uriElegida == null
            is FilaMusica.Cancion -> tieneEleccion && uriElegida == fila.audio.uri.toString()
            is FilaMusica.Carpeta -> false
        }

        // RadioButton puede haberse marcado solo al pulsarlo, aunque la pantalla rechace
        // esa elección mientras comprueba el archivo. Se corrige antes de consultar la caché.
        controles.seleccion.isChecked = seleccionada

        if (controles.seleccionada == seleccionada) {
            return
        }

        controles.seleccionada = seleccionada
        controles.fondo.setColor(
            contexto.getColor(if (seleccionada) R.color.melocoton else R.color.fondo_campo),
        )
        controles.fondo.setStroke(
            dp(1),
            contexto.getColor(if (seleccionada) R.color.ciruela else R.color.borde_campo),
        )
    }

    /**
     * getView y actualizarEstadoPreescucha la llaman para mostrar cómo está una muestra.
     * Recibe los controles y su canción; no devuelve un dato.
     * Cambia el icono, la disponibilidad y la descripción del botón sin tocar la elección.
     */
    private fun actualizarBotonPreescucha(
        controles: Controles,
        audio: CancionLocal,
    ) {
        val nombre = audio.nombre.ifBlank {
            contexto.getString(R.string.musica_nombre)
        }
        val esActual = preescucha.uriActual == audio.uri
        val suena = esActual && preescucha.reproduciendo

        controles.reproducir.setImageResource(
            if (suena) R.drawable.musica_icono_pausa else R.drawable.musica_icono_reproducir,
        )
        controles.reproducir.isEnabled = !(esActual && preescucha.preparando)
        controles.reproducir.contentDescription = when {
            esActual && preescucha.preparando -> contexto.getString(R.string.musica_preparando)
            suena -> contexto.getString(R.string.musica_pausar, nombre)
            esActual -> contexto.getString(R.string.musica_reanudar, nombre)
            else -> contexto.getString(R.string.musica_preescuchar, nombre)
        }
    }

    /**
     * getView la llama cuando no tiene una fila anterior para reutilizar.
     * No recibe argumentos y devuelve una fila con imagen, textos, reproducción y selección.
     * Conecta sus botones una sola vez; cada pulsación consulta la fila que muestran ahora.
     * Los botones miden 48 dp; dp mantiene su tamaño visual en distintos teléfonos.
     */
    private fun crearFila(): LinearLayout {
        val fondo = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
        }
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(82)
            setPadding(dp(8), dp(10), dp(2), dp(10))
            background = fondo
        }

        val caratula = ImageView(contexto).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(contexto.getColor(R.color.melocoton))
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fila.addView(caratula, LinearLayout.LayoutParams(dp(46), dp(46)))

        val textos = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(2), 0)
        }

        val nombre = TextView(contexto).apply {
            setTextColor(contexto.getColor(R.color.ciruela))
            textSize = 17f
            typeface = Typeface.create("serif", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val detalle = TextView(contexto).apply {
            setTextColor(contexto.getColor(R.color.texto_secundario))
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }

        textos.addView(nombre)
        textos.addView(detalle)
        fila.addView(
            textos,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val reproducir = ImageButton(contexto).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val atributo = android.util.TypedValue()
            contexto.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                atributo,
                true,
            )
            setBackgroundResource(atributo.resourceId)
        }

        fila.addView(reproducir, LinearLayout.LayoutParams(dp(48), dp(48)))

        val seleccion = RadioButton(contexto).apply {
            buttonTintList = ColorStateList.valueOf(contexto.getColor(R.color.ciruela))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }

        fila.addView(seleccion, LinearLayout.LayoutParams(dp(48), dp(48)))

        // Los botones leen la fila actual para no conservar una canción anterior al reciclarse.
        val controles = Controles(caratula, nombre, detalle, reproducir, seleccion, fondo)
        fila.tag = controles

        fila.setOnClickListener {
            val actual = controles.fila

            if (actual != null) {
                alElegir(actual)
            }
        }

        seleccion.setOnClickListener {
            val actual = controles.fila

            if (actual != null) {
                alElegir(actual)
            }

            // La pantalla puede rechazar un toque mientras valida. Después del aviso,
            // el círculo vuelve a la elección aceptada en vez de conservar su marca automática.
            actualizarSeleccion(controles)
        }

        reproducir.setOnClickListener {
            val actual = controles.fila as? FilaMusica.Cancion

            if (actual != null) {
                alEscuchar(actual.audio)
            }
        }

        return fila
    }

    /**
     * getView la llama para completar el detalle de una canción.
     * Recibe su duración en milisegundos y devuelve minutos y segundos o el texto de duración desconocida.
     */
    private fun duracion(milisegundos: Long): String {
        if (milisegundos <= 0) {
            return contexto.getString(R.string.musica_duracion_desconocida)
        }

        val segundos = milisegundos / 1000
        return String.format(Locale.getDefault(), "%d:%02d", segundos / 60, segundos % 60)
    }

    /**
     * crearFila y actualizarSeleccion la llaman para adaptar medidas al teléfono.
     * Recibe una medida de diseño en dp y devuelve su tamaño en píxeles de esta pantalla.
     */
    private fun dp(valor: Int): Int = (valor * contexto.resources.displayMetrics.density).toInt()
}