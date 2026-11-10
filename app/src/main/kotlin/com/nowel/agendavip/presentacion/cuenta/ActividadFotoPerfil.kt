package com.nowel.agendavip.presentacion.cuenta

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Muestra en una pantalla propia la fotografía completa del perfil, sin recorte circular.
 * ControladorPerfil la abre con el dato foto_uri, que contiene la dirección autorizada de la imagen.
 * La lectura usa una copia reducida en un trabajo separado para mantener libre la pantalla.
 */
class ActividadFotoPerfil : Activity() {

    // Recuerda la cuenta de esta pantalla y bloquea el regreso después de cerrar su sesión.
    private val proteccion = ProtectorSesion(this)

    // Un hilo es un trabajo separado: este se dedica a abrir y reducir la foto.
    private val trabajador = Executors.newSingleThreadExecutor()

    // Handler permite devolver al hilo principal los cambios de los elementos visibles.
    private val principal = Handler(Looper.getMainLooper())

    // Future representa una lectura pendiente y permite pedir que se detenga al cerrar el visor.
    private var lectura: Future<*>? = null
    private var imagenMostrada: Bitmap? = null

    // Volatile hace que el trabajo de lectura vea enseguida si la pantalla principal ya se cerró.
    @Volatile
    private var cerrada = false

    private lateinit var imagen: ImageView
    private lateinit var carga: ProgressBar
    private lateinit var mensaje: TextView

    /**
     * Android la llama al abrir el visor o al recrearlo, por ejemplo después de girar el teléfono.
     * Recibe el estado temporal de Android y no devuelve datos; el archivo se obtiene de foto_uri.
     * Prepara la flecha para volver, respeta las barras del teléfono y empieza la lectura de la foto.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!proteccion.permitir(estado)) {
            return
        }

        setContentView(R.layout.perfil_pantalla_foto)

        VentanaAgenda.preparar(
            this,
            findViewById(R.id.perfil_visor_raiz),
        )

        imagen = findViewById(R.id.perfil_visor_imagen)
        carga = findViewById(R.id.perfil_visor_carga)
        mensaje = findViewById(R.id.perfil_visor_estado)

        findViewById<View>(R.id.perfil_visor_volver).setOnClickListener {
            finish()
        }

        val referencia = intent.getStringExtra("foto_uri")

        if (referencia.isNullOrBlank()) {
            mostrarProblema()
        } else {
            cargarFoto(Uri.parse(referencia))
        }
    }

    /** Android la llama al regresar; impide continuar si la cuenta cambió mientras estaba oculta. */
    override fun onResume() {
        super.onResume()

        if (!proteccion.comprobar()) {
            return
        }
    }

    /** Android entrega este paquete al girar; recuerda a qué cuenta pertenece la pantalla. */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        super.onSaveInstanceState(estado)
    }

    /**
     * Recibe la URI, la dirección que Android utiliza para abrir la fotografía elegida.
     * onCreate la llama para iniciar la lectura sin bloquear los botones; no devuelve datos.
     * Al terminar coloca la copia en el ImageView, el elemento que muestra imágenes,
     * o presenta una explicación si el archivo ya no se puede leer.
     */
    private fun cargarFoto(uri: Uri) {
        val aplicacion = applicationContext

        lectura = trabajador.submit {
            val resultado = runCatching {
                leerFotoReducida(
                    aplicacion,
                    uri,
                )
            }.getOrNull()

            if (cerrada || Thread.currentThread().isInterrupted) {
                resultado?.recycle()
                return@submit
            }

            principal.post {
                if (cerrada || isFinishing || isDestroyed) {
                    resultado?.recycle()
                } else if (resultado == null) {
                    mostrarProblema()
                } else {
                    imagenMostrada = resultado
                    imagen.setImageBitmap(resultado)
                    carga.visibility = View.GONE
                    mensaje.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Recibe el contexto de la aplicación y la URI del archivo que hay que abrir.
     * Devuelve un Bitmap, una imagen de píxeles que Android puede dibujar, o null si no se puede leer.
     * cargarFoto la llama únicamente desde su trabajo separado.
     * Primero consulta las medidas y después lee una versión cuyo lado mayor no supera 1600 píxeles.
     */
    private fun leerFotoReducida(
        contexto: Context,
        uri: Uri,
    ): Bitmap? {
        val dimensiones = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contexto.contentResolver.openInputStream(uri)?.use { archivo ->
            BitmapFactory.decodeStream(
                archivo,
                null,
                dimensiones,
            )
        }

        if (
            dimensiones.outWidth <= 0 ||
            dimensiones.outHeight <= 0 ||
            Thread.currentThread().isInterrupted
        ) {
            return null
        }

        var reduccion = 1

        val ladoMayor = maxOf(
            dimensiones.outWidth,
            dimensiones.outHeight,
        )

        while (ladoMayor.toLong() > 1600L * reduccion) {
            reduccion *= 2
        }

        val opciones = BitmapFactory.Options().apply {
            inSampleSize = reduccion
        }

        val foto = contexto.contentResolver.openInputStream(uri)?.use { archivo ->
            BitmapFactory.decodeStream(
                archivo,
                null,
                opciones,
            )
        } ?: return null

        if (Thread.currentThread().isInterrupted) {
            foto.recycle()
            return null
        }

        return orientarFoto(
            contexto,
            uri,
            foto,
        )
    }

    /**
     * Recibe el contexto, la URI y la copia reducida para respetar la orientación del archivo.
     * EXIF es la información que algunas cámaras guardan junto a la imagen, como su giro.
     * Devuelve la misma copia si está bien orientada, o una copia girada y libera la anterior.
     * leerFotoReducida la llama desde el trabajo separado; nunca modifica el archivo original.
     */
    private fun orientarFoto(
        contexto: Context,
        uri: Uri,
        foto: Bitmap,
    ): Bitmap {
        val orientacion = runCatching {
            contexto.contentResolver.openInputStream(uri)?.use { archivo ->
                ExifInterface(archivo).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        // Matrix describe el giro o el reflejo que debe aplicarse a la copia de la fotografía.
        val transformacion = Matrix()

        when (orientacion) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                transformacion.setScale(
                    -1f,
                    1f,
                )
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                transformacion.setRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                transformacion.setRotate(180f)
                transformacion.postScale(
                    -1f,
                    1f,
                )
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transformacion.setRotate(90f)
                transformacion.postScale(
                    -1f,
                    1f,
                )
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                transformacion.setRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transformacion.setRotate(-90f)
                transformacion.postScale(
                    -1f,
                    1f,
                )
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                transformacion.setRotate(270f)
            }

            else -> return foto
        }

        val orientada = Bitmap.createBitmap(
            foto,
            0,
            0,
            foto.width,
            foto.height,
            transformacion,
            true,
        )

        if (orientada !== foto) {
            foto.recycle()
        }

        return orientada
    }

    /**
     * No recibe argumentos ni devuelve datos: oculta la espera y muestra cómo resolver el problema.
     * onCreate y cargarFoto la llaman cuando falta la dirección o la imagen no se puede abrir.
     * La flecha de regreso sigue disponible para volver al perfil y elegir otra fotografía.
     */
    private fun mostrarProblema() {
        carga.visibility = View.GONE
        mensaje.setText(R.string.perfil_visor_error)
        mensaje.visibility = View.VISIBLE
    }

    /**
     * Android la llama al cerrar o recrear este visor.
     * No recibe argumentos ni devuelve datos: cancela la lectura, retira respuestas pendientes
     * y libera la copia de imagen que pertenecía a esta pantalla para no conservar memoria de más.
     */
    override fun onDestroy() {
        cerrada = true
        lectura?.cancel(true)
        trabajador.shutdownNow()
        principal.removeCallbacksAndMessages(null)
        if (::imagen.isInitialized) {
            imagen.setImageDrawable(null)
        }
        imagenMostrada?.recycle()
        imagenMostrada = null
        super.onDestroy()
    }
}


