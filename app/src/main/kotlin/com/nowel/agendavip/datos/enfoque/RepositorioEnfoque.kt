package com.nowel.agendavip.datos.enfoque

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.dominio.enfoque.EventoEnfoque
import com.nowel.agendavip.dominio.enfoque.TipoEventoEnfoque
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** Un error de lectura o escritura conserva la agenda anterior y permite explicarlo en pantalla. */
class ErrorDatosEnfoque(mensaje: String, causa: Throwable? = null) : IllegalStateException(mensaje, causa)

/**
 * Guarda actividades e historial de Enfoque en un archivo privado de la cuenta activa.
 * Recibe Android. Las pantallas consultan aquí y GestorEnfoque realiza los cambios.
 * JSON es texto organizado en campos y listas; se guardan actividad y evento juntos.
 */
class RepositorioEnfoque(contexto: Context) {

    private val espacio = EspacioPersonal(contexto)
    private val preferencias
        get() = espacio.preferencias("enfoque_local")

    // La misma cuenta puede cambiar desde otra pantalla, una alarma o un respaldo.
    // La lectura comprueba siempre el texto guardado bajo el cerrojo de la sesión.
    private var lecturaReciente: Pair<String, Documento>? = null

    private data class Documento(
        val actividades: List<ActividadEnfoque>,
        val eventos: List<EventoEnfoque>,
    )

    /** Devuelve las actividades ordenadas por su aviso. Enfoque y Agenda consultan fuera de pantalla. */
    fun listar(): List<ActividadEnfoque> = synchronized(EspacioPersonal.cerrojoSesion) {
        leer().actividades.sortedWith(compareBy<ActividadEnfoque> { it.avisoEnEpochMs }.thenBy { it.titulo })
    }

    /** Recibe el código y devuelve su actividad, o null si no existe en esta cuenta. */
    fun obtener(id: String): ActividadEnfoque? = synchronized(EspacioPersonal.cerrojoSesion) {
        leer().actividades.firstOrNull { it.id == id }
    }

    /** Devuelve los hechos más antiguos primero; Agenda filtra después por día, semana o mes. */
    fun eventos(): List<EventoEnfoque> = synchronized(EspacioPersonal.cerrojoSesion) {
        leer().eventos.sortedBy { it.ocurrioEn }
    }

    /**
     * Recibe la actividad y un evento opcional; GestorEnfoque la llama después de validar la acción.
     * Confirma ambos datos en un mismo documento. Sin evento solo actualiza el reloj de recuperación.
     * No permite dos actividades en curso y conserva todos los eventos; no borra historial por antigüedad.
     */
    fun guardar(
        actividad: ActividadEnfoque,
        tipo: TipoEventoEnfoque? = null,
        detalle: String = "",
    ) = synchronized(EspacioPersonal.cerrojoSesion) {
        validar(actividad)
        require(detalle.length <= 500) { "La explicación del evento es demasiado larga." }
        val anterior = leer()
        val actividades = anterior.actividades.filterNot { it.id == actividad.id } + actividad
        require(actividades.size <= 10000) { "Esta cuenta alcanzó el límite de actividades guardadas." }
        require(actividades.count { it.estado == EstadoEnfoque.EN_CURSO } <= 1) {
            "Ya hay otra actividad en curso. Paúsala antes de comenzar esta."
        }
        val eventos = if (tipo == null) {
            anterior.eventos
        } else {
            anterior.eventos + EventoEnfoque(
                actividadId = actividad.id,
                titulo = actividad.titulo,
                tipo = tipo,
                ocurrioEn = actividad.actualizadaEn,
                detalle = detalle,
                trabajadoAcumuladoMs = actividad.acumuladoTrabajadoMs,
            )
        }
        escribir(Documento(actividades, eventos))
    }

    /**
     * Devuelve las actividades y los eventos de la cuenta; listar, obtener, eventos y guardar
     * la llaman con el cerrojo de la sesión tomado. Si el texto es idéntico al último que
     * validó, reutiliza el resultado. Un documento nuevo se valida antes de recordarlo y
     * un documento dañado sigue produciendo un error.
     */
    private fun leer(): Documento {
        espacio.exigirVigente()

        try {
            val texto = preferencias.getString(CLAVE, null)
            if (texto == null) {
                lecturaReciente = null
                return Documento(emptyList(), emptyList())
            }

            val anterior = lecturaReciente
            if (anterior != null && anterior.first == texto) {
                return anterior.second
            }

            val documento = interpretarDocumento(texto)
            lecturaReciente = texto to documento
            return documento
        } catch (error: Exception) {
            if (error is ErrorDatosEnfoque) {
                throw error
            }
            throw ErrorDatosEnfoque(
                "No se pudieron leer las actividades de Enfoque. Los datos se conservaron; no borres los datos de la app.",
                error,
            )
        }
    }

    /**
     * Recibe el texto de una copia y comprueba todas sus actividades y eventos sin guardarlos.
     * Respaldo la llama antes de sustituir los datos actuales; no devuelve información.
     */
    fun validarCopia(texto: String) {
        interpretarDocumento(texto)
    }

    /**
     * Convierte texto JSON en datos revisados. Leer y Validar copia comparten estas comprobaciones
     * para que importar un archivo no acepte datos que después no se puedan abrir.
     */
    private fun interpretarDocumento(texto: String): Documento {
        val lector = JSONTokener(texto)
        val raiz = lector.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("El documento no es un objeto.")
        require(lector.nextClean() == '\u0000' && entero(raiz, "version") == 1L)
        val actividadesJson = raiz.getJSONArray("actividades")
        val eventosJson = raiz.getJSONArray("eventos")
        require(actividadesJson.length() <= 10000)
        val actividades = (0 until actividadesJson.length()).map {
            leerActividad(actividadesJson.getJSONObject(it))
        }
        val eventos = (0 until eventosJson.length()).map {
            leerEvento(eventosJson.getJSONObject(it))
        }

        require(actividades.map { it.id }.distinct().size == actividades.size)
        require(eventos.map { it.id }.distinct().size == eventos.size)
        require(actividades.count { it.estado == EstadoEnfoque.EN_CURSO } <= 1)
        val identidades = actividades.map { it.id }.toHashSet()
        require(eventos.all { it.actividadId in identidades })

        return Documento(actividades, eventos)
    }

    /**
     * Recibe un documento válido y espera su confirmación en disco.
     * Si Android no confirma, restaura la copia anterior también en memoria antes de informar del fallo.
     * El guardado mantiene un respaldo de la versión válida anterior, sin restaurarlo a escondidas.
     */
    private fun escribir(documento: Documento) {
        espacio.exigirVigente()
        val opciones = preferencias
        val anterior = opciones.getString(CLAVE, null)
        val texto = JSONObject()
            .put("version", 1)
            .put("actividades", JSONArray(documento.actividades.map(::escribirActividad)))
            .put("eventos", JSONArray(documento.eventos.map(::escribirEvento)))
            .toString()
        val editor = opciones.edit().putString(CLAVE, texto)

        if (anterior != null) {
            editor.putString("respaldo_ultimo_valido", anterior)
        }

        try {
            if (!editor.commit()) {
                throw ErrorDatosEnfoque("Android no pudo confirmar el guardado de Enfoque. Vuelve a intentarlo.")
            }
        } catch (error: RuntimeException) {
            val interrumpido = Thread.interrupted()

            try {
                val restauracion = opciones.edit()

                if (anterior == null) {
                    restauracion.remove(CLAVE)
                } else {
                    restauracion.putString(CLAVE, anterior)
                }

                if (!restauracion.commit()) {
                    error.addSuppressed(IllegalStateException("No se pudo confirmar la restauración anterior."))
                }
            } catch (fallo: RuntimeException) {
                error.addSuppressed(fallo)
            } finally {
                if (interrumpido) {
                    Thread.currentThread().interrupt()
                }
            }

            throw ErrorDatosEnfoque("No se pudo guardar Enfoque. Revisa la actividad antes de repetir la acción.", error)
        }
    }

    /** Recibe una actividad y comprueba sus límites, fechas y coherencia antes de leerla o guardarla. */
    private fun validar(actividad: ActividadEnfoque) {
        require(esId(actividad.id))
        require(actividad.titulo.isNotBlank() && actividad.titulo.length <= 160)
        require(actividad.creadaEn > 0L && actividad.actualizadaEn > 0L && actividad.avisoEnEpochMs > 0L)
        require(actividad.duracionMinutos in 1..480)
        require(actividad.restanteMs in 0L..480L * 60_000L)
        require(actividad.acumuladoTrabajadoMs >= 0L)
        require(actividad.musicaNombre.length <= 300)
        require(actividad.musicaUri == null || actividad.musicaUri.length <= 4000)

        if (actividad.estado == EstadoEnfoque.EN_CURSO) {
            require(actividad.finEnEpochMs != null && actividad.finEnEpochMs > 0L)
            require(actividad.finEnElapsedMs != null && actividad.finEnElapsedMs >= 0L)
            require(actividad.restanteMs > 0L)
        } else {
            require(actividad.finEnEpochMs == null && actividad.finEnElapsedMs == null)
        }
    }

    /** Recibe campos JSON y devuelve la actividad conservando los tipos exactos de cada dato. */
    private fun leerActividad(objeto: JSONObject): ActividadEnfoque {
        val minutos = entero(objeto, "duracionMinutos")
        val arranque = entero(objeto, "arranqueSistema")
        require(minutos in 1L..480L && arranque in -1L..Int.MAX_VALUE.toLong())
        val actividad = ActividadEnfoque(
            id = texto(objeto, "id"),
            titulo = texto(objeto, "titulo"),
            creadaEn = entero(objeto, "creadaEn"),
            avisoEnEpochMs = entero(objeto, "avisoEnEpochMs"),
            duracionMinutos = minutos.toInt(),
            estado = EstadoEnfoque.valueOf(texto(objeto, "estado")),
            restanteMs = entero(objeto, "restanteMs"),
            finEnEpochMs = opcionalEntero(objeto, "finEnEpochMs"),
            finEnElapsedMs = opcionalEntero(objeto, "finEnElapsedMs"),
            arranqueSistema = arranque.toInt(),
            actualizadaEn = entero(objeto, "actualizadaEn"),
            acumuladoTrabajadoMs = entero(objeto, "acumuladoTrabajadoMs"),
            musicaUri = if (objeto.isNull("musicaUri")) null else texto(objeto, "musicaUri"),
            musicaNombre = texto(objeto, "musicaNombre"),
        )
        validar(actividad)
        return actividad
    }

    /** Recibe una actividad y devuelve sus campos organizados para el guardado conjunto. */
    private fun escribirActividad(actividad: ActividadEnfoque): JSONObject = JSONObject()
        .put("id", actividad.id)
        .put("titulo", actividad.titulo)
        .put("creadaEn", actividad.creadaEn)
        .put("avisoEnEpochMs", actividad.avisoEnEpochMs)
        .put("duracionMinutos", actividad.duracionMinutos)
        .put("estado", actividad.estado.name)
        .put("restanteMs", actividad.restanteMs)
        .put("finEnEpochMs", actividad.finEnEpochMs ?: JSONObject.NULL)
        .put("finEnElapsedMs", actividad.finEnElapsedMs ?: JSONObject.NULL)
        .put("arranqueSistema", actividad.arranqueSistema)
        .put("actualizadaEn", actividad.actualizadaEn)
        .put("acumuladoTrabajadoMs", actividad.acumuladoTrabajadoMs)
        .put("musicaUri", actividad.musicaUri ?: JSONObject.NULL)
        .put("musicaNombre", actividad.musicaNombre)

    /** Recibe un evento JSON y devuelve un hecho del historial sin inventar datos ausentes. */
    private fun leerEvento(objeto: JSONObject): EventoEnfoque {
        val evento = EventoEnfoque(
            id = texto(objeto, "id"),
            actividadId = texto(objeto, "actividadId"),
            titulo = texto(objeto, "titulo"),
            tipo = TipoEventoEnfoque.valueOf(texto(objeto, "tipo")),
            ocurrioEn = entero(objeto, "ocurrioEn"),
            detalle = texto(objeto, "detalle"),
            trabajadoAcumuladoMs = if (objeto.has("trabajadoAcumuladoMs")) entero(objeto, "trabajadoAcumuladoMs") else 0L,
        )
        require(esId(evento.id) && esId(evento.actividadId))
        require(evento.titulo.isNotBlank() && evento.titulo.length <= 160)
        require(evento.ocurrioEn > 0L && evento.detalle.length <= 500 && evento.trabajadoAcumuladoMs >= 0L)
        return evento
    }

    /** Recibe un evento y devuelve el objeto JSON que se guarda con la actividad. */
    private fun escribirEvento(evento: EventoEnfoque): JSONObject = JSONObject()
        .put("id", evento.id)
        .put("actividadId", evento.actividadId)
        .put("titulo", evento.titulo)
        .put("tipo", evento.tipo.name)
        .put("ocurrioEn", evento.ocurrioEn)
        .put("detalle", evento.detalle)
        .put("trabajadoAcumuladoMs", evento.trabajadoAcumuladoMs)

    /** Recibe objeto y campo; devuelve texto real, rechazando números convertidos a texto. */
    private fun texto(objeto: JSONObject, campo: String): String = objeto.get(campo) as? String
        ?: throw IllegalArgumentException("El campo $campo no contiene texto.")

    /** Recibe objeto y campo; devuelve un entero largo sin aceptar decimales ni cadenas. */
    private fun entero(objeto: JSONObject, campo: String): Long {
        val numero = objeto.get(campo)
        require(numero is Long || numero is Int)
        return (numero as Number).toLong()
    }

    /** Recibe un campo de tiempo y devuelve su entero o null cuando no existe ese momento. */
    private fun opcionalEntero(objeto: JSONObject, campo: String): Long? =
        if (objeto.isNull(campo)) null else entero(objeto, campo)

    /** Recibe un código y devuelve si es un UUID canónico, el identificador propio de una actividad. */
    private fun esId(id: String): Boolean = try {
        UUID.fromString(id).toString() == id
    } catch (_: IllegalArgumentException) {
        false
    }

    private companion object {
        const val CLAVE = "documento_v1"
    }
}