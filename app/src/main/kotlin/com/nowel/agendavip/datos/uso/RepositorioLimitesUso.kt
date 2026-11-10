package com.nowel.agendavip.datos.uso

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.uso.LimiteAplicacion
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate

/**
 * Conserva objetivos y fechas de aviso en el espacio privado de cada cuenta.
 * Solo guarda las aplicaciones elegidas: no almacena el historial completo del teléfono.
 * La pantalla modifica objetivos; el servicio registra que ya avisó para no repetirlo todo el día.
 */
class RepositorioLimitesUso(contexto: Context) {

    private val espacio = EspacioPersonal(contexto)

    private val preferencias
        get() = espacio.preferencias("limites_uso")

    /** Devuelve los límites guardados y rechaza datos dañados sin sustituirlos por una lista vacía. */
    fun listar(): List<LimiteAplicacion> = synchronized(EspacioPersonal.cerrojoSesion) {
        espacio.exigirVigente()
        val texto = preferencias.getString("limites", null)
            ?: return@synchronized emptyList()
        try {
            leerLista(texto)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudieron leer tus límites. Sus datos se conservaron.",
                error
            )
        }
    }

    /** Recibe un objetivo válido y lo guarda sin cambiar los objetivos de otras aplicaciones. */
    fun guardar(limite: LimiteAplicacion) = synchronized(EspacioPersonal.cerrojoSesion) {
        validar(limite)
        val todos = listar().filterNot { it.paquete == limite.paquete } + limite
        require(todos.size <= 200) { "Puedes configurar hasta 200 aplicaciones." }
        guardarLista(todos)
    }

    /** Recibe una aplicación y elimina únicamente su objetivo, no la aplicación del teléfono. */
    fun eliminar(paquete: String) = synchronized(EspacioPersonal.cerrojoSesion) {
        guardarLista(listar().filterNot { it.paquete == paquete })
    }

    /** Devuelve si ya se emitió el aviso de esa aplicación en la fecha indicada. */
    fun yaAvisado(paquete: String, fecha: LocalDate): Boolean =
        synchronized(EspacioPersonal.cerrojoSesion) {
            espacio.exigirVigente()
            preferencias.getString("aviso_" + paquete, null) == fecha.toString()
        }

    /** Recibe aplicación y día; confirma el registro para permitir como máximo un aviso diario. */
    fun marcarAvisado(paquete: String, fecha: LocalDate) =
        synchronized(EspacioPersonal.cerrojoSesion) {
            espacio.exigirVigente()
            val clave = "aviso_" + paquete
            val anterior = preferencias.getString(clave, null)
            if (!preferencias.edit().putString(clave, fecha.toString()).commit()) {
                preferencias.edit().putString(clave, anterior).commit()
                error("Android no pudo guardar que este límite ya fue avisado.")
            }
        }

    /** Convierte la lista a texto JSON, un formato de campos y valores, y confirma su guardado. */
    private fun guardarLista(limites: List<LimiteAplicacion>) {
        espacio.exigirVigente()
        val lista = JSONArray()
        limites.forEach { limite ->
            lista.put(
                JSONObject()
                    .put("paquete", limite.paquete)
                    .put("nombre", limite.nombre)
                    .put("minutos", limite.minutosDiarios)
                    .put("mensaje", limite.mensaje)
                    .put("hablar", limite.hablar)
                    .put("habilitado", limite.habilitado)
            )
        }
        val anterior = preferencias.getString("limites", null)
        if (!preferencias.edit().putString("limites", lista.toString()).commit()) {
            preferencias.edit().putString("limites", anterior).commit()
            error("Android no pudo guardar los límites. Vuelve a intentarlo.")
        }
    }

    companion object {

        /**
         * Respaldo recibe el valor JSON del campo limites antes de importar una copia.
         * Valida su formato, tipos y objetivos sin cambiar preferencias ni necesitar una cuenta abierta.
         * Devuelve Unit, es decir, ningún dato; un error impide importar la copia.
         */
        fun validarCopia(texto: String) {
            leerLista(texto)
        }

        /** Convierte el JSON válido en objetivos; la lectura normal y la importación comparten las mismas reglas. */
        private fun leerLista(texto: String): List<LimiteAplicacion> {
            val lector = JSONTokener(texto)
            val lista = lector.nextValue() as? JSONArray
                ?: throw IllegalArgumentException("Los límites de uso no contienen una lista válida.")
            require(lector.nextClean() == '\u0000' && lista.length() <= 200)
            val resultado = (0 until lista.length()).map { indice ->
                val dato = lista.getJSONObject(indice)
                require(listOf("paquete", "nombre", "mensaje").all { dato.get(it) is String })
                require(dato.get("hablar") is Boolean && dato.get("habilitado") is Boolean)
                val numero = dato.get("minutos") as? Number
                    ?: throw IllegalArgumentException("Los minutos del límite no son un número.")
                require(numero.toDouble().isFinite() && numero.toDouble() == numero.toInt().toDouble())
                LimiteAplicacion(
                    paquete = dato.getString("paquete"),
                    nombre = dato.getString("nombre"),
                    minutosDiarios = numero.toInt(),
                    mensaje = dato.getString("mensaje"),
                    hablar = dato.getBoolean("hablar"),
                    habilitado = dato.getBoolean("habilitado")
                ).also(::validar)
            }
            require(resultado.map { it.paquete }.distinct().size == resultado.size)
            return resultado.sortedBy { it.nombre.lowercase() }
        }

        /** Comprueba los campos antes de guardarlos o usarlos para emitir un mensaje. */
        private fun validar(limite: LimiteAplicacion) {
            require(limite.paquete.matches(Regex("[a-zA-Z0-9_.]+")) && limite.paquete.length <= 300)
            require(limite.nombre.isNotBlank() && limite.nombre.length <= 200)
            require(limite.minutosDiarios in 1..1440) { "Elige de 1 a 1440 minutos diarios." }
            require(limite.mensaje.length <= 400) { "El mensaje puede tener hasta 400 caracteres." }
        }
    }
}
