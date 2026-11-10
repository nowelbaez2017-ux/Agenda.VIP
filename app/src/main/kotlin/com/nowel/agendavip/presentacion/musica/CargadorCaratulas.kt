package com.nowel.agendavip.presentacion.musica

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.nowel.agendavip.datos.musica.BibliotecaMusica
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Lee carátulas para las filas visibles sin volver a dibujar la lista completa.
 * La pantalla lo crea con su [biblioteca] y llama sus funciones desde el hilo principal.
 * Conserva imágenes pequeñas y ausencias en memorias temporales con límites de tamaño.
 * Una referencia débil permite recordar una imagen de la fila sin impedir que Android la libere.
 */
class CargadorCaratulas(private val biblioteca: BibliotecaMusica) {

    /**
     * Reúne una lectura y todas las imágenes de filas que están esperando la misma dirección.
     * El cargador comprueba sus etiquetas antes de empezar y antes de entregar cada resultado.
     */
    private class Solicitud(val uri: Uri, val clave: String) {
        val cancelacion = CancellationSignal()
        val vistas = mutableListOf<WeakReference<ImageView>>()
    }

    // Solo hay dos lecturas simultáneas. Las otras solicitudes esperan en el hilo principal
    // para poder retirar las filas que ya salieron de pantalla antes de abrir sus archivos.
    private val ejecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(24),
    )
    private val hiloPrincipal = Handler(Looper.getMainLooper())
    private val solicitudes = linkedMapOf<String, Solicitud>()
    private val enCurso = mutableSetOf<Solicitud>()
    private var revisionProgramada = false

    @Volatile
    private var cerrado = false

    private val caratulas = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        /**
         * La memoria de imágenes llama esta función para medir una portada.
         * Recibe su dirección y su imagen; devuelve los bytes que cuenta para el límite de 4 MB.
         */
        override fun sizeOf(clave: String, imagen: Bitmap): Int {
            return imagen.byteCount
        }
    }

    // También se limita el recuerdo de archivos sin imagen para que no crezca con toda la biblioteca.
    private val sinCaratula = LruCache<String, Boolean>(512)

    private val revisarPendientes = Runnable {
        revisionProgramada = false

        if (!cerrado) {
            iniciarPendientes()
        }
    }

    /**
     * El adaptador la llama al preparar una fila con la dirección [uri] y su imagen [vista].
     * No devuelve un dato: coloca una portada guardada o reúne las filas que esperan su lectura.
     * La etiqueta debe contener esa dirección. La apertura del archivo se difiere hasta que
     * Android haya adjuntado la fila, ya que getView puede crearla antes de mostrarla.
     */
    fun cargar(uri: Uri, vista: ImageView) {
        if (cerrado) {
            return
        }

        val clave = uri.toString()

        if (vista.tag != clave) {
            return
        }

        programarRevision()

        caratulas.get(clave)?.let { imagen ->
            vista.setImageBitmap(imagen)
            return
        }

        if (sinCaratula.get(clave) == true) {
            return
        }

        val solicitud = solicitudes[clave] ?: crearSolicitud(uri, clave)
        val yaRegistrada = solicitud.vistas.any { it.get() === vista }

        if (!yaRegistrada) {
            solicitud.vistas += WeakReference(vista)
        }
    }

    /**
     * cargar la llama con la dirección [uri] y su [clave] cuando todavía no hay una lectura igual.
     * Devuelve la solicitud y limita a 24 las lecturas en espera; las dos en curso son independientes.
     * Si se alcanza el límite retira la espera más antigua para atender las filas recién dibujadas.
     */
    private fun crearSolicitud(uri: Uri, clave: String): Solicitud {
        val esperando = solicitudes.values.filter { it !in enCurso }

        if (esperando.size >= 24) {
            retirarSolicitud(esperando.first())
        }

        val solicitud = Solicitud(uri, clave)
        solicitudes[clave] = solicitud
        return solicitud
    }

    /**
     * cargar y la terminación de una lectura la llaman sin argumentos para revisar la cola.
     * No devuelve un dato: reúne las peticiones del mismo dibujo en una sola revisión posterior.
     * Así las filas terminan de adjuntarse antes de decidir cuáles siguen necesitando una imagen.
     */
    private fun programarRevision() {
        if (cerrado || revisionProgramada) {
            return
        }

        revisionProgramada = true
        hiloPrincipal.post(revisarPendientes)
    }

    /**
     * La revisión pendiente la llama sin argumentos después de que Android prepare las filas.
     * No devuelve un dato: cancela las filas reutilizadas o retiradas e inicia hasta dos lecturas.
     * Los trabajos cancelados ocupan su lugar hasta terminar para no acumular aperturas lentas.
     */
    private fun iniciarPendientes() {
        solicitudes.values.toList().forEach { solicitud ->
            if (!conservarVistas(solicitud)) {
                retirarSolicitud(solicitud)
            }
        }

        val disponibles = (2 - enCurso.size).coerceAtLeast(0)
        val siguientes = solicitudes.values
            .filter { it !in enCurso }
            .take(disponibles)

        siguientes.forEach { solicitud ->
            enCurso += solicitud

            ejecutor.execute {
                val imagen = try {
                    solicitud.cancelacion.throwIfCanceled()
                    biblioteca.leerCaratula(solicitud.uri, solicitud.cancelacion)
                } catch (_: Exception) {
                    null
                }

                if (!cerrado) {
                    hiloPrincipal.post {
                        terminarSolicitud(solicitud, imagen)
                    }
                }
            }
        }
    }

    /**
     * iniciarPendientes la llama con una [solicitud] para comprobar las imágenes que aún la esperan.
     * Devuelve true si queda alguna fila adjunta cuya etiqueta conserve esa dirección.
     * Retira referencias liberadas o reutilizadas para no cargar portadas de canciones que ya no se ven.
     */
    private fun conservarVistas(solicitud: Solicitud): Boolean {
        solicitud.vistas.removeAll { referencia ->
            val vista = referencia.get()
            vista == null || vista.tag != solicitud.clave || !vista.isAttachedToWindow
        }

        return solicitud.vistas.isNotEmpty()
    }

    /**
     * La revisión y el límite de espera la llaman con la [solicitud] que ya no es relevante.
     * No devuelve un dato: cancela su acceso al archivo y olvida las filas que esperaba atender.
     * La lectura en curso libera su lugar al terminar, incluso si el origen tarda en cancelarla.
     */
    private fun retirarSolicitud(solicitud: Solicitud) {
        solicitud.cancelacion.cancel()
        solicitud.vistas.clear()

        if (solicitudes[solicitud.clave] === solicitud) {
            solicitudes.remove(solicitud.clave)
        }
    }

    /**
     * Una lectura llama esta función en el hilo principal con su [solicitud] y su posible [imagen].
     * No devuelve un dato: guarda el resultado y actualiza todas las filas que aún muestran ese audio.
     * Nunca redibuja la lista completa; después deja pasar a las siguientes solicitudes.
     */
    private fun terminarSolicitud(solicitud: Solicitud, imagen: Bitmap?) {
        enCurso.remove(solicitud)

        if (cerrado) {
            return
        }

        if (solicitudes[solicitud.clave] === solicitud) {
            solicitudes.remove(solicitud.clave)

            if (!solicitud.cancelacion.isCanceled) {
                if (imagen == null) {
                    sinCaratula.put(solicitud.clave, true)
                } else {
                    caratulas.put(solicitud.clave, imagen)

                    solicitud.vistas.forEach { referencia ->
                        val vista = referencia.get()

                        if (vista != null && vista.tag == solicitud.clave && vista.isAttachedToWindow) {
                            vista.setImageBitmap(imagen)
                        }
                    }
                }
            }
        }

        solicitud.vistas.clear()
        programarRevision()
    }

    /**
     * La pantalla la llama al dejar de verse o cambiar los resultados que está mostrando.
     * No recibe argumentos ni devuelve un dato: retira esperas y cancela lecturas sin borrar la caché.
     * Al volver a dibujar, cargar puede reutilizar las imágenes que ya se habían conseguido.
     */
    fun cancelarPendientes() {
        hiloPrincipal.removeCallbacks(revisarPendientes)
        revisionProgramada = false

        solicitudes.values.toList().forEach { solicitud ->
            retirarSolicitud(solicitud)
        }

        enCurso.forEach { solicitud ->
            solicitud.cancelacion.cancel()
            solicitud.vistas.clear()
        }
    }

    /**
     * La pantalla la llama al destruirse. No recibe argumentos ni devuelve un dato.
     * Cancela lecturas, cierra los trabajadores y vacía las dos memorias temporales de imágenes.
     * Después del cierre ninguna lectura terminada puede modificar las filas de esa pantalla.
     */
    fun cerrar() {
        if (cerrado) {
            return
        }

        cerrado = true
        cancelarPendientes()
        ejecutor.shutdownNow()
        hiloPrincipal.removeCallbacksAndMessages(null)
        enCurso.clear()
        caratulas.evictAll()
        sinCaratula.evictAll()
    }
}