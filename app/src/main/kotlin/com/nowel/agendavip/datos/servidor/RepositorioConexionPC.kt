package com.nowel.agendavip.datos.servidor

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reúne los datos que identifican una conexión autorizada con la computadora.
 * El token es una llave aleatoria de acceso y queda en el archivo privado de esta cuenta.
 * No se incluye en las copias de seguridad ni se muestra en las pantallas.
 */
data class EnlacePC(
    val direccion: String,
    val huella: String,
    val token: String,
    val vinculoId: String,
    val dispositivoId: String,
    val servidorId: String,
    val nombreServidor: String,
)

/**
 * Conserva la conexión y las órdenes recibidas de una sola cuenta.
 * Un diario es la lista que permite recordar una operación después de un cierre inesperado.
 * El sincronizador lo guarda antes de cambiar la agenda para poder continuar con seguridad.
 */
class RepositorioConexionPC(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val espacio = EspacioPersonal(this.contexto)
    private val preferencias
        get() = espacio.preferencias("conexion_pc")

    /** El sincronizador la llama antes de capturar datos para comprobar el dueño original del vínculo. */
    internal fun exigirCuenta() {
        espacio.exigirVigente()
    }

    /** La pantalla y el sincronizador la llaman para leer la conexión de la cuenta actual. */
    fun leer(): EnlacePC? = synchronized(EspacioPersonal.cerrojoSesion) {
        val texto = preferencias.getString("enlace", null) ?: return@synchronized null
        val objeto = JSONObject(texto)
        EnlacePC(
            direccion = objeto.getString("direccion"),
            huella = objeto.getString("huella"),
            token = objeto.getString("token"),
            vinculoId = objeto.getString("vinculo_id"),
            dispositivoId = objeto.getString("dispositivo_id"),
            servidorId = objeto.getString("servidor_id"),
            nombreServidor = objeto.getString("nombre_servidor"),
        )
    }

    /** Devuelve el resultado de la última conexión para mostrarlo en Mi computadora. */
    fun ultimoEstado(): String = preferencias.getString(
        "estado",
        "Conecta tu computadora para administrar esta agenda.",
    ).orEmpty()

    /** Devuelve la hora de la última comunicación confirmada, o cero si nunca se conectó. */
    fun ultimaSincronizacion(): Long = preferencias.getLong("ultima_sincronizacion", 0L)

    /**
     * Recibe una conexión aprobada por ambas partes y la guarda junto a un diario vacío.
     * Vincular la llama al terminar el intercambio del código temporal.
     */
    internal fun guardar(enlace: EnlacePC) = synchronized(EspacioPersonal.cerrojoSesion) {
        val documento = JSONObject()
            .put("direccion", enlace.direccion)
            .put("huella", enlace.huella)
            .put("token", enlace.token)
            .put("vinculo_id", enlace.vinculoId)
            .put("dispositivo_id", enlace.dispositivoId)
            .put("servidor_id", enlace.servidorId)
            .put("nombre_servidor", enlace.nombreServidor)

        comprobarGuardado(
            preferencias.edit()
                .clear()
                .putString("enlace", documento.toString())
                .putString("diario", "[]")
                .putString("estado", "Conexión autorizada. Preparando la primera sincronización…")
                .commit(),
        )
    }

    /**
     * Recibe el enlace esperado y devuelve si esta cuenta aún tiene esa misma autorización.
     * Cada respuesta de red lo comprueba para ignorar una conexión cerrada o reemplazada.
     */
    internal fun vigente(enlace: EnlacePC): Boolean {
        return espacio.vigente() && leer() == enlace
    }

    /**
     * Recibe el vínculo comprobado y su nueva dirección. Devuelve la conexión actualizada.
     * Cambia solo ese dato: conserva el token, el diario y el número de sincronización.
     */
    internal fun actualizarDireccion(esperado: EnlacePC, direccion: String): EnlacePC =
        synchronized(EspacioPersonal.cerrojoSesion) {
            check(vigente(esperado)) { "La cuenta o la conexión cambiaron mientras buscabas la PC." }
            val actualizada = esperado.copy(direccion = ClientePC.validarDireccion(direccion))
            val documento = JSONObject(preferencias.getString("enlace", null))
                .put("direccion", actualizada.direccion)
            comprobarGuardado(preferencias.edit().putString("enlace", documento.toString()).commit())
            actualizada
        }

    /**
     * Devuelve si se puede buscar otra vez. Evita buscar cada quince segundos cuando estás fuera.
     * La búsqueda manual puede forzarlo para intentarlo de inmediato al regresar.
     */
    internal fun permitirBusqueda(forzar: Boolean): Boolean {
        val ahora = System.currentTimeMillis()
        val anterior = preferencias.getLong("ultima_busqueda", 0L)
        if (!forzar && ahora - anterior in 0L until 60_000L) {
            return false
        }
        comprobarGuardado(preferencias.edit().putLong("ultima_busqueda", ahora).commit())
        return true
    }

    /** Desconectar la llama para retirar la llave y las órdenes que todavía no se aplicaron. */
    internal fun desconectar() = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarGuardado(
            preferencias.edit()
                .clear()
                .putString("estado", "Computadora desconectada. Tu agenda permanece en el teléfono.")
                .commit(),
        )
    }

    /** Recibe el mensaje de una conexión y si terminó bien para actualizar su hora visible. */
    internal fun registrarEstado(mensaje: String, correcta: Boolean = false) {
        val editor = preferencias.edit().putString("estado", mensaje)
        if (correcta) {
            editor.putLong("ultima_sincronizacion", System.currentTimeMillis())
        }
        comprobarGuardado(editor.commit())
    }

    /**
     * Recibe órdenes nuevas y las guarda antes de que el sincronizador toque las tareas.
     * Las identidades repetidas conservan el resultado previo para evitar repetir un cambio.
     */
    internal fun recibir(ordenes: JSONArray) {
        require(ordenes.length() <= 30) { "La computadora envió demasiadas operaciones juntas." }
        if (ordenes.length() == 0) {
            return
        }

        val diario = diario()
        val totalAnterior = diario.length()
        val existentes = (0 until diario.length()).map {
            diario.getJSONObject(it).getJSONObject("orden").getString("id")
        }.toMutableSet()

        for (indice in 0 until ordenes.length()) {
            val orden = ordenes.getJSONObject(indice)
            val id = orden.getString("id")
            require(UUID.fromString(id).toString() == id) { "La operación no tiene una identidad válida." }

            if (existentes.add(id)) {
                diario.put(
                    JSONObject()
                        .put("orden", orden)
                        .put("estado", "pendiente")
                        .put("mensaje", ""),
                )
            }
        }

        require(diario.length() <= 500) { "Hay demasiadas confirmaciones pendientes. Sincroniza de nuevo." }
        if (diario.length() != totalAnterior) {
            guardarDiario(diario)
        }
    }

    /**
     * Reserva y devuelve el siguiente número de envío de este vínculo.
     * El servidor lo usa para que una petición lenta no sustituya datos más recientes.
     */
    internal fun siguienteSecuencia(): Long {
        val anterior = preferencias.getLong("secuencia", 0L)
        check(anterior < Long.MAX_VALUE) { "Vuelve a vincular la computadora para renovar la conexión." }
        val siguiente = anterior + 1L
        comprobarGuardado(preferencias.edit().putLong("secuencia", siguiente).commit())
        return siguiente
    }

    /** Devuelve los registros pendientes o interrumpidos que deben revisarse antes de publicar datos. */
    internal fun pendientes(): List<JSONObject> {
        val documento = diario()
        return (0 until documento.length()).map { documento.getJSONObject(it) }
            .filter { it.getString("estado") in listOf("pendiente", "aplicando") }
    }

    /**
     * Recibe el registro de una orden y sustituye su estado en el diario.
     * Aplicar la llama antes y después del cambio para conservar el avance en disco.
     */
    internal fun actualizarRegistro(registro: JSONObject) {
        val id = registro.getJSONObject("orden").getString("id")
        val anterior = diario()
        val nuevo = JSONArray()

        for (indice in 0 until anterior.length()) {
            val existente = anterior.getJSONObject(indice)
            nuevo.put(
                if (existente.getJSONObject("orden").getString("id") == id) registro else existente,
            )
        }
        guardarDiario(nuevo)
    }

    /** Devuelve las confirmaciones que la computadora aún no recibió de forma comprobada. */
    internal fun confirmaciones(): JSONArray {
        val resultado = JSONArray()
        val documento = diario()
        for (indice in 0 until documento.length()) {
            val registro = documento.getJSONObject(indice)
            if (registro.getString("estado") in listOf("aplicada", "conflicto", "error")) {
                resultado.put(
                    JSONObject()
                        .put("id", registro.getJSONObject("orden").getString("id"))
                        .put("estado", registro.getString("estado"))
                        .put("mensaje", registro.getString("mensaje")),
                )
            }
        }
        return resultado
    }

    /**
     * Retira solo las confirmaciones aceptadas por el servidor. La sincronización la llama
     * tras recibir una respuesta. Si no hubo confirmaciones, no toca el archivo.
     */
    internal fun confirmarRecibidas(confirmaciones: JSONArray) {
        if (confirmaciones.length() == 0) {
            return
        }

        val recibidas = (0 until confirmaciones.length()).map {
            confirmaciones.getJSONObject(it).getString("id")
        }.toSet()
        val nuevo = JSONArray()
        val documento = diario()
        for (indice in 0 until documento.length()) {
            val registro = documento.getJSONObject(indice)
            if (registro.getJSONObject("orden").getString("id") !in recibidas) {
                nuevo.put(registro)
            }
        }
        if (nuevo.length() != documento.length()) {
            guardarDiario(nuevo)
        }
    }

    /** Lee la lista privada del diario. Un archivo incorrecto informa del error y conserva su contenido. */
    private fun diario(): JSONArray = JSONArray(preferencias.getString("diario", "[]"))

    /** Guarda todos los registros juntos y espera la confirmación del archivo antes de continuar. */
    private fun guardarDiario(documento: JSONArray) {
        comprobarGuardado(preferencias.edit().putString("diario", documento.toString()).commit())
    }

    /** Recibe el resultado de Android y detiene la operación si el archivo no pudo guardarse. */
    private fun comprobarGuardado(guardado: Boolean) {
        check(guardado) { "Android no pudo guardar el estado de la conexión. Inténtalo de nuevo." }
    }

    companion object {

        /**
         * Devuelve una identidad de esta instalación creada una sola vez.
         * Vincular la envía para distinguir dos celulares, sin usar su número, IMEI o contactos.
         */
        internal fun identidadDispositivo(contexto: Context): String = synchronized(EspacioPersonal.cerrojoSesion) {
            val archivo = contexto.applicationContext.getSharedPreferences("identidad_conexion_pc", Context.MODE_PRIVATE)
            archivo.getString("id", null)?.let { return@synchronized it }
            val id = UUID.randomUUID().toString()
            check(archivo.edit().putString("id", id).commit()) {
                "No se pudo preparar la identidad del dispositivo."
            }
            id
        }
    }
}
