package com.nowel.agendavip.presentacion.comun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Prepara una versión pequeña y circular de la foto elegida para el perfil.
 * Las pantallas que muestran al usuario la usan para evitar abrir una fotografía grande
 * directamente en los elementos visibles de la pantalla.
 */
object FotoPerfil {

    // Un hilo es un trabajo separado: aquí lee la foto sin congelar los botones de la pantalla.
    private val trabajador = Executors.newSingleThreadExecutor()

    // Los cambios de los elementos visibles se devuelven al trabajo principal de Android.
    private val principal = Handler(Looper.getMainLooper())

    // LruCache conserva en memoria como máximo un avatar reducido, para compartirlo
    // entre Inicio, el menú y Perfil. Solo entran copias privadas con un nombre único.
    private val ultimaFoto = LruCache<Pair<String, String>, Bitmap>(1)
    private val nombreCopia = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.imagen",
    )

    /**
     * Muestra la foto del perfil en el elemento de imagen que recibe.
     * Recibe el contexto de Android, la dirección de la foto guardada, el ImageView
     * donde se verá y el color del icono que aparece mientras no hay una foto disponible.
     * Un ImageView es un elemento de pantalla dedicado a mostrar imágenes.
     *
     * La referencia es el texto de una URI: la dirección que Android utiliza para abrir
     * un archivo autorizado. Puede faltar; en ese caso queda visible el icono de usuario.
     * No devuelve un valor. Las pantallas que muestran el perfil la llaman para actualizar la imagen.
     */
    fun mostrar(
        contexto: Context,
        referencia: String?,
        vista: ImageView,
        colorMarcador: Int = R.color.ciruela,
    ) {
        // Cada solicitud usa su propia etiqueta. Una lectura anterior no puede repintar
        // esta vista cuando se vuelve a usar, aunque ambas peticiones tengan la misma dirección.
        val solicitud = Any()
        vista.tag = solicitud

        val aplicacion = contexto.applicationContext
        val propietario = EspacioPersonal.claveActual(aplicacion)
        val clave = if (!referencia.isNullOrBlank() && propietario != null) {
            propietario to referencia
        } else {
            null
        }
        val guardada = clave?.let { ultimaFoto.get(it) }

        if (guardada != null) {
            colocarFoto(vista, guardada)
            return
        }

        vista.setImageResource(R.drawable.icono_usuario)
        vista.imageTintList = android.content.res.ColorStateList.valueOf(
            contexto.getColor(colorMarcador),
        )

        if (referencia.isNullOrBlank()) {
            return
        }

        // La referencia débil evita mantener en memoria la pantalla si ya se cerró.
        val debil = WeakReference(vista)

        trabajador.execute {
            if (EspacioPersonal.claveActual(aplicacion) != propietario) {
                return@execute
            }

            // Si el menú pidió la foto durante la carga del avatar, esta segunda
            // consulta reutiliza el resultado en cuanto el primer trabajo termina.
            val imagen = clave?.let { ultimaFoto.get(it) } ?: runCatching {
                val uri = Uri.parse(referencia)
                val leida = leerCircular(aplicacion, uri)

                if (leida != null && clave != null && esCopiaPrivada(aplicacion, uri, clave.first)) {
                    ultimaFoto.put(clave, leida)
                }

                leida
            }.getOrNull()

            principal.post {
                val destino = debil.get()

                if (
                    destino != null &&
                    destino.tag === solicitud &&
                    imagen != null &&
                    EspacioPersonal.claveActual(aplicacion) == propietario
                ) {
                    colocarFoto(destino, imagen)
                }
            }
        }
    }

    /**
     * Recibe Android, la dirección leída y el dueño esperado. Devuelve si esta es una
     * copia interna con el nombre único que usa la galería de Perfil o Restaurar respaldo.
     * mostrar la llama en segundo plano antes de guardar el avatar en memoria. Las URI
     * externas se vuelven a leer porque su contenido puede cambiar sin cambiar la dirección.
     */
    private fun esCopiaPrivada(contexto: Context, uri: Uri, propietario: String): Boolean {
        if (
            uri.scheme != "file" ||
            !uri.authority.isNullOrEmpty() ||
            uri.query != null ||
            uri.fragment != null
        ) {
            return false
        }

        val ruta = uri.path ?: return false
        val archivo = File(ruta)
        val carpeta = File(contexto.filesDir, "fotos_perfil/$propietario")

        return archivo.parentFile == carpeta && nombreCopia.matches(archivo.name)
    }

    /**
     * Recibe el elemento que muestra el avatar y la foto reducida. Quita el tinte del icono
     * inicial para mostrar los colores reales. mostrar la llama desde el trabajo de pantalla
     * tanto si encontró la foto en memoria como si acabó de leerla.
     */
    private fun colocarFoto(vista: ImageView, foto: Bitmap) {
        vista.imageTintList = null
        vista.setImageBitmap(foto)
    }

    /**
     * Lee la fotografía y crea una copia circular para mostrarla en el perfil.
     * Recibe el contexto de Android y la URI, la dirección que permite abrir el archivo.
     * Devuelve un Bitmap, es decir, una imagen hecha de píxeles que Android puede dibujar,
     * o null si no obtiene las dimensiones o no puede leer la imagen.
     *
     * Solo la usa mostrar, desde el trabajo separado de lectura. Se reduce la foto antes
     * de dibujarla para ocupar menos memoria. El archivo original permanece igual.
     */
    private fun leerCircular(contexto: Context, uri: Uri): Bitmap? {
        // La primera lectura obtiene únicamente el ancho y el alto, sin cargar toda la foto.
        val dimensiones = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contexto.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, dimensiones)
        }

        if (dimensiones.outWidth <= 0 || dimensiones.outHeight <= 0) {
            return null
        }

        // Se aumenta la reducción hasta que el lado mayor calculado no supere 512 píxeles.
        var muestra = 1

        while (maxOf(dimensiones.outWidth, dimensiones.outHeight) / muestra > 512) {
            muestra *= 2
        }

        val opciones = BitmapFactory.Options().apply {
            inSampleSize = muestra
        }

        val foto = contexto.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opciones)
        } ?: return null

        // El lado más corto determina un cuadrado que se recorta del centro de la foto.
        val lado = minOf(foto.width, foto.height)
        val recorte = Bitmap.createBitmap(
            foto,
            (foto.width - lado) / 2,
            (foto.height - lado) / 2,
            lado,
            lado,
        )
        val resultado = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)

        // El pincel usa el recorte como relleno. Solo lo dibuja dentro del círculo;
        // las esquinas de la imagen nueva quedan transparentes.
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(recorte, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        Canvas(resultado).drawCircle(lado / 2f, lado / 2f, lado / 2f, pincel)

        return resultado
    }
}
