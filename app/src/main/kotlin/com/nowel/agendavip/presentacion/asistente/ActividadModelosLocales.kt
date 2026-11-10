package com.nowel.agendavip.presentacion.asistente

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.asistente.ModelosLocales
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Prepara el modelo que Enfoque usa para convertir respuestas de voz en texto.
 * El botón Preparar voz sin conexión abre esta pantalla. Una selección cancelada
 * conserva el archivo anterior y la compatibilidad se comprueba al utilizar el modelo.
 */
class ActividadModelosLocales : Activity() {

    private val proteccion = ProtectorSesion(this)
    private val ejecutor = Executors.newSingleThreadExecutor()
    private var copia: Future<*>? = null
    private var turno = 0
    private var visible = false
    private var copiando = false
    private lateinit var contenido: LinearLayout
    private lateinit var modelos: ModelosLocales

    /** Android entrega estado al abrir o girar. Prepara una columna que se puede desplazar. */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!proteccion.permitir(estado)) {
            return
        }

        modelos = ModelosLocales(this)
        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadModelosLocales, 20)
            setPadding(margen, margen, margen, margen)
        }
        val desplazamiento = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.marfil))
            addView(contenido)
        }
        setContentView(desplazamiento)
        VentanaAgenda.preparar(this, desplazamiento)
    }

    /** Al regresar verifica la cuenta y vuelve a mostrar qué archivos están preparados. */
    override fun onResume() {
        super.onResume()

        if (!::contenido.isInitialized || !proteccion.comprobar()) {
            return
        }

        visible = true
        dibujar()
    }

    /**
     * La apertura y el final de una copia llaman aquí para mostrar el estado del modelo.
     * Conecta los botones de descarga y selección sin activar el micrófono.
     */
    private fun dibujar() {
        contenido.removeAllViews()
        contenido.addView(ComponentesAgenda.boton(this, "‹ Volver") { finish() })
        contenido.addView(ComponentesAgenda.texto(this, "Voz sin conexión para Enfoque", 27f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Whisper convierte tus respuestas de Enfoque en texto. Elige el archivo multilingüe ggml-base.bin, sin .en y sin comprimir.",
            15f,
        ))
        contenido.addView(ComponentesAgenda.espacio(this, 16))

        val estado = if (modelos.instalado()) {
            val megas = modelos.archivo.length() / (1024 * 1024)
            "Archivo copiado · $megas MB"
        } else {
            "Archivo pendiente"
        }
        contenido.addView(ComponentesAgenda.texto(this, estado, 14f, R.color.texto_secundario))

        contenido.addView(ComponentesAgenda.boton(this, "Abrir fuente del modelo") {
            abrirFuente()
        }.apply {
            isEnabled = !copiando
        })
        contenido.addView(ComponentesAgenda.boton(this, "Seleccionar archivo descargado") {
            seleccionar()
        }.apply {
            isEnabled = !copiando
        })

        if (copiando) {
            contenido.addView(ComponentesAgenda.texto(
                this,
                "Copiando el modelo. Conserva esta pantalla abierta.",
                16f,
            ))
        }
        contenido.addView(ComponentesAgenda.espacio(this, 16))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "La descarga inicial se abre en tu navegador. Después, Enfoque usa el archivo en el teléfono.",
            14f,
            R.color.texto_secundario,
        ))
    }

    /** El botón Abrir fuente del modelo llama aquí para abrir la descarga en el navegador. */
    private fun abrirFuente() {
        val direccion = "https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-base.bin"

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(direccion)))
        } catch (_: ActivityNotFoundException) {
            informar("No se encontró un navegador. Abre la fuente del modelo en otro navegador para descargarlo.")
        }
    }

    /** El botón Seleccionar archivo abre Archivos. Android devuelve la dirección elegida. */
    @Suppress("DEPRECATION")
    private fun seleccionar() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                ELEGIR_MODELO,
            )
        } catch (_: ActivityNotFoundException) {
            informar("Este teléfono no ofrece un selector de archivos.")
        }
    }

    /**
     * Android devuelve el archivo elegido; lo copia en segundo plano sin guardar permisos permanentes.
     * No carga el modelo ni ejecuta una prueba. Si la copia falla, informa y conserva el anterior.
     */
    @Deprecated("Contrato nativo usado por esta pantalla de Android.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)

        if (solicitud != ELEGIR_MODELO || resultado != RESULT_OK || !proteccion.comprobar()) {
            return
        }

        val uri = datos?.data ?: return
        val version = ++turno
        copiando = true
        dibujar()
        copia = ejecutor.submit {
            val mensaje = try {
                modelos.importar(uri)
                "Archivo copiado. Ya puedes volver a Enfoque."
            } catch (error: Exception) {
                error.message ?: "No se pudo copiar el archivo. Se conservó el modelo anterior."
            }
            runOnUiThread {
                if (version == turno && !isDestroyed && !isFinishing) {
                    copiando = false
                    if (visible) {
                        dibujar()
                        informar(mensaje)
                    }
                }
            }
        }
    }

    /** Conserva únicamente el dueño de la pantalla al girar; no copia los archivos al estado temporal. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /** Al salir detiene la copia pendiente. El importador conserva siempre el modelo anterior. */
    override fun onStop() {
        visible = false
        turno += 1
        copia?.cancel(true)
        copiando = false
        super.onStop()
    }

    /** Libera el trabajador de copia cuando se cierra la pantalla; no borra modelos instalados. */
    override fun onDestroy() {
        ejecutor.shutdownNow()
        super.onDestroy()
    }

    /** Recibe el resultado de la selección y lo muestra como un mensaje breve. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val ELEGIR_MODELO = 901
    }
}
