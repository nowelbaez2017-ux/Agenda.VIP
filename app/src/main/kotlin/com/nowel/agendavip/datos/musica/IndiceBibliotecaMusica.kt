package com.nowel.agendavip.datos.musica

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

/** Datos de una carpeta ya contada; el selector convierte esta información en una fila visible. */
data class CarpetaMusical(val ruta: String, val nombre: String, val cantidad: Int)

/** Una búsqueda devuelve canciones, carpetas y la ruta válida que finalmente se pudo consultar. */
data class ResultadoBiblioteca(
    val canciones: List<CancionLocal>,
    val carpetas: List<CarpetaMusical>,
    val carpeta: String
)

/**
 * Prepara una biblioteca una sola vez para no ordenar ni quitar acentos al escribir cada letra.
 * Recibe los audios del catálogo y los importados. La pantalla construye este índice fuera del
 * trabajo que dibuja los botones y lo reutiliza hasta que cambia el contenido de la biblioteca.
 * Un índice es una colección de datos ya preparados para encontrarlos con menos trabajo.
 */
class IndiceBibliotecaMusica(audios: List<CancionLocal>) {

    /** Conserva el audio, su dirección y los textos preparados una sola vez para ordenar y buscar. */
    private data class Entrada(
        val audio: CancionLocal,
        val nombre: String,
        val artista: String,
        val busqueda: String,
        val direccion: String,
    )

    /** Guarda una carpeta y su nombre sin acentos para no preparar el mismo texto al compararla. */
    private data class CarpetaPreparada(
        val carpeta: CarpetaMusical,
        val nombre: String,
    )

    /** Reúne los hijos y las canciones directas de una carpeta; cantidad incluye sus descendientes. */
    private class Nodo {
        val hijos = linkedMapOf<String, CarpetaMusical>()
        val canciones = mutableListOf<CancionLocal>()
        var hijasOrdenadas: List<CarpetaMusical> = emptyList()
        var cantidad = 0
    }

    private val porUri: Map<String, CancionLocal>
    private val entradas: List<Entrada>
    private val ordenadas: List<CancionLocal>
    private val carpetas = linkedMapOf<String, Nodo>()

    val cantidad: Int
        get() = ordenadas.size

    init {
        val unicas = linkedMapOf<String, CancionLocal>()
        audios.forEachIndexed { posicion, audio ->
            comprobarContinuacion(posicion)
            unicas.putIfAbsent(audio.uri.toString(), audio)
        }
        porUri = unicas
        entradas = unicas.values.mapIndexed { posicion, audio ->
            comprobarContinuacion(posicion)
            Entrada(
                audio,
                normalizar(audio.nombre),
                normalizar(audio.artista),
                normalizar("${audio.nombre} ${audio.artista} ${audio.nombreArchivo} ${audio.carpeta}"),
                audio.uri.toString(),
            )
        }.sortedWith(ORDEN_ENTRADAS)
        ordenadas = entradas.map { it.audio }
        carpetas[""] = Nodo()

        ordenadas.forEachIndexed { posicion, audio ->
            comprobarContinuacion(posicion)
            var ruta = ""
            var nodo = carpetas.getValue("")
            nodo.cantidad += 1

            audio.carpeta.split('/').filter { it.isNotBlank() }.forEach { parte ->
                val siguienteRuta = if (ruta.isEmpty()) parte else "$ruta/$parte"
                val siguiente = carpetas.getOrPut(siguienteRuta) { Nodo() }
                siguiente.cantidad += 1
                nodo.hijos[siguienteRuta] = CarpetaMusical(siguienteRuta, parte, siguiente.cantidad)
                ruta = siguienteRuta
                nodo = siguiente
            }
            nodo.canciones += audio
        }

        // Cada hija prepara su nombre una vez antes de ordenar. Un comparador decide cuál va primero
        // usando esos textos ya preparados; no vuelve a quitar acentos por cada pareja de carpetas.
        carpetas.values.forEachIndexed { posicion, nodo ->
            comprobarContinuacion(posicion)

            val hijas = nodo.hijos.values.mapIndexed { indice, hija ->
                comprobarContinuacion(indice)
                CarpetaPreparada(hija, normalizar(hija.nombre))
            }.sortedWith(ORDEN_CARPETAS)

            nodo.hijos.clear()

            // Al terminar los recuentos, cada carpeta recibe su cantidad definitiva.
            hijas.forEachIndexed { indice, preparada ->
                comprobarContinuacion(indice)
                val hija = preparada.carpeta
                nodo.hijos[hija.ruta] = hija.copy(cantidad = carpetas.getValue(hija.ruta).cantidad)
            }

            nodo.hijasOrdenadas = nodo.hijos.values.toList()
        }
    }

    /** Recibe una dirección y devuelve el audio ya leído, sin abrir otra vez sus metadatos. */
    fun obtener(uri: String): CancionLocal? {
        return porUri[uri]
    }

    /** El selector la usa para conservar una ruta si todavía existe después de actualizar la música. */
    fun contieneCarpeta(carpeta: String): Boolean {
        return carpetas.containsKey(carpeta)
    }

    /**
     * Recibe lo escrito, si se ven carpetas y la ruta actual; devuelve solo las filas necesarias.
     * No abre archivos ni vuelve a ordenar. Cada palabra puede coincidir con título, artista,
     * nombre del archivo o ruta, sin distinguir mayúsculas o acentos ni exigir el mismo orden.
     * Si una carpeta desapareció, retrocede al padre disponible para no dejar una vista sin salida.
     */
    fun buscar(consulta: String, enCarpetas: Boolean, carpeta: String = ""): ResultadoBiblioteca {
        comprobarContinuacion(0)
        var ruta = carpeta.trim('/')
        while (ruta.isNotEmpty() && !carpetas.containsKey(ruta)) {
            ruta = ruta.substringBeforeLast('/', "")
        }
        val palabras = normalizar(consulta).split(ESPACIOS).filter { it.isNotEmpty() }.distinct()
        if (palabras.isEmpty()) {
            if (!enCarpetas) {
                return ResultadoBiblioteca(ordenadas, emptyList(), ruta)
            }
            val nodo = carpetas.getValue(ruta)
            return ResultadoBiblioteca(nodo.canciones, nodo.hijasOrdenadas, ruta)
        }

        val coincidencias = mutableListOf<CancionLocal>()
        val conteoHijas = linkedMapOf<String, Int>()
        val prefijo = if (ruta.isEmpty()) "" else "$ruta/"
        entradas.forEachIndexed { posicion, entrada ->
            comprobarContinuacion(posicion)
            val audio = entrada.audio
            val pertenece = !enCarpetas || audio.carpeta == ruta || audio.carpeta.startsWith(prefijo)
            if (pertenece && palabras.all { entrada.busqueda.contains(it) }) {
                coincidencias += audio
                if (enCarpetas && audio.carpeta != ruta) {
                    val hija = audio.carpeta.removePrefix(prefijo).substringBefore('/')
                    val direccion = "$prefijo$hija"
                    conteoHijas[direccion] = (conteoHijas[direccion] ?: 0) + 1
                }
            }
        }
        val hijas = if (enCarpetas) {
            carpetas.getValue(ruta).hijos.values.mapNotNull { hija ->
                conteoHijas[hija.ruta]?.let { hija.copy(cantidad = it) }
            }
        } else {
            emptyList()
        }
        return ResultadoBiblioteca(coincidencias, hijas, ruta)
    }

    companion object {
        private val ACENTOS = Regex("\\p{M}+")
        private val ESPACIOS = Regex("\\s+")

        private val ORDEN_ENTRADAS = object : Comparator<Entrada> {
            /**
             * La ordenación de canciones entrega dos entradas y recibe un número que indica su orden.
             * Compara título, artista y dirección para conservar los mismos desempates de la biblioteca.
             * Comprueba la cancelación en cada pareja: una ordenación grande puede detenerse al escribir.
             */
            override fun compare(primera: Entrada, segunda: Entrada): Int {
                comprobarContinuacion(0)

                val porNombre = primera.nombre.compareTo(segunda.nombre)
                if (porNombre != 0) {
                    return porNombre
                }

                val porArtista = primera.artista.compareTo(segunda.artista)
                if (porArtista != 0) {
                    return porArtista
                }

                return primera.direccion.compareTo(segunda.direccion)
            }
        }

        private val ORDEN_CARPETAS = object : Comparator<CarpetaPreparada> {
            /**
             * La ordenación de carpetas entrega dos hijas con sus nombres ya preparados.
             * Devuelve su orden por nombre y después por ruta, sin normalizar textos de nuevo.
             * La comprobación permite cancelar también cuando una carpeta tiene muchas hijas.
             */
            override fun compare(primera: CarpetaPreparada, segunda: CarpetaPreparada): Int {
                comprobarContinuacion(0)

                val porNombre = primera.nombre.compareTo(segunda.nombre)
                if (porNombre != 0) {
                    return porNombre
                }

                return primera.carpeta.ruta.compareTo(segunda.carpeta.ruta)
            }
        }

        /** Prepara un texto para comparar; la expresión de acentos se reutiliza en todas las canciones. */
        private fun normalizar(texto: String): String {
            return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replace(ACENTOS, "")
                .lowercase(Locale.ROOT)
                .trim()
        }

        /** Cada grupo de canciones comprueba si una búsqueda anterior fue cancelada al seguir escribiendo. */
        private fun comprobarContinuacion(posicion: Int) {
            if (posicion % 128 == 0 && Thread.currentThread().isInterrupted) {
                throw CancellationException("La búsqueda cambió o se cerró el selector.")
            }
        }
    }
}
